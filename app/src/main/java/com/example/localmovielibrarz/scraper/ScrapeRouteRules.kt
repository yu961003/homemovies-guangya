package com.example.localmovielibrarz.scraper

/**
 * 内容类型：只按番号/文件名形态划分，**不看抓取结果**。
 *
 * 如果拿抓取结果去反推类型，就变成「先问完所有站才知道该先问谁」，路由本身失去意义。
 * [Unknown] 不是错误：用户自定义编号、或者文件名里没有番号时，就按配置顺序走。
 */
enum class ScrapeContentType(val label: String) {
    /** 有码（DMM/FANZA 体系能查到的标准番号）。 */
    Censored("有码"),

    /** 素人（SIRO / STP / STN 等 MGStage 体系）。 */
    Amateur("素人"),

    /** 无码（1pondo / caribbean 等，DMM 不收录）。 */
    Uncensored("无码"),

    /** FC2-PPV。 */
    Fc2("FC2"),

    /** 韩国 MIB。 */
    Kmib("韩国 MIB"),

    /** 国产/其它。 */
    Other("国产/其它"),

    /** 识别不出来：不做路由，交回配置顺序。 */
    Unknown("未识别"),
}

/**
 * 按番号形态决定先问哪几家站。
 *
 * 为什么要按类型分流：FC2 的番号在 DMM 上根本不存在，排在链首只会先吃一次「搜不到」；
 * 素人/无码在 DMM 的收录情况也与有码不同。但**不能**在分类失败时直接不查：
 * 鸭影来源少、用户还可能配了自定义 JSON，退回配置顺序比直接放弃更有用。
 *
 * 两套 API 并存，语义不同，不要混用：
 * - [orderFor] 只调整顺序，不增删来源；用户配置了哪些来源，最终就只查哪些。
 * - [routeFor] 按类型裁剪 + 按取信层级重排，返回 [RoutePlan]（含被裁掉的来源与回退原因）。
 *   自动链用这一套；用户点名某个来源时两套都不走。
 */
object ScrapeRouteRules {

    /**
     * 一次路由的完整结果。
     *
     * [skipped] 非空时调用方要把「为什么没问这几家」写进日志——用户看到来源没被尝试，
     * 唯一能解释的地方就是这条日志。
     */
    data class RoutePlan(
        val sources: List<ScrapeSource>,
        val skipped: List<ScrapeSource>,
        val fallbackReason: String?,
    )

    /**
     * FC2 的两种常见写法：
     * - `FC2-PPV-1234567`（分隔符也允许 `_`/空格）
     * - `FC2-123456`（纯数字，至少 6 位）
     */
    private val FC2_PPV = Regex("""FC2[\s_-]*PPV[\s_-]*\d+""", RegexOption.IGNORE_CASE)
    private val FC2_NUMBER = Regex("""FC2[\s_-]*\d{6,}""", RegexOption.IGNORE_CASE)

    /** 素人厂牌：判定前要先去掉番号的前导数字（`300SIRO-001` 也要能认出来）。 */
    private val AMATEUR_PREFIXES = setOf("SIRO", "STP", "STN", "ARA", "LUXU", "MAAN", "GANA")

    /** 无码厂牌：这些前缀本身带数字（`1PONDO`/`10MU`），不能去前导数字。 */
    private val UNCENSORED_PREFIXES = setOf(
        "1PONDO", "CARIB", "TOKYOHOT", "10MU", "HEYDOUGA",
        "PACOPACOMAMA", "MURAMURA", "GACHINCO",
    )

    /** 有码番号主体：字母段 + 连字符 + 数字段；前导数字在匹配前剥掉。 */
    private val CENSORED_SHAPE = Regex("""^[A-Z]{2,6}-\d{2,5}$""")

    private val LEADING_DIGITS = Regex("""^\d+""")

    /**
     * 国产标记。
     *
     * `MD`/`MT`/`91` 用边界匹配而不是裸 contains：裸 contains 会把 `MDVR-123`、`ABP-919`、
     * `CAWD-091` 这些正经有码番号误判成国产，路由反而变慢。
     */
    private val DOMESTIC_WORDS = listOf("麻豆", "SWAG")
    private val DOMESTIC_LATIN = Regex("""(?<![A-Z0-9])(?:MD|MT)(?![A-Z0-9])""")
    private val DOMESTIC_NUMBER = Regex("""(?<!\d)91(?!\d)""")

