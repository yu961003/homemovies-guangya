package com.example.localmovielibrarz.scraper

/**
 * 来源的取信层级（借鉴 peach 的 `metadata_policy.CHAIN_*`，按鸭影只有 8 个来源的规模收敛成 5 档）。
 *
 * 与 [`com.example.localmovielibrarz.metadata.MetadataAuthority`] 是**两个不同的轴**，不要合并：
 * - `MetadataAuthority` 管「这条值谁写的」（文件名推断 / 站点 / 本地 NFO / 用户手改），决定
 *   写库时能不能盖掉现有值；
 * - `SourceTier` 管「同一个字段上几份站点值同时到手时听谁的」，决定一次刮削内部的拼装。
 *
 * 之所以要这一档：鸭影原来的 `ScrapeSourceChain.merge` 是「先到的非空值优先」，谁先打到听谁的。
 * 链序本身可以被用户配置成 Javbus 在 TheJavDB 之前，于是同一部片会拿 Javbus 的厂牌/演员
 * 覆盖 TheJavDB 更准的值。peach 在 2026-09-16 逐条核对过这两家（见
 * `PREFERRED_COMMUNITY_SOURCE` / `FALLBACK_SOURCES` 的注释）：javbus 的取值常常来自另一部片，
 * `259LUXU-891` 它答的是 `259LUXU-1891`。顺序不该决定字段归属，层级应该。
 *
 * 这里**只表达层级，不表达顺序**；顺序仍由 [ScrapeRouteRules.routeFor] 与用户配置决定。
 */
enum class SourceTier(val rank: Int, val label: String) {
    /** 发行方自己的页面/目录：DMM 系与各家片商官网。字段最接近一手资料。 */
    Official(1, "官方"),

    /** 逐条核对过、在社区站里最准的一家。鸭影目前只有 TheJavDB 处于这一档。 */
    PreferredCommunity(2, "首选社区"),

    /**
     * 其余社区站与用户自带来源。
     *
     * `CustomJson` 放在这一档而不是官方档：它是用户自己配的解析规则，可能指向任何站点，
     * 鸭影无从判断质量；但它也不该被当成兜底——用户主动配置的来源显然比「明确判过不准」的那家更值得采信。
     */
    Community(3, "社区"),

    /** 兜底：同一字段上有别家可用时，它的取值不当作证据（peach 的 `FALLBACK_SOURCES`）。 */
    Fallback(4, "兜底"),

    /** 归不了档（含 `Priority` 这种链标记）。不构成证据，但也不该让整条链判成无解。 */
    Unknown(5, "未知");

    /** rank 越小越可信。 */
    fun trustedOver(other: SourceTier): Boolean = rank < other.rank
}

object ScrapeAuthority {

