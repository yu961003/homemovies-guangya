package com.example.localmovielibrarz.ui.player

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import com.example.localmovielibrarz.data.repository.GuangyaDirectLinkResolver
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.Format
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.SeekParameters
import com.example.localmovielibrarz.cloud115.Cloud115Client
import com.example.localmovielibrarz.data.repository.CloudStrmRecordRepository
import com.example.localmovielibrarz.data.repository.DirectLinkRepository
import com.example.localmovielibrarz.data.repository.AppSettingsRepository
import com.example.localmovielibrarz.data.repository.PlaybackProgressRepository
import com.example.localmovielibrarz.data.repository.MovieRepository
import com.example.localmovielibrarz.data.repository.UsageStatsRepository
import com.example.localmovielibrarz.diagnostics.RuntimeErrorLog
import com.example.localmovielibrarz.playback.DEFAULT_USER_AGENT
import com.example.localmovielibrarz.playback.PickcodeExtractor
import com.example.localmovielibrarz.playback.PlaybackFailureClassifier
import com.example.localmovielibrarz.playback.PlaybackRequest
import com.example.localmovielibrarz.playback.PlaybackResolver
import com.example.localmovielibrarz.playback.ROUTE_GUANGYA_RES
import com.example.localmovielibrarz.playback.ROUTE_GUANGYA_VOD
import com.example.localmovielibrarz.playback.engine.EngineAvailability
import com.example.localmovielibrarz.playback.engine.EngineSwitchPlanner
import com.example.localmovielibrarz.playback.engine.ExternalSubtitle
import com.example.localmovielibrarz.playback.engine.MpvHwdecMode
import com.example.localmovielibrarz.playback.engine.MpvPlaybackEngine.MpvAudioTrack
import com.example.localmovielibrarz.playback.engine.MpvPlaybackEngine
import com.example.localmovielibrarz.playback.engine.PlaybackEngineFactory
import com.example.localmovielibrarz.playback.engine.PlaybackEngineKind
import com.example.localmovielibrarz.playback.engine.PlaybackSnapshot
import com.example.localmovielibrarz.playback.engine.SubtitleStyle
import com.example.localmovielibrarz.playback.engine.VideoResizeMode
import com.example.localmovielibrarz.playback.lut.LutAutoAnalysis
import com.example.localmovielibrarz.playback.lut.LutAutoAnalyzer
import com.example.localmovielibrarz.playback.lut.LutSettings
import com.example.localmovielibrarz.playback.vr.VrControlMode
import com.example.localmovielibrarz.playback.vr.VrMode
import com.example.localmovielibrarz.playback.vr.VrModeSettings
import com.example.localmovielibrarz.subtitle.AvsubtitlesCloudflareException
import com.example.localmovielibrarz.subtitle.AvsubtitlesSubtitleRepository
import com.example.localmovielibrarz.subtitle.Cloud115SubtitleRepository
import com.example.localmovielibrarz.subtitle.LocalSubtitleStore
import com.example.localmovielibrarz.subtitle.LocalSubtitleFile
import com.example.localmovielibrarz.subtitle.SubtitleSearchProvider
import com.example.localmovielibrarz.subtitle.SubtitleSearchResult
import com.example.localmovielibrarz.subtitle.SubtitleTimeShiftStore
import com.example.localmovielibrarz.subtitle.XunleiSubtitleRepository
import com.example.localmovielibrarz.subtitle.normalizeCloud115SubtitleNumber
import com.example.localmovielibrarz.subtitle.normalizeDefaultSubtitleNumber
import com.example.localmovielibrarz.subtitle.normalizeXunleiSubtitleNumber
import com.example.localmovielibrarz.util.normalizeMovieNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.math.abs
import com.example.localmovielibrarz.util.VideoFormats

