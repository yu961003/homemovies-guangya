package com.example.localmovielibrarz.playback.engine

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ExoPlayer 的引擎适配器。
 *
 * **它不重新创建播放器**：ExoPlayer 实例仍由 [com.example.localmovielibrarz.ui.player.PlayerViewModel.createPlayer]
 * 原样构建（软/硬解策略、LoadControl、SeekParameters、诊断监听全部保留），
 * 这里只负责：
 * 1. 把 media3 的回调翻译成引擎无关的 [PlaybackSnapshot]；
 * 2. 把引擎无关的控制指令翻译回 media3 调用。
 *
 * 这样「零改动接入」与「统一 UI 状态」两个目标同时成立：exo 侧行为不变，
 * 双引擎却能共用同一套 UI 状态与切换逻辑。
 */
@OptIn(UnstableApi::class)
class ExoPlaybackEngine(
    val player: ExoPlayer
) : PlaybackEngine {

    override val kind: PlaybackEngineKind = PlaybackEngineKind.EXO

    private val _snapshot = MutableStateFlow(readSnapshot())
    override val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    private var externalSubtitle: ExternalSubtitle? = null
    private var subtitleOffsetMs: Long = 0L

    /** 播放器已结束/出错时置位，切换引擎前用于判断是否需要保存进度。 */
    var onPlaybackEnded: (() -> Unit)? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
        override fun onPlaybackStateChanged(playbackState: Int) = publish()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publish()
        override fun onPlayerError(error: PlaybackException) = publish()
        // media3 1.9.1 的 Player.Listener 只有一个单参回调 onVolumeChanged(volume: Float)，
        // 没有携带 muted 的重载；音量变化只需重读快照，静音态走 setVolume 后同一路径刷新。
        override fun onVolumeChanged(volume: Float) = publish()
        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = publish()
    }

    init {
        player.addListener(listener)
    }

    override fun play() = player.play()

    override fun pause() = player.pause()

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        player.playWhenReady = playWhenReady
    }

    override fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
        publish()
    }

    override fun seekBy(deltaMs: Long) {
        if (deltaMs >= 0) player.seekForward() else player.seekBack()
        publish()
    }

    override fun setSpeed(speed: Float): Float {
        player.setPlaybackSpeed(speed)
        publish()
        return speed
    }

    override fun load(
        uri: String,
        headers: Map<String, String>,
        startPositionMs: Long,
        autoPlay: Boolean
    ) {
        // exo 的装载由 ViewModel.createPlayer / setMediaItem 完成（需要 PlaybackRequest、
        // LoadControl、字幕 MediaItem 等本项目私有上下文），引擎层不重复实现，只做占位断言。
        Log.w(TAG, "ExoPlaybackEngine.load 由 ViewModel 负责，此处忽略：$uri")
    }

    override fun setExternalSubtitle(subtitle: ExternalSubtitle, offsetMs: Long) {
        externalSubtitle = subtitle
        subtitleOffsetMs = offsetMs
    }

    override fun clearExternalSubtitle() {
        externalSubtitle = null
        subtitleOffsetMs = 0L
    }

    override fun setSubtitleOffset(offsetMs: Long) {
        subtitleOffsetMs = offsetMs
    }

    override fun setSubtitleStyle(style: SubtitleStyle) {
        // exo 侧样式通过 PlayerView.subtitleView.setStyle 生效，UI 层已直接处理。
    }

    override fun setSubtitleEnabled(enabled: Boolean) {
        // exo 侧通过 setMediaItem(带/不带字幕) 切换，UI 层已直接处理。
    }

    override fun selectAudioTrack(trackId: Int?) {
        val builder = player.trackSelectionParameters.buildUpon()
        if (trackId == null) {
            builder.clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_AUDIO)
        } else {
            builder.setOverrideForType(
                androidx.media3.common.TrackSelectionOverride(
                    player.currentTracks.groups[trackId].mediaTrackGroup,
                    0
                )
            )
        }
        player.trackSelectionParameters = builder.build()
    }

    override fun release() {
        player.removeListener(listener)
    }

    /** 主动刷新一次快照（seek 后 media3 不会立刻回调，UI 需要即时反馈）。 */
    fun publish() {
        _snapshot.value = readSnapshot()
    }

    private fun readSnapshot(): PlaybackSnapshot {
        val state = when (player.playbackState) {
            Player.STATE_IDLE -> PlaybackSnapshot.STATE_IDLE
            Player.STATE_BUFFERING -> PlaybackSnapshot.STATE_BUFFERING
            Player.STATE_READY -> PlaybackSnapshot.STATE_READY
            Player.STATE_ENDED -> PlaybackSnapshot.STATE_ENDED
            else -> PlaybackSnapshot.STATE_IDLE
        }
        val videoSize = player.videoSize
        return PlaybackSnapshot(
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            playbackState = state,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.coerceAtLeast(0L),
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
            speed = player.playbackParameters.speed,
            videoWidth = videoSize.width,
            videoHeight = videoSize.height,
            errorMessage = player.playerError?.message
        )
    }

    private companion object {
        const val TAG = "ExoPlaybackEngine"
    }
}
