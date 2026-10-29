package com.example.localmovielibrarz.ui.settings

import androidx.compose.material3.lightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 库扫描按钮在扫描中的配色回归。
 *
 * 历史缺陷：禁用态用了「白字 + 浅底」，扫描中按钮上的文字几乎看不见。修复后禁用容器色与
 * 内容色都从 onSurface 派生（alpha 0.55 / 0.62），与启用态（primary / onPrimary）明确区分。
 * 这里断言的是派生规则本身，配色随主题变化时不需要改测试。
 */
class SettingsLibraryScanButtonTest {
    @Test
    fun `scanning state keeps dark text on a visible light button`() {
        val scheme = lightColorScheme()
        val palette = libraryScanButtonPalette(scheme)

        assertEquals(scheme.primary, palette.containerColor)
        assertEquals(scheme.onPrimary, palette.contentColor)
        assertEquals(scheme.onSurface.copy(alpha = 0.55f), palette.disabledContainerColor)
        assertEquals(scheme.onSurface.copy(alpha = 0.62f), palette.disabledContentColor)
    }
}
