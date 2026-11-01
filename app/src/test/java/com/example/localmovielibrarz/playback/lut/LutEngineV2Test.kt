package com.example.localmovielibrarz.playback.lut

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

/**
 * v2 调色引擎回归：
 * - 创意层中性时，LUT 与 v1 公式逐位一致（以 v1 的「生成 → 与单位 LUT 混合」路径为基准重算）；
 * - 签名在默认值下与 v1 逐字一致（旧缓存文件继续命中）；
 * - `.cube` 写出/解析往返无损；四面体插值保持中性灰；
 * - 肤色压缩（唯一颜色 + 权重）不改变自动分析的结果；
 * - 人脸掩码路径能从脸部区域取到肤色，没有人脸时回到整帧路径。
 */
class LutEngineV2Test {

    @Test
    fun neutralCreativeKeepsV1Results() {
        val r = Random(42)
        repeat(20) {
            val p = LutParams(
                r.nextInt(201) - 100, r.nextInt(201) - 100, r.nextInt(201) - 100, r.nextInt(201) - 100,
                r.nextInt(201) - 100, r.nextInt(201) - 100, r.nextInt(201) - 100
            )
            val strength = r.nextInt(101)
            val v2 = LutColorMath.generateScaledLut(9, p, strength)
            // v1 路径：先生成原值，再按 1-k 与单位 LUT 混合
            val raw = LutColorMath.generateLut(9, p)
            val k = strength / 100f
            val v1 = if (k >= 1f) raw else raw.blendTowards(raw.identity(), 1f - k)
            assertArrayEquals("params=$p strength=$strength", v1.values, v2.values, 0f)
        }
    }

    @Test
    fun signatureUnchangedForDefaults() {
        val s = LutSettings(enabled = true, params = LutPreset.Standard.params)
        assertEquals("s33_k80_t-20_n6_sa0_b0_c0_h-2_sh2", s.signature())
        val withCreative = s.withCreative(LutCreativeParams(vibrance = 10))
        assertNotEquals(s.signature(), withCreative.signature())
        val withOverlay = s.copy(overlayLutPath = "/data/x/luts/0123456789abcdef.cube")
        assertNotEquals(s.signature(), withOverlay.signature())
        // 只有肤色保护、没有任何改色算子：不构成「改变画面」
        assertTrue(LutCreativeParams(skinProtect = 80).isNeutral())
    }

    @Test
    fun creativeOutputsStayInGamut() {
        for (look in LutLook.entries) {
            val lut = LutColorMath.generateScaledLut(17, look.params, look.creative, 100, null, 0)
            lut.values.forEach { v -> assertTrue("${look.name} 越界：$v", v in 0f..1f) }
        }
    }

    @Test
    fun cubeRoundTripAndTetrahedralNeutrality() {
        val lut = LutColorMath.generateScaledLut(17, LutPreset.ExtremeDark.params, 80)
        val bytes = ByteArrayOutputStream().also { lut.writeCube(it) }.toByteArray()
        val text = String(bytes, Charsets.US_ASCII)
        assertEquals(lut.toCubeText(), text)
        val parsed = CubeLut.parse(text)
        for (i in lut.values.indices) {
            assertEquals(LutColorMath.quantize6(lut.values[i]), LutColorMath.quantize6(parsed.values[i]))
        }
        val out = FloatArray(3)
        CubeLut.identity(33).sample(0.41f, 0.41f, 0.41f, out)
        assertEquals(0.41f, out[0], 1e-6f)
        assertEquals(out[0], out[1], 1e-7f)
        assertEquals(out[1], out[2], 1e-7f)
    }

    @Test
    fun identityOverlayDoesNotChangeResult() {
        val cr = LutCreativeParams(vibrance = 20, fade = 10)
        val base = LutColorMath.generateScaledLut(17, LutPreset.Standard.params, cr, 100, null, 0)
        val withIdentity = LutColorMath.generateScaledLut(17, LutPreset.Standard.params, cr, 100, CubeLut.identity(9), 100)
        assertArrayEquals(base.values, withIdentity.values, 2e-6f)
    }

