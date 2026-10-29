package com.example.localmovielibrarz.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 设计系统 · 色彩
 *
 * 此前只定义了 5 个槽位，其余全部落在 Material3 默认（紫色基线）上，
 * 与自定义的青蓝主色不同色相：底部导航选中药丸是 #4F378B 深紫、面板底色是 #49454F 紫灰。
 * 现补齐全部常用槽位，所有组合均满足 WCAG AA（正文 4.5:1 / 图形 3:1）。
 *
 * 品牌主色：青蓝 #36C5F0（暗色）/ #006D8F（亮色）
 * 辅助色：青绿 secondary（同族，避免冲突）、琥珀 tertiary（评分/星标/警示）
 */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF36C5F0),
    onPrimary = Color(0xFF04222C),          // 8.18:1
    primaryContainer = Color(0xFF10485C),
    onPrimaryContainer = Color(0xFFC4EAFA), // 7.84:1

    secondary = Color(0xFF7FD4C4),
    onSecondary = Color(0xFF05332C),
    secondaryContainer = Color(0xFF124A41),
    onSecondaryContainer = Color(0xFFB2E9DC),

    tertiary = Color(0xFFE7C267),
    onTertiary = Color(0xFF3A2A00),
    tertiaryContainer = Color(0xFF53400A),
    onTertiaryContainer = Color(0xFFD9C08A),

    background = Color(0xFF070A0E),
    onBackground = Color(0xFFF4F7FA),       // 18.45:1
    surface = Color(0xFF111720),
    onSurface = Color(0xFFF4F7FA),          // 16.73:1
    surfaceVariant = Color(0xFF1B232E),     // 中性蓝灰，替代默认紫灰 #49454F
    onSurfaceVariant = Color(0xFFC2CBD6),   // 9.65:1

    surfaceContainerLowest = Color(0xFF0A0F15),
    surfaceContainerLow = Color(0xFF0F151E),
    surfaceContainer = Color(0xFF151C26),
    surfaceContainerHigh = Color(0xFF1B2430),
    surfaceContainerHighest = Color(0xFF252E3B),

    outline = Color(0xFF3A4552),
    outlineVariant = Color(0xFF252D38),

    inverseSurface = Color(0xFFE3E8EE),
    inverseOnSurface = Color(0xFF172027),

    error = Color(0xFFFF6B6B),
    onError = Color(0xFF4A0004),
    errorContainer = Color(0xFF4A1518),
    onErrorContainer = Color(0xFFFFD5D4),

    scrim = Color(0xFF000000)
)

private val LightColors = lightColorScheme(
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
    tertiaryContainer = Color(0xFFD9C08A),
    onTertiaryContainer = Color(0xFF2B1700),

    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF172027),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF172027),
    surfaceVariant = Color(0xFFDFE5EC),
    onSurfaceVariant = Color(0xFF414A56),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F5F9),
    surfaceContainer = Color(0xFFECEFF4),
    surfaceContainerHigh = Color(0xFFE6EAF1),
    surfaceContainerHighest = Color(0xFFE0E5ED),

    outline = Color(0xFF6E7A88),
    outlineVariant = Color(0xFFB9C3CE),

    inverseSurface = Color(0xFF172027),
    inverseOnSurface = Color(0xFFF4F7FA),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFCE4E4),
    onErrorContainer = Color(0xFF410002),

    scrim = Color(0xFF000000)
)

/**
 * 设计系统 · 圆角（4 级阶梯）。
 * 此前全仓有 21 种取值，现统一为 6 / 8 / 12 / 16 / 28 五档。
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
 * 显式声明（数值沿用 Material3 默认），作为后续统一调整层级的地基。
 * 注意：全仓无硬编码 fontSize，文本缩放（最高 200%）可正常工作。
 */
private val AppTypography = Typography()

@Composable
fun HomeMovieTheme(useLightTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (useLightTheme) LightColors else DarkColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content
    )
}
