package com.example.localmovielibrarz.metadata

/**
 * 实体名（演员 / 厂牌 / 系列）的写法规整。
 *
 * 为什么需要：同一个演员在不同站点会被写成「葵つかさ」「葵 つかさ」「ＡＯＩ　ＴＳＵＫＡＳＡ」
 * 或「Aoi Tsukasa」。直接拿原始字符串当主键，一次刮削就能建出四五个「不同」的人，
 * 演员页、作品数、头像全部裂开，别名也永远聚不到一起。
 *
 * 这里只消除**排版差异**（字符宽度、大小写、空白、零宽字符、常见标点），不做语义翻译：
 * 不把日文名映射成英文名，那需要外部字典且极易误合并（不同的人可能共用一个罗马字写法）。
 * 因此它是纯函数、无 Android 依赖，可以放心在扫描线程或单测里跑。
 *
 * 已知边界：半角片假名（ｱ）与全角片假名（ア）不互相折叠；两者混写极少见，且要正确处理
 * 浊音/半浊音组合必须引入假名对照表，收益低于误合并风险，故不做。
 */
object EntityNameNormalizer {

    /**
     * 一个实体名的两种视图。
     *
     * @property canonical 展示用写法：保留大小写，但已折叠空白、统一字符宽度、去掉零宽字符。
     * @property normalized 比较用写法：在 canonical 基础上统一小写，用于判断是否同一实体。
     */
    data class EntityKey(val canonical: String, val normalized: String)

    /**
     * 比较用的规范化：全角转半角、标点统一、折叠空白、去零宽、转小写。
     *
     * 等于 `canonicalFrom(raw).lowercase()`；单列出来是因为「判断两个写法是否相同」是最高频调用，
     * 调用点不必自己记得补 lowercase。
     */
    fun normalize(raw: String): String = canonicalFrom(raw).lowercase()

    /**
     * 展示用的规范化：保留大小写，但折叠连续空白、去掉首尾空白与零宽字符、全角转半角。
     *
     * 为什么展示也要转半角：全角拉丁字母在 Android 列表里字距异常、搜索也搜不到，
     * 而且「ＡＯＩ」与「AOI」在界面上看起来是两个人，先统一掉可以少一次人工困惑。
     */
    fun canonicalFrom(raw: String): String = foldTypography(raw)

    /**
     * 给一批原始写法生成去重后的键，保持输入顺序，首次出现的写法作为 [EntityKey.canonical]。
     *
     * 去重键是 normalized：大小写、全角半角、连续空白不同的写法会合并成一条；
     * 「有没有空格」（葵つかさ / 葵 つかさ）故意**不**在这里合并——那是两条不同的规范化写法，
     * 由 [aliasGroups] 负责把它们归到同一实体组，调用方可以按需选择「精确键」还是「别名组」。
     */
    fun keysOf(values: Iterable<String>): List<EntityKey> {
        val seen = LinkedHashSet<String>()
        val keys = ArrayList<EntityKey>()
        for (raw in values) {
            val canonical = canonicalFrom(raw)
            if (canonical.isEmpty()) continue
            val normalized = canonical.lowercase()
            if (seen.add(normalized)) {
                keys += EntityKey(canonical = canonical, normalized = normalized)
            }
        }
        return keys
    }

    /**
     * 把同一实体的多种写法归组，组内首元素是首次出现的写法，组与组的相对顺序同样按首次出现排列。
     *
     * 归组条件是「normalized 相同」或「再去掉全部空格后相同」：日文名在站点上带不带空格非常随机，
     * 但对同一个人来说这只是排版差别。相较之下，直接把所有空格从比较键里删掉后仍保留单个空格
     * 的形式给 [keysOf]，是为了让「A B」这种真实存在的双词名在精确映射时不会和「AB」撞键。
     *
     * 代价是理论上可能把「Ann Marie」与「Annmarie」合成一个人；对当前以日文演员为主的库来说，
     * 这种合并远多于它造成的误合并，且人工确认界面拿到的仍是全部原始写法，可随时拆开。
     */
    fun aliasGroups(values: Iterable<String>): List<List<String>> {
        val grouped = LinkedHashMap<String, LinkedHashSet<String>>()
        for (raw in values) {
            val canonical = canonicalFrom(raw)
            if (canonical.isEmpty()) continue
            val groupKey = canonical.lowercase().replace(" ", "")
            grouped.getOrPut(groupKey) { LinkedHashSet() }.add(canonical)
        }
        return grouped.values.map { it.toList() }
    }

    /**
     * 统一字符宽度与标点，并把连续空白折叠成一个空格、去掉首尾空白与零宽字符。
     *
     * 用逐字符折叠而不是 `Regex("\\s+")`：JVM 正则的 `\s` 默认只认 ASCII 空白，
     * 全角空格（U+3000）与不换行空格（U+00A0）都会漏掉，而这两个恰是站点文本里最常见的「看不见的差异」。
     */
    private fun foldTypography(raw: String): String {
        val builder = StringBuilder(raw.length)
        var pendingSpace = false
        for (original in raw) {
            if (isZeroWidth(original)) continue
            val folded = foldChar(original)
            if (folded.isWhitespace()) {
                if (builder.isNotEmpty()) pendingSpace = true
                continue
            }
            if (pendingSpace) {
                builder.append(' ')
                pendingSpace = false
            }
            builder.append(folded)
        }
        return builder.toString()
    }

    /**
     * 单个字符的宽度/标点折叠。
     *
     * 全角 ASCII（U+FF01..U+FF5E）与半角 ASCII 相差固定的 0xFEE0，直接按码位平移即可覆盖
     * ！＂＃…～ 全段；CJK 标点（、。「」【】・）不在该区段内，单独列出来，避免它们把名字切成
     * 两种写法却看不出差别。
     */
    private fun foldChar(ch: Char): Char {
        if (ch == '\u3000' || ch == '\u00A0') return ' '
        if (ch in '\uFF01'..'\uFF5E') return (ch.code - 0xFEE0).toChar()
        return when (ch) {
            '\u3001' -> ','          // 、
            '\u3002' -> '.'          // 。
            '\u300C', '\u300D',      // 「」
            '\u300E', '\u300F',      // 『』
            '\u201C', '\u201D' -> '"' // “”
            '\u3010' -> '['          // 【
            '\u3011' -> ']'          // 】
            '\u00B7', '\u30FB' -> ' ' // ·・：日文名常把它当分隔符，折叠成空格才能和普通空格写法同组
            '\u301C' -> '~'          // 〜
            '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> '-'
            else -> ch
        }
    }

    /**
     * 零宽/不可见格式字符。
     *
     * 从网页复制来的名字经常夹带这些字符，肉眼看不见却会让 `==` 失败。只列真正不可见的几个，
     * 不用「所有 Unicode Format 类字符」的宽泛规则，避免误删未来可能承载语义的字符。
     */
    private fun isZeroWidth(ch: Char): Boolean = when (ch) {
        '\u00AD', '\u200B', '\u200C', '\u200D', '\u200E', '\u200F', '\u2060', '\uFEFF' -> true
        else -> false
    }
}
