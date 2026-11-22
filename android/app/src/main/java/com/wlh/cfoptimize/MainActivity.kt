package com.wlh.cfoptimize

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wlh.cfoptimize.export.Exporters
import com.wlh.cfoptimize.ui.HistoryScreen
import com.wlh.cfoptimize.ui.HistoryViewModel
import com.wlh.cfoptimize.ui.Phase
import com.wlh.cfoptimize.ui.ScanScreen
import com.wlh.cfoptimize.ui.ScanViewModel
import com.wlh.cfoptimize.ui.components.NetworkChip
import com.wlh.cfoptimize.ui.theme.CfTheme
import com.wlh.cfoptimize.util.formatMs
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CfTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val scanVm: ScanViewModel = viewModel()
    val historyVm: HistoryViewModel = viewModel()

    val state by scanVm.state.collectAsStateWithLifecycle()
    val records by historyVm.records.collectAsStateWithLifecycle()
    val stats by historyVm.stats.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showExport by remember { mutableStateOf(false) }

    val activity = context as? Activity
    DisposableEffect(state.running) {
        if (state.running) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(Exporters.csv(scanVm.state.value.sorted).toByteArray())
                }
            }.onSuccess {
                toast(context, "已导出 CSV")
            }.onFailure {
                toast(context, "导出失败：${it.message}")
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            if (tab == 0 && state.phase == Phase.DONE) {
                FloatingActionButton(onClick = { showExport = true }) { Text("导出") }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Header(state.networkType)
            Tabs(tab) { tab = it }
            Spacer(Modifier.height(8.dp))
            if (tab == 0) {
                ScanScreen(
                    state = state,
                    items = scanVm.items,
                    onStart = { domain, cfg -> scanVm.start(domain, cfg) },
                    onCancel = { scanVm.cancel() },
                    onCopy = { copyToClipboard(context, it) },
                    onDismissMessage = { scanVm.dismissMessages() }
                )
            } else {
                HistoryScreen(
                    records = records,
                    stats = stats,
                    onDelete = { historyVm.delete(it) },
                    onClear = { historyVm.clear() }
                )
            }
        }
    }

    if (showExport) {
        ExportSheet(
            bestIp = state.best?.ip,
            bestTls = state.best?.avgTlsMs ?: 0.0,
            domain = state.config.domain,
            onDismiss = { showExport = false },
            onCsv = {
                showExport = false
                exportLauncher.launch("cf-ip-result.csv")
            },
            onShare = {
                showExport = false
                shareCsv(context, Exporters.csv(scanVm.state.value.sorted))
            },
            onCopyIp = {
                showExport = false
                state.best?.let { copyToClipboard(context, it.ip) }
            },
            onCopyHosts = {
                showExport = false
                state.best?.let {
                    copyToClipboard(context, Exporters.clashHosts(state.config.domain, it.ip))
                }
            }
        )
    }
}

@Composable
private fun Header(networkType: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("CF 优选", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Cloudflare 边缘 IP 并发测速",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        NetworkChip(networkType)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Tabs(selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        listOf("扫描", "历史").forEachIndexed { index, label ->
            SegmentedButton(
                selected = selected == index,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index, 2),
                label = { Text(label) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(
    bestIp: String?,
    bestTls: Double,
    domain: String,
    onDismiss: () -> Unit,
    onCsv: () -> Unit,
    onShare: () -> Unit,
    onCopyIp: () -> Unit,
    onCopyHosts: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)
        ) {
            Text("导出结果", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                if (bestIp != null) "最优 $bestIp · ${formatMs(bestTls)} ms TLS"
                else "暂无可用结果",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            ExportItem("导出 CSV 文件", "列名与 cf-optimize.sh 一致，仅含完成握手的 IP", onCsv)
            ExportItem("分享 CSV", "通过系统分享发送给其他应用", onShare)
            ExportItem("复制最优 IP", bestIp ?: "无可用结果", onCopyIp)
            ExportItem("复制 Clash hosts 片段", "$domain → $bestIp", onCopyHosts)
        }
    }
}

@Composable
private fun ExportItem(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "→",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText("cf-optimize", text))
    toast(context, "已复制")
}

private fun shareCsv(context: Context, csv: String) {
    val dir = File(context.cacheDir, "export").apply { mkdirs() }
    val file = File(dir, "cf-ip-result.csv")
    file.writeText(csv)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享 CSV"))
}
