package com.example.localmovielibrarz.scraper

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 刮削失败的结构化归因（参考 peach 的 12 档失败分类，但只保留鸭影真正会遇到的几类）。
 *
 * 为什么需要它：现在所有失败都退化成 `error.message` 一个字符串，于是
 * 「站点 403 封了出口 IP」和「这个番号在站内确实搜不到」在日志里长得一模一样，
 * 用户与自动重试策略都没法据此做不同决定——前者该降权/冷却，后者该换下一个源。
 */
enum class ScrapeFailureKind(
    /** 换源/重试是否可能成功。确定性失败（番号不存在、配置错）重试只是浪费请求。 */
    val retryable: Boolean,
    /** 同一来源在这么久之内不应再被打扰；0 表示无需冷却。 */
    val cooldownMillis: Long
) {
    /**
     * HTTP 401/403：被拦，通常与出口 IP 或风控有关。
     *
     * 这里的 15 分钟是**首停时长**，不是每次 403 都停 15 分钟——连着撞才翻倍，
     * 上限见 [ScrapeCooldownPolicy.blockedPauseLimitMillis]（对齐 peach 的
     * `FIRST_BLOCKED_PAUSE` + `blocked_pause` 阶梯）。理由来自 peach 的实测
     * （`scraping_access.py:98-103`）：按出口 IP 计的封期常常远短于登记的上限，
     * 一次 403 就锁半小时等于让来源在盲等，那一轮 778 部片的 1432 条失败全写着
     * 「来源正在冷却」。
     */
    Forbidden(retryable = true, cooldownMillis = ScrapeCooldownPolicy.FIRST_BLOCKED_PAUSE_MILLIS),

    /** HTTP 429：明确要求限速（peach 无 `Retry-After` 时停 900 秒）。 */
    RateLimited(retryable = true, cooldownMillis = 15L * 60 * 1000),

    /**
     * 验证页 / 人机校验 / 站点维护页：继续打只会被记更多次。
     *
     * 与 [Forbidden] 走同一条阶梯：验证页往往就是 403 的 200 变体，分两套冷却没有意义。
     */
    Blocked(retryable = true, cooldownMillis = ScrapeCooldownPolicy.FIRST_BLOCKED_PAUSE_MILLIS),

    /** HTTP 5xx：服务端临时故障。 */
    ServerError(retryable = true, cooldownMillis = 2L * 60 * 1000),

    /** HTTP 404 / 410 / 站内没有该番号：确定性失败，换源而不是重试同一源。 */
    NotFound(retryable = false, cooldownMillis = 0L),

    /** 抓到了页面但解析不出字段：可能是站点改版，冷却中等。 */
    ParseError(retryable = true, cooldownMillis = 5L * 60 * 1000),

    /** 连接/超时/DNS/TLS：与站点内容无关，短冷却后即可再试。 */
    Network(retryable = true, cooldownMillis = 15_000L),

    /** 配置类错误（源未启用、地址非法、来源不支持）：重试永远不会成功。 */
    Config(retryable = false, cooldownMillis = 0L),

    Unknown(retryable = true, cooldownMillis = 0L)
}

/**
 * 来源冷却的取档规则（对齐 peach `scraping_access.py:153-170`）。
 *
 * peach 对「被站方拦下」（403 / 429 / 200 的 Cloudflare 验证页）不是每次停同样久，而是：
 * 第一次停 `FIRST_BLOCKED_PAUSE`（900 秒），**连着再撞才翻倍**，上限是该来源登记的
 * `blocked_pause`（javdb 24 小时、javbus 6 小时，没登记的按 24 小时）。
 * 只要通一次就把记录清掉，下次从最短一档重新起算。
 *
 * 鸭影照搬这条阶梯，因为它解决的是一个真实症状：老实现里 403 一律停 30 分钟，
 * 用户点「重试」时来源仍在冷却，日志只写「来源正在冷却」，看起来就像刮削坏了。
 */
object ScrapeCooldownPolicy {
    /** 第一次被拦停多久：peach 的 `FIRST_BLOCKED_PAUSE = 900`。 */
    const val FIRST_BLOCKED_PAUSE_MILLIS = 900_000L

    /** 没登记 `blocked_pause` 的来源按 24 小时封顶（peach 同口径）。 */
    const val DEFAULT_BLOCKED_PAUSE_LIMIT_MILLIS = 24L * 60 * 60 * 1000

    /** `javdb`: `blocked_pause = 24 * 3600`（`scraping_access.py:75`）。 */
    const val JAVDB_BLOCKED_PAUSE_LIMIT_MILLIS = 24L * 60 * 60 * 1000