    /**
     * 从番号（以及可选的文件名）判断内容类型。
     *
     * 判定顺序本身是语义：FC2 → 素人 → 无码 → 韩国 MIB → 国产 → 有码形状 → 未知。
     * 例如 `1PONDO-010123_001` 必须先在无码集合命中，否则会被当成普通有码。
     *
     * @param number 已提取的番号；为空时退回用 [fileName] 判断。
     * @param fileName 原始文件名，用于补足番号里没有的信息（如「韩国 MIB」）。
     */
    fun classify(number: String, fileName: String = ""): ScrapeContentType {
        val numberText = number.trim().uppercase()
        val fileText = fileBaseName(fileName).trim().uppercase()
        val haystack = "$numberText $fileText"

        // 1. FC2 在任何厂牌判定之前：FC2 番号里的数字段很长，不能落到后面的有码形状。
        if (FC2_PPV.containsMatchIn(haystack) || FC2_NUMBER.containsMatchIn(haystack)) {
            return ScrapeContentType.Fc2
        }

        // 2/3/4 的「前缀」取番号；番号为空时退回文件名。
        val rawPrefix = rawPrefix(numberText).ifEmpty { rawPrefix(fileText) }
        val letters = letterPrefix(numberText).ifEmpty { letterPrefix(fileText) }

        if (letters in AMATEUR_PREFIXES) return ScrapeContentType.Amateur
        if (rawPrefix in UNCENSORED_PREFIXES) return ScrapeContentType.Uncensored
        if (rawPrefix == "KMIB" || haystack.contains("韩国 MIB") || haystack.contains("韩国MIB")) {
            return ScrapeContentType.Kmib
        }

        // 5. 国产标记。
        if (hasDomesticMarker(haystack)) return ScrapeContentType.Other

        // 6. 有码形状：`SSIS-115`，以及 `300MIUM-001` 这种带前导数字的写法。
        if (isCensoredShape(numberText) || isCensoredShape(fileText)) {
            return ScrapeContentType.Censored
        }

        return ScrapeContentType.Unknown
    }

    /**
     * 按类型调整来源顺序：只把该类型最该先问的来源提到前面，其余保持用户原有相对次序。
     *
     * 返回列表与 [configured] 的元素集合、长度完全一致，只是顺序不同；分类失败或
     * 没有偏好时原样返回，绝不在路由层替用户开关来源。
     */
    fun orderFor(content: ScrapeContentType, configured: List<ScrapeSource>): List<ScrapeSource> {
        val pinned = when (content) {
            ScrapeContentType.Censored -> listOf(
                ScrapeSource.Official,
                ScrapeSource.Dmm,
                ScrapeSource.Dmm2,
            )

            ScrapeContentType.Amateur -> listOf(ScrapeSource.Mgstage)
            ScrapeContentType.Fc2 -> listOf(ScrapeSource.Dmm2, ScrapeSource.CustomJson)
            ScrapeContentType.Uncensored -> listOf(ScrapeSource.Dmm2)
            ScrapeContentType.Kmib,
            ScrapeContentType.Other,
            ScrapeContentType.Unknown -> return configured
        }

        // 不用 sortedBy：它依赖枚举序号，且无法保证「同组内保持 configured 的相对次序」。
        val pinnedSet = pinned.toSet()
        return configured.filter { it in pinnedSet } + configured.filter { it !in pinnedSet }
    }

