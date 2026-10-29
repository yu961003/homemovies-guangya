package com.example.localmovielibrarz.tasks

/**
 * 账本中「一次任务运行」的不可变快照。
 *
 * 实体行（`task_runs` 表）由 Lead 侧的 Room Entity/DAO 持有；本类只承载**决策与展示需要的最小字段集**，
 * 刻意不依赖 Room/Android，因此可以直接在 JVM 单测里构造。这样做的好处是：
 * 恢复、去重、失联判定这些容易写错的逻辑都能脱离设备验证，不必等 instrumentation。
 *
 * 字段语义：
 * - [id]：run 记录主键。**一次尝试一条记录**；同一业务任务重跑会产生新 [id]，旧记录保留为历史。
 * - [kind]/[dedupeKey]：去重与分组依据。`dedupeKey` 由 `TaskRunPolicy.dedupeKeyOf` 规范化生成。
 * - [step]：当前步骤的可读描述（如「列举子文件夹」「写入第 128 个 STRM」），可为空。
 * - [completedSteps]/[totalSteps]：断点进度。`totalSteps <= 0` 表示总量未知（例如文件夹任务边扫边算）。
 * - [attempts]：含本次在内的累计尝试次数；续跑（Paused→Running）**不**自增，只有新 run 才从 1 重新计。
 * - [lastHeartbeatAtMillis]：活协程最近一次写心跳的墙钟时间；0 表示从未写过（旧数据或刚建记录）。
 * - [updatedAtMillis]：记录最近一次落库时间，是心跳缺失时的兜底判据。
 */
data class TaskRunSnapshot(
    val id: Long,
    val kind: TaskKind,
    val dedupeKey: String,
    val title: String,
    val state: TaskState,
    val step: String?,
    val completedSteps: Int,
    val totalSteps: Int,
    val attempts: Int,
    val failureMessage: String?,
    val lastHeartbeatAtMillis: Long,
    val updatedAtMillis: Long
) {
    /**
     * 进度比例，供进度条使用。
     *
     * 总量未知（[totalSteps] <= 0）时返回 0.0 而不是猜一个值：文件夹批量任务在列出目录前拿不到总数，
     * 此时画 0% 比画「99% 卡住」更诚实。其余情况钳制到 `0.0..1.0`，防止脏数据（完成数 > 总数、
     * 负数）把 Compose 的进度条画崩。
     */
    val progressFraction: Double
        get() {
            if (totalSteps <= 0) return 0.0
            val raw = completedSteps.toDouble() / totalSteps.toDouble()
            return raw.coerceIn(0.0, 1.0)
        }

    /**
     * 便捷失联判定：以当前墙钟与 [TaskRunPolicy.DEFAULT_STALL_THRESHOLD_MILLIS] 为参数。
     *
     * 仅供 UI（设置页/横幅）显示「疑似卡住」徽标；**任何决策逻辑都必须调用 [isStalledAt] 并显式传入时间**，
     * 否则单测会依赖真实时钟、恢复原因也会随执行时刻漂移。
     */
    val isStalled: Boolean
        get() = isStalledAt(System.currentTimeMillis(), TaskRunPolicy.DEFAULT_STALL_THRESHOLD_MILLIS)

    /**
     * 指定时刻是否失联（心跳超时）。
     *
     * 判定规则：
     * 1. 只有 [TaskState.Running] 会失联——Paused/Failed 本来就没协程，不应被算作「卡住」；
     * 2. 心跳时间戳优先取 [lastHeartbeatAtMillis]，为 0（从未写心跳）时退回 [updatedAtMillis]；
     * 3. 两个时间戳都不可用（<= 0）时视为未知，返回 `false`，宁可漏报也不误判可接管；
     * 4. 时间差严格**大于**阈值才算失联（等于阈值仍算活着）；
     * 5. 阈值 <= 0 视为配置异常，返回 `false`，避免把活任务误判成可接管而重复执行；
     * 6. 心跳时间在未来（用户改系统时间/时钟回拨）时时间差为负，不算失联。
     *
     * @param now 判定基准时间（毫秒墙钟），由调用方传入以保证可测。
     * @param stallThresholdMillis 允许的最大心跳间隔，通常取 [TaskRunPolicy.DEFAULT_STALL_THRESHOLD_MILLIS]。
     */
    fun isStalledAt(now: Long, stallThresholdMillis: Long): Boolean {
        if (state != TaskState.Running) return false
        if (stallThresholdMillis <= 0L) return false
        val lastSignalAt = if (lastHeartbeatAtMillis > 0L) lastHeartbeatAtMillis else updatedAtMillis
        if (lastSignalAt <= 0L) return false
        return now - lastSignalAt > stallThresholdMillis
    }
}
