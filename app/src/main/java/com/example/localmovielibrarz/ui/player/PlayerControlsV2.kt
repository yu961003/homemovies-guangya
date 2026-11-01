package com.example.localmovielibrarz.ui.player

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.localmovielibrarz.playback.engine.MpvHwdecMode
import com.example.localmovielibrarz.playback.engine.PlaybackEngineKind
import com.example.localmovielibrarz.playback.lut.LutLook
import com.example.localmovielibrarz.playback.lut.LutSettings
import com.example.localmovielibrarz.playback.vr.VrControlMode
import com.example.localmovielibrarz.playback.vr.VrMode
import com.example.localmovielibrarz.ui.AppMotion
import com.example.localmovielibrarz.ui.AppRadius
import com.example.localmovielibrarz.ui.MinTouchTarget
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 播放器 v2 控件（MD3 风格的「影院」深色外观）。
 *
 * 设计要点：
 * - **视频优先**：控件只用上下两条渐变遮罩承载，中部只放三个传输按钮，不遮挡画面主体；
 * - **主题色点睛**：播放键、进度条、选中态用当前主题的 primary，其余一律白色系，深浅主题下都在视频上可读；
 * - **状态可见**：顶栏标题下方的芯片把「当前内核 / HDR 管线 / 调色风格 / A-B 循环 / 睡眠定时」一眼摆清楚；
 * - **工具分层**：高频操作（倍速、比例、A-B、截图、横竖屏）放底栏芯片；低频设置收进右侧分页面板。
 */
internal object PlayerChrome {
    val Glass = Color(0x8A0E1216)
    val GlassStrong = Color(0xF2121619)
    val GlassItem = Color(0x14FFFFFF)
    val GlassItemSelected = Color(0x29FFFFFF)
    val OnGlass = Color.White
    val OnGlassMuted = Color(0xB3FFFFFF)
    val OnGlassFaint = Color(0x80FFFFFF)
}

/** 控件层的全部回调（避免巨型参数列表在多个 Composable 之间来回传）。 */
internal class PlayerControlActions(
    val onBack: () -> Unit,
    val onTogglePlay: () -> Unit,
    val onSeekBack: () -> Unit,
    val onSeekForward: () -> Unit,
    val onSeek: (Float) -> Unit,
    val onSpeedSelected: (Float) -> Unit,
    val onAspect: () -> Unit,
    val onOrientation: () -> Unit,
    val onAudioTrack: () -> Unit,
    val onSubtitle: () -> Unit,
    val onSwitchEngine: () -> Unit,
    val onOpenSheet: () -> Unit,
    val onAbLoop: () -> Unit,
    val onScreenshot: () -> Unit
)

// ─────────────────────────────────────────────────────────────────────────────
// 控件层
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun PlayerControlsOverlay(
    title: String,
    statusChips: List<String>,
    isPlaying: Boolean,
    isLandscape: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    abLoopA: Long?,
    abLoopB: Long?,
    progressBarStyle: PlayerProgressBarStyleSettings,
    speed: Float,
    speeds: List<Float>,
    errorMessage: String?,
    aspectLabel: String,
    showSubtitle: Boolean,
    showAudio: Boolean,
    engineKind: PlaybackEngineKind,
    engineSwitching: Boolean,
    actions: PlayerControlActions
) {
    Box(modifier = Modifier.fillMaxSize()) {
        PlayerTopBar(
            title = title,
            statusChips = statusChips,
            engineKind = engineKind,
            engineSwitching = engineSwitching,
            showSubtitle = showSubtitle,
            showAudio = showAudio,
            actions = actions,
            modifier = Modifier.align(Alignment.TopCenter)
        )
        PlayerTransport(
            isPlaying = isPlaying,
            onSeekBack = actions.onSeekBack,
            onToggle = actions.onTogglePlay,
            onSeekForward = actions.onSeekForward,
            modifier = Modifier.align(Alignment.Center)
        )
        PlayerBottomBar(
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            abLoopA = abLoopA,
            abLoopB = abLoopB,
            progressBarStyle = progressBarStyle,
            speed = speed,
            speeds = speeds,
            errorMessage = errorMessage,
            aspectLabel = aspectLabel,
            isLandscape = isLandscape,
            actions = actions,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun PlayerTopBar(
    title: String,
    statusChips: List<String>,
    engineKind: PlaybackEngineKind,
    engineSwitching: Boolean,
    showSubtitle: Boolean,
    showAudio: Boolean,
    actions: PlayerControlActions,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 28.dp),
        verticalAlignment = Alignment.Top
    ) {
        GlassCircleButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = actions.onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 8.dp, top = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                color = PlayerChrome.OnGlass,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (statusChips.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    statusChips.forEach { StatusChip(it) }
                }
            }
        }
        EngineSwitchPill(
            current = engineKind,
            switching = engineSwitching,
            onSwitch = actions.onSwitchEngine,
            modifier = Modifier.padding(top = 9.dp, end = 8.dp)
        )
        if (showAudio) GlassCircleButton(Icons.Rounded.VolumeUp, "音轨", onClick = actions.onAudioTrack)
        if (showSubtitle) GlassCircleButton(Icons.Rounded.ClosedCaption, "字幕", onClick = actions.onSubtitle)
        GlassCircleButton(Icons.Rounded.Tune, "播放模式", onClick = actions.onOpenSheet)
    }
}

