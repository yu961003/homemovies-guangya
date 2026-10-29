package com.example.localmovielibrarz.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三闸门（peach `SourceTransport` 的 requests / bytes / seconds）。
 *
 * peach 的三道闸是**传输层**判的：每次真发请求前 `requests >= max_requests` 就抛
 * `SourcePaused`，读多少正文就 `bytes += len(body)`，墙钟超了就把 timeout 截到 0 再抛。
 * 鸭影这边请求由各家 scraper 自己发，所以闸门挪到了链上——这里的断言就是「挪过去之后
 * 三档口径没有走样」：谁记账（链）、什么时候判（每次发请求之前）、停下时给出什么理由。
 */
class ScrapeBudgetTest {

    /** 可拨的时钟：墙钟闸门不该依赖真实时间。 */
    private class Clock(var now: Long = 1_000L) : () -> Long {
        override fun invoke(): Long = now
    }

    @Test
    fun requestGateStopsAtTheConfiguredCount() {
        val budget = ScrapeBudget(maxRequests = 2)
        assertTrue(budget.canRequest())
        budget.requestUsed()
        assertTrue(budget.canRequest())
        budget.requestUsed()
        // 第 2 次之后就不能再打了——判的是「已用 >= 上限」。
        assertFalse(budget.canRequest())
        assertEquals(0, budget.remainingRequests())
        assertNotNull(budget.exhaustedReason())
    }

    @Test
    fun bytesGateStopsWhenDownloadBudgetIsSpent() {
        val budget = ScrapeBudget(maxBytes = 1_000L)
        budget.bytesUsed(600L)
        assertTrue(budget.canRequest())
        assertNull(budget.exhaustedReason())
        budget.bytesUsed(400L)
        assertFalse(budget.canRequest())
        assertEquals(0L, budget.remainingBytes())
        assertTrue(budget.exhaustedReason()!!.contains("下载量"))
    }

    @Test
    fun startGateStartsCountingOnlyWhenChainBegins() {
        val clock = Clock()
        val budget = ScrapeBudget(maxMillis = 10_000L, clock = clock)
        // 还没 markStarted 时墙钟闸门必须放行：否则「链还没开始就超时」。
        clock.now += 60_000L
        assertTrue(budget.canRequest())

        budget.markStarted()
        clock.now += 4_000L
        assertTrue(budget.canRequest())
        clock.now += 6_000L
        assertFalse(budget.canRequest())
        assertTrue(budget.exhaustedReason()!!.contains("耗时"))
    }

    @Test
    fun markStartedIsIdempotent() {
        val clock = Clock()
        val budget = ScrapeBudget(maxMillis = 10_000L, clock = clock)
        budget.markStarted()
        clock.now += 3_000L
        budget.markStarted()
        // 第二次 markStarted 不能把起点往后挪，否则墙钟闸门会被无限续期。
        clock.now += 7_000L
        assertFalse(budget.canRequest())
    }

    @Test
    fun negativeOrZeroBytesAreIgnored() {
        val budget = ScrapeBudget(maxBytes = 100L)
        budget.bytesUsed(0L)
        budget.bytesUsed(-5L)
        assertEquals(0L, budget.usedBytes())
        assertTrue(budget.canRequest())
    }

    @Test
    fun exhaustedReasonNamesTheFirstExhaustedGate() {
        // 三道同时到底时按「请求 → 耗时 → 字节」的顺序报，与 peach 里三道闸的判断顺序一致。
        val clock = Clock()
        val budget = ScrapeBudget(maxRequests = 1, maxMillis = 1L, maxBytes = 1L, clock = clock)
        budget.markStarted()
        clock.now += 10L
        budget.requestUsed()
        budget.bytesUsed(10L)
        assertTrue(budget.exhaustedReason()!!.contains("请求预算"))
    }
}