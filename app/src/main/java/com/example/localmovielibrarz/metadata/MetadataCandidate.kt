package com.example.localmovielibrarz.metadata

/**
 * 某个字段的一次「候选写入」提案。
 *
 * 为什么不用裸值：合并引擎要同时看到值、谁写的、可信度，才能决定覆盖 / 保留 / 送审。
 * 只传 `String` 时这些信息在调用点就丢了，事后无法解释「为什么最后是这个值」，
 * 也无法在用户质疑时回答「谁把它改掉的」。
 *
 * [updatedAtMillis] 不参与优先级比较（优先级只看 [authority] 与 [confidence]），
 * 它只用于审计展示和「同一来源新旧值」的排查；把它排除在排序之外，是为了让合并结果可复现，
 * 不受两次刮削的机器时钟差异影响。
 */
data class MetadataCandidate(
    val field: MetadataFieldKey,
    val value: String?,
    val authority: MetadataAuthority,
    /** 来源的可读标签（站点名、文件名、`local.nfo` 等），用于日志与待审界面。 */
    val sourceLabel: String,
    /** 该来源对这条值的把握程度，仅在同等级来源之间比较；默认 1.0 表示「不确定，但也不打折」。 */
    val confidence: Double = 1.0,
    /** 提案产生时间（epoch millis），只用于审计与展示。 */
    val updatedAtMillis: Long = 0L
) {
    /**
     * 是否等价于「没有值」。
     *
     * 只判断 null / 空白不够：站点缺字段时经常回填 `N/A`、`未知`、`-` 这类占位词，
     * 它们不是数据，却会让后续更可靠的来源在「已有值就不覆盖」的规则下被永久挡住，
     * 或者在界面上被当成真的写了 N/A 展示给用户。等号比较忽略大小写与首尾空白，
     * 因此 `n/a`、` N/A ` 也算空。
     */
    val isBlank: Boolean
        get() {
            val text = value?.trim() ?: return true
            return text.isEmpty() || text.lowercase() in BLANK_TOKENS
        }

    private companion object {
        /** 站点缺字段时的常见占位词；统一小写存放，比较前先 lowercase。 */
        val BLANK_TOKENS = setOf("n/a", "未知", "-")
    }
}
