package com.example.localmovielibrarz.ui.player

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.example.localmovielibrarz.playback.engine.MpvPlaybackEngine
import com.example.localmovielibrarz.playback.face.ExoFrameGrabber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 睡眠定时选项（分钟）。 */
enum class SleepTimerOption(val minutes: Int, val label: String) {
    M15(15, "15 分钟"),
    M30(30, "30 分钟"),
    M45(45, "45 分钟"),
    M60(60, "60 分钟"),
    M90(90, "90 分钟"),
    M120(120, "120 分钟")
}

/** 统计浮层的一行。 */
data class StatsLine(val label: String, val value: String)

/**
 * 播放器工具集（v2，参考 mpvRx 能力清单接入）：A-B 循环、逐帧、睡眠定时、统计浮层、截图、音量增强。
 *
 * ## 为什么单独成类
 *
 * `PlayerViewModel` 已有 2700+ 行，承担了解析/双内核/字幕/进度/诊断等职责。新增工具都是
 * 「对当前内核发一条指令 + 维护一点 UI 状态」的同构逻辑，集中在这里：
 * - 每个功能只写一处「mpv 怎么做 / exo 怎么做」的分支；
 * - ViewModel 只负责把当前内核实例（[mpv] / [exo]）交进来，切换内核后自动跟随；
 * - 生命周期统一由 [release] 收尾。
 */
