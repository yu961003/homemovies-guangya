package com.example.localmovielibrarz.scraper

/**
 * peach 的来源登记表。
 *
 * 为什么要把「peach 里有哪几家」和「本工程接了哪几家」分成两列：路由表 [PeachContentRoute]
 * 是按 peach 的来源名写的（`avbase` / `1pondo` / `fc2` …），如果直接把它翻译成本工程的
 * [ScrapeSource]，那些**本工程根本没实现**的来源（javten、javarchive、fc2cmadb、javsox…）
 * 就只能悄悄从链上删掉。删掉之后有两个后果：一是链的顺序看起来对了，实际每一步都被跳过，
 * 排查时无从判断是「没接」还是「接了但失败」；二是将来补一家源，没人知道它原本在第几顺位。
 *
 * 所以这里保留 peach 的完整名单与顺位，只把「有没有本工程的实现」标成可空的一列。
 * 链执行时遇到空实现会记一条 `skipped` 而不是 `failed`，用户与日志都能区分这两件事。
 */
enum class PeachStage(val label: String) {
    /** 发行方自己的目录/作品页。 */
    Official("官方"),

    /** 社区索引站。 */
    Community("社区")
}

/**
 * 一家 peach 来源在本工程的落地情况。
 *
 * @param name peach `sources/` 里的来源名，路由表用的就是它，**不要翻译成展示名**。
 * @param label 展示名（日志与设置页用）。
 * @param sources 本工程里对应的来源实现；空列表表示没接（见 [unwiredReason]）。
 * @param extraSources 同一家 peach 来源在本工程的多个入口（只有 DMM 一家有：`Dmm` 与 `Dmm2`）。
 */
data class PeachSourceProfile(
    val name: String,
    val label: String,
    val stage: PeachStage,
    val sources: List<ScrapeSource>,
    val pageLimitBytes: Long,
    val blockedPauseMillis: Long,
    val minIntervalMillis: Long,
    val unwiredReason: String = "",
    val extraSources: List<ScrapeSource> = emptyList()
) {
    /** 这一家在本工程有没有可执行的实现。 */
    val wired: Boolean get() = sources.isNotEmpty()

    /** 实际要按顺序试的入口：先主入口，再工程扩展入口。 */
    fun entryPoints(): List<ScrapeSource> = sources + extraSources
}

/**
 * 路由上的一步。
 *
 * 与 [PeachSourceProfile] 分开的原因：同一家 peach 来源在工程里可能展开成两个入口（DMM → Dmm、Dmm2），
 * 而日志需要记「这一步是 peach 的哪一家、用的本工程哪个源」，两条信息分别落在这两个字段上。
 */
data class PeachRouteStep(
    val profile: PeachSourceProfile,
    val source: ScrapeSource?,
    /** 这一步为什么被摘掉/跳过；能执行时为空串。 */
    val note: String = ""
) {
    val executable: Boolean get() = source != null
}

object PeachSourceCatalog {

    /** peach `sources/base.py:DEFAULT_PAGE_LIMIT`：4 MiB。 */
    const val DEFAULT_PAGE_LIMIT_BYTES = 4L * 1024 * 1024

    /** peach `sources/base.py:DEFAULT_INTERVAL`：2 秒。 */
    const val DEFAULT_INTERVAL_MILLIS = 2_000L

    /** peach `library_processing.SOURCE_INTERVALS`：javdb 按出口 IP 计配额，3 秒。 */
    const val JAVDB_INTERVAL_MILLIS = 3_000L

    /** peach `scraping_access.FIRST_BLOCKED_PAUSE`：首停 900 秒，之后翻倍。 */
    const val FIRST_BLOCKED_PAUSE_MILLIS = 900_000L

    /** 没登记 `blocked_pause` 的来源按 24 小时算（`scraping_access.py:168`）。 */
    const val DEFAULT_BLOCKED_PAUSE_MILLIS = 24L * 60 * 60 * 1000

