package com.example.localmovielibrarz.playback

import java.util.Locale

/**
 * 侧挂字幕（sidecar subtitle）定位器：根据视频文件名从同目录文件名列表里挑出匹配的字幕。
 *
 * 为什么需要：
 * - 在线字幕源只覆盖有索引的影片；用户手动放在视频旁边的同名 .srt/.ass 往往是最准确的字幕，
 *   不该因为多了一层语言后缀（`.chs`、`.zh-CN`、`.简`）就被漏掉。
 * - 匹配规则必须严格：`ABC-123` 和 `ABC-1234` 是两个番号，用 startsWith 做模糊匹配会把
 *   隔壁影片的字幕挂到当前视频上——挂错字幕比没有字幕更糟，所以基名必须忽略大小写后完全相等。
 * - 本文件是纯函数：不碰 Android API、不做 IO，调用方把 `File.list()` 或 SAF 列出的文件名
 *   传进来即可，单测不需要 Robolectric。
 */
object SubtitleSidecarLocator {

    /** 支持的字幕扩展名（小写）。 */
    private val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub")

    /** 只覆盖特效/强制对白的字幕标签，排序时要放在最后一档。 */
    private const val FORCED_TAG = "forced"

    /**
     * 一条候选字幕与视频的匹配结果。
     *
     * @property candidate 候选文件名（已去掉目录前缀）。
     * @property baseName 视频去掉扩展名后的基名（保留视频文件名里的原始大小写）。
     * @property languageTag 推断出的语言/用途标签；推断不出为 null。
     * @property score 粗粒度匹配分，只用于展示或简单过滤；[rank] 不读取该字段。
     */
    data class SidecarMatch(
        val candidate: String,
        val baseName: String,
        val languageTag: String?,
        val score: Int
    )

    /**
     * 找出所有与 [videoFileName] 同名的侧挂字幕，并按 [rank] 排好序。
     *
     * 接受的形式（以 `ABC-123.mp4` 为例）：
     * `ABC-123.srt`、`ABC-123.chs.srt`、`ABC-123.zh-CN.ass`、`ABC-123.简.srt`、
     * `ABC-123.forced.vtt`（大小写不敏感；字幕扩展名之外的一律忽略）。
     *
     * 拒绝的形式：`ABC-1234.srt`（基名不等）、`ABC-123.srt.bak`（扩展名不是字幕）、
     * `ABC-123.txt`（不是字幕扩展名）。返回顺序已经排好，调用方可以直接取 first 作为默认字幕。
     */
    fun find(videoFileName: String, candidates: List<String>): List<SidecarMatch> {
        val videoName = videoFileName.fileNamePart()
        val videoBase = videoName.substringBeforeLast('.', videoName).trim()
        if (videoBase.isEmpty()) return emptyList()

        val matches = candidates.mapNotNull { raw ->
            val candidate = raw.fileNamePart()
            val extension = candidate.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (extension !in SUBTITLE_EXTENSIONS) return@mapNotNull null

            val stem = candidate.substringBeforeLast('.', "")
            if (stem.isEmpty() || !stem.matchesVideoBase(videoBase)) return@mapNotNull null

            val tags = stem.tokensAfter(videoBase).mapNotNull { token -> inferLanguageTag(token) }
            // 同时带语言和 forced 时优先保留语言：forced 只是「字幕不完整」的补充信息，
            // 而 SidecarMatch 只有一个标签位，语言对用户更有用。
            val languageTag = tags.firstOrNull { it != FORCED_TAG } ?: tags.firstOrNull()
            SidecarMatch(
                candidate = candidate,
                baseName = videoBase,
                languageTag = languageTag,
                score = scoreOf(languageTag)
            )
        }
        return rank(matches)
    }

    /**
     * 把单个 token 归一化成语言标签。
     *
     * 只认明确的别名，认不出就返回 null 让上层决定——猜错语言会让播放器加载错误的字幕轨，
     * 比「未标注语言」更糟。`forced` 单独成一档，表示只覆盖特效/强制对白。
     */
    fun inferLanguageTag(token: String): String? =
        when (token.trim().lowercase(Locale.ROOT)) {
            "chs", "chi", "zh", "zh-cn", "简", "简体", "中字" -> "zh"
            "cht", "繁", "繁体" -> "zh-Hant"
            "en", "eng", "英文" -> "en"
            "jp", "ja", "日", "日语" -> "ja"
            "forced", "强制" -> FORCED_TAG
            else -> null
        }

    /**
     * 对匹配结果排序（稳定排序，键相同的项保持入参顺序）。
     *
     * 优先级：
     * 1. 语言已知（zh / zh-Hant / en / ja）在最前；
     * 2. 语言未知（null）其次——用户手放的无语言标记字幕通常仍是全片翻译；
     * 3. 同一语言档内，forced（只覆盖特效/强制对白）排在同档其它字幕之后；
     * 4. 再按候选文件名（忽略大小写）字典序，保证不同设备上的顺序一致。
     *
     * forced 是一个独立维度而非一种语言：`ABC-123.chs.forced.ass` 的语言仍是 zh，只是内容
     * 不完整，所以它的 languageTag 记为 "zh"，靠 forced 标记把它压到同档最后。
     */
    fun rank(matches: List<SidecarMatch>): List<SidecarMatch> =
        matches.sortedWith(
            compareBy<SidecarMatch> { languageTier(it.languageTag) }
                .thenBy { if (it.isForcedSidecar()) 1 else 0 }
                .thenBy { it.candidate.lowercase(Locale.ROOT) }
        )

    /** 0 = 语言已知；1 = 语言未知或只有 forced 标记（forced 不是语言，不能排进语言档）。 */
    private fun languageTier(languageTag: String?): Int =
        if (languageTag == null || languageTag == FORCED_TAG) 1 else 0

    /** 从候选文件名的语言 token 里识别 forced 标记（SidecarMatch 只保留一个标签位，forced 单独判断）。 */
    private fun SidecarMatch.isForcedSidecar(): Boolean =
        candidate.fileNamePart()
            .substringBeforeLast('.', "")
            .tokensAfter(baseName)
            .any { inferLanguageTag(it) == FORCED_TAG }

    private fun scoreOf(languageTag: String?): Int = when {
        languageTag == null -> 100
        languageTag == FORCED_TAG -> 90
        else -> 120
    }

    /** 候选去扩展名后的 stem 是否以「基名 + '.'」开头（或与基名完全相等）。 */
    private fun String.matchesVideoBase(videoBase: String): Boolean {
        if (length < videoBase.length) return false
        if (!regionMatches(0, videoBase, 0, videoBase.length, ignoreCase = true)) return false
        if (length == videoBase.length) return true
        return this[videoBase.length] == '.'
    }

    /** 取出基名之后的语言 token（如 `ABC-123.chs.forced.srt` → ["chs", "forced"]）。 */
    private fun String.tokensAfter(videoBase: String): List<String> {
        // rank() 是公开函数，可能收到 baseName 与 candidate 不匹配的输入；这里退化为「没有 token」，
        // 而不是让 substring 越界抛异常——排序不应该因为脏数据整体失败。
        if (length <= videoBase.length) return emptyList()
        return substring(videoBase.length + 1).split('.').filter { it.isNotBlank() }
    }

    /** 去掉路径前缀，只保留文件名（调用方可能传绝对路径）。 */
    private fun String.fileNamePart(): String =
        substringAfterLast('/').substringAfterLast('\\')
}
