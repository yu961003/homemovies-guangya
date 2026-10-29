package com.example.localmovielibrarz.scraper

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * 片商官网这一档在链上的入口：把 [OfficialSiteScraper] 的 `PeachParseResult` 接成
 * 工程里通用的 [MovieScraper] 契约。
 *
 * ## 为什么不直接把 `OfficialSiteScraper` 塞进 registry
 *
 * `MovieScraper` 是**异常驱动**的老契约（失败要抛 [PeachSourceFailure]，成功要交
 * [ScrapedMovieInfo]），而 `OfficialSiteScraper` 按 peach 的口径**返回**结果而不是抛。
 * 两者都合理，混在一处会让「失败到底算不算异常」这种判断散落进 registry 与桃链两处。
 * 所以这里只做一层翻译，不含任何站点知识——改站点改的是目录与抽取器，永远不是这里。
 *
 * ## 为什么三家共用一个类
 *
 * peach 的 `faleno` / `dahlia` / `makers` 在工程里是三个 [ScrapeSource]（要分别记账、
 * 分别冷却、分别出现在设置页里），但它们走的是**同一条**编排：同一个目录、同一个抽取器、
 * 同一套降级。差别只有「这次算哪个来源的账」，所以 `source` 由构造参数决定，
 * 三处在 registry 里各建一个实例即可。
 */
class OfficialMakerScraper(
    private val fetcher: SourcePageFetcher,
    override val source: ScrapeSource,
    private val client: OkHttpClient = OkHttpClient(),
    /** 目录来源。默认内置起始集；主控可传入「内置 + 用户目录」的合并结果。 */
    private val catalog: () -> List<OfficialMakerSite> = { OfficialMakerCatalog.BUILT_IN },
    /** 日志出口。默认丢弃；主控接进应用日志。 */
    private val log: (String) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : MovieScraper {

    init {
        // 这一档只服务片商官网。传进来 Dmm 这类来源会把「片商站没有」记到别人账上，
        // 冷却与健康度就全错了，而且设置页会显示一家永远命中不了的来源。
        require(source == ScrapeSource.Faleno || source == ScrapeSource.Dahlia || source == ScrapeSource.MakerSite) {
            "OfficialMakerScraper 只接片商官网档（Faleno/Dahlia/MakerSite），收到 $source"
        }
    }

    override suspend fun scrape(number: String): ScrapedMovieInfo = withContext(ioDispatcher) {
        val code = ScraperResponseGuard.requireSafeScrapeNumber(number)
        val delegate = OfficialSiteScraper(
            fetcher = fetcher,
            source = source,
            catalog = catalog,
            log = log
        )
        when (val result = delegate.scrape(code)) {
            is PeachParseResult.Failed -> throw PeachSourceFailure(result.reason, result.message)
            is PeachParseResult.Record ->
                // 封面在抽取阶段只拿到**候选**地址，选哪一张要量完尺寸才知道
                // （见 PeachCoverPick）；与 FC2 三家走同一条判定，口径才一致。
                PeachCoverProbe(client, source).applyTo(result.info, result.coverUrls)
        }
    }
}