    private fun profile(
        name: String,
        label: String,
        stage: PeachStage,
        sources: List<ScrapeSource> = emptyList(),
        pageLimitBytes: Long = DEFAULT_PAGE_LIMIT_BYTES,
        blockedPauseMillis: Long = DEFAULT_BLOCKED_PAUSE_MILLIS,
        minIntervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
        unwiredReason: String = "",
        extraSources: List<ScrapeSource> = emptyList()
    ) = PeachSourceProfile(
        name = name,
        label = label,
        stage = stage,
        sources = sources,
        pageLimitBytes = pageLimitBytes,
        blockedPauseMillis = blockedPauseMillis,
        minIntervalMillis = minIntervalMillis,
        unwiredReason = unwiredReason,
        extraSources = extraSources
    )

    /**
     * 全表。
     *
     * 顺序与 peach `metadata_routes.ROUTES` 的串接顺序一致，但**链的执行顺序不看这张表**，
     * 看的是 [PeachContentRoute.routeNamesOf]——这里只登记属性，不参与排序。
     */
    val ALL: List<PeachSourceProfile> = listOf(
        // ── 片商官网桥（peach 走 amane 子进程，本项目没有等价通道） ─────────────────
        profile(
            name = "prestige",
            label = "Prestige 官网",
            stage = PeachStage.Official,
            // 本工程的 OfficialScraper 就是「片商官网前缀白名单」，prestige-av.com 在名单里
            // （`ABP`/`ABF`/`ABS` 等前缀），所以这一家能落到既有实现上。
            sources = listOf(ScrapeSource.Official)
        ),
        profile(
            name = "faleno",
            label = "FALENO 官网",
            stage = PeachStage.Official,
            // 官网对非日本出口直接 403，靠浏览器通道取页（`BrowserFallbackPolicy` 的 OnRefusal）。
            sources = listOf(ScrapeSource.Faleno)
        ),
        profile(
            name = "dahlia",
            label = "DAHLIA 官网",
            stage = PeachStage.Official,
            // WordPress 站，页面上没有结构化字段；解析器按「标题 / 发行日 / 厂牌 / 类型」
            // 逐个从正文与 meta 里取，取不到就留空交给下一家补。
            sources = listOf(ScrapeSource.Dahlia)
        ),
        profile(
            name = "makers",
            label = "片商官网通用桥",
            stage = PeachStage.Official,
            // peach 用 amane 子进程把番号映射到具体片商官网；本工程没有子进程，
            // 等价能力是【进程内站点路由表 + 通用页面解析】（见 `AmaneSiteDirectory`）。
            sources = listOf(ScrapeSource.MakerSite)
        ),

        // ── 有码/素人/无码的官方与镜像 ───────────────────────────────────────────
        profile(
            name = "r18dev",
            label = "r18.dev",
            stage = PeachStage.Official,
            sources = listOf(ScrapeSource.R18Dev),
            pageLimitBytes = 2L * 1024 * 1024
        ),
        profile(
            name = "dmm",
            label = "DMM",
            stage = PeachStage.Official,
            sources = listOf(ScrapeSource.Dmm),
            // Dmm2 是本工程对同一目录的第二个入口（GraphQL），peach 没有对应实现；
            // 放在 dmm 这一步里而不是链尾，是为了让「DMM 系」在链上仍然是一段连续的顺位。
            extraSources = listOf(ScrapeSource.Dmm2),
            pageLimitBytes = 1L * 1024 * 1024
        ),
        profile(
            name = "mgstage",
            label = "MGStage",
            stage = PeachStage.Official,
            sources = listOf(ScrapeSource.Mgstage)
        ),
        profile(
            name = "1pondo",
            label = "一本道",
            stage = PeachStage.Official,
            sources = listOf(ScrapeSource.OnePondo),
            pageLimitBytes = 1L * 1024 * 1024
        ),
        profile(
            name = "fc2",
            label = "FC2",
            stage = PeachStage.Official,
            sources = listOf(ScrapeSource.Fc2),
            pageLimitBytes = 2L * 1024 * 1024
        ),

        // ── FC2 系社区索引 ──────────────────────────────────────────────────────
        profile(
            name = "fc2cmadb",
            label = "FC2-CMADB",
            stage = PeachStage.Community,
            // 不需要浏览器：页面是 Inertia，商品数据就在 `<script type="application/json">`
            // 的 `props.article` 里；女优要按 Inertia 的 partial-data 协议补问一次。
            sources = listOf(ScrapeSource.Fc2Cmadb)
        ),
        profile(
            name = "fc2ppvdb",
            label = "FC2PPVDB",
            stage = PeachStage.Community,
            sources = listOf(ScrapeSource.Fc2PpvDb),
            // peach：`blocked_pause = 6 * 3600`。
            blockedPauseMillis = 6L * 60 * 60 * 1000
        ),
        profile(
            name = "javten",
            label = "JavTen",
            stage = PeachStage.Community,
            sources = listOf(ScrapeSource.Javten),
            // peach：`blocked_pause = 6 * 3600`。
            blockedPauseMillis = 6L * 60 * 60 * 1000
        ),
        profile(
            name = "javarchive",
            label = "JavArchive",
            stage = PeachStage.Community,
            unwiredReason = "站点数据来自旧的 JavBus 抓取快照，字段口径与本工程 Javbus 实现重叠，收益低于维护成本"
        ),

        // ── 社区索引 ────────────────────────────────────────────────────────────
        profile(
            name = "avbase",
            label = "AVBase",
            stage = PeachStage.Community,
            sources = listOf(ScrapeSource.Avbase),
            // peach 给 avbase 显式写了 `blocked_pause: 6h`。
            blockedPauseMillis = 6L * 60 * 60 * 1000
        ),
        profile(
            name = "javbus",
            label = "JavBus",
            stage = PeachStage.Community,
            sources = listOf(ScrapeSource.Javbus),
            blockedPauseMillis = 6L * 60 * 60 * 1000
        ),
        profile(
            name = "javdb",
            label = "JavDB",
            stage = PeachStage.Community,
            sources = listOf(ScrapeSource.TheJavDB),
            minIntervalMillis = JAVDB_INTERVAL_MILLIS
        ),

        // ── 无码的社区索引（本工程未接） ─────────────────────────────────────────
        profile(
            name = "avsox",
            label = "AVSOX",
            stage = PeachStage.Community,
            unwiredReason = "peach `sources/` 里没有独立实现（路由表里出现但无 `avsox.py`），" +
                "只在 amane 桥的来源集合里被引用；本项目既无桥也无独立实现"
        ),
        profile(
            name = "kmib",
            label = "韩国 MIB",
            stage = PeachStage.Community,
            unwiredReason = "peach 自己对 kmib 就是空链（`ROUTES['kmib'] = ()`），故意不问任何站；本工程沿用这条取舍"
        )
    )

