package com.example.localmovielibrarz.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * AVBase 搜索页（peach `sources/avbase.py`）。
 *
 * 这一层只做「把一页取回来并交给 [AvbaseParser]」：解析、番号比对、封面候选筛选全在
 * 那个纯文件里，因为它能被离线单测覆盖，而本文件（`import okhttp3`）不能。
 *
 * 失败按 peach 的档位报（[PeachSourceFailure]）：AVBase 拒绝访问时回的是 Cloudflare 验证页，
 * 状态码可能是 403 也可能是 200——403 走整站冷却，200 的那张由解析器认成
 * `cloudflare_challenge`，两者落到同一份冷却记录上，但日志里能分清是哪一种。
 */
class AvbaseScraper(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MovieScraper {

    override val source: ScrapeSource = ScrapeSource.Avbase

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val code = ScraperResponseGuard.requireSafeScrapeNumber(number)
        val url = AvbaseParser.searchUrl(code)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ScrapeUserAgent.VALUE)
            .header("Accept-Language", ScrapeUserAgent.ACCEPT_LANGUAGE)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            // Referer 用站内搜索结果页：直接从空白页跳进来是抓取脚本的典型特征。
            .header("Referer", "${AvbaseParser.BASE_URL}/")
            .build()

        val html = client.newCall(request).execute().use { response ->
            val body = response.body ?: throw PeachSourceFailure(
                PeachFailureReason.ParseError, "AVBase 响应为空"
            )
            val bytes = ScraperResponseGuard.readLimited(body, ScrapeSource.Avbase)
            val text = bytes.decodeToString()
            if (!response.isSuccessful) {
                // 4xx/5xx 交给档位映射：404 是「没有」，403 是「被挡」，429 是「打太快」。
                val status = response.code
                val reason = PeachReasonClassifier.httpCodeToReason(status)
                    ?: PeachFailureReason.ServerError
                throw PeachSourceFailure(reason, "AVBase 请求失败 HTTP $status", httpCode = status)
            }
            // 200 也可能是验证页：先按挑战页判一次，别把它当成「结构改版」记进永久失败。
            ChallengePageDetector.detect(text, ScrapeSource.Avbase)?.let { failure ->
                throw PeachSourceFailure(
                    PeachFailureReason.CloudflareChallenge,
                    failure.message.orEmpty().ifBlank { AvbaseParser.UNRECOGNISED }
                )
            }
            text
        }

        when (val parsed = AvbaseParser.parse(html, code)) {
            is PeachParseResult.Record -> PeachCoverProbe(client, ScrapeSource.Avbase).applyTo(parsed.info, parsed.coverUrls)
            is PeachParseResult.Failed -> throw PeachSourceFailure(parsed.reason, parsed.message)
        }
    }
}
