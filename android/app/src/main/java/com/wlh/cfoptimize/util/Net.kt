package com.wlh.cfoptimize.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Net {

    const val UNKNOWN = "UNKNOWN"
    const val WIFI = "WIFI"
    const val CELLULAR = "CELLULAR"
    const val ETHERNET = "ETHERNET"

    fun type(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return UNKNOWN
        val network = cm.activeNetwork ?: return UNKNOWN
        val caps = cm.getNetworkCapabilities(network) ?: return UNKNOWN
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ETHERNET
            else -> UNKNOWN
        }
    }

    fun label(type: String): String = when (type) {
        WIFI -> "Wi-Fi"
        CELLULAR -> "蜂窝"
        ETHERNET -> "以太网"
        else -> "未知网络"
    }

    /** 蜂窝下需要降低并发：无线状态机迁移会排队，高并发会让时延失真。 */
    fun suggestConcurrency(type: String): Int = if (type == CELLULAR) 30 else 100
}

fun formatMs(value: Double): String =
    if (value <= 0.0) "—" else String.format(Locale.US, "%.0f", value)

fun formatTls(value: Double): String =
    if (value <= 0.0) "—" else String.format(Locale.US, "%.2f", value)

fun formatPercent(value: Double): String = String.format(Locale.US, "%.1f", value)

fun formatTime(ts: Long): String =
    if (ts <= 0L) "—" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

fun formatDuration(ms: Long): String {
    val s = ms / 1000.0
    return if (s < 60) String.format(Locale.US, "%.1f s", s)
    else String.format(Locale.US, "%d m %02d s", (ms / 60000).toInt(), ((ms % 60000) / 1000).toInt())
}
