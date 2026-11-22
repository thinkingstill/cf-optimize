package com.wlh.cfoptimize.export

import com.wlh.cfoptimize.core.IpResult
import java.util.Locale

object Exporters {

    /** 与脚本 cf-optimize.sh 的输出列名逐字一致。 */
    const val CSV_HEADER = "IP,失败率(%),平均TLS握手(ms),平均TCP连接(ms),成功次数"

    /**
     * 脚本只输出完成握手的 IP，这里保持一致；失败项只在界面上展示。
     */
    fun csv(results: List<IpResult>): String {
        val sb = StringBuilder()
        sb.append(CSV_HEADER).append('\n')
        results.filter { it.usable }.forEach { r ->
            sb.append(
                String.format(
                    Locale.US, "%s,%.1f,%.2f,%.2f,%d",
                    r.ip, r.lossPct, r.avgTlsMs, r.avgTcpMs, r.okCount
                )
            ).append('\n')
        }
        return sb.toString()
    }

    fun clashHosts(domain: String, ip: String): String =
        "hosts:\n  $domain: $ip\n"
}
