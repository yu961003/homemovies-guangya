package com.example.localmovielibrarz.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrapeNumberGuardTest {

    @Test
    fun sameNumberInDifferentCaseAndSeparatorsIsNotAConflict() {
        assertFalse(ScrapeNumberGuard.conflicts("SSIS-123", "ssis00123"))
        assertFalse(ScrapeNumberGuard.conflicts("SSIS-123", "SSIS-0123"))
        assertFalse(ScrapeNumberGuard.conflicts("SSIS-123", " SSIS 123 "))
    }

    @Test
    fun numericPrefixIsNormalizedAway() {
        // 鸭影的数字前缀规则会把 `MIUM-123` 改写成 `300MIUM-123` 再查 MGStage，
        // 站点回的是哪一种写法都可能，归一化后应视为同一部。
        assertEquals(
            ScrapeNumberGuard.canonical("300MIUM-123"),
            ScrapeNumberGuard.canonical("MIUM-123")
        )
        assertFalse(ScrapeNumberGuard.conflicts("MIUM-123", "300MIUM-123"))
        assertFalse(ScrapeNumberGuard.conflicts("300MIUM-123", "MIUM-123"))
    }

    @Test
    fun differentNumberInTheSameSeriesIsAConflict() {
        // peach 把这个记成 javbus 的经典错答：`259LUXU-891` 被答成 `259LUXU-1891`。
        assertTrue(ScrapeNumberGuard.conflicts("259LUXU-891", "259LUXU-1891"))
        assertTrue(ScrapeNumberGuard.conflicts("ABP-123", "ABP-124"))
    }

    @Test
    fun differentSeriesIsAConflictEvenIfTheDigitsMatch() {
        assertTrue(ScrapeNumberGuard.conflicts("SSIS-123", "ABP-123"))
    }

    @Test
    fun unrecognizableShapesAreNeverAConflict() {
        // FC2 的 `FC2-PPV-1234567`、带分段/版本的 `ABC-123-4K`、纯数字与空串都认不出来。
        // 认不出来就放行——这里只拦「确认不是同一部」的结果，不拦「看不懂」的。
        assertFalse(ScrapeNumberGuard.conflicts("FC2-PPV-1234567", "FC2-PPV-7654321"))
        assertFalse(ScrapeNumberGuard.conflicts("ABC-123-4K", "XYZ-999"))
        assertFalse(ScrapeNumberGuard.conflicts("1234567", "7654321"))
        assertFalse(ScrapeNumberGuard.conflicts("", "SSIS-123"))
        assertFalse(ScrapeNumberGuard.conflicts("SSIS-123", ""))
    }

    @Test
    fun canonicalDropsLeadingZerosInTheNumericPart() {
        assertEquals("SSIS123", ScrapeNumberGuard.canonical("SSIS-000123"))
        assertEquals("SSIS0", ScrapeNumberGuard.canonical("SSIS-000"))
        assertEquals("MIUM123", ScrapeNumberGuard.canonical("300MIUM-0123"))
    }
}
