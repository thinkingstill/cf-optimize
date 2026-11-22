package com.wlh.cfoptimize.net

import android.content.Context
import com.wlh.cfoptimize.core.Ip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 获取 Cloudflare 官方 IPv4 网段：远端 → 本地缓存 → 内置快照，三级回退。
 */
class RangeFetcher(private val context: Context) {

    data class Result(val ranges: List<Ip.Range>, val source: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val cacheFile = File(context.filesDir, "ips-v4.txt")

    suspend fun fetch(): Result = withContext(Dispatchers.IO) {
        val remote = runCatching { fetchRemote() }.getOrNull()
        if (!remote.isNullOrEmpty()) {
            runCatching { cacheFile.writeText(remote.joinToString("\n") { it.cidr }) }
            return@withContext Result(remote, "remote")
        }
        val cached = runCatching {
            if (cacheFile.exists()) Ip.parseRanges(cacheFile.readText()) else emptyList()
        }.getOrDefault(emptyList())
        if (cached.isNotEmpty()) return@withContext Result(cached, "cache")

        val snapshot = runCatching {
            context.assets.open("ips-v4.txt").bufferedReader().use { Ip.parseRanges(it.readText()) }
        }.getOrDefault(emptyList())
        Result(snapshot, "snapshot")
    }

    private fun fetchRemote(): List<Ip.Range> {
        val request = Request.Builder()
            .url("https://www.cloudflare.com/ips-v4/")
            .header("User-Agent", "cf-optimize-android/0.1")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            return Ip.parseRanges(body)
        }
    }
}
