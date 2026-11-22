package com.wlh.cfoptimize.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wlh.cfoptimize.core.IpResult
import com.wlh.cfoptimize.data.ScanConfig
import com.wlh.cfoptimize.ui.components.BestCard
import com.wlh.cfoptimize.ui.components.ConfigCard
import com.wlh.cfoptimize.ui.components.LatencyHistogram
import com.wlh.cfoptimize.ui.components.ParamSheet
import com.wlh.cfoptimize.ui.components.ProgressPanel
import com.wlh.cfoptimize.ui.components.ResultRow
import com.wlh.cfoptimize.ui.components.StatChip
import com.wlh.cfoptimize.util.formatDuration

private enum class SortMode(val label: String) {
    TLS("TLS"),
    LOSS("丢包"),
    TCP("TCP")
}

@Composable
fun ScanScreen(
    state: ScanViewModel.UiState,
    items: List<IpResult>,
    onStart: (String, ScanConfig) -> Unit,
    onCancel: () -> Unit,
    onCopy: (String) -> Unit,
    onDismissMessage: () -> Unit
) {
    var showSheet by remember { mutableStateOf(false) }
    var sortMode by rememberSaveable { mutableStateOf(SortMode.TLS) }

    val done = state.phase == Phase.DONE
    val display: List<IpResult> = remember(state.sorted, sortMode, done) {
        if (!done) emptyList() else when (sortMode) {
            SortMode.TLS -> state.sorted
            SortMode.LOSS -> state.sorted.sortedWith(
                compareBy({ !it.usable }, { it.lossPct }, { it.avgTlsMs })
            )

            SortMode.TCP -> state.sorted.sortedWith(
                compareBy({ !it.usable }, { it.avgTcpMs })
            )
        }
    }
    val list: List<IpResult> = if (done) display else items

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        ConfigCard(state) { showSheet = true }
        Spacer(Modifier.height(12.dp))

        if (state.running) {
            ProgressPanel(state)
            Spacer(Modifier.height(8.dp))
        } else if (done && state.best != null) {
            BestCard(state.best) { onCopy(it) }
            Spacer(Modifier.height(8.dp))
        }

        state.error?.let {
            MessageBanner(it, true, onDismissMessage)
        }
        state.notice?.let {
            MessageBanner(it, false, onDismissMessage)
        }

        if (done) {
            Spacer(Modifier.height(8.dp))
            SortRow(sortMode) { sortMode = it }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (list.isEmpty()) {
                EmptyState(state)
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    if (done) {
                        item { StatsHeader(state) }
                    }
                    itemsIndexed(list, key = { _, item -> item.ip }) { index, item ->
                        ResultRow(index + 1, item)
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        if (state.running) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("取消扫描") }
        } else {
            Button(
                onClick = { showSheet = true },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (done) "重新优选" else "开始优选") }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showSheet) {
        ParamSheet(
            config = state.config,
            networkType = state.networkType,
            onDismiss = { showSheet = false },
            onStart = { domain, cfg ->
                showSheet = false
                onStart(domain, cfg)
            }
        )
    }
}

@Composable
private fun StatsHeader(state: ScanViewModel.UiState) {
    val hist = remember(state.sorted) {
        state.sorted.filter { it.usable }.map { it.avgTlsMs }
    }
    Column(Modifier.padding(bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatChip("有效", "${state.validCount}", MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            StatChip("零丢包", "${state.zeroLossCount}", MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(6.dp))
            StatChip("耗时", formatDuration(state.elapsedMs), MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "TLS 握手时延分布",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LatencyHistogram(hist)
        Spacer(Modifier.height(4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortRow(mode: SortMode, onSelect: (SortMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        SortMode.entries.forEachIndexed { index, item ->
            SegmentedButton(
                selected = mode == item,
                onClick = { onSelect(item) },
                shape = SegmentedButtonDefaults.itemShape(index, SortMode.entries.size),
                label = { Text(item.label) }
            )
        }
    }
}

@Composable
private fun MessageBanner(text: String, isError: Boolean, onDismiss: () -> Unit) {
    val accent = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    Surface(
        color = accent.copy(alpha = 0.12f),
        contentColor = accent,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun EmptyState(state: ScanViewModel.UiState) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("还没有结果", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            if (state.config.domain.isEmpty()) "点击下方按钮，填写节点域名与参数"
            else "点击下方按钮开始扫描 ${state.config.sample} 个候选 IP",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
