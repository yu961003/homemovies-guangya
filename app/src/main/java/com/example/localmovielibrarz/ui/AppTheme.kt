package com.example.localmovielibrarz.ui

import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.localmovielibrarz.data.repository.ThemeMode
import com.example.localmovielibrarz.data.repository.ThemePalette
import com.example.localmovielibrarz.data.repository.ThemeSpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 设计系统 · 色彩（v2：MD3 色彩角色全覆盖 + 多色板 + 动态取色 + AMOLED）。
 *
 * - 默认色板 [ThemePalette.CINEMA] 沿用 v1 手调过 WCAG 对比度的青蓝配色（[CinemaDark] / [CinemaLight]），
 *   升级用户的默认外观只做「精修」而不是「换脸」。
 * - 其余色板由种子色按 MD3 的色调（tone）规则生成：主色/辅色/第三色/中性色/中性变体五组调色板，
 *   各角色取固定色调（例如暗色 primary = T80、onPrimary = T20），保证所有色板的对比度关系一致。
 * - Android 12+ 可开启「动态取色」（壁纸配色），优先级高于色板。
 * - AMOLED：暗色下把背景与表面压到纯黑，容器层级保留极小的亮度阶差，OLED 屏更省电也更「影院」。
 */
private val CinemaDark = darkColorScheme(
    primary = Color(0xFF36C5F0),
    onPrimary = Color(0xFF04222C),
    primaryContainer = Color(0xFF10485C),
    onPrimaryContainer = Color(0xFFC4EAFA),

    secondary = Color(0xFF7FD4C4),
    onSecondary = Color(0xFF05332C),
    secondaryContainer = Color(0xFF124A41),
    onSecondaryContainer = Color(0xFFB2E9DC),

    tertiary = Color(0xFFE7C267),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF53400A),
    onTertiaryContainer = Color(0xFFF3DDA6),

    background = Color(0xFF070A0E),
    onBackground = Color(0xFFF4F7FA),
    surface = Color(0xFF0B1016),
    onSurface = Color(0xFFF4F7FA),
    surfaceVariant = Color(0xFF1B232E),
    onSurfaceVariant = Color(0xFFC2CBD6),
    surfaceTint = Color(0xFF36C5F0),

    surfaceDim = Color(0xFF070A0E),
    surfaceBright = Color(0xFF2A3440),
    surfaceContainerLowest = Color(0xFF05080B),
    surfaceContainerLow = Color(0xFF0E141B),
    surfaceContainer = Color(0xFF131A23),
    surfaceContainerHigh = Color(0xFF1A222D),
    surfaceContainerHighest = Color(0xFF232C38),

    outline = Color(0xFF4A5664),
    outlineVariant = Color(0xFF26303B),

    inverseSurface = Color(0xFFE3E8EE),
    inverseOnSurface = Color(0xFF172027),
    inversePrimary = Color(0xFF006D8F),

    error = Color(0xFFFF6B6B),
    onError = Color(0xFF4A0004),
    errorContainer = Color(0xFF4A1518),
    onErrorContainer = Color(0xFFFFD5D4),

    scrim = Color(0xFF000000)
)

private val CinemaLight = lightColorScheme(
    primary = Color(0xFF006D8F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC5E8F7),
    onPrimaryContainer = Color(0xFF001F2A),

    secondary = Color(0xFF146B5E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC3E8DE),
    onSecondaryContainer = Color(0xFF05201B),

    tertiary = Color(0xFF8A5A00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDEA6),
    onTertiaryContainer = Color(0xFF2B1700),

    background = Color(0xFFF6F8FB),
    onBackground = Color(0xFF172027),
    surface = Color(0xFFF9FBFD),
    onSurface = Color(0xFF172027),
    surfaceVariant = Color(0xFFDFE5EC),
    onSurfaceVariant = Color(0xFF414A56),
    surfaceTint = Color(0xFF006D8F),

    surfaceDim = Color(0xFFD8DEE5),
    surfaceBright = Color(0xFFF9FBFD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F5F9),
    surfaceContainer = Color(0xFFECF0F5),
    surfaceContainerHigh = Color(0xFFE6EBF1),
    surfaceContainerHighest = Color(0xFFE0E6ED),

    outline = Color(0xFF6E7A88),
    outlineVariant = Color(0xFFBFC8D2),

    inverseSurface = Color(0xFF172027),
    inverseOnSurface = Color(0xFFF4F7FA),
    inversePrimary = Color(0xFF36C5F0),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE4E4),
    onErrorContainer = Color(0xFF410002),

    scrim = Color(0xFF000000)
)