    /**
     * 按内容类型裁剪来源链，并把幸存者按取信层级重排。
     *
     * 这是 [orderFor] 之上的**过滤**版本，两者共存而不是替换：`orderFor` 保证「配置了哪些就查哪些」，
     * 有测试锁死这一点；但只重排解决不了一个真实问题——**问错站会拿回错的东西**。
     * peach 的 `ROUTES` 表里 `kmib` 与 `other` 都是空链，注释写得很直接：JAV 目录站按同形番号
     * 会返回**别的作品**，错值只能靠人认。鸭影这边同理：韩国 MIB 与国产番号去问 DMM/Javbus，
     * 最好的结果是「搜不到」，坏结果是拿回一部同号的日本片。
     *
     * 裁剪口径（每一条都能说出理由）：
     * - [ScrapeContentType.Censored]：不裁剪。有码是所有来源的主场，裁掉任何一家都可能让用户
     *   少拿一个字段；顺序交给层级重排即可。
     * - [ScrapeContentType.Amateur]：去掉 `Dmm`/`Dmm2`。peach 的 amateur 链从 `mgstage` 起，
     *   注释实测 `MIUM 1239`/`LUXU 1475` 在 DMM 零结果，素人号不在它目录上。
     * - [ScrapeContentType.Uncensored] / [ScrapeContentType.Fc2]：去掉 `Official`/`Dmm`/`Dmm2`/`Mgstage`。
     *   无码与 FC2 都不在 DMM 与片商白名单的目录里，留着只会白打一遍并触发风控。
     * - [ScrapeContentType.Kmib] / [ScrapeContentType.Other]：只留 `CustomJson`（用户自己配的来源）。
     *   没有自定义源时链会空掉，见下面的回退。
     * - [ScrapeContentType.Unknown]：不裁剪也不重排，原样交回配置顺序——识别不出就别替用户做决定。
     *
     * **链被裁空时必须回退到配置顺序**：用户可能只开了 Javbus 一家，或者只配了 DMM，
     * 这时按类型裁完就一个源都不剩。静默失败是这里最坏的结局（用户看到「没有可用来源」
     * 却不知道为什么），所以回退并给出 [RoutePlan.fallbackReason]，由调用方写进日志。
     */
    fun routeFor(content: ScrapeContentType, configured: List<ScrapeSource>): RoutePlan {
        val deduped = configured.distinct()
        if (content == ScrapeContentType.Unknown) {
            return RoutePlan(sources = deduped, skipped = emptyList(), fallbackReason = null)
        }

        val excluded = excludedFor(content)
        if (excluded.isEmpty()) {
            // 有码：不裁剪，只按层级分组。
            return RoutePlan(
                sources = ScrapeAuthority.orderByTier(deduped),
                skipped = emptyList(),
                fallbackReason = null
            )
        }

        val kept = deduped.filter { it !in excluded }
        val skipped = deduped.filter { it in excluded }
        if (kept.isEmpty()) {
            return RoutePlan(
                sources = deduped,
                skipped = emptyList(),
                fallbackReason = "按「${content.label}」裁剪后没有可用来源，已退回配置顺序（被裁掉的：" +
                    skipped.joinToString("、") { it.label } + "）"
            )
        }
        return RoutePlan(
            sources = ScrapeAuthority.orderByTier(kept),
            skipped = skipped,
            fallbackReason = null
        )
    }

