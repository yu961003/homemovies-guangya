package com.example.localmovielibrarz.scraper

/**
 * 反爬验证页（Cloudflare「Just a moment...」之类）识别。
 *
 * 为什么需要它：验证页通常返回 200、HTML 结构也完全正常，如果只按「状态码 200 + 解析失败」
 * 归类，就会被当成 [ScrapeFailureKind.ParseError]（5 分钟冷却），于是程序很快又打一次，
 * 越打越深；用户看到的理由也会是「解析失败」，而不是「该换网络了」。
 *
 * 判定只看 `<title>`，这条取舍是刻意的：
 * - **不**按 `challenge-platform`、`cf_chl`、`cdn-cgi`、`window._cf_chl_opt` 之类的标记判断。
 *   Cloudflare 会把这些脚本注入到正常页面里，按标记判会把好页误判成验证页；
 * - 只看前 [SNIFF_LIMIT] 个字符。挑战页的 title 一定在头部，正常页面动辄几百 KB，
 *   全文扫描纯属浪费；
 * - 不识别正文里的「人机验证」文案，各家站点文案不同，标题才是最稳定的特征。
 *
 * 命中时抛出的异常消息带「人机校验」关键词，上层 [ScrapeFailureClassifier] 会据此把异常
 * 升级为 [ScrapeFailureKind.Blocked]（30 分钟冷却），而不是短暂的 ParseError。
 */
object ChallengePageDetector {

    /** 只看前 4096 个字符。 */
    const val SNIFF_LIMIT = 4096

    /** 兼容旧名字；新代码用 [SNIFF_LIMIT]。 */
    @Deprecated("改用 SNIFF_LIMIT")
    const val SNIFF_BYTES: Int = SNIFF_LIMIT

    /**
     * 只取 `<title>...</title>` 的内容。
     *
     * `DOT_MATCHES_ALL` 让 title 内部有换行时也能整段取出；范围已被 [SNIFF_LIMIT] 限制，
     * 不存在对超大页面做回溯的风险。
     */
    private val TITLE = Regex(
        pattern = "<title\\b[^>]*>(.*?)</title>",
        options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    /** 归一化（trim + 小写）后的 title 命中集合。 */
    private val CHALLENGE_TITLES = setOf(
        "just a moment...",
        "请稍候…",
        "请稍候...",
        "attention required! | cloudflare",
    )

    /**
     * 判断一段页面文本是否是验证页。
     *
     * @param body 已解码的页面文本；`null`、空串或前 4096 字符里没有 `<title>` 都返回 `false`。
     */
    fun isChallengePage(body: String?): Boolean {
        if (body.isNullOrEmpty()) return false
        val title = TITLE.find(body.take(SNIFF_LIMIT))?.groupValues?.get(1) ?: return false
        return title.trim().lowercase() in CHALLENGE_TITLES
    }

    /**
     * 刮削器取到页面后的统一闸门：命中验证页就抛错，未命中直接返回。
     *
     * 消息里必须保留「人机校验」四个字，[ScrapeFailureClassifier] 靠它把异常归到
     * [ScrapeFailureKind.Blocked] 并进入站点冷却；改成「解析失败」会让退避时间缩回 5 分钟。
     */
    fun requireNotChallengePage(body: String?, source: ScrapeSource) {
        if (!isChallengePage(body)) return
        throw IllegalStateException("${source.label} 返回人机校验页（疑似 Cloudflare/验证码），已中止本次解析")
    }

    /**
     * 兼容旧调用：四个既有刮削器仍调用这个名字。
     *
     * 为什么不直接删掉：那四个文件不在本次改动范围内，删除会让 Tier-2 编译断引用；
     * 这里保留一个转发，行为与 [requireNotChallengePage] 完全一致。
     */
    @Deprecated(
        message = "改用 requireNotChallengePage",
        replaceWith = ReplaceWith("requireNotChallengePage(body, source)")
    )
    fun requireNotChallenge(body: String?, source: ScrapeSource) {
        requireNotChallengePage(body, source)
    }

    /**
     * 兼容旧调用：把命中转成 [ScrapeFailure] 对象。
     *
     * 新代码直接用 [requireNotChallengePage]；保留这个入口只是为了避免旧测试/旧调用断引用。
     */
    @Deprecated(
        message = "改用 requireNotChallengePage",
        replaceWith = ReplaceWith("requireNotChallengePage(html, source)")
    )
    fun detect(html: String?, source: ScrapeSource): ScrapeFailure? {
        if (!isChallengePage(html)) return null
        return ScrapeFailure(
            kind = ScrapeFailureKind.Blocked,
            message = "${source.label} 返回人机校验页（疑似 Cloudflare/验证码），已中止本次解析",
            httpCode = 200
        )
    }
}
