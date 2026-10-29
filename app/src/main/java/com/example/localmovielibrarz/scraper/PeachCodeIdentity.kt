package com.example.localmovielibrarz.scraper

/**
 * 番号身份判定（peach `catalog_rules.release_identity` / `same_release_code` /
 * `metadata.identifies_code` 的移植）。
 *
 * 为什么要单独一份，而不是直接用 [ScrapeNumberGuard]：那个类回答的是「站点返回的番号与
 * 我要的是不是冲突」，刻意宽松（认不出就放行），因为它守的是「别把错片的元数据写上去」。
 * 这里回答的是「这家站的这条商品是不是这部作品」，两处代价不同：
 * - 判错成「同一部」→ 封面、厂牌、发行日全部串到别的片子上（peach 账本里 259LUXU-1514
 *   取回 FANZA 合集 `118sng013` 的标题就是这么来的）；
 * - 判错成「不是同一部」→ 少收一条商品，退到下一家来源，只是慢一步。
 * 两边的不对称决定了这里**必须严格**：认不出形态的值只和自己相等。
 */
object PeachCodeIdentity {

    /** `MMDDYY`：日期式番号的六位，月日必须成立（peach `catalog_rules._MMDDYY`）。 */
    private const val MMDDYY = "(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{2}"

    private val CODE_DATE = Regex("^$MMDDYY[-_]\\d{2,4}$")

    private val DATED_COMPACT = Regex("^$MMDDYY\\d{2,4}$")

    /**
     * 番号主体的形状：可选数字前缀 + 字母段 + 数字 + 可选尾字母。
     *
     * `0*` 吃掉补零（`ABW-232` 与 `118abw232` 里的 `00232` 要算同一部），
     * 数字前缀保留为身份的一部分——`390JAC-040`（配信）与 `JAC-040`（DVD）是两次发行。
     */
    private val RELEASE_ID = Regex("^(\\d{1,4})?([A-Z]{2,8})0*(\\d{1,5})([A-Z]?)$")

    /** DMM 的 `content_id` 标签前缀（`h_086iqqq00026`）。 */
    private val DMM_LABEL_PREFIX = Regex("^H_(?=\\d)")

    /**
     * 已核验的「数字前缀 + 字母段」等价对（peach `catalog_rules._RELEASE_PREFIX_ALIASES`）。
     *
     * 只登记逐条核过的：MGStage 前缀那一批在 peach 账本里比对过标题、时长、出演与封面。
     * 没核过的组合（`348NTR` / `451HHH` / `550ENE`）是不同厂牌的不同作品，**不能**加进来。
     */
    private val RELEASE_PREFIX_ALIASES: Set<Pair<String, String>> = setOf(
        "259" to "LUXU", "7" to "BAZX", "49" to "HA",
        "230" to "ORETD", "278" to "GYAN", "336" to "KBI", "345" to "SIMM", "413" to "INST",
        "428" to "SUKE", "435" to "MFC", "476" to "MLA", "762" to "FKOS"
    )

    /** 日期式番号被抹掉分隔符后的纯数字（`092415001`）。 */
    fun isDatedCompact(value: String): Boolean = DATED_COMPACT.matches(value)

    /** 日期式番号（`092415_001` / `092415-001`），两种分隔符都算。 */
    fun isDatedCode(value: String): Boolean = CODE_DATE.matches(value)

