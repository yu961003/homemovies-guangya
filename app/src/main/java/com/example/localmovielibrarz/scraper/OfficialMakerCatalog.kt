package com.example.localmovielibrarz.scraper

/**
 * 片商官网目录：**声明式站点表 + 前缀路由**，是 peach `amane` 子进程桥在进程内的等价物。
 *
 * ## 为什么是「目录 + 通用抽取器」，而不是把 29 份爬虫搬过来
 *
 * peach 的 `makers` 这一步靠 `amane` 子进程（`tools/amane-bridge/bridge.py`，ADR-0043）去问
 * 二十九家片商官网，每家一个手写解析器。Android 上这条路走不通，而且**不该**走：
 *
 * - **没有 Python**：桥是一个 Python 子进程，Android 应用进程里起不来（也没有可用的
 *   `python3` 解释器打包进 APK 的合法路径）。所以桥的三个职责——按系列前缀路由、取页、
 *   归一化到本工程契约——必须在 Kotlin 里各有一份等价实现。
 * - **29 份手写解析器不可维护**：每一份都是「选择器 + 字段映射 + 改版适配」，站点一改版
 *   就要改一处代码、发一次版本。Android 的更新节奏（用户手动升级 APK）比 Web 慢得多，
 *   把 29 份解析器烧进 APK 等于把「站点改版」变成「等用户升级」。
 * - **数据比代码更适合用户修改**：站点域名、番号前缀、详情页/搜索页模板都是**数据**。
 *   放进一份 JSON 之后，用户可以自己加一家、改一条模板、补一个前缀，不需要重编译；
 *   本工程只保留一个抽取器，靠页面自带的机器可读标记（JSON-LD / OpenGraph / microdata）
 *   去抽字段——这才是「29 家」能收敛成一份代码的原因。
 *
 * ## 这是**起始集**，不是 29 家全量
 *
 * [BUILT_IN] 只收 12 家：都是本仓库内有据可查的主域与番号前缀（来源见每一条上面的注释）。
 * peach 那 29 家里剩下的十几家（SOD / Premium / kawaii* / MAXING / Muku / 无码系…）没有进
 * 起始集——不是遗漏，是**不编造**：没有核实过的域名与前缀写进目录，表现为「番号被错误的站
 * 认领」并抓回**另一部片**的资料，比抓不到更糟。
 *
 * 用户可以在设置里导入自家目录（JSON 见 [parseCatalog] 的格式说明），
 * [merge] 按 `id` 覆盖内置条目、其余追加。也就是说这份表是**可被用户扩展的起始集**。
 *
 * ## 模板填不准就留空串（重要）
 *
 * [OfficialMakerSite.detailUrlTemplate] / [OfficialMakerSite.searchUrlTemplate] 为空串是
 * 合法状态，含义是「这家站还没配置取数入口」。此时：
 * - [OfficialSiteScraper] 会记一条日志并跳过这一家，**不**去猜 URL；
 * - 若全部站都跳过，最终失败理由带上「这家片商没有配置取数模板」。

 * 反过来，填一条**猜的**模板更糟：站点回 404，链路会把「我们写错了模板」记成
 * 「这家站没有这部片」（[PeachFailureReason.NotFound]），悄悄换下一家，问题再也不会被发现。
 *
 * ## 为什么不按「番号前缀不在表里也硬发一次请求」
 *
 * 前缀路由是 peach `metadata_routes.MAKER_EVIDENCE` 的同一条判据：**前缀不在表里就不发请求**。
 * 官网的搜索页对陌生番号一律回 200 + 「没有找到」，一次询问换回一条噪声，还会把来源的
 * 健康度（冷却/限流阶梯）白白消耗掉；而 covered 的番号（例如 `SSIS-`）本来就该由这一家答。
 * 所以 [route] 返回空列表时，[OfficialSiteScraper] 直接以 [PeachFailureReason.NotFound] 结束
 * 并说明「前缀不匹配任何已配置片商」——这是**特性**，不是缺陷。
 */
