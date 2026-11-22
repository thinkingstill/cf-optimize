package com.wlh.cfoptimize.ui.components

import android.content.ClipboardManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.wlh.cfoptimize.core.FailureKind
import com.wlh.cfoptimize.core.IpResult
import com.wlh.cfoptimize.data.ScanConfig
import com.wlh.cfoptimize.ui.ScanViewModel
import com.wlh.cfoptimize.ui.theme.NumberStyle
import com.wlh.cfoptimize.util.Net
import com.wlh.cfoptimize.util.formatDuration
import com.wlh.cfoptimize.util.formatMs
import com.wlh.cfoptimize.util.formatPercent
import java.util.Locale

@Composable
fun NetworkChip(type: String, modifier: Modifier = Modifier) {
    val accent = when (type) {
        Net.CELLULAR -> MaterialTheme.colorScheme.tertiary
        Net.WIFI -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.14f),
        contentColor = accent
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).background(accent, RoundedCornerShape(50)))
            Spacer(Modifier.width(6.dp))
            Text(Net.label(type), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun StatChip(label: String, value: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.12f),
        contentColor = accent
    ) {
        Row(
            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(5.dp))
            Text(
                value,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = NumberStyle.fontFamily)
            )
        }
    }
}

@Composable
fun ProgressPanel(state: ScanViewModel.UiState) {
    ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxSize(),
                    strokeWidth = 7.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(
                    "${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = NumberStyle.fontFamily
                    )
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.phaseLabel, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(8.dp))
                    NetworkChip(state.networkType)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "已测 ${state.doneProbes} / ${state.totalProbes}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatChip("有效", "${state.validCount}", MaterialTheme.colorScheme.primary)
                    StatChip("零丢包", "${state.zeroLossCount}", MaterialTheme.colorScheme.tertiary)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    String.format(
                        Locale.US, "%.0f 次/秒 · 剩余 %s",
                        state.ratePerSec, formatDuration(state.etaMs)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun BestCard(best: IpResult, onCopy: (String) -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "本次最优",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onCopy(best.ip) }) { Text("复制") }
            }
            Text(
                best.ip,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = NumberStyle.fontFamily
                )
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    formatMs(best.avgTlsMs),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = NumberStyle.fontFamily
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "ms TLS",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    "TCP ${formatMs(best.avgTcpMs)} ms · 丢包 ${formatPercent(best.lossPct)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ResultRow(index: Int, item: IpResult) {
    val usable = item.usable
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            String.format(Locale.US, "%02d", index),
            modifier = Modifier.width(30.dp),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = NumberStyle.fontFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(Modifier.weight(1f)) {
            Text(
                item.ip,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = NumberStyle.fontFamily)
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle(item),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (usable) formatMs(item.avgTlsMs) else "—",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = NumberStyle.fontFamily
                ),
                color = if (usable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            )
            Text(
                "ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun subtitle(item: IpResult): String {
    if (!item.usable) {
        val kind = when (item.failure) {
            FailureKind.TIMEOUT -> "超时"
            FailureKind.REFUSED -> "连接被拒"
            FailureKind.TLS -> "TLS 失败"
            else -> "失败"
        }
        return "$kind · ${item.attempts} 次"
    }
    val code = item.httpCode?.let { "HTTP $it" } ?: "—"
    return "TCP ${formatMs(item.avgTcpMs)} ms · 丢包 ${formatPercent(item.lossPct)}% · $code"
}

