package com.example.localmovielibrarz.playback.engine

/**
 * 引擎切换的决策器（纯函数，无副作用，便于单元测试与逐条核对）。
 *
 * 切换是本任务「平滑切换、状态不丢失」的核心，因此把「能不能切」「切了要带什么状态」
 * 从 ViewModel 里抽出来单独表达，避免决策逻辑散落在 UI 事件里。
 *
 * 本文件不出现任何 `import android.*` / `import androidx.*`：只有这样才能把切换逻辑
 * 放进纯 JVM 单测里真实运行（见 EngineSwitchPlannerTest），而不是靠人眼读一遍。
 */
object EngineSwitchPlanner {

    /** 切换决策。 */
    sealed interface Decision {
        /** 允许切换，带上需要在两个引擎之间搬运的播放事实。 */
        data class Allowed(val handoff: Handoff) : Decision

        /** 拒绝切换，并给出面向用户的理由（必须显式告知，不允许静默忽略）。 */
        data class Rejected(val reason: String) : Decision
    }

    /**
     * 需要在引擎之间搬运的状态。
     *
     * 逐项对应「切换不丢状态」的验收要求：
     * - 进度（positionMs）：切换后从同一位置继续
     * - 播放/暂停（wasPlaying）：原本在播就继续播，原本暂停就仍然暂停
     * - 倍速（speed）
     * - 外挂字幕及其偏移（subtitle/offsetMs）：在线字幕不因换内核而丢失
     * - 音频轨索引（audioGroupIndex/audioTrackIndex）：exo 的 group/track 与 mpv 的 aid 语义不同，
     *   由 [audioHandoffHint] 给出可迁移的「第几条音轨」序号
     */
    data class Handoff(
        val positionMs: Long,
        val wasPlaying: Boolean,
        val speed: Float,
        val subtitleName: String?,
        val subtitleOffsetMs: Long,
        val audioHandoffHint: Int?,
        val from: PlaybackEngineKind,
        val to: PlaybackEngineKind
    )

    /**
     * 判断能否从 [from] 切到 [to]。
     *
     * @param targetAvailability 目标引擎的可用性探测结果
     * @param vrActive 当前是否处于 VR 球面播放（传 `VrMode.isVr`；用布尔而不是 VrMode 是为了
     *   让本文件与 Android 依赖解耦，从而可在纯 JVM 上测）
     * @param subtitleName 当前挂载的外挂字幕文件名
     */
    fun decide(
        from: PlaybackEngineKind,
        to: PlaybackEngineKind,
        current: PlaybackSnapshot,
        targetAvailability: EngineAvailability,
        vrActive: Boolean,
        subtitleName: String?,
        subtitleOffsetMs: Long,
        audioHandoffHint: Int?
    ): Decision {
        if (from == to) {
            return Decision.Rejected("当前已经在使用 ${to.label}")
        }
        if (!targetAvailability.available) {
            return Decision.Rejected(
                targetAvailability.reason ?: "${to.label} 当前不可用"
            )
        }
        // VR 球面播放依赖 media3 的 SphericalGLSurfaceView + CameraMotionListener + 帧元数据，
        // mpv 无法提供这套回调链（详见对齐表的「VR 球面播放」条目）。
        // 因为不能在 VR 下静默降级成 2D，这里直接回绝并说明原因。
        if (to == PlaybackEngineKind.MPV && vrActive) {
            return Decision.Rejected("VR 球面播放仅 ExoPlayer 支持，请先切回 2D 再切换内核")
        }

        return Decision.Allowed(
            Handoff(
                positionMs = current.positionMs.coerceAtLeast(0L),
                wasPlaying = current.isPlaying || current.playWhenReady,
                speed = current.speed.takeIf { it > 0f } ?: 1f,
                subtitleName = subtitleName,
                subtitleOffsetMs = subtitleOffsetMs,
                audioHandoffHint = audioHandoffHint,
                from = from,
                to = to
            )
        )
    }
}