    /** 某种内容类型下明确不该问的来源。空集表示「不裁剪」。 */
    private fun excludedFor(content: ScrapeContentType): Set<ScrapeSource> = when (content) {
        ScrapeContentType.Censored,
        ScrapeContentType.Unknown -> emptySet()

        ScrapeContentType.Amateur -> setOf(
            // DMM 系不收录 MGS 素人号（peach 2026-09-24 实测 `MIUM 1239`、`LUXU 1475` 零结果）。
            ScrapeSource.Dmm,
            ScrapeSource.Dmm2,
            // r18.dev 是 DMM 目录的镜像，同一条实测在它上面也是 404（`dvd_id=MIUM-1239`、
            // `dvd_id=SIRO-4321`、`dvd_id=STP-001` 三个都试过）。
            ScrapeSource.R18Dev,
            // `OfficialScraper` 的前缀白名单只有 S1/MOODYZ/MADONNA 这批有码厂牌，
            // 素人前缀不在表里时它在发请求前就 error 退出——留着它只是多一条必定失败的记账。
            // peach 的 amateur 链同样不含 makers/prestige/faleno/dahlia 这四家片商站。
            ScrapeSource.Official,
        )

        ScrapeContentType.Uncensored,
        ScrapeContentType.Fc2 -> setOf(
            ScrapeSource.Official,
            ScrapeSource.Dmm,
            ScrapeSource.Dmm2,
            ScrapeSource.Mgstage,
            // 无码号与 FC2 号不在 DMM 目录里，镜像上同样是 404（实测 `1PONDO-010123_001`、
            // `FC2-PPV-1234567`）。
            ScrapeSource.R18Dev,
        )

        // 韩国 MIB / 国产：JAV 目录站会按同形番号返回别的作品，只留给用户自配的来源。
        ScrapeContentType.Kmib,
        ScrapeContentType.Other -> setOf(
            ScrapeSource.Official,
            ScrapeSource.Dmm,
            ScrapeSource.Dmm2,
            ScrapeSource.Mgstage,
            ScrapeSource.Javbus,
            ScrapeSource.TheJavDB,
            // 国产号（MD/MT/91）与韩国 MIB 号在 DMM 目录里不存在，镜像上同样 404；
            // 留着的唯一后果是白打一次并让这家站多一条失败记录。
            ScrapeSource.R18Dev,
            // peach 给这三类的链本就是空表（`ROUTES['kmib'] = ()` / `ROUTES['other'] = ()`）。
            // AVBase 按同形番号会返回日本片，一本道/FC2 更是只认自家体系，
            // 把它们留在 MIB/国产链上就是主动去拿一部错片。
            ScrapeSource.Avbase,
            ScrapeSource.OnePondo,
            ScrapeSource.Fc2,
            // 后面接入的六家同理：片商官网只认自家前缀，FC2 系索引只认 FC2 番号，
            // MIB/国产号在它们上面同样只会拿回同号别的片。peach 的空链语义
            // 就是「这批片不问任何站」，所以它们必须与上面三家一起被裁掉。
            ScrapeSource.Faleno,
            ScrapeSource.Dahlia,
            ScrapeSource.MakerSite,
            ScrapeSource.Fc2Cmadb,
            ScrapeSource.Fc2PpvDb,
            ScrapeSource.Javten,
        )
    }

    /**
     * 只有桃链才额外执行的裁剪。
     *
     * 为什么不直接并进 [excludedFor]：那张表是 legacy 链也在用的，改它等于改用户已经跑了两年的
     * 行为（比如有人只开了一本道，靠它刮无码；也有人让 FC2 号也去 AVBase 搜同号）。
     * 桃链按 peach 的 `ROUTES` 原样表达顺位，代价是这两家有意的偏差要收窄到「只在桃链生效」，
     * 所以单独一张表，语义是「桃链在这一类上不问这几家」。
     *
     * 依据逐条写在这里（名字都能在 `metadata_routes.ROUTES` 里对上）：
     * - censored：peach 链是 `prestige/faleno/dahlia/makers/r18dev/dmm/avbase/javbus/javdb`，
     *   **不含** 1pondo、fc2 与 FC2 系三家索引（有码号在它们上面不存在）。
     * - amateur：peach 链 `mgstage/avbase/javbus/javdb/r18dev`，不含 1pondo、fc2、
     *   片商官网（faleno/dahlia/makers）与 FC2 系三家。
     * - uncensored：peach 链 `1pondo/avbase/javbus/javdb/avsox`，不含 fc2、片商官网与 FC2 系三家。
     * - fc2：peach 链 `fc2/fc2cmadb/fc2ppvdb/javten/javarchive/javdb`，不含 avbase、1pondo
     *   与片商官网（faleno/dahlia/makers）。
     */
    private fun peachOnlyExcluded(content: ScrapeContentType): Set<ScrapeSource> = when (content) {
        // 有码链 `prestige/faleno/dahlia/makers/r18dev/dmm/avbase/javbus/javdb`
        // 不含 1pondo 与 FC2——有码号在这两家根本不存在；片商官网与 FC2 索引里，
        // 有码号只该问片商官网（faleno/dahlia/makers），不该问 fc2cmadb/fc2ppvdb/javten。
        ScrapeContentType.Censored -> setOf(
            ScrapeSource.OnePondo,
            ScrapeSource.Fc2,
            ScrapeSource.Fc2Cmadb,
            ScrapeSource.Fc2PpvDb,
            ScrapeSource.Javten,
        )

        // 素人链 `mgstage/avbase/javbus/javdb/r18dev` 里没有任何片商官网与 FC2 索引：
        // 素人号在片商官网的前缀白名单外，在 FC2 体系里也不存在。
        ScrapeContentType.Amateur -> setOf(
            ScrapeSource.OnePondo,
            ScrapeSource.Fc2,
            ScrapeSource.Faleno,
            ScrapeSource.Dahlia,
            ScrapeSource.MakerSite,
            ScrapeSource.Fc2Cmadb,
            ScrapeSource.Fc2PpvDb,
            ScrapeSource.Javten,
        )

        // 无码链 `1pondo/avbase/javbus/javdb/avsox` 同理不含片商官网与 FC2 索引。
        ScrapeContentType.Uncensored -> setOf(
            ScrapeSource.Fc2,
            ScrapeSource.Faleno,
            ScrapeSource.Dahlia,
            ScrapeSource.MakerSite,
            ScrapeSource.Fc2Cmadb,
            ScrapeSource.Fc2PpvDb,
            ScrapeSource.Javten,
        )

        // FC2 链 `fc2/fc2cmadb/fc2ppvdb/javten/javarchive/javdb` 不含片商官网与一本道/AVBase。
        ScrapeContentType.Fc2 -> setOf(
            ScrapeSource.Avbase,
            ScrapeSource.OnePondo,
            ScrapeSource.Faleno,
            ScrapeSource.Dahlia,
            ScrapeSource.MakerSite,
        )

        ScrapeContentType.Kmib,
        ScrapeContentType.Other,
        ScrapeContentType.Unknown -> emptySet()
    }