data class OfficialMakerSite(
    /** 稳定主键，如 `"s1"`。用户在目录里覆盖内置条目时按它匹配。 */
    val id: String,
    /** 展示名，如 `"S1"`。同时写进 [PeachParseResult.Record] 的 `source` 与日志。 */
    val label: String,
    /** 写进 [ScrapedMovieInfo.studio] 的发行方名，如 `"S1 NO.1 STYLE"`。页面自己给了 メーカー 时以页面为准。 */
    val studio: String,
    /** 主域根，如 `"https://s1s1s1.com/"`。同时用作详情页/搜索页请求的 `Referer`。 */
    val homeUrl: String,
    /**
     * 作品详情页模板；`{code}` 会被替换成**小写去空格**的番号（如 `ssis-001`），
     * `{code_compact}` 是同一个番号去掉分隔符的写法（如 `ssis001`，站点自营 CMS 常用这种）。
     *
     * 空串表示这家不给详情页模板（例如 MAX-A 的作品地址是 `item.php?item_id=<不透明哈希>`，
     * 番号推不出来）。模板可以是相对路径（以 `/` 开头时按 [homeUrl] 补全）。
     */
    val detailUrlTemplate: String,
    /** 搜索页模板；`{code}` / `{code_compact}` 同上，另可用 `{query}`（原样番号）。空串表示这家不支持搜索。 */
    val searchUrlTemplate: String,
    /** 番号前缀（小写），如 `["ssis", "ssni", "sone"]`。命中即认为这家可能有这部片。 */
    val seriesPrefixes: List<String>,
    /** 抽取档：见 [OfficialExtractionProfile]。 */
    val profile: OfficialExtractionProfile = OfficialExtractionProfile.Generic,
    /** 官方发売日开始日早于发行日（peach 对 `prestige` 就是这种），此时 release 放 deliveryDate 而不是 premiered。 */
    val releaseIsDeliveryDate: Boolean = false,
    /**
     * 目录项的备注：这条为什么模板留空 / 前缀从哪来。
     *
     * 它**不参与**路由与抽取，只用于展示与导出——用户 import 一份目录时能直接看到
     * 「这条还没配模板」，比对着一个空串猜原因好。放在最后一个参数是为了让 data class 的
     * 前九个参数与 `OfficialMakerSite` 的文档顺序一致。
     */
    val note: String = ""
) {

    /** 这家站有没有能发出的取数入口。没有的话它只会被路由到、然后被跳过。 */
    val hasFetchTemplate: Boolean
        get() = detailUrlTemplate.isNotBlank() || searchUrlTemplate.isNotBlank()

    /** 这家站认不认番号。空列表的条目是「占位条目」：知道官网在哪，但前缀还没核实。 */
    val claimsAnyCode: Boolean
        get() = seriesPrefixes.isNotEmpty()
}

/**
 * 抽取档。Generic 是默认档，靠页面自带的机器可读标记（JSON-LD / OpenGraph / microdata）
 * 抽字段，不需要为每家站手写选择器——这是「29 家手写爬虫」的替代方案。
 *
 * 为什么只留三档而不是「每家一档」：档位的区别是**页面结构形态**，不是站点身份。
 * 形态只有三种：结构化数据在 `<script type="application/json">` 里（[InertiaJson]，
 * S1 系那批 Next.js 站）、在 `<script type="application/ld+json">` 里（[Generic] 的第一层）、
 * 或者根本没有机器可读段、只有 `dt`/`dd` 与 `th`/`td`（[DefinitionList]）。
 * 再加上「站方给了但三种都没有」时逐层降级的兜底，就覆盖了这 29 家的全部形态。
 */
enum class OfficialExtractionProfile {

    /** JSON-LD `Product`/`Movie` → OpenGraph → microdata，逐层降级。 */
    Generic,

    /** 站点在 `<script type="application/json">` 里给了结构化数据（如 S1 系），先试它。 */
    InertiaJson,

    /** 只有纯 HTML 表格/定义列表，靠 `dt`/`dd`、`th`/`td` 的标签文字配对抽。 */
    DefinitionList,

