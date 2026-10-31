package com.example.localmovielibrarz.playback.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Anime4K 实时超分/降噪着色器链 —— 移植自 HomeMovie 4.0.0-beta1。
 *
 * ## 来源与还原方式
 *
 * 4.0.0 只有 APK、没有源码（构建 revision `7a0991f` 在远端不存在，`git fetch` 返回
 * `not our ref`），本文件是**从 APK 反编译产物逐条读回来的行为重建**，不是原始 Kotlin：
 *
 * - 着色器资产：`assets/mpv/shaders/` 下的 14 个 GLSL（4.0.0 原样取出后原封不动随包分发）；
 * - 枚举与文案：4.0.0 的 `en3`（质量 High/Balanced/Fast → 后缀 L/M/S，默认 Fast）
 *   与 `cn3`（模式 A+/B+/C+/A/B/C，默认 C+），字符串逐字对齐；
 * - 链顺序：4.0.0 的 `fn3`（资源解包 + 链构造），本文件 [chain] 与它逐分支一一对应；
 * - 下发方式：4.0.0 的 `db` 在 `mpv_initialize()` 之前以 `glsl-shaders`（`:` 分隔）
 *   下发，且只在「总开关开 + 链非空」时下发 —— 本文件 [resolvePaths] 的返回值
 *   走同一条路径（[MpvOptions.build] → [MpvPlaybackEngine.initialize]）。
 *
 * ## 为什么只做 init 期下发
 *
 * 4.0.0 里还有一条运行期 `change-list glsl-shaders set` 的路径，用于切档后不重建播放器。
 * 本移植**不做**热切换：着色器链换了要重编 shader program，在本仓实测机器上首次编译
 * 有几百毫秒卡顿，且一旦失败就是黑屏。改为「改设置 → 下次起播生效」，与 LUT 的
 * `--target-lut` 同一条纪律（见 `MpvPlaybackEngine.prepareInitialLut` 的注释）。
 *
 * ## 4.0.0 里没有移植的字段
 *
 * 4.0.0 还存了 `mpv_anime_only_enabled`（默认 true），但反编译产物中它只被写进
 * 设置状态类、只出现在 `copy()` / `equals()` / `toString()` 里，**没有任何一处读取并
 * 参与判定**（对 `wn3` 的全部 `.k` 访问做了穷举）。也就是说这一版并没有真正的
 * 「仅动漫自动启用」逻辑，故不移植，也不引入一个空开关。
 */
enum class Anime4kQuality(val label: String, val suffix: String) {
    /** 高质量：CNN 大模型，最慢。 */
    High("高质量", "L"),

    /** 均衡：中等模型。 */
    Balanced("均衡", "M"),

    /** 性能优先：小模型，4.0.0 的默认值。 */
    Fast("性能优先", "S"),
    ;