    /**
     * 桃链（默认链）的路由：与 [routeFor] 同源，但**链被裁空时不回退**。
     *
     * 这是唯一一处刻意的语义分叉，原因是 [routeFor] 的回退会把错误类型问错站：
     * 韩国 MIB / 国产番号在 JAV 目录站上会拿回**同号别的作品**——回退不是「至少试一下」，
     * 而是「主动去拿一条错的」。peach 的 `ROUTES` 里 `kmib` / `other` 是空链，
     * 语义就是「这批片不问这些站」；空链的正确表达是「没有可用来源，到此为止」，
     * 由调用方决定怎么把这句话告诉用户，而不是悄悄换个来源清单重来。
     *
     * 其余类型的处理与 [routeFor] 一致（同样的裁剪集合、同样的层级重排），
     * 差别只在 [content] 为 [ScrapeContentType.Unknown] 时：
     * - [routeFor]：不裁剪也不重排，原样交回。
     * - 这里：先问社区/索引档（`CustomJson` 是用户自配，算社区），因为形状认不出来的番号
     *   最可能是社区站能搜到的怪号；**但仍保留官方档在后面**——只配了 DMM 一家的用户
     *   不该因为「认不出形状」就一个源都问不到。这是与 peach「unsure 只问社区」的
     *   有意偏差，理由写在这里，免得下次有人以为是漏改。
     */
    fun routeForPeach(content: ScrapeContentType, configured: List<ScrapeSource>): RoutePlan {
        val deduped = configured.distinct()
        if (deduped.isEmpty()) {
            return RoutePlan(
                sources = emptyList(),
                skipped = emptyList(),
                fallbackReason = "没有启用任何刮削来源"
            )
        }
        if (content == ScrapeContentType.Unknown) {
            val communityFirst = ScrapeAuthority.orderByTier(deduped).filter { source ->
                val tier = ScrapeAuthority.tierOf(source)
                tier == SourceTier.Community || tier == SourceTier.PreferredCommunity ||
                    tier == SourceTier.Fallback
            }
            val rest = ScrapeAuthority.orderByTier(deduped).filter { it !in communityFirst }
            return RoutePlan(
                sources = communityFirst + rest,
                skipped = emptyList(),
                fallbackReason = null
            )
        }

        val excluded = excludedFor(content) + peachOnlyExcluded(content)
        val kept = if (excluded.isEmpty()) deduped else deduped.filter { it !in excluded }
        val skipped = if (excluded.isEmpty()) emptyList() else deduped.filter { it in excluded }
        if (kept.isEmpty()) {
            return RoutePlan(
                sources = emptyList(),
                skipped = skipped,
                // 不回退：这句话会作为链的终止原因写进日志与失败信息。
                fallbackReason = "按「${content.label}」没有可问的来源（这类番号问 " +
                    skipped.joinToString("、") { it.label } + " 会拿回同号别的作品），已停止"
            )
        }
        // 顺位改用 peach 的字面 ROUTES 表（PeachContentRoute），而不是按取信层级重排：
        // 层级回答「同一字段听谁的」，顺序回答「先花谁的请求」，两者在同一条链上会给出
        // 不同的答案（r18.dev 层级高于社区，但 peach 在有码链上把它排在片商官网之后）。
        return RoutePlan(
            sources = PeachContentRoute.order(content, kept),
            skipped = skipped,
            fallbackReason = null
        )
    }

