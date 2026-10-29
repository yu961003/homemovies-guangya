package com.example.localmovielibrarz.data.repository

import com.example.localmovielibrarz.scraper.MovieNumberExtractor
import com.example.localmovielibrarz.util.NumberRecognitionRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberRuleRefreshTest {
    @Test
    fun `movie number extraction only loads cached rules`() {
        var cachedRuleLoads = 0
        val number = extractNumberWithCachedRules(
            values = listOf("NACT-067.strm"),
            extractor = MovieNumberExtractor::extract,
            loadCachedRules = { cachedRuleLoads += 1 }
        )

        assertEquals("NACT-067", number)
        assertEquals(1, cachedRuleLoads)
    }

    // ---- 批次 5b：分段标记语义标签（仅整值匹配，不拆解复合值） ----

    @Test
    fun `语义标签_整值命中返回中文`() {
        assertEquals("无码破解", NumberRecognitionRules.partMarkerLabel("U"))
        assertEquals("中文字幕", NumberRecognitionRules.partMarkerLabel("C"))
        assertEquals("破解中文", NumberRecognitionRules.partMarkerLabel("UC"))
        assertEquals("无码破解", NumberRecognitionRules.partMarkerLabel("RESTORED"))
    }

    @Test
    fun `语义标签_大小写与空白归一化`() {
        assertEquals("中文字幕", NumberRecognitionRules.partMarkerLabel(" c "))
        assertEquals("无码破解", NumberRecognitionRules.partMarkerLabel("restored"))
    }

    @Test
    fun `语义标签_复合值与未知标记保持原样`() {
        assertNull(NumberRecognitionRules.partMarkerLabel("RESTORED-A")) // 复合值不做拆解
        assertNull(NumberRecognitionRules.partMarkerLabel("P3"))
        assertNull(NumberRecognitionRules.partMarkerLabel("X"))
        assertNull(NumberRecognitionRules.partMarkerLabel(null))
    }

}
