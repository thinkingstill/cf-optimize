package com.wlh.cfoptimize.ui

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wlh.cfoptimize.core.Ip
import com.wlh.cfoptimize.core.IpResult
import com.wlh.cfoptimize.data.HistoryStore
import com.wlh.cfoptimize.data.IpSnapshot
import com.wlh.cfoptimize.data.RunRecord
import com.wlh.cfoptimize.data.ScanConfig
import com.wlh.cfoptimize.data.Settings
import com.wlh.cfoptimize.net.RangeFetcher
import com.wlh.cfoptimize.net.ScanEngine
import com.wlh.cfoptimize.net.ScanEvent
import com.wlh.cfoptimize.util.Net
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Phase { IDLE, FETCHING, SAMPLING, SCANNING, FINALIZING, DONE, CANCELLED, FAILED }

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val phase: Phase = Phase.IDLE,
        val config: ScanConfig = ScanConfig(),
        val totalProbes: Int = 0,
        val doneProbes: Int = 0,
        val validCount: Int = 0,
        val zeroLossCount: Int = 0,
        val elapsedMs: Long = 0L,
        val testedIps: Int = 0,
        val sorted: List<IpResult> = emptyList(),
        val rangesSource: String = "",
        val networkType: String = Net.UNKNOWN,
        val error: String? = null,
        val notice: String? = null,
        val historyCount: Int = 0,
        val best: IpResult? = null
    ) {
        val running: Boolean
            get() = phase == Phase.FETCHING || phase == Phase.SAMPLING ||
                phase == Phase.SCANNING || phase == Phase.FINALIZING

        val progress: Float
            get() = if (totalProbes <= 0) 0f else (doneProbes.toFloat() / totalProbes).coerceIn(0f, 1f)

        val ratePerSec: Double
            get() = if (elapsedMs <= 0) 0.0 else doneProbes * 1000.0 / elapsedMs

        val etaMs: Long
            get() {
                val rate = ratePerSec
                if (rate <= 0.0) return 0L
                val remain = (totalProbes - doneProbes).coerceAtLeast(0)
                return (remain / rate * 1000).toLong()
            }

        val phaseLabel: String
            get() = when (phase) {
                Phase.IDLE -> "待开始"
                Phase.FETCHING -> "获取网段"
                Phase.SAMPLING -> "生成候选"
                Phase.SCANNING -> "并发探测"
                Phase.FINALIZING -> "汇总结果"
                Phase.DONE -> "已完成"
                Phase.CANCELLED -> "已取消"
                Phase.FAILED -> "失败"
            }
    }

    private val settings = Settings(app)
    private val history = HistoryStore.get(app)
    private val fetcher = RangeFetcher(app)

    val items: SnapshotStateList<IpResult> = mutableStateListOf()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch {
            val config = settings.config.first()
            _state.update {
                it.copy(config = config, networkType = Net.type(getApplication()))
            }
            withContext(Dispatchers.IO) { history.load() }
            if (settings.pending()) {
                settings.setPending(false)
                _state.update { it.copy(notice = "上次扫描被系统中断，结果未保存") }
            }
        }
        viewModelScope.launch {
            history.records.collect { records ->
                _state.update { it.copy(historyCount = records.size) }
            }
        }
        viewModelScope.launch {
            while (true) {
                _state.update { s ->
                    if (s.running) s.copy(networkType = Net.type(getApplication())) else s
                }
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    fun updateConfig(config: ScanConfig) {
        _state.update { it.copy(config = config) }
    }

    fun refreshNetwork() {
        _state.update { it.copy(networkType = Net.type(getApplication())) }
    }

    fun dismissMessages() {
        _state.update { it.copy(error = null, notice = null) }
    }

    fun start(rawDomain: String, config: ScanConfig) {
        if (job?.isActive == true) return
        val domain = normalizeDomain(rawDomain)
        if (domain == null) {
            _state.update { it.copy(error = "域名格式不正确，请填写如 node.example.com") }
            return
        }
        val cfg = config.copy(domain = domain)
        items.clear()
        _state.update {
            it.copy(
                phase = Phase.FETCHING,
                config = cfg,
                error = null,
                notice = null,
                totalProbes = 0,
                doneProbes = 0,
                validCount = 0,
                zeroLossCount = 0,
                elapsedMs = 0L,
                testedIps = 0,
                sorted = emptyList(),
                best = null,
                networkType = Net.type(getApplication())
            )
        }

        job = viewModelScope.launch {
            try {
                settings.save(cfg)
                settings.setPending(true)

                val ranges = fetcher.fetch()
                if (ranges.ranges.isEmpty()) {
                    fail("未获取到 Cloudflare 网段，请检查网络")
                    return@launch
                }
                _state.update { it.copy(phase = Phase.SAMPLING, rangesSource = ranges.source) }

                val blocks = Ip.expandSlash24(ranges.ranges)
                val candidates = withContext(Dispatchers.Default) {
                    Ip.sampleBlocks(blocks, cfg.sample, Random.Default)
                }
                if (candidates.isEmpty()) {
                    fail("没有生成候选 IP")
                    return@launch
                }

                _state.update { it.copy(phase = Phase.SCANNING, totalProbes = candidates.size * cfg.repeat) }

                val engine = ScanEngine(domain, cfg.repeat, cfg.concurrency, cfg.timeoutMs)
                var sorted: List<IpResult> = emptyList()
                engine.scan(candidates).collect { event ->
                    when (event) {
                        is ScanEvent.Started -> _state.update {
                            it.copy(totalProbes = event.totalProbes, testedIps = event.totalIps)
                        }

                        is ScanEvent.Progress -> _state.update {
                            it.copy(
                                doneProbes = event.done,
                                totalProbes = event.total,
                                validCount = event.valid,
                                zeroLossCount = event.zeroLoss,
                                elapsedMs = event.elapsedMs
                            )
                        }

                        is ScanEvent.Item -> items.add(event.result)

                        is ScanEvent.Finished -> sorted = event.sorted
                    }
                }

                _state.update { it.copy(phase = Phase.FINALIZING) }
                complete(sorted, cfg, candidates.size)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { settings.setPending(false) }
                _state.update { it.copy(phase = Phase.CANCELLED, notice = "已取消，本次结果不写入历史") }
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "扫描失败：${e.javaClass.simpleName}")
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun complete(sorted: List<IpResult>, config: ScanConfig, tested: Int) {
        val best = sorted.firstOrNull { it.usable }
        val top = sorted.filter { it.usable }.take(20).map { IpSnapshot.of(it) }
        val snapshot = _state.value
        val record = RunRecord(
            id = UUID.randomUUID().toString(),
            finishedAt = System.currentTimeMillis(),
            domain = config.domain,
            networkType = Net.type(getApplication()),
            sample = config.sample,
            repeat = config.repeat,
            concurrency = config.concurrency,
            timeoutMs = config.timeoutMs,
            tested = tested,
            valid = snapshot.validCount,
            zeroLoss = snapshot.zeroLossCount,
            durationMs = snapshot.elapsedMs,
            rangesSource = snapshot.rangesSource,
            best = best?.let { IpSnapshot.of(it) },
            top = top
        )
        withContext(Dispatchers.IO) { history.append(record) }
        settings.setPending(false)
        _state.update {
            it.copy(phase = Phase.DONE, sorted = sorted, best = best, doneProbes = it.totalProbes)
        }
    }

    private suspend fun fail(message: String) {
        withContext(NonCancellable) { settings.setPending(false) }
        _state.update { it.copy(phase = Phase.FAILED, error = message) }
    }

    private fun normalizeDomain(raw: String): String? {
        var d = raw.trim().lowercase()
        d = d.removePrefix("https://").removePrefix("http://")
        d = d.substringBefore('/').substringBefore('?')
        d = d.substringBefore(':')
        if (d.isEmpty()) return null
        val pattern = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
        return if (pattern.matches(d)) d else null
    }
}
