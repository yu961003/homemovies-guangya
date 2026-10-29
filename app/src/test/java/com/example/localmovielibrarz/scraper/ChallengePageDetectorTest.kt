package com.example.localmovielibrarz.scraper

import org.junit.Assert.*
import org.junit.Test

class ChallengePageDetectorTest {

    private fun page(title: String): String =
        "<!DOCTYPE html><html><head><title>$title</title></head><body>正文</body></html>"

    @Test
    fun detectsEnglishCloudflareTitle() {
        assertTrue(ChallengePageDetector.isChallengePage(page("Just a moment...")))
    }

    @Test
    fun detectsChineseCloudflareTitleWithFullWidthEllipsis() {
        assertTrue(ChallengePageDetector.isChallengePage(page("请稍候…")))
    }

    @Test
    fun detectsChineseCloudflareTitleWithHalfWidthDots() {
        assertTrue(ChallengePageDetector.isChallengePage(page("请稍候...")))
    }

    @Test
    fun detectsAttentionRequiredTitle() {
        assertTrue(ChallengePageDetector.isChallengePage(page("Attention Required! | Cloudflare")))
    }

    @Test
    fun titleMatchIgnoresCaseAndSurroundingWhitespace() {
        assertTrue(ChallengePageDetector.isChallengePage(page("  jUsT a MoMeNt...  ")))
    }

    @Test
    fun normalPageWithChallengePlatformScriptIsNotAChallengePage() {
        val body = """
            <html><head>
              <title>作品详情</title>
              <script src="https://example.com/cdn-cgi/challenge-platform/h/b/orchestrate/jsch/v1"></script>
              <script>window._cf_chl_opt = { cvId: '3' };</script>
            </head><body>正常内容</body></html>
        """.trimIndent()
        assertFalse(ChallengePageDetector.isChallengePage(body))
    }

    @Test
    fun emptyOrNullBodyIsNotAChallengePage() {
        assertFalse(ChallengePageDetector.isChallengePage(null))
        assertFalse(ChallengePageDetector.isChallengePage(""))
        assertFalse(ChallengePageDetector.isChallengePage("   "))
    }

    @Test
    fun titleOutsideSniffWindowIsIgnored() {
        val body = "x".repeat(ChallengePageDetector.SNIFF_LIMIT) + page("Just a moment...")
        assertFalse(ChallengePageDetector.isChallengePage(body))
    }

    @Test
    fun requireNotChallengePageMentionsHumanVerificationKeyword() {
        val error = assertThrows(IllegalStateException::class.java) {
            ChallengePageDetector.requireNotChallengePage(page("Just a moment..."), ScrapeSource.Dmm)
        }
        assertTrue(error.message!!.contains("人机校验"))
        assertTrue(error.message!!.contains(ScrapeSource.Dmm.label))
    }

    @Test
    fun requireNotChallengePagePassesNormalPage() {
        ChallengePageDetector.requireNotChallengePage(page("正常页面"), ScrapeSource.Dmm2)
    }
}
