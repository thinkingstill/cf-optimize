package com.wlh.cfoptimize.core

import kotlin.random.Random

/**
 * IPv4 / CIDR 工具。全部用 Long 承载无符号 32 位，避免 Int 的符号位问题。
 */
object Ip {

    data class Range(val cidr: String, val network: Long, val prefix: Int)

    fun parseRanges(text: String): List<Range> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { parseRange(it) }
            .toList()

    fun parseRange(line: String): Range? {
        val slash = line.indexOf('/')
        if (slash <= 0) return null
        val ip = parseIp(line.substring(0, slash)) ?: return null
        val prefix = line.substring(slash + 1).toIntOrNull() ?: return null
        if (prefix !in 0..32) return null
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        return Range(line, ip and mask, prefix)
    }

    fun parseIp(text: String): Long? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n.toLong()
        }
        return v
    }

    fun format(v: Long): String =
        "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"

    /** 4 字节表示，用于 InetAddress.getByAddress，保证一定是 IPv4。 */
    fun toBytes(ip: String): ByteArray? {
        val v = parseIp(ip) ?: return null
        return byteArrayOf(
            ((v shr 24) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            (v and 0xFF).toByte()
        )
    }

    /**
     * 展开为 /24 抽样块的网络地址。
     * prefix <= 24 的段按 256 步长切分（与脚本一致）；更细的段整体算一个块。
     */
    fun expandSlash24(ranges: List<Range>): LongArray {
        val out = ArrayList<Long>()
        for (r in ranges) {
            if (r.prefix <= 24) {
                val size = 1L shl (32 - r.prefix)
                val end = r.network + size
                var n = r.network
                while (n < end) {
                    out.add(n)
                    n += 256
                }
            } else {
                out.add(r.network)
            }
        }
        return out.toLongArray()
    }

    /**
     * 无偏抽样：全量 Fisher-Yates 洗牌后取前 count 个 /24，每个块内随机主机位 2..254。
     * 与脚本的 `2 + int(rand(253))` 一致。
     */
    fun sampleBlocks(blocks: LongArray, count: Int, random: Random): List<String> {
        if (blocks.isEmpty()) return emptyList()
        val n = count.coerceIn(1, blocks.size)
        val copy = blocks.copyOf()
        for (i in copy.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val t = copy[i]
            copy[i] = copy[j]
            copy[j] = t
        }
        val out = ArrayList<String>(n)
        for (i in 0 until n) {
            val host = 2 + random.nextInt(253)
            out.add(format(copy[i] + host))
        }
        return out
    }
}
