package com.wlh.cfoptimize.data

import com.wlh.cfoptimize.core.IpResult
import org.json.JSONArray
import org.json.JSONObject

data class IpSnapshot(
    val ip: String,
    val lossPct: Double,
    val tlsMs: Double,
    val tcpMs: Double,
    val okCount: Int,
    val httpCode: Int?
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ip", ip)
        put("loss", round(lossPct))
        put("tls", round(tlsMs))
        put("tcp", round(tcpMs))
        put("ok", okCount)
        if (httpCode != null) put("code", httpCode)
    }

    companion object {
        private fun round(v: Double) = Math.round(v * 100.0) / 100.0

        fun from(o: JSONObject): IpSnapshot = IpSnapshot(
            ip = o.optString("ip"),
            lossPct = o.optDouble("loss", 100.0),
            tlsMs = o.optDouble("tls", 0.0),
            tcpMs = o.optDouble("tcp", 0.0),
            okCount = o.optInt("ok", 0),
            httpCode = if (o.has("code")) o.optInt("code") else null
        )

        fun of(r: IpResult): IpSnapshot = IpSnapshot(
            ip = r.ip,
            lossPct = round(r.lossPct),
            tlsMs = round(r.avgTlsMs),
            tcpMs = round(r.avgTcpMs),
            okCount = r.okCount,
            httpCode = r.httpCode
        )
    }
}

data class RunRecord(
    val id: String,
    val finishedAt: Long,
    val domain: String,
    val networkType: String,
    val sample: Int,
    val repeat: Int,
    val concurrency: Int,
    val timeoutMs: Int,
    val tested: Int,
    val valid: Int,
    val zeroLoss: Int,
    val durationMs: Long,
    val rangesSource: String,
    val best: IpSnapshot?,
    val top: List<IpSnapshot>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("finishedAt", finishedAt)
        put("domain", domain)
        put("networkType", networkType)
        put("sample", sample)
        put("repeat", repeat)
        put("concurrency", concurrency)
        put("timeoutMs", timeoutMs)
        put("tested", tested)
        put("valid", valid)
        put("zeroLoss", zeroLoss)
        put("durationMs", durationMs)
        put("rangesSource", rangesSource)
        best?.let { put("best", it.toJson()) }
        put("top", JSONArray().apply { top.forEach { put(it.toJson()) } })
    }

    companion object {
        fun from(o: JSONObject): RunRecord = RunRecord(
            id = o.optString("id"),
            finishedAt = o.optLong("finishedAt"),
            domain = o.optString("domain"),
            networkType = o.optString("networkType", "UNKNOWN"),
            sample = o.optInt("sample"),
            repeat = o.optInt("repeat"),
            concurrency = o.optInt("concurrency"),
            timeoutMs = o.optInt("timeoutMs"),
            tested = o.optInt("tested"),
            valid = o.optInt("valid"),
            zeroLoss = o.optInt("zeroLoss"),
            durationMs = o.optLong("durationMs"),
            rangesSource = o.optString("rangesSource", "remote"),
            best = o.optJSONObject("best")?.let { IpSnapshot.from(it) },
            top = o.optJSONArray("top")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { IpSnapshot.from(it) }
                }
            } ?: emptyList()
        )
    }
}
