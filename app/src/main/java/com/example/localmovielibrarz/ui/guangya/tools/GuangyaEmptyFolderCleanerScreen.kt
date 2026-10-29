package com.example.localmovielibrarz.ui.guangya.tools
import com.example.localmovielibrarz.ui.AppRadius

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.localmovielibrarz.ui.cloud.components.SelectionBar
import kotlinx.coroutines.delay

/**
 * 光鸭空文件夹清理界面（需求 4 · 工具箱四件套之一，修复三轮整页重设计）：
 * 进入即从指定根目录递归扫描空文件夹，支持多选 / 全选后批量移入回收站。
 * 结构与文件清理页一致（状态卡 + 列表 + 底部公共操作栏）；无清理名单规则卡。
 */
@Composable
fun GuangyaEmptyFolderCleanerScreen(
    viewModel: GuangyaEmptyFolderCleanerViewModel,
    onBack: () -> Unit,
    initialParentId: String,
    initialRootName: String
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val cleanProgress by viewModel.cleanProgress.collectAsStateWithLifecycle()
    var showCleanConfirm by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    // 2：移除自动扫描，改为手动触发（顶栏「扫描/停止」按钮）。

    LaunchedEffect(state.message) {
        state.message?.let {
            delay(2_500)
            viewModel.clearMessage()
        }
    }

    // 3（方案 A）：finishedMessage 进入页面后 4s 自动清除，防止跨界面残留。
    LaunchedEffect(cleanProgress.finishedMessage) {
        cleanProgress.finishedMessage?.let {
            delay(4_000)
            viewModel.clearFinishedMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // ── 顶栏 ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text("空文件夹清理", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    text = "范围：$initialRootName · 发现 ${state.candidates.size} 个",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            FilledTonalButton(
                onClick = {
                    if (state.scanning) viewModel.stopScan()
                    else viewModel.startScan(initialParentId, initialRootName)
                },
                enabled = !state.cleaning,
                shape = AppRadius.MD,
                contentPadding = PaddingValues(horizontal = 14.dp)
            ) {
                if (state.scanning) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(if (state.scanning) "停止" else "扫描", style = MaterialTheme.typography.labelMedium)
            }
        }

        // ── 内容：唯一滚动容器 LazyColumn ──
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 状态卡（含消息/错误/进度）
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.message?.let {
                        Surface(shape = AppRadius.SM,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) {
                            Text(it, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    state.error?.let {
                        Surface(shape = AppRadius.SM,
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)) {
                            Text("出错：$it", Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (state.scanning) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text("扫描中：目录 ${state.scannedDirs} · 已发现 ${state.candidates.size} 个",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                    // 第四轮：清理进度（确定进度条，按批推进；应用级执行器跨页面存活）
                    if (cleanProgress.running) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(
                                progress = { if (cleanProgress.total > 0)
                                    (cleanProgress.done + cleanProgress.failed).toFloat() / cleanProgress.total else 0f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("清理中：${cleanProgress.done}/${cleanProgress.total}" +
                                    (if (cleanProgress.failed > 0) " · 失败 ${cleanProgress.failed}" else "") +
                                    " · ${cleanProgress.phase}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.weight(1f))
                                TextButton(onClick = viewModel::stopClean,
                                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text("停止", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    cleanProgress.finishedMessage?.let {
                        Surface(shape = AppRadius.SM,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)) {
                            Text(it, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            // 命中列表 / 空态
            if (state.candidates.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.FolderOff, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.size(48.dp))
                        Text(
                            if (state.scanning) "正在扫描…" else "暂无空文件夹，点击「扫描」按钮开始检查",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                items(state.candidates, key = { it.folderId }) { match ->
                    val checked = match.folderId in state.selectedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(AppRadius.MD)
                            .background(
                                if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                            )
                            .clickable { viewModel.toggleSelect(match.folderId) }   // 整行点击即选中
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { viewModel.toggleSelect(match.folderId) })
                        Column(Modifier.weight(1f).padding(start = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(match.name, style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(match.path,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        // 底部：公共操作栏（有勾选时）
        if (state.selectedIds.isNotEmpty()) {
            SelectionBar(
                selectedCount = state.selectedIds.size,
                totalCount = state.candidates.size,
                showMove = false, showRename = false, showBatchRename = false, showDelete = true,
                onSelectAll = viewModel::selectAll,
                onRecycle = { showCleanConfirm = true },
                onMove = {}, onRename = {}, onBatchRename = {},
                onExit = viewModel::clearSelection
            )
        }
    }

    if (showCleanConfirm) {
        AlertDialog(
            onDismissRequest = { showCleanConfirm = false },
            title = { Text("移入回收站？") },
            text = {
                Text("确定将选中的 ${state.selectedIds.size} 个空文件夹移入回收站吗？回收站内可恢复，不会彻底删除。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCleanConfirm = false
                        viewModel.cleanSelected()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showCleanConfirm = false }) { Text("取消") }
            }
        )
    }
}