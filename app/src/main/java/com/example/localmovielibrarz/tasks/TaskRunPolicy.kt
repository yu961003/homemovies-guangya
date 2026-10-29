package com.example.localmovielibrarz.tasks

/**
 * 统一任务账本的**决策策略**：纯函数集合，没有任何 Android / Room / 协程依赖。
 *
 * 为什么坚持纯函数：
 * - 恢复、去重、失联判定是账本里最容易写错、也最难在真机上复现的三段逻辑；
 *   把它们收敛成「输入快照 + 显式时间 → 输出决策」后，可以在 JVM 单测里穷举边界（时钟、阈值、终态）。
 * - 时间一律由参数传入（`now` / `stallThresholdMillis`），函数内部不读系统时钟，
 *   同一组输入永远得到同一结果，避免「凌晨跑测试绿、晚上跑测试红」的时钟依赖。
 *
 * 本对象只做**判定**，不落库、不改状态：真正的写库由 Lead 侧的协调器按返回的
 * [Decision] / [RecoveryAction] 执行，因此策略可以被单独审计与替换。
 */
object TaskRunPolicy {
    /**
     * 默认失联阈值：10 分钟没有心跳即视为「协程疑似卡死」。
     *
     * 取值依据：文件夹批量任务在大目录列举 / 刮削单部影片时会出现分钟级停顿，
     * 阈值过短（如 1 分钟）会把正常慢操作误判成可接管、导致两条协程同时写同一批影片；
     * 阈值过长（如 1 小时）则用户看到的任务会长时间「假运行」。
     */
    const val DEFAULT_STALL_THRESHOLD_MILLIS: Long = 10L * 60L * 1000L

    private const val DEFAULT_SCOPE = "default"

    /**
     * 生成规范化去重键：`"${kind.name}:${scope.trim().lowercase()}"`，scope 为空时用 `"default"`。
     *
     * 为什么必须规范化：同一部影片在不同入口会被写成 `Pickcode:ABC123` / `pickcode: abc123 `，
     * 若原样入库，账本会认为这是两条不同任务，于是「同一任务被两个协程同时跑」。
     * 统一小写 + 去首尾空白后，去重键才真正起到唯一约束的作用。
     *
     * 注意：`scope` 只做首尾去空白与大小写归一，内部空白与分隔符保持原样——
     * 路径/番号里的空格和冒号属于业务内容，压缩它们会制造新的碰撞。
     */
    fun dedupeKeyOf(kind: TaskKind, scope: String): String {
        val normalizedScope = scope.trim().lowercase()
        val effectiveScope = normalizedScope.ifEmpty { DEFAULT_SCOPE }
        return "${kind.name}:$effectiveScope"
    }

    /**
     * 判断「是否允许为同一 `dedupeKey` 新建一条 run 记录」。
     *
     * @param activeRun 该去重键下最近的一条 run 快照；没有历史记录时为 `null`。
     * @param requestedState 调用方打算以哪个状态开跑。正常只应是 [TaskState.Pending]（先入队）
     *   或 [TaskState.Running]（立即执行）；[TaskState.Paused] 与终态都不是合法的开跑状态，
     *   传错时直接拒绝而不是猜一个默认值。
     * @param now 判定基准时间（毫秒墙钟），显式传入以便单测。
     * @param stallThresholdMillis 失联阈值，默认 [DEFAULT_STALL_THRESHOLD_MILLIS]。
     *
     * 判定顺序（顺序本身是语义的一部分）：
     * 1. 无历史记录 → 允许，理由 `no_active_run`；
     * 2. 已处于终态 → 允许，理由 `previous_run_finished`（重跑走新 run 记录，旧记录留档）；
     * 3. **失联接管必须先于占位拦截**：Running 属于 [TaskState.blocksNewRun]，
     *    若先判占位，卡死的任务将永远无法被接管；因此先看 `isStalledAt`，
     *    超时 → 允许，理由 `take_over_stalled`；
     * 4. Running/Paused 且未失联 → 拒绝，理由 `active_run_in_progress`（同键已有主，避免并发写）；
     * 5. 仅剩 Pending（已排队但没开始）：同样拒绝 `active_run_in_progress`，
     *    因为此时应复用那条排队记录，再插一条只会让同一业务任务被执行两次。
     */
    fun shouldStartNewRun(
        activeRun: TaskRunSnapshot?,
        requestedState: TaskState = TaskState.Pending,
        now: Long = System.currentTimeMillis(),
        stallThresholdMillis: Long = DEFAULT_STALL_THRESHOLD_MILLIS
    ): Decision {
        // 防御非法开跑状态：状态机里 Paused/终态都不是「开始」的入口，静默纠正会掩盖调用方 bug。
        if (requestedState != TaskState.Pending && requestedState != TaskState.Running) {
            return Decision(false, "invalid_requested_state")
        }
        if (activeRun == null) return Decision(true, "no_active_run")
        if (activeRun.state.isTerminal) return Decision(true, "previous_run_finished")
        if (activeRun.isStalledAt(now, stallThresholdMillis)) {
            return Decision(true, "take_over_stalled")
        }
        // Running / Paused / Pending 都占着同一个去重键；Paused 是断点续跑的唯一入口，不能被新 run 顶掉。
        return Decision(false, "active_run_in_progress")
    }

