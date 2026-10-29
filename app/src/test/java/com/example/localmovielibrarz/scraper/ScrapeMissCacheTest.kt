package com.example.localmovielibrarz.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 负缓存（peach `_MissCache`）：站上明确说过「没有」之后 7 天内不再问同一家同一番号。
 *
 * peach 的动机写得很直白：真实账本里有 600+ 条「r18.dev 不认识这个番号」的记录，
 * 每条都要等主机的 2 秒间隔——负缓存省的不是流量，是这一整段等待。所以这里的断言盯的是
 * **什么时候不该复用**（过期、换来源、换番号、被容量挤掉），而不只是「记住了」。
 */
class ScrapeMissCacheTest {

    private class Clock(var now: Long = 0L) : () -> Long {
        override fun invoke(): Long = now
    }

    private val ttl = ScrapeMissCache.DEFAULT_TTL_MILLIS

    @Test
    fun rememberedMissIsReportedWithinTtl() {
        val cache = ScrapeMissCache()
        assertFalse(cache.isMiss(ScrapeSource.TheJavDB, "SSIS-115"))
        cache.rememberMiss(ScrapeSource.TheJavDB, "SSIS-115")
        assertTrue(cache.isMiss(ScrapeSource.TheJavDB, "SSIS-115"))
    }

    @Test
    fun ttlIsSevenDays() {
        // 与 peach 的 `MISS_TTL_SECONDS = 7 * 24 * 3600` 对齐；这一条写死是为了防止
        // 有人为了「更快回正」把 TTL 调成小时级——那会把负缓存变成一台重复打站机器。
        assertEquals(7L * 24 * 60 * 60 * 1000, ttl)
    }

    @Test
    fun missExpiresAfterTtl() {
        val clock = Clock()
        val cache = ScrapeMissCache(clock = clock, ttlMillis = 1_000L)
        cache.rememberMiss(ScrapeSource.Javbus, "ABC-123")
        clock.now = 999L
        assertTrue(cache.isMiss(ScrapeSource.Javbus, "ABC-123"))
        // 边界：正好到期即视为过期，与 peach 的 `expires_at <= now` 一致。
        clock.now = 1_000L
        assertFalse(cache.isMiss(ScrapeSource.Javbus, "ABC-123"))
    }

    @Test
    fun missIsPerSourceNotGlobal() {
        val cache = ScrapeMissCache()
        cache.rememberMiss(ScrapeSource.Javbus, "ABC-123")
        // JavBus 没有不等于 JavDB 没有：按站记是 peach 的取舍（`sites` 是键的一部分）。
        assertFalse(cache.isMiss(ScrapeSource.TheJavDB, "ABC-123"))
        assertTrue(cache.isMiss(ScrapeSource.Javbus, "ABC-123"))
    }

    @Test
    fun numberMatchingIsCaseAndSpaceInsensitive() {
        val cache = ScrapeMissCache()
        cache.rememberMiss(ScrapeSource.Javbus, "ssis-115")
        assertTrue(cache.isMiss(ScrapeSource.Javbus, "  SSIS-115 "))
    }

    @Test
    fun rememberIsIdempotentAndRefreshesExpiry() {
        val clock = Clock()
        val cache = ScrapeMissCache(clock = clock, ttlMillis = 100L)
        cache.rememberMiss(ScrapeSource.Javbus, "ABC-123")
        clock.now = 60L
        cache.rememberMiss(ScrapeSource.Javbus, "ABC-123")
        assertEquals(1, cache.sizeFor(ScrapeSource.Javbus))
        clock.now = 150L
        // 第二次记账把过期时间推到 160，所以这里仍然命中。
        assertTrue(cache.isMiss(ScrapeSource.Javbus, "ABC-123"))
    }

    @Test
    fun oldestEntriesAreEvictedWhenCapacityIsReached() {
        val cache = ScrapeMissCache(maxEntries = 2)
        cache.rememberMiss(ScrapeSource.Javbus, "AAA-001")
        cache.rememberMiss(ScrapeSource.Javbus, "AAA-002")
        cache.rememberMiss(ScrapeSource.Javbus, "AAA-003")
        // 容量是硬上限：批量任务一次几千个番号，不设上限就是内存泄漏。
        assertEquals(2, cache.sizeFor(ScrapeSource.Javbus))
        assertFalse(cache.isMiss(ScrapeSource.Javbus, "AAA-001"))
        assertTrue(cache.isMiss(ScrapeSource.Javbus, "AAA-003"))
    }

    @Test
    fun clearDropsEverything() {
        val cache = ScrapeMissCache()
        cache.rememberMiss(ScrapeSource.Javbus, "AAA-001")
        cache.clear()
        assertFalse(cache.isMiss(ScrapeSource.Javbus, "AAA-001"))
        assertEquals(0, cache.sizeFor(ScrapeSource.Javbus))
    }
}