package com.example.localmovielibrarz.scraper

/**
 * 一次取页请求。
 *
 * 为什么要把「取页」抽成一个接口而不是每个 scraper 各拿一个 [okhttp3.OkHttpClient]：
 * 这一轮新增的来源里有三家需要**浏览器回落**——HTTP 被站方拦下（403/429/Cloudflare 验证页）时，
 * 换一个能执行 JS 的通道再取同一条 URL。如果回落逻辑写在每家 scraper 里，就变成三份
 * 「先 HTTP、再判拦截、再决定是否上浏览器」的分支，且每份都要自己处理 Cookie 回灌；
 * 收到这一层之后，scraper 只回答「这一页怎么解析」，通道选择与 Cookie 是取数层的事。
 *
 * 这个文件**故意不 import 任何 android/okhttp 符号**：它要能被 `work/compile-pure-jvm.sh`
 * 的 Tier 1 编译集收进去，解析器与回落判定的单测才能在无 Android SDK 的机器上跑。
 * 真正的 OkHttp/WebView 实现分别在 `AndroidSourceFetch.kt` 与 `browser/` 下。
 */
data class SourcePageRequest(
    /** 目标地址。 */
    val url: String,
    /** 该来源的展示名，只用于日志与异常文案（不参与请求）。 */
    val sourceLabel: String,
    /** 这项取数属于哪个来源；回落策略与 Cookie 分区都按它查表。 */
    val source: ScrapeSource,
    /** `Referer`；空串表示这个来源不需要它。 */
    val referer: String = "",
    /**
     * 额外的请求头（`X-Inertia` 那一套就挂在这里）。
     *
     * 值里可以出现 `\n` 分隔的多条同名头吗？不可以——同名多值用 [extraHeaders] 的多个 key 表达不了，
     * 而本项目里真正需要多值的场景一个也没有（Inertia 的 `X-Inertia-Partial-Data` 是单值逗号分隔）。
     */
    val extraHeaders: Map<String, String> = emptyMap(),
    /** 读取上限；超过即中止并记一条 `ParseError`（与 `ScraperResponseGuard` 同口径）。 */
    val maxBytes: Long = DEFAULT_PAGE_LIMIT_BYTES
) {

    companion object {
        /**
         * 与 [ScraperResponseGuard.DEFAULT_PAGE_LIMIT_BYTES] 同一个数（4 MiB），
         * 但这里要自己写一份：`ScraperResponseGuard` 依赖 `okhttp3.ResponseBody`，
         * 不在 Tier 1 的纯 JVM 编译集里，直接引用它会让本文件也被排除出单测覆盖。
         */
        const val DEFAULT_PAGE_LIMIT_BYTES = 4L * 1024 * 1024
    }
}

/**
 * 一次取页的结果。
 *
 * [viaBrowser] 是重要的证据位：它让日志能区分「HTTP 直接取到」与「上了浏览器才取到」。
 * 排查「为什么这次刮削突然变慢」时，这一位就是答案。
 */
data class SourcePageResponse(
    val url: String,
    val status: Int,
    val body: String,
    /** 这次是不是走了浏览器通道。 */
    val viaBrowser: Boolean = false
) {
    val isSuccessful: Boolean get() = status in 200..299
}

/**
 * 取页层的入口。
 *
 * 这一层只有一个方法：给它一条请求，回一页正文，失败抛 [SourceFetchException]。
 * 浏览器回落、Cookie 持久化、限流、重试、代理全部藏在实现里——scraper 不该知道
 * 「这一页是 OkHttp 取到的还是 WebView 取到的」，那是通道细节。
 *
 * 实现有且只有两个：
 * - `AndroidSourceFetch`（`scraper/AndroidSourceFetch.kt`）：OkHttp 主通道 + WebView 回落；
 * - `StaticSourceFetcher`（测试用）：按 URL 回预设页面，让解析器与链的判定能被离线覆盖。
 */
interface SourcePageFetcher {

    suspend fun fetch(request: SourcePageRequest): SourcePageResponse
}

