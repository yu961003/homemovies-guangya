package com.example.localmovielibrarz.ui.logs
import com.example.localmovielibrarz.ui.AppRadius
import com.example.localmovielibrarz.ui.warning
import com.example.localmovielibrarz.ui.success
import com.example.localmovielibrarz.ui.info
import com.example.localmovielibrarz.ui.ContentAlphaSecondary

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.localmovielibrarz.diagnostics.RuntimeErrorLog
import com.example.localmovielibrarz.ui.AppElevation
import java.io.File
import java.nio.charset.StandardCharsets

// ==================== 数据模型 ====================

data class LogEntry(
    val timestamp: String,
    val rawTimestamp: String,
    val event: String,
    val details: Map<String, String>,
    val movieTitle: String,
    val rawText: String
)

data class MovieGroup(
    val title: String,
    val entries: List<LogEntry>,
    val latestTime: String,
    val errorCount: Int
)

// ==================== 日志解析 ====================

private fun parseLog(rawText: String): List<MovieGroup> {
    if (rawText.isBlank()) return emptyList()
    // 按 [timestamp] 分割为独立条目
    val entries = mutableListOf<LogEntry>()
    val lines = rawText.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trim()
        if (line.startsWith("[") && line.contains("] ")) {
            val closeBracket = line.indexOf("] ")
            val rawTimestamp = line.substring(1, closeBracket)
            val event = line.substring(closeBracket + 2).trim()
            // 收集后续缩进行作为 details
            val detailLines = mutableListOf<String>()
            val rawBuilder = StringBuilder(line).append('\n')
            var j = i + 1
            while (j < lines.size) {
                val dl = lines[j]
                if (dl.trim().startsWith("[") && dl.contains("] ")) break
                if (dl.isNotBlank()) {
                    detailLines.add(dl)
                    rawBuilder.append(dl).append('\n')
                }
                j++
            }
            val details = mutableMapOf<String, String>()
            detailLines.forEach { dl ->
                val colonIdx = dl.trim().indexOf(": ")
                if (colonIdx > 0) {
                    val key = dl.trim().substring(0, colonIdx).trim()
                    val value = dl.trim().substring(colonIdx + 2).trim()
                    details[key] = value
                }
            }
            val movieTitle = details["title"]?.takeIf { it.isNotBlank() }
                ?: details["fileName"]?.takeIf { it.isNotBlank() }
                ?: "未知影片"
            entries.add(LogEntry(
                timestamp = formatTimestampShort(rawTimestamp),
                rawTimestamp = rawTimestamp,
                event = event,
                details = details,
                movieTitle = movieTitle,
                rawText = rawBuilder.toString().trimEnd()
            ))
            i = j
        } else {
            i++
        }
    }
    // 按影片分组
    return entries
        .groupBy { it.movieTitle }
        .map { (title, groupEntries) ->
            val sorted = groupEntries.sortedByDescending { it.rawTimestamp }
            MovieGroup(
                title = title,
                entries = sorted,
                latestTime = sorted.firstOrNull()?.timestamp ?: "",
                errorCount = sorted.count {
                    it.event.contains("error", ignoreCase = true) ||
                    it.event.contains("failed", ignoreCase = true) ||
                    it.event.contains("decoderOverload", ignoreCase = true) ||
                    it.event.contains("renderStall", ignoreCase = true)
                }
            )
        }
        .sortedByDescending { it.latestTime }
}

private fun formatTimestampShort(ts: String): String {
    // "2024-01-15 10:30:45.123" -> "01-15 10:30:45"
    return runCatching {
        val parts = ts.split(" ")
        if (parts.size >= 2) {
            val datePart = parts[0]
            val timePart = parts[1]
            val dateShort = datePart.substringAfter("-", datePart)
            val timeShort = timePart.substringBeforeLast(".", timePart)
            "$dateShort $timeShort"
        } else ts
    }.getOrElse { ts }
}

// ==================== 事件颜色/标签 ====================