    /**
     * `h2.p-workPage__title` + `p.p-workPage__text` + `div.th`/`div.td` 的标答栏（S1 系）。
     *
     * 与 [DefinitionList] 的区别有两处，都是 S1 实测出来的：
     * 1. 标签栏不是 `dt`/`dd` 也不是 `tr`/`th`，而是 `div.th` → 相邻 `div.td`；
     *    值里还嵌着 `<div class="item">` 与多值 `<a>`，非贪婪正则一遇到内层 `</div>` 就截断。
     * 2. 标题与简介不在 meta 里：`og:title` 一个都没有，`og:image` 反而是站点 logo。
     *    真正的一手标题在 `h2.p-workPage__title`、剧情在 `p.p-workPage__text`。
     */
    WorkPageTable
}

object OfficialMakerCatalog {

    /**
     * 内置目录：起始集（12 家）。
     *
     * 每一条的域名与前缀都能在本仓库里找到出处，注释里写了具体文件与变量名——**没有一条是
     * 记忆或推断出来的**。这条纪律比「覆盖 29 家」重要：错误的前缀会把别的片的资料写到
     * 这个番号头上，而那种错误在账本里看不出来。
     *
     * **模板一律留空**（见每个条目的注释）：详情页/搜索页的路径形态没有本地证据，
     * 猜出来的模板只会把「配置缺失」伪装成「站上没有这部片」。用户在自家目录里补上模板后，
     * 这一家才会真的发请求。
     */
    val BUILT_IN: List<OfficialMakerSite> = listOf(
        site(
            id = "s1",
            label = "S1",
            studio = "S1 NO.1 STYLE",
            homeUrl = "https://s1s1s1.com/",
            // `ssis` / `ssni` / `snis` / `soe` 出自 OfficialScraper.OFFICIAL_PREFIXES 的 s1s1s1.com 行。
            // `sone` 不在那张表里，但有两条本地证据：仓库测试里的 `SONE-694` / `sone-866` 用例，以及
            // peach 自己的 `SSIS`/`SONE` 并存写法——它是 S1 现行番号段，与 `soe` 不是同一段，两条都留。
            // 不写 `s1`：单字母前缀会把 `s1` 开头的别家番号一起认领，且本地没有一条 `s1` 番号证据。
            prefixes = listOf("ssis", "ssni", "snis", "sone", "soe"),
            // 模板出处：2026-10-30 实测（curl + 浏览器 UA，出口为代理线路）。
            // - 搜索：`/search/list?keyword=SNIS225` → 200 / 42678 B，结果页有 1 个
            //   `works/detail/SNIS225` 链接与「全1作品」；`keyword=snis225` 同体；
            //   而 `keyword=SNIS-225`（带连字符）只有 38608 B、0 个详情链接 → 站内搜索**只认 compact 写法**。
            // - 详情：`/works/detail/SNIS225` → 200 / 75918 B，`<title>` 是作品名；
            //   `/works/detail/snis-225` → 200 但 38067 B **空壳页**（无标题），所以模板也得用 compact。
            // 顺带结论：S1 首页 `<form>` 数量为 0，`OfficialSearchForm` 的「回首页发现搜索表单」
            // 兜底对它恒失败——这两条模板就是替代那条兜底的入口。
            detailUrlTemplate = "/works/detail/{code_compact}",
            searchUrlTemplate = "/search/list?keyword={code_compact}",
            // 这一档是跟着 S1 实测出来的：页面没有任何 `ld+json` / `og:title`，标题在
            // `h2.p-workPage__title`、简介在 `p.p-workPage__text`、资料栏是 `div.th`/`div.td`。
            // 用 Generic 档只会从 `<title>` 拿到一个带站点名的串，其余字段全空。
            profile = OfficialExtractionProfile.WorkPageTable,
            note = "S1 NO.1 STYLE 的站；模板与抽取档均已按实测补齐（compact 写法，连字符会命中空壳页）"
        ),
        site(
            id = "moodyz",
            label = "MOODYZ",
            studio = "MOODYZ",
            homeUrl = "https://moodyz.com/",
            // 出处：OfficialScraper.OFFICIAL_PREFIXES 的 moodyz.com 行（该行有五十多个前缀，
            // 这里只取常见的六个，其余由用户目录补）。
            prefixes = listOf("mide", "midv", "mimk", "mifd", "miaa", "miab"),
            note = "MOODYZ 的站；模板待补"
        ),
        site(
            id = "ideapocket",
            label = "IDEA POCKET",
            studio = "IdeaPocket",
            homeUrl = "https://ideapocket.com/",
            // 出处：OfficialScraper.OFFICIAL_PREFIXES 的 ideapocket.com 行。
            prefixes = listOf("ipx", "ipzz", "ipz", "iptd", "ipit", "ipsd"),
            note = "IDEA POCKET 的站；模板待补"
        ),
        site(
            id = "madonna",
            label = "Madonna",
            studio = "Madonna",
            homeUrl = "https://www.madonna-av.com/",
            // 出处：OfficialScraper.OFFICIAL_PREFIXES 的 madonna-av.com 行。
            // 刻意不含该行里的 `jufd` / `jfb`：同一张表在 fitch-av.com 行也列了它们，
            // 而 `buildOfficialPrefixes()` 的 `toMap()` 是后者覆盖前者（fitch 赢）。
            // 两处证据互相矛盾时把歧义留给用户目录，目录里不写一条自己也拿不准的归属。
            prefixes = listOf("jul", "juq", "jux", "juy", "juk", "juc", "jusd", "juvr"),
            note = "Madonna 的站；模板待补"
        ),
        site(
            id = "faleno",
            label = "FALENO",
            studio = "FALENO",
            homeUrl = "https://faleno.jp/",
            // 出处：peach metadata_routes.MAKER_EVIDENCE["faleno"] 的前缀表（FSDSS/FCDSS/FNS/MGOLD/JIMMY）。
            prefixes = listOf("fsdss", "fcdss", "fns", "mgold", "jimmy"),
            note = "FALENO 的站（对非日本出口可能直接 403，靠浏览器回落）；模板待补"
        ),
        site(
            id = "dahlia",
            label = "DAHLIA",
            studio = "DAHLIA",
            homeUrl = "https://dahlia-av.jp/",
            // 出处：peach metadata_routes.MAKER_EVIDENCE["dahlia"]（只有 DLDSS 一条）。
            prefixes = listOf("dldss"),
            note = "DAHLIA 的站（WordPress，页面没有结构化段时走定义列表档）；模板待补"
        ),
        site(
            id = "honnaka",
            label = "本中",
            studio = "本中",
            homeUrl = "https://honnaka.jp/",
            // 出处：OfficialScraper.OFFICIAL_PREFIXES 的 honnaka.jp 行。
            prefixes = listOf("hnd", "hndb", "hmn", "hnvr"),
            note = "本中（Honnaka）的站；模板待补"
        ),
        site(
            id = "fitch",
            label = "Fitch",
            studio = "Fitch",
            homeUrl = "https://fitch-av.com/",
            // 出处：OfficialScraper.OFFICIAL_PREFIXES 的 fitch-av.com 行。同样剔除 jufd/jfb
            // （与 Madonna 行冲突，见上）。
            prefixes = listOf("fcvr", "jufe", "juny", "nyb", "finh", "gcf", "nima"),
            note = "Fitch 的站；模板待补"
        ),
        site(
            id = "ebody",
            label = "E-BODY",
            studio = "E-BODY",
            homeUrl = "https://www.av-e-body.com/",
            // 出处：OfficialScraper.OFFICIAL_PREFIXES 的 av-e-body.com 行。
            prefixes = listOf("ebvr", "ebod", "eyan"),
            note = "E-BODY 的站；模板待补"
        ),
        site(
            id = "kirakira",
            label = "kira☆kira",
            studio = "kira☆kira",
            homeUrl = "https://kirakira-av.com/",
            // 出处：OfficialScraper.OFFICIAL_PREFIXES 的 kirakira-av.com 行。
            prefixes = listOf("kivr", "blk", "kibd", "kifd", "kird", "kisd"),
            note = "kira☆kira 的站；模板待补"
        ),
        site(
            id = "attackers",
            label = "ATTACKERS",
            studio = "ATTACKERS",
            homeUrl = "https://attackers.net/",
            // 出处：OfficialScraper.OFFICIAL_PREFIXES 的 attackers.net 行。
            prefixes = listOf("atvr", "rbk", "rbd", "atid", "atkd", "shkd", "adn", "sspd", "azsd"),
            note = "ATTACKERS 的站；模板待补"
        ),
        site(
            id = "maxa",
            label = "MAX-A",
            studio = "MAX-A",
            homeUrl = "https://www.max-a.co.jp/",
            // 前缀留空：本仓库没有任何 MAX-A 的番号前缀证据（OfficialScraper 的官方站白名单里
            // 没有这家，peach 的 MAKER_EVIDENCE 也没有）。凭记忆填一条前缀，等于让这家去认领
            // 一个可能属于别人的番号。
            prefixes = emptyList(),
            note = "MAX-A 的站：域名有据（web/integrations/jav-sites.json），前缀与模板都待补，" +
                "补上前缀之前这家永远不会被 [route] 选中——这是有意的占位条目，不是遗漏"
        )
    )

