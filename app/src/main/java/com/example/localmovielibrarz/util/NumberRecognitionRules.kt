package com.example.localmovielibrarz.util

object NumberRecognitionRules {
    /**
     * 番号识别规则的默认清单（内置真实值）。
     *
     * 用户可在「设置 → 番号识别规则」中增删条目，由 updateXxx 方法与下述默认值合并生效。
     */
    // 敏感后缀：识别到此后缀时跳过该文件
    val DEFAULT_IGNORED_SUFFIXES = setOf("HHB")
    // 分段标记：识别分段（如 CD1/CD2/RESTORED 等）
    // ★ 第九轮：UC 双字母组合纳入默认标记（-U/-C 单字母走 letterPart 机制，无需配置）
    val DEFAULT_PART_MARKERS = setOf("RESTORED", "UC")
    /** ★ 第九轮（问题 5）：分段标记的展示名称（详情页/筛选页渲染 partLabel 时查询；仅整值匹配生效）。 */
    val DEFAULT_PART_MARKER_LABELS = mapOf(
        "U" to "无码破解",
        "C" to "中文字幕",
        "UC" to "破解中文",
        "RESTORED" to "无码破解"
    )
    // 连写字母分段前缀：此类前缀的番号支持单字母后缀分段（如 NHDTC-1A → NHDTC-1-A）
    val DEFAULT_ATTACHED_LETTER_SEGMENT_PREFIXES = setOf("NHDTC")
    // 番号前缀别名：key 为规范化前缀，value 为带数字的搜索前缀
    val DEFAULT_NUMERIC_PREFIX_ALIASES = mapOf(
        "SHN" to "116SHN",
        "GANA" to "200GANA",
        "SCUTE" to "229SCUTE",
        "LUXU" to "259LUXU",
        "ARA" to "261ARA",
        "DCV" to "277DCV",
        "EWDX" to "299EWDX",
        "MAAN" to "300MAAN",
        "MIUM" to "300MIUM",
        "NTK" to "300NTK",
        "KIRAY" to "314KIRAY",
        "KJO" to "326KJO",
        "NAMA" to "332NAMA",
        "KNB" to "336KNB",
        "SIMM" to "345SIMM",
        "NTR" to "348NTR",
        "JAC" to "390JAC",
        "KIWVR" to "408KIWVR",
        "INST" to "413INST",
        "SRYA" to "417SRYA",
        "SUKE" to "428SUKE",
        "MFC" to "435MFC",
        "HHH" to "451HHH",
        "TEN" to "459TEN",
        "MLA" to "476MLA",
        "SGK" to "483SGK",
        "GCB" to "485GCB",
        "SEI" to "502SEI",
        "STCV" to "529STCV",
        "MY" to "292MY",
        "DANDY" to "104DANDY",
        "ICHK" to "368ICHK"
    )

    @Volatile
    private var ignoredSuffixes: Set<String> = DEFAULT_IGNORED_SUFFIXES
    @Volatile
    private var partMarkers: Set<String> = DEFAULT_PART_MARKERS
    @Volatile
    private var numericPrefixAliases: Map<String, String> = DEFAULT_NUMERIC_PREFIX_ALIASES
    @Volatile
    private var attachedLetterPrefixes: Set<String> = DEFAULT_ATTACHED_LETTER_SEGMENT_PREFIXES

    fun ignoredSuffixes(): Set<String> = ignoredSuffixes

    fun partMarkers(): Set<String> = partMarkers

    /** ★ 第九轮（问题 5）：查分段标记的语义标签（U=无码破解等）；未命中返回 null 保持原始 token。 */
    fun partMarkerLabel(marker: String?): String? =
        DEFAULT_PART_MARKER_LABELS[marker?.trim()?.uppercase()]

    fun attachedLetterPrefixes(): Set<String> = attachedLetterPrefixes

    fun updateAttachedLetterPrefixes(value: Set<String>) {
        // 需求 9：连写字母分段前缀列表化，合并默认值后归一化
        attachedLetterPrefixes = (DEFAULT_ATTACHED_LETTER_SEGMENT_PREFIXES + value)
            .mapNotNull { it.normalizedRuleTokenOrNull() }
            .toSet()
    }

    fun updateIgnoredSuffixes(value: Set<String>) {
        ignoredSuffixes = normalizeIgnoredSuffixes(DEFAULT_IGNORED_SUFFIXES + value)
    }

    fun updatePartMarkers(value: Set<String>) {
        partMarkers = normalizeRuleTokens(DEFAULT_PART_MARKERS + value)
    }

