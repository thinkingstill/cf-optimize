package com.wlh.cfoptimize.core

enum class FailureKind { TIMEOUT, REFUSED, TLS, OTHER }

/** 单次探测的结果。tcpMs / tlsMs 均以「请求开始」为起点，与 curl 的 time_connect / time_appconnect 对齐。 */
data class ProbeSample(
    val ok: Boolean,
    val tcpMs: Double,
    val tlsMs: Double,
    val httpCode: Int?,
    val failure: FailureKind?
)

/** 一个 IP 的重复探测汇总。 */
data class IpResult(
    val ip: String,
    val attempts: Int,
    val okCount: Int,
    val avgTcpMs: Double,
    val avgTlsMs: Double,
    val httpCode: Int?,
    val failure: FailureKind?
) {
    val lossPct: Double get() = if (attempts <= 0) 100.0 else (attempts - okCount) * 100.0 / attempts
    val usable: Boolean get() = okCount > 0
    val zeroLoss: Boolean get() = attempts > 0 && okCount == attempts
}

/** 排序：可用在前 → 失败率升序 → TLS 升序。与脚本的 sort -k2,2n -k3,3n 一致。 */
val ResultOrder: Comparator<IpResult> =
    compareBy<IpResult> { if (it.usable) 0 else 1 }
        .thenBy { it.lossPct }
        .thenBy { it.avgTlsMs }

fun buildResult(ip: String, samples: List<ProbeSample>): IpResult {
    val ok = samples.filter { it.ok }
    val avgTcp = if (ok.isEmpty()) 0.0 else ok.sumOf { it.tcpMs } / ok.size
    val avgTls = if (ok.isEmpty()) 0.0 else ok.sumOf { it.tlsMs } / ok.size
    val lastFailure = samples.lastOrNull { !it.ok }?.failure
    val code = ok.firstOrNull()?.httpCode
    return IpResult(
        ip = ip,
        attempts = samples.size,
        okCount = ok.size,
        avgTcpMs = avgTcp,
        avgTlsMs = avgTls,
        httpCode = code,
        failure = lastFailure
    )
}