    /** 内置目录里被路由命中的站名，日志/单测里用；保持目录顺序。 */
    val BUILT_IN_IDS: List<String> get() = BUILT_IN.map { it.id }

    /**
     * 从 JSON 文本解析目录。
     *
     * 顶层两种形态都收：`{ "sites": [ … ] }` 或直接一个 `[ … ]`。单条格式（键名一律 camelCase，
     * 也收 snake_case 别名）：
     *
     * ```json
     * { "sites": [ { "id": "s1", "label": "S1", "studio": "S1 NO.1 STYLE",
     *   "homeUrl": "https://s1s1s1.com/", "detailUrlTemplate": "", "searchUrlTemplate": "",
     *   "seriesPrefixes": ["ssis","ssni","sone"], "profile": "Generic",
     *   "releaseIsDeliveryDate": false } ] }
     * ```
     *
     * 解析不了的单条**跳过而不整份失败**：用户手写的目录错一条，不该让整站刮削不可用。
     * 但「整份 JSON 都不是 JSON」时返回空列表——调用方拿到的空列表与「目录为空」等价，
     * 此时 [merge] 之后仍是内置目录，行为是「用户目录没生效」而不是「刮削坏掉」。
     *
     * 同一份目录里 `id` 重复时后写的覆盖先写的（位置仍按第一次出现排），与 [merge] 的口径一致。
     */
    fun parseCatalog(json: String?): List<OfficialMakerSite> {
        val root = PeachJson.parse(json) ?: return emptyList()
        val entries: List<PeachJsonValue.Obj> = when (root) {
            is PeachJsonValue.Obj -> root["sites"].objectsOrEmpty()
            is PeachJsonValue.Arr -> root.objectsOrEmpty()
            else -> emptyList()
        }
        val byId = LinkedHashMap<String, OfficialMakerSite>()
        for (entry in entries) {
            val parsed = siteOf(entry) ?: continue
            byId[parsed.id] = parsed
        }
        return byId.values.toList()
    }

