package com.example.localmovielibrarz.playback.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.localmovielibrarz.mpv.MPVLib
import com.example.localmovielibrarz.playback.lut.LutFileWriter
import com.example.localmovielibrarz.playback.lut.LutSettings
import com.example.localmovielibrarz.playback.lut.LutSkinAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * mpv 引擎实现。
 *
 * 设计要点：
 * 1. **生命周期全部落在主线程**：`create`/`init`/`destroy` 与 mpv 的 JNI 约定绑定线程，
 *    这里用 [mainHandler] 保证串行；原生事件回调则在 [MPVLib.Listener] 里切回主线程。
 * 2. **状态单向流动**：mpv 属性 → [MutableStateFlow]<[PlaybackSnapshot]>，UI 只读 Flow，
 *    不直接查询 mpv，避免「UI 线程阻塞在 JNI 上」。
 * 3. **一切失败都降级成错误文案**：不抛异常、不黑屏。出错时调用方可以据此切回 Exo。
 */
class MpvPlaybackEngine(
    private val context: Context,
    private val cacheDir: File,
    /** 硬件解码档位（hw+ / hw / sw）。运行期可用 [setHwdecMode] 热切换。 */
    private val hwdecMode: MpvHwdecMode = MpvHwdecMode.HW
) : PlaybackEngine {

    override val kind: PlaybackEngineKind = PlaybackEngineKind.MPV

    private val mainHandler = Handler(Looper.getMainLooper())
    private val _snapshot = MutableStateFlow(PlaybackSnapshot())
    override val snapshot: StateFlow<PlaybackSnapshot> by lazy { _snapshot.asStateFlow() }

    /** PlaybackEngine 接口要求非挂起读取，这里给 UI 侧一个直接读取的快照。 */
    val current: PlaybackSnapshot get() = _snapshot.value

    @Volatile
    private var created = false

    @Volatile
    private var released = false

    private val fileLoaded = AtomicBoolean(false)

    /**
     * 当前生效的解码档位。
     *
     * 与构造参数分开的原因：[setHwdecMode] 是运行期热切换，构造参数只在建实例时读一次；
     * 错误文案里要报「现在到底用的哪一档」，所以必须有一个会被改写的副本。
     */
    @Volatile
    private var activeHwdecMode: MpvHwdecMode = hwdecMode

    /** mpv 丢帧观测的上一次采样值；-1 表示还没采过（首帧只记基线）。 */
    private var lastFrameDropCount = -1
    private var lastDecoderDropCount = -1
    private var lastMistimedCount = -1

    /** 丢帧回调（渲染丢帧 / 解码丢帧 / 时序错帧 / 当前解码器），见 [scheduleFrameStatsPoll]。 */
    var frameStatsReporter: ((FrameStats) -> Unit)? = null

    /**
     * 是否已经给过「video-reload 重建视频输出」这一次自愈机会（每次装载复位）。
     *
     * mpv 的 android VO 一旦在 `vo_android_init()` 失败，`mpctx->video_out` 就是 NULL，
     * 此时再改 `wid` 也不会自动重建（`command.c` 的 UPDATE_VO 分支要求 video_out 非空），
     * 只有 `video-reload` 会重新走一遍 VO 初始化。所以只能给一次机会，不能无限重试。
     */
    private var videoReloadTried = 0

    private var currentUri: String? = null
    private var currentHeaders: Map<String, String> = emptyMap()

    private var pendingSeekMs: Long? = null
    private var pendingSubtitle: Pair<ExternalSubtitle, Long>? = null
    private var pendingSubtitleStyle: SubtitleStyle? = null
    private var subtitleOffsetMs: Long = 0L
    private var activeSubtitlePath: String? = null

    /**
     * 最近交给 mpv 的媒体来源。`content://` 走 `/proc/self/fd/<fd>` 时，
     * fd 必须撑到 mpv 自己完成 open 为止（mpv 用 open() 在进程内再复制一份，
     * 之后才与我们无关）。保留最近 2 个足够覆盖「replace 装载 + 上一次还在收尾」。
     */
    private val mediaSources = ArrayDeque<MpvMediaSource>()

    /** 解析媒体 URL 的线程：`content://` 可能要落盘，绝不能堵主线程。 */
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mpv-media-resolve").apply { isDaemon = true }
    }

    /** 装载代次：快速连点两次时只让最后一次的结果生效。 */
    private val loadGeneration = AtomicInteger(0)

    /** 最近一次真正下发给 mpv 的 URL，仅用于错误文案与日志。 */
    @Volatile
    private var lastLoadUrl: String? = null

    @Volatile
    private var lastLoadScheme: String? = null

    /** 最近一次装载**真正交给 mpv 的** URL（走代理时是 127.0.0.1 那条），只进日志。 */
    @Volatile
    private var lastMpvLoadUrl: String? = null

    /**
     * 把 mpv 的 https 直链改道到本机回环代理，由 OkHttp 代跑 TLS/重定向/UA。
     *
     * 这是本轮修复的核心，理由见 [MpvHttpProxy] 的类注释：本仓 libavformat 是
     * 静态 mbedtls 构建、不读系统 CA，且 exo 的 `DefaultHttpDataSource` 那套
     * 「UA + 请求头 + 跨协议重定向 + 系统 TLS」在 mpv 侧没有任何等价配置项。
     */
    private var httpProxy: MpvHttpProxy? = null

    /** mpv 最近的错误/告警日志（最多 [MAX_RECENT_LOGS] 条），用于把 loading_failed 的根因带出来。 */
    private val recentMpvLogs = ArrayDeque<String>()

    /** 日志旁路：只在原生线程上做一次加锁入队，绝不做 IO。 */
    private val mpvLogSink = object : MPVLib.LogSink {
        override fun onLog(level: Int, line: String) {
            synchronized(recentMpvLogs) {
                recentMpvLogs.addLast(line)
                while (recentMpvLogs.size > MAX_RECENT_LOGS) recentMpvLogs.removeFirst()
            }
        }
    }

    /** 事件监听：所有回调都从原生事件线程来，统一切到主线程再处理。 */
    private val mpvListener = object : MPVLib.Listener {
        override fun onEvent(eventId: Int) {
            mainHandler.post { handleEvent(eventId) }
        }

        override fun onEndFile(reason: Int, error: Int) {
            mainHandler.post { handleEndFile(reason, error) }
        }

        override fun onProperty(name: String, value: Any?) {
            mainHandler.post { handleProperty(name, value) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 创建并初始化 mpv 实例。必须在主线程调用。
     * @return null 表示成功，否则返回面向用户的错误文案。
     */
    fun initialize(): String? {
        if (released) return "mpv 引擎已释放"
        if (created) return null

        cacheDir.mkdirs()

        // 从第一行日志开始收：createEngine / initialize 阶段的失败原因同样要进错误文案。
        MPVLib.logSinks.add(mpvLogSink)

        // 回环代理与 mpv 实例无关，先起好；后面每次 loadfile 只做一次 URL 登记。
        // 起不来不算致命——退回直连（旧行为），只是 https 直链仍旧押在 mbedtls 上。
        val proxy = MpvHttpProxy()
        httpProxy = if (proxy.start()) proxy else null
        if (httpProxy == null) {
            Log.w(TAG, "回环代理未启动：本次 mpv 将用自带 mbedtls 直连 https（与 exo 不等价）")
        }

        val createRet = MPVLib.createEngine(context)
        if (createRet != 0) {
            return abortInit("mpv 创建失败（code=$createRet）")
        }

        // hwdec 必须问在 createEngine 之后：mediacodec 依赖 FFmpeg 的 JNI 桥
        // （av_jni_set_java_vm），而桥是在 createEngine → mpv_create 这条链里注册的。
        // 原来在 createEngine 之前问，桥必然还没就绪，于是永远退回 hwdec=no 软解，
        // 表现为「能播但一放 4K 就烫」——和 -13 无关，但属于移植缺陷，一并修掉。
        val hwdec = MpvOptions.decideHwdec(activeHwdecMode)

        // 信任锚只对**直连兜底路径**有意义：mpv 0.41 的 stream_lavf 里 tls_verify 默认是
        // false（`OPT_FLAG("tls-verify", …, 1, false)`），所以正常情况它压根不校验证书。
        // 这里仍把 PEM 包喂进去并显式 tls-verify=yes，只为「代理没起来、退回直连」时
        // 别把证书校验也一起丢掉（与 exo 的系统级校验对齐）。主路径（回环代理）不走这里。
        val caFilePath = MpvCaBundle.ensure(context)
        if (caFilePath == null) {
            Log.w(
                TAG,
                "CA 包不可用：代理正常时不影响播放；若退回直连且 tls_verify=yes，https 直链会失败"
            )
        }

        // 所有 --option 必须在 mpv_initialize() 之前设置，否则被静默忽略。
        MpvOptions.build(context, hwdec, cacheDir, caFilePath).forEach { (key, value) ->
            val ret = MPVLib.setOption(key, value)
            if (ret < 0) Log.w(TAG, "设置 mpv 选项 $key=$value 失败: $ret")
        }

        val initRet = MPVLib.initialize()
        if (initRet != 0) {
            MPVLib.destroyEngine()
            return abortInit("mpv 初始化失败（code=$initRet）")
        }

        MPVLib.listeners.add(mpvListener)
        observeProperties()
        created = true
        // 成为原生实例的属主：此后只有本实例可以 destroyEngine()。
        liveEngine = this
        val proxyPort = httpProxy?.port ?: -1
        Log.i(
            TAG,
            "mpv 引擎就绪，hwdec=$hwdec，CA=${caFilePath ?: "(none)"}，" +
                "回环代理=${if (proxyPort > 0) "127.0.0.1:$proxyPort" else "未启用"}"
        )
        return null
    }

    /**
     * 初始化半途失败时的统一收尾：把刚才启动的旁路和代理收回去，避免下次
     * `initialize()` 重入时出现「两个代理、两个日志 sink」。
     */
    private fun abortInit(reason: String): String {
        MPVLib.logSinks.remove(mpvLogSink)
        synchronized(recentMpvLogs) { recentMpvLogs.clear() }
        httpProxy?.close()
        httpProxy = null
        val detail = MPVLib.loadErrorMessage().orEmpty()
        return if (detail.isBlank()) reason else "$reason：$detail"
    }

    private fun observeProperties() {
        // 与 PlaybackSnapshot 字段一一对应。用 DOUBLE 取时间属性（秒），避免 INT64
        // 在时间类属性上的单位歧义（microseconds），换算成毫秒只做一次。
        MPVLib.observe("time-pos", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observe("duration", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observe("pause", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observe("paused-for-cache", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observe("speed", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observe("eof-reached", MPVLib.MPV_FORMAT_FLAG)
        MPVLib.observe("width", MPVLib.MPV_FORMAT_INT64)
        MPVLib.observe("height", MPVLib.MPV_FORMAT_INT64)
        MPVLib.observe("track-list", MPVLib.MPV_FORMAT_NONE)
        MPVLib.observe("demuxer-cache-time", MPVLib.MPV_FORMAT_DOUBLE)
    }

    override fun release() {
        if (released) return
        released = true
        // 先停 LUT 线程：它跑完最后一步时还会 post 回主线程，那里有 released 检查兜底。
        lutExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        synchronized(mediaSources) {
            while (mediaSources.isNotEmpty()) runCatching { mediaSources.removeFirst().close() }
        }
        mainHandler.post {
            MPVLib.listeners.remove(mpvListener)
            MPVLib.logSinks.remove(mpvLogSink)
            // 只有当本实例仍是原生实例的属主时才销毁。切换内核时旧实例的 release 会晚到，
            // 那时 g_mpv 已经属于新实例，这里若无条件 destroy，就会把新实例拆掉。
            if (created) {
                if (liveEngine === this) {
                    MPVLib.destroyEngine()
                    liveEngine = null
                } else {
                    // 新实例 create() 时已经 destroy 掉旧原生实例，这里无事可做——
                    // 但必须留痕，否则「旧引擎把新引擎拆了」这类问题永远查不到。
                    Log.i(TAG, "release(): 本实例已不是原生实例属主，跳过 destroyEngine（新实例仍存活）")
                }
                created = false
            }
            // 代理必须在 destroyEngine 之后关：先断 socket，mpv 收尾时读循环会先报
            // 一条无意义的 "read error"，把真正的失败原因淹掉。
            httpProxy?.close()
            httpProxy = null
            _snapshot.value = PlaybackSnapshot(playbackState = PlaybackSnapshot.STATE_IDLE)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 装载与播放控制
    // ─────────────────────────────────────────────────────────────────────────

    override fun load(
        uri: String,
        headers: Map<String, String>,
        startPositionMs: Long,
        autoPlay: Boolean
    ) {
        if (released) return
        currentUri = uri
        currentHeaders = headers
        pendingSeekMs = startPositionMs.takeIf { it > 0L }
        fileLoaded.set(false)

        val generation = loadGeneration.incrementAndGet()
        // content:// 要取 fd（或落盘）才能交给 mpv，这一步可能在 IO 上耗时，
        // 绝不能放主线程；解析完再回主线程发 loadfile，保持原来的线程约定。
        ioExecutor.execute {
            val source = runCatching { MpvMediaSources.resolve(context, uri, cacheDir) }
                .onFailure { Log.e(TAG, "解析媒体 URL 失败：$uri", it) }
                .getOrNull()
            mainHandler.post { startLoad(generation, source, uri, headers, autoPlay) }
        }
    }

    /**
     * 真正下发 `loadfile`。
     *
     * @param source 归一化后的媒体来源；null 表示解析异常，退化为原 URI。
     */
    private fun startLoad(
        generation: Int,
        source: MpvMediaSource?,
        rawUri: String,
        headers: Map<String, String>,
        autoPlay: Boolean
    ) {
        // 快速连点/切集时旧任务可能晚到，代次对不上就丢弃。
        if (released || generation != loadGeneration.get()) return
        if (!created) {
            Log.w(TAG, "load() 时引擎未就绪，丢弃本次装载")
            return
        }

        val url = source?.url ?: rawUri
        lastLoadUrl = url
        lastLoadScheme = source?.scheme
        // 上一次装载的 mpv 日志不能混进这一次的失败文案
        synchronized(recentMpvLogs) { recentMpvLogs.clear() }
        // 同理，上一次装载的「已尝试 video-reload」状态也不能带过来
        resetVideoOutputAudit()

        // **网络路径的全部差异都在这一行收口**：
        // https/http 直链一律改道本机回环代理，TLS 握手、跨协议重定向、UA、请求头
        // 交给 OkHttp（= 系统 TLS 栈），与 exo 的 DefaultHttpDataSource 逐项对齐；
        // mpv 只跟 127.0.0.1 说 HTTP/1.1，不再触碰它那份静态 mbedtls。
        // 只有代理未启动或 URL 非 http(s) 时才退回老路（把 header 塞给 stream_lavf）。
        val proxiedUrl = httpProxy?.register(url, headers)
        val mpvUrl = proxiedUrl ?: url
        lastMpvLoadUrl = mpvUrl
        if (proxiedUrl != null) {
            Log.i(TAG, "loadfile 走回环代理：${hideQuery(url)} → $proxiedUrl")
        } else {
            Log.i(
                TAG,
                "loadfile 直连：${hideQuery(url)} 原始 scheme=" +
                    "${source?.scheme ?: Uri.parse(rawUri).scheme}"
            )
            applyHeaders(headers)
        }

        // ── 黑屏根因修复：先挂渲染面，再 loadfile ────────────────────────────
        // mpv 的 android VO 在**第一次初始化视频输出**时读一次 `wid`；读到 0 就
        // `MP_FATAL("Missing surface pointer")` 直接失败，此后 mpv 只剩音频——
        // 音频、进度、时长全都正常，画面永远是黑的，也没有 end-file 错误码。
        // 旧实现把「挂 Surface」门控在 uiState.mpvReady 上，而 mpvReady 要等
        // loadfile 之后才变 true，于是 loadfile 必然抢在 wid 之前：每次都黑屏。
        // 现在反过来——等 [MPVLib.surfacePresent] 就绪再下发；超时（例如 View 被
        // 别的界面挡着没起来）仍然下发，但不会静默，由 [auditVideoOutput] 报出来。
        val finalUrl = mpvUrl
        val finalSource = source
        awaitSurfaceThen {
            if (released || generation != loadGeneration.get()) return@awaitSurfaceThen
            MPVLib.cmd("set", "pause", if (autoPlay) "no" else "yes")
            MPVLib.cmd("loadfile", finalUrl, "replace")

            // 新的已经下发，旧的才有资格关：content:// 的 fd 要撑到 mpv 自己 open 完。
            if (finalSource != null) retainSource(finalSource)

            updateSnapshot { it.copy(playWhenReady = autoPlay, errorMessage = null) }
        }
    }

    /**
     * 等到 mpv 已经拿到有效 Surface（原生 `wid` 非 0）后再执行 [action]。
     *
     * 为什么用轮询而不是回调：SurfaceView 的 `surfaceCreated` 与
     * [MPVLib.attachSurfaceSafe] 都在主线程，这里的 `postDelayed` 与它们天然串行，
     * 一个约一帧的轮询就足够，不必再引入一套监听器生命周期。
     *
     * 超时后照常执行 [action]：只出声总比什么都不播好，但必须让原因可见，
     * 这一条由 [auditVideoOutput] 兜底。
     */
    private fun awaitSurfaceThen(attempt: Int = 0, action: () -> Unit) {
        if (released) return
        // 每一轮先让原生确认「**当前句柄**的 wid 已写入」再问状态。
        // 只问 Kotlin 侧缓存会被上一代句柄的旧状态骗过去——上一版仍黑屏正是这个原因：
        // 新引擎 create() 换掉了 mpv 句柄，布尔量却还是 true，于是这里立刻放行 loadfile。
        MPVLib.ensureSurfaceSafe()
        if (MPVLib.surfacePresent) {
            action()
            return
        }
        if (attempt >= SURFACE_WAIT_MAX_ATTEMPTS) {
            Log.w(
                TAG,
                "等待渲染面超时（${attempt * SURFACE_WAIT_RETRY_MS}ms 仍未挂上 Surface）：" +
                    "仍下发 loadfile，若画面全黑见后续「mpv 画面未建立」报错"
            )
            action()
            return
        }
        mainHandler.postDelayed({ awaitSurfaceThen(attempt + 1, action) }, SURFACE_WAIT_RETRY_MS)
    }

    private fun retainSource(source: MpvMediaSource) {
        synchronized(mediaSources) {
            mediaSources.addLast(source)
            while (mediaSources.size > 2) {
                runCatching { mediaSources.removeFirst().close() }
            }
        }
    }

    /**
     * 下发本次装载的 HTTP 请求头。
     *
     * exo 侧是 `DefaultHttpDataSource.setDefaultRequestProperties(Map)`：**逐跳**随请求带出。
     * mpv 侧对应 stream_lavf 的 `http-header-fields` / `user-agent`，它们是**流选项**，
     * 不是全局属性——`libmpv.so` 里 ytdl_hook 的写法是
     * `set_property("file-local-options/http-header-fields", …)`（见二进制内嵌脚本），
     * 带 `file-local-options/` 前缀才能在「下一次 loadfile」时被应用。
     *
     * 两种写法都下发：
     * - 裸名：0.41 里对 stream 选项的裸名 set_property 是否生效取决于版本，
     *   实测无法在本环境确认，保留作为兼容路径；
     * - 带前缀：与内嵌 ytdl_hook 一致，属于该版本自证过的用法。
     * 任一为 0 即代表至少一条路径被接受；两条都非 0 会在日志里看到 -5，
     * 那就是「选项名对不上」，必须查二进制而不是改参数。
     */
    private fun applyHeaders(headers: Map<String, String>) {
        if (headers.isEmpty()) {
            setStreamOption("http-header-fields", "")
            return
        }
        val userAgent = headers.entries
            .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
            ?.value
        if (!userAgent.isNullOrBlank()) {
            setStreamOption("user-agent", userAgent)
        }
        val fields = headers
            .filterKeys { !it.equals("User-Agent", ignoreCase = true) }
            .entries
            .joinToString(",") { "${it.key}: ${escapeListSeparator(it.value)}" }
        setStreamOption("http-header-fields", fields)
    }

    /**
     * 下发一个 **stream_lavf 的流选项**（`http-header-fields` / `user-agent`）。
     *
     * 顺序上先写 `file-local-options/<name>`（与 libmpv.so 内嵌 ytdl_hook 同一写法，
     * 下一次 loadfile 时被应用），**再**写裸名作为兜底。两种情况都只告警不抛：
     * 这条路径不该因为某个选项名对不上就中断装载。
     *
     * 只在下发失败时告警，成功不刷日志——每次切集都会走一遍。
     */
    private fun setStreamOption(name: String, value: String) {
        val localRet = MPVLib.setString("file-local-options/$name", value)
        val globalRet = MPVLib.setString(name, value)
        if (localRet < 0 && globalRet < 0) {
            Log.w(
                TAG,
                "请求头下发失败：$name 的 file-local-options 返回 $localRet、裸名返回 $globalRet —— " +
                    "该 URL 若需要 UA/Referer，服务端会回 403，最终仍表现为 loading_failed(-13)"
            )
        }
    }

    /**
     * 下发一个运行期 property，并把 mpv 的错误码落进日志。
     *
     * 原实现的 `MPVLib.setString` 返回 Unit 且内部 `runCatching{}` 吞异常，
     * 「选项不存在」「值非法」「引擎未加载」三种情况在日志里**完全不可见**。
     * mpv 的 `-5 = MPV_ERROR_OPTION_NOT_FOUND`、`-9 = MPV_ERROR_PROPERTY_FORMAT`、
     * `-11 = MPV_ERROR_PROPERTY_UNAVAILABLE`，见到这几种就该怀疑选项名写错或
     * 本构建把这个特性裁掉了（本仓 libmpv 就裁掉了 `sub-font` 等一整批）。
     */
    private fun setStringChecked(property: String, value: String) {
        val ret = MPVLib.setString(property, value)
        if (ret < 0) Log.w(TAG, "下发 mpv property 失败：$property=$value，返回 $ret")
    }

    /** 与 [setStringChecked] 同理，用于数值型 property。 */
    private fun setDoubleChecked(property: String, value: Double) {
        val ret = MPVLib.setDouble(property, value)
        if (ret < 0) Log.w(TAG, "下发 mpv property 失败：$property=$value，返回 $ret")
    }

    /**
     * 转义 mpv 字符串列表选项的分隔符。
     *
     * `http-header-fields` 是 M_OPT_TYPE_STRING_LIST（mpv `options/m_option.c` 的
     * `get_nextsep`）。解析规则只有一条：**紧跟在分隔符前**的那个反斜杠会被吃掉，
     * 逗号留在值内。也就是说 `a\,b` 解析成 `a,b`，而值里其它位置的反斜杠原样保留。
     *
     * 因此这里**不能**先做 `\` → `\\`：get_nextsep 不会把 `\\` 还原成 `\`，
     * 双写只会把多出来的反斜杠原样塞给 lavf（Cookie 里本来就少的反斜杠变多）。
     * 只替换逗号即可。
     *
     * 已知无法表达的情况：值本身**以反斜杠结尾**。`a\` + 分隔符 会被解析成 `a,`。
     * 现有请求头集合（UA / Cookie / Referer）里没有这种值；真出现时只能改用
     * per-file 的 `file-local-options/http-header-fields` 走 native 接口，不在本次范围。
     *
     * 原实现直接 joinToString(",") 不转义，Cookie 里的逗号会把一个头裂成两个假头
     * → 服务端 403 → lavf 打不开 → mpv 仍然只报 -13。exo 侧走
     * setDefaultRequestProperties(Map)，不存在这个拆分问题。
     */
    private fun escapeListSeparator(value: String): String = value.replace(",", "\\,")

    override fun play() = setPlayWhenReady(true)

    override fun pause() = setPlayWhenReady(false)

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (released) return
        MPVLib.setBoolean("pause", !playWhenReady)
        updateSnapshot { it.copy(playWhenReady = playWhenReady) }
    }

    override fun seekTo(positionMs: Long) {
        if (released) return
        val seconds = (positionMs.coerceAtLeast(0L) / 1000.0)
        // hr-seek=no 时 "absolute" 即关键帧 seek，对应 exo 的 SeekParameters.CLOSEST_SYNC：
        // 只定位到最近关键帧，避免精确 seek 触发长回溯导致的卡顿。
        MPVLib.cmd("seek", String.format(java.util.Locale.US, "%.3f", seconds), "absolute")
        updateSnapshot { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
    }

    override fun seekBy(deltaMs: Long) {
        seekTo(maxOf(0L, current.positionMs + deltaMs))
    }

    override fun setSpeed(speed: Float): Float {
        if (released) return current.speed
        val clamped = speed.coerceIn(0.25f, 4f)
        MPVLib.setDouble("speed", clamped.toDouble())
        updateSnapshot { it.copy(speed = clamped) }
        return clamped
    }

    override fun selectAudioTrack(trackId: Int?) {
        if (released) return
        // mpv: aid=no 交还自动选择；exo 侧对应 clearOverridesOfType(TRACK_TYPE_AUDIO)。
        if (trackId == null) {
            MPVLib.cmd("set", "aid", "auto")
        } else {
            MPVLib.cmd("set", "aid", trackId.toString())
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 字幕
    // ─────────────────────────────────────────────────────────────────────────

    override fun setExternalSubtitle(subtitle: ExternalSubtitle, offsetMs: Long) {
        if (released) return
        subtitleOffsetMs = offsetMs
        if (!fileLoaded.get()) {
            // 媒体还没进入 file-loaded，先记住，等 FILE_LOADED 再挂（mpv 在无文件时 sub-add 会失败）
            pendingSubtitle = subtitle to offsetMs
            return
        }
        applySubtitle(subtitle)
    }

    private fun applySubtitle(subtitle: ExternalSubtitle) {
        val path = MpvSubtitleBridge.materialize(context, subtitle, cacheDir)
        if (path == null) {
            Log.e(TAG, "字幕落盘失败，无法挂载：${subtitle.name}")
            updateSnapshot { it.copy(errorMessage = "字幕无法加载：${subtitle.name}") }
            return
        }

        // 多份字幕切换时先清空，避免 mpv 叠加显示（exo 侧是 setMediaItem 重建，天然替换）。
        MPVLib.cmd("sub-remove")
        MPVLib.cmd("sub-add", path, "select")
        activeSubtitlePath = path
        applySubtitleOffset()
        pendingSubtitleStyle?.let { applySubtitleStyle(it) }
        Log.i(TAG, "字幕已挂载: $path")
    }

    override fun clearExternalSubtitle() {
        if (released) return
        pendingSubtitle = null
        activeSubtitlePath = null
        MPVLib.cmd("sub-remove")
        MPVLib.setString("sub-visibility", "no")
    }

    override fun setSubtitleOffset(offsetMs: Long) {
        subtitleOffsetMs = offsetMs
        applySubtitleOffset()
    }

    private fun applySubtitleOffset() {
        // exo 侧 offset 通过 SubtitleTimeShiftStore 重写时间轴（毫秒 → 文件内容平移）。
        // mpv 侧用 sub-delay 等值表达：sub-delay 为正表示字幕延后出现，与「正偏移=字幕推后」同向。
        val seconds = subtitleOffsetMs / 1000.0
        MPVLib.setDouble("sub-delay", seconds)
    }

    override fun setSubtitleStyle(style: SubtitleStyle) {
        pendingSubtitleStyle = style
        applySubtitleStyle(style)
    }

    private fun applySubtitleStyle(style: SubtitleStyle) {
        // exo 侧：CaptionStyleCompat(WHITE, argb(alpha,0,0,0), TRANSPARENT, EDGE_TYPE_OUTLINE,
        // BLACK, DEFAULT_BOLD) + setFixedTextSize(sp) + setBottomPaddingFraction()。
        //
        // mpv 侧这里踩过一个坑：`--sub-font` / `--sub-font-size` / `--sub-color` /
        // `--sub-back-color` / `--sub-border-color` / `--sub-border-size` 在本仓
        // libmpv.so 里**一个都不存在**（逐字字符串检索 0 命中），下发只会拿到 -5 被静默吞掉。
        // 真正存在的是 sub-pos / sub-scale / sub-ass-override / sub-ass-style-overrides。
        // 所以字号走 sub-scale（18sp 是 SubtitleStyle 的默认值，视为 1.0 基准），
        // 颜色/描边/底框走 ASS 样式覆写——ASS 颜色是 &HAABBGGRR 且 **alpha 取反**
        // （00 = 不透明，FF = 全透明），和 Android 的 #AARRGGBB 不是一个东西。
        setDoubleChecked("sub-pos", (100 - style.bottomPaddingPercent.coerceIn(0, 50)).toDouble())
        setDoubleChecked("sub-scale", style.fontSizeSp.coerceIn(10, 64) / 18.0)
        // alpha 取反：exo 的 60% 不透明 = ASS 的 (255 - 153) = 0x66
        val assAlpha = 255 - (style.backgroundAlphaPercent.coerceIn(0, 100) * 255 / 100)
        setStringChecked(
            "sub-ass-style-overrides",
            listOf(
                "FontName=sans-serif",
                "PrimaryColour=&H00FFFFFF",                     // 白字，不透明
                "OutlineColour=&H00000000",                     // 黑描边
                String.format(java.util.Locale.US, "BackColour=&H%02X000000", assAlpha), // 半透明黑底（alpha 取反）
                // ASS 的 BorderStyle 只能二选一：1=outline(+shadow)，3=opaque box。
                // 本工程要的是「半透明底框」，所以取 3，BackColour 才是底框色；
                // 取 1 时 BackColour 会变成阴影色，配 Shadow=0 等于两者都看不到。
                // 代价：3 模式下没有描边，与 exo 的 EDGE_TYPE_OUTLINE 不完全等价（见对齐表）。
                "BorderStyle=3",
                "Shadow=0",
            ).joinToString(",")
        )
        // 故意**不**下发 `sub-ass-override=force`：
        //   - 纯文本字幕（srt/vtt）本来就被 mpv 转成 ASS 再交给 libass，Default 样式里没有
        //     用户样式标签，上面这组覆写默认就会生效，不需要 force；
        //   - force 会连**带样式标签的 ASS 外挂字幕**也一并覆盖，与本工程
        //     `--embeddedfonts=yes`「优先尊重字幕文件自带的字体与样式」的意图相冲突。
        // 用户可调的字号/位置已由 sub-scale / sub-pos 表达，这两项不受 override 语义影响。
    }

    override fun setSubtitleEnabled(enabled: Boolean) {
        if (released) return
        MPVLib.setString("sub-visibility", if (enabled) "yes" else "no")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 实时调色 LUT
    // ─────────────────────────────────────────────────────────────────────────

    /** LUT 文件目录：`<cacheDir>/lut/`。与字幕缓存分开，清理时互不影响。 */
    private val lutWriter by lazy { LutFileWriter(File(cacheDir, LUT_DIR_NAME)) }

    /** 上次成功下发的签名。相同就不重复写文件与 set，避免滑杆拖动时的无谓重载。 */
    @Volatile
    private var lastLutSignature: String? = null

    /**
     * 最近一次请求下发、但可能还在队列里的签名。
     *
     * 拖动滑杆时每一次参数变化都会 [applyLut]，单线程队列会把它们全部排上。
     * 没有这个字段时，队列里前面那几次「已经不新鲜的」请求仍会各自生成一张 LUT
     * 并 `set target-lut`，白算（33³ 约 20ms/次）且中间会看到几帧过时的调色。
     * 任务真正开跑时用它复查一次，发现已被更新的请求覆盖就直接让位。
     */
    @Volatile
    private var pendingLutSignature: String? = null

    /**
     * LUT 生成线程。
     *
     * 单线程而不是 `Dispatchers.Default`：拖动滑杆会产生成串的请求，单线程天然把它们
     * 排成队，配合 [lastLutSignature] 的短路，队列里只会剩最后一个真正不同的设置被执行；
     * 多线程并发写同一个缓存目录反而要额外加锁。
     *
     * 用守护线程：进程退出时不阻塞在未完成的 LUT 生成上。
     */
    private val lutExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mpv-lut").apply { isDaemon = true }
    }

    /**
     * 下发实时调色 LUT。
     *
     * 移植自参考实现 `mpv_realtime_lut.py` 的 `_generate_and_push_lut`：生成 `.cube`
     * 文件后 `set target-lut <路径>`。参考实现每次先置空再设值（`mpv.command("set",
     * "target-lut", "")` + 0.02s + 再设），这里**不做置空**——置空那一瞬间画面会闪回
     * 原色，而 mpv 的 `target-lut` 在路径变化时会自行重新加载，不存在"必须先卸载"的
     * 约束（那是参考实现对某些版本 mpv 的兼容性写法）。
     *
     * 关闭时把 `target-lut` 设为空串，mpv 即恢复片源原色。
     *
     * @param settings 完整设置快照。调用方从 `StateFlow` 取，天然是某一时刻的一致值。
     */
    fun applyLut(settings: LutSettings) {
        if (released) return
        val signature = if (settings.shouldApply) settings.signature() else DISABLED_LUT_SIGNATURE
        if (signature == lastLutSignature) return
        // 先于入队登记：队列里的旧任务靠它判断自己是否已被更新的请求覆盖（见 [pendingLutSignature]）。
        pendingLutSignature = signature

        // LUT 生成要算 size³ 个格点、每个格点跑完整调色链：33³ 约 20ms、65³ 约 150ms。
        // 放在主线程上做，拖滑杆时会把 UI 拖到掉帧，所以生成与写文件都丢到 IO 线程。
        runCatching { lutExecutor.execute {
            if (released) return@execute
            // 入队时是新请求，但排队期间可能已经被后面的拖动覆盖；此时生成出来的是一张
            // 用不上的图，直接让位给最新那次。
            if (pendingLutSignature != signature || signature == lastLutSignature) return@execute
            val file = runCatching { lutWriter.materialize(settings) }
                .onFailure { Log.e(TAG, "生成 LUT 文件失败", it) }
                .getOrNull()

            // JNI 调用统一回主线程：与 create/init/destroy 保持同一线程，避免
            // mpv 句柄在跨线程销毁的瞬间被这里用到（那会直接 crash 在 native 层）。
            mainHandler.post {
                if (released || !created) {
                    Log.w(TAG, "LUT 生成完成但引擎已释放，丢弃本次下发")
                    return@post
                }
                // 生成期间用户可能又拖了滑杆：这一张已经是过时的，不下发，否则会覆盖掉
                // 稍后到达的新签名（两者都在主线程，后 post 的会先到，画面会闪回旧调色）。
                if (pendingLutSignature != signature) {
                    Log.d(TAG, "LUT 生成完成但已被更新的请求覆盖，丢弃本次下发")
                    return@post
                }
                if (file == null) {
                    if (settings.shouldApply) {
                        // 该生效但生成失败：保持上一张 LUT 比突然闪回原色更可预期。
                        Log.w(TAG, "LUT 生成失败，保持当前 LUT 不变")
                        return@post
                    }
                    MPVLib.setString("target-lut", "")
                    lastLutSignature = signature
                    Log.i(TAG, "实时调色已关闭，target-lut 已清空")
                    return@post
                }

                // mpv 在 Android 上以 POSIX 路径解析，正斜杠是唯一安全写法
                // （参考实现也做了 `.replace("\\", "/")`，见 mpv_realtime_lut.py:1686）。
                val path = file.absolutePath.replace('\\', '/')
                MPVLib.setString("target-lut", path)
                lastLutSignature = signature
                Log.i(TAG, "已下发 LUT：${file.name}（${file.length()} 字节）")
            }
        } }.onFailure {
            // 已 release 时线程池已 shutdown，execute 会抛 RejectedExecutionException；
            // 这属于正常竞态（用户滑完参数立刻退出播放），不该让异常冒到播放页。
            Log.d(TAG, "LUT 任务未能提交（引擎已释放）：${it.message}")
        }
    }

    /** 当前生效的 LUT 签名，供诊断与测试断言。 */
    fun currentLutSignature(): String? = lastLutSignature

    // ─────────────────────────────────────────────────────────────────────────
    // 渲染面
    // ─────────────────────────────────────────────────────────────────────────

    fun setRenderSize(width: Int, height: Int) {
        MPVLib.setSurfaceSizeSafe(width, height)
    }

    /**
     * 画面比例模式，对齐 exo 的 `PlayerView.resizeMode` 那个循环按钮。
     *
     * mpv 没有「resizeMode」这个属性，等价的实现是三个选项的组合：
     * - FIT  : keepaspect=yes + panscan=0    → 完整显示，多余留黑边
     * - FILL : keepaspect=no                 → 拉伸铺满（宽高比会变，和 media3 FILL 一致）
     * - ZOOM : keepaspect=yes + panscan=1.0  → 保持比例裁掉溢出部分
     *
     * 注意这三个都是运行期可改属性，不需要重启 mpv 实例，因此切换比例不会打断播放。
     */
    fun setVideoResizeMode(mode: VideoResizeMode) {
        if (released) return
        when (mode) {
            VideoResizeMode.FIT -> {
                MPVLib.setString("keepaspect", "yes")
                MPVLib.setDouble("panscan", 0.0)
            }
            VideoResizeMode.FILL -> {
                MPVLib.setString("keepaspect", "no")
                MPVLib.setDouble("panscan", 0.0)
            }
            VideoResizeMode.ZOOM -> {
                MPVLib.setString("keepaspect", "yes")
                MPVLib.setDouble("panscan", 1.0)
            }
        }
        currentResizeMode = mode
    }

    /** 重载/重建实例后恢复用户选择的画面比例。 */
    private var currentResizeMode: VideoResizeMode = VideoResizeMode.FIT

    /** 供切换内核时把比例一并搬运过去。 */
    fun currentVideoResizeMode(): VideoResizeMode = currentResizeMode

    // ─────────────────────────────────────────────────────────────────────────
    // 事件处理（全部在主线程）
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleEvent(eventId: Int) {
        when (eventId) {
            MPVLib.MPV_EVENT_FILE_LOADED -> {
                fileLoaded.set(true)
                pendingSeekMs?.let { ms ->
                    pendingSeekMs = null
                    seekTo(ms)
                }
                pendingSubtitle?.let { (subtitle, offset) ->
                    pendingSubtitle = null
                    subtitleOffsetMs = offset
                    applySubtitle(subtitle)
                }
                // AUTO 档要等 FILE_LOADED 之后 video-params 才有值，所以放在这里细化。
                refineAutoHwdec()
                // 画面到底出没出来，要等 VO 初始化之后才知道，所以延后一拍再查。
                scheduleVideoOutputAudit()
                scheduleFrameStatsPoll()
                updateSnapshot { it.copy(playbackState = PlaybackSnapshot.STATE_READY) }
            }

            MPVLib.MPV_EVENT_PLAYBACK_RESTART -> {
                updateSnapshot { it.copy(playbackState = PlaybackSnapshot.STATE_READY) }
            }

            MPVLib.MPV_EVENT_SHUTDOWN -> {
                Log.w(TAG, "mpv 发出 shutdown 事件")
                updateSnapshot { it.copy(playbackState = PlaybackSnapshot.STATE_IDLE) }
            }
        }
    }

    /**
     * AUTO 档的按片源细化。
     *
     * 只在用户选了 [MpvHwdecMode.AUTO] 时动作，手动选了三档里的任何一档都不干预
     * （否则会出现「我明明选了软解，它自己变回硬解」）。
     *
     * 判定依据是 mpv 的 `video-params/*`：primaries 命中 bt.2020（HDR10 / DV 的常见色域）
     * 或 gamma 命中 pq / hlg 时，说明片源是 HDR，零拷贝直出更省电且能吃到 HDR 通路；
     * 其余一律走回拷贝，兼容性优先。桥接不可用时 MpvOptions.resolveHwdecValue
     * 会把 mediacodec* 降成 no，这里不需要重复判断。
     */
    private fun refineAutoHwdec() {
        if (activeHwdecMode != MpvHwdecMode.AUTO) return
        val primaries = MPVLib.getString("video-params/primaries").orEmpty()
        val gamma = MPVLib.getString("video-params/gamma").orEmpty()
        val isHdr = primaries.contains("bt.2020", ignoreCase = true) ||
            gamma.contains("pq", ignoreCase = true) ||
            gamma.contains("hlg", ignoreCase = true)
        val target = if (isHdr) MpvHwdecMode.HW_PLUS else MpvHwdecMode.HW
        val value = MpvOptions.resolveHwdecValue(target)
        val ret = MPVLib.setString("hwdec", value)
        if (ret < 0) {
            Log.w(TAG, "AUTO 细化 hwdec=$value 失败: $ret（保持装载值）")
            return
        }
        Log.i(TAG, "AUTO 按片源细化：primaries=$primaries gamma=$gamma → ${target.label}（hwdec=$value）")
    }

    /**
     * mpv 侧的丢帧观测。
     *
     * exo 那条链路读的是 `VideoDecoderCounters`（PlayerViewModel.startDropFrameMonitor），
     * mpv 没有那个对象，所以真机上 mpv 的卡顿一直没有数据。这里直接查 mpv 自己的
     * 三个计数属性（libmpv.so 字符串表里均存在）：
     * - `frame-drop-count`：渲染层丢帧（VO 来不及显示）
     * - `decoder-frame-drop-count`：解码层丢帧
     * - `mistimed-frame-count`：时序不对的帧（帧率匹配问题的直接指标）
     *
     * 计数变化时才上报，避免刷屏。
     */
    private fun scheduleFrameStatsPoll() {
        mainHandler.removeCallbacks(frameStatsPoll)
        mainHandler.postDelayed(frameStatsPoll, FRAME_STATS_INTERVAL_MS)
    }

    private val frameStatsPoll = object : Runnable {
        override fun run() {
            if (released || !created) return
            val rendered = MPVLib.getInt("frame-drop-count")
            val decoded = MPVLib.getInt("decoder-frame-drop-count")
            val mistimed = MPVLib.getInt("mistimed-frame-count")
            if (rendered >= 0 && decoded >= 0 && mistimed >= 0) {
                val changed = rendered != lastFrameDropCount ||
                    decoded != lastDecoderDropCount ||
                    mistimed != lastMistimedCount
                if (changed) {
                    lastFrameDropCount = rendered
                    lastDecoderDropCount = decoded
                    lastMistimedCount = mistimed
                    frameStatsReporter?.invoke(
                        FrameStats(
                            renderedDrops = rendered,
                            decoderDrops = decoded,
                            mistimedFrames = mistimed,
                            activeHwdec = MPVLib.getString("hwdec-current").orEmpty()
                        )
                    )
                }
            }
            mainHandler.postDelayed(this, FRAME_STATS_INTERVAL_MS)
        }
    }

    private fun handleEndFile(reason: Int, error: Int) {
        fileLoaded.set(false)
        when {
            error != 0 -> {
                // 带回 mpv 的符号名 + 实际下发的 URL + **mpv 自己的失败日志** + 走的哪条网络路径。
                // 只给一个 -13 是没法排障的：TLS 握手失败、HTTP 403、DNS 失败在 mpv 里
                // 全都折叠成 loading_failed，区别只在 lavf 那一行日志里。
                val message = "mpv 播放失败：${MPVLib.errorName(error)}（error=$error, " +
                    "reason=${MPVLib.endFileReasonName(reason)}/$reason）" +
                    describeLoadedUrl() + describeNetworkPath() + describeMpvLogTail()
                Log.e(TAG, message)
                updateSnapshot {
                    it.copy(playbackState = PlaybackSnapshot.STATE_IDLE, errorMessage = message)
                }
            }

            reason == MPVLib.MPV_END_FILE_REASON_EOF -> {
                updateSnapshot { it.copy(playbackState = PlaybackSnapshot.STATE_ENDED, isPlaying = false) }
            }

            else -> {
                updateSnapshot { it.copy(playbackState = PlaybackSnapshot.STATE_IDLE, isPlaying = false) }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 黑屏自检（把「静默黑屏」变成一句能查的话）
    // ─────────────────────────────────────────────────────────────────────────

    private val videoOutputAudit = Runnable { auditVideoOutput() }

    /** 每次 loadfile 前复位自检状态。 */
    private fun resetVideoOutputAudit() {
        videoReloadTried = 0
        mainHandler.removeCallbacks(videoOutputAudit)
    }

    /** 装载完成后延后一秒半检查视频输出（留够 VO 初始化 + 首帧渲染的时间）。 */
    private fun scheduleVideoOutputAudit() {
        mainHandler.removeCallbacks(videoOutputAudit)
        mainHandler.postDelayed(videoOutputAudit, VIDEO_OUTPUT_AUDIT_DELAY_MS)
    }

    /**
     * 检查「视频输出到底有没有建立」。
     *
     * 判据是 mpv 自己的 `vo-configured` 属性 **加上** 它自己打出的 fatal 日志：
     * - `vo-configured == false` 且日志命中 [VIDEO_OUTPUT_FAILURE_SIGNATURES]
     *   → 视频输出初始化失败，画面必然是黑的（音频不受影响）；
     * - 两个条件必须同时成立才报错：暂停时 `vo-configured` 也可能还是 false，
     *   只看属性会误报。
     *
     * 命中后先试一次 `video-reload`：此刻 Surface 通常已经挂上了，重建 VO 就能出画面。
     * 复查仍失败才写 errorMessage —— 用户至少能看到「为什么黑」和该往哪查。
     */
    private fun auditVideoOutput() {
        if (released || !fileLoaded.get()) return
        val currentError = _snapshot.value.errorMessage
        // 已经有真正的根因（加载失败等）报过了，别用「黑屏」把它盖掉；
        // 但「黑屏」这条自己是可撤销的——surface 迟到后 VO 可能补建立起来。
        if (currentError != null && !currentError.startsWith(VIDEO_OUTPUT_ERROR_PREFIX)) return

        if (MPVLib.getBoolean("vo-configured") == true) {
            // 画面已经建立（靠 reload 或后续补挂的 surface 自愈），把之前的黑屏提示撤掉。
            if (currentError != null) {
                Log.i(TAG, "视频输出已建立，撤销之前的黑屏提示")
                updateSnapshot { it.copy(errorMessage = null) }
            }
            return
        }

        // 句柄可能刚被重建（切内核 / 重进播放页）：先把 wid 重放，再判断。
        MPVLib.ensureSurfaceSafe()
        val signature = videoOutputFailureSignature() ?: return

        if (videoReloadTried < VIDEO_OUTPUT_RELOAD_MAX_ATTEMPTS && MPVLib.surfacePresent) {
            videoReloadTried++
            Log.w(
                TAG,
                "视频输出未建立（$signature）：Surface 已就绪，第 $videoReloadTried 次 video-reload 重建"
            )
            MPVLib.cmd("video-reload")
            mainHandler.removeCallbacks(videoOutputAudit)
            mainHandler.postDelayed(videoOutputAudit, VIDEO_OUTPUT_RELOAD_RECHECK_MS)
            return
        }

        val message = "mpv 画面未建立：视频输出初始化失败（$signature）。" +
            describeLoadedUrl() + describeVideoOutputPath() + describeMpvLogTail()
        Log.e(TAG, message)
        updateSnapshot { it.copy(errorMessage = message) }
    }

    /**
     * 从 mpv 最近日志里找「视频输出失败」的证据行。
     *
     * 这几条字符串来自 mpv 的 `video/out/android_common.c` 与
     * `video/out/opengl/context_android.c`，是本仓 libmpv.so 里逐字存在的 `MP_FATAL` 文案
     * （字符串表核过），命中即等价于「VO 初始化失败」——不是猜测，也不是超时推断。
     */
    private fun videoOutputFailureSignature(): String? {
        val tail = synchronized(recentMpvLogs) { recentMpvLogs.toList() }
        if (tail.isEmpty()) return null
        for (line in tail.asReversed()) {
            for (signature in VIDEO_OUTPUT_FAILURE_SIGNATURES) {
                if (line.contains(signature)) return signature
            }
        }
        return null
    }

    /** 黑屏时补一句「该往哪看」，免得把问题误判成解码或网络。 */
    private fun describeVideoOutputPath(): String {
        val surface = if (MPVLib.surfacePresent) {
            "已挂载（wid 属于当前句柄）"
        } else {
            "未挂载（Surface 未到 / 已被销毁 / **句柄重建后 wid 归零**）"
        }
        val vo = MPVLib.getString("current-vo") ?: "unknown"
        val reload = if (videoReloadTried > 0) "，已试 video-reload×$videoReloadTried" else ""
        return "；渲染面=$surface，vo=$vo$reload，hwdec=${activeHwdecMode.mpvValue}"
    }

    /**
     * 运行期切换硬件解码档位。
     *
     * `hwdec` 在 mpv 里是可选值属性：改完之后 mpv 丢弃当前解码器、用新档位重建视频链，
     * 不需要重新 loadfile，也不会重建 mpv 实例——所以切档不打断播放位置。
     *
     * @return mpv 错误码，0 = 已生效；负数表示属性不可写或被裁剪掉（会写进日志）。
     */
    fun setHwdecMode(mode: MpvHwdecMode): Int {
        if (released) return MPVLib.ERR_NOT_LOADED
        val value = MpvOptions.resolveHwdecValue(mode)
        val ret = MPVLib.setString("hwdec", value)
        if (ret < 0) {
            Log.w(TAG, "运行期设置 hwdec=$value 失败: $ret")
        } else {
            activeHwdecMode = mode
            Log.i(TAG, "运行期切换解码档位：${mode.label} → hwdec=$value")
        }
        return ret
    }

    /**
     * 错误文案里的 URL 摘要。
     *
     * http(s) 只留 scheme+host+path：直链的 query 里常带 token/签名，不进界面也不进日志。
     * `/proc/self/fd/N` 这种内部路径照原样给，用户一眼能看出是不是走了 fd 归一化。
     */
    private fun describeLoadedUrl(): String {
        val url = lastLoadUrl ?: return ""
        val scheme = lastLoadScheme.orEmpty()
        val shown = if (scheme.startsWith("http")) {
            runCatching {
                val parsed = Uri.parse(url)
                "${parsed.scheme}://${parsed.host}${parsed.path}"
            }.getOrDefault("<$scheme>")
        } else {
            url
        }
        return "，url=$shown（scheme=${scheme.ifEmpty { "?" }}）"
    }

    /**
     * 错误文案里的网络路径摘要：这次到底是**走回环代理**还是**退回 mpv 自带 TLS 直连**。
     *
     * 这两条路径的失败含义完全不同：
     * - 回环代理：失败原因在 OkHttp 那一侧（已单独 Log 了上游状态码），mpv 记的 -13 只是二手信息；
     * - 直连：失败原因就是 mpv/mbedtls 自己的握手或证书问题。
     * 不把这条信息带出来，用户手里的 `-13` 无法区分是哪种。
     */
    private fun describeNetworkPath(): String {
        if (!lastLoadScheme.orEmpty().startsWith("http")) return ""
        val mpvUrl = lastMpvLoadUrl.orEmpty()
        val proxy = httpProxy
        val viaProxy = mpvUrl.startsWith("http://127.0.0.1:")
        val base = "，网络=${if (viaProxy) "回环代理（OkHttp/系统 TLS）" else "mpv 自带 TLS 直连"}"
        if (!viaProxy || proxy == null) return base
        // 代理已经拿到上游响应码时，把码直接写进错误文案。
        // 这一条是「为什么错误反复出现」的关键分诊：403/404/410 = 上游拒了（签名过期 / UA 被拦 /
        // 链接已失效），跟 TLS 栈、mpv 移植毫无关系；只有连码都拿不到才轮到怀疑网络栈。
        val status = proxy.lastUpstreamStatus
        val upstreamError = proxy.lastUpstreamError
        return when {
            status > 0 -> "$base，上游 HTTP $status（${proxy.lastUpstreamHost}）"
            upstreamError.isNotEmpty() ->
                "$base，请求上游时失败：$upstreamError（未拿到任何 HTTP 响应）"
            else -> "$base，上游未返回任何响应（连接/握手阶段就失败了）"
        }
    }

    /**
     * 错误文案里带上 mpv 最近的错误/告警日志。
     *
     * 这些行是 mpv 自己的 `[lavf]` / `[ffmpeg]` / `[tls]` 输出，例如
     * `[ffmpeg] https protocol not found`、`[lavf] HTTP error 403 Forbidden`、
     * `[lavf] Failed to open …`。**根因几乎总在这几行里**，而 -13 只是个笼统的壳。
     */
    private fun describeMpvLogTail(): String {
        val tail = synchronized(recentMpvLogs) {
            recentMpvLogs.toList().takeLast(MAX_RECENT_LOGS)
        }
        if (tail.isEmpty()) return "；mpv 日志：<空>"
        val joined = tail.joinToString(" | ") { line ->
            val oneLine = line.replace('\n', ' ').replace('\r', ' ').trim()
            if (oneLine.length > MAX_LOG_CHARS) oneLine.take(MAX_LOG_CHARS) + "…" else oneLine
        }
        return "；mpv 日志：$joined"
    }

    /** 日志里用的 URL：去掉 query（直链 query 带签名/token，不进日志）。 */
    private fun hideQuery(url: String): String {
        val index = url.indexOf('?')
        if (index < 0) return url
        return url.substring(0, index) + "?<${url.length - index - 1}B>"
    }

    private fun handleProperty(name: String, value: Any?) {
        when (name) {
            "time-pos" -> {
                val seconds = (value as? Double) ?: return
                val positionMs = (seconds * 1000.0).toLong().coerceAtLeast(0L)
                updateSnapshot { it.copy(positionMs = positionMs, playbackState = snapshotStateAfterTick(it)) }
            }

            "duration" -> {
                val seconds = (value as? Double) ?: return
                updateSnapshot { it.copy(durationMs = (seconds * 1000.0).toLong().coerceAtLeast(0L)) }
            }

            "pause" -> {
                val paused = value as? Boolean ?: return
                // playWhenReady 与 isPlaying 分开维护：exo 的 playWhenReady 只表示「想不想播」，
                // 而 isPlaying 还要看缓冲。UI 的播放/暂停图标读 playWhenReady，
                // 缓冲指示读 playbackState，两者不能互相代替（否则缓冲时图标会闪成暂停）。
                updateSnapshot {
                    it.copy(
                        playWhenReady = !paused,
                        isPlaying = !paused && it.playbackState != PlaybackSnapshot.STATE_BUFFERING
                    )
                }
            }

            "paused-for-cache" -> {
                val buffering = value as? Boolean ?: return
                updateSnapshot {
                    it.copy(
                        playbackState = if (buffering) {
                            PlaybackSnapshot.STATE_BUFFERING
                        } else if (it.playbackState == PlaybackSnapshot.STATE_BUFFERING) {
                            PlaybackSnapshot.STATE_READY
                        } else {
                            it.playbackState
                        },
                        isPlaying = !buffering && it.isPlaying
                    )
                }
            }

            "speed" -> {
                val speed = (value as? Double)?.toFloat() ?: return
                updateSnapshot { it.copy(speed = speed) }
            }

            "eof-reached" -> {
                val eof = value as? Boolean ?: return
                if (eof) {
                    updateSnapshot { it.copy(playbackState = PlaybackSnapshot.STATE_ENDED, isPlaying = false) }
                }
            }

            "width" -> {
                val width = (value as? Int)?.toInt() ?: (value as? Long)?.toInt() ?: return
                updateSnapshot { it.copy(videoWidth = width) }
            }

            "height" -> {
                val height = (value as? Int)?.toInt() ?: (value as? Long)?.toInt() ?: return
                updateSnapshot { it.copy(videoHeight = height) }
            }

            "demuxer-cache-time" -> {
                val seconds = (value as? Double) ?: return
                updateSnapshot { it.copy(bufferedMs = (seconds * 1000.0).toLong().coerceAtLeast(0L)) }
            }

            "track-list" -> {
                // 轨道列表变化时通知上层刷新音轨面板（exo 侧对应 onTracksChanged）
                audioTrackListener?.invoke(readAudioTracks())
            }
        }
    }

    private fun snapshotStateAfterTick(snapshot: PlaybackSnapshot): Int =
        if (snapshot.playbackState == PlaybackSnapshot.STATE_ENDED) {
            snapshot.playbackState
        } else if (snapshot.playbackState == PlaybackSnapshot.STATE_IDLE) {
            PlaybackSnapshot.STATE_READY
        } else {
            snapshot.playbackState
        }

    private fun updateSnapshot(transform: (PlaybackSnapshot) -> PlaybackSnapshot) {
        _snapshot.value = transform(_snapshot.value)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 音轨
    // ─────────────────────────────────────────────────────────────────────────

    /** 与 PlayerUiState.audioTracks 的结构对齐；由 ViewModel 转换。 */
    data class MpvAudioTrack(val id: Int, val title: String, val language: String)

    var audioTrackListener: ((List<MpvAudioTrack>) -> Unit)? = null

    /**
     * 读取 mpv 的轨道列表。
     *
     * mpv 只在 `track-list` 变化时给一个无数据的 NONE 事件，具体内容需要按索引抓取
     * （`track-list/count` + `track-list/N/{type,id,title,lang}`），没有批量接口。
     */
    fun readAudioTracks(): List<MpvAudioTrack> {
        if (!created || released) return emptyList()
        val count = MPVLib.getInt("track-list/count") ?: return emptyList()
        val tracks = ArrayList<MpvAudioTrack>(count)
        for (i in 0 until count) {
            val type = MPVLib.getString("track-list/$i/type") ?: continue
            if (type != "audio") continue
            val id = MPVLib.getInt("track-list/$i/id") ?: continue
            val title = MPVLib.getString("track-list/$i/title").orEmpty()
            val lang = MPVLib.getString("track-list/$i/lang").orEmpty()
            tracks.add(MpvAudioTrack(id = id, title = title.ifBlank { "音轨 $id" }, language = lang))
        }
        return tracks
    }

    /**
     * 当前生效的音频轨 aid；`null` 表示 mpv 处于 `aid=auto`（自动选择）。
     *
     * 与 [readAudioTracks] 配对：它给的是轨道清单，这条给的是「清单里哪条在用」。
     * UI 需要前者渲染列表、需要后者标出选中项——mpv 的 `track-list/N` 里没有
     * `selected` 字段，只有全局的 `aid`，所以只能这样分开取。
     */
    fun currentAudioTrackId(): Int? {
        if (!created || released) return null
        return MPVLib.getInt("aid")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 截帧（自动分析调参的输入）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 抓一帧当前画面，给「自动分析调参」提取肤色用。
     *
     * 移植自参考实现的 `capture_mpv_frame_via_ipc`（mpv_realtime_lut.py:1631）：那条链路
     * 是往 mpv 的 IPC 端口发 `screenshot-to-file <path> video`，然后读盘。Android 上
     * 没有 IPC 端口，但同一套命令走 JNI 就能到达同一个 mpv 实例，所以直接
     * [MPVLib.cmd]。
     *
     * **为什么用 "video" 而不是 "window"**：`video` 是解码后的片源画面，不含字幕与
     * OSD；而 `target-lut` 是 vo 级的 shader，截图走的是片源帧，所以拿到的也是未调色
     * 的原片——正好是「当前画面适合什么参数」需要的输入。
     *
     * **为什么要轮询文件**：mpv 的 screenshot 命令是异步的，它会等下一帧渲染完才落盘，
     * 中途可能插入 seek。这里没有完成回调可用，就按「文件存在且非空」当完成信号，
     * 超时上限 [CAPTURE_TIMEOUT_MS]。
     *
     * 调用方应该在后台线程调用（内部会阻塞到截图完成）。
     *
     * @return 解码后的位图；引擎未就绪、超时或解码失败返回 null。
     */
    fun captureFrameForAnalysis(
        timeoutMs: Long = CAPTURE_TIMEOUT_MS,
        maxSide: Int = CAPTURE_MAX_SIDE
    ): Bitmap? {
        if (!created || released) {
            Log.w(TAG, "截帧失败：引擎未就绪")
            return null
        }
        val dir = File(cacheDir, CAPTURE_DIR_NAME)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "截帧失败：无法创建目录 ${dir.absolutePath}")
            return null
        }
        val file = File(dir, "frame-${System.nanoTime()}.png")
        // mpv 按 POSIX 路径解析，正斜杠是唯一安全写法（同 target-lut 的处理）。
        val posixPath = file.absolutePath.replace('\\', '/')

        // JNI 调用统一回主线程：与 create/init/destroy 同一线程，避免命令与销毁竞态。
        mainHandler.post {
            if (released || !created) return@post
            MPVLib.cmd("screenshot-to-file", posixPath, "video")
        }

        try {
            if (!awaitFile(file, timeoutMs)) {
                Log.w(TAG, "截帧超时（${timeoutMs}ms）：mpv 未产出 ${file.name}")
                return null
            }
            val bitmap = decodeScaled(file, maxSide)
            if (bitmap == null) Log.w(TAG, "截帧失败：无法解码 ${file.name}（${file.length()} 字节）")
            return bitmap
        } finally {
            // 分析只需要像素，文件用完即删：截图帧不小，攒在 cacheDir 里没人清。
            if (file.exists() && !file.delete()) Log.d(TAG, "截图临时文件未能删除：${file.name}")
        }
    }

    /** 轮询等待截图落盘。返回 false 表示超时。 */
    private fun awaitFile(file: File, timeoutMs: Long): Boolean {
        // 截图命令是 mainHandler.post 投递的，在主线程上轮询会把那条命令一起堵住：
        // 命令永远执行不到 → 文件永远不出现 → 必然等到 2s 超时并返回 null。
        // 这里挡成明确失败，而不是让调用方拿到一个总是超时的「分析没找到人脸」。
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "截帧失败：captureFrameForAnalysis 必须在后台线程调用")
            return false
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (released) return false
            // 命令是异步的，文件可能出现得比写满早，所以要求非空再退出等待。
            if (file.length() > 0L) {
                // 再给一点时间让最后一个块落完，否则解码会读到截断的 PNG。
                Thread.sleep(FRAME_SETTLE_MS)
                return true
            }
            Thread.sleep(CAPTURE_POLL_MS)
        }
        return false
    }

    /**
     * 按 [maxSide] 做 2 的幂次降采样解码。
     *
     * 两段式：先只解析尺寸（`inJustDecodeBounds`），算出 2 的幂次采样率，再真正解码。
     * 1080p 的帧约 8 MB 的 ARGB，直接整解再加一份 IntArray 拷给分析器，峰值会翻倍；
     * 而 [LutSkinAnalyzer.MAX_SAMPLE_DIM] 本来就是 640，多出来的像素都会被丢掉。
     *
     * 采样率取「降完不小于 [maxSide] 的最大 2 的幂」，所以长边落在
     * `[maxSide, 2 * maxSide)`——这是下限而非上限：1080p 配 [CAPTURE_MAX_SIDE] 得到
     * 960 宽，比目标 640 大一档，剩下的交给分析器里的双线性缩放（质量好于
     * `inSampleSize` 的最近邻），换掉的是多出来的半张图的内存。
     */
    private fun decodeScaled(file: File, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    /** mpv 侧一帧统计快照，见 [scheduleFrameStatsPoll]。 */
    data class FrameStats(
        val renderedDrops: Int,
        val decoderDrops: Int,
        val mistimedFrames: Int,
        val activeHwdec: String
    )

    companion object {
        private const val TAG = "MpvPlaybackEngine"

        /** mpv 丢帧采样间隔：5 秒与 exo 侧 DROP_FRAME_MONITOR_INTERVAL_MS 对齐。 */
        private const val FRAME_STATS_INTERVAL_MS = 5_000L

        /** 等渲染面的重试间隔：约一帧。SurfaceView 通常 1~3 帧内就绪。 */
        private const val SURFACE_WAIT_RETRY_MS = 16L

        /**
         * 最多等 8 秒（500 帧）。
         *
         * 旧值是 150（2.4 秒）——那个预算假设「Surface 一两帧内必到」。切内核、冷启动、
         * 低端机首帧解码时都不成立，超时后照样 loadfile，于是 VO 读不到 wid 而失败。
         * 现在等待期间每轮都会重放 wid，多等几秒的代价远小于一次黑屏。
         */
        private const val SURFACE_WAIT_MAX_ATTEMPTS = 500

        /** video-reload 最多重试几次：第一次失败常见于 surface 刚挂上、VO 还没重建完。 */
        private const val VIDEO_OUTPUT_RELOAD_MAX_ATTEMPTS = 3

        /** 黑屏错误的固定前缀：用于把「可撤销的画面错误」与真正的加载失败区分开。 */
        private const val VIDEO_OUTPUT_ERROR_PREFIX = "mpv 画面未建立"

        /** 装载后多久检查视频输出：留够 VO 初始化 + 首帧渲染的时间。 */
        private const val VIDEO_OUTPUT_AUDIT_DELAY_MS = 1500L

        /** video-reload 之后多久复查：重建 VO 比首次初始化慢一点。 */
        private const val VIDEO_OUTPUT_RELOAD_RECHECK_MS = 1200L

        /**
         * mpv 视频输出失败的 fatal 文案（见 `video/out/android_common.c` 与
         * `video/out/opengl/context_android.c`）。命中任一即表示 VO 没起来。
         */
        private val VIDEO_OUTPUT_FAILURE_SIGNATURES = listOf(
            "Missing surface pointer",
            "Failed to create ANativeWindow",
            "Could not attach java VM",
            "Video output initialization failed",
            "Could not create video output"
        )

        /**
         * 当前「持有原生 mpv 实例」的引擎（静态，因为原生 `g_mpv` 是进程级单例）。
         *
         * 原生侧 `cpp/src/main.cpp` 的 `create()` 发现 `g_mpv` 非空会先 `destroy_mpv_locked()`
         * 再重建，而 `destroy()` 只认这个全局指针。`PlayerViewModel.startMpvSession` 的顺序是
         * 「先建新引擎 → 再 `previous.release()`」，且 `release()` 把 `destroyEngine()` 排进
         * `mainHandler.post` 延后执行——等它真正跑时 `g_mpv` 已指向**新**实例，
         * 旧引擎的 release 就把新实例销毁了，之后新引擎所有 `mpv_command` 全部空转
         * （原生侧 `MPV_CHECK_VOID` 直接 return），表现为「切内核后没报错但没画面」。
         *
         * 这张登记表让「只有属主才有权销毁原生实例」，把跨实例误杀堵死。
         * 只应在主线程读写（initialize / release 都在主线程）。
         */
        @Volatile
        private var liveEngine: MpvPlaybackEngine? = null

        /** 错误文案里最多带几条 mpv 日志：再多会撑爆界面上的单行提示。 */
        private const val MAX_RECENT_LOGS = 8

        /** 单条日志进文案前的截断长度。 */
        private const val MAX_LOG_CHARS = 200

        /** LUT 文件子目录名（位于 [cacheDir] 下）。 */
        private const val LUT_DIR_NAME = "lut"

        /** 未启用调色时的签名占位。用它区分「没下发过」与「下发过且是关闭态」。 */
        private const val DISABLED_LUT_SIGNATURE = "__disabled__"

        /** 截图临时文件子目录名（位于 [cacheDir] 下）。 */
        private const val CAPTURE_DIR_NAME = "lut-capture"

        /** 截帧等待上限。mpv 正常在一帧时间内落盘，2 秒足够覆盖暂停时等关键帧的情况。 */
        private const val CAPTURE_TIMEOUT_MS = 2_000L

        /** 轮询截图文件的间隔。 */
        private const val CAPTURE_POLL_MS = 25L

        /** 文件出现后再等一会儿，让最后一块写完，避免解到截断的 PNG。 */
        private const val FRAME_SETTLE_MS = 30L

        /**
         * 解码时长边的下限目标。
         *
         * 与分析器的 [LutSkinAnalyzer.MAX_SAMPLE_DIM] 取同一个值：采样率只能是 2 的幂，
         * 所以实际解出来的长边是 640~1279，再由分析器用双线性缩到 640。取 1280 会让
         * 1080p 完全不采样（整幅 8 MB 解码完再一路缩到 640），纯属浪费内存。
         */
        private const val CAPTURE_MAX_SIDE = 640
    }
}
