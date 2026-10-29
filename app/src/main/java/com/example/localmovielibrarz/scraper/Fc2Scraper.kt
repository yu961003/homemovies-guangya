package com.example.localmovielibrarz.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * FC2 内容市场商品页（peach `sources/fc2.py`）。
 *
 * 解析与判定在 [Fc2Parser]，本文件只负责取那一页。FC2 的链上位置固定在第一位：
 * 它是这批番号的发行方，目录站对 FC2 番号基本是「没有这个番号」。
 *
 * 下架的商品仍然回 200，由解析器按「正文里没有 Product」判成 `not_found`。
 * 关键在于**不能在这里按状态码判**：200 + 没有商品 与 200 + 有商品 长得一模一样，
 * 状态码这一层看不出区别。
 */
class Fc2Scraper(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MovieScraper {

    override val source: ScrapeSource = ScrapeSource.Fc2

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val code = ScraperResponseGuard.requireSafeScrapeNumber(number)
        val videoId = Fc2Parser.videoId(code)
        if (videoId.isEmpty()) {
            throw PeachSourceFailure(PeachFailureReason.NotFound, Fc2Parser.UNRECOGNISED)
        }
        val url = Fc2Parser.articleUrl(videoId)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ScrapeUserAgent.VALUE)
            .header("Accept-Language", ScrapeUserAgent.ACCEPT_LANGUAGE)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Referer", "${Fc2Parser.BASE_URL}/")
            .build()

        val html = client.newCall(request).execute().use { response ->
            val body = response.body ?: throw PeachSourceFailure(
                PeachFailureReason.ParseError, "FC2 响应为空"
            )
            val text = ScraperResponseGuard.readLimited(body, ScrapeSource.Fc2).decodeToString()
            if (!response.isSuccessful) {
                val status = response.code
                val reason = PeachReasonClassifier.httpCodeToReason(status)
                    ?: PeachFailureReason.ServerError
                throw PeachSourceFailure(reason, "FC2 请求失败 HTTP $status", httpCode = status)
            }
            text
        }

        when (val parsed = Fc2Parser.parse(html, code)) {
            is PeachParseResult.Record -> PeachCoverProbe(client, ScrapeSource.Fc2).applyTo(parsed.info, parsed.coverUrls)
            is PeachParseResult.Failed -> throw PeachSourceFailure(parsed.reason, parsed.message)
        }
    }
}