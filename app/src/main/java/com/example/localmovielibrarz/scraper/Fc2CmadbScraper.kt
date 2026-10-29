package com.example.localmovielibrarz.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * FC2CMADB（peach `sources/fc2cmadb.py`）。解析在 [Fc2CmadbParser]。
 *
 * ## 两次取页
 *
 * 商品页本身**不含女优**：那一栏由 Inertia 的 partial reload 单独提供。所以这一家是
 * 本工程里少见的「一次刮削取两页」的来源，顺序固定：
 * 1. 取商品页 → 解析出标题/日期/时长/标签/封面（女优字段此刻是空列表）；
 * 2. 若第 1 步成功**且** `partialHeaders` 给出了请求头，再问一次要 `actresses`。
 *
 * 第 2 步失败不推翻第 1 步：女优是加分项，为它把整条结果判失败会让一部片在别的站
 * 明明查得到却因为「女优没问到」而被记成失败。所以这里把第 2 步包成「尽力而为」。
 *
 * ## 为什么不直接走 `client.newCall`
 *
 * 这一家对非日本出口会直接 403，需要浏览器回落通道。通道选择是 [SourcePageFetcher]
 * 的职责（见 `SourceFetch.kt` 的说明），本类只提供 URL 与请求头。
 */
class Fc2CmadbScraper(
    private val fetcher: SourcePageFetcher,
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MovieScraper {

    override val source: ScrapeSource = ScrapeSource.Fc2Cmadb

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val code = ScraperResponseGuard.requireSafeScrapeNumber(number)
        val videoId = Fc2Parser.videoId(code)
        if (videoId.isEmpty()) {
            throw PeachSourceFailure(PeachFailureReason.NotFound, Fc2Parser.UNRECOGNISED)
        }

        val page = PeachFetchBridge.fetchOrThrow(
            fetcher = fetcher,
            request = SourcePageRequest(
                url = Fc2CmadbParser.articleUrl(videoId),
                sourceLabel = ScrapeSource.Fc2Cmadb.label,
                source = ScrapeSource.Fc2Cmadb,
                referer = "${Fc2CmadbParser.BASE_URL}/"
            ),
            numberMatched = code
        )

        val parsed = Fc2CmadbParser.parse(page.body, code)
        if (parsed is PeachParseResult.Failed) {
            throw PeachSourceFailure(parsed.reason, parsed.message)
        }
        val record = parsed as PeachParseResult.Record
        val withActors = attachActresses(record.info, page.body, videoId)
        PeachCoverProbe(client, ScrapeSource.Fc2Cmadb).applyTo(withActors, record.coverUrls)
    }

    /**
     * 补问女优。任何失败都只是「这一次没有女优」，不影响已解析出的主字段。
     *
     * 女优页要的是**同一篇文章**的 partial 视图，所以 URL 与商品页相同、只换请求头——
     * Inertia 靠 `X-Inertia` 头决定回整页还是回片段，地址本身不变。
     */
    private suspend fun attachActresses(
        info: ScrapedMovieInfo,
        html: String,
        videoId: String
    ): ScrapedMovieInfo {
        val headers = Fc2CmadbParser.partialHeaders(html)
        if (headers.isEmpty()) return info
        val payload = runCatching {
            fetcher.fetch(
                SourcePageRequest(
                    url = Fc2CmadbParser.articleUrl(videoId),
                    sourceLabel = ScrapeSource.Fc2Cmadb.label,
                    source = ScrapeSource.Fc2Cmadb,
                    referer = "${Fc2CmadbParser.BASE_URL}/",
                    extraHeaders = headers
                )
            ).body
        }.getOrNull() ?: return info
        val actors = Fc2CmadbParser.parseActresses(payload)
        if (actors.isEmpty()) return info
        return info.copy(
            actors = actors,
            actorImageUrls = info.actorImageUrls
        )
    }
}