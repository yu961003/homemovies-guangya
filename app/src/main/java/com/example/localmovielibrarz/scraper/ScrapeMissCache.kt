package com.example.localmovielibrarz.scraper

/**
 * 「这家站没有这个番号」的负缓存（peach `miss_cache` 的鸭影版）。
 *
 * 为什么需要它：批量刮削里同一部片经常被问两三遍（首次刮削 + 元数据补全 + 用户手动重刮），
 * 而「404 / 站内没有」是**确定性**回答——重问一遍既拿不到值，又多一次被风控记账的机会。
 * 命中时直接跳过该来源，等于把一次网络请求换成一次内存查表。
 *
 * 只记 [ScrapeFailureKind.NotFound]。三类失败**刻意不记**：
 * - `Forbidden` / `Blocked`：冷却机制已经在管，记进负缓存会让「解封后也不问」持续 7 天；
 * - `ParseError`：站点改版可能明天就修好，负缓存会把它藏起来；
 * - `Network`：纯传输抖动，与「站里有没有这部」无关。
 *
 * 生命周期是**进程内**，不落库。取舍写在 [ScrapeMissCache] 的类注释里：加一张持久表要动
 * Room schema 版本 + 迁移，而这条缓存的全部价值是「省掉同一会话内的重复请求」，
 * 冷启动后重新问一遍的代价只有一次 404，不值为它冒迁移失败的风险。
 *
 * @param clock 可注入的时间源，单测用它跳过 TTL。
 */
class ScrapeMissCache(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {

    /**
     * `key -> 过期时间戳`。
     *
     * 用 [LinkedHashMap] 而不是 `HashMap`：需要按插入顺序淘汰最旧的一条（超 [maxEntries] 时），
     * 否则一次「整库补全」的几千个番号会把这张表撑成内存泄漏。
     */
    private val entries = LinkedHashMap<String, Long>()

    private val lock = Any()

    /** 记一次「这家没有」。同一来源同一番号重复调用是幂等的（只刷新过期时间）。 */
    fun rememberMiss(source: ScrapeSource, number: String) {
        val key = keyOf(source, number)
        synchronized(lock) {
            // 先删再插，让它挪到 LinkedHashMap 的队尾：淘汰顺序要按「最近一次被记」算，
            // 而不是按第一次被记算。
            entries.remove(key)
            entries[key] = clock() + ttlMillis
            while (entries.size > maxEntries) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
            }
        }
    }

    /** 这条「没有」还在有效期内吗？命中即过期条目会顺手清掉。 */
    fun isMiss(source: ScrapeSource, number: String): Boolean {
        val key = keyOf(source, number)
        synchronized(lock) {
            val expiresAt = entries[key] ?: return false
            if (expiresAt <= clock()) {
                entries.remove(key)
                return false
            }
            return true
        }
    }

    /** 该来源记了多少条未过期的「没有」——只给日志与单测用。 */
    fun sizeFor(source: ScrapeSource): Int {
        val prefix = "${source.name}|"
        val now = clock()
        synchronized(lock) {
            return entries.count { (key, expiresAt) -> key.startsWith(prefix) && expiresAt > now }
        }
    }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    /**
     * 番号统一大写后做键：`ssis-115` 与 `SSIS-115` 是同一部片，分两个键等于缓存命中率减半。
     * 分隔符用 `|`，因为番号形态里不会出现它（有码/素人/FC2 都是字母数字加连字符/下划线）。
     */
    private fun keyOf(source: ScrapeSource, number: String): String =
        "${source.name}|${number.trim().uppercase()}"

    companion object {
        /** 与 peach 的 miss 缓存同口径：7 天。 */
        const val DEFAULT_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** 上限按「一部片 × 8 个来源」的量级估：4096 条足够覆盖几百部片的批量任务。 */
        const val DEFAULT_MAX_ENTRIES = 4096
    }
}