    companion object {
        val DEFAULT: Anime4kQuality = Fast

        /** 设置里存的是枚举名（与 4.0.0 的 `c.i.name()` 一致）。 */
        fun fromName(raw: String?): Anime4kQuality =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/**
 * Anime4K 的修复档位（4.0.0 的 `cn3`）。
 *
 * 顺序即界面上出现的顺序，**不能重排**：A+/B+/C+ 三个「双 pass」档排在三个
 * 单 pass 档之前，与 4.0.0 声明顺序一致。默认 [CPlus]。
 */
enum class Anime4kMode(val label: String) {
    APlus("A+ · 双重修复"),
    BPlus("B+ · 双重柔和修复"),
    CPlus("C+ · 降噪与修复"),
    A("A · 修复"),
    B("B · 柔和修复"),
    C("C · 降噪"),
    ;

    companion object {
        val DEFAULT: Anime4kMode = CPlus

        fun fromName(raw: String?): Anime4kMode =
            entries.firstOrNull { it.name == raw } ?: DEFAULT
    }
}

/**
 * Anime4K 着色器资产的解包与链解析。
 *
 * 线程约定：可在任意线程调用；[ensureShaders] 内部每次都会比对哈希，
 * 只有内容变化时才重写（与 4.0.0 的 `AtomicFile` + SHA-256 比对等价）。
 */
object MpvAnime4k {

    private const val TAG = "MpvAnime4k"

    /** APK 内资产目录（对应 4.0.0 的 `mpv/shaders/`）。 */
    private const val ASSET_DIR = "mpv/shaders"

    /** filesDir 下的落地目录：mpv 只认文件系统绝对路径。 */
    private const val DIR_NAME = "mpv/shaders-anime4k"

    private const val CLAMP_HIGHLIGHTS = "Anime4K_Clamp_Highlights.glsl"
    private const val AUTO_DOWNSCALE = "Anime4K_AutoDownscalePre_x2.glsl"

    /**
     * 所有需要用到的着色器文件名，顺序与 4.0.0 的静态表一致：
     * 两个公共头 + 每个质量档 4 个 CNN 模型。
     */
    val ALL_SHADERS: List<String> = buildList {
        add(CLAMP_HIGHLIGHTS)
        add(AUTO_DOWNSCALE)
        for (quality in Anime4kQuality.entries) {
            val s = quality.suffix
            add("Anime4K_Restore_CNN_$s.glsl")
            add("Anime4K_Restore_CNN_Soft_$s.glsl")
            add("Anime4K_Upscale_CNN_x2_$s.glsl")
            add("Anime4K_Upscale_Denoise_CNN_x2_$s.glsl")
        }
    }

    /**
     * 把 [mode] + [quality] 展开成着色器文件名序列（**不含**公共尾部）。
     *
     * 分支逐条对应 4.0.0 `fn3` 里的 `when (mode.ordinal)`：
     * ```
     * A+ : Restore_L, Upscale_L, DownscalePre, Restore_L, Upscale_L
     * B+ : RestoreSoft_L, Upscale_L, DownscalePre, RestoreSoft_L, Upscale_L
     * C+ : Denoise_L, DownscalePre, Restore_L, Upscale_L
     * A  : Restore_L, Upscale_L, DownscalePre, Upscale_L
     * B  : RestoreSoft_L, Upscale_L, DownscalePre, Upscale_L
     * C  : Denoise_L, DownscalePre, Upscale_L
     * ```
     * 末尾统一追加 [CLAMP_HIGHLIGHTS]（4.0.0 用 `J + I` 把 Clamp 拼在链尾）。
     *
     * 顺序有意义：Anime4K 作者的推荐链是「先降噪/修复，最后一次性放大」，
     * `AutoDownscalePre` 必须紧跟在「已经放大过的 pass」之后，否则会二次放大。
     */
    fun chain(mode: Anime4kMode, quality: Anime4kQuality): List<String> {
        val s = quality.suffix
        val restore = "Anime4K_Restore_CNN_$s.glsl"
        val restoreSoft = "Anime4K_Restore_CNN_Soft_$s.glsl"
        val upscale = "Anime4K_Upscale_CNN_x2_$s.glsl"
        val denoise = "Anime4K_Upscale_Denoise_CNN_x2_$s.glsl"

        val body = when (mode) {
            Anime4kMode.APlus -> listOf(restore, upscale, AUTO_DOWNSCALE, restore, upscale)
            Anime4kMode.BPlus -> listOf(restoreSoft, upscale, AUTO_DOWNSCALE, restoreSoft, upscale)
            Anime4kMode.CPlus -> listOf(denoise, AUTO_DOWNSCALE, restore, upscale)
            Anime4kMode.A -> listOf(restore, upscale, AUTO_DOWNSCALE, upscale)
            Anime4kMode.B -> listOf(restoreSoft, upscale, AUTO_DOWNSCALE, upscale)
            Anime4kMode.C -> listOf(denoise, AUTO_DOWNSCALE, upscale)
        }
        return body + CLAMP_HIGHLIGHTS
    }

    /**
     * 确保 [ALL_SHADERS] 全部落到 filesDir，返回 `glsl-shaders` 需要的值。
     *
     * @param enabled 总开关；false 时直接返回 null（调用方据此不下发任何选项）。
     * @return `a.glsl:b.glsl` 形式的绝对路径串；任何一步失败返回 null（本次以无
     *   Anime4K 起播），绝不因为超分失败而挡住播放。
     *
     * 为什么返回 null 而不是空串：`glsl-shaders` 传空串在 mpv 里等价于「清空列表」，
     * 但与「从未设置」在日志与状态回读上不同。这里统一走「不设置」。
     */
    fun resolvePaths(
        context: Context,
        enabled: Boolean,
        mode: Anime4kMode,
        quality: Anime4kQuality,
    ): String? {
        if (!enabled) return null
        val dir = ensureShaders(context) ?: return null

        val names = chain(mode, quality)
        val paths = ArrayList<String>(names.size)
        for (name in names) {
            val file = File(dir, name)
            if (!file.isFile) {
                Log.w(TAG, "着色器缺失，本次以无 Anime4K 起播：${file.absolutePath}")
                return null
            }
            // mpv 的 glsl-shaders 是 ':' 分隔路径列表；Android 上路径不含 ':'，
            // 因此不需要转义（与 4.0.0 的 joinToString(":") 一致）。
            paths.add(file.absolutePath)
        }
        return paths.joinToString(":")
    }

    /**
     * 把随包分发的着色器解到 filesDir，返回目录；失败返回 null。
     *
     * 每次调用都对每个文件做 SHA-256 比对，只有内容不同才重写。这样升级 APK 后
     * 旧着色器会被自动换掉，而正常启动只多读几次 ~40 KB 小文件（毫秒级）。
     * 用 [File.renameTo] 做原子替换，避免 mpv 读到写了一半的 GLSL。
     */
    fun ensureShaders(context: Context): File? {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "无法创建着色器目录：${dir.absolutePath}")
            return null
        }
        for (name in ALL_SHADERS) {
            val target = File(dir, name)
            val bytes = runCatching {
                context.assets.open("$ASSET_DIR/$name").use { it.readBytes() }
            }.onFailure { Log.w(TAG, "读取随包着色器失败：$name", it) }.getOrNull() ?: return null

            if (target.isFile && sha256Of(target).contentEquals(sha256Of(bytes))) continue
            val ok = runCatching {
                val tmp = File(dir, "$name.tmp")
                FileOutputStream(tmp).use { it.write(bytes) }
                if (target.isFile) target.delete()
                tmp.renameTo(target)
            }.onFailure { Log.w(TAG, "展开着色器失败：$name", it) }.getOrDefault(false)
            if (!ok || !target.isFile) {
                Log.w(TAG, "着色器落地失败：${target.absolutePath}")
                return null
            }
        }
        return dir
    }

    private fun sha256Of(file: File): ByteArray = runCatching {
        FileInputStream(file).use { sha256Of(it.readBytes()) }
    }.getOrDefault(ByteArray(0))

    private fun sha256Of(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