    /** `javbus`: `blocked_pause = 6 * 3600`（`scraping_access.py:77`）。 */
    const val JAVBUS_BLOCKED_PAUSE_LIMIT_MILLIS = 6L * 60 * 60 * 1000

    /** `avbase`: `blocked_pause = 6 * 3600`（`scraping_access.py:84`）。 */
    const val AVBASE_BLOCKED_PAUSE_LIMIT_MILLIS = 6L * 60 * 60 * 1000

    /**
     * `fc2ppvdb` / `javten`: `blocked_pause = 6 * 3600`（`scraping_access.py:80,82`）。
     *
     * 这两家在 peach 里是 `browser: True` 的来源：被拦之后要么等浏览器通道把验证点过去，
     * 要么等冷却过去，所以上限比默认的 24 小时短得多——24 小时等于「今天这条链就没有它们了」。
     */
    const val FC2_BLOCKED_PAUSE_LIMIT_MILLIS = 6L * 60 * 60 * 1000

    /**
     * 翻倍次数上限。
     *
     * 900 秒左移 7 次 = 32 小时，已经越过最大的 24 小时上限；再多移只会在 `Long` 里
     * 绕回负数（`shl` 不做溢出检查），所以先夹住次数再移位。
     */
    private const val MAX_BLOCK_SHIFT = 7

    /** 该来源被拦后的冷却上限。 */
    fun blockedPauseLimitMillis(source: ScrapeSource): Long = when (source) {
        ScrapeSource.TheJavDB -> JAVDB_BLOCKED_PAUSE_LIMIT_MILLIS
        ScrapeSource.Javbus -> JAVBUS_BLOCKED_PAUSE_LIMIT_MILLIS
        // peach 的 `scraping_access.SOURCES` 只给 avbase 显式写了 `blocked_pause: 6h`，
        // 1pondo / fc2 没写，走的是同一份 `24 * 3600` 默认值——所以这里只给 avbase 单列。
        ScrapeSource.Avbase -> AVBASE_BLOCKED_PAUSE_LIMIT_MILLIS
        // peach 只给这两家显式写了 6 小时（`browser: True` 的来源）。
        ScrapeSource.Fc2PpvDb,
        ScrapeSource.Javten -> FC2_BLOCKED_PAUSE_LIMIT_MILLIS
        else -> DEFAULT_BLOCKED_PAUSE_LIMIT_MILLIS
    }

    /** 这类失败是否走「首停 + 翻倍」阶梯。 */
    fun isEscalating(kind: ScrapeFailureKind): Boolean =
        kind == ScrapeFailureKind.Forbidden || kind == ScrapeFailureKind.Blocked

    /**
     * 本次失败该冷却多久。
     *
     * [blockStreak] 是**含本次**的连续被拦次数（1 = 第一次撞上）。
     * 只有 [isEscalating] 的种类会用到它，其余种类仍按 [ScrapeFailureKind.cooldownMillis] 固定冷却。
     */
    fun cooldownMillis(
        kind: ScrapeFailureKind,
        source: ScrapeSource,
        blockStreak: Int = 1
    ): Long {
        if (!isEscalating(kind) || blockStreak <= 0) return kind.cooldownMillis
        val shift = (blockStreak - 1).coerceIn(0, MAX_BLOCK_SHIFT)
        val escalated = FIRST_BLOCKED_PAUSE_MILLIS shl shift
        return minOf(escalated, blockedPauseLimitMillis(source))
    }
}

data class ScrapeFailure(
    val kind: ScrapeFailureKind,
    val message: String,
    val httpCode: Int? = null
) {
    val retryable: Boolean get() = kind.retryable
    val cooldownMillis: Long get() = kind.cooldownMillis
}

/**
 * 从异常里解析出结构化失败。
 *
 * 站点实现统一用 `error("...")` 抛出 IllegalStateException，把 HTTP 状态码塞在文案里，
 * 所以这里既看异常类型，也看格式化后的文案——两条路都比改七个 scraper 的静态类型便宜。
 */
object ScrapeFailureClassifier {

    private val httpCodeRegex = Regex("""HTTP\s*(\d{3})""", RegexOption.IGNORE_CASE)

    fun fromThrowable(error: Throwable): ScrapeFailure {
        val text = buildString {
            append(error::class.java.simpleName)
            append(": ")
            append(error.message.orEmpty())
        }
        val httpCode = httpCodeRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val kind = classifyKind(error, text, httpCode)
        return ScrapeFailure(
            kind = kind,
            message = error.message ?: error::class.java.simpleName,
            httpCode = httpCode
        )
    }

