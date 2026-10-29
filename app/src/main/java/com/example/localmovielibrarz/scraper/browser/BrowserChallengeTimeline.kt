package com.example.localmovielibrarz.scraper.browser

/**
 * 一次「页面加载」的时间轴决策。
 *
 * 这是 peach `scraping_access` / `browser_transport` 那套浏览器回落状态机的纯函数移植：
 * 真实实现要开 WebView、轮询 `document.readyState`、在必要时刻把窗口提到前台让人点，
 * 但「什么时候还该等、什么时候清 Cookie 重载、什么时候该弹窗、什么时候放弃」全是**时间与
 * 页面状态的纯判断**。把它抽出来有两个理由：
 *
 * 1. 这条路径在真机上极难复现——要真的撞上 Cloudflare 挑战、真的等 40 秒、真的有人去点。
 *    抽成纯函数后，「按秒喂观察值 → 断言第 N 秒的决策」可以在无 Android SDK 的机器上跑。
 * 2. 三个超时（15 / 40 / 120 秒）是**互相咬合**的，散在 WebView 回调里必然分叉。集中一处
 *    才能让「为什么这次 25 秒就放弃了」有唯一答案。
 *
 * 本文件不 import 任何 android 符号，留在 Tier 1 编译集里。
 */
object BrowserChallengeTimeline {

    /** 挑战页转这么久还没过去 → 清 Cookie 重载一次（peach `RESET_SECONDS`）。 */
    const val RESET_SECONDS = 15L

    /** 重载后再等这么久还没过去 → 把窗口提到前台让人点（peach `AUTO_SECONDS`）。 */
    const val AUTO_SECONDS = 40L

    /** 弹窗之后最多等这么久；超时即放弃并对该 host 记一笔「弹过但没点过去」（peach `CLICK_SECONDS`）。 */
    const val CLICK_SECONDS = 120L

    /** 10 分钟没有取页就回收 WebView（peach `IDLE_SECONDS`）。 */
    const val IDLE_MILLIS = 600_000L

    /**
     * 挑战页标记（peach `CHALLENGE_MARKS`）。
     *
     * 比 `ChallengePageDetector.isChallengePage` 宽：那边只认 `<title>` 的三种文案，这里连
     * 正文里的 `cf-chl-` / `challenge-platform` 也算——同一个挑战页在页面没解析完时 title
     * 还是空的，只靠 title 会把「正在验证」误判成「加载完了」。
     */
    private val CHALLENGE_MARKS = listOf(
        "Just a moment",
        "请稍候",
        "cf-chl-",
        "challenge-platform",
        "Verifying you are human",
        "Attention Required"
    )

    /**
     * 当前这一页是不是挑战页。
     *
     * [headExcerpt] 只需传 `document.documentElement.outerHTML` 的前几 KB：
     * 挑战页的脚本与容器都在 `<head>` 附近，截尾足够。
     */
    fun isChallenged(title: String, headExcerpt: String): Boolean {
        val haystack = title + "\n" + headExcerpt
        return CHALLENGE_MARKS.any { haystack.contains(it, ignoreCase = true) }
    }

    /** 当前观测到的页面状态。每次轮询（建议 1 秒）构造一份。 */
    data class Observation(
        /** 本次加载开始到现在的毫秒数。清 Cookie 重载会重置这个起点。 */
        val elapsedMillis: Long,
        val title: String,
        /** `document.readyState`：`loading` / `interactive` / `complete`。 */
        val readyState: String,
        /** `document.documentElement.outerHTML` 前 4000 字符。 */
        val headExcerpt: String
    )

    sealed interface Decision {
        data object KeepWaiting : Decision

        /** 正在验证 → 清 Cookie 重载一次。 */
        data object ClearCookiesAndReload : Decision

        /** 自动等不到了 → 把 WebView 提到前台让人点。 */
        data class PromoteToForeground(val message: String) : Decision

        /** 挑战过去了，可以读正文。 */
        data object ChallengeSolved : Decision

        /** 放弃。调用方应把它翻成 `PeachFailureReason.CloudflareChallenge` 并**不再重试**。 */
        data class GiveUp(val message: String) : Decision
    }

    /**
     * 判定下一步。
     *
     * @param observation 本次轮询的观测值
     * @param cleared 本次加载是否已经清过 Cookie 重载（每次重载后置 true，重置起点时清回 false）
     * @param shownAtMillis 弹窗时刻（`elapsedMillis` 时间轴）；null 表示还没弹过
     * @param hostAlreadyFailed 该 host 之前弹过窗但没人点过去（跨请求记忆）；为 true 时不再弹窗，直接放弃
     * @param timeoutMillis 上层给这次取页的总时限
     */
    fun decide(
        observation: Observation,
        cleared: Boolean,
        shownAtMillis: Long?,
        hostAlreadyFailed: Boolean,
        timeoutMillis: Long
    ): Decision {
        val elapsed = observation.elapsedMillis
        val challenged = isChallenged(observation.title, observation.headExcerpt)

        if (challenged) {
            // 第一次重载的时机必须**早于**弹窗时机，否则「先弹窗、后重载」会让刚点过去的
            // 验证被一次 Cookie 清除立刻作废。取两者较小值就是在守住这个顺序。
            val resetLimit = minOf(RESET_SECONDS * 1000, minOf(AUTO_SECONDS * 1000, timeoutMillis))
            if (!cleared && elapsed >= resetLimit) return Decision.ClearCookiesAndReload

            if (cleared && elapsed >= minOf(AUTO_SECONDS * 1000, timeoutMillis)) {
                return if (hostAlreadyFailed) {
                    Decision.GiveUp(
                        "人机验证在 ${AUTO_SECONDS} 秒内没有自动通过；窗口上次弹出后没点过去，这次不再弹"
                    )
                } else {
                    Decision.PromoteToForeground("人机验证需要点一下，浏览器窗口已打开")
                }
            }
        }

        // 弹窗之后的超时与「是不是挑战页」无关：用户可能已经点过、页面正在跳转，
        // 也可能点了没反应。两种情况都在 CLICK_SECONDS 后收手。
        if (shownAtMillis != null && elapsed - shownAtMillis >= CLICK_SECONDS * 1000) {
            return Decision.GiveUp("人机验证在 ${CLICK_SECONDS} 秒内没有通过")
        }

        val ready = observation.readyState == "interactive" || observation.readyState == "complete"
        if (ready && !challenged && observation.title.isNotBlank()) return Decision.ChallengeSolved

        if (!challenged && elapsed >= timeoutMillis) {
            return Decision.GiveUp("页面在 ${timeoutMillis / 1000} 秒内没有打开")
        }

        return Decision.KeepWaiting
    }
}