    /**
     * 按番号前缀路由。返回所有**前缀命中**的站，保持目录顺序。
     *
     * 匹配口径（`code` 先经 [normalizedCode] 归一：小写、去空白）：
     * 归一后的番号以某个前缀开头，且**紧跟前缀的那一位不是字母**。
     *
     * 为什么要求「不是字母」：番号前缀互为前缀的情况很多（`hnd` 与 `hndb`、`ipx` 与 `ipzz`），
     * 只要 `startsWith` 就会让 `hnd` 抢走 `hndb-001`，而 `hnd` 那家回的会是另一部片的页。
     * 允许数字紧跟（`ssis001` 与 `ssis-001` 两种写法站点/文件名里都有），
     * 拒绝字母紧跟就是这条判据。
     *
     * 多个站同时命中时**不裁**：目录顺序先试，第一家给出可用元数据就赢（见 [OfficialSiteScraper]）。
     */
    fun route(code: String, catalog: List<OfficialMakerSite> = BUILT_IN): List<OfficialMakerSite> {
        val normalized = normalizedCode(code)
        if (normalized.isEmpty()) return emptyList()
        return catalog.filter { site ->
            site.seriesPrefixes.any { prefix -> claims(prefix, normalized) }
        }
    }

    /**
     * 合并内置与用户目录：同 id 的用户条目覆盖内置条目，其余追加。
     *
     * 为什么是「覆盖 + 追加」而不是「用户目录整份替换」：用户写的通常只是**增量**（新加两家、
     * 给 S1 补一条详情模板）。整份替换会让一份只有三条的目录把另外九家静默删掉，
     * 而用户完全看不出「为什么之前能抓的片现在抓不到了」。
     *
     * 位置口径：被覆盖的条目留在内置顺序的位置上，用户新增的按用户文件顺序追加在尾部——
     * 重刮时链上的尝试顺序不会因为导入一份目录而整体洗牌。
     */
    fun merge(builtIn: List<OfficialMakerSite>, user: List<OfficialMakerSite>): List<OfficialMakerSite> {
        val byId = LinkedHashMap<String, OfficialMakerSite>()
        builtIn.forEach { byId[it.id] = it }
        user.forEach { byId[it.id] = it }
        return byId.values.toList()
    }