@OptIn(UnstableApi::class)
internal class PlayerTools(
    private val app: Application,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<PlayerUiState>,
    /** 当前活跃的 mpv 实例；exo 模式下返回 null。 */
    private val mpv: () -> MpvPlaybackEngine?,
    /** 当前活跃的 exo 实例；mpv 模式下返回 null。 */
    private val exo: () -> ExoPlayer?,
    private val positionMs: () -> Long,
    private val exoDecoderName: () -> String?,
    private val log: (String, Map<String, String?>) -> Unit
) {

    private var sleepJob: Job? = null
    private var abWatchJob: Job? = null
    private var statsJob: Job? = null
    private var loudness: LoudnessEnhancer? = null
    private var loudnessSession: Int = 0

    // ─────────────────────────────────────────────────────────────────────────
    // A-B 循环
    // ─────────────────────────────────────────────────────────────────────────

    /** 依次：设 A → 设 B → 清除。 */
    fun cycleAbLoop() {
        val s = state.value
        val pos = positionMs()
        when {
            s.abLoopA == null -> {
                state.update { it.copy(abLoopA = pos, abLoopB = null, toastMessage = "A 点已设定：${formatClock(pos)}") }
            }
            s.abLoopB == null -> {
                val a = s.abLoopA
                if (pos <= a + 500) {
                    state.update { it.copy(toastMessage = "B 点需要晚于 A 点") }
                    return
                }
                state.update { it.copy(abLoopB = pos, toastMessage = "A-B 循环：${formatClock(a)} → ${formatClock(pos)}") }
                applyAbLoop()
            }
            else -> clearAbLoop()
        }
    }

    fun clearAbLoop() {
        state.update { it.copy(abLoopA = null, abLoopB = null, toastMessage = "已取消 A-B 循环") }
        mpv()?.setAbLoop(null, null)
        abWatchJob?.cancel()
    }

    /** 内核切换后重放 A-B 设置。 */
    fun applyAbLoop() {
        val a = state.value.abLoopA
        val b = state.value.abLoopB
        mpv()?.setAbLoop(a, b)
        abWatchJob?.cancel()
        if (a == null || b == null) return
        // exo 没有原生 A-B 循环：低频轮询位置，越过 B 就跳回 A。
        abWatchJob = scope.launch {
            while (isActive) {
                val player = exo()
                if (player != null && player.currentPosition >= b) player.seekTo(a)
                delay(AB_POLL_MS)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 逐帧
    // ─────────────────────────────────────────────────────────────────────────

    fun frameStep(forward: Boolean) {
        mpv()?.let {
            it.frameStep(forward)
            return
        }
        val player = exo() ?: return
        player.playWhenReady = false
        val fps = player.videoFormat?.frameRate?.takeIf { it > 0f } ?: 24f
        val frameMs = (1000f / fps).toLong().coerceAtLeast(1L)
        // exo 默认 CLOSEST_SYNC 只能跳关键帧，逐帧必须临时改成精确 seek，用完恢复。
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo((player.currentPosition + if (forward) frameMs else -frameMs).coerceAtLeast(0L))
        scope.launch {
            delay(600)
            exo()?.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 睡眠定时
    // ─────────────────────────────────────────────────────────────────────────

    fun setSleepTimer(option: SleepTimerOption?) {
        sleepJob?.cancel()
        if (option == null) {
            state.update { it.copy(sleepTimerEndsAt = null, toastMessage = "已取消睡眠定时") }
            return
        }
        val endsAt = SystemClock.elapsedRealtime() + option.minutes * 60_000L
        state.update { it.copy(sleepTimerEndsAt = endsAt, toastMessage = "${option.label}后暂停播放") }
        sleepJob = scope.launch {
            while (isActive) {
                val left = endsAt - SystemClock.elapsedRealtime()
                if (left <= 0L) break
                delay(left.coerceAtMost(30_000L))
            }
            mpv()?.pause()
            exo()?.playWhenReady = false
            state.update { it.copy(sleepTimerEndsAt = null, toastMessage = "睡眠定时已到，播放已暂停") }
            log("player.sleepTimer.fired", mapOf("minutes" to option.minutes.toString()))
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 统计浮层
    // ─────────────────────────────────────────────────────────────────────────

    fun setStatsVisible(visible: Boolean) {
        statsJob?.cancel()
        state.update { it.copy(statsVisible = visible, stats = if (visible) it.stats else emptyList()) }
        if (!visible) return
        statsJob = scope.launch {
            while (isActive) {
                state.update { it.copy(stats = collectStats()) }
                // 暂停时降频（参考 mpvRx 的轮询退避）：数据基本不变，没必要每秒读一次 JNI。
                delay(if (state.value.mpvSnapshot.isPlaying || exo()?.isPlaying == true) 1_000L else 2_000L)
            }
        }
    }

    private fun collectStats(): List<StatsLine> {
        mpv()?.readStats()?.let { st ->
            return listOfNotNull(
                StatsLine("内核", "MPV · ${st.vo.ifBlank { "?" }}"),
                StatsLine("视频", "${st.videoCodec.ifBlank { "?" }} · ${st.width}×${st.height}"),
                StatsLine("解码", st.hwdec.ifBlank { "软解" }),
                StatsLine("帧率", "%.2f / 实测 %.2f fps".format(Locale.US, st.containerFps, st.estimatedFps)),
                StatsLine("丢帧", "渲染 ${st.droppedFrames} · 解码 ${st.decoderDroppedFrames}"),
                StatsLine("码率", formatBitrate(st.videoBitrate)),
                StatsLine("缓存", "%.1f 秒".format(Locale.US, st.cacheSeconds)),
                StatsLine("音画差", "%+.3f 秒".format(Locale.US, st.avsync)),
                StatsLine("音频", st.audioCodec.ifBlank { "?" }),
                st.hdrChain?.let { StatsLine("HDR", "hdr-toys · $it") }
            )
        }
        val player = exo() ?: return emptyList()
        val vf = player.videoFormat
        val counters = player.videoDecoderCounters
        return listOfNotNull(
            StatsLine("内核", "ExoPlayer"),
            StatsLine("视频", "${vf?.sampleMimeType?.substringAfter('/') ?: "?"} · ${vf?.width ?: 0}×${vf?.height ?: 0}"),
            StatsLine("解码", exoDecoderName() ?: "?"),
            StatsLine("帧率", vf?.frameRate?.takeIf { it > 0f }?.let { "%.2f fps".format(Locale.US, it) } ?: "?"),
            counters?.let { StatsLine("丢帧", "丢弃 ${it.droppedBufferCount} · 已渲染 ${it.renderedOutputBufferCount}") },
            StatsLine("码率", formatBitrate(vf?.bitrate?.toDouble()?.takeIf { it > 0 } ?: 0.0)),
            StatsLine("缓冲", "%.1f 秒".format(Locale.US, (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L) / 1000f)),
            StatsLine("音频", player.audioFormat?.sampleMimeType?.substringAfter('/') ?: "?")
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 截图
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 截图并保存到相册（Android 10+ 写入 `Pictures/HomeMovie`；更早版本写入应用专属图片目录，免存储权限）。
     * mpv 截的是含字幕的成片；exo 截的是原片关键帧（不含调色，见 [ExoFrameGrabber] 的说明）。
     */
    fun takeScreenshot(title: String, mediaUri: String?, headers: Map<String, String>) {
        scope.launch {
            state.update { it.copy(toastMessage = "正在截图…") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = buildFileName(title)
                    val engine = mpv()
                    if (engine != null) {
                        val tmp = File(app.cacheDir, "shot-${System.nanoTime()}.png")
                        try {
                            if (!engine.screenshotTo(tmp)) error("mpv 截图超时")
                            saveToGallery(name) { out -> FileInputStream(tmp).use { it.copyTo(out) } }
                        } finally {
                            tmp.delete()
                        }
                    } else {
                        val uri = mediaUri ?: error("播放地址未就绪")
                        val bitmap = ExoFrameGrabber.grab(app, uri, headers, positionMs(), maxSide = 3840)
                            ?: error(ExoFrameGrabber.lastFailure ?: "抽帧失败")
                        try {
                            saveToGallery(name) { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
            val message = result.fold(
                onSuccess = { "截图已保存：$it" },
                onFailure = { "截图失败：${it.message}" }
            )
            state.update { it.copy(toastMessage = message) }
            log("player.screenshot", mapOf("ok" to result.isSuccess.toString(), "error" to result.exceptionOrNull()?.message))
        }
    }

    private fun saveToGallery(name: String, write: (OutputStream) -> Unit): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/HomeMovie")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = app.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("无法创建相册条目")
            try {
                resolver.openOutputStream(uri)?.use(write) ?: error("无法写入相册")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
            return "相册/HomeMovie/$name"
        }
        val dir = File(app.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "HomeMovie").apply { mkdirs() }
        val file = File(dir, name)
        file.outputStream().use(write)
        return file.absolutePath
    }

    private fun buildFileName(title: String): String {
        val safe = title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40).ifBlank { "frame" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "${safe}_${formatClock(positionMs()).replace(':', '-')}_$stamp.png"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 音量增强
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 音量增强 0..100%。mpv 用 `volume`（init 时已按设置放开 `volume-max`）；
     * exo 用系统 [LoudnessEnhancer]（100% ≈ +12 dB）。
     */
    fun setVolumeBoost(percent: Int) {
        val p = percent.coerceIn(0, 100)
        state.update { it.copy(volumeBoostPercent = p) }
        mpv()?.setVolumePercent(100 + p)
        val player = exo() ?: return
        runCatching {
            val session = player.audioSessionId
            if (loudness == null || loudnessSession != session) {
                loudness?.release()
                loudness = LoudnessEnhancer(session)
                loudnessSession = session
            }
            loudness?.setTargetGain(p * 12)
            loudness?.enabled = p > 0
        }.onFailure { Log.w(TAG, "LoudnessEnhancer 不可用：${it.message}") }
    }

    /** exo 实例重建后调用：旧会话的增强器作废。 */
    fun onExoRecreated() {
        loudness?.release()
        loudness = null
        loudnessSession = 0
        val boost = state.value.volumeBoostPercent
        if (boost > 0) setVolumeBoost(boost)
        applyAbLoop()
    }

    fun release() {
        sleepJob?.cancel()
        abWatchJob?.cancel()
        statsJob?.cancel()
        runCatching { loudness?.release() }
        loudness = null
    }

    companion object {
        private const val TAG = "PlayerTools"
        private const val AB_POLL_MS = 150L

        fun formatClock(ms: Long): String {
            val total = (ms / 1000).coerceAtLeast(0)
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }

        private fun formatBitrate(bps: Double): String = when {
            bps <= 0 -> "?"
            bps >= 1_000_000 -> "%.1f Mbps".format(Locale.US, bps / 1_000_000)
            else -> "%.0f kbps".format(Locale.US, bps / 1000)
        }
    }
}
