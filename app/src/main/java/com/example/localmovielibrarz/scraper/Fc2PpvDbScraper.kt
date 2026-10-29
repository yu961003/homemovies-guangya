package com.example.localmovielibrarz.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * FC2PPV-DB（peach `sources/fc2ppvdb.py`）。解析在 [Fc2PpvDbParser]。
 *
 * ## 与其它四家不同的两点
 *
 * 1. **上来就走浏览器**：peach 侧 `browser: True`，这一站对数据中心出口长期直接给验证页。
 *    通道选择由 [SourcePageFetcher] 按 [BrowserFallbackPolicy.prefersBrowser] 决定，
 *    本类只发请求，不自己判要不要上浏览器。
 * 2. **需要最终 URL**：站点有 `/age-verify` 与 `/ja/`、`/en/` 的语言跳转，解析器要靠
 *    `finalUrl` 判断「这一页到底是不是商品页」。所以这里必须把 [SourcePageResponse.url]
 *    （跟随重定向之后的地址）传下去，而不是自己拼的那个 URL。
 *
 * ## 封面为什么允许为空
 *
 * 这一家的商品页封面不总是给（有的是懒加载占位）；解析器把候选封面留空时链上会由别家补。
 * 这是刻意的：为了一张封面把整条结果判失败，等于让一部能在 JAVten 查到的片在这家卡住。
 */
class Fc2PpvDbScraper(
    private val fetcher: SourcePageFetcher,
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MovieScraper {

    override val source: ScrapeSource = ScrapeSource.Fc2PpvDb

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val code = ScraperResponseGuard.requireSafeScrapeNumber(number)
        val videoId = Fc2Parser.videoId(code)
        if (videoId.isEmpty()) {
            throw PeachSourceFailure(PeachFailureReason.NotFound, Fc2Parser.UNRECOGNISED)
        }

        val page = PeachFetchBridge.fetchOrThrow(
            fetcher = fetcher,
            request = SourcePageRequest(
                url = Fc2PpvDbParser.videoUrl(videoId),
                sourceLabel = ScrapeSource.Fc2PpvDb.label,
                source = ScrapeSource.Fc2PpvDb,
                referer = "${Fc2PpvDbParser.BASE_URL}/"
            ),
            numberMatched = code
        )

        when (val parsed = Fc2PpvDbParser.parse(page.body, code, page.url)) {
            is PeachParseResult.Record ->
                PeachCoverProbe(client, ScrapeSource.Fc2PpvDb).applyTo(parsed.info, parsed.coverUrls)

            is PeachParseResult.Failed -> throw PeachSourceFailure(parsed.reason, parsed.message)
        }
    }
}