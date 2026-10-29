package com.example.localmovielibrarz.scraper

/**
 * 逐字段判胜（peach 的 `field_owners.py` + `metadata_policy.py` 的鸭影版）。
 *
 * 为什么需要它，而 [ScrapeAuthority.mergeByAuthority] 不够：那一套是「按来源层级整份胜出」——
 * 同一份结果里 title 来自 A、actors 来自 B 这种情形表达不出来，只能整份挑一份。peach 的做法正相反：
 * 每个字段各自有一张归属表（谁最可信），一份结果里 title 可以来自官网、performers 来自 FC2 索引，
 * 互不干扰。用户要的「按字段跨源合并」就是这条，所以单独成一层，而不是继续往 merge 里加 if。
 *
 * 判胜顺序（从强到弱，与 peach 的 `sort_candidates` 一致）：
 * 1. **黑名单**：`FIELD_SOURCE_BLACKLIST` 里的来源在这个字段上连候选都不算——这是「已确认它会答错
 *    这个字段」的硬结论，比任何层级都硬。
 * 2. **字段专用优先表**：`FIELD_SOURCE_PRIORITY` 里点名的那几家，按表内顺序排在所有来源之前。
 *    peach 只为 performers 建了这张表（fc2cmadb / fc2ppvdb 的演员表比目录站准），这里原样保留。
 * 3. **来源层级**：官方 > 社区偏好（javdb）> 社区 > 兜底（javbus）> 未知，来自 [ScrapeAuthority.tierOf]。
 * 4. **到达顺序**：全一样时先到的胜（与 `merge` 的既有语义一致，不引入第三套顺序）。
 *
 * 纯函数、不碰网络与 Android：链上的每一份结果都过这里挑一次，单测可以逐字段固化行为。
 */
object PeachFieldMerge {

    /**
     * peach 的 `metadata_policy.FIELD_SOURCE_PRIORITY`。
     *
     * 键是字段名（用 [ScrapeField] 而不是字符串，避免拼错时静默失效），值是「在这个字段上
     * 应当排到最前的来源，按此顺序」。表里没有列出的字段走层级判胜。
     */
    val FIELD_SOURCE_PRIORITY: Map<ScrapeField, List<ScrapeSource>> = mapOf(
        // peach 原表只有 performers 一项：FC2 系的演员名来自作品页的「出演者」字段，
        // 而目录站（javdb/javbus）对 FC2 号往往把卖家名当演员写，口径更差。
        ScrapeField.Actors to listOf(ScrapeSource.Fc2),
        // 这一条是 peach 没有、但鸭影这边实测成立的同类情形：一本道/FC2 的发行日就是作品页上的
        // 「配信日/販売日」，而目录站对这两类号的日期常年缺一半、或者写成上架日。
        ScrapeField.Premiered to listOf(ScrapeSource.Fc2, ScrapeSource.OnePondo)
    )

    /**
     * peach 的 `metadata_policy.FIELD_SOURCE_BLACKLIST`。
     *
     * peach 出厂是空表（它只用这张表记录「逐条校过的反例」）。这里也保持空表，但把机制做出来：
     * 空表是**数据**，不是缺失的实现——将来发现某家在某个字段上稳定答错，往这里加一条即可，
     * 不必改动判胜逻辑。
     */
    val FIELD_SOURCE_BLACKLIST: Map<ScrapeField, Set<ScrapeSource>> = emptyMap()

    /** 一次字段候选：值 + 写它的来源。 */
    data class Candidate<T>(val source: ScrapeSource, val value: T)

    /**
     * 在一组候选里挑胜者；全都不可用时返回 null。
     *
     * `isUsable` 由调用方给（字符串用 `isNotBlank`、列表用 `isNotEmpty`）——这层不该猜
     * 「空串算不算没有」，那是各字段自己的口径。
     */
    fun <T> pick(
        field: ScrapeField,
        candidates: List<Candidate<T>>,
        isUsable: (T) -> Boolean
    ): Candidate<T>? {
        val blacklist = FIELD_SOURCE_BLACKLIST[field].orEmpty()
        val usable = candidates.filter { it.source !in blacklist && isUsable(it.value) }
        if (usable.isEmpty()) return null
        val priority = FIELD_SOURCE_PRIORITY[field].orEmpty()
        return usable.minWithOrNull(
            compareBy(
                // ① 字段专用优先表：表内越靠前越小；不在表内的一律排到表后。
                { candidate: Candidate<T> ->
                    val index = priority.indexOf(candidate.source)
                    if (index >= 0) index else priority.size
                },
                // ② 来源层级：层级高的排前面。`rank` 越小越可信（Official=1 … Fallback=4），
                //    所以这里按 rank 升序直接比——写成 `-rank` 会让兜底档（4）排到官方档（1）前面，
                //    正好把 peach「同一字段上有别家可用时兜底档不算证据」的取舍反过来。
                { candidate: Candidate<T> -> ScrapeAuthority.tierOf(candidate.source).rank },
                // ③ 到达顺序：稳定排序，先到的胜。
                { candidate: Candidate<T> -> candidates.indexOfFirst { it === candidate } }
            )
        )
    }