@Composable
fun LatencyHistogram(values: List<Double>, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    val grid = MaterialTheme.colorScheme.surfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val sorted = remember(values) { values.sorted() }
    val min = sorted.first()
    val max = sorted.last().let { if (it - min < 1.0) min + 1.0 else it }
    val bins = 20
    val counts = remember(sorted) {
        IntArray(bins).also { c ->
            sorted.forEach { v ->
                val idx = (((v - min) / (max - min)) * (bins - 1)).toInt().coerceIn(0, bins - 1)
                c[idx]++
            }
        }
    }
    val peak = counts.max().coerceAtLeast(1)
    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        val cell = size.width / bins
        counts.forEachIndexed { i, c ->
            val h = (size.height * (c.toFloat() / peak)).coerceAtLeast(2f)
            drawRoundRect(
                color = if (c > 0) accent.copy(alpha = 0.85f) else grid,
                topLeft = Offset(i * cell + 1.5f, size.height - h),
                size = Size(cell - 3f, h),
                cornerRadius = CornerRadius(3f, 3f)
            )
        }
    }
}

@Composable
fun ConfigCard(state: ScanViewModel.UiState, onEdit: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() }
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        state.config.domain.ifEmpty { "未设置域名" },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (state.config.domain.isEmpty()) {
                            MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "候选 ${state.config.sample} · 重复 ${state.config.repeat} · " +
                            "并发 ${state.config.concurrency} · 超时 ${state.config.timeoutMs / 1000}s",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "修改",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParamSheet(
    config: ScanConfig,
    networkType: String,
    onDismiss: () -> Unit,
    onStart: (String, ScanConfig) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    var domain by remember { mutableStateOf(config.domain) }
    var sampleText by remember { mutableStateOf(config.sample.toString()) }
    var repeat by remember { mutableIntStateOf(config.repeat) }
    var concurrency by remember { mutableFloatStateOf(config.concurrency.toFloat()) }
    var timeoutSec by remember { mutableFloatStateOf(config.timeoutMs / 1000f) }

    val sample = sampleText.toIntOrNull()
        ?.coerceIn(ScanConfig.SAMPLE_MIN, ScanConfig.SAMPLE_MAX)
        ?: config.sample

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text("扫描参数", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "结果仅反映本机当前网络出口的链路质量",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                label = { Text("节点域名") },
                placeholder = { Text("node.example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        val text = cm?.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.text?.toString()
                        if (!text.isNullOrBlank()) domain = text.trim()
                    }) { Text("粘贴") }
                }
            )

            Spacer(Modifier.height(20.dp))
            Text("候选数量", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = sampleText,
                onValueChange = { v -> sampleText = v.filter { it.isDigit() }.take(4) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(500, 1000, 2000).forEach { preset ->
                    AssistChip(
                        onClick = { sampleText = preset.toString() },
                        label = { Text("$preset") }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("重复次数", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(1, 2, 3, 5).forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = repeat == value,
                        onClick = { repeat = value },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 4),
                        label = { Text("$value") }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("并发数", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    "${concurrency.toInt()}",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = NumberStyle.fontFamily
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = concurrency,
                onValueChange = { concurrency = it },
                valueRange = ScanConfig.CONCURRENCY_MIN.toFloat()..ScanConfig.CONCURRENCY_MAX.toFloat(),
                steps = 13
            )
            Text(
                if (networkType == Net.CELLULAR) "蜂窝网络建议不超过 30，否则时延会失真"
                else "Wi-Fi 下实测 300 仍线性，默认 100 留有射频余量",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("单次超时", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Text(
                    "${timeoutSec.toInt()} s",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = NumberStyle.fontFamily
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Slider(
                value = timeoutSec,
                onValueChange = { timeoutSec = it },
                valueRange = 2f..8f,
                steps = 5
            )

            Spacer(Modifier.height(16.dp))
            Text(
                "预计可用 ≈ ${(sample * ScanConfig.EXPECTED_VALID_RATIO).toInt()} 个 · " +
                    "实测有效率约 34%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    onStart(
                        domain,
                        config.copy(
                            sample = sample,
                            repeat = repeat,
                            concurrency = concurrency.toInt(),
                            timeoutMs = (timeoutSec * 1000).toInt()
                        )
                    )
                },
                enabled = domain.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) { Text("开始优选") }
        }
    }
}
