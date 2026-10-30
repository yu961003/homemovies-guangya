package com.example.localmovielibrarz.mpv

import android.content.Context
import android.util.Log
import android.view.Surface
import java.util.concurrent.CopyOnWriteArraySet

/**
 * libmpv 的 JNI 门面。
 *
 * 原生实现见 `app/src/main/cpp/src 下的 .cpp`，编译产物为 `libplayer.so`，它在内部链接
 * 预编译的 `libmpv.so`。若 jniLibs 下没有 libmpv.so，CMake 会编译 stub 版，
 * [isAvailable] 恒为 false，[MpvPlaybackEngine] 据此把引擎判定为不可用。
 *
 * 线程约定（与上游 mpv-android 一致）：
 * - [create]/[init]/[destroy] 必须在同一个线程上成对调用（本项目固定在主线程）；
 * - [command]/[setProperty*]/[getProperty*] 可在任意线程调用，libmpv 内部会串行化；
 * - 回调 [event]/[eventProperty]/[logMessage] 来自原生事件线程，**不是主线程**，
 *   因此所有分发都通过 [listeners] 交给上层自行切线程。
 */
object MPVLib {

    private const val TAG = "MPVLib"

    // ---- mpv_format 常量（与 client.h 对齐）----
    const val MPV_FORMAT_NONE = 0
    const val MPV_FORMAT_STRING = 1
    const val MPV_FORMAT_OSD_STRING = 2
    const val MPV_FORMAT_FLAG = 3
    const val MPV_FORMAT_INT64 = 4
    const val MPV_FORMAT_DOUBLE = 5
    const val MPV_FORMAT_NODE = 6

    // ---- mpv_event_id 常用值（与 client.h 对齐）----
    const val MPV_EVENT_NONE = 0
    const val MPV_EVENT_SHUTDOWN = 1
    const val MPV_EVENT_LOG_MESSAGE = 2
    const val MPV_EVENT_END_FILE = 7
    const val MPV_EVENT_FILE_LOADED = 8
    const val MPV_EVENT_PLAYBACK_RESTART = 21
    const val MPV_EVENT_PROPERTY_CHANGE = 22

    // ---- mpv_end_file_reason（client.h）----
    const val MPV_END_FILE_REASON_EOF = 0
    const val MPV_END_FILE_REASON_STOP = 2
    const val MPV_END_FILE_REASON_QUIT = 3
    const val MPV_END_FILE_REASON_ERROR = 4
    const val MPV_END_FILE_REASON_REDIRECT = 5

    // ---- mpv_error（client.h）----
    // 移植期最常见的几个，全列出来是为了错误文案能直接读出「哪一步挂了」，
    // 不用再去翻 client.h。数值与上游一一对应，不要改。
    const val MPV_ERROR_NOMEM = -2
    const val MPV_ERROR_UNINITIALIZED = -3
    const val MPV_ERROR_INVALID_PARAMETER = -4
    const val MPV_ERROR_COMMAND = -12
    const val MPV_ERROR_LOADING_FAILED = -13
    const val MPV_ERROR_AO_INIT_FAILED = -14
    const val MPV_ERROR_VO_INIT_FAILED = -15
    const val MPV_ERROR_NOTHING_TO_PLAY = -16
    const val MPV_ERROR_UNKNOWN_FORMAT = -17
    const val MPV_ERROR_UNSUPPORTED = -18
    const val MPV_ERROR_GENERIC = -20

    /** `mpv_error` 的可读名。未知码原样带上数字，不编。 */
    fun errorName(code: Int): String = when (code) {
        0 -> "SUCCESS"
        MPV_ERROR_NOMEM -> "NOMEM"
        MPV_ERROR_UNINITIALIZED -> "UNINITIALIZED"
        MPV_ERROR_INVALID_PARAMETER -> "INVALID_PARAMETER"
        -5 -> "OPTION_NOT_FOUND"
        -6 -> "OPTION_FORMAT"
        -7 -> "OPTION_ERROR"
        -8 -> "PROPERTY_NOT_FOUND"
        -9 -> "PROPERTY_FORMAT"
        -10 -> "PROPERTY_UNAVAILABLE"
        -11 -> "PROPERTY_ERROR"
        MPV_ERROR_COMMAND -> "COMMAND"
        MPV_ERROR_LOADING_FAILED -> "LOADING_FAILED"
        MPV_ERROR_AO_INIT_FAILED -> "AO_INIT_FAILED"
        MPV_ERROR_VO_INIT_FAILED -> "VO_INIT_FAILED"
        MPV_ERROR_NOTHING_TO_PLAY -> "NOTHING_TO_PLAY"
        MPV_ERROR_UNKNOWN_FORMAT -> "UNKNOWN_FORMAT"
        MPV_ERROR_UNSUPPORTED -> "UNSUPPORTED"
        -19 -> "NOT_IMPLEMENTED"
        MPV_ERROR_GENERIC -> "GENERIC"
        else -> "ERROR_$code"
    }