    /**
     * 桃链的结算判据：把 peach `settles` 的三条拆成可选的档位。
     *
     * 与 [settles] 的差别就是 peach 的第 ② 条——**薄请求**。当本次只想要封面/标签这类
     * 非必填字段时，第一家给到东西就该收工，不必为了凑齐标题/演员把后面的来源再打一遍。
     * 旧 [settles] 里这条缺失，导致「只要一张封面」的重刮也要跑完整条链。
     *
     * 必须保持 [settles] 原有行为不变：`ScrapeRouteRulesSettlesTest` 锁死了它的语义，
     * 而 legacy 链仍在用它。所以这里是新函数，不是给旧函数加参数。
     *
     * @param wantedScalars 这次是否要凑齐必填标量（标题/演员/厂牌/发行日）。
     *   为 false 即薄请求：只要有任意非空字段就结算。
     */
    fun settlesPeach(
        info: ScrapedMovieInfo,
        remainingSources: List<ScrapeSource> = emptyList(),
        wantedScalars: Boolean = true
    ): Boolean {
        if (!wantedScalars) return hasAnyValue(info)
        // ① 要类别，且后面还排着官方档 —— 值得多问一次。
        if (info.genres.isEmpty() && remainingSources.any { isOfficialSource(it) }) return false
        // ③ 必填标量齐了才算齐。
        return missingRequiredFields(info).isEmpty()
    }

    /** 结果里有没有任何一处非空值（薄请求的「给到东西」判据）。 */
    fun hasAnyValue(info: ScrapedMovieInfo): Boolean =
        info.title.isNotBlank() ||
            info.actors.isNotEmpty() ||
            info.studio.isNotBlank() ||
            info.year.isNotBlank() ||
            info.premiered.isNotBlank() ||
            info.genres.isNotEmpty() ||
            info.tags.isNotEmpty() ||
            info.posterUrl.isNotBlank() ||
            info.thumbUrl.isNotBlank() ||
            info.fanartUrl.isNotBlank() ||
            info.posterImageUrls.isNotEmpty() ||
            info.plot.isNotBlank()

    /**
     * 兼容旧调用：`StrmScrapeRepository` 仍在用 `ScrapeRouteRules.ContentType`。
     *
     * 这里用 object + val 指向顶层枚举，而不是再维护一套嵌套 enum：旧调用只做
     * `==` 比较和取 `.label`，两种写法等价，但这样不会出现「两套枚举各判各的」。
     */
    @Deprecated("改用顶层 ScrapeContentType", ReplaceWith("ScrapeContentType"))
    object ContentType {
        val Censored: ScrapeContentType = ScrapeContentType.Censored
        val Amateur: ScrapeContentType = ScrapeContentType.Amateur
        val Uncensored: ScrapeContentType = ScrapeContentType.Uncensored
        val Fc2: ScrapeContentType = ScrapeContentType.Fc2
        val Kmib: ScrapeContentType = ScrapeContentType.Kmib
        val Other: ScrapeContentType = ScrapeContentType.Other
        val Unknown: ScrapeContentType = ScrapeContentType.Unknown
    }

