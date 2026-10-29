package com.example.localmovielibrarz.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * JAVten（peach `sources/javten.py`）。解析在 [JavtenParser]。
 *
 * ## 两步取页，且第一步不是商品页
 *
 * JAVten 的 URL 是 `/video/{mediaId}/id{产品号}/{slug}`——`mediaId` 和 `slug` 都不是从番号
 * 能算出来的，所以必先搜一次：
 * 1. `GET /search?kw=<裸数字>` → 从结果卡片里挑出 `id` 段与裸数字**完全相同**的那几条；
 * 2. 挨个取作品页，第一个解析成功的就用它。
 *
 * 第 2 步为什么要循环：搜索页会同时给出「本篇」「合集」「同人」等若干条，编号相同并不代表
 * 是同一条作品（`workLinks` 已按 `id` 严格比对，但站上仍可能有一条是合集）。循环上限是
 * [JavtenParser.workLinks] 返回的条数，通常就是 1~3 条，不会失控。
 *
 * ## 与 FC2PPV-DB 相同的两点
 *
 * - 上来就走浏览器（peach `browser: True`），通道由 [SourcePageFetcher] 决定；
 * - 需要 `finalUrl` 判译文页（`/tw/` `/en/` `/ko/` 前缀）。
 */
class JavtenScraper(
    private val fetcher: SourcePageFetcher,
    private val client: OkHttpClient = OkHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MovieScraper {

    override val source: ScrapeSource = ScrapeSource.Javten

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val code = ScraperResponseGuard.requireSafeScrapeNumber(number)
        val videoId = Fc2Parser.videoId(code)
        if (videoId.isEmpty()) {
            throw PeachSourceFailure(PeachFailureReason.NotFound, Fc2Parser.UNRECOGNISED)
        }
        val searchUrl = JavtenParser.searchUrl(code)
        if (searchUrl.isEmpty()) {
            throw PeachSourceFailure(PeachFailureReason.NotFound, Fc2Parser.UNRECOGNISED)
        }

        val searchPage = PeachFetchBridge.fetchOrThrow(
            fetcher = fetcher,
            request = SourcePageRequest(
                url = searchUrl,
                sourceLabel = ScrapeSource.Javten.label,
                source = ScrapeSource.Javten,
                referer = "${JavtenParser.BASE_URL}/"
            ),
            numberMatched = code
        )
        val links = JavtenParser.workLinks(searchPage.body, code)
        if (links.isEmpty()) {
            throw PeachSourceFailure(PeachFailureReason.NotFound, "JAVten 上没有这个商品")
        }

        // 一条失败就换下一条：合集那条常常解析不出单体字段，而本篇那条往往就在后面。
        var lastFailure: PeachSourceFailure? = null
        for (link in links) {
            val page = try {
                PeachFetchBridge.fetchOrThrow(
                    fetcher = fetcher,
                    request = SourcePageRequest(
                        url = link,
                        sourceLabel = ScrapeSource.Javten.label,
                        source = ScrapeSource.Javten,
                        referer = searchUrl
                    ),
                    numberMatched = code
                )
            } catch (error: PeachSourceFailure) {
                // 整站被拦时继续试下一条只是多撞几次，立刻收手交给链上冷却。
                if (error.reason == PeachFailureReason.CloudflareChallenge) throw error
                lastFailure = error
                continue
            }
            when (val parsed = JavtenParser.parse(page.body, code, page.url)) {
                is PeachParseResult.Record ->
                    return@withContext PeachCoverProbe(client, ScrapeSource.Javten)
                        .applyTo(parsed.info, parsed.coverUrls)

                is PeachParseResult.Failed -> lastFailure =
                    PeachSourceFailure(parsed.reason, parsed.message)
            }
        }
        throw lastFailure ?: PeachSourceFailure(PeachFailureReason.NotFound, "JAVten 上没有这个商品")
    }
}