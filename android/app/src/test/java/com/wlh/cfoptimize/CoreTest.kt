package com.wlh.cfoptimize

import com.wlh.cfoptimize.core.FailureKind
import com.wlh.cfoptimize.core.Ip
import com.wlh.cfoptimize.core.IpResult
import com.wlh.cfoptimize.core.ResultOrder
import com.wlh.cfoptimize.data.IpSnapshot
import com.wlh.cfoptimize.data.RunRecord
import com.wlh.cfoptimize.data.aggregate
import com.wlh.cfoptimize.export.Exporters
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {

    private val ranges = """
        173.245.48.0/20
        103.21.244.0/22
        104.16.0.0/13
        172.64.0.0/13
        104.24.0.0/14
    """.trimIndent()

    @Test
    fun parseRangeComputesNetwork() {
        val r = Ip.parseRange("104.16.7.9/13")
        assertNotNull(r)
        assertEquals(13, r!!.prefix)
        assertEquals("104.16.0.0", Ip.format(r.network))
    }

    @Test
    fun parseRangeRejectsGarbage() {
        assertNull(Ip.parseRange("not-an-ip/24"))
        assertNull(Ip.parseRange("104.16.0.0/33"))
        assertNull(Ip.parseRange("104.16.0.0"))
    }

    @Test
    fun expandSlash20Gives16Blocks() {
        val blocks = Ip.expandSlash24(Ip.parseRanges("173.245.48.0/20"))
        assertEquals(16, blocks.size)
        assertEquals("173.245.48.0", Ip.format(blocks.first()))
        assertEquals("173.245.63.0", Ip.format(blocks.last()))
    }

    @Test
    fun expandSlash13Gives2048Blocks() {
        val blocks = Ip.expandSlash24(Ip.parseRanges("104.16.0.0/13"))
        assertEquals(2048, blocks.size)
        assertEquals("104.16.0.0", Ip.format(blocks.first()))
        assertEquals("104.23.255.0", Ip.format(blocks.last()))
    }

    @Test
    fun expandSingleSlash24() {
        val blocks = Ip.expandSlash24(Ip.parseRanges("104.16.0.0/24"))
        assertEquals(1, blocks.size)
    }

    @Test
    fun expandSlash25KeepsOneBlock() {
        val blocks = Ip.expandSlash24(Ip.parseRanges("104.16.0.128/25"))
        assertEquals(1, blocks.size)
        assertEquals("104.16.0.128", Ip.format(blocks.first()))
    }

    @Test
    fun realListYields5956Blocks() {
        val text = javaClass.classLoader!!.getResourceAsStream("ips-v4.txt")
        if (text == null) return
        val content = text.bufferedReader().use { it.readText() }
        assertEquals(5956, Ip.expandSlash24(Ip.parseRanges(content)).size)
    }

    @Test
    fun sampleIsUniqueAndInRange() {
        val blocks = Ip.expandSlash24(Ip.parseRanges(ranges))
        val ips = Ip.sampleBlocks(blocks, 400, Random(42))
        assertEquals(400, ips.size)
        assertEquals(400, ips.toSet().size)

        val allowed = blocks.toHashSet()
        for (ip in ips) {
            val v = Ip.parseIp(ip)!!
            val octets = ip.split('.')
            val host = octets[3].toInt()
            assertTrue("host octet out of 2..254: $ip", host in 2..254)
            val blockBase = v and 0xFFFFFF00L
            assertTrue("not in sampled blocks: $ip", allowed.contains(blockBase))
        }
    }

    @Test
    fun sampleCountIsClampedToBlockCount() {
        val blocks = Ip.expandSlash24(Ip.parseRanges("104.16.0.0/24"))
        assertEquals(1, Ip.sampleBlocks(blocks, 100, Random(1)).size)
    }

    @Test
    fun sortPutsZeroLossFastestFirstAndDeadLast() {
        val lossy = IpResult("1.1.1.1", 3, 1, 100.0, 300.0, 200, null)
        val zeroSlow = IpResult("2.2.2.2", 3, 3, 100.0, 400.0, 200, null)
        val zeroFast = IpResult("3.3.3.3", 3, 3, 100.0, 200.0, 200, null)
        val dead = IpResult("4.4.4.4", 3, 0, 0.0, 0.0, null, FailureKind.TIMEOUT)

        val sorted = listOf(lossy, zeroSlow, zeroFast, dead)
            .sortedWith(ResultOrder)
            .map { it.ip }

        assertEquals(listOf("3.3.3.3", "2.2.2.2", "1.1.1.1", "4.4.4.4"), sorted)
    }

    @Test
    fun lossPctAndZeroLoss() {
        val r = IpResult("1.1.1.1", 3, 2, 10.0, 20.0, 200, null)
        assertEquals(33.333333, r.lossPct, 0.001)
        assertTrue(r.usable)
        assertTrue(!r.zeroLoss)
    }

    @Test
    fun csvMatchesScriptHeaderAndFormat() {
        val r = IpResult("104.17.153.99", 3, 3, 179.514, 364.70, 200, null)
        val csv = Exporters.csv(listOf(r))
        val lines = csv.trimEnd('\n').split('\n')
        assertEquals("IP,失败率(%),平均TLS握手(ms),平均TCP连接(ms),成功次数", lines[0])
        assertEquals("104.17.153.99,0.0,364.70,179.51,3", lines[1])
    }

    @Test
    fun csvSkipsUnusable() {
        val dead = IpResult("4.4.4.4", 3, 0, 0.0, 0.0, null, FailureKind.TIMEOUT)
        val csv = Exporters.csv(listOf(dead))
        assertEquals(1, csv.trimEnd('\n').split('\n').size)
    }

    @Test
    fun aggregateCountsBestAndTopOccurrences() {
        fun snap(ip: String, tls: Double) = IpSnapshot(ip, 0.0, tls, 100.0, 3, 200)
        fun record(id: String, at: Long, best: IpSnapshot, top: List<IpSnapshot>) = RunRecord(
            id, at, "a.com", "WIFI", 1000, 3, 100, 4000, 1000, 340, 60, 20000, "remote", best, top
        )

        val r1 = record("1", 1000, snap("A", 300.0), listOf(snap("A", 300.0), snap("B", 400.0)))
        val r2 = record("2", 2000, snap("A", 320.0), listOf(snap("A", 320.0), snap("B", 380.0)))
        val r3 = record("3", 3000, snap("B", 350.0), listOf(snap("B", 350.0), snap("A", 500.0)))

        val stats = aggregate(listOf(r1, r2, r3))
        val a = stats.first { it.ip == "A" }
        val b = stats.first { it.ip == "B" }

        assertEquals(2, a.bestCount)
        assertEquals(3, a.topCount)
        assertEquals(2.0 / 3.0, a.stability, 0.001)
        assertEquals(310.0, a.avgTlsWhenBest, 0.001)
        assertEquals(3000L, a.lastSeenAt)

        assertEquals(1, b.bestCount)
        assertEquals(3, b.topCount)
        assertEquals(listOf("A", "B"), stats.map { it.ip })
    }
}