    /**
     * 来源到层级的映射。
     *
     * 判定口径：
     * - `Official` / `Dmm` / `Dmm2` / `Mgstage` 都是发行方自己的目录或页面，归官方档。
     *   Mgstage 是 Prestige 系的配信店，素人番号的发行方那一页就在它上面，与 peach 把
     *   amane 桥问到的那批站放在 `official` 一致。
     * - `TheJavDB` 归首选社区：鸭影的这批站点里它是唯一把厂牌/演员/类型都规整好的，
     *   与 peach 选 javdb 当 `PREFERRED_COMMUNITY_SOURCE` 的理由同源。
     * - `Javbus` 归兜底：同样是「常常张冠李戴」的那一家。
     * - `CustomJson` 归社区（理由见 [SourceTier.Community] 的注释）。
     * - `Priority` 是自动链标记不是来源，归 [SourceTier.Unknown]。
     */
    fun tierOf(source: ScrapeSource): SourceTier = when (source) {
        ScrapeSource.Official,
        ScrapeSource.Dmm,
        ScrapeSource.Dmm2,
        ScrapeSource.Mgstage -> SourceTier.Official

        ScrapeSource.TheJavDB -> SourceTier.PreferredCommunity

        // r18.dev 是 DMM 目录的镜像，与 Dmm/Dmm2 同源（字段口径一致、无人工编辑），
        // 所以归同一层而不是社区层：社区站的值常来自另一部片（见 SourceTier.Fallback 的注释），
        // 用它们去覆盖镜像数据是净损失。
        ScrapeSource.R18Dev -> SourceTier.Official

        // 一本道与 FC2 都是发行方自己的作品页（peach 的 `SOURCE_SPECS` 里同为 official），
        // 标题/日期/厂牌就是官方口径；AVBase 是纯社区索引（peach 同样归 community），
        // 但它做了跨店聚合（fanza/mgstage/duga 三店商品号交叉），比 Javbus 规整，归社区层。
        ScrapeSource.OnePondo,
        ScrapeSource.Fc2 -> SourceTier.Official

        // 这一轮补进来的六家，档位照 peach 的 `SOURCE_SPECS.stage`：
        // faleno / dahlia / makers 是片商自己的站（official），
        // fc2cmadb / fc2ppvdb / javten 是 FC2 的社区索引（community）。
        //
        // faleno 与 dahlia 归官方档而不是社区档，尽管它们的取数要靠浏览器：层级回答的是
        // 「这份字段值该不该覆盖别人写的」，与「怎么取到那一页」无关。站点自己的发行日
        // 与厂牌永远是权威值，浏览器只是通道。
        ScrapeSource.Faleno,
        ScrapeSource.Dahlia,
        ScrapeSource.MakerSite -> SourceTier.Official

        ScrapeSource.Fc2Cmadb,
        ScrapeSource.Fc2PpvDb,
        ScrapeSource.Javten -> SourceTier.Community

        ScrapeSource.Avbase -> SourceTier.Community

        ScrapeSource.CustomJson -> SourceTier.Community

        ScrapeSource.Javbus -> SourceTier.Fallback

        ScrapeSource.Priority -> SourceTier.Unknown
    }

    /**
     * 按层级稳定重排：层级高的在前，**同层内保持传入顺序**。
     *
     * 用两趟 filter 而不是 `sortedBy`：`sortedBy` 是稳定排序，但它按 rank 排完之后，
     * 同 rank 的相对次序虽然能保住，调用方却读不出「这一步只是按层分组」的意图；
     * 两趟 filter 让「先出官方档、再出首选社区档……」读起来就是分组本身。
     *
     * 返回值与入参元素集合、长度完全一致——这一层不做裁剪，裁剪在 [ScrapeRouteRules.routeFor]。
     */
    fun orderByTier(configured: List<ScrapeSource>): List<ScrapeSource> {
        val deduped = configured.distinct()
        return SourceTier.entries.flatMap { tier -> deduped.filter { tierOf(it) == tier } }
    }

    /**
     * 同一个字段上有多份非空值时，选哪一份。
     *
     * 返回到达顺序里层级最高的那一份；层级相同则取先到的（与 `merge` 的既有语义一致）。
     * `values` 为空返回 null，由调用方决定回落到什么。
     */
    fun <T> preferByTier(values: List<Pair<ScrapeSource, T>>): Pair<ScrapeSource, T>? {
        if (values.isEmpty()) return null
        var best = values.first()
        for (candidate in values.drop(1)) {
            if (tierOf(candidate.first) == SourceTier.Unknown &&
                tierOf(best.first) != SourceTier.Unknown
            ) {
                // 归不了档的值不构成证据，遇到已知来源就退开。
                continue
            }
            if (tierOf(candidate.first).trustedOver(tierOf(best.first))) {
                best = candidate
            }
        }
        return best
    }