@Composable
private fun StatusChip(text: String) {
    Text(
        text = text,
        color = PlayerChrome.OnGlass,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 1,
        modifier = Modifier
            .clip(AppRadius.Full)
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 3.dp)
    )
}

@Composable
private fun PlayerTransport(
    isPlaying: Boolean,
    onSeekBack: () -> Unit,
    onToggle: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassCircleButton(Icons.Rounded.Replay10, "Replay 10 seconds", size = 56, iconSize = 30, onClick = onSeekBack)
        val primary = MaterialTheme.colorScheme.primary
        val onPrimary = MaterialTheme.colorScheme.onPrimary
        IconButton(
            onClick = onToggle,
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(primary.copy(alpha = 0.92f))
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = onPrimary,
                modifier = Modifier.size(40.dp)
            )
        }
        GlassCircleButton(Icons.Rounded.Forward10, "Forward 10 seconds", size = 56, iconSize = 30, onClick = onSeekForward)
    }
}

@Composable
private fun PlayerBottomBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    abLoopA: Long?,
    abLoopB: Long?,
    progressBarStyle: PlayerProgressBarStyleSettings,
    speed: Float,
    speeds: List<Float>,
    errorMessage: String?,
    aspectLabel: String,
    isLandscape: Boolean,
    actions: PlayerControlActions,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 20.dp, end = 20.dp, top = 36.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        errorMessage?.let {
            Text(
                text = "Playback failed: $it",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        PlayerSeekBar(
            positionMs = positionMs,
            durationMs = durationMs,
            bufferedMs = bufferedMs,
            abLoopA = abLoopA,
            abLoopB = abLoopB,
            style = progressBarStyle,
            onSeek = actions.onSeek
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpeedChip(speed = speed, speeds = speeds, onSpeedSelected = actions.onSpeedSelected)
                ToolChip(Icons.Rounded.AspectRatio, aspectLabel, onClick = actions.onAspect)
                ToolChip(
                    Icons.Rounded.Repeat,
                    when {
                        abLoopA == null -> "A-B"
                        abLoopB == null -> "设 B 点"
                        else -> "A-B 开"
                    },
                    selected = abLoopA != null,
                    onClick = actions.onAbLoop
                )
                ToolChip(Icons.Rounded.PhotoCamera, "截图", onClick = actions.onScreenshot)
            }
            ToolChip(
                icon = if (isLandscape) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                label = if (isLandscape) "竖屏" else "横屏",
                onClick = actions.onOrientation
            )
        }
    }
}

/**
 * 进度条：已缓冲 / 已播放 / A-B 区间三层，拖动时只在本地预览，松手才 seek
 * （沿用 v1 H+1 轮的优化：拖动不再每帧触发 seek）。
 */
