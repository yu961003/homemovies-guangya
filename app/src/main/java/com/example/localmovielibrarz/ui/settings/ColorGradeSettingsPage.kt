package com.example.localmovielibrarz.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.localmovielibrarz.playback.lut.CubeLut
import com.example.localmovielibrarz.playback.lut.LutColorMath
import com.example.localmovielibrarz.playback.lut.LutCreativeKey
import com.example.localmovielibrarz.playback.lut.LutCreativeParams
import com.example.localmovielibrarz.playback.lut.LutLook
import com.example.localmovielibrarz.playback.lut.LutOverlayImporter
import com.example.localmovielibrarz.playback.lut.LutParamKey
import com.example.localmovielibrarz.playback.lut.LutParams
import com.example.localmovielibrarz.playback.lut.LutPreset
import com.example.localmovielibrarz.playback.lut.LutSettings
import com.example.localmovielibrarz.ui.AppRadius
import com.example.localmovielibrarz.ui.components.HmChoiceChips
import com.example.localmovielibrarz.ui.components.HmDivider
import com.example.localmovielibrarz.ui.components.HmGroup
import com.example.localmovielibrarz.ui.components.HmHint
import com.example.localmovielibrarz.ui.components.HmListItem
import com.example.localmovielibrarz.ui.components.HmSelectableCard
import com.example.localmovielibrarz.ui.components.HmSliderItem
import com.example.localmovielibrarz.ui.components.HmSwitchItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 实时调色设置页（v2 重写）。
 *
 * v1 页面只有「总开关 + 4 个预设按钮 + 7 个滑杆 + 强度/尺寸」，且说明文字仍写着「exo 内核不支持」
 * （ExoLutEffect 接入后已不成立）。v2：
 * - **风格预设**：8 个 Look，每个带「调色前 → 调色后」的色样对比（肤色 / 天空 / 草地 / 暗部 / 高光），
 *   直接用同一份 [LutColorMath] 算出，所见即所得；
 * - **创意调色**：自然饱和度、色相、褪色、分离色调（阴影/高光各自色相+强度+平衡）、肤色保护；
 * - **风格 LUT 叠加**：导入任意 3D `.cube`，按比例混合在参数调色之后；
 * - 文案更正为「两个内核都实时生效、HDR 片源自动透传」。
 */
