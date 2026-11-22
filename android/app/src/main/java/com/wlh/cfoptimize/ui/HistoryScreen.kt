package com.wlh.cfoptimize.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wlh.cfoptimize.data.IpStat
import com.wlh.cfoptimize.data.RunRecord
import com.wlh.cfoptimize.ui.theme.NumberStyle
import com.wlh.cfoptimize.util.Net
import com.wlh.cfoptimize.util.formatMs
import com.wlh.cfoptimize.util.formatPercent
import com.wlh.cfoptimize.util.formatTime
import java.util.Locale

@Composable
fun HistoryScreen(
    records: List<RunRecord>,
    stats: List<IpStat>,
    onDelete: (String) -> Unit,
    onClear: () -> Unit
) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        SummaryCard(records, stats, onClear = { confirmClear = true })
        Spacer(Modifier.height(12.dp))

        if (records.isEmpty()) {
            EmptyHistory()
        } else {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("IP 榜", "时间线").forEachIndexed { index, label ->
                    SegmentedButton(
                        selected = mode == index,
                        onClick = { mode = index },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (mode == 0) StatList(stats) else RecordList(records, onDelete)
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空历史？") },
            text = { Text("${records.size} 条记录将被删除，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SummaryCard(records: List<RunRecord>, stats: List<IpStat>, onClear: () -> Unit) {
    val distinctBest = stats.count { it.bestCount > 0 }
    val top = stats.firstOrNull()
    Card(shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("累计统计", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "扫描 ${records.size} 次 · $distinctBest 个 IP 登顶",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                if (records.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("清空") }
                }
            }
            if (top != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "最稳定 ${top.ip} · ${top.bestCount} 次登顶 · 登顶率 ${formatPercent(top.stability * 100)}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatList(stats: List<IpStat>) {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(stats, key = { _, s -> s.ip }) { index, stat ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    String.format(Locale.US, "%02d", index + 1),
                    modifier = Modifier.width(30.dp),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = NumberStyle.fontFamily
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stat.ip,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = NumberStyle.fontFamily
                        )
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${stat.bestCount} 次登顶 · 登顶率 ${formatPercent(stat.stability * 100)}% · 最近 ${formatTime(stat.lastSeenAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        formatMs(stat.lastTlsMs),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = NumberStyle.fontFamily
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
private fun RecordList(records: List<RunRecord>, onDelete: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(records, key = { it.id }) { record ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        formatTime(record.finishedAt) + " · " + record.domain,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${Net.label(record.networkType)} · 有效 ${record.valid}/${record.tested} · " +
                            "零丢包 ${record.zeroLoss} · ${record.sample}×${record.repeat}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    record.best?.let { best ->
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                best.ip,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = NumberStyle.fontFamily
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${formatMs(best.tlsMs)} ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                TextButton(onClick = { onDelete(record.id) }) { Text("删除") }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
private fun EmptyHistory() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("暂无历史", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "完成一次优选后，这里会累积每个 IP 的登顶次数与时延",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