@Composable
private fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    abLoopA: Long?,
    abLoopB: Long?,
    style: PlayerProgressBarStyleSettings,
    onSeek: (Float) -> Unit
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var widthPx by remember { mutableIntStateOf(1) }
    val hasDuration = durationMs > 0
    val played = if (hasDuration) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (hasDuration) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val shown = dragFraction ?: played
    val barColor = Color(style.colorArgb).copy(alpha = style.alphaPercent / 100f)
    val trackHeight = style.widthDp.coerceIn(2, 12).dp
    val thumbScale by animateFloatAsState(if (dragFraction != null) 1.35f else 1f, tween(AppMotion.DurationShort), label = "thumb")

    Row(verticalAlignment = Alignment.CenterVertically) {
        TimeLabel(formatClock(dragFraction?.let { (durationMs * it).toLong() } ?: positionMs))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .padding(horizontal = 12.dp)
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
                .pointerInput(hasDuration) {
                    if (!hasDuration) return@pointerInput
                    detectTapGestures { offset -> onSeek((offset.x / widthPx).coerceIn(0f, 1f)) }
                }
                .pointerInput(hasDuration) {
                    if (!hasDuration) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> dragFraction = (offset.x / widthPx).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let(onSeek)
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragFraction = ((dragFraction ?: played) + amount / widthPx).coerceIn(0f, 1f)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(trackHeight.coerceAtLeast(4.dp) + 14.dp)) {
                val h = trackHeight.toPx()
                val top = (size.height - h) / 2f
                val radius = CornerRadius(h / 2f, h / 2f)
                drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, top), Size(size.width, h), radius)
                drawRoundRect(Color.White.copy(alpha = 0.38f), Offset(0f, top), Size(size.width * buffered, h), radius)
                if (hasDuration && abLoopA != null) {
                    val a = (abLoopA.toFloat() / durationMs).coerceIn(0f, 1f) * size.width
                    val b = (abLoopB?.toFloat()?.div(durationMs))?.coerceIn(0f, 1f)?.times(size.width)
                    if (b != null) {
                        drawRect(Color(0x66FFD54F), Offset(a, top - 3f), Size(b - a, h + 6f))
                    }
                    drawRect(Color(0xFFFFD54F), Offset(a - 1.5f, top - 6f), Size(3f, h + 12f))
                    if (b != null) drawRect(Color(0xFFFFD54F), Offset(b - 1.5f, top - 6f), Size(3f, h + 12f))
                }
                drawRoundRect(barColor, Offset(0f, top), Size(size.width * shown, h), radius)
                if (hasDuration) {
                    drawCircle(barColor, radius = (h * 1.4f + 4f) * thumbScale, center = Offset(size.width * shown, size.height / 2f))
                }
            }
            // 拖动时的时间气泡
            if (dragFraction != null) {
                val density = LocalDensity.current
                val bubbleX = with(density) { (widthPx * shown).toDp() - 28.dp }
                Text(
                    text = formatClock((durationMs * shown).toLong()),
                    color = Color.Black,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = bubbleX, y = (-26).dp)
                        .clip(AppRadius.Full)
                        .background(Color.White)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        TimeLabel(if (hasDuration) formatClock(durationMs) else "--:--")
    }
}