    /**
     * 冷启动恢复：把进程重启前残留的 [TaskState.Running] 记录降级为 [TaskState.Paused]。
     *
     * 关键设计：**中断不是失败**。进程被杀、系统回收、用户强停之后，协程已不可能还活着，
     * 但任务本身通常可续（文件夹任务有 `processedPickcodesCsv` 断点，单视频任务幂等，刮削任务可重试）。
     * 因此这里只降级到 Paused，绝不写 Failed——写成 Failed 会让设置页误报错误、也会让自动续跑不敢碰。
     *
     * 两类 reason 的差别只用于日志与可观测性，目标状态相同：
     * - `interrupted_stalled`：心跳已超过 [DEFAULT_STALL_THRESHOLD_MILLIS]，重启前就疑似卡死；
     * - `interrupted_restart`：心跳还新鲜，但跨进程不可能存在同进程协程，一律按重启中断处理。
     *
     * [TaskState.Pending] 不进返回值：它本来就该在重启后继续排队，无需动作。
     * 终态与 Paused 也跳过；对同一批输入重复调用是幂等的（Paused 不会再产生 action）。
     *
     * @param runs 待恢复的账本快照集合，通常来自 `state = Running` 的查询。
     * @param now 判定基准时间（毫秒墙钟）。
     * @return 与 [runs] 同序的迁移动作列表，调用方逐条落库即可。
     */
    fun recoverInterrupted(runs: List<TaskRunSnapshot>, now: Long): List<RecoveryAction> {
        val actions = ArrayList<RecoveryAction>(runs.size)
        runs.forEach { run ->
            if (run.state != TaskState.Running) return@forEach
            val reason = if (run.isStalledAt(now, DEFAULT_STALL_THRESHOLD_MILLIS)) {
                "interrupted_stalled"
            } else {
                "interrupted_restart"
            }
            actions += RecoveryAction(
                runId = run.id,
                from = TaskState.Running,
                to = TaskState.Paused,
                reason = reason
            )
        }
        return actions
    }

    /**
     * 汇总账本概况，供设置页与首页横幅显示一行状态。
     *
     * 口径说明：
     * - [TaskRunSummary.active] 只数 [TaskState.Running]；[TaskRunSummary.stalled] 是它的子集
     *   （Running 且心跳超时），因此 `stalled <= active` 恒成立；
     * - [TaskState.Pending]/[TaskState.Cancelled] 没有独立计数（前者只是排队，后者是用户主动结束，
     *   都不需要在「有多少事在跑/需要处理」的横幅里出现）；
     * - 本函数**不去重**：传入的应当是「每个 `dedupeKey` 的最新一条 run」，
     *   否则历史 run 会被重复计数。去重是 DAO 查询的职责（`ORDER BY updatedAt DESC` 取首条）。
     *
     * @param runs 待汇总的快照集合。
     * @param now 判定失联的基准时间（毫秒墙钟）。
     */
    fun summarize(runs: List<TaskRunSnapshot>, now: Long): TaskRunSummary {
        var active = 0
        var stalled = 0
        var paused = 0
        var failed = 0
        var succeeded = 0
        runs.forEach { run ->
            when (run.state) {
                TaskState.Running -> {
                    active += 1
                    if (run.isStalledAt(now, DEFAULT_STALL_THRESHOLD_MILLIS)) stalled += 1
                }
                TaskState.Paused -> paused += 1
                TaskState.Failed -> failed += 1
                TaskState.Succeeded -> succeeded += 1
                TaskState.Pending, TaskState.Cancelled -> Unit
            }
        }
        return TaskRunSummary(
            active = active,
            stalled = stalled,
            paused = paused,
            failed = failed,
            succeeded = succeeded
        )
    }

    /**
     * 「是否允许开新 run」的判定结果。
     *
     * [reason] 是稳定的机器可读原因码（不翻译、不拼参数），UI 文案由调用方按原因映射；
     * 这样策略层不需要认识中文，新增语言也不必改本对象。
     */
    data class Decision(
        val allowed: Boolean,
        val reason: String
    )

    /**
     * 一条冷启动恢复动作：把 [runId] 从 [from] 改到 [to]，[reason] 记录降级原因。
     *
     * 表达为「动作列表」而不是直接改数据，是为了让协调器能在同一个事务里落库并写一条运行日志，
     * 也让单测可以断言「谁被改了、为什么」而不必启动 Room。
     */
    data class RecoveryAction(
        val runId: Long,
        val from: TaskState,
        val to: TaskState,
        val reason: String
    )

    /**
     * 账本概况计数。字段口径见 [summarize]。
     */
    data class TaskRunSummary(
        val active: Int,
        val stalled: Int,
        val paused: Int,
        val failed: Int,
        val succeeded: Int
    )
}