    private val byName: Map<String, PeachSourceProfile> = ALL.associateBy { it.name }

    fun of(name: String): PeachSourceProfile? = byName[name]

    /** 路由表里登记过的全部来源名（含未接的），用于自检与日志。 */
    val names: List<String> get() = ALL.map { it.name }

    /** 把一个 peach 来源名展开成本工程可执行的步骤；未接的来源给出一条 `executable=false` 的步骤。 */
    fun stepsOf(name: String): List<PeachRouteStep> {
        val profile = byName[name]
            ?: return listOf(
                PeachRouteStep(
                    profile = profile("$name", name, PeachStage.Community, unwiredReason = "来源名未登记"),
                    source = null,
                    note = "来源名未登记"
                )
            )
        if (!profile.wired) {
            return listOf(PeachRouteStep(profile = profile, source = null, note = profile.unwiredReason))
        }
        return profile.entryPoints().mapIndexed { index, source ->
            PeachRouteStep(
                profile = profile,
                source = source,
                note = if (index == 0) "" else "工程扩展入口（peach 无同名实现）"
            )
        }
    }

    /**
     * 该来源被拦后的冷却上限，与 [ScrapeCooldownPolicy.blockedPauseLimitMillis] 同源。
     *
     * 这里再给一份是因为 peach 链要在**不持有 [ScrapeSource]**（未接的来源）时也能回答
     * 「这家撞验证页要停多久」，而既有实现是按 [ScrapeSource] 查表的。
     */
    fun blockedPauseMillisOf(name: String): Long = byName[name]?.blockedPauseMillis ?: DEFAULT_BLOCKED_PAUSE_MILLIS

    /** 该来源两次请求的最小间隔。 */
    fun minIntervalMillisOf(name: String): Long = byName[name]?.minIntervalMillis ?: DEFAULT_INTERVAL_MILLIS
}
