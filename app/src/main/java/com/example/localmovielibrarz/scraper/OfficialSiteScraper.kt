package com.example.localmovielibrarz.scraper

/**
 * 片商官网这一步：**路由 → 取页 → 抽取**，是 peach `amane` 桥在进程内的等价实现。
 *
 * ## 它替代了什么
 *
 * peach 的 `makers` 步骤起一个 Python 子进程（`amane`），由子进程按番号前缀选中一家官网、
 * 取详情页、用该站手写的解析器抽字段，再把结果交回链上。Android 侧没有 Python，子进程走不通，
 * 所以这里把三件事拆成三段可离线回归的 Kotlin：
 *
 * 1. 路由：[OfficialMakerCatalog.route]（声明式目录，数据在，代码不在）；
 * 2. 取页：[SourcePageFetcher]（通道选择、浏览器回落、Cookie 全在实现里）；
 * 3. 归一化：[OfficialPageExtractor]（通用抽取器，三层降级）。
 *
 * 这个类本身只做**编排**：按目录顺序逐站尝试、按 [SourceFetchException.reason] 分档、
 * 取到第一家可用的就走。它不含站点知识，所以站点改版时改的是数据（目录）与抽取器，
 * 不是这里。
 *
 * ## 为什么「前缀不命中就一个请求都不发」
 *
 * peach 的 `metadata_routes.MAKER_EVIDENCE` 就是这么判的：前缀不在表里，这一家根本不知道
 * 你在说哪部片，问了也只会回一页「没有找到」。而每发一次都要计入来源健康度（限流阶梯、
 * 冷却），等于用一次真实请求换一条噪声。所以前缀不命中时直接以
 * [PeachFailureReason.NotFound] 结束，**并在文案里说明这是路由判据而不是站点缺失**——
 * 用户看到「没有任何片商认领 SSIS-001」时会去检查目录前缀，而不是以为官网挂了。
 *
 * 这也是 Android 侧与 peach 唯一的语义差别：peach 链在 makers 失败后还会继续问 r18.dev，
 * 而这里的返回值由链上（[ScrapeRouteRules]）决定要不要往下走——本类只负责「这一环的结论」。
 *
 * ## 为什么单站最多两个请求
 *
 * 搜索页一跳、详情页一跳，没有第三次。重试属于取页层（它知道这一页是 403 还是超时、
 * 该不该上浏览器），编排层再重试只会把「站点被拦」放大成三倍流量，并让冷却时间算不准。
 *
 * ## 线程/协程
 *
 * `scrape` 是 `suspend`，唯一的挂起点是 [fetcher]。它自己不切线程、不并发——一台片最多
 * 两跳、目录里同时认领的站通常只有一两家，串行早退比并发更容易读日志，也不会让两家的
 * 请求同时打在同一个源上（健康度记账是按源的）。
 */