@Composable
internal fun ColorGradeSettingsPage(
    uiState: SettingsUiState,
    onEnabledChange: (Boolean) -> Unit,
    onParamChange: (LutParamKey, Int) -> Unit,
    onStrengthChange: (Int) -> Unit,
    onLutSizeChange: (Int) -> Unit,
    onPresetSelect: (LutPreset) -> Unit,
    onLookSelect: (LutLook) -> Unit,
    onCreativeChange: (LutCreativeKey, Int) -> Unit,
    onCreativeReset: () -> Unit,
    onOverlayChange: (String?, String?) -> Unit,
    onOverlayMixChange: (Int) -> Unit,
    onMessage: (String) -> Unit
) {
    val settings = uiState.lutSettings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { LutOverlayImporter.import(context, uri) }
            result
                .onSuccess { onOverlayChange(it.path, "${it.displayName}（${it.size}³）") }
                .onFailure { onMessage("导入失败：${it.message}") }
        }
    }

    HmGroup(title = "总开关") {
        HmSwitchItem(
            title = "启用实时调色",
            subtitle = when {
                !settings.enabled -> "关闭 · 画面保持片源原色"
                settings.shouldApply -> "已生效 · ${settings.lutSize}³ · 强度 ${settings.strengthPercent}% · MPV 与 ExoPlayer 均实时生效"
                else -> "已开启，但所有参数为默认值（画面无变化）"
            },
            icon = Icons.Rounded.Palette,
            checked = settings.enabled,
            onCheckedChange = onEnabledChange
        )
    }

    HmGroup(title = "风格预设") {
        HmHint("一键套用，会同时替换下方的基础参数与创意参数。色样展示同一组颜色在调色前后的变化。")
        LookGrid(settings = settings, onLookSelect = onLookSelect)
    }

    HmGroup(title = "参考模板（自动分析的三个起点）") {
        HmChoiceChips(
            options = LutPreset.orderedForUi,
            selected = LutPreset.orderedForUi.firstOrNull { it.params == settings.params && settings.creative.isNeutral() },
            label = { it.label },
            onSelect = onPresetSelect
        )
        val active = LutPreset.orderedForUi.firstOrNull { it.params == settings.params }
        HmHint(active?.description ?: "自定义参数 · 已偏离全部模板")
    }

    HmGroup(title = "基础参数") {
        HmHint("范围 -100 ~ 100，0 表示不介入。拖动即时生效。")
        LutParamKey.entries.forEachIndexed { index, key ->
            val value = settings.params.get(key)
            HmSliderItem(
                title = key.label,
                value = value,
                range = LutParams.MIN..LutParams.MAX,
                valueText = if (value > 0) "+$value" else "$value",
                hint = key.hint,
                onValueChange = { onParamChange(key, it) }
            )
            if (index != LutParamKey.entries.lastIndex) HmDivider()
        }
    }

    HmGroup(title = "创意调色") {
        HmHint("在 Oklab 色彩空间计算：转色相、拉色度时亮度基本不变；越界颜色按色相保真软裁剪。")
        val creative = settings.creative
        LutCreativeKey.entries.forEach { key ->
            val value = creative.get(key)
            if (key.isAngle) {
                val enabledAmount = when (key) {
                    LutCreativeKey.ShadowTintHue -> creative.shadowTintAmount > 0
                    LutCreativeKey.HighlightTintHue -> creative.highlightTintAmount > 0
                    else -> true
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
                    Box(Modifier.weight(1f)) {
                        HmSliderItem(
                            title = key.label,
                            value = value,
                            range = key.min..key.max,
                            valueText = "$value°",
                            hint = key.hint,
                            enabled = enabledAmount,
                            onValueChange = { onCreativeChange(key, it) }
                        )
                    }
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color.hsv(value.toFloat(), 0.75f, 0.95f))
                    )
                }
            } else {
                HmSliderItem(
                    title = key.label,
                    value = value,
                    range = key.min..key.max,
                    valueText = if (key.min < 0 && value > 0) "+$value" else "$value",
                    hint = key.hint,
                    onValueChange = { onCreativeChange(key, it) }
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onCreativeReset, enabled = !creative.isNeutral() || creative.skinProtect != 0) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                Text("创意参数归零", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }

    HmGroup(title = "风格 LUT 叠加") {
        val overlayName = settings.overlayLutPath?.substringAfterLast('/')
        HmListItem(
            title = if (overlayName == null) "导入 .cube 风格 LUT" else "已叠加风格 LUT",
            subtitle = if (overlayName == null) {
                "支持调色软件导出的 3D LUT（0..1 定义域）。叠加在参数调色之后，以四面体插值采样。"
            } else {
                "文件 $overlayName · 混合 ${settings.overlayMixPercent}%"
            },
            icon = Icons.Rounded.FileOpen,
            onClick = { importer.launch(arrayOf("*/*")) }
        )
        if (overlayName != null) {
            HmSliderItem(
                title = "混合比例",
                value = settings.overlayMixPercent,
                range = 0..100,
                valueText = "${settings.overlayMixPercent}%",
                onValueChange = onOverlayMixChange
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { onOverlayChange(null, null) }) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                    Text("移除", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }

    HmGroup(title = "强度与精度") {
        HmSliderItem(
            title = "叠加强度",
            value = settings.strengthPercent,
            range = LutSettings.MIN_STRENGTH..LutSettings.MAX_STRENGTH,
            valueText = "${settings.strengthPercent}%",
            hint = "0% 等同关闭；100% 为参数原样。默认 80%。",
            onValueChange = onStrengthChange
        )
        HmDivider()
        HmChoiceChips(
            options = LutSettings.SIZE_OPTIONS,
            selected = LutSettings.SIZE_OPTIONS.firstOrNull { it.size == settings.lutSize },
            label = { it.title },
            onSelect = { onLutSizeChange(it.size) }
        )
        HmHint(
            (LutSettings.SIZE_OPTIONS.firstOrNull { it.size == settings.lutSize }?.description ?: "") +
                "。${CubeLut.MIN_SIZE}³ 以下不可选。v2 生成在后台线程并行完成，65³ 也不再卡界面。"
        )
    }

    HmGroup(title = "能力说明") {
        HmListItem(
            title = "两个内核都实时生效",
            subtitle = "MPV 通过 target-lut 重载，ExoPlayer 通过 media3 SingleColorLut；同一组参数、同一张 LUT。"
        )
        HmDivider()
        HmListItem(
            title = "HDR 片源自动透传",
            subtitle = "LUT 只适用于 SDR。ExoPlayer 遇到 HDR 自动跳过；MPV 开启 HDR 色调映射时调色暂停、回到 SDR 片源自动恢复。"
        )
        HmDivider()
        HmListItem(
            title = "自动分析调参（人脸识别）",
            subtitle = "播放页 → 右上角面板 → 调色 → 自动分析。用 MediaPipe 找到人脸，只在脸部皮肤上统计肤色，背景不再干扰。",
            icon = Icons.Rounded.AutoAwesome
        )
    }
}

/** 风格预设卡片网格：名称 + 描述 + 前后色样。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookGrid(settings: LutSettings, onLookSelect: (LutLook) -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 2
    ) {
        LutLook.entries.forEach { look ->
            val selected = settings.enabled && look.params == settings.params && look.creative == settings.creative
            HmSelectableCard(
                selected = selected,
                onClick = { onLookSelect(look) },
                modifier = Modifier.weight(1f)
            ) {
                Text(look.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    look.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                SwatchStrip(look.params, look.creative)
            }
        }
    }
}

/** 色样对比：上排原色、下排调色后（与渲染用的是同一套算子）。 */
@Composable
private fun SwatchStrip(params: LutParams, creative: LutCreativeParams) {
    val samples = remember {
        listOf(
            floatArrayOf(0.86f, 0.66f, 0.55f), // 肤色
            floatArrayOf(0.45f, 0.65f, 0.90f), // 天空
            floatArrayOf(0.35f, 0.62f, 0.30f), // 草地
            floatArrayOf(0.12f, 0.11f, 0.13f), // 暗部
            floatArrayOf(0.93f, 0.90f, 0.84f)  // 高光
        )
    }
    val graded = remember(params, creative) {
        samples.map { c -> LutColorMath.applyAll(c[0], c[1], c[2], params, creative).let { (r, g, b) -> Color(r, g, b) } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.clip(AppRadius.XS)) {
            samples.forEach { c -> Box(Modifier.width(22.dp).height(12.dp).background(Color(c[0], c[1], c[2]))) }
        }
        Row(Modifier.clip(AppRadius.XS)) {
            graded.forEach { c -> Box(Modifier.width(22.dp).height(12.dp).background(c)) }
        }
    }
}
