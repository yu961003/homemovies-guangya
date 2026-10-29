package com.example.localmovielibrarz.tasks

/**
 * 统一任务账本（`task_runs`）里「一次运行」的状态。
 *
 * 为什么要有这一层：鸭影原有三套任务各自维护 `Pending/Running/Completed/Failed` 等私有枚举，
 * 命名与语义互不一致（文件夹任务的 `Completed` 对应单视频任务的 `Completed`，但刮削任务的完成态叫
 * `Completed` 而失败态还混着 `None`）。账本必须只认一套状态，否则「某任务是第几次尝试、上次为什么中断、
 * 被谁占用、恢复到第几步」无法用同一条查询回答。该枚举就是这套唯一状态词汇。
 *
 * 迁移表（`canTransitionTo` 的完整规则；行 = 当前状态，列 = 目标状态）：
 *
 * | 当前 \ 目标 | Pending | Running | Paused | Succeeded | Failed | Cancelled |
 * |---|---|---|---|---|---|---|
 * | **Pending**   | ✅（幂等写回） | ✅ 开始执行 | ✅ 直接暂停 | ❌ | ❌ | ✅ 取消排队 |
 * | **Running**   | ❌ | ✅（幂等写回） | ✅ 让位 | ✅ 正常完成 | ✅ 执行失败 | ✅ 用户取消 |
 * | **Paused**    | ❌ | ✅ 续跑 | ✅（幂等写回） | ❌ | ✅ 续跑时确认失败 | ✅ 用户取消 |
 * | **Succeeded** | ❌ | ❌ | ❌ | ✅（幂等写回） | ❌ | ❌ |
 * | **Failed**    | ❌ | ❌ | ❌ | ❌ | ✅（幂等写回） | ❌ |
 * | **Cancelled** | ❌ | ❌ | ❌ | ❌ | ❌ | ✅（幂等写回） |
 *
 * 两条容易写错的边界：
 *
 * 1. **终态互斥且不可复活**。`Succeeded/Failed/Cancelled` 之间不能互转，也不能再回到 `Pending/Running/Paused`。
 *    重跑同一业务任务时**必须新建一条 run 记录**（新的 `attempts`），而不是把旧的 `Succeeded` 改回 `Running`；
 *    这样「第几次尝试、上一次为什么结束」才留得住。`Succeeded → Running` 在单条记录上永久禁止，
 *    账本层通过「新 run 记录的 `dedupeKey` 相同、`id` 不同」表达重跑。
 * 2. **`Running/Paused` 是占位态**（见 [blocksNewRun]）：占位期间不允许对同一 `dedupeKey` 再开新 run，
 *    否则会出现两条协程同时写同一批影片。`Pending` 不在占位集合内——它只是排队，尚未持有执行权；
 *    但「是否允许为同一 key 再插一条排队记录」由 `TaskRunPolicy.shouldStartNewRun` 决定，不在本枚举里表达。
 *
 * 自反写回（`X → X`）返回 `true`：进度与心跳落库会重复写当前状态，按幂等空操作放行，不算状态迁移。
 * 真正需要拦截的是「改变状态」的写入，调用方用 `from != to && !from.canTransitionTo(to)` 判断即可。
 */
enum class TaskState {
    /** 已入队，尚未拿到执行权（冷启动后仍有效，可直接被 worker 取走）。 */
    Pending,

    /** 正在执行：持有占位，存在活协程写心跳。 */
    Running,

    /**
     * 已让位：用户暂停，或进程重启/心跳超时被恢复逻辑降级。
     * 与 [Cancelled] 的区别是「还能续」，与 [Failed] 的区别是「没出错，只是没在跑」。
     */
    Paused,

    /** 正常完成。终态。 */
    Succeeded,

    /** 执行失败（有明确错误）。终态，但可被下一次新 run 重试。 */
    Failed,

    /** 用户主动取消。终态，语义上「不要再自动继续」，需要用户重新发起才会产生新 run。 */
    Cancelled;

    /** 终态：不会再自行变化，只能被新 run 记录取代。 */
    val isTerminal: Boolean
        get() = this == Succeeded || this == Failed || this == Cancelled

    /** 有活协程在跑（仅 [Running]）。恢复逻辑据此判断「重启后不可能还有同进程协程」。 */
    val isActive: Boolean
        get() = this == Running

    /**
     * 占位中：[Running] 与 [Paused]。
     *
     * 为什么 Paused 也算占位：暂停的任务持有断点（`completedSteps`/`step`）与同一 `dedupeKey`，
     * 此时再开新 run 会与断点续跑互相覆盖进度。用户想重来时应先把旧记录 [Cancelled] 掉。
     */
    val blocksNewRun: Boolean
        get() = this == Running || this == Paused

    /**
     * 状态机判定：当前状态能否迁移到 [next]。
     *
     * 规则见类 KDoc 的迁移表；同状态写回视为幂等空操作放行。终态不可复活，重跑请新建 run 记录。
     */
    fun canTransitionTo(next: TaskState): Boolean = when (this) {
        // 排队中：可以被 worker 取走、被用户暂停，或直接被取消。
        Pending -> next == Pending || next == Running || next == Paused || next == Cancelled

        // 执行中：让位、完成、失败、取消。不能凭空回到排队，避免「已跑一半又变回未开始」。
        Running -> next == Running || next == Paused || next == Succeeded || next == Failed || next == Cancelled

        // 暂停中：只允许续跑、确认失败、取消。不允许暂停态直接宣告成功（没跑完不能算成功）。
        Paused -> next == Paused || next == Running || next == Failed || next == Cancelled

        // 终态：只允许写回自身，任何状态变化都禁止。
        Succeeded, Failed, Cancelled -> next == this
    }

    companion object {
        /**
         * 宽松解析持久化字符串：去空白、忽略大小写；空串或未知值返回 `null` 而不是抛异常。
         *
         * 为什么不抛：状态列由历史数据、迁移与外部导入共同写入，一条脏数据不应让整个账本读取失败。
         */
        fun parse(raw: String?): TaskState? {
            val normalized = raw?.trim().orEmpty()
            if (normalized.isEmpty()) return null
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        }

        /**
         * 「还没收尾、需要账本/用户继续处理」的状态名，供 DAO 做 `WHERE state IN (:names)`。
         *
         * 口径与既有 `ScrapeTaskStatus.unfinishedNames` 保持一致：Pending / Running / Paused / Failed。
         * 含 [Failed] 的原因不是把它当「进行中」，而是失败需要被设置页暴露、被用户重试——
         * 它虽然不会再自行变化，但业务上仍是一件没做完的事。真正「已经不需要再管」的只有
         * [Succeeded]（完成）与 [Cancelled]（用户主动结束）。
         * 需要严格「非终态」集合时请自行用 [isTerminal] 过滤，不要修改本列表的语义。
         */
        val unfinishedNames: List<String> = listOf(
            Pending.name,
            Running.name,
            Paused.name,
            Failed.name
        )

        /**
         * 「正在执行」的状态名，与实例属性 [isActive] 严格一致（只有 [Running]）。
         *
         * 需要「占位中」的查询（Running + Paused）请用 `blocksNewRun` 过滤，不要复用本列表：
         * 占位与执行是两件事，混用会让「运行中」的横幅把暂停任务也数进去。
         */
        val activeNames: List<String> = listOf(Running.name)
    }
}
