package com.example.localmovielibrarz.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.DeviceThermostat
import androidx.compose.material.icons.rounded.Gradient
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Pinch
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.example.localmovielibrarz.LocalMovieLibraryApp
import com.example.localmovielibrarz.playback.engine.HdrToneMode
import com.example.localmovielibrarz.playback.engine.MpvUserOptions
import com.example.localmovielibrarz.ui.components.HmChoiceChips
import com.example.localmovielibrarz.ui.components.HmDivider
import com.example.localmovielibrarz.ui.components.HmGroup
import com.example.localmovielibrarz.ui.components.HmHint
import com.example.localmovielibrarz.ui.components.HmListItem
import com.example.localmovielibrarz.ui.components.HmSliderItem
import com.example.localmovielibrarz.ui.components.HmSwitchItem

/**
 * 播放增强设置页（v2 新增）：承载从 mpvRx 能力清单接入的各项开关。
 *
 * 每项都标注了「立即生效」还是「下次起播生效」——mpv 的渲染相关选项必须在 `mpv_initialize()`
 * 之前下发（本工程既有约束），播放中改了也要等下次进播放页才会用上，这一点必须让用户知道。
 */
@Composable
internal fun PlayerEnhancementSettingsPage() {
    val app = LocalContext.current.applicationContext as LocalMovieLibraryApp
    val repo = app.container.playerFeatureSettings
    val f by repo.observe().collectAsState(initial = repo.snapshot())

    HmGroup(title = "画质（MPV · 下次起播生效）") {
        HmListItem(title = "HDR 色调映射", subtitle = f.hdrMode.subtitle, icon = Icons.Rounded.AutoAwesome)
        HmChoiceChips(
            options = HdrToneMode.entries,
            selected = f.hdrMode,
            label = { it.label },
            onSelect = { mode -> repo.update { it.copy(hdrMode = mode) } }
        )
        HmHint("基于 hdr-toys（MIT）着色器：Astra 色调映射 + Bottosson 色域映射。HDR 管线生效时实时调色会暂停，回到 SDR 片源自动恢复。")
        HmDivider()
        HmSwitchItem(
            title = "GPU 去色带",
            subtitle = "消除天空、暗部渐变里的色阶断层（轻微增加 GPU 负载）",
            icon = Icons.Rounded.Gradient,
            checked = f.debandEnabled,
            onCheckedChange = { v -> repo.update { it.copy(debandEnabled = v) } }
        )
        if (f.debandEnabled) {
            HmChoiceChips(
                options = listOf(1, 2, 3),
                selected = f.debandStrength,
                label = { when (it) { 1 -> "轻"; 3 -> "强"; else -> "标准" } },
                onSelect = { v -> repo.update { it.copy(debandStrength = v) } }
            )
        }
        HmDivider()
        HmSwitchItem(
            title = "Anime4K 安全守卫",
            subtitle = "4K 以上片源自动停用；设备偏热时降到性能档，过热时关闭（Android 11+ 热余量预测）",
            icon = Icons.Rounded.DeviceThermostat,
            checked = f.anime4kGuards,
            onCheckedChange = { v -> repo.update { it.copy(anime4kGuards = v) } }
        )
        HmDivider()
        HmSwitchItem(
            title = "省电渲染",
            subtitle = "未开调色且未开 HDR 管线时改用 vo=gpu（比 gpu-next 更省电）；需要调色时自动回到 gpu-next",
            icon = Icons.Rounded.BatterySaver,
            checked = f.powerSaveRender,
            onCheckedChange = { v -> repo.update { it.copy(powerSaveRender = v) } }
        )
    }

    HmGroup(title = "手势（立即生效）") {
        HmSwitchItem(
            title = "双击快退 / 快进",
            subtitle = "左侧双击快退、右侧快进、中间播放/暂停；连续双击会累加",
            icon = Icons.Rounded.TouchApp,
            checked = f.doubleTapSeek,
            onCheckedChange = { v -> repo.update { it.copy(doubleTapSeek = v) } }
        )
        HmDivider()
        HmSwitchItem(
            title = "长按倍速",
            subtitle = "按住画面临时以 ${formatSpeed(f.longPressSpeed)}x 播放，松手恢复",
            icon = Icons.Rounded.SlowMotionVideo,
            checked = f.longPressEnabled,
            onCheckedChange = { v -> repo.update { it.copy(longPressEnabled = v) } }
        )
        if (f.longPressEnabled) {
            HmChoiceChips(
                options = listOf(1.5f, 2f, 2.5f, 3f),
                selected = f.longPressSpeed,
                label = { "${formatSpeed(it)}x" },
                onSelect = { v -> repo.update { it.copy(longPressSpeed = v) } }
            )
        }
        HmDivider()
        HmSwitchItem(
            title = "双指缩放",
            subtitle = "捏合放大画面并拖动平移（MPV 用 video-zoom，ExoPlayer 缩放渲染面）",
            icon = Icons.Rounded.Pinch,
            checked = f.pinchZoom,
            onCheckedChange = { v -> repo.update { it.copy(pinchZoom = v) } }
        )
    }

    HmGroup(title = "显示与声音（立即生效）") {
        HmSwitchItem(
            title = "刷新率匹配",
            subtitle = "切换到片源帧率整数倍的刷新率（23.976→48/120Hz），消除 3:2 拉片造成的顿挫",
            icon = Icons.Rounded.Tv,
            checked = f.matchRefreshRate,
            onCheckedChange = { v -> repo.update { it.copy(matchRefreshRate = v) } }
        )
        HmDivider()
        HmSwitchItem(
            title = "记住亮度",
            subtitle = "下次进入播放页恢复上次手势调过的亮度",
            icon = Icons.Rounded.BrightnessMedium,
            checked = f.rememberBrightness,
            onCheckedChange = { v -> repo.update { it.copy(rememberBrightness = v) } }
        )
        HmDivider()
        HmSwitchItem(
            title = "统计浮层",
            subtitle = "播放时左上角显示帧率、丢帧、解码器、码率、缓存",
            icon = Icons.Rounded.Insights,
            checked = f.statsOverlay,
            onCheckedChange = { v -> repo.update { it.copy(statsOverlay = v) } }
        )
        HmDivider()
        HmSliderItem(
            title = "音量增强",
            value = f.volumeBoostPercent,
            range = 0..100,
            valueText = "+${f.volumeBoostPercent}%",
            hint = "MPV 直接放大音量（上限 200%）；ExoPlayer 使用系统响度增强（100% ≈ +12 dB）。过高可能破音。",
            onValueChange = { v -> repo.update { it.copy(volumeBoostPercent = v) } }
        )
    }

    HmGroup(title = "高级 · 自定义 mpv 选项（下次起播生效）") {
        var text by remember { mutableStateOf(f.customMpvOptions) }
        val parsed = remember(text) { MpvUserOptions.parse(text) }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace),
                placeholder = { Text("# 每行一个 key=value，例如\nscale=ewa_lanczos\ninterpolation=yes") },
                colors = settingsTextFieldColors()
            )
            Text(
                text = buildString {
                    append("将下发 ${parsed.options.size} 项")
                    if (parsed.rejected.isNotEmpty()) append("；已拦截受保护选项：${parsed.rejected.joinToString()}")
                    if (parsed.invalidLines.isNotEmpty()) append("；无法解析的行：${parsed.invalidLines.joinToString()}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (parsed.rejected.isEmpty() && parsed.invalidLines.isEmpty()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = { repo.update { it.copy(customMpvOptions = text) } }, enabled = text != f.customMpvOptions) {
                    Text("保存选项")
                }
            }
        }
        HmHint("渲染上下文、渲染面、网络/TLS、OSD 等由应用管理的选项不可覆盖（覆盖会导致黑屏或云盘直链 403）。")
    }
}

private fun formatSpeed(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else v.toString()