    /**
     * 这次失败是否值得**立刻重试同一来源**（peach 的 `NETWORK_RETRY_DELAYS` 只用在传输层异常上）。
     *
     * 只认 [ScrapeFailureKind.Network]：连接被拒/超时/DNS/TLS 都是「这一跳没通」，
     * 等几秒再试有意义；而 403/404/429/5xx/解析失败是站点给出的**确定性回答**，
     * 立刻重试只会多烧一次配额，交给来源级冷却与换源更合适。
     */
    fun isRetryableTransportFailure(error: Throwable): Boolean =
        fromThrowable(error).kind == ScrapeFailureKind.Network

    private fun classifyKind(error: Throwable, text: String, httpCode: Int?): ScrapeFailureKind {
        // 先排除一个「长得像网络异常、其实不是」的已知形态：响应体超限由
        // ScraperResponseGuard.readLimited 抛的是裸 IOException，按类型会被归到 Network，
        // 但它的性质是「站点给了东西、只是太大」——重试只会再下一次大响应，必须留在 ParseError。
        if (text.contains("响应体超过")) return ScrapeFailureKind.ParseError

        // 再看类型：网络层异常与文案无关，最可靠的信号。
        // 注意顺序——SocketTimeoutException / SSLException / ConnectException 都继承自
        // IOException，这里先列子类只是为了可读性，最终都由 IOException 兜住。
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            when (current) {
                is SocketTimeoutException,
                is UnknownHostException,
                is SSLException,
                is ConnectException,
                is NoRouteToHostException,
                is SocketException,
                is EOFException,
                is InterruptedIOException,
                // IOException 兜底：OkHttp 在「代理拒绝连接」「读到一半被 FIN」等场景抛的是
                // 裸 IOException / ProtocolException，漏掉它们会被归成 Unknown，
                // 于是这些本该重试的传输失败在「只重试 Network」的策略下变成一次就放弃。
                is IOException -> return ScrapeFailureKind.Network
                else -> Unit
            }
            current = current.cause
            depth += 1
        }

        if (httpCode != null) {
            return when {
                httpCode == 401 || httpCode == 403 -> ScrapeFailureKind.Forbidden
                // 404 与 410 都是「站点明确说这东西没了」：410 Gone 只是语义更硬的 404，
                // 重试同一个番号不会变出结果，归 NotFound 才能立刻换下一个来源。
                httpCode == 404 || httpCode == 410 -> ScrapeFailureKind.NotFound
                httpCode == 429 -> ScrapeFailureKind.RateLimited
                // 408 Request Timeout 是**服务端**等请求超时，与 5xx 的「临时故障」同性质：
                // 换来的下一次请求通常就过了。落到 Unknown 会让它在「只重试 Network」的策略下
                // 一次就放弃——和本地 SocketTimeoutException 的待遇不该差这么多。
                httpCode == 408 -> ScrapeFailureKind.Network
                httpCode in 500..599 -> ScrapeFailureKind.ServerError
                else -> ScrapeFailureKind.Unknown
            }
        }

        val lower = text.lowercase()
        return when {
            lower.contains("验证") || lower.contains("captcha") || lower.contains("人机") ->
                ScrapeFailureKind.Blocked
            lower.contains("没有搜索到") || lower.contains("未匹配") ||
                lower.contains("没有返回") || lower.contains("搜不到") ->
                ScrapeFailureKind.NotFound
            lower.contains("解析") || lower.contains("响应体超过") -> ScrapeFailureKind.ParseError
            lower.contains("请求失败") || lower.contains("连接") || lower.contains("超时") ||
                lower.contains("timeout") || lower.contains("socket") || lower.contains("响应为空") ->
                ScrapeFailureKind.Network
            lower.contains("不支持") || lower.contains("未启用") ||
                lower.contains("必须以 http") -> ScrapeFailureKind.Config
            else -> ScrapeFailureKind.Unknown
        }
    }
}

/**
 * 来源级健康度与冷却（参考 peach 的 403 按出口 IP 记账，但记账粒度收窄到「来源」）。
 *
 * 鸭影是单机 App，没有服务端出口 IP 的概念；真正会受伤的是「同一个站点被本地反复打」，
 * 所以按 [ScrapeSource] 记冷却就够了，也避免了引入持久化表。
 */
