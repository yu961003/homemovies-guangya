package com.example.localmovielibrarz.playback.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内核切换决策的回归测试。
 *
 * 这些用例逐条对应交付说明里的「切换逻辑」验收点：拒绝必须有理由、
 * 允许时进度/倍速/字幕/偏移必须原样搬运，避免切换后状态丢失。
 */
class EngineSwitchPlannerTest {

    private fun availability(kind: PlaybackEngineKind, available: Boolean = true, reason: String? = null) =
        EngineAvailability(kind = kind, available = available, reason = reason)

    private fun snapshot(
        positionMs: Long = 93_000L,
        isPlaying: Boolean = true,
        playWhenReady: Boolean = true,
        speed: Float = 1.5f
    ) = PlaybackSnapshot(
        isPlaying = isPlaying,
        playWhenReady = playWhenReady,
        positionMs = positionMs,
        speed = speed
    )

    @Test
    fun `目标内核与当前相同时拒绝并说明原因`() {
        val decision = EngineSwitchPlanner.decide(
            from = PlaybackEngineKind.EXO,
            to = PlaybackEngineKind.EXO,
            current = snapshot(),
            targetAvailability = availability(PlaybackEngineKind.EXO),
            vrActive = false,
            subtitleName = null,
            subtitleOffsetMs = 0L,
            audioHandoffHint = null
        )

        assertTrue(decision is EngineSwitchPlanner.Decision.Rejected)
        assertTrue((decision as EngineSwitchPlanner.Decision.Rejected).reason.contains("已经在使用"))
    }

    @Test
    fun `目标内核不可用时拒绝并透传探测原因`() {
        val decision = EngineSwitchPlanner.decide(
            from = PlaybackEngineKind.EXO,
            to = PlaybackEngineKind.MPV,
            current = snapshot(),
            targetAvailability = availability(
                PlaybackEngineKind.MPV,
                available = false,
                reason = "libmpv.so 未打包进 APK"
            ),
            vrActive = false,
            subtitleName = null,
            subtitleOffsetMs = 0L,
            audioHandoffHint = null
        )

        assertTrue(decision is EngineSwitchPlanner.Decision.Rejected)
        assertEquals(
            "libmpv.so 未打包进 APK",
            (decision as EngineSwitchPlanner.Decision.Rejected).reason
        )
    }

    @Test
    fun `VR 模式下切到 mpv 被拒绝`() {
        val decision = EngineSwitchPlanner.decide(
            from = PlaybackEngineKind.EXO,
            to = PlaybackEngineKind.MPV,
            current = snapshot(),
            targetAvailability = availability(PlaybackEngineKind.MPV),
            vrActive = true,
            subtitleName = "movie.srt",
            subtitleOffsetMs = 1_200L,
            audioHandoffHint = 0
        )

        assertTrue(decision is EngineSwitchPlanner.Decision.Rejected)
        assertTrue((decision as EngineSwitchPlanner.Decision.Rejected).reason.contains("VR"))
    }

    @Test
    fun `从 mpv 切回 exo 在 VR 模式下允许`() {
        // 反方向不受限制：exo 具备 VR 能力，用户从 mpv 的 2D 状态切回 exo 后可以再开 VR。
        val decision = EngineSwitchPlanner.decide(
            from = PlaybackEngineKind.MPV,
            to = PlaybackEngineKind.EXO,
            current = snapshot(),
            targetAvailability = availability(PlaybackEngineKind.EXO),
            vrActive = true,
            subtitleName = null,
            subtitleOffsetMs = 0L,
            audioHandoffHint = null
        )

        assertTrue(decision is EngineSwitchPlanner.Decision.Allowed)
    }

    @Test
    fun `允许切换时进度倍速字幕偏移与音轨全部搬运`() {
        val decision = EngineSwitchPlanner.decide(
            from = PlaybackEngineKind.EXO,
            to = PlaybackEngineKind.MPV,
            current = snapshot(positionMs = 754_321L, speed = 1.25f),
            targetAvailability = availability(PlaybackEngineKind.MPV),
            vrActive = false,
            subtitleName = "SSIS-001.chs.srt",
            subtitleOffsetMs = 2_500L,
            audioHandoffHint = 1
        )

        val handoff = (decision as EngineSwitchPlanner.Decision.Allowed).handoff
        assertEquals(754_321L, handoff.positionMs)
        assertTrue(handoff.wasPlaying)
        assertEquals(1.25f, handoff.speed, 0.0001f)
        assertEquals("SSIS-001.chs.srt", handoff.subtitleName)
        assertEquals(2_500L, handoff.subtitleOffsetMs)
        assertEquals(1, handoff.audioHandoffHint)
        assertEquals(PlaybackEngineKind.EXO, handoff.from)
        assertEquals(PlaybackEngineKind.MPV, handoff.to)
    }

    @Test
    fun `暂停状态下切换后仍然暂停`() {
        val decision = EngineSwitchPlanner.decide(
            from = PlaybackEngineKind.EXO,
            to = PlaybackEngineKind.MPV,
            current = snapshot(isPlaying = false, playWhenReady = false),
            targetAvailability = availability(PlaybackEngineKind.MPV),
            vrActive = false,
            subtitleName = null,
            subtitleOffsetMs = 0L,
            audioHandoffHint = null
        )

        val handoff = (decision as EngineSwitchPlanner.Decision.Allowed).handoff
        assertTrue(!handoff.wasPlaying)
    }

    @Test
    fun `负进度与非正倍速被归一到安全值`() {
        // 引擎在 seek 边界上可能吐回负值；倍速 0 会让 mpv 静音般停帧。
        val decision = EngineSwitchPlanner.decide(
            from = PlaybackEngineKind.EXO,
            to = PlaybackEngineKind.MPV,
            current = snapshot(positionMs = -1L, speed = 0f),
            targetAvailability = availability(PlaybackEngineKind.MPV),
            vrActive = false,
            subtitleName = null,
            subtitleOffsetMs = 0L,
            audioHandoffHint = null
        )

        val handoff = (decision as EngineSwitchPlanner.Decision.Allowed).handoff
        assertEquals(0L, handoff.positionMs)
        assertEquals(1f, handoff.speed, 0.0001f)
    }

    @Test
    fun `目标引擎可用但 reason 为空时不再额外编造理由`() {
        val decision = EngineSwitchPlanner.decide(
            from = PlaybackEngineKind.MPV,
            to = PlaybackEngineKind.EXO,
            current = snapshot(),
            targetAvailability = availability(PlaybackEngineKind.EXO, available = false),
            vrActive = false,
            subtitleName = null,
            subtitleOffsetMs = 0L,
            audioHandoffHint = null
        )

        val reason = (decision as EngineSwitchPlanner.Decision.Rejected).reason
        assertTrue(reason.contains("不可用"))
    }
}