    /**
     * 把番号或来源返回的 id 收敛成可比对的作品身份。
     *
     * 返回空串表示「什么都没有」——调用方不能用空串当作「相等」，peach 的原话是
     * 「没有证据不是证据」。
     */
    fun releaseIdentity(code: String?): String {
        val raw = DMM_LABEL_PREFIX.replace((code.orEmpty()).uppercase().trim(), "")
        if (raw.isEmpty()) return ""
        // 日期式番号带着分隔符进身份：抹掉它就等于断言一本道的 `092415_001` 与
        // 加勒比的 `092415-001` 是同一部（实际是两家不同发行）。
        if (CODE_DATE.matches(raw)) return raw
        val value = raw.replace(Regex("[\\s._\\-]+"), "")
        if (value.isEmpty()) return ""
        if (value.startsWith("FC2")) {
            // 分隔符已抹掉，数字要从 `FC2` 之后找：否则 `FC2-1812235` 会被读成 `21812235`。
            val digits = Regex("(\\d{5,})").find(value.substring(3))?.groupValues?.get(1)
            return if (digits != null) "FC2-${digits.toLong()}" else value
        }
        // 来源只给一串数字时分隔符无从得知，原样留着，别替它挑一个片商。
        if (DATED_COMPACT.matches(value)) return value
        val shape = RELEASE_ID.find(value) ?: return value
        val prefix = shape.groupValues[1]
        val letters = shape.groupValues[2]
        val number = shape.groupValues[3]
        val suffix = shape.groupValues[4]
        val canonicalPrefix = if ((prefix to letters) in RELEASE_PREFIX_ALIASES ||
            (code.orEmpty()).uppercase().trim().startsWith("H_")
        ) {
            ""
        } else {
            prefix
        }
        return "$canonicalPrefix$letters-${number.toLongOrNull() ?: number}$suffix"
    }

    /**
     * 两个写法是否指同一部作品。
     *
     * 空值不算相等。另外放行两种 peach 明确列出的等价：
     * 1. 日期式番号的紧凑写法（`040221001` vs `040221_001`）——缺分隔符不构成「这是另一部」的证据；
     * 2. FC2 来源 id 是裸数字（`FC2-PPV-1812235` → `1812235`）。
     */
    fun sameReleaseCode(left: String?, right: String?): Boolean {
        val first = releaseIdentity(left)
        val second = releaseIdentity(right)
        if (first.isEmpty() || second.isEmpty()) return false
        if (first == second) return true
        val compact = listOf(first, second).firstOrNull { DATED_COMPACT.matches(it) }.orEmpty()
        if (compact.isNotEmpty()) {
            val dated = if (second == compact) first else second
            if (CODE_DATE.matches(dated) && dated.replace(Regex("[-_]"), "") == compact) return true
        }
        val fc2 = listOf(first, second).firstOrNull { it.startsWith("FC2-") }.orEmpty()
        if (fc2.isEmpty()) return false
        val other = if (second == fc2) first else second
        return other.all { it.isDigit() } && fc2 == "FC2-${other.toLongOrNull() ?: return false}"
    }

    /**
     * 来源返回的这条结果是不是这个番号本身。
     *
     * [identifiers] 是要参与比对的片段集合：来源的 `id`、`content_id`、`product_id`，
     * 以及 URL 里能承载番号的路径段与查询值（peach `_release_writings` 的口径）。
     *
     * 两层判据取并集：`sameReleaseCode` 严格，正则宽松但认得厂牌数字前缀
     * （`MAAN-545` 与 `300MAAN-545`）——peach 实测去掉任一条都会有合法候选被误拒。
     */
    fun identifiesCode(code: String, identifiers: Iterable<String?>): Boolean {
        val cleaned = identifiers.mapNotNull { it?.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) return false
        if (cleaned.any { sameReleaseCode(code, it) }) return true
        val value = code.uppercase().trim()
        val shape = Regex("^(?:\\d{3,6})?([A-Z]{2,8})-?(\\d{2,5})").find(value)
        if (shape != null) {
            val letters = shape.groupValues[1].lowercase()
            val digits = shape.groupValues[2].trimStart('0').ifEmpty { "0" }
            val pattern = Regex("(?<![a-z])$letters[-_]?0*$digits(?!\\d)")
            if (cleaned.any { pattern.containsMatchIn(it.lowercase()) }) return true
        }
        val compact = value.filter { it.isLetterOrDigit() }.lowercase()
        if (compact.isEmpty()) return false
        return cleaned.any { it.filter { ch -> ch.isLetterOrDigit() }.lowercase().contains(compact) }
    }

