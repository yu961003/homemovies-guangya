package com.example.localmovielibrarz.ui

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 设计系统 · Token
 *
 * 此前全仓没有 token 层：圆角 21 种取值（226 处）、间距 2dp 奇数步进无栅格、
 * 语义色红 8 种黄 5 种绿 3 种各自写死。这里统一定义。
 */

/** 搜索输入防抖：网盘搜索走远端 API，逐字符请求会被限流。 */
const val SEARCH_DEBOUNCE_MS = 300L

/**
 * 次要文字透明度阶梯。
 * 实测（onSurface #F4F7FA 叠 surface #111720）：
 * - 0.30 → 2.59:1、0.38 → 3.40:1、0.42 → 3.88:1、0.45 → 4.27:1 —— 均低于 WCAG AA 4.5:1
 * - 0.52 → 5.25:1、0.62 → 7.01:1、0.72 → 9.02:1 —— 达标
 * 因此正文一律不低于 [ContentAlphaMin]。
 */
const val ContentAlphaMin = 0.52f
const val ContentAlphaSecondary = 0.72f
const val ContentAlphaHigh = 0.88f

/** 最小触控目标（Material 规范 48dp；24dp 的图标按钮在手机上误触率极高）。 */
val MinTouchTarget: Dp = 48.dp

/**
 * 圆角阶梯：6 / 8 / 12 / 16 / 24 / 全圆。原全仓 21 种取值已全部收敛到这 6 档。
 *
 * 默认形态为 [CornerBasedShape]：可直接用于 clip / shape / background / border 等接收 Shape 的位置；
 * 如需在 RoundedCornerShape(topStart = ...) 等局部圆角组合中使用，取对应的 *_Dp 值。
 */
object AppRadius {
    val XS: CornerBasedShape = RoundedCornerShape(6.dp)
    val SM: CornerBasedShape = RoundedCornerShape(8.dp)
    val MD: CornerBasedShape = RoundedCornerShape(12.dp)
    val LG: CornerBasedShape = RoundedCornerShape(16.dp)
    val XL: CornerBasedShape = RoundedCornerShape(24.dp)
    val Full: CornerBasedShape = RoundedCornerShape(999.dp)

    val XS_Dp: Dp = 6.dp
    val SM_Dp: Dp = 8.dp
    val MD_Dp: Dp = 12.dp
    val LG_Dp: Dp = 16.dp
    val XL_Dp: Dp = 24.dp
}

/** 间距阶梯：4pt 栅格。 */
object AppSpace {
    val XS: Dp = 4.dp
    val SM: Dp = 8.dp
    val MD: Dp = 12.dp
    val LG: Dp = 16.dp
    val XL: Dp = 24.dp
    val XXL: Dp = 32.dp
}

/**
 * 阴影阶梯。
 * 此前全仓仅 3 处卡片显式设置 elevation，其余靠 surfaceVariant 与 surface 的色差硬凑层次。
 */
object AppElevation {
    val None: Dp = 0.dp
    val SM: Dp = 2.dp
    val MD: Dp = 4.dp
    val LG: Dp = 8.dp
}

// ==================== 语义色（随当前色彩方案明暗自适应） ====================

private val SuccessDark = Color(0xFF54C77B)   // 对 #111720 约 7.3:1
private val SuccessLight = Color(0xFF1B7A3E)  // 对 #FFFFFF 约 5.1:1
private val WarningDark = Color(0xFFE7C267)
private val WarningLight = Color(0xFF8A5A00)
private val InfoDark = Color(0xFF36C5F0)
private val InfoLight = Color(0xFF006D8F)

/** 是否处于暗色方案（按 surface 亮度判断，兼容「用户手动切换」而非跟随系统）。 */
private val ColorScheme.isDarkScheme: Boolean get() = surface.luminance() < 0.5f

/** 成功（已完成、已连接、操作成功）。 */
val ColorScheme.success: Color
    @Composable @ReadOnlyComposable get() = if (isDarkScheme) SuccessDark else SuccessLight

/** 警告（需要注意但不阻断）。 */
val ColorScheme.warning: Color
    @Composable @ReadOnlyComposable get() = if (isDarkScheme) WarningDark else WarningLight

/** 信息（提示、说明、进行中）。 */
val ColorScheme.info: Color
    @Composable @ReadOnlyComposable get() = if (isDarkScheme) InfoDark else InfoLight