    fun updateNumericPrefixAliases(value: Map<String, String>) {
        numericPrefixAliases = (DEFAULT_NUMERIC_PREFIX_ALIASES + value).mapNotNull { (prefix, searchPrefix) ->
            val canonical = prefix.normalizedPrefixOrNull() ?: return@mapNotNull null
            val search = searchPrefix.normalizedPrefixOrNull() ?: return@mapNotNull null
            canonical to search
        }.toMap()
    }

    fun canonicalizePrefix(value: String): String {
        val normalized = value.normalizedPrefixOrNull() ?: return value.uppercase()
        return numericPrefixAliases.entries
            .firstOrNull { (_, searchPrefix) -> searchPrefix == normalized }
            ?.key
            ?: normalized
    }

    fun numericPrefixAliasFor(value: String): String? {
        val normalized = value.normalizedPrefixOrNull() ?: return null
        numericPrefixAliases[normalized]
            ?.takeIf { it.any(Char::isDigit) }
            ?.let { return it }
        return numericPrefixAliases.entries
            .firstOrNull { (_, searchPrefix) ->
                searchPrefix == normalized && searchPrefix.any(Char::isDigit)
            }
            ?.value
    }

    fun isAttachedLetterSegment(prefix: String, suffix: String): Boolean {
        val normalizedSuffix = suffix.trim().uppercase().filter(Char::isLetter)
        if (normalizedSuffix.length != 1) return false
        return canonicalizePrefix(prefix) in attachedLetterPrefixes
    }

    fun normalizeIgnoredSuffixes(value: Iterable<String>): Set<String> =
        normalizeRuleTokens(value)

    fun normalizePartMarkers(value: Iterable<String>): Set<String> =
        normalizeRuleTokens(value)

    fun stripIgnoredSuffix(baseName: String, suffixes: Set<String> = ignoredSuffixes()): IgnoredSuffixStripResult {
        val markerTokens = normalizePartMarkers(partMarkers())
        val normalizedSuffixes = suffixes
            .mapNotNull { it.normalizedIgnoredSuffixOrNull() }
            .filterNot { it in markerTokens }
            .sortedByDescending { it.length }
        val match = normalizedSuffixes.firstOrNull { suffix ->
            baseName.endsWith(suffix, ignoreCase = true) &&
                baseName.length > suffix.length &&
                baseName.dropLast(suffix.length).any { it.isDigit() }
        }
        return if (match == null) {
            IgnoredSuffixStripResult(baseName = baseName, stripped = false)
        } else {
            IgnoredSuffixStripResult(
                baseName = baseName.dropLast(match.length),
                stripped = true
            )
        }
    }

    fun normalizeDigits(prefix: String, digits: String, hasExplicitSeparator: Boolean, strippedIgnoredSuffix: Boolean): String {
        if (canonicalizePrefix(prefix).contains("VR") || digits.length <= 3 || !digits.startsWith("0")) {
            return digits
        }
        return digits.trimStart('0').ifBlank { "0" }.padStart(3, '0')
    }

    fun partAfterMarker(suffix: String, markers: Set<String> = partMarkers()): String? {
        val normalizedMarkers = normalizePartMarkers(markers)
        if (normalizedMarkers.isEmpty()) return null
        val tokens = suffix.split(Regex("[._\\s-]+"))
            .mapNotNull { it.normalizedLooseTokenOrNull() }
        for (index in tokens.indices) {
            val token = tokens[index]
            if (token !in normalizedMarkers) continue
            val next = tokens.getOrNull(index + 1)
            if (next == null) return token
            if (next.length == 1 && next.first() in 'A'..'Z') return "$token-$next"
            Regex("""(?:PART|P)?0*([1-9][0-9]?)""")
                .matchEntire(next)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?.let { return "$token-P$it" }
            return token
        }
        return null
    }

    fun String.normalizedIgnoredSuffixOrNull(): String? =
        normalizedRuleTokenOrNull()

    private fun normalizeRuleTokens(value: Iterable<String>): Set<String> =
        value.mapNotNull { it.normalizedRuleTokenOrNull() }.toSet()

    private fun String.normalizedRuleTokenOrNull(): String? =
        trim()
            .uppercase()
            .filter { it.isLetterOrDigit() }
            .takeIf { it.isNotBlank() && it.any { char -> char.isLetter() } }

    private fun String.normalizedLooseTokenOrNull(): String? =
        trim()
            .uppercase()
            .filter { it.isLetterOrDigit() }
            .takeIf { it.isNotBlank() }

    private fun String.normalizedPrefixOrNull(): String? =
        trim()
            .uppercase()
            .filter { it.isLetterOrDigit() }
            .takeIf { it.isNotBlank() && it.any(Char::isLetter) }
}

data class IgnoredSuffixStripResult(
    val baseName: String,
    val stripped: Boolean
)
