package com.example.localmovielibrarz.scraper

/**
 * 一次刮削链的请求闸门（peach 三闸门的鸭影版）。
 *
 * peach 的闸门是**整批**口径：`MAX_SOURCE_REQUESTS = 6000` / `MAX_BYTES = 1GiB` /
 * `MAX_SECONDS = 4h`，因为它一次任务就是「一个文件夹几千部片」。鸭影这边
 * `scrapeWithFallback` 的粒度是**单部片**，照搬 6000 次等于不设闸，所以这里换成
 * 单片口径——它的作用是给一条链封顶，让「配置错了 / 站点慢 / 某源反复超时」不会把
 * 单部片拖成几分钟。
 *
 * 三项与 peach 的 `max_requests` / `max_bytes` / `max_seconds` 一一对应，但**口径换成单片**：
 * - **请求数**：每次真正发出去的网络请求算一格（命中负缓存、冷却跳过、配置错误都不算）。
 * - **墙钟**：从链开始计时，超时就收尾并交回已拿到的部分。
 * - **字节数**：由每次取数的 [PeachAttempt.Success.responseBytes] / [PeachAttempt.Failure.responseBytes]
 *   累加。这一项在 peach 那边是传输层的天然副产物（它自己读正文，`len(response.body)` 就有），
 *   鸭影这边正文由各家 scraper 的 `ScraperResponseGuard` 读，所以必须由取数实现如实回报；
 *   漏报的后果是**少算**（不会误伤，只会少拦一次），代价可接受，比在这一层再包一层
 *   计数流（要改所有 scraper 的读法）小得多。
 *
 * 用完即弃：每次 `scrapeWithFallback` 新建一个实例，不跨片共享，
 * 因此不需要锁（链内是串行 `for` 循环）。
 */
class ScrapeBudget(
    private val maxRequests: Int = DEFAULT_MAX_REQUESTS,
    private val maxMillis: Long = DEFAULT_MAX_MILLIS,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private var startedAt: Long? = null

    private var usedRequests: Int = 0

    private var usedBytes: Long = 0L

    /** 链真正开始打第一枪时记一次起点；重复调用只认第一次。 */
    fun markStarted() {
        if (startedAt == null) startedAt = clock()
    }

    fun requestUsed() {
        usedRequests += 1
    }

    /**
     * 记一次实际读到的响应体字节数。
     *
     * 为什么字节数要由调用方报进来，而不是在这一层去包 `ResponseBody`：字节数只有在
     * 「读完正文的那一刻」才准确，而读取分散在各 scraper 的 `ScraperResponseGuard` 里。
     * 在这一层包一层 counting stream 就得改所有 scraper 的读法（那是另一场重构）；
     * 让每家的 fetch 返回 `responseBytes` 反而是最小侵入，代价是**漏报会少算**——
     * 所以取数实现里凡是读了正文的地方都必须填这个字段，见 [PeachAttempt.Success] 的注释。
     */
    fun bytesUsed(bytes: Long) {
        if (bytes > 0L) usedBytes += bytes
    }

    /** 还剩几格请求；已用尽返回 0。 */
    fun remainingRequests(): Int = (maxRequests - usedRequests).coerceAtLeast(0)

    /** 还剩多少字节；链还没开始算「还满额」。 */
    fun remainingBytes(): Long = (maxBytes - usedBytes).coerceAtLeast(0L)

    /** 已用字节数（给日志与测试读）。 */
    fun usedBytes(): Long = usedBytes

    /** 还剩多少毫秒；链还没开始算「还满额」。 */
    fun remainingMillis(): Long {
        val start = startedAt ?: return maxMillis
        return (maxMillis - (clock() - start)).coerceAtLeast(0L)
    }

    /** 还能继续打吗：请求、字节与时间都还有余量。 */
    fun canRequest(): Boolean =
        remainingRequests() > 0 && remainingMillis() > 0L && remainingBytes() > 0L

    /**
     * 为什么停下了——给日志用的一句话；还能继续时返回 null。
     *
     * 调用方每轮循环读它，命中即收尾。把「为什么停」集中在这里，是为了让链的日志
     * 不必自己再判断三个计数（那正是三处口径容易分叉的地方）。
     */
    fun exhaustedReason(): String? = when {
        remainingRequests() <= 0 ->
            "本次刮削的请求预算已用完（上限 ${maxRequests} 次），提前收尾"

        remainingMillis() <= 0L ->
            "本次刮削的耗时预算已用完（上限 ${maxMillis / 1000} 秒），提前收尾"

        remainingBytes() <= 0L ->
            "本次刮削的下载量预算已用完（上限 ${maxBytes / 1024} KB），提前收尾"

        else -> null
    }

    companion object {
        /**
         * 单片请求上限。
         *
         * 8 个来源 × 1 次成功请求 = 8，留一格给重试与镜像，12 是「够覆盖一条完整链、
         * 又不至于在站点卡住时无限拖延」的折中值。peach 的 6000 是整批口径，不可比。
         */
        const val DEFAULT_MAX_REQUESTS = 12

        /**
         * 单片墙钟上限。
         *
         * peach 的 4 小时是整批。单片 90 秒的依据是现有 `ScrapeSourceInterval` 的
         * 最小间隔（3 秒级）与 `SCRAPE_RETRY_DELAYS_MILLIS` 的重试节奏：正常一部片
         * 在 20~40 秒内结束，90 秒足够跑完一条含两三次重试的链，超出就说明不是「正常慢」。
         */
        const val DEFAULT_MAX_MILLIS = 90_000L

        /**
         * 单片下载量上限。
         *
         * peach 的 1 GiB 是整批口径；折算到单部片就是「一条链最多读几页 HTML」。
         * 现有各源的 `ScraperResponseGuard.pageLimitBytes` 是 1~4 MiB/页（DMM 1 MiB、
         * r18.dev 2 MiB、其余 4 MiB），一条 12 格的链最坏也要读十几页，所以按
         * 「12 × 4 MiB」再留一点余量取 48 MiB。这个数的作用不是省流量，而是让
         * 「某家站返回一个几百 MB 的错误页」在一次取数之内就被闸住。
         */
        const val DEFAULT_MAX_BYTES = 48L * 1024 * 1024
    }
}