package com.example.localmovielibrarz.scraper

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 按来源累计的响应体字节数（peach 三闸门里 `max_bytes` 那一项的数据来源）。
 *
 * 为什么要一个进程级计数器，而不是让每个 scraper 自己 return 读了多少：读正文的地方只有
 * `ScraperResponseGuard.readLimited` 一处，但它的调用点散在 8 个 scraper 的十几行里，
 * 而且有的路径读两次（详情页 + 列表页）。让 readLimited 顺手记一笔，是唯一不用改
 * 所有 scraper 签名的做法——那条路要么改 `MovieScraper` 接口（连带 8 个实现与 3 个单测），
 * 要么在仓库层包一层 counting stream（OkHttp 的 body 只能读一次，包了就得自己管缓存）。
 *
 * 为什么按「来源」而不是按「一次刮削」：取数前后各取一次快照，差值就是这一次的字节数
 * （见 `StrmScrapeRepository.fetchSingleSource` 的用法）。刮削是串行的，同一来源不会并发
 * 读两页，所以差值不会被别的请求污染。
 *
 * 纯 JVM：不 import android / okhttp3，单测可以直接驱动它。
 */
object ScrapeByteMeter {

    private val counters = ConcurrentHashMap<ScrapeSource, AtomicLong>()

    /** 记一次实际读到的字节数。负数与非正数直接忽略（改不了口径就别污染累计值）。 */
    fun record(source: ScrapeSource, bytes: Long) {
        if (bytes <= 0L) return
        counters.computeIfAbsent(source) { AtomicLong() }.addAndGet(bytes)
    }

    /** 取快照：配合 [since] 算「这一段读了多少」。 */
    fun mark(source: ScrapeSource): Long = counters[source]?.get() ?: 0L

    /** 从 [mark] 到现在累计读了多少（不会被别人的减成负数）。 */
    fun since(source: ScrapeSource, mark: Long): Long {
        val now = counters[source]?.get() ?: 0L
        return (now - mark).coerceAtLeast(0L)
    }

    /** 清空（单测用；运行时不需要，累计值本身没有语义，只有差值有）。 */
    fun reset() {
        counters.clear()
    }
}
