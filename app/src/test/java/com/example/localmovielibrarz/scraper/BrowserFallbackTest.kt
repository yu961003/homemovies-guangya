package com.example.localmovielibrarz.scraper

import com.example.localmovielibrarz.scraper.browser.BrowserChallengeTimeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览器回落这条链的离线回归。
 *
 * 为什么这三处值得单测：它们都是「真机上撞不到就永远测不了」的分支——挑战页要真的出现、
 * 403 要真的发生、120 秒放弃要真的等。抽成纯函数之后，这些分支可以不碰网络、不碰 Android
 * 地逐条断言，而它们恰恰是**最容易改坏**的一段（阈值互相咬合、来源集合按站点增删）。
 */
class BrowserFallbackTest {

    // ── BrowserFallbackPolicy ──────────────────────────────────────────────

    @Test
    fun fc2ppvdbAndJavtenPreferBrowser() {
        // peach 侧这两家 `browser: True`：上来就走浏览器，不先试 HTTP。
        assertTrue(BrowserFallbackPolicy.prefersBrowser(ScrapeSource.Fc2PpvDb))
        assertTrue(BrowserFallbackPolicy.prefersBrowser(ScrapeSource.Javten))
    }

    @Test
    fun otherSourcesDoNotPreferBrowser() {
        // DMM 系从未出验证页，为它开放浏览器只会把一次网络抖动变成一次界面卡顿。
        assertFalse(BrowserFallbackPolicy.prefersBrowser(ScrapeSource.Dmm))
        assertFalse(BrowserFallbackPolicy.prefersBrowser(ScrapeSource.Fc2Cmadb))
    }

    @Test
    fun fallbackAllowedOnlyForBlockedSources() {
        for (source in listOf(
            ScrapeSource.Fc2Cmadb, ScrapeSource.Fc2PpvDb, ScrapeSource.Javten,
            ScrapeSource.Faleno, ScrapeSource.Dahlia, ScrapeSource.MakerSite
        )) {
            assertTrue("$source 应允许回落", BrowserFallbackPolicy.allowsFallback(source))
        }
        assertFalse(BrowserFallbackPolicy.allowsFallback(ScrapeSource.Dmm))
        assertFalse(BrowserFallbackPolicy.allowsFallback(ScrapeSource.R18Dev))
    }

    @Test
    fun refusedByStatusOrChallengeBody() {
        assertTrue(BrowserFallbackPolicy.isRefused(403, ""))
        assertTrue(BrowserFallbackPolicy.isRefused(429, ""))
        // 200 也可能是验证页：状态码这一层看不出来，只能看正文。
        assertTrue(
            BrowserFallbackPolicy.isRefused(
                200,
                "<html><head><title>Just a moment...</title></head><body></body></html>"
            )
        )
        assertFalse(BrowserFallbackPolicy.isRefused(200, "<html><body>正常商品页</body></html>"))
        // 404 是「没有这个番号」，不是「被挡」——回落浏览器只会白跑一次。
        assertFalse(BrowserFallbackPolicy.isRefused(404, ""))
    }

    // ── BrowserChallengeTimeline ───────────────────────────────────────────

    private fun challenged(elapsedMillis: Long, ready: String = "loading") =
        BrowserChallengeTimeline.Observation(
            elapsedMillis = elapsedMillis,
            title = "Just a moment...",
            readyState = ready,
            headExcerpt = "<div class=\"cf-chl-widget\"></div>"
        )

    private fun loaded(elapsedMillis: Long) = BrowserChallengeTimeline.Observation(
        elapsedMillis = elapsedMillis,
        title = "商品页",
        readyState = "complete",
        headExcerpt = "<html><body>正文</body></html>"
    )

    @Test
    fun keepsWaitingBeforeResetWindow() {
        val decision = BrowserChallengeTimeline.decide(
            observation = challenged(elapsedMillis = 5_000),
            cleared = false,
            shownAtMillis = null,
            hostAlreadyFailed = false,
            timeoutMillis = 60_000
        )
        assertEquals(BrowserChallengeTimeline.Decision.KeepWaiting, decision)
    }

    @Test
    fun clearsCookiesOnceAtFifteenSeconds() {
        val decision = BrowserChallengeTimeline.decide(
            observation = challenged(elapsedMillis = BrowserChallengeTimeline.RESET_SECONDS * 1000),
            cleared = false,
            shownAtMillis = null,
            hostAlreadyFailed = false,
            timeoutMillis = 60_000
        )
        assertEquals(BrowserChallengeTimeline.Decision.ClearCookiesAndReload, decision)
    }

    @Test
    fun promotesToForegroundAfterResetWhenStillChallenged() {
        val decision = BrowserChallengeTimeline.decide(
            observation = challenged(elapsedMillis = BrowserChallengeTimeline.AUTO_SECONDS * 1000),
            cleared = true,
            shownAtMillis = null,
            hostAlreadyFailed = false,
            timeoutMillis = 60_000
        )
        assertTrue(decision is BrowserChallengeTimeline.Decision.PromoteToForeground)
    }

