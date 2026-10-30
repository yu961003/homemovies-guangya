package com.example.localmovielibrarz.ui.cloud.components
import com.example.localmovielibrarz.ui.AppRadius

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.sizeIn
import com.example.localmovielibrarz.ui.MinTouchTarget

/**
 * 批量操作悬浮条（115 / 光鸭共用，修复二轮统一视觉）。
 *
 * 第一行：全选框 + 计数 + 全选文本钮 + 关闭；第二行：等宽图标操作（不再横滚）。
 */
@Composable
fun SelectionBar(
    selectedCount: Int,
    totalCount: Int,
    showMove: Boolean,
    showRename: Boolean,
    showBatchRename: Boolean,
    showDelete: Boolean,
    onSelectAll: () -> Unit,
    onRecycle: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onBatchRename: () -> Unit,
    onExit: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = AppRadius.LG,
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 第一行：全选框 + 计数 + 全选文本钮 + 关闭
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = selectedCount > 0 && selectedCount == totalCount,
                    onCheckedChange = { onSelectAll() },
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = "已选 $selectedCount/$totalCount",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.weight(1f).padding(start = 4.dp)
                )
                if (selectedCount < totalCount) {
                    TextButton(onClick = onSelectAll, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("全选", style = MaterialTheme.typography.labelMedium)
                    }
                }
                IconButton(onClick = onExit, modifier = Modifier.sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)) {
                    Icon(Icons.Rounded.Close, contentDescription = "退出多选",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
            // 第二行：等宽图标操作（不再横滚）
            Row(Modifier.fillMaxWidth()) {
                if (showMove) SelectionIconAction("移动", Icons.Rounded.DriveFileMove, onMove, Modifier.weight(1f))
                if (showRename) SelectionIconAction("重命名", Icons.Rounded.DriveFileRenameOutline, onRename, Modifier.weight(1f))
                if (showBatchRename) SelectionIconAction("批量重命名", Icons.Rounded.EditNote, onBatchRename, Modifier.weight(1f))
                if (showDelete) SelectionIconAction(
                    "回收站", Icons.Rounded.DeleteOutline, onRecycle, Modifier.weight(1f), destructive = true
                )
            }
        }
    }
}

@Composable
private fun SelectionIconAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false
) {
    val tint = if (destructive) MaterialTheme.colorScheme.error
               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
    Column(
        modifier = modifier
            .sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    if (destructive) MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}