    /** `mpv_end_file_reason` 的可读名。 */
    fun endFileReasonName(reason: Int): String = when (reason) {
        MPV_END_FILE_REASON_EOF -> "EOF"
        MPV_END_FILE_REASON_STOP -> "STOP"
        MPV_END_FILE_REASON_QUIT -> "QUIT"
        MPV_END_FILE_REASON_ERROR -> "ERROR"
        MPV_END_FILE_REASON_REDIRECT -> "REDIRECT"
        else -> "REASON_$reason"
    }

    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var loadError: String? = null

    val listeners = CopyOnWriteArraySet<Listener>()

    /** libplayer.so 是否加载成功（与 libmpv 是否存在无关，后者看 [isAvailable]）。 */
    val isLibraryLoaded: Boolean get() = libraryLoaded

    private fun ensureLoaded(): Boolean {
        if (libraryLoaded) return true
        if (loadError != null) return false
        synchronized(this) {
            if (libraryLoaded) return true
            if (loadError != null) return false
            return try {
                System.loadLibrary("player")
                libraryLoaded = true
                Log.i(TAG, "libplayer.so 加载成功，libmpv 可用=${isAvailable()}")
                true
            } catch (t: Throwable) {
                loadError = t.message
                Log.e(TAG, "libplayer.so 加载失败，mpv 引擎不可用", t)
                false
            }
        }
    }

    /**
     * 探测 mpv 引擎是否可用：库能加载 + 原生侧报告 libmpv 已链接。
     * 任何一步失败都返回 false，绝不让异常冒到调用方。
     */
    fun probe(): Boolean {
        if (!ensureLoaded()) return false
        return try {
            isAvailable()
        } catch (t: Throwable) {
            Log.e(TAG, "isAvailable 调用失败", t)
            false
        }
    }

    fun loadErrorMessage(): String? = loadError ?: runCatching { lastError() }.getOrNull()

    // ---- native 声明（签名必须与 cpp/src 下的 .cpp 完全一致）----
    @JvmStatic private external fun isAvailable(): Boolean
    @JvmStatic private external fun isHwdecBridgeAvailable(): Boolean
    @JvmStatic private external fun lastError(): String?
    @JvmStatic private external fun create(appContext: Context?): Int
    @JvmStatic private external fun init(): Int
    @JvmStatic private external fun destroy()
    @JvmStatic private external fun command(args: Array<String>): Unit
    @JvmStatic private external fun attachSurface(surface: Surface): Int
    @JvmStatic private external fun detachSurface(): Int
    @JvmStatic private external fun hasSurface(): Boolean
    @JvmStatic private external fun ensureSurface(): Int
    @JvmStatic private external fun setSurfaceSize(width: Int, height: Int): Int
    @JvmStatic private external fun setOptionString(option: String, value: String): Int
    @JvmStatic private external fun getPropertyInt(property: String): Int?
    @JvmStatic private external fun setPropertyInt(property: String, value: Int)
    @JvmStatic private external fun getPropertyDouble(property: String): Double?
    @JvmStatic private external fun setPropertyDouble(property: String, value: Double): Int
    @JvmStatic private external fun getPropertyBoolean(property: String): Boolean?
    @JvmStatic private external fun setPropertyBoolean(property: String, value: Boolean)
    @JvmStatic private external fun getPropertyString(property: String): String?
    @JvmStatic private external fun setPropertyString(property: String, value: String): Int
    @JvmStatic private external fun observeProperty(property: String, format: Int)
    @JvmStatic private external fun unobserveProperty(property: String)

    // ---- 供 Kotlin 侧使用的安全包装（库未加载时一律降级为空操作）----

    fun isHwdecBridgeReady(): Boolean =
        ensureLoaded() && runCatching { isHwdecBridgeAvailable() }.getOrDefault(false)

