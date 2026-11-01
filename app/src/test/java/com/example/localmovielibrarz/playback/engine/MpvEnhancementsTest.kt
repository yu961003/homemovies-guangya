package com.example.localmovielibrarz.playback.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 播放增强（mpvRx 能力清单接入）的纯逻辑回归。
 */
class MpvEnhancementsTest {

    @Test
    fun userOptionsRejectProtectedKeys() {
        val parsed = MpvUserOptions.parse(
            """
            # comment
            scale=ewa_lanczos
            --interpolation=yes
            vo=gpu
            hwdec = no
            tls-verify=no
            bad key=1
            deband
            """.trimIndent()
        )
        assertEquals(listOf("scale" to "ewa_lanczos", "interpolation" to "yes", "deband" to "yes"), parsed.options)
        assertTrue(parsed.rejected.containsAll(listOf("vo", "hwdec", "tls-verify")))
        assertEquals(listOf(7), parsed.invalidLines)
    }

    @Test
    fun hdrChainSelectionFollowsSourceParams() {
        assertEquals(HdrToysChain.PQ, HdrToysChain.forSource("pq", "bt.2020"))
        assertEquals(HdrToysChain.HLG, HdrToysChain.forSource("hlg", "bt.2020"))
        assertEquals(HdrToysChain.BT2020, HdrToysChain.forSource("bt.1886", "bt.2020"))
        assertNull(HdrToysChain.forSource("bt.1886", "bt.709"))
        // 每条链都以 bt1886 编码收尾（输出 SDR）
        HdrToysChain.entries.forEach { assertEquals("transfer-function_bt1886.glsl", it.shaders.last()) }
    }

    @Test
    fun defaultEnhancementsAddNoDebandOrVolumeOptions() {
        val d = MpvEnhancementOptions.DEFAULT
        assertFalse(d.deband.enabled)
        assertEquals(100, d.volumeMaxPercent)
        assertEquals(HdrToneMode.OFF, d.hdrMode)
        assertEquals(listOf("deband" to "no"), d.deband.toOptions())
    }

    @Test
    fun autoCropDetectsLetterbox() {
        val w = 320
        val h = 180
        val bar = 22
        val px = IntArray(w * h) { i ->
            val y = i / w
            if (y < bar || y >= h - bar) 0xFF000000.toInt() else (0xFF shl 24) or (120 shl 16) or (100 shl 8) or 90
        }
        val crop = AutoCropDetector.detect(px, w, h)
        assertNotNull(crop)
        assertEquals(bar, crop!!.top)
        assertEquals(bar, crop.bottom)
        assertEquals(0, crop.left)
        assertEquals("320x${h - 2 * bar}+0+$bar", crop.toMpvValue())
        // 全黑（暗场景）不裁
        assertNull(AutoCropDetector.detect(IntArray(w * h) { 0xFF000000.toInt() }, w, h))
    }

    @Test
    fun anime4kResolutionGuard() {
        assertTrue(ThermalGuard.anime4kAllowedFor(1920, 1080))
        assertTrue(ThermalGuard.anime4kAllowedFor(2560, 1440))
        assertFalse(ThermalGuard.anime4kAllowedFor(3840, 2160))
        assertEquals(ThermalGuard.Level.WARM, ThermalGuard.level(0.85f))
        assertEquals(ThermalGuard.Level.HOT, ThermalGuard.level(1.2f))
    }
}
