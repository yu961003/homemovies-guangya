package com.example.localmovielibrarz.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrapeSourceThrottleTest {

    @Test
    fun javdbWaitsThreeSecondsOthersTwo() {
        assertEquals(3_000L, ScrapeSourceInterval.minIntervalMillis(ScrapeSource.TheJavDB))
        assertEquals(2_000L, ScrapeSourceInterval.minIntervalMillis(ScrapeSource.Javbus))
        assertEquals(2_000L, ScrapeSourceInterval.minIntervalMillis(ScrapeSource.Dmm2))
    }

    @Test
    fun customJsonAndPriorityAreNotThrottled() {
        assertEquals(0L, ScrapeSourceInterval.minIntervalMillis(ScrapeSource.CustomJson))
        assertEquals(0L, ScrapeSourceInterval.minIntervalMillis(ScrapeSource.Priority))
    }

    @Test
    fun firstRequestIsNotDelayed() {
        var now = 1_000L
        val throttle = ScrapeSourceThrottle(clock = { now })
        assertEquals(0L, throttle.waitMillisBefore(ScrapeSource.Javbus))
    }

    @Test
    fun secondRequestWaitsRemainingInterval() {
        var now = 1_000L
        val throttle = ScrapeSourceThrottle(clock = { now })
        throttle.markRequested(ScrapeSource.Javbus)
        now += 500L
        assertEquals(1_500L, throttle.waitMillisBefore(ScrapeSource.Javbus))
        now += 1_500L
        assertEquals(0L, throttle.waitMillisBefore(ScrapeSource.Javbus))
    }

    @Test
    fun markingWithoutWaitingDoesNotConsumeTheWindow() {
        var now = 1_000L
        val throttle = ScrapeSourceThrottle(clock = { now })
        throttle.markRequested(ScrapeSource.TheJavDB)
        // 只是问「还要等多久」不该推进计时。
        throttle.waitMillisBefore(ScrapeSource.TheJavDB)
        throttle.waitMillisBefore(ScrapeSource.TheJavDB)
        now += 1_000L
        assertEquals(2_000L, throttle.waitMillisBefore(ScrapeSource.TheJavDB))
    }

    @Test
    fun sourcesAreTimedIndependently() {
        var now = 1_000L
        val throttle = ScrapeSourceThrottle(clock = { now })
        throttle.markRequested(ScrapeSource.Javbus)
        assertEquals("另一家站不该被上一家的窗口挡住", 0L, throttle.waitMillisBefore(ScrapeSource.Dmm))
    }

    @Test
    fun clockGoingBackwardsDoesNotStallForHours() {
        var now = 10_000L
        val throttle = ScrapeSourceThrottle(clock = { now })
        throttle.markRequested(ScrapeSource.Javbus)
        now = 5_000L // 用户回拨系统时间
        val wait = throttle.waitMillisBefore(ScrapeSource.Javbus)
        assertTrue("回拨只该退回一个完整间隔，不该算出几分钟的等待", wait <= 2_000L)
    }

    @Test
    fun resetClearsEverySourceWindow() {
        var now = 1_000L
        val throttle = ScrapeSourceThrottle(clock = { now })
        throttle.markRequested(ScrapeSource.Javbus)
        throttle.reset()
        assertEquals(0L, throttle.waitMillisBefore(ScrapeSource.Javbus))
    }

    @Test
    fun unthrottledSourceNeverWaits() {
        var now = 1_000L
        val throttle = ScrapeSourceThrottle(clock = { now })
        throttle.markRequested(ScrapeSource.CustomJson)
        assertEquals(0L, throttle.waitMillisBefore(ScrapeSource.CustomJson))
    }
}
