package com.example.localmovielibrarz.ui.cloudclean
import com.example.localmovielibrarz.ui.AppRadius

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.example.localmovielibrarz.ui.shared.formatFileSize

/**
 * 115 清理工具界面（需求 13）：
 * 顶部为共享清理规则配置（关键词 / 后缀 + 大小阈值），下方为全盘扫描命中列表，
 * 支持多选 / 全选后批量移入回收站（绝不永久删除）。
 */
@Composable
fun CloudCleanScreen(
    viewModel: CloudCleanViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 5a：清理名单 StateFlow 绑定——删除/导入后回灌触发重组，chips 实时刷新
    val keywords by viewModel.keywordsFlow.collectAsStateWithLifecycle()
    val suffixes by viewModel.suffixesFlow.collectAsStateWithLifecycle()
    var showCleanConfirm by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LaunchedEffect(state.message) {
        state.message?.let {
            delay(2_500)
            viewModel.clearMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "115 清理工具",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(
                onClick = { if (state.scanning) viewModel.stopScan() else viewModel.startScan() },
                enabled = !state.cleaning
            ) {
                Text(if (state.scanning) "停止扫描" else "开始扫描")
            }
        }

        // ★ 内容区：唯一滚动容器 = LazyColumn（去掉 verticalScroll 的 Column，修复一）
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.message?.let { msg ->
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    state.error?.let { err ->
                        Text(
                            text = "出错：$err",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            item {
                // 共享清理规则卡片（关键词 / 后缀 + 大小阈值 + 启停开关；外挂标题与卡片壳——
                // CloudCleanRulePanel 已去掉自身 Surface 壳，供光鸭页嵌套在可折叠卡内）
                Surface(shape = AppRadius.LG,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("清理名单", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        CloudCleanRulePanel(
                            keywords = keywords,
                            suffixes = suffixes,
                            maxSizeMb = viewModel.maxSizeMb(),
                            keywordEnabled = viewModel.keywordEnabled(),
                            suffixEnabled = viewModel.suffixEnabled(),
                            onToggleKeyword = {
                                viewModel.setKeywordEnabled(it)
                                viewModel.push("已${if (it) "点亮" else "熄灭"}关键词匹配，请重新扫描")
                            },
                            onToggleSuffix = {
                                viewModel.setSuffixEnabled(it)
                                viewModel.push("已${if (it) "点亮" else "熄灭"}后缀匹配，请重新扫描")
                            },
                            onAddKeyword = viewModel::addKeyword,
                            onRemoveKeyword = viewModel::removeKeyword,
                            onAddSuffix = viewModel::addSuffix,
                            onRemoveSuffix = viewModel::removeSuffix,
                            onSaveMaxSizeMb = viewModel::saveMaxSizeMb,
                            onImportKeywords = viewModel::importKeywords,
                            onImportSuffixes = viewModel::importSuffixes,
                            // ★ 需求 3：下拉多选一次性提交集合（全选/清空/勾选），避免逐条写盘
                            onApplyKeywords = { viewModel.setKeywords(it); viewModel.push("关键词已更新，请重新扫描") },
                            onApplySuffixes = { viewModel.setSuffixes(it); viewModel.push("后缀已更新，请重新扫描") }
                        )
                    }
                }
            }
            item {
                // 扫描状态与命中列表标题
                if (state.scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "扫描中：目录 ${state.scannedDirs} · 文件 ${state.scannedFiles} · 命中 ${state.matches.size}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    Text(
                        text = "命中 ${state.matches.size} 项",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            // 命中列表
            if (state.matches.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (state.scanning) "正在扫描…" else "暂无命中项，点击「开始扫描」检查网盘",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            } else {
                items(state.matches, key = { it.pickcode }) { match ->
                    val checked = match.pickcode in state.selectedPickcodes
                    val rowBg =
                        if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                rowBg,
                                shape = AppRadius.MD
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = match.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "路径：${match.path} · ${match.reason}",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            match.size?.let {
                                Text(
                                    text = formatSize(it),
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                        if (state.selectionMode) {
                            Checkbox(checked = checked, onCheckedChange = { viewModel.toggleSelect(match.pickcode) })
                        } else {
                            TextButton(onClick = { viewModel.enterSelectionMode(); viewModel.toggleSelect(match.pickcode) }) {
                                Text("选择", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }

        // 底部操作栏（多选模式）
        if (state.selectionMode) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                shape = AppRadius.LG,
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "已选 ${state.selectedPickcodes.size}/${state.matches.size}",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = viewModel::selectAll) { Text("全选", style = MaterialTheme.typography.labelMedium) }
                    TextButton(
                        onClick = { showCleanConfirm = true },
                        enabled = state.selectedPickcodes.isNotEmpty() && !state.cleaning
                    ) {
                        Text("移入回收站", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = viewModel::clearSelection) { Text("完成", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }

    if (showCleanConfirm) {
        AlertDialog(
            onDismissRequest = { showCleanConfirm = false },
            title = { Text("移入回收站？") },
            text = {
                Text("确定将选中的 ${state.selectedPickcodes.size} 项移入回收站吗？回收站内可恢复，不会彻底删除。")
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

// H+1 轮：改为复用统一实现（此前 3 个文件各有一份逐字相同的副本）
private fun formatSize(bytes: Long): String = formatFileSize(bytes)