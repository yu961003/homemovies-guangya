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
 * peach 的十二档失败原因（`sources/base.py:FailureReason`），原样搬过来。
 *
 * 为什么不像 [ScrapeFailureKind] 那样精简成九档：两者回答的不是同一个问题。
 * - [ScrapeFailureKind] 是**本项目**的处置口径（要不要重试、冷却多久），已经被设置页、
 *   健康度记账和既有刮削链引用，不能改。
 * - 本枚举是**按 peach 的分档**回答「这一趟链上到底撞了什么」，因为链的分支判断依赖
 *   站方语义：「验证页」和「被封」在处置上都是冷却，但在日志与聚合原因里是两回事，
 *   而「地区限制」「需要登录」这种**重试永远不会成功**的档位如果被并进 [ScrapeFailureKind.Forbidden]，
 *   用户会看到来源反复冷却却永远不通。
 *
 * 两档之间的翻译在 [toLocalKind] / [fromLocalKind]，单向且可测：peach 档 → 本项目档。
 * 反过来的映射只用于「外部已经给了本项目档」的场景（既有 scraper 抛出的异常）。
 */
enum class PeachFailureReason(
    /** 日志与聚合原因里的中文说法。 */
    val label: String,
    /** peach 的 `REASON_KINDS` 三档：授权类 / 不可用 / 没有。 */
    val kind: PeachReasonKind,
    /** peach 的 `COOLDOWN_ACTIONS`：只有被拦与限流会让整个来源停下。 */
    val cooldownAction: PeachCooldownAction?,
    /** peach 的 `PERMANENT_REASONS`：重试同一个源不会变。 */
    val permanent: Boolean
) {
    /** Cloudflare 验证页（200 也可能是它）。 */
    CloudflareChallenge("撞上站点验证页", PeachReasonKind.Auth, PeachCooldownAction.Blocked, false),

    /** 出口 IP 被封。 */
    IpBanned("出口 IP 被站点封禁", PeachReasonKind.Auth, PeachCooldownAction.Blocked, false),

    /** 地区限制（DMM 系对日本以外通常就是这个）。 */
    GeoRestricted("站点限制本地区访问", PeachReasonKind.Auth, PeachCooldownAction.Blocked, false),

    /** 需要登录态或 Cookie。 */
    AuthRequired("站点要求登录", PeachReasonKind.Auth, null, false),

    /** 站上明确没有这个番号。 */
    NotFound("站点没有收录该番号", PeachReasonKind.NotFound, null, false),

    /** 曾经有、现在下架（410）。 */
    Gone("作品已下架", PeachReasonKind.NotFound, null, false),

    /** 页面拿到了但结构对不上（改版、截断）。 */
    ParseError("页面结构未识别", PeachReasonKind.Unavailable, null, true),

    /** 页面正常，但一条可用字段都没有。 */
    NoUsableMetadata("页面没有可用字段", PeachReasonKind.NotFound, null, false),

    /** 明确限流（429 / Retry-After）。 */
    RateLimited("请求被限流", PeachReasonKind.Unavailable, PeachCooldownAction.RateLimited, false),

    /** 超时。 */
    Timeout("请求超时", PeachReasonKind.Unavailable, null, false),

    /** 5xx。 */
    ServerError("站点服务端故障", PeachReasonKind.Unavailable, null, false),

    /** 传输层故障（DNS / 连接 / TLS / 读到一半断开）。 */
    Network("网络或传输故障", PeachReasonKind.Unavailable, null, false);

    /** 是不是「这个出口对这一站进不去」——这类失败对整条链都意味着换源而不是重试。 */
    val blocksWholeSource: Boolean get() = cooldownAction == PeachCooldownAction.Blocked

    /**
     * 翻成本项目的处置档。
     *
     * 对应关系与 peach 的口径一致，只有一处刻意分叉：[ParseError] 在 peach 里是永久失败
     * （`PERMANENT_REASONS`），本项目给的是 5 分钟冷却而不是「永不重试」——站点改版可能只是
     * 临时返回半页，也可能确实是改版；用冷却表达「先别打」比用不可重试表达「永远不行」更安全，
     * 因为前者会自动回正。
     */
    fun toLocalKind(): ScrapeFailureKind = when (this) {
        CloudflareChallenge -> ScrapeFailureKind.Blocked
        IpBanned -> ScrapeFailureKind.Forbidden
        // 地区限制与登录墙：重试同一个出口不会成功，归 Config（不可重试），
        // 否则来源会被反复冷却又反复撞墙，用户看到的是「一直在冷却」而不是「这个站用不了」。
        GeoRestricted, AuthRequired -> ScrapeFailureKind.Config
        NotFound, Gone, NoUsableMetadata -> ScrapeFailureKind.NotFound
        ParseError -> ScrapeFailureKind.ParseError
        RateLimited -> ScrapeFailureKind.RateLimited
        Timeout, Network -> ScrapeFailureKind.Network
        ServerError -> ScrapeFailureKind.ServerError
    }

    /** 带上下文的失败对象（消息保留来源侧的说明，方便日志直读）。 */
    fun toLocalFailure(message: String, httpCode: Int? = null): ScrapeFailure =
        ScrapeFailure(kind = toLocalKind(), message = message, httpCode = httpCode)

    companion object {
        /** 从本项目的处置档反推一个 peach 档：只用于既有 scraper 抛出的异常。 */
        fun fromLocalKind(kind: ScrapeFailureKind): PeachFailureReason = when (kind) {
            ScrapeFailureKind.Forbidden -> IpBanned
            ScrapeFailureKind.RateLimited -> RateLimited
            ScrapeFailureKind.Blocked -> CloudflareChallenge
            ScrapeFailureKind.ServerError -> ServerError
            ScrapeFailureKind.NotFound -> NotFound
            ScrapeFailureKind.ParseError -> ParseError
            ScrapeFailureKind.Network -> Network
            // 配置错误在本项目里是「源没启用 / 地址非法」，peach 没有对应档，
            // 借 AuthRequired 表达「这一档不是网络问题、重试没用」——两者同为 auth 类。
            ScrapeFailureKind.Config -> AuthRequired
            ScrapeFailureKind.Unknown -> Network
        }
    }
}

