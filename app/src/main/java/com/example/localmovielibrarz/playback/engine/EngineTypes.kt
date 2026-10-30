package com.example.localmovielibrarz.playback.engine

/**
 * 双内核架构里「与引擎实现无关」的数据类型。
 *
 * 单独成文件的原因：这些类型被 [EngineSwitchPlanner] 引用，而决策逻辑是整条切换链路上
 * 唯一可以在纯 JVM 上跑单测的部分。如果它们和 [PlaybackEngine]（依赖 android.net.Uri /
 * Context）挤在同一个文件里，单测就不得不把整个 Android 依赖树搬进测试 classpath。
 * 保持本文件不出现任何 `import android.*` / `import androidx.*` 是刻意的约束，改动时请维持。
 */

/**
 * 播放内核标识。
 *
 * 双播放器架构里，`EXO` 与原工程行为完全一致（改动为零），`MPV` 是新接入的引擎。
 * 选择结果持久化在 `PlayerEngineSettings`，缺省保持 `EXO`，保证升级用户不受影响。
 */
enum class PlaybackEngineKind(val id: String, val label: String) {
    EXO("exo", "ExoPlayer（默认）"),
    MPV("mpv", "MPV（万能解码）");

    companion object {
        fun fromId(id: String?): PlaybackEngineKind =
            entries.firstOrNull { it.id == id } ?: EXO
    }
}

/**
 * 两个引擎共用的「播放事实」。UI 只读这份快照，不直接触碰任何引擎对象，
 * 这样 PlayerScreen 不需要为每个引擎写一套控件。
 */
data class PlaybackSnapshot(
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = true,
    /** 播放态：IDLE / BUFFERING / READY / ENDED，与 media3 Player 常量取值保持一致。 */
    val playbackState: Int = STATE_IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val speed: Float = 1f,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** 端到端错误文案；非空即代表当前引擎无法继续播放。 */
    val errorMessage: String? = null
) {
    companion object {
        // 这里刻意复制 media3 Player 的常量取值而不是引用它，
        // 目的同样是让本文件不依赖 Android；数值与 Player.STATE_* 一一对应。
        const val STATE_IDLE = 1
        const val STATE_BUFFERING = 2
        const val STATE_READY = 3
        const val STATE_ENDED = 4
    }
}

/**
 * mpv 硬件解码档位。
 *
 * 为什么是三档而不是一个布尔开关：`hwdec` 在 mpv 里本来就是多值项。
 * - `mediacodec`      ：Surface 零拷贝直出，最省电、延迟最低；个别机型的解码器对
 *                       10bit / HDR 支持不全，会花屏或绿屏。
 * - `mediacodec-copy` ：解出来的帧拷回内存再上屏，多一次拷贝，兼容性明显更好。
 * - `no`              ：纯 CPU 软解，任何片源都能出画面，代价是功耗。
 * 旧版本只暴露了「开 / 关」两态（而且那个开关根本没有 UI 入口），
 * 用户遇到花屏时没有中间档可选——这就是本次补三档的动机。
 *
 * [mpvValue] 直接写 mpv 的对外取值而不是在别处做 when 分支：这些字符串是
 * libmpv 的契约，集中一处便于和二进制里实际支持的取值逐条对照
 * （本仓 libmpv.so 的字符串表里 `mediacodec` / `mediacodec-copy` / `no` 均存在）。
 *
 * 本文件不允许出现 import android.* / androidx.*（纯 JVM 单测要能加载它），
 * 因此这里只放枚举，不放任何读设置的逻辑。
 */
enum class MpvHwdecMode(
    val id: String,
    val label: String,
    val subtitle: String,
    val mpvValue: String
) {
    /** 零拷贝硬解。 */
    HW_PLUS("hw_plus", "硬件解码 +", "零拷贝直出（最省电，个别机型可能花屏）", "mediacodec"),

    /** 硬解回拷：兼容性更好的硬解档。 */
    HW("hw", "硬件解码", "回拷上屏，兼容性更好（推荐）", "mediacodec-copy"),

    /** 纯软解。 */
    SW("sw", "软件解码", "CPU 解码，兼容一切片源，高码率更费电", "no");

    companion object {
        fun fromId(id: String?): MpvHwdecMode = entries.firstOrNull { it.id == id } ?: HW
    }
}

/**
 * 引擎可用性探测结果。
 *
 * `available=false` 时 [reason] 会直接出现在 UI 上。这是**显式降级**而不是静默回退：
 * 用户点了 mpv 却拿到 exo 时，必须知道为什么。
 */
data class EngineAvailability(
    val kind: PlaybackEngineKind,
    val available: Boolean,
    val reason: String? = null
)

/**
 * 画面比例模式，与 exo 侧 `AspectRatioFrameLayout.RESIZE_MODE_*` 一一对应。
 *
 * 为什么需要这层映射：exo 的比例控制是 `PlayerView.resizeMode` 一个赋值就完事，
 * 由 media3 自己在渲染层处理；mpv 没有对等属性，得靠 `panscan` / `keepaspect`
 * 两个选项组合出来（见 MpvPlaybackEngine.setVideoResizeMode）。
 * 把「用户想要哪种比例」与「引擎怎么实现」分开，UI 才能对两个内核用同一套按钮。
 */
enum class VideoResizeMode {
    /** 完整显示，多余留黑边（media3 RESIZE_MODE_FIT）。 */
    FIT,
    /** 拉伸铺满，会改变画面宽高比（media3 RESIZE_MODE_FILL）。 */
    FILL,
    /** 保持比例裁剪铺满（media3 RESIZE_MODE_ZOOM）。 */
    ZOOM;

    companion object {
        /** 与 PlayerScreen 里 resizeModes 列表的顺序保持一致：FIT → FILL → ZOOM。 */
        fun fromIndex(index: Int): VideoResizeMode = entries.getOrElse(index) { FIT }
    }
}