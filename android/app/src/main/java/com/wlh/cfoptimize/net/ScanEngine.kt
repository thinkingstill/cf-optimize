package com.wlh.cfoptimize.net

import com.wlh.cfoptimize.core.IpResult
import com.wlh.cfoptimize.core.ProbeSample
import com.wlh.cfoptimize.core.ResultOrder
import com.wlh.cfoptimize.core.buildResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

sealed interface ScanEvent {
    data class Started(val totalProbes: Int, val totalIps: Int) : ScanEvent
    data class Progress(
        val done: Int,
        val total: Int,
        val valid: Int,
        val zeroLoss: Int,
        val elapsedMs: Long
    ) : ScanEvent

    data class Item(val result: IpResult) : ScanEvent
    data class Finished(val sorted: List<IpResult>, val elapsedMs: Long) : ScanEvent
}

/**
 * 并发扫描引擎。真正的闸门是 Semaphore；每个探测是阻塞调用，跑在 Dispatchers.IO 上。
 */
class ScanEngine(
    private val domain: String,
    private val repeat: Int,
    private val concurrency: Int,
    private val timeoutMs: Int
) {

    fun scan(candidates: List<String>): Flow<ScanEvent> = channelFlow {
        val prober = Prober(domain, timeoutMs)
        val total = candidates.size * repeat
        val done = AtomicInteger(0)
        val valid = AtomicInteger(0)
        val zeroLoss = AtomicInteger(0)
        val lastEmit = AtomicLong(0)
        val startedAt = System.currentTimeMillis()
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        val results = Collections.synchronizedList(ArrayList<IpResult>(candidates.size))

        send(ScanEvent.Started(total, candidates.size))

        coroutineScope {
            for (ip in candidates) {
                launch(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (!isActive) return@withPermit
                        val samples = ArrayList<ProbeSample>(repeat)
                        for (i in 0 until repeat) {
                            if (!isActive) return@withPermit
                            samples.add(prober.probe(ip))
                            val d = done.incrementAndGet()
                            val now = System.currentTimeMillis()
                            val prev = lastEmit.get()
                            if (d == total || now - prev >= 80) {
                                if (lastEmit.compareAndSet(prev, now)) {
                                    send(
                                        ScanEvent.Progress(
                                            d, total, valid.get(), zeroLoss.get(), now - startedAt
                                        )
                                    )
                                }
                            }
                        }
                        val result = buildResult(ip, samples)
                        results.add(result)
                        if (result.usable) valid.incrementAndGet()
                        if (result.zeroLoss) zeroLoss.incrementAndGet()
                        send(ScanEvent.Item(result))
                    }
                }
            }
        }

        send(
            ScanEvent.Finished(
                sorted = results.sortedWith(ResultOrder),
                elapsedMs = System.currentTimeMillis() - startedAt
            )
        )
    }
}