/**
 * 种子色 → 色调调色板（简化 HCT：在 HSL 空间按目标明度取色，并按色调压缩饱和度，
 * 避免高明度/低明度端出现荧光色或泥色）。
 */
private class TonalPalette(seed: Color, hueShift: Float = 0f, private val chroma: Float = 1f) {
    private val hue: Float
    private val sat: Float

    init {
        val r = seed.red
        val g = seed.green
        val b = seed.blue
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2f
        val d = mx - mn
        sat = if (d == 0f) 0f else d / (1f - abs(2f * l - 1f))
        var h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * (((b - r) / d) + 2f)
            else -> 60f * (((r - g) / d) + 4f)
        }
        if (h < 0f) h += 360f
        hue = (h + hueShift + 360f) % 360f
    }

    fun tone(t: Int): Color {
        val l = t / 100f
        // 两端收饱和度：T10/T95 附近的颜色不该像霓虹灯。
        val edge = 1f - abs(l - 0.55f) * 1.1f
        val s = (sat * chroma * edge.coerceIn(0.25f, 1f)).coerceIn(0f, 1f)
        return Color.hsl(hue, s, l.coerceIn(0f, 1f))
    }
}

private fun generatedScheme(seed: Color, dark: Boolean): ColorScheme {
    val p = TonalPalette(seed, chroma = 1f)
    val s = TonalPalette(seed, hueShift = 18f, chroma = 0.42f)
    val t = TonalPalette(seed, hueShift = 60f, chroma = 0.62f)
    val n = TonalPalette(seed, chroma = 0.07f)
    val nv = TonalPalette(seed, chroma = 0.14f)
    return if (dark) {
        darkColorScheme(
            primary = p.tone(80), onPrimary = p.tone(20), primaryContainer = p.tone(30), onPrimaryContainer = p.tone(90),
            secondary = s.tone(80), onSecondary = s.tone(20), secondaryContainer = s.tone(30), onSecondaryContainer = s.tone(90),
            tertiary = t.tone(80), onTertiary = t.tone(20), tertiaryContainer = t.tone(30), onTertiaryContainer = t.tone(90),
            background = n.tone(5), onBackground = n.tone(92),
            surface = n.tone(6), onSurface = n.tone(92),
            surfaceVariant = nv.tone(22), onSurfaceVariant = nv.tone(80),
            surfaceTint = p.tone(80),
            surfaceDim = n.tone(6), surfaceBright = n.tone(24),
            surfaceContainerLowest = n.tone(4), surfaceContainerLow = n.tone(9),
            surfaceContainer = n.tone(12), surfaceContainerHigh = n.tone(16), surfaceContainerHighest = n.tone(21),
            outline = nv.tone(48), outlineVariant = nv.tone(26),
            inverseSurface = n.tone(90), inverseOnSurface = n.tone(18), inversePrimary = p.tone(40),
            error = Color(0xFFFF6B6B), onError = Color(0xFF4A0004),
            errorContainer = Color(0xFF4A1518), onErrorContainer = Color(0xFFFFD5D4),
            scrim = Color.Black
        )
    } else {
        lightColorScheme(
            primary = p.tone(40), onPrimary = Color.White, primaryContainer = p.tone(90), onPrimaryContainer = p.tone(10),
            secondary = s.tone(40), onSecondary = Color.White, secondaryContainer = s.tone(90), onSecondaryContainer = s.tone(10),
            tertiary = t.tone(40), onTertiary = Color.White, tertiaryContainer = t.tone(90), onTertiaryContainer = t.tone(10),
            background = n.tone(97), onBackground = n.tone(10),
            surface = n.tone(98), onSurface = n.tone(10),
            surfaceVariant = nv.tone(90), onSurfaceVariant = nv.tone(30),
            surfaceTint = p.tone(40),
            surfaceDim = n.tone(87), surfaceBright = n.tone(98),
            surfaceContainerLowest = Color.White, surfaceContainerLow = n.tone(96),
            surfaceContainer = n.tone(94), surfaceContainerHigh = n.tone(92), surfaceContainerHighest = n.tone(90),
            outline = nv.tone(50), outlineVariant = nv.tone(80),
            inverseSurface = n.tone(20), inverseOnSurface = n.tone(95), inversePrimary = p.tone(80),
            error = Color(0xFFB3261E), onError = Color.White,
            errorContainer = Color(0xFFFCE4E4), onErrorContainer = Color(0xFF410002),
            scrim = Color.Black
        )
    }
}

