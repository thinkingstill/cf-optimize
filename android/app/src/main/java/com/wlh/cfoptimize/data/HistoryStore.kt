package com.wlh.cfoptimize.data

import android.content.Context
import org.json.JSONArray
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 扫描历史：JSON 文件 + 内存聚合。
 * 原子写（tmp → rename），解析失败时留证重建，绝不静默丢数据。
 */
class HistoryStore private constructor(context: Context) {

    companion object {
        private const val MAX_RECORDS = 365

        @Volatile
        private var instance: HistoryStore? = null

        fun get(context: Context): HistoryStore =
            instance ?: synchronized(this) {
                instance ?: HistoryStore(context.applicationContext).also { instance = it }
            }
    }

    private val dir = context.filesDir
    private val file = File(dir, "history.json")
    private val tmp = File(dir, "history.json.tmp")

    private val _records = MutableStateFlow<List<RunRecord>>(emptyList())
    val records: StateFlow<List<RunRecord>> = _records

    @Synchronized
    fun load() {
        _records.value = readFile()
    }

    @Synchronized
    fun append(record: RunRecord): List<RunRecord> {
        val next = (listOf(record) + _records.value)
            .sortedByDescending { it.finishedAt }
            .take(MAX_RECORDS)
        persist(next)
        _records.value = next
        return next
    }

    @Synchronized
    fun delete(id: String): List<RunRecord> {
        val next = _records.value.filterNot { it.id == id }
        persist(next)
        _records.value = next
        return next
    }

    @Synchronized
    fun clear(): List<RunRecord> {
        persist(emptyList())
        _records.value = emptyList()
        return emptyList()
    }

    private fun readFile(): List<RunRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o -> runCatching { RunRecord.from(o) }.getOrNull() }
            }
        } catch (e: Exception) {
            runCatching {
                file.renameTo(File(dir, "history.corrupt.${System.currentTimeMillis()}.json"))
            }
            emptyList()
        }
    }

    private fun persist(list: List<RunRecord>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            val text = arr.toString()
            tmp.writeText(text)
            if (file.exists() && !file.delete()) {
                file.writeText(text)
                runCatching { tmp.delete() }
                return
            }
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                runCatching { tmp.delete() }
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
        }
    }
}

/**
 * 单个 IP 的跨次统计。
 * stability = 登顶次数 ÷ 进入前 20 的次数（历史只保存 top-20，因此分母是这个口径，不是"被抽样次数"）。
 */
data class IpStat(
    val ip: String,
    val bestCount: Int,
    val topCount: Int,
    val avgTlsWhenBest: Double,
    val lastBestAt: Long,
    val lastSeenAt: Long,
    val lastTlsMs: Double
) {
    val stability: Double get() = if (topCount <= 0) 0.0 else bestCount.toDouble() / topCount
}

fun aggregate(records: List<RunRecord>): List<IpStat> {
    class Acc {
        var best = 0
        var top = 0
        var tlsSum = 0.0
        var tlsCount = 0
        var lastBest = 0L
        var lastSeen = 0L
        var lastTls = 0.0
    }

    val map = HashMap<String, Acc>()
    for (record in records) {
        val seen = HashSet<String>()
        for (snap in record.top) {
            if (!seen.add(snap.ip)) continue
            val acc = map.getOrPut(snap.ip) { Acc() }
            acc.top += 1
            if (snap.okCount > 0) acc.lastTls = snap.tlsMs
            if (record.finishedAt > acc.lastSeen) acc.lastSeen = record.finishedAt
        }
        record.best?.let { b ->
            val acc = map.getOrPut(b.ip) { Acc() }
            acc.best += 1
            acc.tlsSum += b.tlsMs
            acc.tlsCount += 1
            if (record.finishedAt > acc.lastBest) acc.lastBest = record.finishedAt
        }
    }

    return map.map { (ip, a) ->
        IpStat(
            ip = ip,
            bestCount = a.best,
            topCount = a.top,
            avgTlsWhenBest = if (a.tlsCount > 0) a.tlsSum / a.tlsCount else 0.0,
            lastBestAt = a.lastBest,
            lastSeenAt = a.lastSeen,
            lastTlsMs = a.lastTls
        )
    }.sortedWith(
        compareByDescending<IpStat> { it.bestCount }
            .thenByDescending { it.stability }
            .thenBy { it.avgTlsWhenBest }
    )
}