    /**
     * 从 URL 里取出可能承载番号的片段（路径段与查询值）。
     *
     * `combined=ene009` 这种要按 `=` 再切一层才拿得到右边那一半；路径按 `/` 切。
     */
    fun identifiersFromUrl(url: String?): List<String> {
        val address = url.orEmpty()
        if (address.isEmpty()) return emptyList()
        val withoutScheme = address.substringAfter("://", address)
        val pathAndQuery = withoutScheme.substringAfter('/', "")
        val found = mutableListOf<String>()
        for (chunk in pathAndQuery.split('/', '&', '?')) {
            if (chunk.isEmpty()) continue
            found += chunk
            if (chunk.contains('=')) found += chunk.substringAfter('=')
        }
        return found.filter { it.isNotEmpty() }
    }

    /**
     * 番号的字母段（`ABW-232` → `abw`），用来和 DMM 的 `content_id` 对照。
     *
     * FC2 返回空串：FC2 没有字母段体系，按字母段比的判据对它只会误判。
     */
    fun letterStem(code: String?): String {
        val key = (code.orEmpty()).uppercase().trim()
        if (key.isEmpty() || key.startsWith("FC2")) return ""
        val head = key.substringBefore('-')
        val letters = head.filter { it.isLetter() }.lowercase()
        return letters
    }

    /** 番号末尾那段数字（`ABW-232` → `232`）。 */
    fun trailingNumber(code: String?): String? =
        Regex("(\\d+)\\D*$").find(code.orEmpty())?.groupValues?.get(1)

    /**
     * 这个封面地址指向的是不是**另一部**作品的封套。
     *
     * 素人系番号在 AVBase 上会连到 DVD 合集（`259LUXU-1475` 取回 `SNG-021` 的封套），
     * 内容是同一段，封面却不是这部片的——大图定位、以图搜番都会跟着错位。
     * 判据落在 URL 上而不是抓取路径上，因为下一个引入错图的来源不会走同一个函数。
     */
    fun isCrossProductCover(code: String, url: String): Boolean {
        if (namesAnotherFc2Work(code, url)) return true
        val stem = letterStem(code)
        if (stem.isEmpty()) return false
        val cid = dmmContentId(url) ?: return false
        if (!cid.contains(stem)) return true
        // 字母段对上还不够：`YUJ-101` 取回的是 `yuj00011`，`CHU-201` 是 `chu00021`。
        val wanted = trailingNumber(code) ?: return false
        val got = Regex("(\\d+)[a-z]*$").find(cid)?.groupValues?.get(1) ?: return false
        return wanted.toLongOrNull() != got.toLongOrNull()
    }

    /** DMM `content_id` 的路径段（`/digital/video/118abw232/` → `118abw232`）。 */
    private val DMM_CID = Regex(
        "/(?:pics_dig/|dig/)?(?:digital/(?:video|amateur)|mono/movie)(?:/adult)?/([^/]+)/",
        RegexOption.IGNORE_CASE
    )

    private fun dmmContentId(url: String): String? = DMM_CID.find(url)?.groupValues?.get(1)?.lowercase()

    /** 图床文件名里写着别的作品号（`FC2PPV-2543627.gif`、`1083921pl.jpg`）。 */
    private fun namesAnotherFc2Work(code: String, url: String): Boolean {
        val key = (code.orEmpty()).uppercase().trim()
        if (!key.startsWith("FC2")) return false
        val wanted = Regex("(\\d{5,})").find(key.substring(3))?.groupValues?.get(1) ?: return false
        val fileName = url.substringBefore('?').substringAfterLast('/')
        // 图床给上传起的十六进制串（13 位）里夹着的数字不是作品号，先剔掉。
        val withoutHash = Regex("[0-9a-f]{13,}", RegexOption.IGNORE_CASE).replace(fileName, "")
        val numbers = Regex("(?<!\\d)\\d{5,7}(?!\\d)").findAll(withoutHash)
            .mapNotNull { it.value.toLongOrNull() }
            .toSet()
        if (numbers.isEmpty()) return false
        return (wanted.toLongOrNull() ?: return false) !in numbers
    }
}