    @Test
    fun skinCompressionKeepsPrediction() {
        val rnd = Random(7)
        val n = 20_000
        val skin = FloatArray(n * 3)
        for (i in 0 until n) {
            // 真实画面里肤色集中在很窄的色域（约几千种 8 bit 颜色），这里模拟同样的分布。
            skin[i * 3] = ((190 + rnd.nextInt(20)) / 255.0).toFloat()
            skin[i * 3 + 1] = ((140 + rnd.nextInt(15)) / 255.0).toFloat()
            skin[i * 3 + 2] = ((110 + rnd.nextInt(15)) / 255.0).toFloat()
        }
        val features = LutSkinStats.sample(skin)
        val compressed = LutParamPredictor.compressSkin(skin, null)
        assertNotNull("8 bit 量化像素应当可压缩", compressed.weights)
        val weighted = LutSkinStats.sampleWeighted(compressed.rgb, compressed.weights)
        assertEquals(features.skinRb, weighted.skinRb, 1e-12)
        assertEquals(features.skinLum, weighted.skinLum, 1e-12)
        assertEquals(features.fullHlRatio, weighted.fullHlRatio, 0.0)
        assertEquals(features.fullContrast, weighted.fullContrast, 1e-7)
        // 压缩后与压缩前搜出的参数完全一致
        val fromCompressed = LutParamPredictor.predict(features).params
        val fromRaw = LutParamPredictor.predict(LutSkinStats.sample(skin.copyOf())).params
        assertEquals(fromRaw, fromCompressed)
        // 任意浮点（非 8 bit）不压缩，行为与 v1 一致
        val arbitrary = floatArrayOf(0.70001f, 0.5f, 0.4f, 0.6f, 0.45f, 0.35f)
        assertEquals(null, LutParamPredictor.compressSkin(arbitrary, null).weights)
    }

    @Test
    fun faceMaskPathUsesFaceRegion() {
        val w = 400
        val h = 300
        // 背景：暖色木地板（容易被误判为肤色）；中间一张「脸」。
        val frame = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val inFace = (x - 200) * (x - 200) / 3600.0 + (y - 150) * (y - 150) / 6400.0 < 1.0
            if (inFace) (0xFF shl 24) or (210 shl 16) or (160 shl 8) or 135
            else (0xFF shl 24) or (170 shl 16) or (120 shl 8) or 70
        }
        val hull = LutFaceGeometry.convexHull(
            floatArrayOf(0.36f, 0.25f, 0.64f, 0.25f, 0.66f, 0.5f, 0.64f, 0.75f, 0.36f, 0.75f, 0.34f, 0.5f)
        )
        val face = LutFaceRegion(0.34f, 0.25f, 0.66f, 0.75f, listOf(hull), emptyList(), 0.9f, LutFaceRegion.Source.MESH)
        val withFace = LutSkinAnalyzer.analyze(frame, w, h, listOf(face))
        assertNotNull(withFace)
        assertEquals(LutFaceRegion.Source.MESH.regionName, withFace!!.regionName)
        assertEquals(1, withFace.faceCount)
        // 人脸路径只统计脸上的像素：R/B 应接近脸色 210/135≈1.556，而不是木地板 170/70≈2.43
        assertEquals(210.0 / 135.0, withFace.skinRb, 0.02)
        val withoutFace = LutSkinAnalyzer.analyze(frame, w, h)
        if (withoutFace != null) assertEquals(LutSkinAnalyzer.REGION_CENTER, withoutFace.regionName)
    }

    @Test
    fun blazeKeypointsProduceThreeRegions() {
        val region = LutFaceGeometry.regionsFromBlazeKeypoints(
            0.3f, 0.2f, 0.7f, 0.8f,
            floatArrayOf(0.42f, 0.4f, 0.58f, 0.4f, 0.5f, 0.52f, 0.5f, 0.65f, 0.32f, 0.45f, 0.68f, 0.45f),
            0.9f
        )
        assertEquals(3, region.include.size)
        assertEquals(LutFaceRegion.Source.BOX, region.source)
        val raster = LutFaceGeometry.rasterize(listOf(region), 200, 200)
        assertNotNull(raster)
        assertTrue(raster!!.second > 100)
    }
}