    fun createEngine(context: Context): Int {
        if (!ensureLoaded()) return ERR_NOT_LOADED
        return runCatching { create(context.applicationContext) }
            .getOrElse { t ->
                Log.e(TAG, "create 抛出异常", t)
                ERR_NOT_LOADED
            }
    }

    fun initialize(): Int {
        if (!ensureLoaded()) return ERR_NOT_LOADED
        return runCatching { init() }.getOrElse { ERR_NOT_LOADED }
    }

    fun destroyEngine() {
        if (!ensureLoaded()) return
        runCatching { destroy() }.onFailure { Log.e(TAG, "destroy 失败", it) }
    }

    fun cmd(vararg args: String) {
        if (!ensureLoaded() || args.isEmpty()) return
        runCatching { command(arrayOf(*args)) }
            .onFailure { Log.e(TAG, "command(${args.joinToString(" ")}) 失败", it) }
    }

    /**
     * 当前这个 mpv 句柄是否真的持有 Surface（等价于原生 `wid` 属于它）。
     *
     * mpv 的 android VO **只在视频输出第一次初始化时读一次 `wid`**：读到 0 就
     * `vo_android_init()` → `MP_FATAL("Missing surface pointer")`，之后 mpv 退化成
     * 「只放声音」（音频、进度、时长全正常，画面永远黑，且没有 end-file 错误码）。
     * 所以装载媒体必须排在它之后（见 `MpvPlaybackEngine.awaitSurfaceThen`）。
     *
     * **这里必须问原生，不能在 Kotlin 侧缓存一个布尔**：原生 `create()` 在旧实例还在时
     * 会先 `destroy_mpv_locked()` 再 `mpv_create()`——句柄换了一个，而 `wid` 是按句柄
     * 生效的，新句柄的 wid 恒为 0。旧实现那个「attach 成功过就置 true」的布尔会跨句柄
     * 存活，把等待方直接骗过去，于是 `loadfile` 照发、VO 照失败：
     * 真机日志 `渲染面=已挂载，vo=unknown` + `[vo/gpu/android] Missing surface pointer`
     * 就是这个组合。
     */
    val surfacePresent: Boolean
        get() = ensureLoaded() && runCatching { hasSurface() }.getOrDefault(false)

    fun attachSurfaceSafe(surface: Surface): Int {
        if (!ensureLoaded()) return ERR_NOT_LOADED
        val ret = runCatching { attachSurface(surface) }.getOrDefault(ERR_NOT_LOADED)
        Log.i(TAG, "attachSurface -> $ret（hasSurface=${surfacePresent}）")
        return ret
    }

    /**
     * 幂等重放：把缓存的 Surface 写回**当前**句柄的 `wid`。
     *
     * 切内核、重新进播放页、Activity 重建都会让原生句柄换一个，而 Surface 还是同一个；
     * 每次开播前调用一次，就不必依赖「谁记得在正确时机 attach」。已应用时是空操作。
     */
    fun ensureSurfaceSafe(): Int {
        if (!ensureLoaded()) return ERR_NOT_LOADED
        return runCatching { ensureSurface() }.getOrDefault(ERR_NOT_LOADED)
    }

    fun detachSurfaceSafe(): Int {
        if (!ensureLoaded()) return 0
        return runCatching { detachSurface() }.getOrDefault(ERR_NOT_LOADED)
    }

    fun setSurfaceSizeSafe(width: Int, height: Int) {
        if (!ensureLoaded() || width <= 0 || height <= 0) return
        runCatching { setSurfaceSize(width, height) }
    }

    fun setOption(name: String, value: String): Int {
        if (!ensureLoaded()) return ERR_NOT_LOADED
        return runCatching { setOptionString(name, value) }.getOrDefault(ERR_NOT_LOADED)
    }

    fun observe(property: String, format: Int) {
        if (!ensureLoaded()) return
        runCatching { observeProperty(property, format) }
    }

    fun unobserve(property: String) {
        if (!ensureLoaded()) return
        runCatching { unobserveProperty(property) }
    }

    fun getInt(property: String): Int? =
        if (!ensureLoaded()) null else runCatching { getPropertyInt(property) }.getOrNull()

    fun getDouble(property: String): Double? =
        if (!ensureLoaded()) null else runCatching { getPropertyDouble(property) }.getOrNull()

    fun getBoolean(property: String): Boolean? =
        if (!ensureLoaded()) null else runCatching { getPropertyBoolean(property) }.getOrNull()