class ScrapeSourceHealthTracker(
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val lock = Any()
    private val coolingUntil = mutableMapOf<ScrapeSource, Long>()
    private val lastFailures = mutableMapOf<ScrapeSource, ScrapeFailure>()

    /**
     * 该来源连续被拦了几次（403 / 验证页）。只增不减，直到 [recordSuccess] 才清零——
     * 这是 peach `blocked_pause` 阶梯的计数器，也是「停多久」的唯一输入。
     */
    private val blockStreaks = mutableMapOf<ScrapeSource, Int>()

    fun cooldownRemaining(source: ScrapeSource): Long {
        synchronized(lock) {
            val until = coolingUntil[source] ?: return 0L
            return (until - clock()).coerceAtLeast(0L)
        }
    }

    fun isCoolingDown(source: ScrapeSource): Boolean = cooldownRemaining(source) > 0L

    fun lastFailure(source: ScrapeSource): ScrapeFailure? = synchronized(lock) { lastFailures[source] }

    /**
     * 记录一次失败并算出本次冷却。
     *
     * 返回值是**实际用了多久冷却**（毫秒，0 表示不冷却），调用方据此写库——
     * 持久化层不该自己再按 [ScrapeFailureKind.cooldownMillis] 算一遍：
     * 阶梯冷却依赖「连续被拦第几次」，那个计数只在 tracker 里，两层各算一遍必然分叉。
     */
    fun recordFailure(source: ScrapeSource, failure: ScrapeFailure): Long {
        synchronized(lock) {
            lastFailures[source] = failure
            val cooldownMillis = if (failure.cooldownMillis <= 0L) {
                0L
            } else if (ScrapeCooldownPolicy.isEscalating(failure.kind)) {
                // 只有「被拦」这一类才计入连续次数；否则一次 404 会把 403 的阶梯清零。
                val streak = (blockStreaks[source] ?: 0) + 1
                blockStreaks[source] = streak
                ScrapeCooldownPolicy.cooldownMillis(failure.kind, source, streak)
            } else {
                // 其他种类沿用固定冷却，同时**不清**阶梯：站点偶发 5xx 不代表封禁记录作废，
                // 真正能清的是「成功一次」（见 recordSuccess）。
                failure.cooldownMillis
            }
            if (cooldownMillis > 0L) {
                val until = clock() + cooldownMillis
                val previous = coolingUntil[source] ?: 0L
                if (until > previous) coolingUntil[source] = until
            }
            return cooldownMillis
        }
    }

    /** 成功一次就清掉冷却与被拦阶梯：站点已经能正常响应，下次从最短一档重新起算（peach 同口径）。 */
    fun recordSuccess(source: ScrapeSource) {
        synchronized(lock) {
            coolingUntil.remove(source)
            lastFailures.remove(source)
            blockStreaks.remove(source)
        }
    }

    /** 该来源当前连续被拦了几次（含已过冷却的历史）；给日志与设置页用。 */
    fun blockStreak(source: ScrapeSource): Int = synchronized(lock) { blockStreaks[source] ?: 0 }

    /**
     * 冷启动回灌：把上一次运行留在库里的冷却状态装回内存（由
     * `SourceHealthRepository.primeTracker` 调用）。
     *
     * 与 [recordFailure] 的区别是**不累加、不重算**：库里存的截止时间就是权威值，
     * 这里只负责把它翻译成「还剩多少毫秒」。重复回灌是幂等的（取较大值），
     * 所以启动期间被调用两次也不会把冷却越推越远。
     */
    fun restore(
        source: ScrapeSource,
        failure: ScrapeFailure,
        cooldownRemainingMillis: Long,
        blockStreak: Int = 0
    ) {
        synchronized(lock) {
            lastFailures[source] = failure
            // 阶梯次数不落库（那要动表结构），所以进程重启后从「第一次被拦」重新起算。
            // 这是刻意的**更宽松**方向：库里那份冷却截止时间仍然生效（来源不会被提前打），
            // 重启只是让下一次被拦重新从 15 分钟起算，不会把来源锁得比 peach 更久。
            if (blockStreak > 0) {
                val previous = blockStreaks[source] ?: 0
                if (blockStreak > previous) blockStreaks[source] = blockStreak
            }
            if (cooldownRemainingMillis > 0L) {
                val until = clock() + cooldownRemainingMillis
                val previous = coolingUntil[source] ?: 0L
                if (until > previous) coolingUntil[source] = until
            } else {
                coolingUntil.remove(source)
            }
        }
    }

    fun reset() {
        synchronized(lock) {
            coolingUntil.clear()
            lastFailures.clear()
            blockStreaks.clear()
        }
    }

    /** 给日志与设置页用的可读快照，例如 `Dmm=Forbidden（剩余 28 分钟）`。 */
    fun snapshot(): Map<ScrapeSource, String> = synchronized(lock) {
        lastFailures.mapValues { (source, failure) ->
            val remaining = ((coolingUntil[source] ?: 0L) - clock()).coerceAtLeast(0L)
            val streak = blockStreaks[source] ?: 0
            val streakText = if (streak > 1) "，连续被拦 ${streak} 次" else ""
            if (remaining > 0L) {
                "${failure.kind.name}（剩余 ${(remaining / 60_000L).coerceAtLeast(1L)} 分钟$streakText）"
            } else {
                failure.kind.name + streakText
            }
        }
    }
}