    /**
     * 兼容旧调用：旧 `arrange` 支持「用户显式置顶」，新 API 把置顶交给调用方（`ScrapeSourceChain.order` 的 `pinned`）。
     *
     * 保留是因为它是一个公开 API，删掉会让仓库外的调用方编译不过；仓库内已无调用方，
     * 自动链走 [routeFor]。保持旧行为：先按类型重排，再把 `pinned` 提到最前。
     */
    @Deprecated("改用 routeFor(type, configured) 或 orderFor(type, configured)", ReplaceWith("orderFor(type, configured)"))
    fun arrange(
        configured: List<ScrapeSource>,
        pinned: ScrapeSource?,
        type: ScrapeContentType
    ): List<ScrapeSource> {
        val deduped = (listOfNotNull(pinned) + configured).distinct()
        val ordered = orderFor(type, deduped)
        if (pinned == null) return ordered
        return listOf(pinned) + ordered.filter { it != pinned }
    }

    /**
     * 还缺哪些必填标量。结算判据 [settles] 用它，日志也用它说明「为什么还继续问」。
     */
    fun missingRequiredFields(info: ScrapedMovieInfo): List<String> = buildList {
        if (info.title.isBlank()) add("标题")
        if (info.actors.isEmpty()) add("演员")
        if (info.studio.isBlank()) add("厂牌")
        if (info.year.isBlank() && info.premiered.isBlank()) add("发行日")
    }

    /**
     * 结算判据（照 peach `metadata_routes.settles` 三条改写成鸭影版）：
     *
     * 1. 有类型字段的需求时，官方站给不出的类别不该算「够了」——但只有在
     *    **后面还排着另一家官方来源**时才值得多问一次；剩下的全是社区站时，
     *    它们本来就常常不带类别，多问也只是多打一次。
     * 2. 必填标量（标题 / 演员 / 厂牌 / 发行日）齐了才算齐。列表字段（类型、封面）
     *    刻意不参与短路——它们多数来源本来就缺，要求它们等于逼着把每一家都打一遍。
     *
     * 完成度阈值不再参与判定：0.7 的阈值会让「标题、厂牌、发行日有，演员为空」这种情况
     * 直接结算，而演员恰恰是用户翻库时最常筛的一项。
     */
    fun settles(
        info: ScrapedMovieInfo,
        remainingSources: List<ScrapeSource> = emptyList()
    ): Boolean {
        if (info.genres.isEmpty() && remainingSources.any { isOfficialSource(it) }) return false
        return missingRequiredFields(info).isEmpty()
    }

    /** 官方档来源：它们比社区站更接近发行方，也最有可能补齐类别与厂牌。 */
    private fun isOfficialSource(source: ScrapeSource): Boolean =
        source == ScrapeSource.Official ||
            source == ScrapeSource.Dmm ||
            source == ScrapeSource.Dmm2 ||
            source == ScrapeSource.Mgstage ||
            // 一本道与 FC2 在 peach 的 `SOURCE_SPECS` 里同为 official：作品页就是发行方自己的页，
            // 标题/日期/厂牌是官方口径。漏掉它们会让「有码号问完一本道就收工」——
            // 而一本道对同形有码号往往只有标题，正是第 ① 条要避免的情形。
            source == ScrapeSource.OnePondo ||
            source == ScrapeSource.Fc2

    /** 去掉前导数字后的字母前缀，如 `300MIUM-001` → `MIUM`。 */
    private fun letterPrefix(text: String): String =
        text.replaceFirst(LEADING_DIGITS, "").takeWhile { it in 'A'..'Z' }

    /** 原样取开头的字母/数字前缀，如 `1PONDO-...` → `1PONDO`。 */
    private fun rawPrefix(text: String): String =
        text.takeWhile { it in 'A'..'Z' || it in '0'..'9' }

    /** 去掉目录与扩展名，避免文件名里的 `.mp4` 干扰形状匹配。 */
    private fun fileBaseName(fileName: String): String {
        val name = fileName.trim().substringAfterLast('/').substringAfterLast('\\')
        return name.substringBeforeLast('.', name)
    }

    /** 前导数字允许存在（`300MIUM-001`），剥掉后再套标准有码形状。 */
    private fun isCensoredShape(text: String): Boolean =
        text.isNotEmpty() && CENSORED_SHAPE.matches(text.replaceFirst(LEADING_DIGITS, ""))

    private fun hasDomesticMarker(haystack: String): Boolean =
        DOMESTIC_WORDS.any { haystack.contains(it) } ||
            DOMESTIC_LATIN.containsMatchIn(haystack) ||
            DOMESTIC_NUMBER.containsMatchIn(haystack)
}