/** AMOLED：背景与表面压到纯黑，容器保留 2~4% 的亮度阶差，层级仍可分辨。 */
private fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF07090B),
    surfaceContainer = Color(0xFF0C0F12),
    surfaceContainerHigh = Color(0xFF12161A),
    surfaceContainerHighest = Color(0xFF191E23),
    surfaceVariant = Color(0xFF14191E)
)

/**
 * 设计系统 · 圆角（MD3 Expressive 取向：更圆润的容器、胶囊形按钮）。
 * 数值统一由 [AppRadius] 提供，避免各页面各写各的。
 */
private val AppShapes = Shapes(
    extraSmall = AppRadius.XS,
    small = AppRadius.SM,
    medium = AppRadius.MD,
    large = AppRadius.LG,
    extraLarge = AppRadius.XL
)

/**
 * 设计系统 · 排版。
 *
 * 在 MD3 默认字阶上做三处调整：标题类字重提到 SemiBold（中文在 Regular 下层级感偏弱）、
 * Display/Headline 收紧字距、Label 用 Medium 保证小字号可读。全仓不写死 fontSize，文本缩放照常生效。
 */
private val AppTypography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        displayMedium = base.displayMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium)
    )
}

/** 设计系统 · 动效令牌（MD3 强调缓动）。 */
object AppMotion {
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    const val DurationShort = 150
    const val DurationMedium = 300
    const val DurationLong = 450
}

/** 当前主题的辅助判断（组件里需要按明暗切换描边/阴影时使用）。 */
val ColorScheme.isDark: Boolean get() = background.toArgb().let { argb ->
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    (0.2126 * r + 0.7152 * g + 0.0722 * b) < 128
}

/**
 * v2 主题入口。
 */
@Composable
fun HomeMovieTheme(spec: ThemeSpec, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (spec.mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val base = remember(spec.palette, spec.dynamicColor, dark) {
        when {
            spec.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            spec.palette == ThemePalette.CINEMA -> if (dark) CinemaDark else CinemaLight
            else -> generatedScheme(Color(spec.palette.seedArgb), dark)
        }
    }
    val scheme = if (dark && spec.amoled) base.toAmoled() else base
    MaterialTheme(
        colorScheme = scheme,
        shapes = AppShapes,
        typography = AppTypography,
        content = content
    )
}

/**
 * v1 兼容入口（只区分明暗，品牌色板）。保留给尚未迁移的调用点与预览。
 */
@Composable
fun HomeMovieTheme(useLightTheme: Boolean, content: @Composable () -> Unit) {
    HomeMovieTheme(
        spec = ThemeSpec(mode = if (useLightTheme) ThemeMode.LIGHT else ThemeMode.DARK),
        content = content
    )
}

/** 预览色板用：给出某色板在暗色下的主色（设置页的色板选择器显示用）。 */
fun ThemePalette.previewPrimary(dark: Boolean = true): Color = when (this) {
    ThemePalette.CINEMA -> if (dark) CinemaDark.primary else CinemaLight.primary
    else -> generatedScheme(Color(seedArgb), dark).primary
}
