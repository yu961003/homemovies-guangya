package com.example.localmovielibrarz.ui.cloud.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.sizeIn
import com.example.localmovielibrarz.ui.MinTouchTarget

/**
 * 网盘页统一操作栏（115 / 光鸭共用）——图标化紧凑版。
 *
 * 设计动机：原「图标+文字」胶囊一行总宽 ~428dp，超出 360dp 屏宽导致拥挤。
 * 改为 40dp 图标钮后整行 ~200dp；功能说明放 contentDescription（无障碍可读）。
 * 主操作（离线下载）用 primaryContainer 圆形底座区分层级，
 * token 与底部导航选中态、网盘切换栏一致，不引入新颜色。
 */
@Composable
fun CloudOperationBar(
    canGoBack: Boolean,
    onGoBack: () -> Unit,
    onGoRoot: () -> Unit,
    primaryAction: CloudPrimaryAction?,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 左：返回上级（仅可返回时出现，带动画）
        AnimatedVisibility(
            visible = canGoBack,
            enter = fadeIn() + slideInHorizontally { -it / 4 },
            exit = fadeOut() + slideOutHorizontally { -it / 4 }
        ) {
            CloudNavIcon(Icons.AutoMirrored.Rounded.ArrowBack, "返回上级", onGoBack)
        }
        // 左：根目录
        CloudNavIcon(
            Icons.Rounded.Home, "根目录", onGoRoot,
            enabled = primaryAction !is CloudPrimaryAction.Loading
        )

        Spacer(Modifier.weight(1f))

        // 右：搜索（配合搜索框收起方案，见问题7）
        if (onSearch != null) {
            CloudNavIcon(Icons.Rounded.Search, "搜索", onSearch)
        }
        // 右：主操作（primaryContainer 圆形底座）。115 页已无主操作（传 null），
        // 光鸭页传 OfflineDownload；加载中显示进度圈占位，避免右侧图标跳位。
        when (primaryAction) {
            null -> Unit
            is CloudPrimaryAction.OfflineDownload ->
                CloudPrimaryIcon(Icons.Rounded.CloudDownload, "离线下载", primaryAction.onClick)
            is CloudPrimaryAction.Loading -> Box(
                Modifier.size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        }
    }
}

/**
 * 主操作按钮配置。115 页不传（null = 无主操作），光鸭页传 [OfflineDownload]，
 * 加载中传 [Loading] 占位。
 */
sealed class CloudPrimaryAction {
    data class OfflineDownload(val onClick: () -> Unit) : CloudPrimaryAction()
    data object Loading : CloudPrimaryAction()
}

/** 次级操作：40dp 无底座图标钮，onSurface 82% 着色（融入背景）。 */
@Composable
private fun CloudNavIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)) {
        Icon(
            icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f)
        )
    }
}

/** 主操作：40dp primaryContainer 圆形底座（对齐底部导航选中态 / 网盘切换栏）。 */
@Composable
private fun CloudPrimaryIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(20.dp))
    }
}