    /**
     * 两份结果按层级合并：每个字段各自挑层级最高的那份非空值。
     *
     * 与 [ScrapeSourceChain.merge] 的区别只有一处，但正是这一处：`merge` 认「先到的」，
     * 这里认「层级高的」。字段级挑选用 [prefer] 明确表达，避免把「合并」写成一大串 ifBlank。
     *
     * 任何一方为 null 时直接返回另一方（不复制，省一次对象分配）。
     */
    fun mergeByAuthority(
        base: Pair<ScrapeSource, ScrapedMovieInfo>?,
        incoming: Pair<ScrapeSource, ScrapedMovieInfo>
    ): Pair<ScrapeSource, ScrapedMovieInfo> {
        if (base == null) return incoming
        val baseInfo = base.second
        val incomingInfo = incoming.second

        fun <T> prefer(baseValue: T, incomingValue: T, isBlank: (T) -> Boolean): T {
            val baseBlank = isBlank(baseValue)
            val incomingBlank = isBlank(incomingValue)
            return when {
                incomingBlank -> baseValue
                baseBlank -> incomingValue
                tierOf(incoming.first).trustedOver(tierOf(base.first)) -> incomingValue
                else -> baseValue
            }
        }

        val blankString: (String) -> Boolean = { it.isBlank() }
        val emptyList: (List<String>) -> Boolean = { it.isEmpty() }
        val emptyMap: (Map<String, String>) -> Boolean = { it.isEmpty() }

        val merged = baseInfo.copy(
            number = prefer(baseInfo.number, incomingInfo.number, blankString),
            title = prefer(baseInfo.title, incomingInfo.title, blankString),
            originalTitle = prefer(baseInfo.originalTitle, incomingInfo.originalTitle, blankString),
            plot = prefer(baseInfo.plot, incomingInfo.plot, blankString),
            outline = prefer(baseInfo.outline, incomingInfo.outline, blankString),
            year = prefer(baseInfo.year, incomingInfo.year, blankString),
            premiered = prefer(baseInfo.premiered, incomingInfo.premiered, blankString),
            runtime = prefer(baseInfo.runtime, incomingInfo.runtime, blankString),
            studio = prefer(baseInfo.studio, incomingInfo.studio, blankString),
            publisher = prefer(baseInfo.publisher, incomingInfo.publisher, blankString),
            series = prefer(baseInfo.series, incomingInfo.series, blankString),
            directors = prefer(baseInfo.directors, incomingInfo.directors, emptyList),
            actors = prefer(baseInfo.actors, incomingInfo.actors, emptyList),
            actorImageUrls = prefer(baseInfo.actorImageUrls, incomingInfo.actorImageUrls, emptyMap),
            genres = prefer(baseInfo.genres, incomingInfo.genres, emptyList),
            tags = prefer(baseInfo.tags, incomingInfo.tags, emptyList),
            rating = prefer(baseInfo.rating, incomingInfo.rating, blankString),
            trailer = prefer(baseInfo.trailer, incomingInfo.trailer, blankString),
            website = prefer(baseInfo.website, incomingInfo.website, blankString),
            thumbUrl = prefer(baseInfo.thumbUrl, incomingInfo.thumbUrl, blankString),
            posterUrl = prefer(baseInfo.posterUrl, incomingInfo.posterUrl, blankString),
            fanartUrl = prefer(baseInfo.fanartUrl, incomingInfo.fanartUrl, blankString),
            thumbImageUrls = prefer(baseInfo.thumbImageUrls, incomingInfo.thumbImageUrls, emptyList),
            posterImageUrls = prefer(baseInfo.posterImageUrls, incomingInfo.posterImageUrls, emptyList)
        )
        // 合并后的「来源」标层级最高的那家：它是这批字段的主要出处，写库时的 `info.source`
        // 也会落成这个名字。不能用 `merged.source`——那是 base 的旧值，层级更高的一家来了也不该被它挡住。
        val winner = if (tierOf(incoming.first).trustedOver(tierOf(base.first))) incoming.first else base.first
        return winner to merged.copy(source = winner.label)
    }
}