class PlayerViewModel(
    private val application: Application,
    private val videoUri: Uri,
    title: String,
    private val fileName: String,
    directLinkRepository: DirectLinkRepository,
    guangyaDirectLinkResolver: GuangyaDirectLinkResolver,
    cloud115Client: Cloud115Client,
    private val cloudStrmRecordRepository: CloudStrmRecordRepository,
    private val settingsRepository: AppSettingsRepository,
    private val playbackProgressRepository: PlaybackProgressRepository,
    private val movieRepository: MovieRepository,
    private val movieId: Long,
    private val usageStatsRepository: UsageStatsRepository
) : AndroidViewModel(application) {
    private val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f)
    private var speedIndex = 1
    private val resolver = PlaybackResolver(application.contentResolver, directLinkRepository, guangyaDirectLinkResolver)
    private val localSubtitleStore = LocalSubtitleStore(application, settingsRepository)
    private val subtitleTimeShiftStore = SubtitleTimeShiftStore(application)
    private val avsubtitlesSubtitleRepository = AvsubtitlesSubtitleRepository(application, settingsRepository)
    private val xunleiSubtitleRepository = XunleiSubtitleRepository(application, settingsRepository)
    private val cloud115SubtitleRepository = Cloud115SubtitleRepository(application, settingsRepository, cloud115Client)
    private val errorLog = RuntimeErrorLog(application)
    private val subtitleSearchCache = mutableMapOf<String, CachedSubtitleSearch>()
    private val playbackMediaKey = videoUri.toString()
    private var mediaKey = playbackMediaKey
    private var subtitleStorageSourceUri: Uri? = null
    private var activeExternalSubtitle: LocalSubtitleFile? = null
    private val progressPersistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var progressJob: Job? = null
    private var dropFrameJob: Job? = null
    private var initialSubtitleJob: Job? = null
    private var subtitleOffsetApplyJob: Job? = null
    private var lastPersistedPositionMs = Long.MIN_VALUE
    private var lastPersistedDurationMs = 0L
    private var lastPersistedAtMs = 0L
    private var retriedAfterForbidden = false
    private var hasMarkedWatched = false
    private var playbackStartedAt = 0L
    private var latestTracks: Tracks? = null   // 缓存最近一次 onTracksChanged，供其他回调记录音视频格式
    // 解码器名：由 queryActualDecoders() 从 ExoPlayer 渲染器实例直接查询，
    // 区分硬解（含 MediaCodec 名如 c2.qti.avc.decoder）与 FFmpeg 软解（含渲染器类名）。
    private var latestVideoDecoder: String? = null
    private var latestAudioDecoder: String? = null
    private val vrModeSettings = VrModeSettings(application)

    // ───────────────────────────────────────────────────────────────────────
    // 双引擎运行态（见 README-MPV 与对齐表）：
    // - engineKind 决定当前由谁出画面，UI 据此选择 PlayerView / MpvVideoView；
    // - mpvEngine 只在 mpv 内核激活期间非空，退到 exo 后立刻释放，避免两套解码器同时占内存；
    // - mpvSnapshotJob 只观察「当前这一个」mpv 实例，切换时先取消再重订阅。
    // ───────────────────────────────────────────────────────────────────────
    private val mpvEngineLock = Any()

    @Volatile
    private var mpvEngine: MpvPlaybackEngine? = null

    private var mpvSnapshotJob: Job? = null
    private var mpvProgressJob: Job? = null
    private var engineSwitchJob: Job? = null

    /**
     * 实时调色设置的镜像。
     *
     * 为什么在 ViewModel 里存一份而不是每次现读 `settingsRepository`：新建 mpv 实例时
     * 要立刻下发一次 LUT（否则开头几秒是原色），而 SharedPreferences 的读虽然快、
     * 却在主线程上有磁盘 IO 风险；这里由协程持续订阅并缓存，下发时是纯内存读。
     */
    @Volatile
    private var currentLutSettings: LutSettings = LutSettings()

    /** 订阅 LUT 设置变化的协程。整个 ViewModel 生命周期只此一个。 */
    private var lutObserverJob: Job? = null

    /** 正在跑的自动分析。重复点击时用它挡住；退出页面时取消。 */
    private var lutAutoTuneJob: Job? = null

    /**
     * 当前画面比例模式在 UI 列表里的索引（0=FIT / 1=FILL / 2=ZOOM）。
     *
     * exo 侧这个状态完全由 `PlayerView.resizeMode` 承载，ViewModel 不必知道；
     * 但 mpv 侧没有对等属性，新建实例时必须重新下发 panscan/keepaspect，
     * 所以这里记一份，只服务于 mpv 实例的重建。
     */
    private var currentResizeIndex: Int = 0

    /**
     * 把 UI 的比例模式应用到当前内核。
     *
     * exo 由 Compose 侧的 `PlayerView.resizeMode` 直接生效，这里只需处理 mpv；
     * 保留统一入口是为了让 PlayerScreen 不必关心当前是哪个内核。
     */
    fun applyResizeMode(index: Int) {
        currentResizeIndex = index
        mpvEngine?.setVideoResizeMode(VideoResizeMode.fromIndex(index))
    }

    // 重缓冲统计：累计次数、总时长、上次进入 BUFFERING 的时间戳
    private var rebufferCount = 0
    private var rebufferTotalMs = 0L
    private var rebufferStartedAt = 0L
    private var lastRecoveryMs = 0L   // 上次重缓冲恢复耗时
    private var lastReadyAt = 0L      // 上次进入 READY 的时间（用于计算 time-to-rebuffer）
    private var lastOverloadLogMs = 0L // 上次记录过载事件的时间（防刷屏）
    // P0 掉帧监控状态（2026-09-11 掉帧报告）：seek/flush 基线重置、起播/seek 恢复期保护、
    // 正确分母（skipped 不计入）、连续窗口才判定真过载。状态提为成员，seek 路径可写入保护期。
    private var lastDropped = -1
    private var lastRendered = -1
    private var lastPositionMs = -1L
    private var highDropStreak = 0
    private var suppressStatsUntilMs = 0L
    private var playbackStartTimeMs = 0L

    val title: String = title.ifBlank { "Movie" }

    private val _uiState = MutableStateFlow(
        PlayerUiState(
            isLoading = true,
            loadingMessage = initialPlaybackLoadingMessage(),
            externalSubtitleStyle = externalSubtitleStyleSettings(),
            progressBarStyle = progressBarStyleSettings(),
            vrMode = vrModeSettings.getMode(mediaKey),
            vrControlMode = vrModeSettings.getControlMode(mediaKey)
        )
    )
    val uiState: StateFlow<PlayerUiState> = _uiState

    val playbackSpeed: Float
        get() = speeds[speedIndex]

    val playbackSpeeds: List<Float>
        get() = speeds

    init {
        // 解码档位是持久化偏好，面板的选中态第一帧就要对，先同步进状态。
        _uiState.update { it.copy(mpvHwdecMode = settingsRepository.getMpvHwdecMode()) }
        startLutObserver()
        viewModelScope.launch {
            val startedAtMs = SystemClock.elapsedRealtime()
            showLoading(initialPlaybackLoadingMessage())
            resolvePlayback()
                .onSuccess { request ->
                    val resolveElapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                    if (resolveElapsedMs >= SLOW_PLAYBACK_RESOLVE_LOG_MS) {
                        logPlaybackStage("player.resolve.slow", resolveElapsedMs)
                    }
                    showLoading("正在启动播放器...")
                    val subtitleFeaturesEnabled = canUseLibrarySubtitleFeatures(request)
                    val resumePositionMs = playbackProgressRepository.getResumePosition(playbackMediaKey)
                    val mpvStarted = startInitialMpvSession(request, resumePositionMs, subtitleFeaturesEnabled)
                    if (!mpvStarted) {
                        val player = createPlayer(request, resumePositionMs)
                        _uiState.update {
                            it.copy(
                                playbackRequest = request,
                                player = player,
                                isLoading = false,
                                subtitleFeaturesEnabled = subtitleFeaturesEnabled,
                                externalSubtitleStyle = externalSubtitleStyleSettings(),
                                progressBarStyle = progressBarStyleSettings(),
                                vrMode = vrModeSettings.getMode(mediaKey),
                                vrControlMode = vrModeSettings.getControlMode(mediaKey),
                                engineKind = PlaybackEngineKind.EXO
                            )
                        }
                    }
                    if (subtitleFeaturesEnabled) {
                        loadInitialExternalSubtitles(request)
                    }
                }
                .onFailure { error ->
                    logPlaybackStage(
                        event = "player.resolve.failed",
                        elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
                        error = error
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            externalSubtitleStyle = externalSubtitleStyleSettings(),
                            progressBarStyle = progressBarStyleSettings(),
                            errorMessage = playbackResolveErrorMessage(error)
                        )
                    }
                }
        }
    }

    @OptIn(UnstableApi::class)  // 新增注解：setAllowCrossProtocolRedirects / DefaultLoadControl / setApproximateSeekEnabled 等 API 标记为 UnstableApi
    private fun createPlayer(request: PlaybackRequest, resumePositionMs: Long, subtitle: LocalSubtitleFile? = null): ExoPlayer {
        val userAgent = request.userAgent ?: DEFAULT_USER_AGENT
        val headers = buildMap {
            putAll(request.headers)
            if (request.mediaUri.startsWith("http", ignoreCase = true)) {
                put("User-Agent", userAgent)
            }
        }
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)    // 新增：允许跨协议重定向
        val dataSourceFactory = DefaultDataSource.Factory(application, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        // P1 seek 优化（2026-09-11 掉帧报告）：收紧缓冲水位，减少 seek 后重建缓冲的时间基错位窗口
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 60_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000
            )
            .build()

        // 解码器策略：统一硬解优先。
        // v5 移除 nextlib 后，extension 软解由 DefaultRenderersFactory 原生解码 + mpv 核心兜底（§5.4）。
        // 需求 1：固定 EXTENSION_RENDERER_MODE_PREFER（硬解优先）+ 解码器回退，不再提供用户可切换的解码方式。
        logPlaybackEvent(
            "player.decoderMode",
            mapOf("mode" to "auto", "renderers" to "DefaultRenderersFactory")
        )

        // 双核心 v5：移除 nextlib，使用原生渲染器 + 解码器回退（§5.4）
        val renderersFactory = DefaultRenderersFactory(application)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val player: ExoPlayer = ExoPlayer.Builder(application)
            .setRenderersFactory(renderersFactory)  // 新增：解码器降级
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)             // 新增
            // 注意：setApproximateSeekEnabled 并非所有 media3 版本都公开暴露（构建期报 Unresolved），
            // 此处移除该调用，近似 seek 由 ExoPlayer 默认行为兜底，不影响播放功能。
            .setSeekBackIncrementMs(settingsRepository.getPlayerSeekBackSeconds() * 1_000L)
            .setSeekForwardIncrementMs(settingsRepository.getPlayerSeekForwardSeconds() * 1_000L)
            .build()

        player.apply {
            // P1 seek 优化（2026-09-11 掉帧报告）：CLOSEST_SYNC 避免精确 seek 触发长回溯
            // （keyint=250 + B 帧金字塔时回溯最多 8 秒才出首帧），抖动随之消除。
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
            // P1 帧率切换策略：方法在 ExoPlayer 实例上（不是 Builder），避免渲染器切换帧率时的额外抖动。
            // media3 1.9.1 中常量已移至 androidx.media3.common.C（ExoPlayer 接口不再持有该常量，直接引用会 Unresolved）
            setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
                    .build(),
                true
            )
            setMediaItem(createMediaItem(request, subtitle), resumePositionMs.coerceAtLeast(0L))
            prepare()
            playWhenReady = true
            addListener(
                object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        logPlaybackEvent(
                            "player.isPlaying",
                            mapOf("isPlaying" to isPlaying.toString()) + describeTracks(latestTracks)
                        )
                        if (isPlaying) {
                            playbackStartedAt = SystemClock.elapsedRealtime()
                            markMovieWatched()
                        } else {
                            recordPlaybackTime()
                            saveProgress(this@apply)
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val stateName = when (playbackState) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> playbackState.toString()
                        }
                        // 重缓冲统计：播放中进入 BUFFERING 即为重缓冲（首次 BUFFERING 不计）
                        if (playbackState == Player.STATE_BUFFERING && lastReadyAt > 0L) {
                            rebufferCount++
                            rebufferStartedAt = SystemClock.elapsedRealtime()
                            val timeSinceReady = rebufferStartedAt - lastReadyAt
                            logPlaybackEvent(
                                "player.rebuffer.start",
                                mapOf(
                                    "rebufferCount" to rebufferCount.toString(),
                                    "timeSinceReadyMs" to timeSinceReady.toString(),
                                    "positionMs" to this@apply.currentPosition.coerceAtLeast(0L).toString()
                                )
                            )
                        }
                        if (playbackState == Player.STATE_READY) {
                            val now = SystemClock.elapsedRealtime()
                            if (rebufferStartedAt > 0L) {
                                lastRecoveryMs = now - rebufferStartedAt
                                rebufferTotalMs += lastRecoveryMs
                                logPlaybackEvent(
                                    "player.rebuffer.recovered",
                                    mapOf(
                                        "rebufferCount" to rebufferCount.toString(),
                                        "recoveryMs" to lastRecoveryMs.toString(),
                                        "rebufferTotalMs" to rebufferTotalMs.toString(),
                                        "positionMs" to this@apply.currentPosition.coerceAtLeast(0L).toString()
                                    )
                                )
                                rebufferStartedAt = 0L
                            }
                            lastReadyAt = now
                        }
                        logPlaybackEvent(
                            "player.playbackState",
                            mapOf(
                                "state" to stateName,
                                "stateCode" to playbackState.toString(),
                                "durationMs" to this@apply.duration.toString(),
                                "durationKnown" to (this@apply.duration > 0L).toString(),
                                "bufferedMs" to this@apply.bufferedPosition.toString(),
                                "positionMs" to this@apply.currentPosition.coerceAtLeast(0L).toString(),
                                "rebufferCount" to rebufferCount.toString(),
                                "rebufferTotalMs" to rebufferTotalMs.toString()
                            ) + describeTracks(latestTracks)
                        )
                        when (playbackState) {
                            Player.STATE_ENDED -> clearProgress()
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        latestTracks = tracks
                        refreshAudioTracks(this@apply)
                        // 查询实际使用的渲染器（硬解/软解），不再按编码格式推断
                        val (vDec, aDec) = queryActualDecoders(this@apply)
                        latestVideoDecoder = vDec
                        latestAudioDecoder = aDec
                        val groupInfo = buildString {
                            tracks.groups.forEach { group ->
                                val format = runCatching { group.getTrackFormat(0) }.getOrNull()
                                if (format != null) {
                                    val mime = format.sampleMimeType ?: "?"
                                    val fr = format.frameRate.takeIf { it > 0f }?.let { " ${"%.2f".format(it)}fps" } ?: ""
                                    val codecs = format.codecs?.let { " $it" } ?: ""
                                    append("[$mime ${format.width}x${format.height}${fr}${codecs}] ")
                                }
                            }
                        }
                        logPlaybackEvent(
                            "player.tracksChanged",
                            mapOf("trackGroups" to groupInfo.trim().ifBlank { "none" }) +
                                describeTracks(tracks)
                        )
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        logPlaybackEvent(
                            "player.videoSize",
                            mapOf(
                                "width" to videoSize.width.toString(),
                                "height" to videoSize.height.toString(),
                                "unappliedRotationDegrees" to videoSize.unappliedRotationDegrees.toString(),
                                "pixelWidthHeightRatio" to videoSize.pixelWidthHeightRatio.toString()
                            )
                        )
                    }

                    override fun onRenderedFirstFrame() {
                        val firstFrameMs = SystemClock.elapsedRealtime() - playbackStartedAt
                        // 首帧渲染时 codec 一定已初始化，重新查询拿到真实 MediaCodec 名
                        val (vDec, aDec) = queryActualDecoders(this@apply)
                        latestVideoDecoder = vDec
                        latestAudioDecoder = aDec
                        logPlaybackEvent(
                            "player.firstFrame",
                            mapOf(
                                "sincePlayRequestedMs" to (if (playbackStartedAt > 0) firstFrameMs else -1L).toString(),
                                "videoDecoder" to (latestVideoDecoder ?: "unknown"),
                                "audioDecoder" to (latestAudioDecoder ?: "unknown")
                            ) + describeTracks(latestTracks)
                        )
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        // 提取完整的 cause chain，辅助定位网络/解码器层面的根因
                        val causeChain = buildString {
                            var cause: Throwable? = error.cause
                            while (cause != null) {
                                append(" -> ").append(cause::class.java.simpleName)
                                cause.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                                cause = cause.cause
                            }
                        }
                        logPlaybackEvent(
                            "player.error",
                            mapOf(
                                "errorCode" to error.errorCode.toString(),
                                "errorCodeName" to error.errorCodeName,
                                "message" to (error.message ?: ""),
                                "causeChain" to causeChain.ifBlank { "none" },
                                "positionMs" to this@apply.currentPosition.coerceAtLeast(0L).toString(),
                                "bufferedMs" to this@apply.bufferedPosition.toString(),
                                "isPlaying" to this@apply.isPlaying.toString()
                            ),
                            error = error
                        )
                        saveProgress(this@apply)
                        // ★ W5：结构化归因取代裸 message。只有「刷新直链真能救」的失败才自动重试，
                        // 404/解码器不支持这类失败立刻给出可执行建议，而不是重试一遍再失败。
                        val failure = PlaybackFailureClassifier.classify(error)
                        logPlaybackEvent(
                            "player.error.classified",
                            mapOf(
                                "kind" to failure.kind.name,
                                "httpCode" to (failure.httpCode?.toString() ?: "none")
                            )
                        )
                        if (failure.kind.autoRefreshLink && request.pickcode != null && !retriedAfterForbidden) {
                            retryAfterForbidden(request.pickcode)
                            return
                        }
                        _uiState.update {
                            it.copy(errorMessage = failure.userMessage)
                        }
                    }
                }
            )
            startProgressSaver(this)
            startDropFrameMonitor(this)
            // 播放可能在监听器注册前就已开始；补一次即时检查，保证首次播放也会标记。
            if (isPlaying) markMovieWatched()
        }

        // 解码器诊断：通过 queryActualDecoders() 从 ExoPlayer 渲染器实例直接查询
        // 实际使用的解码器（硬解 MediaCodec 名 / FFmpeg 软解），不再靠编码格式推断。


        return player
    }

    /**
     * 从 ExoPlayer 实例查询真实的视频/音频解码器信息（不再靠编码格式推断）。
     *
     * 遍历 player 的 renderer 列表，按 trackType 找到视频/音频渲染器：
     * - 渲染器类名含 nextlib/ffdecoder/Next → FFmpeg 软解
     * - 否则 → 硬解，并尝试通过 MediaCodecRenderer.getCodecName() 读取实际 MediaCodec 名
     *   （如 c2.qti.avc.decoder / OMX.qcom.video.decoder.avc），反射调用以兼容不同 media3 版本。
     */
    @OptIn(UnstableApi::class)
    private fun queryActualDecoders(player: ExoPlayer): Pair<String, String> {
        var videoDecoder = "unknown"
        var audioDecoder = "unknown"
        for (i in 0 until player.rendererCount) {
            val renderer = player.getRenderer(i)
            // 需求 1：工程已移除 nextlib，统一按原生渲染器（HARDWARE）处理
            when (renderer.trackType) {
                C.TRACK_TYPE_VIDEO -> {
                    val codecName = tryGetCodecName(renderer)
                    videoDecoder = if (codecName != null) "HARDWARE ($codecName)" else "HARDWARE (${renderer::class.java.simpleName}, codec未初始化)"
                }
                C.TRACK_TYPE_AUDIO -> {
                    val codecName = tryGetCodecName(renderer)
                    audioDecoder = if (codecName != null) "HARDWARE ($codecName)" else "HARDWARE (${renderer::class.java.simpleName}, codec未初始化)"
                }
            }
        }
        return videoDecoder to audioDecoder
    }

    /** 反射调用 MediaCodecRenderer.getCodecName()，兼容 protected/public 差异。 */
    private fun tryGetCodecName(renderer: Any): String? {
        return runCatching {
            var clazz: Class<*>? = renderer.javaClass
            while (clazz != null) {
                val method = try {
                    clazz.getDeclaredMethod("getCodecName")
                } catch (_: NoSuchMethodException) { null }
                if (method != null) {
                    method.isAccessible = true
                    return@runCatching method.invoke(renderer) as? String
                }
                clazz = clazz.superclass
            }
            null
        }.getOrNull()
    }

    private fun createMediaItem(request: PlaybackRequest, subtitle: LocalSubtitleFile?): MediaItem {
        val builder = MediaItem.Builder().setUri(request.mediaUri)

        // ★ 需求 1（WMV 支持）：为「本地文件 / 直链」显式声明容器 MIME。
        // 原因：Media3 对 .wmv 的容器嗅探依赖 DataSource 提供的 Content-Type，
        // content:// 与部分 http 直链给不出 video/x-ms-wmv，ExoPlayer 会直接判「无法识别的媒体格式」。
        // 显式 setMimeType 后由 Extractor 按容器解析，WMV/ASF 才能正常起播。
        // 说明：.strm 是自定义占位文件（正文是 URL），不能按扩展名声明容器类型，故排除。
        videoMimeTypeFor(request.mediaUri)?.let { builder.setMimeType(it) }

        if (subtitle != null) {
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(subtitle.uri)
                        .setMimeType(subtitle.mimeType())
                        .setLanguage("zh")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }
        return builder.build()
    }

    /**
     * 从 playable Uri 推断容器 MIME；无法判定（.strm / 扩展名未知 / 无扩展名）返回 null，
     * 交给 Media3 自行嗅探。只覆盖「扩展名可作为容器类型依据」的场景。
     */
    private fun videoMimeTypeFor(mediaUri: String): String? {
        val ext = VideoFormats.extensionOf(mediaUri.substringBefore('?'))
        if (ext.isEmpty() || ext == "strm") return null
        if (!VideoFormats.isVideoExtension(ext)) return null
        return VideoFormats.MIME_TYPES[ext]
    }

    /** 桥接：读取上次播放缓存的时长，作为个别影片无法即时解析时长时的兜底。 */
    suspend fun getCachedDurationMs(): Long =
        playbackProgressRepository.getCachedDurationMs(playbackMediaKey)

    private fun markMovieWatched() {
        if (hasMarkedWatched || movieId <= 0L) return
        hasMarkedWatched = true
        progressPersistenceScope.launch { movieRepository.setWatched(movieId, true) }
    }

    private fun recordPlaybackTime() {
        if (playbackStartedAt <= 0L) return
        usageStatsRepository.addPlaybackMillis(SystemClock.elapsedRealtime() - playbackStartedAt)
        playbackStartedAt = 0L
    }

    private fun externalSubtitleStyleSettings(): ExternalSubtitleStyleSettings =
        ExternalSubtitleStyleSettings(
            fontSizeSp = settingsRepository.getExternalSubtitleFontSizeSp(),
            bottomPaddingPercent = settingsRepository.getExternalSubtitleBottomPaddingPercent(),
            backgroundAlphaPercent = settingsRepository.getExternalSubtitleBackgroundAlphaPercent()
        )

    private fun progressBarStyleSettings(): PlayerProgressBarStyleSettings =
        PlayerProgressBarStyleSettings(
            widthDp = settingsRepository.getPlayerProgressBarWidthDp(),
            colorArgb = settingsRepository.getPlayerProgressBarColor(),
            alphaPercent = settingsRepository.getPlayerProgressBarAlphaPercent()
        )

    private suspend fun resolvePlayback(forceRefresh: Boolean = false): Result<PlaybackRequest> =
        try {
            Result.success(
                withTimeout(PLAYBACK_RESOLVE_TIMEOUT_MS) {
                    resolver.resolve(videoUri.toString(), title, fileName, forceRefresh)
                        .getOrThrow()
                }
            )
        } catch (error: TimeoutCancellationException) {
            Result.failure(error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }

    private fun playbackResolveErrorMessage(error: Throwable, refreshing: Boolean = false): String {
        // H 轮：文案按真实来源区分，播放光鸭时不再显示「115」
        val label = playbackProviderLabel()
        return when (error) {
            is TimeoutCancellationException,
            is SocketTimeoutException -> "解析播放地址超时，请检查网络、代理或 $label 登录状态后再试"
            is IOException -> "解析播放地址失败，请检查网络、代理或 $label 登录状态后再试：${error.message ?: "网络请求失败"}"
            else -> error.message ?: if (refreshing) "刷新 $label 播放地址失败" else "解析播放地址失败"
        }
    }

    /**
     * 当前播放源的中文名（H 轮）。
     * 光鸭链接（guangya_res / guangya_vod）此前一律被当成 115，UI 显示「正在向 115 请求播放直链」。
     * STRM 源无法在解析前得知内容路由，统一显示「网盘」，避免误导。
     */
    private fun playbackProviderLabel(): String =
        when (PickcodeExtractor.routeOf(videoUri.toString())) {
            ROUTE_GUANGYA_RES, ROUTE_GUANGYA_VOD -> "光鸭"
            "download_m3u", "play", "video_proxy" -> "115"
            else -> "网盘"
        }

    private fun showLoading(message: String, clearPlayer: Boolean = false) {
        _uiState.update {
            it.copy(
                playbackRequest = if (clearPlayer) null else it.playbackRequest,
                player = if (clearPlayer) null else it.player,
                isLoading = true,
                errorMessage = null,
                loadingMessage = message
            )
        }
    }

    private fun initialPlaybackLoadingMessage(forceRefresh: Boolean = false): String =
        when {
            PickcodeExtractor.extract(videoUri.toString()) != null -> {
                // H 轮：按路由显示真正的网盘名（光鸭 / 115）
                val label = playbackProviderLabel()
                if (forceRefresh) "正在刷新 $label 播放直链..." else "正在向 $label 请求播放直链..."
            }
            isStrmSource(videoUri, fileName) -> "正在解析播放地址..."
            else -> "正在解析播放地址..."
        }

    private suspend fun canUseLibrarySubtitleFeatures(request: PlaybackRequest): Boolean {
        if (isStrmSource(videoUri, fileName)) return true
        val pickcode = request.pickcode
            ?: PickcodeExtractor.extract(videoUri.toString())
            ?: return true
        return cloudStrmRecordRepository.getCached(pickcode) != null
    }

    private fun loadInitialExternalSubtitles(request: PlaybackRequest) {
        initialSubtitleJob?.cancel()
        if (!_uiState.value.subtitleFeaturesEnabled) return
        initialSubtitleJob = viewModelScope.launch {
            val startedAtMs = SystemClock.elapsedRealtime()
            runCatching {
                val resolvedStorageUri = resolveSubtitleStorageSourceUri(request)
                subtitleStorageSourceUri = resolvedStorageUri
                mediaKey = resolvedStorageUri?.toString() ?: mediaKey
                val localSubtitles = listLocalExternalSubtitles()
                val preferredSubtitle = preferredExternalSubtitle(localSubtitles)
                localSubtitles to preferredSubtitle
            }.onSuccess { (localSubtitles, preferredSubtitle) ->
                val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                if (elapsedMs >= SLOW_SUBTITLE_LOAD_LOG_MS) {
                    logPlaybackStage("player.subtitle.scan.slow", elapsedMs)
                }
                _uiState.update {
                    it.copy(
                        localSubtitles = localSubtitles,
                        vrMode = vrModeSettings.getMode(mediaKey),
                        vrControlMode = vrModeSettings.getControlMode(mediaKey)
                    )
                }
                preferredSubtitle?.let { applyExternalSubtitle(it, closePanel = false) }
            }.onFailure { error ->
                logPlaybackStage(
                    event = "player.subtitle.scan.failed",
                    elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
                    error = error
                )
            }
        }
    }

    private fun logPlaybackStage(
        event: String,
        elapsedMs: Long,
        extra: Map<String, String?> = emptyMap(),
        error: Throwable? = null
    ) {
        errorLog.append(
            event = event,
            details = playbackLogDetails() + mapOf("elapsedMs" to elapsedMs.toString()) + extra,
            error = error
        )
    }

    /** 播放运行期事件日志（无耗时指标，用于状态/轨道/时长等诊断）。 */
    private fun logPlaybackEvent(event: String, extra: Map<String, String?> = emptyMap(), error: Throwable? = null) {
        errorLog.append(
            event = event,
            details = playbackLogDetails() + extra,
            error = error
        )
    }

    /**
     * 提取当前视频/音频轨道关键信息，用于定位卡顿掉帧与无时长问题。
     * 记录编码格式、分辨率、帧率、码率、色彩/profile 等——高 profile / 高码率 / 10bit
     * 常让设备硬解器吃力而掉帧；软解（FFmpeg 兜底）或设备不支持的编码也是根因；
     * duration 未知则对应进度条无法拖动。
     * 解码器信息由 queryActualDecoders() 从 ExoPlayer 渲染器实例直接查询，不再推断。
     */
    private fun describeTracks(tracks: Tracks?): Map<String, String?> {
        val details = mutableMapOf<String, String?>()
        var audioFormat: Format? = null
        var videoFormat: Format? = null
        var videoTrackCount = 0
        var audioTrackCount = 0
        var textTrackCount = 0
        // 版本兼容：不依赖 Tracks.Group.type 属性，改用 Format.sampleMimeType 前缀区分音视频轨道
        tracks?.groups?.forEach { group ->
            val format = runCatching { group.getTrackFormat(0) }.getOrNull() ?: return@forEach
            val mime = format.sampleMimeType ?: return@forEach
            when {
                mime.startsWith("video/") -> {
                    videoTrackCount++
                    if (videoFormat == null) videoFormat = format
                }
                mime.startsWith("audio/") -> {
                    audioTrackCount++
                    if (audioFormat == null) audioFormat = format
                }
                mime.startsWith("text/") -> textTrackCount++
            }
        }
        details["trackGroupCount"] = tracks?.groups?.size?.toString() ?: "0"
        details["videoTrackCount"] = videoTrackCount.toString()
        details["audioTrackCount"] = audioTrackCount.toString()
        details["textTrackCount"] = textTrackCount.toString()
        if (videoFormat != null) {
            val v = videoFormat!!
            details["videoCodec"] = v.sampleMimeType
            details["videoCodecsTag"] = v.codecs ?: "unknown"   // 如 avc1.640028 / hev1.2.4
            details["videoContainer"] = v.containerMimeType ?: "unknown"
            details["videoSize"] = "${v.width}x${v.height}"
            details["videoFrameRate"] = v.frameRate.takeIf { it > 0f }?.toString() ?: "unknown"
            details["videoBitrate"] = v.bitrate.takeIf { it > 0 }?.toString() ?: "unknown"
            details["videoPeakBitrate"] = v.peakBitrate.takeIf { it > 0 }?.toString() ?: "unknown"
            // 高色深 / HDR 易让硬解器掉帧，单独标记。
            // media3 1.9.1 的 ColorInfo 没有 isHdr 便捷属性，改用稳定的 colorTransfer 判断：
            // ST2084(PQ)=6 / HLG=7 即 HDR 传输函数。
            val colorInfo = v.colorInfo
            details["videoColorSpace"] = colorInfo?.colorSpace
                ?.let { cs -> if (cs == 0) "unknown" else "CS$cs" } ?: "unknown"
            val transfer = colorInfo?.colorTransfer ?: 0
            details["videoColorTransfer"] = when (transfer) {
                0 -> "unknown"
                C.COLOR_TRANSFER_ST2084 -> "ST2084(PQ)"
                C.COLOR_TRANSFER_HLG -> "HLG"
                C.COLOR_TRANSFER_SDR -> "SDR"
                C.COLOR_TRANSFER_LINEAR -> "LINEAR"
                else -> "TF$transfer"
            }
            details["videoHdr"] = if (transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG) "HDR" else "SDR"
        } else {
            details["videoFormat"] = "none"
        }
        if (audioFormat != null) {
            val a = audioFormat!!
            details["audioCodec"] = a.sampleMimeType
            details["audioCodecsTag"] = a.codecs ?: "unknown"
            details["audioContainer"] = a.containerMimeType ?: "unknown"
            details["audioSampleRate"] = a.sampleRate.takeIf { it > 0 }?.toString() ?: "unknown"
            details["audioChannels"] = a.channelCount.takeIf { it > 0 }?.toString() ?: "unknown"
            details["audioBitrate"] = a.bitrate.takeIf { it > 0 }?.toString() ?: "unknown"
        } else {
            details["audioFormat"] = "none"
        }
        // 解码器信息（由 queryActualDecoders 从渲染器实例直接查询，非推断）
        details["videoDecoder"] = latestVideoDecoder ?: "unknown"
        details["audioDecoder"] = latestAudioDecoder ?: "unknown"
        return details
    }

    private fun playbackLogDetails(): Map<String, String?> =
        mapOf(
            "source" to playbackSourceLabel(),
            "videoUri" to videoUri.toString().take(600),
            "fileName" to fileName,
            "title" to title
        )

    private fun playbackSourceLabel(): String {
        // H 轮：光鸭此前被一律标成 cloud115，日志/诊断无法区分来源
        val route = PickcodeExtractor.routeOf(videoUri.toString())
        return when {
            route == ROUTE_GUANGYA_RES || route == ROUTE_GUANGYA_VOD -> "guangya"
            route != null -> "cloud115"
            isStrmSource(videoUri, fileName) -> "strm"
            else -> videoUri.scheme.orEmpty().ifBlank { "direct" }
        }
    }

    private fun retryAfterForbidden(pickcode: String) {
        retriedAfterForbidden = true
        viewModelScope.launch {
            val previousPlayer = _uiState.value.player
            // H 轮：先取路由（下面 showLoading(clearPlayer=true) 会清掉 playbackRequest）
            val previousRoute = _uiState.value.playbackRequest?.route
            val resumePositionMs = previousPlayer?.currentPosition?.coerceAtLeast(0L)
                ?: playbackProgressRepository.getResumePosition(playbackMediaKey)
            previousPlayer?.let { persistProgress(it, force = true) }
            progressJob?.cancel()
            initialSubtitleJob?.cancel()
            dropFrameJob?.cancel()
            previousPlayer?.release()
            showLoading(initialPlaybackLoadingMessage(forceRefresh = true), clearPlayer = true)
            // 按路由失效缓存：光鸭 fileId 之前会被送去清 115 的直链表，等于没清
            resolver.invalidateRoute(previousRoute, pickcode)
            val startedAtMs = SystemClock.elapsedRealtime()
            resolvePlayback(forceRefresh = true)
                .onSuccess { request ->
                    val resolveElapsedMs = SystemClock.elapsedRealtime() - startedAtMs
                    if (resolveElapsedMs >= SLOW_PLAYBACK_RESOLVE_LOG_MS) {
                        logPlaybackStage("player.resolve.refresh.slow", resolveElapsedMs)
                    }
                    showLoading("正在重新启动播放器...")
                    val subtitleFeaturesEnabled = canUseLibrarySubtitleFeatures(request)
                    val player = createPlayer(request, resumePositionMs)
                    _uiState.update {
                        it.copy(
                            playbackRequest = request,
                            player = player,
                            isLoading = false,
                            subtitleFeaturesEnabled = subtitleFeaturesEnabled,
                            externalSubtitleStyle = externalSubtitleStyleSettings(),
                            vrMode = vrModeSettings.getMode(mediaKey),
                            vrControlMode = vrModeSettings.getControlMode(mediaKey)
                        )
                    }
                    if (subtitleFeaturesEnabled) {
                        loadInitialExternalSubtitles(request)
                    }
                }
                .onFailure { error ->
                    logPlaybackStage(
                        event = "player.resolve.refresh.failed",
                        elapsedMs = SystemClock.elapsedRealtime() - startedAtMs,
                        error = error
                    )
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            externalSubtitleStyle = externalSubtitleStyleSettings(),
                            errorMessage = playbackResolveErrorMessage(error, refreshing = true)
                        )
                    }
                }
        }
    }

    fun togglePlayPause() {
        val mpv = mpvEngine
        if (isMpvActive() && mpv != null) {
            if (mpv.current.playWhenReady) {
                saveMpvProgress(force = true)
                mpv.pause()
            } else {
                mpv.play()
            }
            return
        }
        uiState.value.player?.let { player ->
            if (player.isPlaying) {
                saveProgress(player)
                player.pause()
            } else {
                player.play()
            }
        }
    }

    fun leavePlayer() {
        val mpv = mpvEngine
        if (isMpvActive() && mpv != null) {
            saveMpvProgress(force = true)
            mpv.pause()
            return
        }
        val player = uiState.value.player
        if (player != null) {
            saveProgress(player)
            player.pause()
        }
    }

    fun seekBack() {
        onSeek() // P0：seek 后 3 秒抑制掉帧统计，防止恢复期瞬态被误判
        val mpv = mpvEngine
        if (isMpvActive() && mpv != null) {
            mpv.seekBy(-settingsRepository.getPlayerSeekBackSeconds() * 1_000L)
            saveMpvProgress(force = true)
            return
        }
        uiState.value.player?.let { player ->
            player.seekBack()
            saveProgress(player)
        }
    }

    fun seekForward() {
        onSeek() // P0：seek 后 3 秒抑制掉帧统计
        val mpv = mpvEngine
        if (isMpvActive() && mpv != null) {
            mpv.seekBy(settingsRepository.getPlayerSeekForwardSeconds() * 1_000L)
            saveMpvProgress(force = true)
            return
        }
        uiState.value.player?.let { player ->
            player.seekForward()
            saveProgress(player)
        }
    }

    fun seekTo(positionMs: Long) {
        onSeek() // P0：seek 后 3 秒抑制掉帧统计（进度条拖动 1 秒≈60 次 seek，也全部走此保护）
        val mpv = mpvEngine
        if (isMpvActive() && mpv != null) {
            mpv.seekTo(positionMs.coerceAtLeast(0L))
            saveMpvProgress(force = true)
            return
        }
        uiState.value.player?.let { player ->
            player.seekTo(positionMs.coerceAtLeast(0L))
            saveProgress(player)
        }
    }

    fun selectPlaybackSpeed(speed: Float): Float {
        val selectedIndex = speeds.indexOfFirst { abs(it - speed) < 0.001f }
        if (selectedIndex < 0) return playbackSpeed
        speedIndex = selectedIndex
        val mpv = mpvEngine
        if (isMpvActive() && mpv != null) {
            // mpv 会把倍速夹到 [0.25, 4]，回写实际生效值，避免 UI 显示与实际不符。
            return mpv.setSpeed(playbackSpeed)
        }
        uiState.value.player?.setPlaybackSpeed(playbackSpeed)
        return playbackSpeed
    }

    fun selectAudioTrack(groupIndex: Int?, trackIndex: Int?) {
        val mpv = mpvEngine
        if (isMpvActive() && mpv != null) {
            // mpv 侧 groupIndex 即 aid（见 mpvAudioTrackOptions），trackIndex 恒为 0。
            mpv.selectAudioTrack(groupIndex)
            _uiState.update { it.copy(audioTrackAutomatic = groupIndex == null) }
            return
        }
        val player = _uiState.value.player ?: return
        val builder = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        if (groupIndex != null && trackIndex != null) {
            val group = player.currentTracks.groups.getOrNull(groupIndex) ?: return
            if (group.type != C.TRACK_TYPE_AUDIO || trackIndex !in 0 until group.length) return
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, trackIndex))
        }
        player.trackSelectionParameters = builder.build()
        _uiState.update { it.copy(audioTrackAutomatic = groupIndex == null) }
        refreshAudioTracks(player)
    }

    /** mpv 侧进度落库（与 exo 共用同一个 PlaybackProgressRepository）。 */
    private fun saveMpvProgress(force: Boolean) {
        val engine = mpvEngine ?: return
        val snapshot = engine.current
        progressPersistenceScope.launch {
            persistProgressValues(
                playbackState = snapshot.playbackState,
                positionMs = snapshot.positionMs.coerceAtLeast(0L),
                durationMs = snapshot.durationMs.takeIf { it > 0L } ?: 0L,
                force = force
            )
        }
    }

    private fun refreshAudioTracks(player: Player) {
        val tracks = player.currentTracks.groups.flatMapIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@flatMapIndexed emptyList()
            (0 until group.length).map { trackIndex ->
                val format = group.getTrackFormat(trackIndex)
                AudioTrackOption(
                    groupIndex = groupIndex,
                    trackIndex = trackIndex,
                    title = format.label?.takeIf { it.isNotBlank() }
                        ?: format.language?.takeIf { it.isNotBlank() }?.uppercase()
                        ?: "音轨 ${trackIndex + 1}",
                    details = buildList {
                        format.language?.takeIf { it.isNotBlank() }?.uppercase()?.let(::add)
                        format.channelCount.takeIf { it > 0 }?.let { add("$it 声道") }
                        format.sampleRate.takeIf { it > 0 }?.let { add("${it / 1000f} kHz") }
                        format.bitrate.takeIf { it > 0 }?.let { add("${it / 1000} kbps") }
                        format.sampleMimeType
                            ?.substringAfter('/')
                            ?.takeIf { it.isNotBlank() }
                            ?.uppercase()
                            ?.let(::add)
                    }.distinct().joinToString(" · "),
                    selected = group.isTrackSelected(trackIndex),
                    supported = group.isTrackSupported(trackIndex)
                )
            }
        }
        _uiState.update { it.copy(audioTracks = tracks) }
    }

    fun openExternalSubtitlePanel(videoDurationMs: Long) {
        if (!_uiState.value.subtitleFeaturesEnabled) return
        val number = normalizeMovieNumber(fileName) ?: normalizeMovieNumber(title)
        val provider = settingsRepository.getSubtitleSearchProvider()
        val subtitleNumber = number?.let { normalizeSubtitleSearchNumber(it, provider) }
        val cachedSearch = subtitleNumber
            ?.takeIf { it.isNotBlank() }
            ?.let { subtitleSearchCache[subtitleSearchKey(provider, it, videoDurationMs)] }
        _uiState.update {
            it.copy(
                externalSubtitlePanelVisible = true,
                externalSubtitleQueryNumber = subtitleNumber.orEmpty(),
                externalSubtitleProvider = provider,
                externalSubtitleMessage = cachedSearch?.message,
                externalSubtitleError = null,
                externalSubtitleSearching = false,
                externalSubtitleDownloading = false,
                onlineSubtitles = cachedSearch?.results ?: it.onlineSubtitles
            )
        }
        viewModelScope.launch {
            val storageSourceUri = resolveSubtitleStorageSourceUri()
            val localFiles = runCatching {
                localSubtitleStore.listLocalSubtitles(videoUri, fileName, storageSourceUri)
            }.getOrElse { emptyList() }
            _uiState.update { it.copy(localSubtitles = localFiles) }
            if (number == null) {
                _uiState.update {
                    it.copy(
                        externalSubtitleSearching = false,
                        externalSubtitleError = "\u6CA1\u6709\u8BC6\u522B\u5230\u5F71\u7247\u756A\u53F7\uFF0C\u65E0\u6CD5\u641C\u7D22\u5B57\u5E55"
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    externalSubtitleMessage = cachedSearch?.message ?: if (localFiles.isNotEmpty()) {
                        "\u5DF2\u627E\u5230\u672C\u5730\u5B57\u5E55\uFF0C\u53EF\u76F4\u63A5\u52A0\u8F7D\uFF1B\u4E5F\u53EF\u4EE5\u624B\u52A8\u641C\u7D22\u5728\u7EBF\u5B57\u5E55"
                    } else {
                        "\u5F53\u524D\u76EE\u5F55\u6CA1\u6709\u672C\u5730\u5B57\u5E55\uFF0C\u53EF\u624B\u52A8\u641C\u7D22\u5728\u7EBF\u5B57\u5E55"
                    },
                    externalSubtitleError = null
                )
            }
        }
    }

    fun selectExternalSubtitleProvider(provider: SubtitleSearchProvider) {
        if (!_uiState.value.subtitleFeaturesEnabled) return
        settingsRepository.saveSubtitleSearchProvider(provider)
        val number = normalizeMovieNumber(fileName) ?: normalizeMovieNumber(title)
        val subtitleNumber = number?.let { normalizeSubtitleSearchNumber(it, provider) }
        _uiState.update {
            it.copy(
                externalSubtitleProvider = provider,
                externalSubtitleQueryNumber = subtitleNumber.orEmpty(),
                onlineSubtitles = emptyList(),
                externalSubtitleSearching = false,
                externalSubtitleDownloading = false,
                externalSubtitleMessage = null,
                externalSubtitleError = if (subtitleNumber.isNullOrBlank()) {
                    "没有识别到影片番号，无法搜索字幕"
                } else {
                    null
                }
            )
        }
    }

    fun searchExternalSubtitles(videoDurationMs: Long) {
        if (!_uiState.value.subtitleFeaturesEnabled) return
        val provider = _uiState.value.externalSubtitleProvider
        val number = _uiState.value.externalSubtitleQueryNumber.ifBlank {
            (normalizeMovieNumber(fileName) ?: normalizeMovieNumber(title))
                ?.let { normalizeSubtitleSearchNumber(it, provider) }
                .orEmpty()
        }
        if (number.isBlank()) {
            _uiState.update {
                it.copy(
                    externalSubtitleSearching = false,
                    externalSubtitleError = "\u6CA1\u6709\u8BC6\u522B\u5230\u5F71\u7247\u756A\u53F7\uFF0C\u65E0\u6CD5\u641C\u7D22\u5B57\u5E55"
                )
            }
            return
        }
        if (_uiState.value.externalSubtitleSearching) return
        val cacheKey = subtitleSearchKey(provider, number, videoDurationMs)
        subtitleSearchCache[cacheKey]?.let { cached ->
            _uiState.update {
                it.copy(
                    onlineSubtitles = cached.results,
                    externalSubtitleSearching = false,
                    externalSubtitleMessage = cached.message,
                    externalSubtitleError = null
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                externalSubtitleSearching = true,
                externalSubtitleMessage = null,
                externalSubtitleError = null,
                onlineSubtitles = emptyList()
            )
        }
        viewModelScope.launch {
            runCatching {
                when (provider) {
                    SubtitleSearchProvider.Avsubtitles -> avsubtitlesSubtitleRepository.search(number, videoDurationMs)
                    SubtitleSearchProvider.Xunlei -> xunleiSubtitleRepository.search(number, videoDurationMs)
                    SubtitleSearchProvider.Cloud115 -> cloud115SubtitleRepository.search(number)
                }
            }.onSuccess { results ->
                val message = subtitleSearchEmptyMessage(provider, results)
                subtitleSearchCache[cacheKey] = CachedSubtitleSearch(results = results, message = message)
                _uiState.update {
                    it.copy(
                        onlineSubtitles = results,
                        externalSubtitleSearching = false,
                        externalSubtitleMessage = message,
                        externalSubtitleError = null
                    )
                }
            }.onFailure { error ->
                when (error) {
                    is AvsubtitlesCloudflareException -> {
                        errorLog.append(
                            event = "player.avsubtitles.search.cloudflare",
                            details = mapOf("number" to number),
                            error = error
                        )
                    }
                }
                _uiState.update {
                    it.copy(
                        externalSubtitleSearching = false,
                        externalSubtitleError = error.message ?: "\u5B57\u5E55\u641C\u7D22\u5931\u8D25"
                    )
                }
            }
        }
    }

    fun dismissExternalSubtitlePanel() {
        _uiState.update { it.copy(externalSubtitlePanelVisible = false) }
    }

    fun loadLocalSubtitle(subtitle: LocalSubtitleFile) {
        if (!_uiState.value.subtitleFeaturesEnabled) return
        applyExternalSubtitle(subtitle)
    }

    fun deleteLocalSubtitle(subtitle: LocalSubtitleFile) {
        if (!_uiState.value.subtitleFeaturesEnabled) return
        viewModelScope.launch {
            runCatching {
                val wasActive = subtitle.name == _uiState.value.activeExternalSubtitleName
                if (wasActive) {
                    activeExternalSubtitle = null
                    settingsRepository.saveExternalSubtitleEnabled(mediaKey, false)
                    clearExternalSubtitleTrack(closePanel = false)
                }
                val deleted = localSubtitleStore.deleteLocalSubtitle(subtitle)
                if (!deleted) error("字幕文件删除失败")
                listLocalExternalSubtitles()
            }.onSuccess { localFiles ->
                _uiState.update {
                    it.copy(
                        localSubtitles = localFiles,
                        externalSubtitleMessage = "已删除字幕：${subtitle.name}",
                        externalSubtitleError = null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        externalSubtitleMessage = null,
                        externalSubtitleError = error.message ?: "字幕文件删除失败"
                    )
                }
            }
        }
    }

    fun setExternalSubtitleEnabled(enabled: Boolean) {
        if (!_uiState.value.subtitleFeaturesEnabled) return
        if (!enabled) {
            activeExternalSubtitle = null
            settingsRepository.saveExternalSubtitleEnabled(mediaKey, false)
            clearExternalSubtitleTrack(closePanel = false)
            return
        }
        val currentName = _uiState.value.activeExternalSubtitleName
        if (currentName != null) {
            settingsRepository.saveExternalSubtitleEnabled(mediaKey, true)
            // mpv 侧字幕是在轨的，开关就是 sub-visibility；exo 侧由 PlayerView 决定可见性。
            mpvEngine?.takeIf { isMpvActive() }?.setSubtitleEnabled(true)
            _uiState.update { it.copy(externalSubtitleEnabled = true) }
            return
        }
        viewModelScope.launch {
            val localFiles = listLocalExternalSubtitles()
            val preferred = preferredExternalSubtitle(localFiles, requireEnabled = false)
                ?: localFiles.firstOrNull()
            if (preferred == null) {
                settingsRepository.saveExternalSubtitleEnabled(mediaKey, false)
                _uiState.update {
                    it.copy(
                        localSubtitles = localFiles,
                        externalSubtitleEnabled = false,
                        externalSubtitleError = "\u5F53\u524D\u6CA1\u6709\u53EF\u52A0\u8F7D\u7684\u672C\u5730\u5B57\u5E55"
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(localSubtitles = localFiles) }
            applyExternalSubtitle(preferred, closePanel = false)
        }
    }

    fun downloadAndLoadSubtitle(result: SubtitleSearchResult) {
        if (!_uiState.value.subtitleFeaturesEnabled) return
        if (_uiState.value.externalSubtitleDownloading) return
        _uiState.update {
            it.copy(
                externalSubtitleDownloading = true,
                externalSubtitleMessage = "\u6B63\u5728\u4E0B\u8F7D\u5B57\u5E55...",
                externalSubtitleError = null
            )
        }
        viewModelScope.launch {
            runCatching {
                val storageSourceUri = resolveSubtitleStorageSourceUri()
                when (result.provider) {
                    SubtitleSearchProvider.Avsubtitles -> avsubtitlesSubtitleRepository.download(videoUri, fileName, result, storageSourceUri)
                    SubtitleSearchProvider.Xunlei -> xunleiSubtitleRepository.download(videoUri, fileName, result, storageSourceUri)
                    SubtitleSearchProvider.Cloud115 -> cloud115SubtitleRepository.download(videoUri, fileName, result, storageSourceUri)
                }
            }.onSuccess { subtitle ->
                val localFiles = runCatching {
                    localSubtitleStore.listLocalSubtitles(videoUri, fileName, resolveSubtitleStorageSourceUri())
                }.getOrElse { _uiState.value.localSubtitles + subtitle }
                _uiState.update {
                    it.copy(
                        localSubtitles = localFiles.distinctBy { file -> file.uri.toString() },
                        externalSubtitleDownloading = false,
                        externalSubtitleMessage = "\u5B57\u5E55\u5DF2\u4E0B\u8F7D\u5E76\u52A0\u8F7D\uFF1A${subtitle.name}",
                        externalSubtitleError = null
                    )
                }
                applyExternalSubtitle(subtitle)
            }.onFailure { error ->
                when (error) {
                    is AvsubtitlesCloudflareException -> {
                        errorLog.append(
                            event = "player.avsubtitles.download.cloudflare",
                            details = subtitleLogDetails(result),
                            error = error
                        )
                    }
                }
                errorLog.append(
                    event = "player.${result.provider.id}.download.failed",
                    details = subtitleLogDetails(result),
                    error = error
                )
                val shouldRemoveResult = result.provider == SubtitleSearchProvider.Xunlei
                if (shouldRemoveResult) {
                    removeFailedXunleiSubtitleFromSearchResults(result)
                }
                _uiState.update {
                    it.copy(
                        externalSubtitleDownloading = false,
                        externalSubtitleMessage = null,
                        externalSubtitleError = if (shouldRemoveResult) {
                            "迅雷字幕文件不存在或下载失败，已从当前列表移除"
                        } else {
                            error.message ?: "字幕下载失败"
                        }
                    )
                }
            }
        }
    }

    private fun removeFailedXunleiSubtitleFromSearchResults(result: SubtitleSearchResult) {
        val identity = result.searchIdentity()
        subtitleSearchCache.entries.toList().forEach { (key, cached) ->
            val remaining = cached.results.filterNot { it.searchIdentity() == identity }
            if (remaining.size != cached.results.size) {
                subtitleSearchCache[key] = cached.copy(
                    results = remaining,
                    message = subtitleSearchEmptyMessage(result.provider, remaining)
                )
            }
        }
        _uiState.update { state ->
            state.copy(onlineSubtitles = state.onlineSubtitles.filterNot { it.searchIdentity() == identity })
        }
    }

    private fun subtitleLogDetails(result: SubtitleSearchResult): Map<String, String?> =
        mapOf(
            "videoUri" to videoUri.toString(),
            "fileName" to fileName,
            "title" to title,
            "subtitleStorageSourceUri" to subtitleStorageSourceUri?.toString(),
            "queryNumber" to _uiState.value.externalSubtitleQueryNumber,
            "resultName" to result.name,
            "resultExt" to result.ext,
            "provider" to result.provider.id,
            "hasAvsubtitlesCookie" to settingsRepository.getAvsubtitlesCookies().isNotBlank().toString(),
            "avsubtitlesCookieLength" to settingsRepository.getAvsubtitlesCookies().length.toString()
        )
    private suspend fun listLocalExternalSubtitles(): List<LocalSubtitleFile> =
        runCatching {
            localSubtitleStore.listLocalSubtitles(videoUri, fileName, resolveSubtitleStorageSourceUri())
        }.getOrElse { emptyList() }

    private fun preferredExternalSubtitle(
        localFiles: List<LocalSubtitleFile>,
        requireEnabled: Boolean = true
    ): LocalSubtitleFile? {
        if (requireEnabled && !settingsRepository.isExternalSubtitleEnabled(mediaKey)) return null
        val preferredName = settingsRepository.getPreferredExternalSubtitleName(mediaKey)
            .takeIf { it.isNotBlank() }
            ?: return null
        return localFiles.firstOrNull { it.name == preferredName }
    }

    private suspend fun resolveSubtitleStorageSourceUri(): Uri? =
        subtitleStorageSourceUri ?: resolveSubtitleStorageSourceUri(_uiState.value.playbackRequest).also {
            if (it != null) subtitleStorageSourceUri = it
        }

    private suspend fun resolveSubtitleStorageSourceUri(request: PlaybackRequest?): Uri? {
        if (isStrmSource(videoUri, fileName)) return videoUri
        val pickcode = request?.pickcode
            ?: PickcodeExtractor.extract(videoUri.toString())
            ?: return null
        val strmUri = cloudStrmRecordRepository.getCached(pickcode)?.strmUri
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return runCatching { Uri.parse(strmUri) }.getOrNull()
    }

    private fun isStrmSource(uri: Uri, name: String): Boolean =
        name.substringAfterLast('.', "").equals("strm", ignoreCase = true) ||
            uri.toString().substringBefore('?').endsWith(".strm", ignoreCase = true)

    fun adjustExternalSubtitleOffset(deltaMs: Long) {
        val subtitle = activeExternalSubtitle ?: return
        val next = (settingsRepository.getExternalSubtitleOffsetMs(mediaKey, subtitle.subtitleOffsetKey()) + deltaMs)
            .coerceIn(AppSettingsRepository.MIN_EXTERNAL_SUBTITLE_OFFSET_MS, AppSettingsRepository.MAX_EXTERNAL_SUBTITLE_OFFSET_MS)
        settingsRepository.saveExternalSubtitleOffsetMs(mediaKey, subtitle.subtitleOffsetKey(), next)
        _uiState.update { it.copy(externalSubtitleOffsetMs = next) }
        scheduleExternalSubtitleOffsetApply(subtitle)
    }

    fun resetExternalSubtitleOffset() {
        val subtitle = activeExternalSubtitle ?: return
        settingsRepository.saveExternalSubtitleOffsetMs(mediaKey, subtitle.subtitleOffsetKey(), 0L)
        _uiState.update { it.copy(externalSubtitleOffsetMs = 0L) }
        scheduleExternalSubtitleOffsetApply(subtitle)
    }

    private fun scheduleExternalSubtitleOffsetApply(subtitle: LocalSubtitleFile) {
        subtitleOffsetApplyJob?.cancel()
        subtitleOffsetApplyJob = viewModelScope.launch {
            delay(SUBTITLE_OFFSET_APPLY_DEBOUNCE_MS)
            applyExternalSubtitle(subtitle, closePanel = false)
        }
    }

    private fun applyExternalSubtitle(subtitle: LocalSubtitleFile, closePanel: Boolean = true) {
        val request = _uiState.value.playbackRequest ?: return
        // mpv 分支：sub-add + sub-delay，不需要重新 prepare 媒体。
        // 这正是 mpv 侧比 exo 更平滑的地方（exo 换字幕后要重建 MediaItem + prepare）。
        val mpv = mpvEngine
        if (isMpvActive() && mpv != null) {
            viewModelScope.launch {
                runCatching {
                    settingsRepository.getExternalSubtitleOffsetMs(mediaKey, subtitle.subtitleOffsetKey())
                }.onSuccess { offsetMs ->
                    mpv.setExternalSubtitle(
                        subtitle = ExternalSubtitle(
                            name = subtitle.name,
                            uri = subtitle.uri,
                            localFile = null
                        ),
                        offsetMs = offsetMs
                    )
                    activeExternalSubtitle = subtitle
                    settingsRepository.savePreferredExternalSubtitle(mediaKey, subtitle.name, enabled = true)
                    _uiState.update {
                        it.copy(
                            activeExternalSubtitleName = subtitle.name,
                            externalSubtitleEnabled = true,
                            externalSubtitleOffsetMs = offsetMs,
                            externalSubtitlePanelVisible = if (closePanel) false else it.externalSubtitlePanelVisible,
                            externalSubtitleMessage = null,
                            externalSubtitleError = null,
                            mpvError = null
                        )
                    }
                }
            }
            return
        }
        val player = _uiState.value.player ?: return
        val position = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.playWhenReady
        viewModelScope.launch {
            runCatching {
                val offsetMs = settingsRepository.getExternalSubtitleOffsetMs(mediaKey, subtitle.subtitleOffsetKey())
                val playbackSubtitle = withContext(Dispatchers.IO) {
                    subtitleTimeShiftStore.prepareSubtitle(subtitle, offsetMs)
                }
                Triple(offsetMs, playbackSubtitle, subtitle)
            }.onSuccess { (offsetMs, playbackSubtitle, originalSubtitle) ->
                player.setMediaItem(createMediaItem(request, playbackSubtitle), position)
                player.prepare()
                player.playWhenReady = wasPlaying
                activeExternalSubtitle = originalSubtitle
                settingsRepository.savePreferredExternalSubtitle(mediaKey, originalSubtitle.name, enabled = true)
                _uiState.update {
                    it.copy(
                        activeExternalSubtitleName = originalSubtitle.name,
                        externalSubtitleEnabled = true,
                        externalSubtitleOffsetMs = offsetMs,
                        externalSubtitlePanelVisible = if (closePanel) false else it.externalSubtitlePanelVisible,
                        externalSubtitleMessage = null,
                        externalSubtitleError = null
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(externalSubtitleError = error.message ?: "字幕时间轴调整失败")
                }
            }
        }
    }

    private fun clearExternalSubtitleTrack(closePanel: Boolean = true) {
        subtitleOffsetApplyJob?.cancel()
        subtitleOffsetApplyJob = null
        activeExternalSubtitle = null
        // mpv 分支：sub-remove 即可，不重建媒体。
        val mpv = mpvEngine
        if (isMpvActive() && mpv != null) {
            mpv.clearExternalSubtitle()
            _uiState.update {
                it.copy(
                    activeExternalSubtitleName = null,
                    externalSubtitleEnabled = false,
                    externalSubtitleOffsetMs = 0L,
                    externalSubtitlePanelVisible = if (closePanel) false else it.externalSubtitlePanelVisible,
                    externalSubtitleMessage = null,
                    externalSubtitleError = null
                )
            }
            return
        }
        if (_uiState.value.activeExternalSubtitleName == null) {
            _uiState.update {
                it.copy(
                    externalSubtitleEnabled = false,
                    externalSubtitleOffsetMs = 0L,
                    externalSubtitlePanelVisible = if (closePanel) false else it.externalSubtitlePanelVisible,
                    externalSubtitleMessage = null,
                    externalSubtitleError = null
                )
            }
            return
        }
        val request = _uiState.value.playbackRequest ?: return
        val player = _uiState.value.player ?: return
        val position = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.playWhenReady
        player.setMediaItem(createMediaItem(request, null), position)
        player.prepare()
        player.playWhenReady = wasPlaying
        _uiState.update {
            it.copy(
                activeExternalSubtitleName = null,
                externalSubtitleEnabled = false,
                externalSubtitleOffsetMs = 0L,
                externalSubtitlePanelVisible = if (closePanel) false else it.externalSubtitlePanelVisible,
                externalSubtitleMessage = null,
                externalSubtitleError = null
            )
        }
    }

    fun setVrMode(mode: VrMode) {
        // mpv 内核没有球面/VR 投射能力（见对齐表），在 mpv 下切到 VR 会得到一块平铺黑面。
        // 这里显式拒绝并说明原因，而不是静默把用户丢进不可用状态。
        if (mode.isVr && isMpvActive()) {
            _uiState.update { it.copy(engineSwitchNotice = "mpv 内核暂不支持 VR 播放，请切回 exo 内核后再开启") }
            return
        }
        vrModeSettings.saveMode(mediaKey, mode)
        _uiState.update { it.copy(vrMode = mode) }
    }

    fun setVrControlMode(mode: VrControlMode) {
        vrModeSettings.saveControlMode(mediaKey, mode)
        _uiState.update { it.copy(vrControlMode = mode) }
    }

    private fun startProgressSaver(player: Player) {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                persistProgress(player, force = false)
            }
        }
    }

    /**
     * 丢帧/卡顿监控：周期采样 ExoPlayer 的 videoDecoderCounters，把累计丢帧、丢到关键帧、跳过的
     * 输出缓冲等指标写入诊断日志。这是「卡顿掉帧」最直接的运动学证据——比按编码格式推断解码器
     * 类型更可信。仅在指标相对上次有变化时记录，避免刷屏。
     * 同时附带缓冲水位、播放速度、重缓冲上下文，辅助定位偶发卡顿根因。
     * 注：videoDecoderCounters / DecoderCounters 是 media3 1.9.1 稳定公开 API。
     */
    private fun startDropFrameMonitor(player: Player) {
        dropFrameJob?.cancel()
        logPlaybackEvent("player.monitorStarted", mapOf("intervalMs" to DROP_FRAME_MONITOR_INTERVAL_MS.toString()))
        // P0 监控修正（2026-09-11 掉帧报告）：重置基线，起播 3 秒内不上报
        lastDropped = -1
        lastRendered = -1
        lastPositionMs = -1L
        highDropStreak = 0
        suppressStatsUntilMs = 0L
        playbackStartTimeMs = SystemClock.elapsedRealtime()
        dropFrameJob = viewModelScope.launch {
            var lastSkipped = -1
            var healthSnapshotCounter = 0
            var countersNullLogged = false
            while (isActive) {
                delay(DROP_FRAME_MONITOR_INTERVAL_MS)
                val exoPlayer = player as? ExoPlayer ?: continue
                val counters = runCatching { exoPlayer.videoDecoderCounters }.getOrNull()
                // 如果 counters 持续返回 null（某些渲染器/版本不暴露此属性），记录一次
                if (counters == null && !countersNullLogged) {
                    countersNullLogged = true
                    logPlaybackEvent(
                        "player.monitor.countersNull",
                        mapOf(
                            "rendererCount" to exoPlayer.rendererCount.toString(),
                            "playbackState" to exoPlayer.playbackState.toString()
                        )
                    )
                }
                val dropped = counters?.droppedBufferCount ?: 0
                val skipped = counters?.skippedOutputBufferCount ?: 0
                val rendered = counters?.renderedOutputBufferCount ?: 0
                val now = SystemClock.elapsedRealtime()
                val pos = player.currentPosition.coerceAtLeast(0L)

                // ① seek/flush 检测：计数器回退、位置跳变（>3s）或非 READY → 重置基线并跳过本周期
                if (lastDropped >= 0 && lastRendered >= 0 && lastPositionMs >= 0L &&
                    (dropped < lastDropped ||
                        rendered < lastRendered ||
                        abs(pos - lastPositionMs) > 3_000 ||
                        player.playbackState != Player.STATE_READY)
                ) {
                    resetDropBaseline(dropped, rendered, pos)
                    lastSkipped = skipped
                    continue
                }

                // ② 起播/seek 恢复期保护：seek 后或起播 3 秒内不上报，只刷新基线
                if (now < playbackStartTimeMs + 3_000 || now < suppressStatsUntilMs) {
                    resetDropBaseline(dropped, rendered, pos)
                    lastSkipped = skipped
                    continue
                }

                val deltaDropped = if (lastDropped >= 0) dropped - lastDropped else 0
                val deltaSkipped = if (lastSkipped >= 0) skipped - lastSkipped else 0
                val deltaRendered = if (lastRendered >= 0) rendered - lastRendered else 0
                // ③ 正确分母：只算真正送去渲染的帧，skipped 不计入（seek 回溯丢弃帧不再放大掉帧率）
                val deltaTotal = deltaDropped + deltaRendered
                val realDropRate = if (deltaTotal > 0) deltaDropped.toDouble() / deltaTotal else 0.0
                val dropRate = if (deltaTotal > 0) "%.1f%%".format(realDropRate * 100) else "0%"
                val countersChanged = dropped != lastDropped || skipped != lastSkipped || rendered != lastRendered
                val maxConsecutiveNulls = rendered == lastRendered && dropped == lastDropped
                lastDropped = dropped
                lastSkipped = skipped
                lastRendered = rendered
                lastPositionMs = pos
                if (countersChanged) {
                    logPlaybackEvent(
                        "player.dropFrames",
                        mapOf(
                            "droppedFrames" to dropped.toString(),
                            "skippedOutputBuffers" to skipped.toString(),
                            "renderedOutputBuffers" to rendered.toString(),
                            "deltaDropped" to deltaDropped.toString(),
                            "deltaSkipped" to deltaSkipped.toString(),
                            "deltaRendered" to deltaRendered.toString(),
                            "dropRate" to dropRate,
                            "positionMs" to pos.toString(),
                            "bufferedMs" to player.bufferedPosition.toString(),
                            "durationMs" to player.duration.toString(),
                            "playbackSpeed" to speeds[speedIndex].toString(),
                            "isPlaying" to player.isPlaying.toString(),
                            "rebufferCount" to rebufferCount.toString()
                        )
                    )
                    // ④ 连续 2 个窗口（10 秒）掉帧率 >15% 才算真过载，消除 seek 恢复期瞬态误报
                    if (realDropRate > 0.15) highDropStreak++ else highDropStreak = 0
                    if (highDropStreak >= 2) {
                        val nowMs = SystemClock.elapsedRealtime()
                        // 过载事件至少间隔 30 秒记录一次，避免刷屏
                        if (nowMs - lastOverloadLogMs > 30_000L) {
                            lastOverloadLogMs = nowMs
                            val hint = "硬解码器性能不足（连续两个采样窗口掉帧率超 15%），可降低播放速度或换设备"
                            logPlaybackEvent(
                                "player.decoderOverload",
                                mapOf(
                                    "dropRate" to dropRate,
                                    "deltaDropped" to deltaDropped.toString(),
                                    "deltaSkipped" to deltaSkipped.toString(),
                                    "deltaRendered" to deltaRendered.toString(),
                                    "bufferedAheadMs" to (player.bufferedPosition - pos).coerceAtLeast(0L).toString(),
                                    "videoDecoder" to (latestVideoDecoder ?: "unknown"),
                                    "videoCodec" to (latestTracks?.groups?.firstNotNullOfOrNull { g ->
                                        runCatching { g.getTrackFormat(0) }.getOrNull()
                                            ?.takeIf { (it.sampleMimeType ?: "").startsWith("video/") }
                                            ?.codecs
                                    } ?: "unknown"),
                                    "positionMs" to pos.toString(),
                                    "hint" to hint
                                )
                            )
                        }
                    }
                    // 渲染停滞告警：isPlaying=true 但连续两个周期无帧输出
                    if (maxConsecutiveNulls && player.isPlaying) {
                        logPlaybackEvent(
                            "player.renderStall",
                            mapOf(
                                "renderedOutputBuffers" to rendered.toString(),
                                "droppedFrames" to dropped.toString(),
                                "skippedOutputBuffers" to skipped.toString(),
                                "bufferedMs" to player.bufferedPosition.toString(),
                                "positionMs" to pos.toString(),
                                "playbackState" to player.playbackState.toString(),
                                "videoDecoder" to (latestVideoDecoder ?: "unknown")
                            )
                        )
                    }
                }
                // 周期性健康快照（首次在第 1 个周期后输出，之后每 6 个周期 = 30 秒）
                healthSnapshotCounter++
                if (healthSnapshotCounter >= HEALTH_SNAPSHOT_INTERVAL || (healthSnapshotCounter == 1 && lastRendered < 0)) {
                    healthSnapshotCounter = 0
                    logPlaybackEvent(
                        "player.healthSnapshot",
                        mapOf(
                            "positionMs" to pos.toString(),
                            "durationMs" to player.duration.toString(),
                            "bufferedMs" to player.bufferedPosition.toString(),
                            "isPlaying" to player.isPlaying.toString(),
                            "playbackSpeed" to speeds[speedIndex].toString(),
                            "droppedFrames" to dropped.toString(),
                            "skippedOutputBuffers" to skipped.toString(),
                            "renderedOutputBuffers" to rendered.toString(),
                            "rebufferCount" to rebufferCount.toString(),
                            "rebufferTotalMs" to rebufferTotalMs.toString()
                        ) + describeTracks(latestTracks)
                    )
                }
            }
        }
    }

    /**
     * P0 掉帧监控辅助：重置采样基线。seek/flush、起播/seek 恢复期保护期后刷新基线，
     * 避免陈旧基线把 seek 恢复期的一次性计数差异算进下一个正常窗口。
     */
    private fun resetDropBaseline(dropped: Int, rendered: Int, positionMs: Long) {
        lastDropped = dropped
        lastRendered = rendered
        lastPositionMs = positionMs
    }

    /**
     * P0 掉帧监控辅助：任何 seek 后 3 秒内抑制掉帧统计，覆盖 seek 恢复期的瞬时 skipped 冲击。
     */
    private fun onSeek() {
        suppressStatsUntilMs = SystemClock.elapsedRealtime() + 3_000
    }

    private fun saveProgress(player: Player) {
        val playbackState = player.playbackState
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val durationMs = player.duration.takeIf { it > 0L } ?: 0L
        progressPersistenceScope.launch {
            persistProgressValues(playbackState, positionMs, durationMs, force = true)
        }
    }

    private suspend fun persistProgress(player: Player, force: Boolean) {
        persistProgressValues(
            playbackState = player.playbackState,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0L } ?: 0L,
            force = force
        )
    }

    private suspend fun persistProgressValues(
        playbackState: Int,
        positionMs: Long,
        durationMs: Long,
        force: Boolean
    ) {
        if (playbackState == Player.STATE_ENDED) {
            playbackProgressRepository.clear(playbackMediaKey)
            lastPersistedPositionMs = Long.MIN_VALUE
            lastPersistedDurationMs = 0L
            lastPersistedAtMs = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (!force) {
            if (positionMs < MIN_AUTOSAVE_POSITION_MS) return
            val positionDelta = if (lastPersistedPositionMs == Long.MIN_VALUE) Long.MAX_VALUE else abs(positionMs - lastPersistedPositionMs)
            val durationStable = abs(durationMs - lastPersistedDurationMs) <= 1_000L
            val intervalNotReached = now - lastPersistedAtMs < PROGRESS_SAVE_INTERVAL_MS
            if (positionDelta < PROGRESS_SAVE_POSITION_DELTA_MS && durationStable && intervalNotReached) return
        }
        playbackProgressRepository.save(
            mediaKey = playbackMediaKey,
            positionMs = positionMs,
            durationMs = durationMs
        )
        lastPersistedPositionMs = positionMs
        lastPersistedDurationMs = durationMs
        lastPersistedAtMs = now
    }

    private fun clearProgress() {
        progressPersistenceScope.launch {
            playbackProgressRepository.clear(playbackMediaKey)
            lastPersistedPositionMs = Long.MIN_VALUE
            lastPersistedDurationMs = 0L
            lastPersistedAtMs = 0L
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 双引擎：mpv 会话、状态镜像与切换
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 判断首次装载是否应该走 mpv。
     *
     * 三条都不满足时返回 false，**且不写任何状态**——外层会照旧创建 ExoPlayer，
     * 保证「升级用户默认行为不变」。
     */
    private fun startInitialMpvSession(
        request: PlaybackRequest,
        resumePositionMs: Long,
        subtitleFeaturesEnabled: Boolean
    ): Boolean {
        if (settingsRepository.getDefaultPlaybackEngine() != AppSettingsRepository.PLAYER_ENGINE_MPV) {
            return false
        }
        // VR 球面播放依赖 ExoPlayer 的 SphericalGLSurfaceView，mpv 侧没有等价渲染链，
        // 因此 VR 模式下不启用 mpv（详见对齐表「VR 球面播放」）。
        if (_uiState.value.vrMode.isVr) {
            settingsRepository.saveDefaultPlaybackEngine(AppSettingsRepository.PLAYER_ENGINE_EXO)
            _uiState.update { it.copy(engineSwitchNotice = "VR 模式下仅支持 ExoPlayer，已切回默认内核") }
            return false
        }
        val error = startMpvSession(request, resumePositionMs, autoPlay = true)
        if (error != null) {
            // 显式回退：不静默降级，把原因交给 UI 展示。
            _uiState.update { it.copy(engineSwitchNotice = "mpv 启动失败，已回退 ExoPlayer：$error") }
            return false
        }
        _uiState.update {
            it.copy(
                playbackRequest = request,
                player = null,
                isLoading = false,
                engineKind = PlaybackEngineKind.MPV,
                subtitleFeaturesEnabled = subtitleFeaturesEnabled,
                externalSubtitleStyle = externalSubtitleStyleSettings(),
                progressBarStyle = progressBarStyleSettings()
            )
        }
        return true
    }

    /**
     * 创建一个 mpv 实例并装载媒体。
     *
     * @return null 表示成功；非 null 是可直接展示给用户的失败原因（调用方据此回退 exo）。
     */
    private fun startMpvSession(
        request: PlaybackRequest,
        startPositionMs: Long,
        autoPlay: Boolean
    ): String? {
        val availability = PlaybackEngineFactory.availability(application, PlaybackEngineKind.MPV)
        if (!availability.available) {
            return availability.reason ?: "mpv 内核不可用"
        }
        val engine = MpvPlaybackEngine(
            context = application,
            cacheDir = File(application.cacheDir, MPV_SUBTITLE_CACHE_DIR),
            hwdecMode = settingsRepository.getMpvHwdecMode()
        )
        val initError = engine.initialize()
        if (initError != null) {
            engine.release()
            return initError
        }
        // 先把新实例挂上，再让旧的退出：中途异常时 UI 至少还有可用来源。
        val previous = synchronized(mpvEngineLock) {
            val old = mpvEngine
            mpvEngine = engine
            old
        }
        observeMpvSnapshot(engine)
        startMpvProgressSaver(engine)
        engine.setSubtitleStyle(currentSubtitleStyle())
        // 实时调色：在 load 之前下发，避免起播最初几帧是未调色的原片（那几帧会被用户看见）。
        // 生成在 IO 线程、set 回主线程，与 load 并发执行不冲突——target-lut 是 vo 级选项，
        // 与是否已装载媒体无关，装载后自然会用上最新那张。
        engine.applyLut(currentLutSettings)
        // 比例模式是「UI 侧状态」，新建的 mpv 实例不知道用户之前选了哪个，
        // 这里按最近一次 applyResizeMode 的结果重新下发，避免切内核后画面突然回弹。
        engine.setVideoResizeMode(VideoResizeMode.fromIndex(currentResizeIndex))
        engine.load(
            uri = request.mediaUri,
            headers = engineHeaders(request),
            startPositionMs = startPositionMs,
            autoPlay = autoPlay
        )
        if (previous != null && previous !== engine) {
            mpvSnapshotJob?.let { /* 观察任务已被 observeMpvSnapshot 重新签发 */ }
            previous.release()
        }
        logPlaybackEvent(
            "player.engine.mpv.started",
            mapOf(
                "startPositionMs" to startPositionMs.toString(),
                "autoPlay" to autoPlay.toString(),
                "hwdec" to settingsRepository.getMpvHwdecMode().id
            )
        )
        return null
    }

    /**
     * 切换 mpv 硬件解码档位（hw+ / hw / sw）。
     *
     * 三条路径必须一起走，否则会出现「面板里选中了、实际没生效」：
     * 1. 持久化 —— 下次进播放器、下次新建 mpv 实例时用新档位；
     * 2. 运行期热切换 —— 当前 mpv 实例直接改 `hwdec`（mpv 会重建解码器，不打断播放位置）；
     * 3. 写 uiState —— 面板选中态立刻跟着动。
     *
     * exo 内核下只做 1 + 3：exo 侧没有对等的三档（硬解由 media3 自己决定），
     * 攒下的选择会在切回 mpv 时生效。
     */
    fun setMpvHwdecMode(mode: MpvHwdecMode) {
        if (_uiState.value.mpvHwdecMode == mode) return
        settingsRepository.saveMpvHwdecMode(mode)
        _uiState.update { it.copy(mpvHwdecMode = mode) }
        logPlaybackEvent("player.mpv.hwdec_mode", mapOf("mode" to mode.id))
        val engine = synchronized(mpvEngineLock) { mpvEngine } ?: return
        if (_uiState.value.engineKind != PlaybackEngineKind.MPV) return
        engine.setHwdecMode(mode)
    }

    /**
     * 订阅实时调色设置。
     *
     * 两条生效路径：
     * 1. 播放中改设置 → 这里收到新值 → 若 mpv 正在出画面，立刻重新下发（滑杆实时生效）；
     * 2. 新建 mpv 实例 → [startMpvSession] 用 [currentLutSettings] 主动下发一次。
     *
     * 只在 mpv 活跃时下发：exo 侧没有对应的色彩管线（见交付文档的「无法对齐项」），
     * 对 exo 下发没有意义，且 mpvEngine 为 null 时本来也发不出去。
     */
    private fun startLutObserver() {
        // 同步读一次：避免第一帧播放时 currentLutSettings 还是默认值（关闭），
        // 导致开了调色的用户每次起播都要等一次设置回调才看到效果。
        currentLutSettings = settingsRepository.getLutSettings()
        lutObserverJob?.cancel()
        lutObserverJob = viewModelScope.launch {
            settingsRepository.observeLutSettings().collect { next ->
                currentLutSettings = next
                if (isMpvActive()) {
                    mpvEngine?.applyLut(next)
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 自动分析调参
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 分析当前画面，自动搜出一组调色参数并用上。
     *
     * 这是参考实现 `analyze_and_apply()`（mpv_realtime_lut.py:1741）在移动端的对应物，
     * 链路是：mpv 截一帧 → 提肤色 → 按模板搜 7 个参数 → 写回设置并下发 LUT。
     * 与参考实现的差别只有两点，都是为了移动端而必须做的：
     *
     * 1. **截帧走 mpv 的 `screenshot-to-file` 命令**，不是 Windows 屏幕抓取
     *    （`mss` + `win32gui`，Android 上不存在）。
     * 2. **肤色提取只走中心区域**，没有人脸关键点（参考实现的 MediaPipe 路径需要
     *    3.7 MB 模型 + TFLite，见交付文档 §4.1）。中心区域的判据本身就带亮度与
     *    R/G、R/B 比例约束，木地板、墙壁这类背景大面积肤色会被亮度上限挡掉。
     *
     * 结果直接写进设置（`enabled = true` + 搜出来的 7 个参数），因此分析完点开设置页
     * 看到的滑杆位置就是这次分析的结果，用户还能继续手动微调。
     *
     * 只对 mpv 生效：exo 没有 `target-lut` 通道（见文档 §4.2），对它分析出来的参数
     * 也无处可用，所以直接给一句提示而不是假装成功。
     */
    fun autoTuneLut() {
        if (uiState.value.lutAutoTuneBusy) return
        if (!isMpvActive()) {
            _uiState.update { it.copy(lutAutoTuneMessage = "自动分析只在使用 mpv 内核时可用") }
            return
        }
        val engine = mpvEngine ?: run {
            _uiState.update { it.copy(lutAutoTuneMessage = "播放内核还没就绪，稍后再试") }
            return
        }
        _uiState.update { it.copy(lutAutoTuneBusy = true, lutAutoTuneMessage = null) }

        // 截帧要等 mpv 落盘（最长 2s），肤色统计要遍历几十万像素，都在 IO 线程做；
        // 只有最后的写设置与下发回主线程，与 applyLut 的约定一致。
        lutAutoTuneJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { analyzeCurrentFrame(engine) }
                    .onFailure { logPlaybackEvent("player.lut.autoTune.failed", error = it) }
                    .getOrNull()
            }
            try {
                if (result == null) {
                    _uiState.update { it.copy(lutAutoTuneMessage = "没抓到画面，换个能看清人脸的场景再试") }
                    return@launch
                }
                val analysis = result
                if (!analysis.hasSkin) {
                    // 没肤色也把消息带出来：这是最常见的结果，用户需要知道「为什么没调」。
                    _uiState.update { it.copy(lutAutoTuneMessage = analysis.message) }
                    return@launch
                }
                val next = settingsRepository.getLutSettings().let { base ->
                    base.copy(enabled = true, params = analysis.prediction.params)
                }
                settingsRepository.saveLutSettings(next)
                // save 之后 observeLutSettings 也会收到一次，那里会 applyLut；这里同步
                // 再下发一次是为了「分析完立刻看到效果」，且 applyLut 内部按签名去重，
                // 重复下发是幂等的。
                currentLutSettings = next
                if (isMpvActive()) mpvEngine?.applyLut(next)
                _uiState.update { it.copy(lutAutoTuneMessage = analysis.message) }
            } finally {
                _uiState.update { it.copy(lutAutoTuneBusy = false) }
            }
        }
    }

    /**
     * 从当前画面提取肤色统计。在 IO 线程调用。
     *
     * 位图取完像素就 `recycle()`：一帧 1080p 的 ARGB 是 8 MB，分析完不再需要，
     * 留在那等 GC 会在连续分析几次后明显卡顿。
     */
    private fun analyzeCurrentFrame(engine: MpvPlaybackEngine): LutAutoAnalysis? {
        val bitmap = engine.captureFrameForAnalysis() ?: return null
        val pixels = try {
            val w = bitmap.width
            val h = bitmap.height
            val argb = IntArray(w * h)
            bitmap.getPixels(argb, 0, w, 0, 0, w, h)
            LutAutoAnalyzer.analyze(argb, w, h)
        } finally {
            bitmap.recycle()
        }
        return pixels
    }

    /** mpv 的请求头：与 exo 的 DefaultHttpDataSource 默认请求属性保持一致。 */
    private fun engineHeaders(request: PlaybackRequest): Map<String, String> = buildMap {        putAll(request.headers)
        val userAgent = request.userAgent ?: DEFAULT_USER_AGENT
        if (request.mediaUri.startsWith("http", ignoreCase = true)) {
            put("User-Agent", userAgent)
        }
    }

    /**
     * 把 mpv 快照镜像进 PlayerUiState。
     *
     * 只保留「当前实例」的订阅：切换引擎时会取消上一个 job，避免旧实例的最后一次状态
     * 落进新引擎的界面。
     */
    private fun observeMpvSnapshot(engine: MpvPlaybackEngine) {
        mpvSnapshotJob?.cancel()
        mpvSnapshotJob = viewModelScope.launch {
            engine.snapshot.collect { snapshot ->
                // 双保险：即便 collect 因为取消竞态多跑一拍，也不会污染已切换的界面。
                if (mpvEngine !== engine) return@collect
                val ended = snapshot.playbackState == PlaybackSnapshot.STATE_ENDED && !snapshot.isPlaying
                val mpvTracks = engine.readAudioTracks()
                // mpv 用全局 aid 表示「当前哪条音轨在响」（track-list 项里没有 selected 字段），
                // 取一次交给转换器，列表选中态与「自动/手动」判定才有真实依据。
                val currentAid = engine.currentAudioTrackId()
                // mpv 给的是扁平轨道（aid），UI/状态字段统一要 AudioTrackOption；
                // 用现成的 mpvAudioTrackOptions 转换（构造器把 aid 塞进 groupIndex）。
                val mpvTrackOptions = mpvAudioTrackOptions(mpvTracks, currentAid)
                _uiState.update {
                    it.copy(
                        mpvSnapshot = snapshot,
                        mpvDurationMs = snapshot.durationMs.takeIf { value -> value > 0L } ?: it.mpvDurationMs,
                        mpvAudioTracks = mpvTrackOptions,
                        // 音轨对话框、按钮可见性都只读 audioTracks；mpv 侧必须写进同一字段，
                        // 否则 mpv 播放时「音轨」按钮会消失（对齐表里显式要求同源）。
                        audioTracks = mpvTrackOptions,
                        // aid 为 null 等价于 mpv 的 `aid=auto`，与 exo 侧「无 override = 自动」同义。
                        audioTrackAutomatic = currentAid == null,
                        mpvError = snapshot.errorMessage,
                        mpvReady = snapshot.playbackState != PlaybackSnapshot.STATE_IDLE ||
                            snapshot.errorMessage != null
                    )
                }
                if (ended) clearProgress()
            }
        }
    }

    /**
     * mpv 进度持久化。
     *
     * exo 侧挂在 ExoPlayer 的监听器与 startProgressSaver 上；mpv 没有等价回调，
     * 因此用一条低频轮询把 position 写进同一个 PlaybackProgressRepository，
     * 保证两个内核看到的是同一份续播进度。
     */
    private fun startMpvProgressSaver(engine: MpvPlaybackEngine) {
        mpvProgressJob?.cancel()
        mpvProgressJob = progressPersistenceScope.launch {
            while (true) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                if (mpvEngine !== engine) return@launch
                val snapshot = engine.current
                if (snapshot.errorMessage != null) continue
                persistProgressValues(
                    playbackState = snapshot.playbackState,
                    positionMs = snapshot.positionMs.coerceAtLeast(0L),
                    durationMs = snapshot.durationMs.takeIf { it > 0L } ?: 0L,
                    force = false
                )
            }
        }
    }

    private fun currentSubtitleStyle(): SubtitleStyle {
        val style = _uiState.value.externalSubtitleStyle
        return SubtitleStyle(
            fontSizeSp = style.fontSizeSp,
            bottomPaddingPercent = style.bottomPaddingPercent,
            backgroundAlphaPercent = style.backgroundAlphaPercent
        )
    }

    /** 当前是否由 mpv 出画面。 */
    private fun isMpvActive(): Boolean =
        _uiState.value.engineKind == PlaybackEngineKind.MPV && mpvEngine != null

    /** 供切换决策读取的「当前播放事实」。 */
    private fun currentPlaybackFacts(): PlaybackSnapshot {
        mpvEngine?.let { engine ->
            if (isMpvActive()) return engine.current
        }
        val player = _uiState.value.player ?: return PlaybackSnapshot()
        return PlaybackSnapshot(
            isPlaying = player.isPlaying,
            playWhenReady = player.playWhenReady,
            playbackState = player.playbackState,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0L } ?: 0L,
            bufferedMs = player.bufferedPosition.coerceAtLeast(0L),
            speed = player.playbackParameters.speed
        )
    }

    /** 当前音轨在列表中的序号，作为跨内核的音轨交接提示。 */
    private fun currentAudioTrackHint(): Int? {
        val tracks = _uiState.value.audioTracks
        val index = tracks.indexOfFirst { it.selected && it.supported }
        return index.takeIf { it >= 0 }
    }

    /**
     * 在 exo 与 mpv 之间切换。
     *
     * 流程（对应任务要求的「切换流畅、状态不丢失」）：
     * 1. 决策：不可用/VR 时拒绝并给出理由；
     * 2. 记录旧内核进度并暂停（避免两路同时出声）；
     * 3. 先切 UI 的引擎标记，让渲染面立刻换成目标内核；
     * 4. 目标内核装载同一媒体、同一进度、同一倍速、同一外挂字幕与偏移；
     * 5. 目标就绪或超时后释放旧内核；任一步失败则回滚到旧内核并提示。
     */
    fun switchEngine(target: PlaybackEngineKind) {
        if (engineSwitchJob?.isActive == true) return
        val from = _uiState.value.engineKind
        val request = _uiState.value.playbackRequest
        if (request == null) {
            _uiState.update { it.copy(engineSwitchNotice = "播放地址尚未解析完成，无法切换内核") }
            return
        }
        val availability: EngineAvailability = PlaybackEngineFactory.availability(application, target)
        val decision = EngineSwitchPlanner.decide(
            from = from,
            to = target,
            current = currentPlaybackFacts(),
            targetAvailability = availability,
            vrActive = _uiState.value.vrMode.isVr,
            subtitleName = _uiState.value.activeExternalSubtitleName,
            subtitleOffsetMs = _uiState.value.externalSubtitleOffsetMs,
            audioHandoffHint = currentAudioTrackHint()
        )
        when (decision) {
            is EngineSwitchPlanner.Decision.Rejected -> {
                _uiState.update { it.copy(engineSwitchNotice = decision.reason) }
                return
            }
            is EngineSwitchPlanner.Decision.Allowed -> Unit
        }
        val handoff = (decision as EngineSwitchPlanner.Decision.Allowed).handoff
        _uiState.update {
            it.copy(
                engineSwitching = true,
                engineSwitchNotice = "正在切换到 ${target.label}…",
                errorMessage = null
            )
        }
        // 先停旧内核：两套解码器同时出画面既有音频叠加，也会抢占 mediacodec 实例。
        mpvEngine?.pause()
        _uiState.value.player?.pause()

        engineSwitchJob = viewModelScope.launch {
            runCatching {
                if (target == PlaybackEngineKind.MPV) {
                    switchToMpv(request, handoff)
                } else {
                    switchToExo(request, handoff)
                }
            }.onSuccess {
                _uiState.update {
                    it.copy(
                        engineSwitching = false,
                        engineKind = target,
                        engineSwitchNotice = null
                    )
                }
                logPlaybackEvent("player.engine.switched", mapOf("to" to target.id))
            }.onFailure { error ->
                if (error is CancellationException) throw error
                _uiState.update {
                    it.copy(
                        engineSwitching = false,
                        engineSwitchNotice = "切换到 ${target.label} 失败：${error.message ?: "未知错误"}"
                    )
                }
                logPlaybackEvent("player.engine.switchFailed", mapOf("to" to target.id), error)
            }
        }
    }

    private suspend fun switchToMpv(
        request: PlaybackRequest,
        handoff: EngineSwitchPlanner.Handoff
    ) {
        val error = startMpvSession(request, handoff.positionMs, autoPlay = handoff.wasPlaying)
        if (error != null) error("$error")
        val engine = mpvEngine ?: error("mpv 实例未创建")
        engine.setSpeed(handoff.speed)
        restoreSubtitleToMpv(engine, handoff)
        // 等首帧就绪（最多 MPV_SWITCH_READY_TIMEOUT_MS），超时不阻塞切换，只记录。
        val ready = withTimeoutOrNull(MPV_SWITCH_READY_TIMEOUT_MS) {
            engine.snapshot.first { snapshot ->
                snapshot.playbackState == PlaybackSnapshot.STATE_READY ||
                    snapshot.errorMessage != null
            }
        }
        if (ready == null) {
            logPlaybackEvent("player.engine.mpv.readyTimeout", emptyMap())
        }
        // 新内核已经在出画面，此时才释放旧 exo。
        _uiState.value.player?.let { oldPlayer ->
            oldPlayer.release()
            _uiState.update { it.copy(player = null) }
        }
    }

    private suspend fun switchToExo(
        request: PlaybackRequest,
        handoff: EngineSwitchPlanner.Handoff
    ) {
        // exo 的字幕走 MediaItem.SubtitleConfiguration，必须随媒体一起重建。
        // 关键差异：exo 用「改写时间轴的文件」表达偏移（SubtitleTimeShiftStore），
        // mpv 用 sub-delay 表达；因此切回 exo 时必须把当前偏移重新落到字幕文件上，
        // 否则偏移会在切换瞬间丢失（这是对齐表里「字幕偏移切换保真」的落点）。
        val subtitle = activeExternalSubtitle?.let { original ->
            val offsetMs = _uiState.value.externalSubtitleOffsetMs
            if (offsetMs == 0L) {
                original
            } else {
                runCatching {
                    withContext(Dispatchers.IO) {
                        subtitleTimeShiftStore.prepareSubtitle(original, offsetMs)
                    }
                }.getOrDefault(original)
            }
        }
        val previousMpv = synchronized(mpvEngineLock) {
            val old = mpvEngine
            mpvEngine = null
            old
        }
        mpvSnapshotJob?.cancel()
        mpvProgressJob?.cancel()
        val player = createPlayer(request, handoff.positionMs, subtitle)
        player.setPlaybackSpeed(handoff.speed)
        player.playWhenReady = handoff.wasPlaying
        _uiState.update {
            it.copy(
                player = player,
                mpvSnapshot = PlaybackSnapshot(),
                mpvDurationMs = 0L,
                mpvAudioTracks = emptyList(),
                mpvError = null,
                mpvReady = false,
                engineKind = PlaybackEngineKind.EXO
            )
        }
        previousMpv?.release()

        // 等 exo 出首帧或进入 READY，再释放 mpv（此时 mpv 已 pause，不会导致黑屏）。
        val ready = withTimeoutOrNull(MPV_SWITCH_READY_TIMEOUT_MS) {
            while (true) {
                if (player.playbackState == Player.STATE_READY || player.playerError != null) break
                delay(50)
            }
            true
        }
        if (ready == null) {
            logPlaybackEvent("player.engine.exo.readyTimeout", emptyMap())
        }
    }

    /** 把外挂字幕重新挂到新的 mpv 实例上（在线字幕因此不丢）。 */
    private fun restoreSubtitleToMpv(
        engine: MpvPlaybackEngine,
        handoff: EngineSwitchPlanner.Handoff
    ) {
        val subtitle = activeExternalSubtitle
        if (subtitle == null) {
            engine.clearExternalSubtitle()
            return
        }
        engine.setExternalSubtitle(
            subtitle = ExternalSubtitle(
                name = subtitle.name,
                uri = subtitle.uri,
                localFile = null
            ),
            offsetMs = handoff.subtitleOffsetMs
        )
    }

    /**
     * 当前引擎的音轨列表，统一成 exo 的 [AudioTrackOption] 形状。
     *
     * mpv 只有一条扁平轨道列表（`track-list/N/id` 即 `aid`），没有 exo 的 group/track 两层；
     * 为了让 UI 的对话框不做分支，这里把 mpv 的 aid 塞进 groupIndex、trackIndex 固定 0。
     * [currentAid] 是 mpv 当前生效的 aid（null = `aid=auto`），用于标出选中项——
     * `track-list/N` 上没有 selected 字段，只能拿全局 aid 对照，不能拍成常量 false。
     */
    private fun mpvAudioTrackOptions(
        tracks: List<MpvAudioTrack>,
        currentAid: Int?
    ): List<AudioTrackOption> =
        tracks.map { track ->
            AudioTrackOption(
                groupIndex = track.id,
                trackIndex = 0,
                title = track.title,
                details = if (track.language.isBlank()) "id=${track.id}" else "${track.language} · id=${track.id}",
                selected = track.id == currentAid,
                supported = true
            )
        }

    override fun onCleared() {
        val player = uiState.value.player
        progressJob?.cancel()
        recordPlaybackTime()
        initialSubtitleJob?.cancel()
        subtitleOffsetApplyJob?.cancel()
        dropFrameJob?.cancel()
        // mpv 侧：进度轮询、状态镜像、切换任务都必须先停，否则 release 之后仍可能写状态。
        engineSwitchJob?.cancel()
        mpvSnapshotJob?.cancel()
        mpvProgressJob?.cancel()
        lutObserverJob?.cancel()
        lutAutoTuneJob?.cancel()
        synchronized(mpvEngineLock) {
            mpvEngine?.release()
            mpvEngine = null
        }
        // 取消独立创建的进度持久化作用域，避免 ViewModel 销毁后协程泄漏（审查 2.2）
        progressPersistenceScope.cancel()
        if (player != null) {
            player.release()
        }
    }

    companion object {
        private const val PROGRESS_SAVE_INTERVAL_MS = 15_000L
        private const val PROGRESS_SAVE_POSITION_DELTA_MS = 10_000L
        private const val MIN_AUTOSAVE_POSITION_MS = 5_000L
        private const val PLAYBACK_RESOLVE_TIMEOUT_MS = 45_000L
        private const val SLOW_PLAYBACK_RESOLVE_LOG_MS = 8_000L
        private const val SLOW_SUBTITLE_LOAD_LOG_MS = 3_000L
        private const val SUBTITLE_OFFSET_APPLY_DEBOUNCE_MS = 400L
        private const val DROP_FRAME_MONITOR_INTERVAL_MS = 5_000L  // 丢帧采样周期
        private const val HEALTH_SNAPSHOT_INTERVAL = 6  // 每 6 个采样周期（30秒）输出一次健康快照

        /**
         * mpv 内核的缓存根目录名（挂在 `application.cacheDir` 下）。
         *
         * 这是「父目录」而不是字幕目录：MpvPlaybackEngine 会在它下面再建 `lut/`、
         * `mpv-subtitles/`、`lut-capture/` 三个子目录，并把同一个目录作为 mpv 的 `--cache-dir`。
         * 双内核重构时这行引用被留下、常量定义丢了，此处按上述约定补回。
         */
        private const val MPV_SUBTITLE_CACHE_DIR = "mpv"

        /**
         * 引擎切换时等待新内核出首帧的上限。
         *
         * 超时不阻塞切换（只记一条 readyTimeout 事件，见 switchToMpv/switchToExo），
         * 因此取 5 秒：与 [SLOW_PLAYBACK_RESOLVE_LOG_MS] 同量级，又短于 [PLAYBACK_RESOLVE_TIMEOUT_MS]，
         * 不会让用户在看换核转场时干等 45 秒。
         */
        private const val MPV_SWITCH_READY_TIMEOUT_MS = 5_000L

        fun factory(
            application: Application,
            videoUri: Uri,
            title: String,
            fileName: String,
            directLinkRepository: DirectLinkRepository,
            guangyaDirectLinkResolver: GuangyaDirectLinkResolver,
            cloud115Client: Cloud115Client,
            cloudStrmRecordRepository: CloudStrmRecordRepository,
            settingsRepository: AppSettingsRepository,
            playbackProgressRepository: PlaybackProgressRepository,
            movieRepository: MovieRepository,
            movieId: Long,
            usageStatsRepository: UsageStatsRepository
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    PlayerViewModel(
                        application = application,
                        videoUri = videoUri,
                        title = title,
                        fileName = fileName,
                        directLinkRepository = directLinkRepository,
                        guangyaDirectLinkResolver = guangyaDirectLinkResolver,
                        cloud115Client = cloud115Client,
                        cloudStrmRecordRepository = cloudStrmRecordRepository,
                        settingsRepository = settingsRepository,
                        playbackProgressRepository = playbackProgressRepository,
                        movieRepository = movieRepository,
                        movieId = movieId,
                        usageStatsRepository = usageStatsRepository
                    ) as T
            }
    }
}

data class PlayerUiState(
    val playbackRequest: PlaybackRequest? = null,
    val player: ExoPlayer? = null,
    val externalSubtitleStyle: ExternalSubtitleStyleSettings = ExternalSubtitleStyleSettings(),
    val progressBarStyle: PlayerProgressBarStyleSettings = PlayerProgressBarStyleSettings(),
    val vrMode: VrMode = VrMode.Normal2D,
    val vrControlMode: VrControlMode = VrControlMode.TouchAndSensor,
    val externalSubtitlePanelVisible: Boolean = false,
    val externalSubtitleSearching: Boolean = false,
    val externalSubtitleDownloading: Boolean = false,
    val externalSubtitleQueryNumber: String = "",
    val externalSubtitleProvider: SubtitleSearchProvider = SubtitleSearchProvider.Xunlei,
    val subtitleSearchProviderOptions: List<SubtitleSearchProvider> = SubtitleSearchProvider.entries,
    val localSubtitles: List<LocalSubtitleFile> = emptyList(),
    val onlineSubtitles: List<SubtitleSearchResult> = emptyList(),
    val externalSubtitleEnabled: Boolean = false,
    val activeExternalSubtitleName: String? = null,
    val externalSubtitleOffsetMs: Long = 0L,
    val externalSubtitleMessage: String? = null,
    val externalSubtitleError: String? = null,
    val audioTracks: List<AudioTrackOption> = emptyList(),
    val audioTrackAutomatic: Boolean = true,
    val subtitleFeaturesEnabled: Boolean = true,
    val isLoading: Boolean = false,
    val loadingMessage: String? = null,
    val errorMessage: String? = null,
    // ── 双内核（见对齐表）────────────────────────────────────────────────
    /** 当前出画面的内核。UI 据此选择 PlayerView / MpvVideoView。 */
    val engineKind: PlaybackEngineKind = PlaybackEngineKind.EXO,
    /** 切换进行中：UI 需要禁用切换按钮并显示进度提示。 */
    val engineSwitching: Boolean = false,
    /** 切换被拒绝/失败/回退的原因，null 表示无提示。 */
    val engineSwitchNotice: String? = null,
    /** mpv 的播放事实镜像；exo 模式下保持默认值。 */
    val mpvSnapshot: PlaybackSnapshot = PlaybackSnapshot(),
    /** mpv 已解析出的时长（缓存，避免每次重组都读快照）。 */
    val mpvDurationMs: Long = 0L,
    /** mpv 音轨（已映射成 exo 的 AudioTrackOption 形状，供同一个对话框使用）。 */
    val mpvAudioTracks: List<AudioTrackOption> = emptyList(),
    /** mpv 侧错误；非空即代表当前内核无法继续播放。 */
    val mpvError: String? = null,
    /** mpv 是否已完成首次装载（未就绪时 UI 显示加载态而不是黑屏）。 */
    val mpvReady: Boolean = false,
    /** mpv 硬件解码档位（hw+ / hw / sw）：面板选中态 + 下次建实例都用它。 */
    val mpvHwdecMode: MpvHwdecMode = MpvHwdecMode.HW,
    // ── 自动分析调参（文档 §4.1）──────────────────────────────────────────
    /** 正在截帧分析：UI 用它禁掉按钮并显示进度。 */
    val lutAutoTuneBusy: Boolean = false,
    /** 上一次分析的结论或失败原因；null 表示还没分析过。 */
    val lutAutoTuneMessage: String? = null,
)

data class AudioTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val title: String,
    val details: String,
    val selected: Boolean,
    val supported: Boolean
)

data class ExternalSubtitleStyleSettings(
    val fontSizeSp: Int = AppSettingsRepository.DEFAULT_EXTERNAL_SUBTITLE_FONT_SIZE_SP,
    val bottomPaddingPercent: Int = AppSettingsRepository.DEFAULT_EXTERNAL_SUBTITLE_BOTTOM_PADDING_PERCENT,
    val backgroundAlphaPercent: Int = AppSettingsRepository.DEFAULT_EXTERNAL_SUBTITLE_BACKGROUND_ALPHA_PERCENT
)

data class PlayerProgressBarStyleSettings(
    val widthDp: Int = AppSettingsRepository.DEFAULT_PLAYER_PROGRESS_BAR_WIDTH_DP,
    val colorArgb: Int = AppSettingsRepository.DEFAULT_PLAYER_PROGRESS_BAR_COLOR,
    val alphaPercent: Int = AppSettingsRepository.DEFAULT_PLAYER_PROGRESS_BAR_ALPHA_PERCENT
)

private fun normalizeSubtitleSearchNumber(number: String, provider: SubtitleSearchProvider): String =
    when (provider) {
        SubtitleSearchProvider.Xunlei -> normalizeXunleiSubtitleNumber(number)
        SubtitleSearchProvider.Cloud115 -> normalizeCloud115SubtitleNumber(number)
        SubtitleSearchProvider.Avsubtitles -> normalizeDefaultSubtitleNumber(number)
    }

private fun LocalSubtitleFile.mimeType(): String {
    return when (name.substringAfterLast('.', "").lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

private fun LocalSubtitleFile.subtitleOffsetKey(): String =
    uri.toString() + "|" + name

private fun SubtitleSearchResult.searchIdentity(): String =
    "${provider.id}|$cid|$ext|$signature"

private fun subtitleSearchKey(
    provider: SubtitleSearchProvider,
    number: String,
    videoDurationMs: Long
): String {
    val durationKey = if (provider == SubtitleSearchProvider.Avsubtitles) videoDurationMs / 1_000L else 0L
    return provider.id + "|" + number.uppercase() + "|" + durationKey
}

private fun subtitleSearchEmptyMessage(
    provider: SubtitleSearchProvider,
    results: List<SubtitleSearchResult>
): String? {
    if (results.isNotEmpty()) return null
    return when (provider) {
        SubtitleSearchProvider.Cloud115 -> "网盘中没有找到包含番号的字幕文件"
        SubtitleSearchProvider.Xunlei -> "没有找到可用字幕"
        SubtitleSearchProvider.Avsubtitles -> "没有找到时长匹配的字幕"
    }
}

private data class CachedSubtitleSearch(
    val results: List<SubtitleSearchResult>,
    val message: String?
)

