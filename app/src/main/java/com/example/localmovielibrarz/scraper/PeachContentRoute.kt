package com.example.localmovielibrarz.scraper

/**
 * peach `metadata_routes.ROUTES` 的字面顺序（名字级），以及把它落到本工程来源上的排序。
 *
 * 为什么要有这张表，而不是继续按「取信层级」排：层级只回答「谁说的更可信」，
 * 不回答「先问谁」。peach 的链是**固定的手写顺序**——无码先问发行方一本道，
 * FC2 先问 FC2 自己，有码先问片商官网再问镜像再问社区；层级排序在这三处都会给出
 * 不同的答案（r18.dev 是镜像档、层级高于社区，但 peach 在有码链上把它排在
 * prestige/faleno/dahlia/makers 之后）。层级决定「同一字段几份值听谁的」，
 * 顺序决定「先花谁的请求」，是两件事。
 *
 * 表里保留 peach 的英文来源名（`PeachSourceCatalog` 用它们做键），
 * 翻译成 [ScrapeSource] 由目录完成——未接的来源在目录里是 `wired=false`，
 * 展开后就自然从链上消失，不需要在这里再维护一份「哪些接了」的清单。
 */
object PeachContentRoute {

    /**
     * peach `ROUTES` 原表。
     *
     * 值的顺序就是链的顺序，一字不改。`kmib` / `other` / `unknown` 是**空链**：
     * peach 对这三类的取舍是「一家都不问」，不是「还没配」。
     */
    val ROUTES: Map<ScrapeContentType, List<String>> = mapOf(
        ScrapeContentType.Censored to listOf(
            "prestige", "faleno", "dahlia", "makers", "r18dev", "dmm", "avbase", "javbus", "javdb"
        ),
        ScrapeContentType.Amateur to listOf(
            "mgstage", "avbase", "javbus", "javdb", "r18dev"
        ),
        ScrapeContentType.Uncensored to listOf(
            "1pondo", "avbase", "javbus", "javdb", "avsox"
        ),
        ScrapeContentType.Fc2 to listOf(
            "fc2", "fc2cmadb", "fc2ppvdb", "javten", "javarchive", "javdb"
        ),
        ScrapeContentType.Kmib to emptyList(),
        ScrapeContentType.Other to emptyList(),
        // peach 的 `unsure` 只问社区三家；本工程把「认不出形状」映射到 Unknown，
        // 那边走的是「社区优先、官方留在后面」的另一套（见 ScrapeRouteRules.routeForPeach）。
        ScrapeContentType.Unknown to listOf("avbase", "javbus", "javdb")
    )

    /** 某一类的 peach 来源名，按链上顺序。 */
    fun routeNamesOf(content: ScrapeContentType): List<String> =
        ROUTES[content].orEmpty()

    /**
     * 把 peach 的名字链展开成可执行的入口来源（含未接步骤的占位）。
     *
     * 同一个 peach 来源名可能展开成多个本工程来源（`dmm` 一步同时含 Dmm 与 Dmm2），
     * 所以返回的是扁平的步骤列表，`executable=false` 的那些就是「peach 有、本工程没接」。
     */
    fun stepsFor(content: ScrapeContentType): List<PeachRouteStep> =
        routeNamesOf(content).flatMap { name -> PeachSourceCatalog.stepsOf(name) }

    /**
     * 按 peach 顺序稳定排序。
     *
     * 两条规则：
     * 1. 出现在该类 peach 链上的来源，按链上的先后排；同一 peach 名字展开出的多个入口
     *    （Dmm 与 Dmm2）保持目录给的内部顺序。
     * 2. 不在链上的来源（用户自配的 CustomJson、或本工程有而 peach 该类链上没有的）
     *    一律排在链后，内部仍按调用方给的顺序——**不丢弃**：用户配了它就是要问它，
     *    只是不该插到 peach 认为更准的来源前面。
     *
     * 空链（kmib/other）时第 1 条不产生任何元素，结果就是原顺序；是否真的该问，
     * 由 [ScrapeRouteRules.routeForPeach] 那层的「裁空即停止」决定，不在这里悄悄改语义。
     */
    fun order(content: ScrapeContentType, configured: List<ScrapeSource>): List<ScrapeSource> {
        val positions = HashMap<ScrapeSource, Int>()
        stepsFor(content).forEachIndexed { index, step ->
            val source = step.source ?: return@forEachIndexed
            // 同一来源在链上只认第一次出现的位置：目录里 dmm 那一步的 extraSources 是
            // 「工程扩展入口」，它不该把这个来源在链上的位置往后挪。
            if (!positions.containsKey(source)) positions[source] = index
        }
        if (positions.isEmpty()) return configured
        val (onRoute, offRoute) = configured.partition { positions.containsKey(it) }
        return onRoute.sortedBy { positions.getValue(it) } + offRoute
    }
}