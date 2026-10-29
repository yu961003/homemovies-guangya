package com.example.localmovielibrarz.metadata

/**
 * 决策动作。
 *
 * [NeedsReview] 不是失败：它表示「这个值想覆盖用户手改的内容，先别写，等人确认」。
 * 接线层应把候选值和快照落到待审表，而不是直接更新 `movies`。
 *
 * 声明在顶层而不是塞进 [MetadataMergeEngine]：持久化门面（如 `MovieMetadataLedgerRepository`）
 * 的返回类型就是 `MetadataDecision`，顶层类型让调用方只 import 一个名字，无需再穿过引擎对象。
 */
enum class Action { Apply, KeepCurrent, NeedsReview }

/**
 * 一次决策的结果。
 *
 * @property value [Action.Apply] 时是待写入的新值；[Action.KeepCurrent] 时是保留下来的旧值；
 *   [Action.NeedsReview] 时是**建议**的新值，供待审界面展示「将改成什么」。
 *   调用方不应当在 NeedsReview 时直接写库，否则等同于自动覆盖了手改值。
 */
data class MetadataDecision(val action: Action, val reason: String, val value: String?)

/**
 * 字段级合并决策器：纯函数、无 Android 依赖，输入输出都只是值对象。
 *
 * 为什么要有它：现有 `scrapeWithFallback()` 是「某个来源成功就整条 `ScrapedMovieInfo` 覆盖写库」，
 * 后一个来源即使只补了一个字段，也会把前一个来源填好的字段冲成空值；用户手改同样没有保护。
 * 把规则从「谁最后跑完」改为「谁更可信 + 空位能不能被填」，多源回退才能互补而不是互相破坏。
 *
 * 纯函数还有实际收益：整条决策链可以在 JVM 单测里穷举，不需要设备、网络或数据库。
 */
object MetadataMergeEngine {

    /**
     * 决策原因常量。
     *
     * 日志、待审表和界面都会按 reason 分支，暴露成常量可以避免调用点手写字符串拼错；
     * 单测直接断言字面量，把对外契约钉死在这里。
     */
    object Reasons {
        /** 候选本身是空值 / 占位词，什么都不用做。 */
        const val INCOMING_BLANK = "incoming_blank"

        /** 该字段当前没有记录，直接采用候选。 */
        const val NO_CURRENT = "no_current"

        /** 当前值来自用户手改，自动来源只能送审。 */
        const val MANUAL_PROTECTED = "manual_protected"

        /** 候选来源的权威等级严格更高，允许覆盖。 */
        const val HIGHER_AUTHORITY = "higher_authority"

        /** 同等级且当前为空，允许用候选填空。 */
        const val SAME_AUTHORITY_FILL_BLANK = "same_authority_fill_blank"

        /** 同等级且当前已有值，没有足够理由替换。 */
        const val SAME_AUTHORITY_NO_GAIN = "same_authority_no_gain"

        /** 候选权威更低，但当前为空，低权威也总比空着强。 */
        const val FILL_BLANK_LOWER_AUTHORITY = "fill_blank_lower_authority"

        /** 候选权威更低且当前已有值，保留旧值。 */
        const val LOWER_AUTHORITY = "lower_authority"
    }

    /**
     * 决策一条候选是否应当写入。
     *
     * 规则顺序是刻意固定的，顺序本身表达优先级：
     * 1. 候选为空 → 保留现状。空值没有任何信息量，先短路可以避免后续规则把「空」当成一次覆盖。
     * 2. 当前无记录 → 直接用候选。没有旧值就没有冲突。
     * 3. 当前是用户手改、候选不是 → 送审。手改不受自动覆盖，但仍把候选留给人工判断，
     *    避免用户永远看不到「站点其实提供了更好的值」。
     * 4. 候选等级严格更高 → 覆盖。
     * 5. 同等级 → 仅当当前为空时填空（同等级的「有值」没有高低之分，替换只会造成来回抖动）。
     * 6. 候选等级更低 → 仅当当前为空时填空（低权威数据也比空着有用），否则保留。
     *
     * 注意第 3 条排在第 4 条之前：自动来源的候选即使等级更高（例如 LocalNfo 对 Provider 手改值），
     * 也只会送审而不会覆盖；Manual 对 Manual 不命中第 3 条，落到第 5 条，当前为空时仍可填空，
     * 因为那是用户自己在继续编辑。另外，当前值虽是 Manual 但为空（用户手动清空了字段），
     * 同样走第 3 条送审而不是自动回填——清空是用户的明确决定，自动来源不应把它填回去。
     */
    fun decide(current: MetadataCandidate?, incoming: MetadataCandidate): MetadataDecision {
        if (incoming.isBlank) {
            return MetadataDecision(Action.KeepCurrent, Reasons.INCOMING_BLANK, current?.value)
        }
        if (current == null) {
            return MetadataDecision(Action.Apply, Reasons.NO_CURRENT, incoming.value)
        }
        if (current.authority == MetadataAuthority.Manual && incoming.authority != MetadataAuthority.Manual) {
            return MetadataDecision(Action.NeedsReview, Reasons.MANUAL_PROTECTED, incoming.value)
        }
        if (incoming.authority.canOverride(current.authority)) {
            return MetadataDecision(Action.Apply, Reasons.HIGHER_AUTHORITY, incoming.value)
        }
        if (incoming.authority == current.authority) {
            return if (current.isBlank) {
                MetadataDecision(Action.Apply, Reasons.SAME_AUTHORITY_FILL_BLANK, incoming.value)
            } else {
                MetadataDecision(Action.KeepCurrent, Reasons.SAME_AUTHORITY_NO_GAIN, current.value)
            }
        }
        // 走到这里只剩「候选权威严格更低」：只配填空，不配替换。
        return if (current.isBlank) {
            MetadataDecision(Action.Apply, Reasons.FILL_BLANK_LOWER_AUTHORITY, incoming.value)
        } else {
            MetadataDecision(Action.KeepCurrent, Reasons.LOWER_AUTHORITY, current.value)
        }
    }

    /**
     * 批量决策：每个字段从候选里挑出「最值得考虑」的一条，再走 [decide]。
     *
     * 同一字段有多条候选时，按 authority rank 降序、再按 confidence 降序取第一条；
     * 两项都相同时保留输入顺序中的第一条（Kotlin 的 sortedWith 是稳定排序），
     * 这样同一批候选无论重跑多少次结果都一致。
     *
     * 选择「等级最高的一条」而不是「非空且等级最高的一条」是刻意的：最高权威来源明确给出空值，
     * 说明它对这个字段没有把握，不应该让更弱的来源顶上来把值写进去；需要「任意非空即可」的调用方
     * 应先在传入前过滤空候选，而不是让引擎在排序里偷偷加一条例外。
     *
     * 返回的 Map 只包含 incoming 里出现过的字段；current 里存在但本轮没有候选的字段不会出现在结果里
     * （它们本来就不需要任何动作），调用方据此遍历即可，不必逐字段判空。
     */
    fun mergeAll(
        current: Map<MetadataFieldKey, MetadataCandidate>,
        incoming: List<MetadataCandidate>
    ): Map<MetadataFieldKey, MetadataDecision> {
        val rankedByField = incoming.groupBy { it.field }
        val decisions = LinkedHashMap<MetadataFieldKey, MetadataDecision>(rankedByField.size)
        for ((field, candidates) in rankedByField) {
            val best = candidates
                .sortedWith(
                    compareByDescending<MetadataCandidate> { it.authority.rank }
                        .thenByDescending { it.confidence }
                )
                .first()
            decisions[field] = decide(current[field], best)
        }
        return decisions
    }
}
