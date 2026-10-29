package com.example.localmovielibrarz.cloudguangya

import java.net.URLDecoder

/**
 * 磁力链接/离线下载链接提取（移植自 guangya-txt-cloud-add 油猴脚本）。
 *
 * 支持格式：magnet:?xt=urn:btih:...、40 位 hex、32 位 base32、.torrent URL、ed2k 链接。
 * 逐行解析，去重，忽略 # / // 开头的注释行。
 * 每行支持提取多个链接（findAll），解决手动粘贴时同行多链接只识别首个的问题。
 *
 * H 轮修复（ed2k 批量提交失败）：
 * - ed2k 支持 AICH 段（`|h=...|/`）、来源段（`|/|sources,...|/`）、百分号编码、缺少结尾 `|/` 的变体；
 *   此前这些链接要么被整条丢弃，要么被裸 hash 规则误造成假磁力链（提交后服务端报「BT 文件不存在」）。
 * - 一行只要识别到真实链接（magnet / torrent / ed2k），就不再对该行做「裸 hash → 磁力」猜测。
 * - 去重哈希对 ed2k 归一化为 `ed2k:<32位hash>`，使同一资源的不同写法（带/不带 AICH、来源）能正确去重。
 */
object GuangyaMagnetExtractor {
    fun extractMagnets(text: String): List<String> {
        val found = LinkedHashSet<String>()
        text.lineSequence().forEach { line ->
            val raw = line.trim()
            if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("//")) return@forEach
            // 整行被百分号编码时（ed2k%3A%2F%2F%7Cfile%7C...）先解码，否则任何规则都匹配不到
            val s = decodeIfEncoded(raw)

            // 优先提取所有 magnet 链接（一行可能含多个）
            val magnets = MAGNET_RE.findAll(s).toList()
            // .torrent URL（一行可能含多个，用 findAll）
            val torrents = TORRENT_URL_RE.findAll(s).toList()
            // ed2k：先按完整形态（含 AICH / sources 段）匹配，匹配不到再退回宽松形态
            val ed2kFull = ED2K_RE.findAll(s).toList()
            val ed2kLoose = if (ed2kFull.isEmpty()) ED2K_LOOSE_RE.findAll(s).toList() else emptyList()

            magnets.forEach { found.add(it.value) }
            torrents.forEach { found.add(it.value) }
            ed2kFull.forEach { found.add(normalizeEd2k(it.value)) }
            ed2kLoose.forEach { found.add(normalizeEd2k(it.value)) }

            // ★ 本行已识别出真实链接 → 不再把行内裸 hash 猜成磁力（避免 ed2k 行的 32 位 hash 变成假 BT）
            if (magnets.isNotEmpty() || torrents.isNotEmpty() || ed2kFull.isNotEmpty() || ed2kLoose.isNotEmpty()) {
                return@forEach
            }

            // 纯 40 位 hex（一行可能含多个）
            HASH40_RE.findAll(s).forEach { found.add("magnet:?xt=urn:btih:${it.value}") }

            // 纯 32 位 base32（一行可能含多个）
            HASH32_RE.findAll(s).forEach { found.add("magnet:?xt=urn:btih:${it.value}") }
        }
        return found.toList()
    }

    private val MAGNET_RE = Regex("""magnet:\?xt=urn:btih:[0-9a-fA-F]{32,40}""", RegexOption.IGNORE_CASE)
    private val HASH40_RE = Regex("""\b[0-9a-fA-F]{40}\b""")
    private val HASH32_RE = Regex("""\b[A-Z2-7]{32}\b""")
    private val TORRENT_URL_RE = Regex("""https?://\S+\.torrent(?:[?#]\S*)?""", RegexOption.IGNORE_CASE)

    /**
     * 标准形态 + AICH 段（`|h=...|/`，AICH 根哈希是 base32，不能按 hex 匹配）+ 来源段（`|/|sources,...|/`）。
     */
    private val ED2K_RE = Regex(
        """ed2k://\|file\|[^|]*\|\d+\|[0-9a-fA-F]{32}\|(?:h=[^|]{8,64}\|)?/(?:\|sources,[^|]*\|/)?""",
        RegexOption.IGNORE_CASE
    )

    /** 宽松形态：无结尾 |/（部分站点导出会截断），或尾部还有未覆盖的段。 */
    private val ED2K_LOOSE_RE = Regex("""ed2k://\|file\|[^|]*\|\d+\|[0-9a-fA-F]{32}""", RegexOption.IGNORE_CASE)

    /** ed2k 的 32 位 MD4 哈希（用于归一化去重）。 */
    private val ED2K_HASH_RE = Regex("""ed2k://\|file\|[^|]*\|\d+\|([0-9a-fA-F]{32})""", RegexOption.IGNORE_CASE)

    /** 提取 urn:btih: 后的 info-hash（32-40 字符，用于去重记忆）。 */
    private val BTIH_RE = Regex("""urn:btih:([0-9a-fA-F]{32,40})""", RegexOption.IGNORE_CASE)

    /**
     * 「已提交成功」记忆的键：主键为 [magnetHash]，ed2k 额外带旧版本遗留键 `raw:<整条链接>`。
     * 兼容旧记忆，避免升级后把以前添加过的电驴链接又提交一遍（云端会出现重复任务）。
     */
    fun submittedKeys(link: String): List<String> {
        val hash = magnetHash(link)
        if (!hash.startsWith("ed2k:")) return listOf(hash)
        val legacy = "raw:${link.trim()}"
        return if (legacy == hash) listOf(hash) else listOf(hash, legacy)
    }

    /**
     * 「失败黑名单」的键：只用新键。
     * 旧版本的电驴失败项是以整条链接（raw:…）记的黑名单；协议修复后应当允许它们重试，
     * 所以这里刻意不回读旧键（只回读「成功」记忆即可避免重复）。
     */
    fun failedKeys(link: String): List<String> = listOf(magnetHash(link))

    /** 规范 ed2k：保证以 `|/` 收尾（缺则补），去掉尾部多余空白。 */
    private fun normalizeEd2k(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.endsWith("|/")) trimmed else "$trimmed|/"
    }

    /** 整行百分号编码时解码（ed2k 链接从网页复制常带编码）。解码失败则保持原样。 */
    private fun decodeIfEncoded(value: String): String {
        val looksEncoded = value.contains("ed2k%3A", ignoreCase = true) ||
            value.contains("%7Cfile%7C", ignoreCase = true)
        if (!looksEncoded) return value
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    /**
     * 从磁力链提取稳定哈希（info_hash），用于记忆去重 / 黑名单（移植油猴脚本 magnetHash）。
     * - magnet:?xt=urn:btih:HASH → HASH 大写
     * - ed2k → "ed2k:<32位hash 大写>"
     * - 纯 40hex / 32base32 → 原值（hex 大写）
     * - .torrent URL → "url:xxx"
     * - 其它 → "raw:xxx"
     */
    fun magnetHash(magnet: String): String {
        val s = magnet.trim()
        ED2K_HASH_RE.find(s)?.let { return "ed2k:${it.groupValues[1].uppercase()}" }
        BTIH_RE.find(s)?.let { return it.groupValues[1].uppercase() }
        HASH40_RE.find(s)?.let { return it.value.uppercase() }
        HASH32_RE.find(s)?.let { return it.value }
        if (s.startsWith("http://") || s.startsWith("https://")) return "url:$s"
        return "raw:$s"
    }
}