@Composable
private fun eventColor(event: String): Color {
    return when {
        event.contains("error", ignoreCase = true) -> MaterialTheme.colorScheme.error
        event.contains("failed", ignoreCase = true) -> MaterialTheme.colorScheme.error
        event.contains("decoderOverload", ignoreCase = true) -> MaterialTheme.colorScheme.error
        event.contains("renderStall", ignoreCase = true) -> MaterialTheme.colorScheme.error
        event.contains("rebuffer", ignoreCase = true) -> MaterialTheme.colorScheme.warning
        event.contains("dropFrames", ignoreCase = true) -> MaterialTheme.colorScheme.warning
        event.contains("countersNull", ignoreCase = true) -> MaterialTheme.colorScheme.warning
        event.contains("healthSnapshot", ignoreCase = true) -> MaterialTheme.colorScheme.success
        event.contains("firstFrame", ignoreCase = true) -> MaterialTheme.colorScheme.info
        event.contains("tracksChanged", ignoreCase = true) -> MaterialTheme.colorScheme.info
        event.contains("monitorStarted", ignoreCase = true) -> MaterialTheme.colorScheme.success
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = ContentAlphaSecondary)
    }
}

private fun eventLabel(event: String): String {
    return when {
        event == "player.isPlaying" -> "播放/暂停"
        event == "player.playbackState" -> "状态变化"
        event == "player.tracksChanged" -> "轨道变更"
        event == "player.videoSize" -> "视频尺寸"
        event == "player.firstFrame" -> "首帧渲染"
        event == "player.error" -> "播放错误"
        event == "player.dropFrames" -> "丢帧"
        event == "player.decoderOverload" -> "解码器过载"
        event == "player.renderStall" -> "渲染停滞"
        event == "player.rebuffer.start" -> "重缓冲开始"
        event == "player.rebuffer.recovered" -> "重缓冲恢复"
        event == "player.healthSnapshot" -> "健康快照"
        event == "player.monitorStarted" -> "监控启动"
        event == "player.monitor.countersNull" -> "计数器不可用"
        event.contains("resolve") -> "链接解析"
        event.contains("subtitle") -> "字幕"
        else -> event.removePrefix("player.")
    }
}

// ==================== UI ====================

