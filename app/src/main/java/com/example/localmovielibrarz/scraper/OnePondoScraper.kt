package com.example.localmovielibrarz.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 一本道详情 JSON（peach `sources/onepondo.py`）。
 *
 * 走 `/dyn/phpauto/movie_details/movie_id/<id>.json` 而不是抓 `/movies/<id>/` 的 HTML：
 * 那一页是壳，真正的字段全在这个接口里，而接口不要任何凭据。解析与判定在 [OnePondoParser]。
 *
 * 只对「日期式无码番号」有意义（`012345_001` 这一种）。链上把它排在 avbase 之前，
 * 不是因为它更权威，而是因为它是发行方本人：无码片在目录站上常只有一段简介与一张缩略图，
 * 发行方给的是完整标题、时长、演员与高清封面。
 *
 * 番号形态不成立时**不发请求**，直接报 `not_found`：这不是失败，是「这家问不了这种番号」。
 * 发出去只会让对方的日志里多一条 404，而链本身还要按间隔等几秒。
 */
class OnePondoScraper(
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MovieScraper {

    override val source: ScrapeSource = ScrapeSource.OnePondo

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val code = ScraperResponseGuard.requireSafeScrapeNumber(number)
        val movieId = OnePondoParser.movieId(code)
        if (movieId.isEmpty()) {
            throw PeachSourceFailure(PeachFailureReason.NotFound, OnePondoParser.UNRECOGNISED)
        }
        val request = Request.Builder()
            .url(OnePondoParser.detailUrl(movieId))
            .header("User-Agent", ScrapeUserAgent.VALUE)
            .header("Accept-Language", ScrapeUserAgent.ACCEPT_LANGUAGE)
            .header("Accept", "application/json,text/plain,*/*")
            // 接口挂在详情页同源下，带上详情页作 Referer 与浏览器里点开那一页时一致。
            .header("Referer", OnePondoParser.pageUrl(movieId))
            .build()

        val body = client.newCall(request).execute().use { response ->
            val payload = response.body ?: throw PeachSourceFailure(
                PeachFailureReason.ParseError, "一本道响应为空"
            )
            val text = ScraperResponseGuard.readLimited(payload, ScrapeSource.OnePondo).decodeToString()
            if (!response.isSuccessful) {
                val status = response.code
                val reason = PeachReasonClassifier.httpCodeToReason(status)
                    ?: PeachFailureReason.ServerError
                throw PeachSourceFailure(reason, "一本道请求失败 HTTP $status", httpCode = status)
            }
            text
        }

        when (val parsed = OnePondoParser.parse(body, code)) {
            is PeachParseResult.Record -> PeachCoverProbe(client, ScrapeSource.OnePondo).applyTo(parsed.info, parsed.coverUrls)
            is PeachParseResult.Failed -> throw PeachSourceFailure(parsed.reason, parsed.message)
        }
    }
}