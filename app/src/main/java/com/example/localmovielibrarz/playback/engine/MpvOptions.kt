package com.example.localmovielibrarz.playback.engine

import android.content.Context
import android.util.Log
import com.example.localmovielibrarz.mpv.MPVLib
import com.example.localmovielibrarz.playback.DEFAULT_USER_AGENT
import java.io.File

/**
 * libmpv 的启动参数。
 *
 * 设计原则：**凡是 exo 侧已有的行为，这里都要有对应项**，否则「功能对齐」就是空话。
 * 每一项都写明它与 exo 的哪条行为对应，方便逐条核对（见交付文档的对齐表）。
 */
object MpvOptions {

    private const val TAG = "MpvOptions"

    /**
     * 生成 mpv 配置。
     *
     * @param context 预留（历史实现曾用它取 `Build.VERSION` 做版本分支，现已不需要；
     *   保留参数以维持既有调用点与 androidTest 的签名不变）。
     * @param hwdec 由 [decideHwdec] 决定；硬解确实不可用时回落软解而不是黑屏。
     * @param cacheDir mpv 的 demuxer 缓存目录，用于在线字幕/网络流缓存（与 exo 的 LoadControl 对位）。
     * @param caFilePath [MpvCaBundle.ensure] 给出的 CA 包绝对路径；null 表示没有可用 CA，
     *   此时**不下发** `tls-ca-file`，并且把 `tls-verify` 显式降为 `no`——
     *   绝不留下「要求校验但没有锚」的组合，那会让 mbedtls 报
     *   `No CA chain is set, but required to operate.` 并最终 -13。
     */
    fun build(
        context: Context,
        hwdec: String,
        cacheDir: File,
        caFilePath: String?,
    ): List<Pair<String, String>> = buildList {
        // ── 渲染 ───────────────────────────────────────────────────────────────
        // exo: PlayerView 默认 SurfaceView 输出。mpv 对应 vo=gpu + android context。
        add("vo" to "gpu")
        add("gpu-context" to "android")
        // 硬件解码：exo 侧是 DefaultRenderersFactory 硬解优先 + 解码器回退（EXTENSION_RENDERER_MODE_PREFER）。
        // mpv 对应 hwdec=mediacodec（copy 模式兼容性最好），软解作为内部回退。
        add("hwdec" to hwdec)
        // exo: setVideoChangeFrameRateStrategy(OFF). mpv 侧关闭自动帧率切换以保持一致。
        add("video-sync" to "audio")
        // π 校准对齐：exo 用 CLOSEST_SYNC 近似 seek，mpv 对应 hr-seek=no（关键帧 seek，快）
        add("hr-seek" to "no")

        // ── 音频 ───────────────────────────────────────────────────────────────
        // exo: AudioAttributes USAGE_MEDIA / CONTENT_TYPE_MOVIE / ALLOW_CAPTURE_BY_ALL
        add("ao" to "audiotrack")
        add("audio-channels" to "auto-safe")

        // ── 网络 ───────────────────────────────────────────────────────────────
        // exo: DefaultHttpDataSource + allowCrossProtocolRedirects(true)。
        // ★ 主路径已改为「mpv → 127.0.0.1 回环代理 → OkHttp」，见 MpvHttpProxy：
        //   下面的 UA / stream-lavf-o / TLS 三项只在代理没起来、退回直连时才起作用。
        add("network-timeout" to "30")
        add("stream-lavf-o" to "reconnect=1,reconnect_streamed=1,reconnect_delay_max=5")
        // exo: DefaultHttpDataSource.setUserAgent(DEFAULT_USER_AGENT)。
        // 原来这里是自造的 "Homemovies/MPV" UA，与 exo 发的完全不是同一个字符串——
        // 云盘直链对 UA 是敏感的（光鸭/115 都会校验），UA 不一致本身就是一次对齐缺陷。
        add("user-agent" to DEFAULT_USER_AGENT)
        // TLS：**只对直连兜底路径有意义**。mpv 0.41 的 stream_lavf.c 里
        // `OPT_FLAG("tls-verify", …, 1, false)` —— 默认是 **false**，即默认不校验证书。
        // 所以「不做校验」从来不是 -13 的原因；Round-1 把它当根因是误判（已更正）。
        //
        // 但这里有个必须成对处理的陷阱：tls-verify=yes **配不上**证书包时，mbedtls 会
        // 直接报 "No CA chain is set, but required to operate." 然后 -13 —— 也就是说
        // 「开了校验却没有锚」比「干脆不校验」更糟，属于自己造出来的失败面。
        // 原来是无论 CA 在不在都写死 yes，CA 解包一旦失败，兜底直连就必然打不开。
        // 现在两者绑定：有锚才开校验，没锚就退回 mpv 自己的默认（不校验）。
        if (!caFilePath.isNullOrBlank()) {
            add("tls-verify" to "yes")
            add("tls-ca-file" to caFilePath)
        } else {
            add("tls-verify" to "no")
            Log.w(TAG, "没有可用的 CA 包：退回直连时按 mpv 默认不校验证书（代理正常时不受影响）")
        }

        // ── 缓存 ───────────────────────────────────────────────────────────────
        // exo: DefaultLoadControl(min 15s / max 60s / 1.5s / 3s)
        add("cache" to "yes")
        // 注意：mpv 没有 `--cache-dir`，本仓 libmpv 里只有 icc-cache-dir /
        // demuxer-cache-dir / gpu-shader-cache-dir。写 `cache-dir` 会被
        // mpv_set_option_string 返回 MPV_ERROR_PROPERTY_NOT_FOUND 并静默丢弃
        // （本文件调用点只 Log.w），于是「缓存目录」这一项从来就没生效过。
        // 要落盘必须同时给 cache-on-disk=yes，否则 demuxer-cache-dir 只是个摆设。
        add("cache-on-disk" to "yes")
        add("demuxer-cache-dir" to cacheDir.absolutePath)
        add("cache-secs" to "60")
        add("demuxer-max-bytes" to "64MiB")
        add("demuxer-max-back-bytes" to "16MiB")

        // ── 字幕 ───────────────────────────────────────────────────────────────
        // exo: 外挂字幕由应用手动挂载（subtitleFeaturesEnabled 开关控制），内嵌字幕自动选。
        // mpv 对应 sub-auto=fuzzy（同目录同名自动加载）+ 手动 sub-add。
        add("sub-auto" to "fuzzy")
        add("sub-visibility" to "yes")
        // 显式写出默认值：带样式标签的 ASS 外挂字幕以文件自带样式为准，
        // 上面的 sub-ass-style-overrides 只对「没有样式标签」的字幕（含 srt 转 ASS）生效。
        // 不用 force——那会覆盖掉 ASS 文件自带的样式，与 embeddedfonts=yes 的意图相悖。
        add("sub-ass-override" to "yes")
        // 内嵌字体：ASS 字幕自带字体时优先用文件里的，避免落到系统缺失字体的方块字。
        add("embeddedfonts" to "yes")
        // exo: CaptionStyleCompat 白字 + 黑描边 + 半透明底
        //
        // 这里不能照抄桌面 mpv 的 `--sub-color/--sub-border-color/--sub-back-color/
        // --sub-border-size/--sub-shadow-offset`：本仓 libmpv.so 里这些名字一个都搜不到
        // （sub-font 同样是 0 命中），设了只会得到 NEGATIVE 返回值再被静默吞掉。
        // ASS 字幕的颜色/描边在 v0.41 里走 `--sub-ass-style-overrides`，键名沿用
        // ASS 规范的 Style.Param（PrimaryColour / OutlineColour / BackColour / BorderSize）。
        // 纯文本字幕（srt 等）的样式仍可用 `--sub-ass-override=force` + 通用 --sub-* 生效；
        // 这里给 ASS 侧一套与 exo CaptionStyleCompat 对齐的覆写值。
        // 颜色格式为 ASS 的 &HAABBGGRR（注意 BGR 顺序与 &H 前缀），不写 #RRGGBB；
        // 且 ASS 的 alpha 是**取反**的（00=不透明、FF=全透明），与 Android #AARRGGBB 相反。
        // BorderStyle 只能二选一：1=outline(+shadow)、3=opaque box。exo 那边是
        // EDGE_TYPE_OUTLINE + 半透明背景色同时存在，ASS 做不到两者兼得；这里选 3 保底框，
        // 因为字幕可读性主要靠底框，而取 1 时 BackColour 会变成阴影色、配 Shadow=0 两者都看不到。
        // 运行期 [MpvPlaybackEngine.applySubtitleStyle] 会按用户设置重算这两个值，此处是默认值。
        add(
            "sub-ass-style-overrides" to listOf(
                "PrimaryColour=&H00FFFFFF",   // 白字
                "OutlineColour=&H00000000",   // 黑描边
                "BackColour=&H66000000",      // 半透明黑底：60% 不透明 → alpha 取反 0x66
                "BorderStyle=3",              // opaque box：用 BackColour 当底框
                "Outline=2",                  // 描边宽度 2
                "Shadow=0",                   // 关阴影，对应 exo 的 shadow-offset=0
                "FontName=sans-serif",        // 不指定字体时的兜底，对应原 --sub-font
            ).joinToString(",")
        )

        // ── 其它 ───────────────────────────────────────────────────────────────
        // exo: 无 OSD；mpv 默认会在画面上烧字，必须关掉，UI 全部由 Compose 负责
        add("osd-level" to "0")
        // 输入：不接管按键（UI 层已有手势与按钮）
        add("input-default-bindings" to "no")
        add("input-vo-keyboard" to "no")
        add("osc" to "no")
        // 保持画面比例，由外层按 resizeMode 决定裁剪（FIT/FILL/ZOOM）
        add("keepaspect" to "yes")
        add("video-unscaled" to "no")
        // 日志量：info 级足够定位问题，all 会刷屏拖慢事件线程
        add("msg-level" to "all=info")
        // 关掉 ytdl 钩子。libmpv.so 里内嵌了 ytdl_hook 脚本（二进制里能逐字搜到
        // `file-local-options/user-agent` 之类的写法），它会拦截**所有** ^https?:// 的
        // loadfile 去跑一遍 yt-dlp。本应用只播云盘直链/本地文件，从来不播 YouTube，
        // 而 Android 上没有 yt-dlp 可执行文件——多这一层只会白白探测一次再回落。
        // 改道回环代理后 URL 变成 http://127.0.0.1:…，同样会被它匹配，所以必须显式关。
        add("ytdl" to "no")
        // 结束行为：不自动播放下一个（exo 侧也是单条 mediaItem）
        add("idle" to "yes")
    }

