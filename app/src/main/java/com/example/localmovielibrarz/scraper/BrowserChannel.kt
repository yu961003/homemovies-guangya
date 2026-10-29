package com.example.localmovielibrarz.scraper

/**
 * 浏览器通道的对外契约。
 *
 * 为什么单独抽一个接口：[浏览器回落] 在 Android 上必须靠 WebView（`android.webkit.*`），
 * 而 WebView 的三个硬约束——只能在主线程创建、必须有 Activity 承载、进程内单实例共享 Cookie——
 * 让它的实现天然与「一次刮削调用」的生命周期不匹配（批刮可能跑在后台，屏幕熄灭，没有 Activity）。
 * 把通道抽成接口之后：
 * - 抓取侧只问 [state] 与 [fetch]，不需要 import Android；
 * - 通道不可用时，调用方拿到的是**明确的失败原因**，而不是一个超时；
 * - 单测可以塞一个假通道，覆盖「HTTP 被拦 → 回落 → 又失败」这条最难在真机上复现的路径。
 *
 * 实现只有 `WebViewBrowserChannel`（`scraper/browser/` 下）；本文件不 import 任何 android 符号，
 * 所以它留在 Tier 1 编译集里，回落决策的单测无需 Android SDK。
 */
interface BrowserChannel {

    /** 当前通道能不能用。每次取页前问一次——Activity 可能刚被系统回收。 */
    fun state(): BrowserChannelState

    /**
     * 用浏览器取一页。
     *
     * 契约：
     * - 成功时回 [SourcePageResponse]，`viaBrowser = true`；
     * - 站点的人机验证在限时内没过去 → 抛 [SourceFetchException]，`reason = CloudflareChallenge`、
     *   `browserBlocked = true`。调用方应当按**整站冷却**处理，不要立刻重试；
     * - 没有前台界面 → 抛 [SourceFetchException]，`reason = Network`、`browserAttempted = false`。
     *   「没界面」不是站点的错，不该给站点记一笔拦截；
     * - 页面能打开但正文取不到 → `reason = ParseError`。
     */
    suspend fun fetch(request: SourcePageRequest): SourcePageResponse
}

/**
 * 没有浏览器通道时用的空实现。
 *
 * 存在的意义是让 `AndroidSourceFetch` 的构造参数可以是非空类型：批刮、单测、或用户在设置里
 * 关掉浏览器通道时，塞这个进来，取数层不需要到处判 null。
 */
object NoBrowserChannel : BrowserChannel {

    override fun state(): BrowserChannelState = BrowserChannelState.Disabled

    override suspend fun fetch(request: SourcePageRequest): SourcePageResponse {
        throw SourceFetchException(
            message = "${request.sourceLabel} 需要浏览器通道，但当前不可用",
            reason = PeachFailureReason.Network,
            browserAttempted = false
        )
    }
}