/** peach 的三档大类（`REASON_KINDS` 的取值）。 */
enum class PeachReasonKind(val label: String) {
    Auth("授权/拦截"),
    Unavailable("暂时不可用"),
    NotFound("没有")
}

/** peach 的冷却动作（`COOLDOWN_ACTIONS` 的取值）。 */
enum class PeachCooldownAction(val label: String) {
    /** 整个来源停：验证页、封禁、地区限制。 */
    Blocked("整源冷却"),

    /** 限流：同样整源停，但语义上是「打太快」。 */
    RateLimited("限流冷却")
}

/**
 * 把一次异常翻成 peach 的失败档。
 *
 * 为什么还留一套自己的分类器而不是直接用 [ScrapeFailureClassifier]：本项目的分类器认的是
 * 「异常类型 + 文案 + HTTP 状态码」，它给出的九档里没有「地区限制 / 登录墙 / 没可用字段」
 * 这几档，而那几档正是 peach 链决定「这个源别再问了」的依据。这里先看显式的
 * [PeachSourceFailure]，再退回本项目的分类结果再翻译——两条路都不丢信息，
 * 新源可以精确报档，老源不需要改一行。
 */
object PeachReasonClassifier {

    private val httpCodeRegex = Regex("""HTTP\s*(\d{3})""", RegexOption.IGNORE_CASE)

    fun classify(error: Throwable): PeachFailureReason {
        // 新源自己报的档最准：它们能分清 404（没有）与 410（下架）、
        // 也能分清「页面拿到了但没有字段」与「页面结构没认出来」。
        (error as? PeachSourceFailure)?.let { return it.reason }

        val text = buildString {
            append(error::class.java.simpleName)
            append(": ")
            append(error.message.orEmpty())
        }
        val httpCode = httpCodeRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
        httpCode?.let { code -> httpCodeToReason(code)?.let { return it } }

        if (isTransportFailure(error)) return PeachFailureReason.Network

        val lower = text.lowercase()
        return when {
            lower.contains("验证") || lower.contains("captcha") || lower.contains("人机") ->
                PeachFailureReason.CloudflareChallenge
            lower.contains("地区") || lower.contains("地域") -> PeachFailureReason.GeoRestricted
            lower.contains("需要登录") || lower.contains("登录后") -> PeachFailureReason.AuthRequired
            lower.contains("下架") || lower.contains("已删除") -> PeachFailureReason.Gone
            lower.contains("没有搜索到") || lower.contains("未匹配") || lower.contains("没有返回") ||
                lower.contains("搜不到") || lower.contains("没有收录") ||
                lower.contains("对不上") -> PeachFailureReason.NotFound
            lower.contains("解析") || lower.contains("响应体超过") || lower.contains("结构") ->
                PeachFailureReason.ParseError
            lower.contains("超时") || lower.contains("timeout") -> PeachFailureReason.Timeout
            lower.contains("没有可用字段") || lower.contains("没有可用元数据") ->
                PeachFailureReason.NoUsableMetadata
            // 其余一律退回本项目的九档再翻译：保证「既有的八个源一行不改也能进 peach 链」，
            // 它们抛的还是 `error("...")`，文案里塞着 HTTP 状态码。
            else -> PeachFailureReason.fromLocalKind(ScrapeFailureClassifier.fromThrowable(error).kind)
        }
    }

    /** HTTP 状态码 → peach 档；认不出的码返回 null，交给文案与异常类型判断。 */
    fun httpCodeToReason(code: Int): PeachFailureReason? = when {
        code == 401 -> PeachFailureReason.AuthRequired
        code == 403 -> PeachFailureReason.IpBanned
        code == 404 -> PeachFailureReason.NotFound
        code == 410 -> PeachFailureReason.Gone
        code == 429 -> PeachFailureReason.RateLimited
        code == 408 || code == 504 -> PeachFailureReason.Timeout
        code in 500..599 -> PeachFailureReason.ServerError
        else -> null
    }

    /** 传输层异常（含链式 cause）判定，与 [ScrapeFailureClassifier] 同口径。 */
    fun isTransportFailure(error: Throwable): Boolean {
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
                is IOException -> return true
                else -> Unit
            }
            current = current.cause
            depth += 1
        }
        return false
    }
}

/**
 * 新源专用：直接带 peach 档的失败。
 *
 * 用它而不是 `error("...")` 字符串的唯一原因是**可分类**：站点说「没有」和站点说
 * 「你被 Cloudflare 挡了」在链上的处置完全不同（前者换源、后者冷却），而字符串匹配
 * 一旦改文案就失效。继承 [IllegalStateException] 是为了让既有捕获逻辑无需改动。
 */
class PeachSourceFailure(
    val reason: PeachFailureReason,
    message: String,
    val httpCode: Int? = null,
    val detail: String = ""
) : IllegalStateException(message) {

    /** 翻成本项目的失败对象（带冷却）。 */
    fun toLocalFailure(): ScrapeFailure =
        ScrapeFailure(kind = reason.toLocalKind(), message = message.orEmpty(), httpCode = httpCode)

    override fun toString(): String = "PeachSourceFailure($reason): ${message.orEmpty()}"
}