    fun getString(property: String): String? =
        if (!ensureLoaded()) null else runCatching { getPropertyString(property) }.getOrNull()

    fun setInt(property: String, value: Int) {
        if (!ensureLoaded()) return
        runCatching { setPropertyInt(property, value) }
    }

    fun setDouble(property: String, value: Double): Int {
        if (!ensureLoaded()) return ERR_NOT_LOADED
        return runCatching { setPropertyDouble(property, value) }.getOrDefault(ERR_NOT_LOADED)
    }

    fun setBoolean(property: String, value: Boolean) {
        if (!ensureLoaded()) return
        runCatching { setPropertyBoolean(property, value) }
    }

    /**
     * 设置一个 mpv property（运行期可改的项）。
     *
     * **返回 mpv 的错误码**（0 = 成功）。原实现返回 Unit + `runCatching{}`，把
     * 「属性不存在」和「未加载」一起吞掉——本仓 libmpv 是裁剪过的构建，很多
     * `--sub-*` 项根本不存在，静默失败让「字幕样式调了没反应」无从查起。
     */
    fun setString(property: String, value: String): Int {
        if (!ensureLoaded()) return ERR_NOT_LOADED
        return runCatching { setPropertyString(property, value) }.getOrDefault(ERR_NOT_LOADED)
    }

    const val ERR_NOT_LOADED = -1000

    // ---- 原生 → Java 的回调入口（名字/签名由 jni_utils.cpp 缓存）----

    @JvmStatic
    fun event(eventId: Int) {
        listeners.forEach { it.onEvent(eventId) }
    }

    @JvmStatic
    fun eventEndFile(reason: Int, error: Int) {
        listeners.forEach { it.onEndFile(reason, error) }
    }

    @JvmStatic
    fun eventProperty(property: String) {
        listeners.forEach { it.onProperty(property, null) }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Boolean) {
        listeners.forEach { it.onProperty(property, value) }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Long) {
        listeners.forEach { it.onProperty(property, value) }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Double) {
        listeners.forEach { it.onProperty(property, value) }
    }

    @JvmStatic
    fun eventProperty(property: String, value: String) {
        listeners.forEach { it.onProperty(property, value) }
    }

    @JvmStatic
    fun logMessage(prefix: String, level: Int, text: String) {
        // mpv 的 log_level：fatal=10 / error=20 / warn=30 / info=40 / v=50 / debug=60，
        // 数字越小越严重。原注释写「转发 warn 及以上」，实现却是 `level >= 30 return`——
        // 边界差一，把 warn 本身丢掉了；而 mbedtls 的 "No CA chain is set" 正是 warn 级，
        // 一旦漏掉，https 类 -13 就只剩一句 "Failed to open"，根因不可见。
        // 改成 `> 30`：fatal/error/warn 全转发，info 及以上继续丢弃（避免刷屏）。
        if (level > 30) return
        val line = "[$prefix] ${text.trimEnd()}"
        Log.println(
            when {
                level <= 10 -> Log.ERROR
                level <= 20 -> Log.WARN
                else -> Log.INFO
            },
            TAG,
            line
        )
        // 旁路：把这几行同时喂给引擎。loading_failed 的文案里要能带上 mpv 自己的
        // lavf/http 报错（"HTTP error 403"、"Failed to open"、"No CA chain is set"），
        // 否则用户和排障者只能看到 -13/reason=4，无法判断是 TLS、403 还是 DNS。
        if (logSinks.isNotEmpty()) {
            for (sink in logSinks) runCatching { sink.onLog(level, line) }
        }
    }

    /**
     * mpv 日志旁路回调。**在原生事件线程上调用**，实现方自己保证线程安全，
     * 且绝不能在实现里做耗时操作（会顶住 mpv 的事件循环）。
     */
    interface LogSink {
        fun onLog(level: Int, line: String)
    }

    /** 已注册的日志旁路；用写时复制集合，原生线程遍历、主线程增删互不阻塞。 */
    val logSinks: MutableSet<LogSink> = java.util.concurrent.CopyOnWriteArraySet()

    /** 回调接口。**全部方法都在原生事件线程上调用**，实现方负责切回自己的调度器。 */
    interface Listener {
        fun onEvent(eventId: Int)
        fun onEndFile(reason: Int, error: Int) {}
        fun onProperty(name: String, value: Any?)
    }
}
