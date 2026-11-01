package com.example.localmovielibrarz.ui.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/** 双击区域（参考 mpvRx「分区双击」：左右快退快进、中间播放/暂停）。 */
enum class TapZone { Left, Center, Right }

/** 拖动的轴向。 */
enum class DragAxis { Horizontal, VerticalLeft, VerticalRight }

/** 手势开关（来自 PlayerFeatures）。 */
@Stable
data class GestureConfig(
    val doubleTapSeek: Boolean = true,
    val longPressSpeed: Boolean = true,
    val pinchZoom: Boolean = true,
    /** v1 行为：竖屏时不响应拖动手势（竖屏下方是控件区，误触率高）。 */
    val dragEnabled: Boolean = true
)

/** 手势回调。全部在主线程、手势检测协程里调用。 */
interface PlayerGestureCallbacks {
    fun onTap()
    fun onDoubleTap(zone: TapZone)
    fun onLongPressStart()
    fun onLongPressEnd()
    fun onDragStart(axis: DragAxis)
    /** [delta] 为本次位移，[total] 为从起点累计的位移（像素）。 */
    fun onDrag(axis: DragAxis, delta: Offset, total: Offset, width: Int, height: Int)
    fun onDragEnd(axis: DragAxis, cancelled: Boolean)
    fun onPinch(zoom: Float, pan: Offset, width: Int, height: Int)
    fun onPinchEnd()
}

/**
 * 播放页统一手势层。
 *
 * v1 只有「拖动调亮度/音量/进度」与「单击显隐控件」，且两者分别挂在 `detectDragGestures` 和
 * `clickable` 上。v2 参考 mpvRx 的手势体系补齐双击分区、长按倍速、双指缩放，拆成两个检测器：
 *
 * 1. **点按检测**（`detectTapGestures`）：单击 / 双击 / 长按。只要另一个检测器开始消费移动事件，
 *    点按就会被取消，所以「拖动」不会被误判成「长按」；
 * 2. **移动检测**（自定义 `awaitEachGesture`）：单指超过触控阈值后锁定轴向（横向=进度、
 *    左半竖向=亮度、右半竖向=音量）；任何时刻出现第二根手指即切换到双指缩放，并取消正在进行的拖动。
 *
 * 用一个检测器同时处理多指缩放与单指拖动，是为了避免 `detectDragGestures` 在双指时把第一根手指
 * 当作拖动、导致缩放时进度条乱跳。
 */
internal fun Modifier.playerGestures(
    enabled: Boolean,
    config: GestureConfig,
    callbacks: PlayerGestureCallbacks
): Modifier {
    if (!enabled) return this
    return this
        .pointerInput(config) {
            detectTapGestures(
                onTap = { callbacks.onTap() },
                onDoubleTap = if (config.doubleTapSeek) {
                    { offset ->
                        val third = size.width / 3f
                        val zone = when {
                            offset.x < third -> TapZone.Left
                            offset.x > third * 2 -> TapZone.Right
                            else -> TapZone.Center
                        }
                        callbacks.onDoubleTap(zone)
                    }
                } else {
                    null
                },
                onLongPress = if (config.longPressSpeed) {
                    { callbacks.onLongPressStart() }
                } else {
                    null
                },
                onPress = {
                    tryAwaitRelease()
                    // 无论长按是否触发过都通知一次；回调方按自身状态判断是否需要恢复倍速。
                    callbacks.onLongPressEnd()
                }
            )
        }
        .pointerInput(config) {
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var axis: DragAxis? = null
                var pinching = false
                var total = Offset.Zero
                var lifted = false
                while (!lifted) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    if (pressed >= 2 && config.pinchZoom) {
                        if (axis != null) {
                            callbacks.onDragEnd(axis, cancelled = true)
                            axis = null
                        }
                        pinching = true
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f || pan != Offset.Zero) {
                            callbacks.onPinch(zoom, pan, size.width, size.height)
                        }
                        event.changes.forEach { it.consume() }
                    } else if (!pinching && config.dragEnabled) {
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change != null && change.pressed) {
                            val delta = change.positionChange()
                            total += delta
                            if (axis == null && (abs(total.x) > slop || abs(total.y) > slop)) {
                                axis = if (abs(total.x) > abs(total.y)) {
                                    DragAxis.Horizontal
                                } else if (down.position.x < size.width / 2f) {
                                    DragAxis.VerticalLeft
                                } else {
                                    DragAxis.VerticalRight
                                }
                                callbacks.onDragStart(axis)
                            }
                            if (axis != null) {
                                callbacks.onDrag(axis, delta, total, size.width, size.height)
                                change.consume()
                            }
                        }
                    }
                    lifted = event.changes.none { it.pressed }
                }
                axis?.let { callbacks.onDragEnd(it, cancelled = false) }
                if (pinching) callbacks.onPinchEnd()
            }
        }
}