    /**
     * 逐字段合并两份结果。
     *
     * `base` 为 null 时直接返回 `incoming`（不复制，省一次分配）；两份都在时，**每个字段**都
     * 走一次 [pick]，而不是整份挑一份。`number` 永远保留 base 的——链尾会把它改写成查询番号，
     * 中途让它被别的来源改写会让后续番号一致性判断读到脏值。
     */
    fun merge(
        base: Pair<ScrapeSource, ScrapedMovieInfo>?,
        incoming: Pair<ScrapeSource, ScrapedMovieInfo>
    ): Pair<ScrapeSource, ScrapedMovieInfo> {
        if (base == null) return incoming
        val (baseSource, baseInfo) = base
        val (incomingSource, incomingInfo) = incoming
        val both = listOf(baseSource, incomingSource)

        fun <T> decide(field: ScrapeField, baseValue: T, incomingValue: T, usable: (T) -> Boolean): T =
            pick(
                field = field,
                candidates = listOf(
                    Candidate(baseSource, baseValue),
                    Candidate(incomingSource, incomingValue)
                ),
                isUsable = usable
            )?.value ?: baseValue

        val blankString: (String) -> Boolean = { it.isNotBlank() }
        val nonEmptyList: (List<String>) -> Boolean = { it.isNotEmpty() }
        val nonEmptyMap: (Map<String, String>) -> Boolean = { it.isNotEmpty() }

        val merged = baseInfo.copy(
            title = decide(ScrapeField.Title, baseInfo.title, incomingInfo.title, blankString),
            originalTitle = decide(
                ScrapeField.OriginalTitle, baseInfo.originalTitle, incomingInfo.originalTitle, blankString
            ),
            plot = decide(ScrapeField.Plot, baseInfo.plot, incomingInfo.plot, blankString),
            outline = decide(ScrapeField.Outline, baseInfo.outline, incomingInfo.outline, blankString),
            year = decide(ScrapeField.Year, baseInfo.year, incomingInfo.year, blankString),
            premiered = decide(ScrapeField.Premiered, baseInfo.premiered, incomingInfo.premiered, blankString),
            runtime = decide(ScrapeField.Runtime, baseInfo.runtime, incomingInfo.runtime, blankString),
            studio = decide(ScrapeField.Studio, baseInfo.studio, incomingInfo.studio, blankString),
            publisher = decide(ScrapeField.Publisher, baseInfo.publisher, incomingInfo.publisher, blankString),
            series = decide(ScrapeField.Series, baseInfo.series, incomingInfo.series, blankString),
            directors = decide(ScrapeField.Directors, baseInfo.directors, incomingInfo.directors, nonEmptyList),
            actors = decide(ScrapeField.Actors, baseInfo.actors, incomingInfo.actors, nonEmptyList),
            genres = decide(ScrapeField.Genres, baseInfo.genres, incomingInfo.genres, nonEmptyList),
            tags = decide(ScrapeField.Tags, baseInfo.tags, incomingInfo.tags, nonEmptyList),
            rating = decide(ScrapeField.Rating, baseInfo.rating, incomingInfo.rating, blankString),
            trailer = decide(ScrapeField.Trailer, baseInfo.trailer, incomingInfo.trailer, blankString),
            website = decide(ScrapeField.Website, baseInfo.website, incomingInfo.website, blankString),
            thumbUrl = decide(ScrapeField.ThumbUrl, baseInfo.thumbUrl, incomingInfo.thumbUrl, blankString),
            posterUrl = decide(ScrapeField.PosterUrl, baseInfo.posterUrl, incomingInfo.posterUrl, blankString),
            fanartUrl = decide(ScrapeField.FanartUrl, baseInfo.fanartUrl, incomingInfo.fanartUrl, blankString),
            actorImageUrls = decide(
                ScrapeField.ActorImageUrls, baseInfo.actorImageUrls, incomingInfo.actorImageUrls, nonEmptyMap
            ),
            thumbImageUrls = decide(
                ScrapeField.ThumbImageUrls, baseInfo.thumbImageUrls, incomingInfo.thumbImageUrls, nonEmptyList
            ),
            posterImageUrls = decide(
                ScrapeField.PosterImageUrls, baseInfo.posterImageUrls, incomingInfo.posterImageUrls, nonEmptyList
            )
        )

        // 结果的「主来源」标层级最高的那家，与 mergeByAuthority 同口径：它决定写库时的 info.source。
        val mainSource = if (ScrapeAuthority.tierOf(incomingSource).trustedOver(ScrapeAuthority.tierOf(baseSource))) {
            incomingSource
        } else {
            baseSource
        }
        // both 只用于在调试时确认「这一份结果确实只由这两家拼起来」，不参与判胜。
        @Suppress("UNUSED_VARIABLE")
        val contributors = both
        return mainSource to merged.copy(source = mainSource.label)
    }
}

/** 可判胜的字段名，与 [ScrapedMovieInfo] 的字段一一对应（`number` 除外，它不属于任何来源）。 */
enum class ScrapeField(val label: String) {
    Title("标题"),
    OriginalTitle("原始标题"),
    Plot("简介"),
    Outline("大纲"),
    Year("年份"),
    Premiered("发行日"),
    Runtime("时长"),
    Studio("厂牌"),
    Publisher("发行方"),
    Series("系列"),
    Directors("导演"),
    Actors("演员"),
    Genres("类别"),
    Tags("标签"),
    Rating("评分"),
    Trailer("预告"),
    Website("来源页"),
    ThumbUrl("封面"),
    PosterUrl("海报"),
    FanartUrl("剧照"),
    ActorImageUrls("演员头像"),
    ThumbImageUrls("封面候选"),
    PosterImageUrls("海报候选")
}
