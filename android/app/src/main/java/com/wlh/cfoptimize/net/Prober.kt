package com.wlh.cfoptimize.net

import com.wlh.cfoptimize.core.FailureKind
import com.wlh.cfoptimize.core.Ip
import com.wlh.cfoptimize.core.ProbeSample
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * 单 IP 探测：用自定义 Dns 把域名解析固定到目标 IP，SNI / Host / 证书校验全部保持为原域名，
 * 等价于 `curl --resolve 域名:443:IP`。
 */
class Prober(domain: String, private val timeoutMs: Int) {

    private val url = "https://$domain/"

    private val base: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
        .connectTimeout(2000, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .callTimeout((timeoutMs + 1000).toLong(), TimeUnit.MILLISECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun probe(ip: String): ProbeSample {
        val bytes = Ip.toBytes(ip)
            ?: return ProbeSample(false, 0.0, 0.0, null, FailureKind.OTHER)
        val address = InetAddress.getByAddress(bytes)
        val fixedDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(address)
        }
        val timing = Timing()
        val client = base.newBuilder()
            .dns(fixedDns)
            .eventListener(timing)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "cf-optimize-android/0.1")
            .header("Accept", "*/*")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                ProbeSample(
                    ok = timing.secureConnected,
                    tcpMs = timing.tcpMs,
                    tlsMs = timing.tlsMs,
                    httpCode = response.code,
                    failure = null
                )
            }
        } catch (e: IOException) {
            ProbeSample(
                ok = false,
                tcpMs = timing.tcpMs,
                tlsMs = timing.tlsMs,
                httpCode = null,
                failure = classify(e)
            )
        }
    }

    private fun classify(e: IOException): FailureKind = when (e) {
        is SocketTimeoutException -> FailureKind.TIMEOUT
        is SSLException -> FailureKind.TLS
        is ConnectException -> FailureKind.REFUSED
        is NoRouteToHostException -> FailureKind.REFUSED
        else -> FailureKind.OTHER
    }

    /**
     * 计时口径：curl 的 time_connect / time_appconnect 都从「请求开始」计。
     * OkHttp 的 connectStart 在 DNS 之后，因此必须用 connectEnd - callStart。
     */
    private class Timing : EventListener() {
        @Volatile private var callStartAt = 0L
        @Volatile private var connectEndAt = 0L
        @Volatile private var secureConnectEndAt = 0L

        override fun callStart(call: Call) {
            callStartAt = System.nanoTime()
        }

        override fun connectEnd(
            call: Call,
            inetSocketAddress: InetSocketAddress,
            proxy: Proxy,
            protocol: Protocol?
        ) {
            connectEndAt = System.nanoTime()
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            secureConnectEndAt = System.nanoTime()
        }

        val secureConnected: Boolean get() = secureConnectEndAt > 0

        val tcpMs: Double
            get() = if (callStartAt > 0 && connectEndAt > callStartAt) {
                (connectEndAt - callStartAt) / 1_000_000.0
            } else 0.0

        val tlsMs: Double
            get() = if (callStartAt > 0 && secureConnectEndAt > callStartAt) {
                (secureConnectEndAt - callStartAt) / 1_000_000.0
            } else 0.0
    }
}
