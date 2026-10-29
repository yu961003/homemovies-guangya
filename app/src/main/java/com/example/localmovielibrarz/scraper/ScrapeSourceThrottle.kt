package com.example.localmovielibrarz.scraper

/**
 * 按来源计时的最小间隔执行器。
 *
 * 只回答一个问题：「现在打这家站，还要等多久」。等待由调用方做（仓库层是 `delay`），
 * 这样纯逻辑部分可以脱离协程与 Android 单测——`clock` 可注入就是为此。
 *
 * 线程安全：批量刮削会按 `serialScrapeMutex` 串行，但不同的入口（手动单部、批量、
 * 元数据补全）可能同时进来，所以这里仍然上锁。
 */
class ScrapeSourceThrottle(
    private val clock: () -> Long = System::currentTimeMillis,
    private val intervalOf: (ScrapeSource) -> Long = ScrapeSourceInterval::minIntervalMillis,
) {
    private val lock = Any()
    private val lastRequestAt = mutableMapOf<ScrapeSource, Long>()

    /**
     * 距离下一次可以请求该来源还剩多少毫秒；0 表示现在就可以打。
     *
     * 判断本身不推进计时——只有真的发出请求并调用 [markRequested] 才算用过这个窗口，
     * 否则「算完等待、结果被取消」会把窗口白白吃掉。
     */
    fun waitMillisBefore(source: ScrapeSource): Long {
        val interval = intervalOf(source)
        if (interval <= 0L) return 0L
        val now = clock()
        synchronized(lock) {
            val last = lastRequestAt[source] ?: return 0L
            val elapsed = now - last
            // 时钟回拨（用户改系统时间、NTP 校正）时 elapsed 会是负数。此时不该算出
            // 一个巨大的等待值把整条链卡死，按「刚打过」处理即可。
            if (elapsed < 0L) return interval
            return (interval - elapsed).coerceAtLeast(0L)
        }
    }

    /** 记下刚刚向该来源发出了一次请求。调用点必须在真正发请求之前，失败也算打过。 */
    fun markRequested(source: ScrapeSource) {
        if (intervalOf(source) <= 0L) return
        val now = clock()
        synchronized(lock) { lastRequestAt[source] = now }
    }

    /** 清空计时。来源开关变化或用户手动重试时调用，避免旧窗口挡住新请求。 */
    fun reset() {
        synchronized(lock) { lastRequestAt.clear() }
    }
}