    @Test
    fun givesUpWithoutPromptingWhenHostAlreadyFailed() {
        // 弹过一次没人点过去：不该再浪费用户 40 秒。
        val decision = BrowserChallengeTimeline.decide(
            observation = challenged(elapsedMillis = BrowserChallengeTimeline.AUTO_SECONDS * 1000),
            cleared = true,
            shownAtMillis = null,
            hostAlreadyFailed = true,
            timeoutMillis = 60_000
        )
        assertTrue(decision is BrowserChallengeTimeline.Decision.GiveUp)
    }

    @Test
    fun reportsSolvedOncePageIsReadyAndNotChallenged() {
        val decision = BrowserChallengeTimeline.decide(
            observation = loaded(elapsedMillis = 3_000),
            cleared = false,
            shownAtMillis = null,
            hostAlreadyFailed = false,
            timeoutMillis = 60_000
        )
        assertEquals(BrowserChallengeTimeline.Decision.ChallengeSolved, decision)
    }

    @Test
    fun clickTimeoutAppliesRegardlessOfChallengeState() {
        // 弹窗后已过 CLICK_SECONDS：即便页面看起来正常也要收手，否则调用方会一直挂在 onPageFinished 上。
        val decision = BrowserChallengeTimeline.decide(
            observation = loaded(elapsedMillis = BrowserChallengeTimeline.CLICK_SECONDS * 1000 + 1),
            cleared = true,
            shownAtMillis = 0L,
            hostAlreadyFailed = false,
            timeoutMillis = 60_000
        )
        assertTrue(decision is BrowserChallengeTimeline.Decision.GiveUp)
    }

    // ── PeachFetchBridge ───────────────────────────────────────────────────

    /** 按预设回应答的取页器：本文件只验证「取页层的结果如何变成链上的失败档位」。 */
    private class StubFetcher(
        private val response: SourcePageResponse,
        private val error: SourceFetchException? = null
    ) : SourcePageFetcher {
        var calls = 0
        override suspend fun fetch(request: SourcePageRequest): SourcePageResponse {
            calls += 1
            error?.let { throw it }
            return response
        }
    }

    private fun request(source: ScrapeSource = ScrapeSource.Fc2PpvDb) = SourcePageRequest(
        url = "https://example.test/page",
        sourceLabel = source.label,
        source = source
    )

    @Test
    fun browserBlockedBecomesCloudflareChallenge() = runBlocking {
        val fetcher = StubFetcher(
            response = SourcePageResponse("", 200, ""),
            error = SourceFetchException(
                message = "两端都被拦",
                reason = PeachFailureReason.CloudflareChallenge,
                statusCode = 403,
                browserAttempted = true,
                browserBlocked = true
            )
        )
        val failure = runCatching { PeachFetchBridge.fetchOrThrow(fetcher, request(), "FC2-PPV-1") }
            .exceptionOrNull() as PeachSourceFailure
        // 保持 CloudflareChallenge：链上按**整站冷却**处理，而不是当解析失败反复重试。
        assertEquals(PeachFailureReason.CloudflareChallenge, failure.reason)
        assertEquals(403, failure.httpCode)
        assertTrue(failure.message.orEmpty().contains("浏览器通道都被站方拦下"))
        assertTrue(failure.detail.contains("browser_attempted"))
    }

    @Test
    fun httpFailureKeepsItsOwnReason() = runBlocking {
        val fetcher = StubFetcher(SourcePageResponse("", 404, ""))
        val failure = runCatching { PeachFetchBridge.fetchOrThrow(fetcher, request(), "FC2-PPV-1") }
            .exceptionOrNull() as PeachSourceFailure
        // 404 归 NotFound：含义是「换下一家问」，不能进整源冷却。
        assertEquals(PeachFailureReason.NotFound, failure.reason)
        assertEquals(404, failure.httpCode)
    }

    @Test
    fun challengeBodyInSuccessfulResponseIsStillChallenge() = runBlocking {
        val fetcher = StubFetcher(
            SourcePageResponse(
                url = "https://example.test/page",
                status = 200,
                body = "<html><head><title>Just a moment...</title></head><body></body></html>"
            )
        )
        val failure = runCatching { PeachFetchBridge.fetchOrThrow(fetcher, request(), "FC2-PPV-1") }
            .exceptionOrNull() as PeachSourceFailure
        assertEquals(PeachFailureReason.CloudflareChallenge, failure.reason)
    }

    @Test
    fun successfulResponsePassesThrough() = runBlocking {
        val fetcher = StubFetcher(SourcePageResponse("https://example.test/page", 200, "<html>ok</html>"))
        val response = PeachFetchBridge.fetchOrThrow(fetcher, request(), "FC2-PPV-1")
        assertEquals(200, response.status)
        assertEquals(1, fetcher.calls)
    }
}