    /** 详情页地址；模板为空时返回空串（调用方据此跳过这一家，不要猜 URL）。 */
    fun detailUrl(site: OfficialMakerSite, code: String): String =
        fillTemplate(site.detailUrlTemplate, code, site.homeUrl)

    /** 搜索页地址；模板为空时返回空串。 */
    fun searchUrl(site: OfficialMakerSite, code: String): String =
        fillTemplate(site.searchUrlTemplate, code, site.homeUrl)

    /** 小写、去空白的番号写法：`SSIS-001` → `ssis-001`。模板里的 `{code}` 用的就是它。 */
    fun normalizedCode(code: String): String = buildString {
        for (ch in code.trim().lowercase()) {
            // 全角连字符与全角空格：用户的番号从文件名/NFO 来，两种写法都见过。
            val mapped = when (ch) {
                '－', 'ー' -> '-'
                else -> ch
            }
            if (!mapped.isWhitespace()) append(mapped)
        }
    }

    /**
     * 去掉分隔符的番号写法：`SSIS-001` → `ssis001`。
     *
     * 站点自营 CMS 与搜索结果页的 URL 常用这种（`/works/detail/midv377` 而不是 `midv-377`），
     * 所以模板里给了 `{code_compact}`，[OfficialPageExtractor.firstWorkUrl] 也用它做链接匹配。
     */
    fun compactCode(code: String): String =
        normalizedCode(code).filterNot { it == '-' || it == '_' || it == '.' || it == '/' }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 前缀是否认领这个（已归一的）番号。判据见 [route] 的 KDoc。 */
    private fun claims(prefix: String, normalizedCode: String): Boolean {
        if (prefix.isEmpty() || !normalizedCode.startsWith(prefix)) return false
        if (normalizedCode.length == prefix.length) return true
        val next = normalizedCode[prefix.length]
        return !next.isLetter()
    }

    private fun fillTemplate(template: String, code: String, homeUrl: String): String {
        val raw = template.trim()
        if (raw.isEmpty()) return ""
        val normalized = normalizedCode(code)
        val filled = raw
            .replace("{code_compact}", compactCode(code))
            .replace("{code}", normalized)
            .replace("{query}", code.trim())
        return when {
            filled.startsWith("//") -> "https:$filled"
            filled.startsWith("/") && homeUrl.isNotBlank() -> homeUrl.trimEnd('/') + filled
            filled.startsWith("http://") || filled.startsWith("https://") -> filled
            homeUrl.isBlank() -> filled
            else -> homeUrl.trimEnd('/') + "/" + filled
        }
    }

    private fun site(
        id: String,
        label: String,
        studio: String,
        homeUrl: String,
        prefixes: List<String>,
        note: String,
        detailUrlTemplate: String = "",
        searchUrlTemplate: String = "",
        profile: OfficialExtractionProfile = OfficialExtractionProfile.Generic,
        releaseIsDeliveryDate: Boolean = false
    ) = OfficialMakerSite(
        id = id,
        label = label,
        studio = studio,
        homeUrl = homeUrl,
        detailUrlTemplate = detailUrlTemplate,
        searchUrlTemplate = searchUrlTemplate,
        seriesPrefixes = prefixes.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct(),
        profile = profile,
        releaseIsDeliveryDate = releaseIsDeliveryDate,
        note = note
    )

