package com.example.localmovielibrarz.metadata

/**
 * 「元数据补全」任务要写回影片表的一组候选值。
 *
 * 为什么单独搞一个 patch 类型，而不是直接把 `ScrapedMovieInfo` 丢给仓储：
 * 1. `ScrapedMovieInfo` 是刮削层的输出，带着来源、原始 URL、图片地址等**不该进影片表**的东西；
 *    补全任务只需要其中「本来就该是纯文本/数值」的那十几项。
 * 2. 补全的语义是**只填空位**，所以 patch 必须能表达「这一项我没打算动」——
 *    用 null / 空列表表示「不提供」，而不是「清空」。这样调用方不必先读出整行再逐字段比对。
 * 3. 有了 patch，字段决策（要不要覆盖手改值）与真正落库可以分开：决策在任务侧用
 *    [MetadataMergeEngine] 做，落库只是一次 `copy()`。两者分开才好测。
 *
 * 刻意**不含** posterUri / fanartUri / thumbUri：这三个字段在影片表里存的是**本地文件 Uri**，
 * 不是站点 URL。补全任务不下载图片（那是刮削/重新刮削的职责），把 URL 写进 Uri 列会让
 * 详情页拿到一个无法打开的文件路径，比留空更糟。
 */
data class MetadataGapPatch(
    val title: String? = null,
    val originalTitle: String? = null,
    val plot: String? = null,
    val year: Int? = null,
    val premiered: String? = null,
    val runtimeMinutes: Int? = null,
    val studios: List<String> = emptyList(),
    val series: String? = null,
    val directors: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val rating: Double? = null
) {

    /**
     * 这条 patch 是否**一项都没带**。
     *
     * 调用方用它做短路：来源返回了成功但字段全空（站点把信息藏在图片里之类）时，
     * 不该为「什么都没写」这件事去打一行「补全成功」的日志，更不该产生一次空写库。
     */
    fun isEmpty(): Boolean = candidates().isEmpty()

    /**
     * 把 patch 摊平成「字段 → 待写入值」。
     *
     * 多值字段按 [MetadataFieldKey.isMultiValue] 的约定用 `\u001F` 串接：
     * 决策层只认字符串（它不该知道影片表的列类型），落库时再拆回列表。
     */
    fun candidates(): Map<MetadataFieldKey, String> = buildMap {
        putIfNotBlank(MetadataFieldKey.Title, title)
        putIfNotBlank(MetadataFieldKey.OriginalTitle, originalTitle)
        putIfNotBlank(MetadataFieldKey.Plot, plot)
        putIfNotBlank(MetadataFieldKey.Year, year?.toString())
        putIfNotBlank(MetadataFieldKey.Premiered, premiered)
        putIfNotBlank(MetadataFieldKey.Runtime, runtimeMinutes?.toString())
        putList(MetadataFieldKey.Studios, studios)
        putIfNotBlank(MetadataFieldKey.Series, series)
        putList(MetadataFieldKey.Directors, directors)
        putList(MetadataFieldKey.Actors, actors)
        putList(MetadataFieldKey.Genres, genres)
        putList(MetadataFieldKey.Tags, tags)
        putIfNotBlank(MetadataFieldKey.Rating, rating?.toString())
    }

    private fun MutableMap<MetadataFieldKey, String>.putIfNotBlank(
        field: MetadataFieldKey,
        value: String?
    ) {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isNotEmpty()) put(field, trimmed)
    }

    private fun MutableMap<MetadataFieldKey, String>.putList(
        field: MetadataFieldKey,
        values: List<String>
    ) {
        val cleaned = values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isNotEmpty()) put(field, cleaned.joinToString(MULTI_VALUE_SEPARATOR))
    }

    companion object {
        /** 与 `MovieTypeConverters` 对齐的多值分隔符（U+001F，单元分隔符）。 */
        const val MULTI_VALUE_SEPARATOR: String = "\u001F"
    }
}

/**
 * 一条缺口补全的结论，供任务层汇总「补了几个字段、哪些字段被手改保护挡住」。
 *
 * 之所以把两者分开计数：被 [Action.KeepCurrent] / [Action.NeedsReview] 挡下不是失败——
 * 它恰恰说明用户的编辑历史起了作用。混进「失败数」会让用户以为补全没跑通。
 */
data class MetadataGapFillResult(
    val appliedFields: List<MetadataFieldKey>,
    val protectedFields: List<MetadataFieldKey>
) {
    val appliedCount: Int get() = appliedFields.size
    val protectedCount: Int get() = protectedFields.size
}