@Composable
private fun TimeLabel(text: String) {
    Text(text = text, color = PlayerChrome.OnGlassMuted, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun SpeedChip(speed: Float, speeds: List<Float>, onSpeedSelected: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ToolChip(Icons.Rounded.Speed, "${formatSpeedLabel(speed)}x", selected = abs(speed - 1f) > 0.001f) { expanded = true }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = PlayerChrome.GlassStrong,
            shape = AppRadius.MD
        ) {
            speeds.asReversed().forEach { option ->
                val selected = abs(option - speed) < 0.001f
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${formatSpeedLabel(option)}x",
                                color = if (selected) MaterialTheme.colorScheme.primary else PlayerChrome.OnGlass,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.width(64.dp)
                            )
                            Text(if (selected) "✓" else "", color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    onClick = {
                        onSpeedSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ToolChip(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .sizeIn(minHeight = 40.dp)
            .clip(AppRadius.Full)
            .background(if (selected) primary.copy(alpha = 0.28f) else PlayerChrome.Glass)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) primary else PlayerChrome.OnGlass, modifier = Modifier.size(18.dp))
        Text(label, color = PlayerChrome.OnGlass, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun GlassCircleButton(
    icon: ImageVector,
    contentDescription: String,
    size: Int = 48,
    iconSize: Int = 24,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .size(size.dp)
            .clip(CircleShape)
            .background(PlayerChrome.Glass)
    ) {
        Icon(icon, contentDescription = contentDescription, tint = PlayerChrome.OnGlass, modifier = Modifier.size(iconSize.dp))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 内核切换 pill（v1 组件迁移至此，行为不变：只读 current，写只走 onSwitch）
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun EngineSwitchPill(
    current: PlaybackEngineKind,
    switching: Boolean,
    onSwitch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EngineSwitchSegment("MPV", current == PlaybackEngineKind.MPV, !switching) {
            if (current != PlaybackEngineKind.MPV) onSwitch()
        }
        EngineSwitchSegment("EXO", current == PlaybackEngineKind.EXO, !switching) {
            if (current != PlaybackEngineKind.EXO) onSwitch()
        }
        if (switching) {
            CircularProgressIndicator(
                modifier = Modifier.padding(horizontal = 4.dp).size(11.dp),
                strokeWidth = 1.4.dp,
                color = Color.White
            )
        }
    }
}

@Composable
private fun EngineSwitchSegment(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) primary else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (active) MaterialTheme.colorScheme.onPrimary else Color.White.copy(alpha = 0.78f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 右侧分页面板
// ─────────────────────────────────────────────────────────────────────────────

private enum class SheetTab(val label: String) { Picture("画面"), Grade("调色"), Playback("播放"), Decode("解码") }

/** 面板状态与回调（只读快照 + 动作）。 */
internal class PlayerSheetModel(
    val vrMode: VrMode,
    val vrControlMode: VrControlMode,
    val engineKind: PlaybackEngineKind,
    val hwdecMode: MpvHwdecMode,
    val lutAutoTuneBusy: Boolean,
    val lutAutoTuneMessage: String?,
    val lutSettings: LutSettings,
    val sleepTimerEndsAt: Long?,
    val statsVisible: Boolean,
    val volumeBoostPercent: Int,
    val zoomScale: Float,
    val cropActive: Boolean,
    val hdrLabel: String,
    val onVrMode: (VrMode) -> Unit,
    val onVrControlMode: (VrControlMode) -> Unit,
    val onHwdecMode: (MpvHwdecMode) -> Unit,
    val onAutoTune: () -> Unit,
    val onLook: (LutLook?) -> Unit,
    val onLutStrength: (Int) -> Unit,
    val onSleepTimer: (SleepTimerOption?) -> Unit,
    val onFrameStep: (Boolean) -> Unit,
    val onToggleStats: () -> Unit,
    val onVolumeBoost: (Int) -> Unit,
    val onResetZoom: () -> Unit,
    val onAutoCrop: () -> Unit,
    val onClearCrop: () -> Unit
)

@Composable
internal fun PlayerSideSheet(
    visible: Boolean,
    model: PlayerSheetModel,
    onDismiss: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().zIndex(19f)) {
        val quiet = remember { MutableInteractionSource() }
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable(interactionSource = quiet, indication = null, onClick = onDismiss)
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(tween(AppMotion.DurationMedium, easing = AppMotion.EmphasizedDecelerate)) { it } + fadeIn(),
            exit = slideOutHorizontally(tween(AppMotion.DurationShort, easing = AppMotion.EmphasizedAccelerate)) { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxHeight()) {
                val width = when {
                    maxWidth < 360.dp -> maxWidth
                    maxWidth < 560.dp -> maxWidth - 56.dp
                    else -> 360.dp
                }
                var tab by remember { mutableStateOf(SheetTab.Grade) }
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(width)
                        .clip(RoundedCornerShape(topStart = AppRadius.XL_Dp, bottomStart = AppRadius.XL_Dp))
                        .background(PlayerChrome.GlassStrong)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "播放模式",
                            color = PlayerChrome.OnGlass,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = "收起面板", tint = PlayerChrome.OnGlass)
                        }
                    }
                    SheetTabs(selected = tab, onSelect = { tab = it }, showDecode = model.engineKind == PlaybackEngineKind.MPV)
                    Column(
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (tab) {
                            SheetTab.Picture -> PictureTab(model)
                            SheetTab.Grade -> ColorTab(model)
                            SheetTab.Playback -> PlaybackTab(model)
                            SheetTab.Decode -> DecodeTab(model)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTabs(selected: SheetTab, onSelect: (SheetTab) -> Unit, showDecode: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppRadius.Full)
            .background(PlayerChrome.GlassItem)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SheetTab.entries.filter { showDecode || it != SheetTab.Decode }.forEach { tab ->
            val active = tab == selected
            Text(
                text = tab.label,
                color = if (active) MaterialTheme.colorScheme.onPrimary else PlayerChrome.OnGlassMuted,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(AppRadius.Full)
                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun SheetSection(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
    )
}

@Composable
private fun SheetOption(
    title: String,
    subtitle: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppRadius.MD)
            .background(
                when {
                    selected -> PlayerChrome.GlassItemSelected
                    enabled -> PlayerChrome.GlassItem
                    else -> Color.White.copy(alpha = 0.04f)
                }
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (selected) MaterialTheme.colorScheme.primary else PlayerChrome.OnGlassMuted, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = PlayerChrome.OnGlass.copy(alpha = if (enabled) 1f else 0.38f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = PlayerChrome.OnGlassFaint.copy(alpha = if (enabled) 0.8f else 0.3f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (selected) {
            Text(
                text = "✓",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SheetChips(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) { content() }
}

@Composable
private fun SheetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else PlayerChrome.OnGlass,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(AppRadius.Full)
            .background(if (selected) MaterialTheme.colorScheme.primary else PlayerChrome.GlassItem)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun SheetSlider(title: String, value: Int, range: IntRange, valueText: String, onChange: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AppRadius.MD)
            .background(PlayerChrome.GlassItem)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = PlayerChrome.OnGlass, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local.roundToInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
            )
        )
    }
}

@Composable
private fun PictureTab(model: PlayerSheetModel) {
    SheetSection("画面")
    SheetOption(
        title = if (model.cropActive) "恢复完整画面" else "自动裁黑边",
        subtitle = if (model.engineKind == PlaybackEngineKind.MPV) "分析当前帧，去掉片源自带的黑边（暗场景会保守放弃）" else "仅 MPV 内核支持",
        icon = Icons.Rounded.Crop,
        enabled = model.engineKind == PlaybackEngineKind.MPV,
        onClick = { if (model.cropActive) model.onClearCrop() else model.onAutoCrop() }
    )
    SheetOption(
        title = "重置缩放",
        subtitle = "当前 ${String.format(java.util.Locale.US, "%.1f", model.zoomScale)}x · 双指捏合可缩放与平移",
        icon = Icons.Rounded.ZoomOutMap,
        enabled = model.zoomScale != 1f,
        onClick = model.onResetZoom
    )
    SheetOption(title = "HDR 色调映射", subtitle = model.hdrLabel, icon = Icons.Rounded.AutoAwesome, enabled = false, onClick = {})
    SheetSection("全景模式")
    VrMode.entries.forEach { mode ->
        SheetOption(title = mode.label, subtitle = mode.modeSubtitle(), selected = mode == model.vrMode) { model.onVrMode(mode) }
    }
    SheetSection("全景控制")
    VrControlMode.entries.forEach { mode ->
        SheetOption(
            title = mode.label,
            subtitle = mode.controlSubtitle(),
            selected = mode == model.vrControlMode,
            enabled = model.vrMode.isVr
        ) { model.onVrControlMode(mode) }
    }
}

@Composable
private fun ColorTab(model: PlayerSheetModel) {
    val lut = model.lutSettings
    SheetSection("智能调色")
    SheetOption(
        title = if (model.lutAutoTuneBusy) "正在分析当前画面…" else "自动分析调参",
        subtitle = if (model.lutAutoTuneBusy) "截帧 → 人脸识别（MediaPipe）→ 肤色统计 → 搜参" else (model.lutAutoTuneMessage ?: "按当前画面的人脸肤色推一组参数（两个内核都可用）"),
        icon = Icons.Rounded.AutoAwesome,
        enabled = !model.lutAutoTuneBusy,
        onClick = model.onAutoTune
    )
    SheetSection("风格")
    val activeLook = LutLook.entries.firstOrNull { it.params == lut.params && it.creative == lut.creative }
    SheetChips {
        SheetChip("关闭", selected = !lut.enabled) { model.onLook(null) }
        LutLook.entries.forEach { look ->
            SheetChip(look.label, selected = lut.enabled && look == activeLook) { model.onLook(look) }
        }
    }
    if (lut.enabled) {
        Text(
            text = activeLook?.description ?: "自定义参数（可在 设置 → 播放器 → 实时调色 中精调）",
            color = PlayerChrome.OnGlassFaint,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        SheetSlider("强度", lut.strengthPercent, LutSettings.MIN_STRENGTH..LutSettings.MAX_STRENGTH, "${lut.strengthPercent}%", model.onLutStrength)
    }
}

@Composable
private fun PlaybackTab(model: PlayerSheetModel) {
    SheetSection("睡眠定时")
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(model.sleepTimerEndsAt) {
        while (model.sleepTimerEndsAt != null) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val remaining = model.sleepTimerEndsAt?.let { (it - now).coerceAtLeast(0L) }
    if (remaining != null) {
        Text(
            text = "剩余 ${formatClock(remaining)} 后暂停",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
    SheetChips {
        SheetChip("关闭", selected = model.sleepTimerEndsAt == null) { model.onSleepTimer(null) }
        SleepTimerOption.entries.forEach { option -> SheetChip(option.label, selected = false) { model.onSleepTimer(option) } }
    }
    SheetSection("逐帧")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f)) { SheetOption("上一帧", icon = Icons.Rounded.SkipPrevious) { model.onFrameStep(false) } }
        Box(Modifier.weight(1f)) { SheetOption("下一帧", icon = Icons.Rounded.SkipNext) { model.onFrameStep(true) } }
    }
    SheetSection("声音")
    SheetSlider("音量增强", model.volumeBoostPercent, 0..100, "+${model.volumeBoostPercent}%", model.onVolumeBoost)
    SheetSection("诊断")
    SheetOption(
        title = "统计浮层",
        subtitle = "帧率、丢帧、解码器、码率、缓存",
        icon = Icons.Rounded.Insights,
        selected = model.statsVisible,
        onClick = model.onToggleStats
    )
}

@Composable
private fun DecodeTab(model: PlayerSheetModel) {
    SheetSection("解码（MPV）")
    MpvHwdecMode.entries.forEach { mode ->
        SheetOption(title = mode.label, subtitle = mode.subtitle, selected = mode == model.hwdecMode) { model.onHwdecMode(mode) }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 浮层：统计 / 轻提示 / 双击涟漪 / 长按倍速
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun PlayerStatsOverlay(lines: List<StatsLine>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    Column(
        modifier = modifier
            .clip(AppRadius.MD)
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        lines.forEach { line ->
            Row {
                Text(line.label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(52.dp))
                Text(line.value, color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
internal fun PlayerToast(message: String?, onConsumed: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(2_400)
            onConsumed()
        }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        Text(
            text = message.orEmpty(),
            color = MaterialTheme.colorScheme.inverseOnSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .clip(AppRadius.Full)
                .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.94f))
                .padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}

/** 双击快进/快退反馈（连续双击会累加秒数，参考 mpvRx 的多击连续 seek）。 */
internal data class SeekRipple(val zone: TapZone, val seconds: Int, val version: Long)

@Composable
internal fun SeekRippleOverlay(ripple: SeekRipple?, onTimeout: () -> Unit) {
    LaunchedEffect(ripple?.version) {
        if (ripple != null) {
            delay(750)
            onTimeout()
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = ripple != null && ripple.zone != TapZone.Center,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 1.1f),
            modifier = Modifier.align(if (ripple?.zone == TapZone.Left) Alignment.CenterStart else Alignment.CenterEnd)
        ) {
            val current = ripple ?: return@AnimatedVisibility
            Column(
                modifier = Modifier
                    .padding(horizontal = 48.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .padding(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    if (current.zone == TapZone.Left) Icons.Rounded.FastRewind else Icons.Rounded.FastForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
                Text(
                    text = if (current.zone == TapZone.Left) "-${current.seconds}s" else "+${current.seconds}s",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
internal fun SpeedBoostBadge(speed: Float?, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = speed != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(AppRadius.Full)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Rounded.FastForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Text("${formatSpeedLabel(speed ?: 1f)}x 倍速中", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 文案工具
// ─────────────────────────────────────────────────────────────────────────────

internal fun formatSpeedLabel(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else String.format(java.util.Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')

internal fun formatClock(ms: Long): String = PlayerTools.formatClock(ms)