class OfficialSiteScraper(
    private val fetcher: SourcePageFetcher,
    /** 这一次取数属于哪个来源：`Faleno` / `Dahlia` / `MakerSite`（见 [ScrapeAuthority.tierOf]）。 */
    private val source: ScrapeSource,
    /** 目录来源。默认内置起始集；主控可以传入「内置 + 用户目录」的合并结果。 */
    private val catalog: () -> List<OfficialMakerSite> = { OfficialMakerCatalog.BUILT_IN },
    /** 日志出口。默认丢弃；主控接进应用日志，排查「为什么这次没抓到」全靠这几行。 */
    private val log: (String) -> Unit = {}
) {

    /**
     * 抓一页作品的元数据。
     *
     * 返回值只有两种：任一家站抽出可用记录即 [PeachParseResult.Record]（**第一家有值的赢**，
     * 对应 peach 官方档「官网有就直接用，官网没有或出错再问 r18.dev」），否则
     * [PeachParseResult.Failed]，`reason` 取所有尝试里最严重的那一档。
     */
    suspend fun scrape(code: String): PeachParseResult {
        val needle = code.trim()
        if (needle.isEmpty()) {
            // 空番号连前缀都没有，路由必然为空。单独给一句文案：调用方拿到的是「输入没给」，
            // 而不是「目录里没有这家」，两者的处置完全不同（前者不该记来源健康度）。
            return PeachParseResult.Failed(
                PeachFailureReason.NotFound,
                "没有片商目录愿意认领（番号为空）：没有前缀可以匹配任何已配置片商"
            )
        }

        val sites = OfficialMakerCatalog.route(needle, catalog())
        if (sites.isEmpty()) {
            // 这是特性：前缀不在表里就不发请求（见类注释）。文案写清判据，方便用户去补目录。
            return PeachParseResult.Failed(
                PeachFailureReason.NotFound,
                "没有片商目录愿意认领 $needle（前缀不匹配任何已配置片商）"
            )
        }

        val attempts = ArrayList<Attempt>(sites.size)
        for (site in sites) {
            when (val result = trySite(site, needle)) {
                is PeachParseResult.Record -> {
                    log("[${source.label}/${site.label}] 命中：${result.sourceUrl}")
                    return result
                }
                is PeachParseResult.Failed -> {
                    attempts += Attempt(site.label, result.reason, result.message)
                    log("[${source.label}/${site.label}] 未命中：${result.reason.label}（${result.message}）")
                }
            }
        }

        // 全部站点走完仍无记录。取最严重的一档而不是「最后一条」：最后一条往往是最不相关的
        // 那一家的 404，而真正值得用户注意的是「其中一家要求登录」或「被 Cloudflare 拦了」。
        val worst = attempts.minByOrNull { severityOf(it.reason) }?.reason ?: PeachFailureReason.NotFound
        return PeachParseResult.Failed(worst, summarize(needle, attempts))
    }

    // ── 单站编排 ────────────────────────────────────────────────────────────

    /**
     * 试一家站。
     *
     * 顺序固定（契约）：详情页模板非空 → 只取详情页；否则搜索页模板非空 → 先取搜索页、
     * 从结果里抽第一个作品链接、再取详情页；两条都空 → 记一条日志并返回，不抛错
     * （别的站可能认领这部片，一家没配模板不该让整步失败）。
     */
    private suspend fun trySite(site: OfficialMakerSite, code: String): PeachParseResult {
        val detailUrl = OfficialMakerCatalog.detailUrl(site, code)
        if (detailUrl.isNotEmpty()) {
            log("[${source.label}/${site.label}] 详情页模板命中，取 $detailUrl")
            return fetchAndExtract(detailUrl, site, code)
        }

        val searchUrl = OfficialMakerCatalog.searchUrl(site, code)
        if (searchUrl.isNotEmpty()) {
            log("[${source.label}/${site.label}] 没有详情模板，先取搜索页 $searchUrl")
            return searchThenExtract(searchUrl, site, code)
        }

        // 两条模板都是空串。内置目录里 12 家全是这种状态（理由见 OfficialMakerCatalog）：
        // 直接跳过等于让 Faleno/Dahlia/MakerSite 三个来源永远 ParseError。所以这里补一条
        // **有据可查**的通道——回一次首页，读站点自己的搜索表单，拿它的 action 与参数名。
        // 这不是猜路径（见 OfficialSearchForm），发现结果按 site.id 缓存在进程内。
        val discovered = discoverSearchPrefix(site)
        if (discovered.isNotEmpty()) {
            val url = discovered + encode(code)
            log("[${source.label}/${site.label}] 目录没有模板，用首页搜索表单发现的入口取页 $url")
            return searchThenExtract(url, site, code)
        }

        log("[${source.label}/${site.label}] 没有配置取数模板，首页也没有可用的搜索表单，跳过")
        // 用 ParseError 而不是 NotFound：这不是「站上没有这部片」，而是「我们还没有取数入口」。
        // NotFound 会让链上把它当成「官网确实没有」从而安心换源，问题就永远不会被发现。
        return PeachParseResult.Failed(PeachFailureReason.ParseError, "这家片商没有配置取数模板")
    }

    /**
     * 取搜索页 → 从结果里拿第一个作品链接 → 取详情页抽取。
     *
     * 单独抽出来是因为它现在有两个入口：目录里配了搜索模板的站，和「模板为空但首页有搜索框」
     * 的站（见 [discoverSearchPrefix]）。两条入口之后的步骤完全一样，分开放两份必然走偏。
     */
    private suspend fun searchThenExtract(
        searchUrl: String,
        site: OfficialMakerSite,
        code: String
    ): PeachParseResult {
        return when (val page = fetchPage(searchUrl, site)) {
            is PageOutcome.Failed -> failOf(page, searchUrl, "搜索页")
            is PageOutcome.Page -> {
                val workUrl = OfficialPageExtractor.firstWorkUrl(page.body, page.finalUrl, code)
                if (workUrl.isEmpty()) {
                    log("[${source.label}/${site.label}] 搜索页里没有找到 $code 的作品链接")
                    return PeachParseResult.Failed(
                        PeachFailureReason.ParseError,
                        "搜索页里没有找到 $code 的作品链接"
                    )
                }
                log("[${source.label}/${site.label}] 搜索命中作品链接 $workUrl")
                fetchAndExtract(workUrl, site, code)
            }
        }
    }

    /**
     * 首页搜索入口，按 `site.id` 在进程内缓存。
     *
     * 缓存的是**发现结果**（含「这站没有可用表单」这个否定结果），不是页面内容：
     * 站点改版后进程重启或换一部片就会重新发现，不需要等一次发版。否定结果也缓存，
     * 是为了避免「一部片跑一遍首页」——批量刮削时那是每站几百次多余的请求。
     */
    private val searchPrefixCache = HashMap<String, String>()

    private suspend fun discoverSearchPrefix(site: OfficialMakerSite): String {
        searchPrefixCache[site.id]?.let { return it }
        val page = when (val outcome = fetchPage(site.homeUrl, site)) {
            is PageOutcome.Failed -> {
                log("[${source.label}/${site.label}] 首页取不到（${outcome.reason.label}），无法发现搜索入口")
                // 取不到首页不写缓存：这多半是网络或拦截，下一部片可能就好了。
                return ""
            }
            is PageOutcome.Page -> outcome
        }
        val prefix = OfficialSearchForm.discoverSearchPrefix(page.body, page.finalUrl).orEmpty()
        searchPrefixCache[site.id] = prefix
        return prefix
    }

    /** 番号放进查询串：只保留字母数字与连字符，其余百分号编码。 */
    private fun encode(code: String): String = buildString {
        for (ch in code.trim()) {
            if (ch.isLetterOrDigit() || ch == '-') append(ch) else {
                for (byte in ch.toString().toByteArray(Charsets.UTF_8)) {
                    append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
                }
            }
        }
    }

    private val HEX = "0123456789ABCDEF".toCharArray()

    /** 取一页并立刻抽取。取页失败按 [SourceFetchException.reason] 原档返回，不在这里重试。 */
    private suspend fun fetchAndExtract(
        url: String,
        site: OfficialMakerSite,
        code: String
    ): PeachParseResult {
        return when (val page = fetchPage(url, site)) {
            is PageOutcome.Failed -> failOf(page, url, "详情页")
            is PageOutcome.Page -> {
                log(
                    "[${source.label}/${site.label}] 详情页取到（status=${page.status}" +
                        "，viaBrowser=${page.viaBrowser}），开始抽取"
                )
                OfficialPageExtractor.extract(page.body, code, page.finalUrl, source, site)
            }
        }
    }

    /**
     * 取一页。
     *
     * 只接 [SourceFetchException]：它是取页层**声明过的**失败通道（reason 已经是 peach 的那十二档）。
     * 其它异常不接——`suspend` 函数里如果连 `CancellationException` 一起吞掉，取消就永远传不上去，
     * 而本文件不允许 import `kotlinx.coroutines`（纯 JVM 编译集的约束），没法只捕获它的父类。
     * 交给链上统一的异常兜底更安全。
     *
     * 状态码这一层也做一次判定：正常实现会把失败包成异常，但 `StaticSourceFetcher` 这类测试替身
     * 可能直接回 404 的页面。两处都判，才能保证「重定向后的最终地址」与「拦截页」这两条
     * 不依赖实现方自觉。
     */
    private suspend fun fetchPage(url: String, site: OfficialMakerSite): PageOutcome {
        val request = SourcePageRequest(
            url = url,
            sourceLabel = "${source.label}/${site.label}",
            source = source,
            // 片商站对无 Referer 的直连偶发回 403（Referer 校验），用主域根做默认值。
            referer = site.homeUrl
        )
        val response = try {
            fetcher.fetch(request)
        } catch (e: SourceFetchException) {
            return PageOutcome.Failed(e.reason, e.message.orEmpty(), 0, false)
        }

        if (!response.isSuccessful) {
            return PageOutcome.Failed(reasonOfStatus(response.status), "HTTP ${response.status}", response.status, response.viaBrowser)
        }
        if (response.body.isBlank()) {
            return PageOutcome.Failed(PeachFailureReason.ParseError, "响应正文为空", response.status, response.viaBrowser)
        }
        // 200 + 验证页正文：状态码看不出来，只能看正文。判出来就直接记成 CloudflareChallenge，
        // 让链上按「整源冷却」处置，而不是当成一次普通的解析失败。
        if (ChallengePageDetector.isChallengePage(response.body)) {
            return PageOutcome.Failed(
                PeachFailureReason.CloudflareChallenge,
                "回的是站点验证页",
                response.status,
                response.viaBrowser
            )
        }
        // `response.url` 是跟随重定向之后的地址；空串时退回请求地址，保证 website 一定有值。
        return PageOutcome.Page(
            finalUrl = response.url.ifBlank { url },
            body = response.body,
            status = response.status,
            viaBrowser = response.viaBrowser
        )
    }

    private fun failOf(page: PageOutcome.Failed, url: String, step: String): PeachParseResult.Failed {
        log("[${source.label}] $step 取不到 $url：${page.reason.label}（${page.detail}）")
        val detail = if (page.detail.isBlank()) page.reason.label else "${page.reason.label}：${page.detail}"
        return PeachParseResult.Failed(page.reason, "${step}取不到：$detail")
    }

    private fun summarize(code: String, attempts: List<Attempt>): String {
        val parts = attempts.joinToString("；") { attempt ->
            val detail = attempt.detail.ifBlank { attempt.reason.label }
            "${attempt.site}（${attempt.reason.label}：$detail）"
        }
        return "官方站全部未命中 $code：$parts"
    }

    // ── 保底：状态码 → 失败档 ────────────────────────────────────────────────

    /**
     * 状态码映射。只有取页层没抛异常时才会走到这里（测试替身、或某些实现把 4xx 当普通响应）。
     *
     * 与 peach 的口径对齐：429 → 限流；401/403 → 需要登录/被拦；404 → 没有；410 → 下架；
     * 5xx → 服务端故障。其余（含 200 之外的 3xx）按 ParseError——页面形状不对，不是网络问题。
     */
    private fun reasonOfStatus(status: Int): PeachFailureReason = when (status) {
        401, 403 -> PeachFailureReason.AuthRequired
        404 -> PeachFailureReason.NotFound
        410 -> PeachFailureReason.Gone
        429 -> PeachFailureReason.RateLimited
        in 500..599 -> PeachFailureReason.ServerError
        else -> PeachFailureReason.ParseError
    }

    // ── 严重度 ──────────────────────────────────────────────────────────────

    /**
     * 失败档的严重度：数字越小越严重，聚合时取最小。
     *
     * 顺序照契约（`AuthRequired > CloudflareChallenge > RateLimited > ServerError > Timeout >
     * Network > ParseError > NotFound`），只把三个同类补充档并进去：
     * - `IpBanned` / `GeoRestricted` 与 `AuthRequired` 同档——都是「这个出口进不去，
     *   换一家站也没用，得用户改网络或等」；
     * - `Gone` / `NoUsableMetadata` 与 `NotFound` 同档——都是「这一家没有这部片」。
     *
     * 为什么 AuthRequired 比 CloudflareChallenge 更严重：验证页靠浏览器通道大多能过，
     * 而登录墙是**这一趟根本拿不到**，用户需要的提示不同（前者等一会再试，后者要配 Cookie）。
     */
    private fun severityOf(reason: PeachFailureReason): Int = when (reason) {
        PeachFailureReason.AuthRequired,
        PeachFailureReason.IpBanned,
        PeachFailureReason.GeoRestricted -> 0

        PeachFailureReason.CloudflareChallenge -> 1
        PeachFailureReason.RateLimited -> 2
        PeachFailureReason.ServerError -> 3
        PeachFailureReason.Timeout -> 4
        PeachFailureReason.Network -> 5
        PeachFailureReason.ParseError -> 6

        PeachFailureReason.NotFound,
        PeachFailureReason.Gone,
        PeachFailureReason.NoUsableMetadata -> 7
    }

    // ── 内部类型 ────────────────────────────────────────────────────────────

    /** 一次取页的两种结果；`Failed` 已经带上是哪一档，编排层不用再判。 */
    private sealed interface PageOutcome {
        data class Page(val finalUrl: String, val body: String, val status: Int, val viaBrowser: Boolean) : PageOutcome
        data class Failed(
            val reason: PeachFailureReason,
            val detail: String,
            val status: Int,
            val viaBrowser: Boolean
        ) : PageOutcome
    }

    /** 一家站的尝试记录，用于最终失败文案与「取最严重的一档」。 */
    private data class Attempt(val site: String, val reason: PeachFailureReason, val detail: String)

    private companion object {
        /**
         * 单站请求上限：搜索页 1 次 + 详情页 1 次。写在常量里是为了让「为什么只有两跳」
         * 在代码里可见——未来谁想加第三次重试，得先删掉这个注释。
         */
        const val MAX_REQUESTS_PER_SITE = 2
    }
}