/**
 * 取页层的统一失败。
 *
 * [browserAttempted] 说明「已经试过浏览器」，避免上层再判一次要不要回落。
 * [browserBlocked] 专指「浏览器也没过去」——这对应 peach 的 `ChallengeUnsolved`，
 * 上层应当按**整站冷却**处理（`blocked` 档），而不是当成解析失败反复重试。
 */
class SourceFetchException(
    message: String,
    val reason: PeachFailureReason,
    val statusCode: Int = 0,
    val browserAttempted: Boolean = false,
    val browserBlocked: Boolean = false,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * 浏览器通道的可用性。
 *
 * 单独成型是因为「能不能上浏览器」与「这一页要不要解析」是两件事：前者取决于有没有前台
 * Activity、WebView 是否可用，后者取决于站点。把它们混在一个返回值里，scraper 就得
 * 为了判通道而 import Android。
 */
enum class BrowserChannelState(val label: String) {
    /** 可用：有前台 Activity，能挂 WebView。 */
    Available("浏览器通道可用"),

    /** 当前没有前台 Activity（应用在后台，或批刮在屏幕熄灭时进行）。 */
    NoForeground("当前没有前台界面，浏览器通道不可用"),

    /** 设备上 WebView 不可用（系统组件被禁用/缺失）。 */
    Unavailable("本机 WebView 不可用"),

    /** 用户或策略关闭了浏览器通道。 */
    Disabled("浏览器通道已被关闭");

    val usable: Boolean get() = this == Available
}

/**
 * 本来源要不要走浏览器。
 *
 * 判定口径照 peach 的两个开关：
 * - `browser: True` 的来源（fc2ppvdb / javten）**优先**走浏览器；
 * - 其余来源**只在 HTTP 被拦时**回落（peach 的 `browser_fallback`）。
 *
 * 与 peach 的一处有意分叉：peach 把 `browser_fallback` 做成「验证成功一次就固定到浏览器」，
 * 并把选择写进来源设置文件。这里不写盘——一个来源是被拦还是临时抽风，写盘会让一次偶发
 * 变得永久；改成「每次决策 + Cookie 持久化」：只要 `cf_clearance` 还在，HTTP 直连就能过，
 * 浏览器通道自然退化为兜底，不需要那个持久开关。
 */
object BrowserFallbackPolicy {

    /** 一上来就走浏览器的来源（peach `scraping_access.SOURCES[*].browser == True`）。 */
    private val ALWAYS_BROWSER: Set<ScrapeSource> = setOf(
        ScrapeSource.Fc2PpvDb,
        ScrapeSource.Javten
    )

    /**
     * 允许「HTTP 被拦 → 上浏览器」的来源（peach `browser_fallback` 的等价集合）。
     *
     * 为什么不把全部来源都开成 true：浏览器通道一次要起一个 WebView、跑到 challenge 过去，
     * 代价是秒级到分钟级。对 DMM / r18.dev / MGStage 这些从来不出验证页的来源开放它，
     * 只会把一次网络抖动变成一次界面卡顿。
     */
    private val REFUSAL_FALLBACK: Set<ScrapeSource> = setOf(
        ScrapeSource.Fc2Cmadb,
        ScrapeSource.Fc2PpvDb,
        ScrapeSource.Javten,
        ScrapeSource.Faleno,
        ScrapeSource.Dahlia,
        ScrapeSource.MakerSite
    )

    fun prefersBrowser(source: ScrapeSource): Boolean = source in ALWAYS_BROWSER

    fun allowsFallback(source: ScrapeSource): Boolean = source in REFUSAL_FALLBACK

    /**
     * 这次取页的结果算不算「被站方拦下」。
     *
     * 三种形态都算（对齐 peach `scraping_access._refused`）：
     * - `403` / `429`：显式拒绝；
     * - `200` 但正文是 Cloudflare 验证页：状态码这一层看不出来，只能看正文；
     * - `503` 带 Cloudflare 的 `cf-mitigated: challenge` 头：这是 2024 年后 CF 的新形态，
     *   本工程按状态码判不了，交给 [ChallengePageDetector] 的正文判定兜住。
     */
    fun isRefused(status: Int, body: String?): Boolean =
        status == 403 || status == 429 || ChallengePageDetector.isChallengePage(body)
}