    /**
     * 单条目录项 → [OfficialMakerSite]；缺 `id` 或整条不是对象时返回 null（跳过而不失败）。
     *
     * 缺省口径：`label` 缺省用 `id`，`studio` 缺省用 `label`——两列都是展示/归并用的，
     * 缺了不该让整条作废（真的作废会让用户目录里少一家且没有任何提示）。
     */
    private fun siteOf(entry: PeachJsonValue.Obj): OfficialMakerSite? {
        val id = entry.textOf("id", "siteId", "site_id")
        if (id.isEmpty()) return null
        val label = entry.textOf("label", "name").ifEmpty { id }
        val prefixes = buildList {
            addAll(entry["seriesPrefixes"].stringsOrEmpty())
            addAll(entry["series_prefixes"].stringsOrEmpty())
            addAll(entry["prefixes"].stringsOrEmpty())
            // 也收逗号/竖线分隔的单串写法：手写目录里 { "seriesPrefixes": "ssis,ssni" } 很常见。
            val inline = entry["seriesPrefix"].stringOrEmpty()
            if (inline.isNotBlank()) addAll(inline.split(',', '|', '/', ' '))
        }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

        return OfficialMakerSite(
            id = id,
            label = label,
            studio = entry.textOf("studio", "maker").ifEmpty { label },
            homeUrl = normalizeHomeUrl(entry.textOf("homeUrl", "home_url", "url", "baseUrl", "base_url")),
            detailUrlTemplate = entry.textOf("detailUrlTemplate", "detail_url_template", "detailUrl", "detail_url"),
            searchUrlTemplate = entry.textOf("searchUrlTemplate", "search_url_template", "searchUrl", "search_url"),
            seriesPrefixes = prefixes,
            profile = profileOf(entry.textOf("profile", "extractionProfile", "extraction_profile")),
            releaseIsDeliveryDate = entry.boolOf("releaseIsDeliveryDate", "release_is_delivery_date"),
            note = entry.textOf("note", "reason", "comment")
        )
    }

    /** 主域根统一成「带 scheme、以 `/` 结尾」的形状；空串保持空串（这家没配主域也能路由）。 */
    private fun normalizeHomeUrl(raw: String): String {
        val value = raw.trim()
        if (value.isEmpty()) return ""
        val absolute = if (value.startsWith("//")) "https:$value" else value
        return absolute.trimEnd('/') + "/"
    }

    /**
     * 抽取档的词表：认枚举名，也认几个常见别名。
     *
     * 认不出的档退回 [OfficialExtractionProfile.Generic] 而不是丢掉整条：
     * 一个拼错的 `"profile": "generic"`（大小写）不该让这家站从目录里消失。
     */
    private fun profileOf(raw: String): OfficialExtractionProfile {
        val key = raw.trim().lowercase().filterNot { it == '_' || it == '-' || it == ' ' }
        return when (key) {
            "", "generic", "auto", "default" -> OfficialExtractionProfile.Generic
            "inertia", "inertiajson", "jsonscript", "applicationjson" -> OfficialExtractionProfile.InertiaJson
            "definitionlist", "dl", "table", "html" -> OfficialExtractionProfile.DefinitionList
            "workpagetable", "workpage", "workpagetb", "s1" -> OfficialExtractionProfile.WorkPageTable
            else -> OfficialExtractionProfile.Generic
        }
    }

    private fun PeachJsonValue.Obj.textOf(vararg keys: String): String {
        for (key in keys) {
            val value = this[key].stringOrEmpty().trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    private fun PeachJsonValue.Obj.boolOf(vararg keys: String): Boolean {
        for (key in keys) {
            when (val value = this[key]) {
                is PeachJsonValue.Bool -> return value.value
                is PeachJsonValue.Str -> if (value.value.equals("true", ignoreCase = true)) return true
                is PeachJsonValue.Num -> if (value.raw == "1") return true
                else -> Unit
            }
        }
        return false
    }
}