@Composable
fun PlayerLogScreen(
    errorLog: RuntimeErrorLog,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var groups by remember { mutableStateOf<List<MovieGroup>>(emptyList()) }
    var fileExists by remember { mutableStateOf(true) }
    var showClearDialog by remember { mutableStateOf(false) }
    var expandedTitles by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun reload() {
        val path = errorLog.filePath()
        val file = File(path)
        fileExists = file.exists()
        val rawText = if (fileExists) {
            runCatching { file.readText(StandardCharsets.UTF_8) }.getOrElse { "" }
        } else ""
        groups = parseLog(rawText)
        // 默认展开最近的一个影片组
        if (expandedTitles.isEmpty() && groups.isNotEmpty()) {
            expandedTitles = setOf(groups.first().title)
        }
    }

    LaunchedEffect(Unit) { reload() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            runCatching {
                val rawText = groups.flatMap { it.entries }.joinToString("\n\n") { it.rawText }
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(rawText.toByteArray(StandardCharsets.UTF_8))
                }
                Toast.makeText(context, "播放诊断日志已导出", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空日志") },
            text = { Text("确定清空所有播放诊断日志？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    errorLog.clear()
                    showClearDialog = false
                    groups = emptyList()
                    expandedTitles = emptySet()
                    Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "播放诊断日志",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { reload() }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
            }
            IconButton(onClick = { showClearDialog = true }) {
                Icon(Icons.Rounded.Delete, contentDescription = "清空")
            }
            IconButton(onClick = { exportLauncher.launch("HomeMovie-播放诊断日志.txt") }) {
                Icon(Icons.Rounded.FileDownload, contentDescription = "导出")
            }
        }

        HorizontalDivider()

        // 日志列表
        if (groups.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (!fileExists) "暂无日志文件" else "暂无播放诊断日志\n播放一次影片后这里会记录状态/轨道/卡顿/报错",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(groups, key = { it.title }) { group ->
                    MovieLogCard(
                        group = group,
                        expanded = group.title in expandedTitles,
                        onToggle = {
                            expandedTitles = if (group.title in expandedTitles) {
                                expandedTitles - group.title
                            } else {
                                expandedTitles + group.title
                            }
                        },
                        onCopyEntry = { entry ->
                            clipboard.setText(AnnotatedString(entry.rawText))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }

        // 底部信息
        Text(
            text = "共 ${groups.sumOf { it.entries.size }} 条日志，${groups.size} 部影片",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun MovieLogCard(
    group: MovieGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopyEntry: (LogEntry) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        shape = AppRadius.MD,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        // 审查 P2：此前无阴影，只靠 40% surfaceVariant 的色差撑层次
        elevation = CardDefaults.cardElevation(defaultElevation = AppElevation.SM)
    ) {
        // 卡片头：影片名 + 事件数 + 展开箭头
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = group.latestTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "${group.entries.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    if (group.errorCount > 0) {
                        Spacer(modifier = Modifier.size(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(AppRadius.XS)
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${group.errorCount} 错误",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                modifier = Modifier.size(24.dp)
            )
        }

        // 展开后：事件列表（倒序，最新在上）
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                group.entries.forEach { entry ->
                    LogEntryItem(entry = entry, onCopy = { onCopyEntry(entry) })
                }
            }
        }
    }
}

@Composable
private fun LogEntryItem(entry: LogEntry, onCopy: () -> Unit) {
    val color = eventColor(entry.event)
    val label = eventLabel(entry.event)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppRadius.SM)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .clickable { onCopy() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // 第一行：时间 + 事件标签
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.timestamp,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Box(
                modifier = Modifier
                    .clip(AppRadius.XS)
                    .background(color.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        Spacer(modifier = Modifier.size(4.dp))
        // 第二行：关键指标摘要（非全量 details，只显示重要的）
        val summaryKeys = remember(entry.event) { importantKeysForEvent(entry.event) }
        val summaryParts = summaryKeys.mapNotNull { key ->
            entry.details[key]?.let { "$key: $it" }
        }
        if (summaryParts.isNotEmpty()) {
            Text(
                text = summaryParts.joinToString("  "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            // 无摘要字段时显示事件名
            Text(
                text = entry.event,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

private fun importantKeysForEvent(event: String): List<String> {
    return when {
        event == "player.dropFrames" -> listOf("droppedFrames", "skippedOutputBuffers", "dropRate", "deltaDropped", "deltaSkipped", "bufferedMs", "rebufferCount")
        event == "player.decoderOverload" -> listOf("dropRate", "deltaDropped", "deltaSkipped", "deltaRendered", "bufferedAheadMs", "videoDecoder", "videoCodec", "hint")
        event == "player.renderStall" -> listOf("renderedOutputBuffers", "droppedFrames", "skippedOutputBuffers", "bufferedMs", "videoDecoder")
        event == "player.rebuffer.start" -> listOf("rebufferCount", "timeSinceReadyMs", "positionMs")
        event == "player.rebuffer.recovered" -> listOf("recoveryMs", "rebufferTotalMs", "positionMs")
        event == "player.healthSnapshot" -> listOf("isPlaying", "bufferedMs", "droppedFrames", "skippedOutputBuffers", "rebufferCount", "videoDecoder")
        event == "player.firstFrame" -> listOf("sincePlayRequestedMs", "videoDecoder", "videoCodec", "videoSize")
        event == "player.error" -> listOf("errorCodeName", "message", "causeChain", "positionMs")
        event == "player.playbackState" -> listOf("state", "durationKnown", "bufferedMs", "rebufferCount")
        event == "player.tracksChanged" -> listOf("videoCodec", "videoCodecsTag", "videoSize", "videoFrameRate", "videoBitrate", "videoDecoder", "audioDecoder")
        event == "player.isPlaying" -> listOf("isPlaying", "videoDecoder")
        event == "player.monitorStarted" -> listOf("intervalMs")
        event == "player.monitor.countersNull" -> listOf("rendererCount", "playbackState")
        event.contains("resolve") -> listOf("elapsedMs", "source")
        else -> emptyList()
    }
}