    /**
     * 把「界面上选的档位」翻译成 mpv 的 `hwdec` 取值。
     *
     * 硬解依赖 FFmpeg 的 JNI 桥接（`av_jni_set_java_vm`）。桥接没建立时
     * `mediacodec*` 会在解码器初始化阶段失败——表现为**画面黑、声音正常**，
     * 而错误码只出现在 mpv 日志里。所以这里必须降级到 `no`：
     * 把「用户选了硬解但机器不支持」变成「能看画面」，而不是黑屏。
     *
     * 具体映射见 [MpvHwdecMode.mpvValue]（`mediacodec` / `mediacodec-copy` / `no`）。
     */
    fun resolveHwdecValue(mode: MpvHwdecMode): String {
        if (mode == MpvHwdecMode.SW) return mode.mpvValue
        if (!MPVLib.isHwdecBridgeReady()) {
            // 桥接不可用时 mediacodec* 会在解码器初始化阶段失败，表现是「画面黑、声音正常」，
            // 而且错误码只在 mpv 日志里。这里必须降到软解：让用户看到画面，而不是看黑屏。
            Log.w(TAG, "FFmpeg JNI 桥接不可用，${mode.label} 降级为软解（hwdec=no）")
            return MpvHwdecMode.SW.mpvValue
        }
        return mode.mpvValue
    }

    /**
     * 按档位决定 `hwdec` 取值（[resolveHwdecValue] 的语义化别名）。
     *
     * 保留这个名字是因为「装配 mpv 选项」的调用点读起来更像 decide 而不是 resolve；
     * 真正的映射只有一份，就在 [resolveHwdecValue] 里。
     */
    fun decideHwdec(mode: MpvHwdecMode): String = resolveHwdecValue(mode)
}
