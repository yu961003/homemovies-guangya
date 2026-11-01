package com.example.localmovielibrarz.ui.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.localmovielibrarz.LocalMovieLibraryApp
import com.example.localmovielibrarz.data.repository.ThemeMode
import com.example.localmovielibrarz.data.repository.ThemePalette
import com.example.localmovielibrarz.ui.AppRadius
import com.example.localmovielibrarz.ui.components.HmChoiceChips
import com.example.localmovielibrarz.ui.components.HmDivider
import com.example.localmovielibrarz.ui.components.HmGroup
import com.example.localmovielibrarz.ui.components.HmHint
import com.example.localmovielibrarz.ui.components.HmSelectableCard
import com.example.localmovielibrarz.ui.components.HmSwitchItem
import com.example.localmovielibrarz.ui.isDark
import com.example.localmovielibrarz.ui.previewPrimary

/**
 * 外观设置页（v2 新增；参考 mpvRx「多主题 + AMOLED + Material You」能力）。
 *
 * 写入 [com.example.localmovielibrarz.data.repository.ThemeSettingsRepository]，应用根部订阅后即时换肤，无需重启。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceSettingsPage() {
    val app = LocalContext.current.applicationContext as LocalMovieLibraryApp
    val repo = app.container.themeSettings
    val spec by repo.observe().collectAsState(initial = repo.snapshot())
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val dark = MaterialTheme.colorScheme.isDark

    HmGroup(title = "明暗") {
        HmChoiceChips(
            options = ThemeMode.entries,
            selected = spec.mode,
            label = { it.label },
            onSelect = { mode -> repo.update { it.copy(mode = mode) } }
        )
        HmDivider()
        HmSwitchItem(
            title = "纯黑背景（AMOLED）",
            subtitle = "深色模式下背景与表面为纯黑，OLED 屏更省电、观影更沉浸",
            icon = Icons.Rounded.Contrast,
            checked = spec.amoled,
            enabled = spec.mode != ThemeMode.LIGHT,
            onCheckedChange = { checked -> repo.update { it.copy(amoled = checked) } }
        )
    }

    HmGroup(title = "主题色") {
        HmSwitchItem(
            title = "动态取色（Material You）",
            subtitle = if (dynamicSupported) "跟随系统壁纸配色；开启后下方色板暂不生效" else "需要 Android 12 及以上",
            icon = Icons.Rounded.Colorize,
            checked = spec.dynamicColor && dynamicSupported,
            enabled = dynamicSupported,
            onCheckedChange = { checked -> repo.update { it.copy(dynamicColor = checked) } }
        )
        HmDivider()
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = 3
        ) {
            ThemePalette.entries.forEach { palette ->
                HmSelectableCard(
                    selected = spec.palette == palette && !(spec.dynamicColor && dynamicSupported),
                    onClick = { repo.update { it.copy(palette = palette, dynamicColor = false) } },
                    modifier = Modifier.weight(1f)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(palette.previewPrimary(dark = true)))
                        Box(Modifier.size(20.dp).clip(CircleShape).background(palette.previewPrimary(dark = false)))
                    }
                    Text(palette.label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        HmHint("「影院青」是品牌默认色，沿用 v1 手调过对比度的配色；其余色板按 MD3 色调规则由种子色生成。")
    }

    HmGroup(title = "预览") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.secondary,
                MaterialTheme.colorScheme.tertiary,
                MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.colorScheme.background
            ).forEach { color ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(AppRadius.SM)
                        .background(color)
                )
            }
        }
        HmHint("主色 / 辅色 / 第三色 / 容器 / 背景")
    }
}
