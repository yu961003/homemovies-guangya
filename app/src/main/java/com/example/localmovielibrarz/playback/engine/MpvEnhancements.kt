package com.example.localmovielibrarz.playback.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * mpv 画质与渲染增强（v2，参考 mpvRx 的能力清单**重新实现**）。
 *
 * ## 许可与取舍
 *
 * mpvRx 以 AGPL-3.0-or-later 发布。把它的 Kotlin 源码复制进本工程会让整个工程受 AGPL 约束，
 * 与本工程的分发方式冲突。因此本文件只参考其**能力与 mpv 选项层面的事实**（选项名、
 * 着色器链顺序这类不受著作权保护的接口事实），代码按本工程的架构独立编写。
 * 随包的 hdr-toys GLSL 着色器来自上游 natural-harmonia-gropius/hdr-toys（MIT），
 * 许可证随 `assets/mpv/shaders-hdr-toys/LICENSE-hdr-toys.txt` 一起分发。
 *
 * ## 与本工程既有约束的关系
 *
 * - `vo=gpu-next` 是 `target-lut` 的前提（见 [MpvOptions]）。mpvRx 的「gpu/gpu-next 自动选择」与之冲突，
 *   这里收敛为一个**显式的省电开关**：只有在「没开调色、没开 HDR 管线」时才允许退回 `vo=gpu`，
 *   其余情况强制 gpu-next，保证调色永远可用。
 * - HDR 管线输出的是经过 hdr-toys 色调映射的 SDR 信号；`target-lut` 作用在输出色彩空间上，
 *   两者叠加会让 LUT 作用在错误的色域/传递函数上。因此 HDR 管线生效期间**暂停实时调色**，
 *   与 exo 侧 `ExoLutEffect` 在 HDR 下透传的行为一致，并通过 UI 明确告知。
 */

/** HDR 色调映射模式。 */
enum class HdrToneMode(val id: String, val label: String, val subtitle: String) {
    /** mpv 默认行为（与 v1 完全一致）。 */
    OFF("off", "关闭", "使用 mpv 内置色调映射（默认）"),

    /** 只在片源确实是 HDR（PQ/HLG）或 BT.2020 广色域时自动启用对应 hdr-toys 链。 */
    AUTO("auto", "自动", "HDR10 / HLG / BT.2020 片源自动启用高质量色调映射"),

    PQ("pq", "BT.2100 PQ", "强制按 HDR10 处理（Astra 色调映射 + Bottosson 色域映射）"),
    HLG("hlg", "BT.2100 HLG", "强制按 HLG 处理"),
    BT2020("bt2020", "BT.2020", "仅做广色域 → BT.709 的色域映射"),

    /** gpu-next 原生 HDR 路径（交给系统/屏幕协商），适合 HDR 屏。 */
    LINEAR("linear", "HDR 屏直出", "gpu-next 原生 HDR 输出，适合支持 HDR 的屏幕");

    companion object {
        fun fromId(id: String?): HdrToneMode = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/**
 * hdr-toys 着色器链。每条链都以 bt1886 编码收尾，最终输出 SDR（BT.709 / BT.1886）。
 *
 * @property targetPrim / [targetTrc] 让 mpv 先把画面转换到「着色器链期望的输入空间」，
 *   着色器链再完成色调映射与色域映射。
 */
enum class HdrToysChain(
    val targetPrim: String,
    val targetTrc: String,
    val shaders: List<String>,
    val shaderOpts: String = ""
) {
    PQ(
        "bt.2020", "pq",
        listOf(
            "utils_clip_both.glsl",
            "transfer-function_pq_inv.glsl",
            "tone-mapping_astra.glsl",
            "gamut-mapping_bottosson.glsl",
            "transfer-function_bt1886.glsl"
        )
    ),
    HLG(
        "bt.2020", "hlg",
        listOf(
            "utils_clip_both.glsl",
            "transfer-function_hlg_inv.glsl",
            "tone-mapping_astra.glsl",
            "gamut-mapping_bottosson.glsl",
            "transfer-function_bt1886.glsl"
        )
    ),
    BT2020(
        "bt.2020", "bt.1886",
        listOf(
            "transfer-function_bt1886_inv.glsl",
            "gamut-mapping_bottosson.glsl",
            "transfer-function_bt1886.glsl"
        )
    );

    companion object {
        /** 所有链用到的着色器（去重），也是随包资产的清单。 */
        val ALL_SHADERS: List<String> = entries.flatMap { it.shaders }.distinct()

        /**
         * AUTO 模式：按片源的 `video-params/gamma` 与 `video-params/primaries` 选链。
         * 返回 null 表示是普通 SDR 片源，不启用任何链（行为与 OFF 相同）。
         */
        fun forSource(gamma: String, primaries: String): HdrToysChain? = when {
            gamma.contains("pq", ignoreCase = true) -> PQ
            gamma.contains("hlg", ignoreCase = true) -> HLG
            primaries.contains("bt.2020", ignoreCase = true) -> BT2020
            else -> null
        }

        fun forMode(mode: HdrToneMode): HdrToysChain? = when (mode) {
            HdrToneMode.PQ -> PQ
            HdrToneMode.HLG -> HLG
            HdrToneMode.BT2020 -> BT2020
            else -> null
        }
    }
}

/** GPU 去色带（mpv `--deband`）。默认值取 mpv 上游默认。 */
data class DebandSettings(
    val enabled: Boolean = false,
    val iterations: Int = 1,
    val threshold: Int = 48,
    val range: Int = 16,
    val grain: Int = 32
) {
    fun toOptions(): List<Pair<String, String>> = if (!enabled) {
        listOf("deband" to "no")
    } else {
        listOf(
            "deband" to "yes",
            "deband-iterations" to iterations.coerceIn(1, 16).toString(),
            "deband-threshold" to threshold.coerceIn(0, 4096).toString(),
            "deband-range" to range.coerceIn(1, 64).toString(),
            "deband-grain" to grain.coerceIn(0, 4096).toString()
        )
    }
}

/**
 * 一次 mpv 会话的增强选项（init 前下发的部分）。
 *
 * 默认值 [DEFAULT] 与 v1 的选项表**完全一致**（不追加任何新选项），保证升级用户行为不变。
 */
data class MpvEnhancementOptions(
    val hdrMode: HdrToneMode = HdrToneMode.OFF,
    val hdrShaderDir: String? = null,
    val deband: DebandSettings = DebandSettings(),
    /** 省电渲染：仅在无调色、无 HDR 管线时用 `vo=gpu`。 */
    val powerSaveRender: Boolean = false,
    /** 音量上限（百分比，100 = 不增强）。 */
    val volumeMaxPercent: Int = 100,
    /** 用户自定义 mpv 选项（高级，见 [MpvUserOptions]）。 */
    val userOverrides: List<Pair<String, String>> = emptyList(),
    /** Anime4K 的 4K 守卫与热降级（见 [ThermalGuard]）。 */
    val anime4kGuards: Boolean = true
) {
    companion object {
        val DEFAULT = MpvEnhancementOptions()
    }
}

/**
 * 用户自定义 mpv 选项（类 `mpv.conf` 语法：每行 `key=value`，`#` 开头为注释）。
 *
 * 对应 mpvRx 的「内置 mpv.conf 编辑器」，但**加了保护名单**：渲染上下文、渲染面、
 * 网络代理/TLS、OSD/输入接管这些由本工程管理的选项不允许被覆盖——改了它们就是黑屏或
 * 云盘直链 403，而且用户很难把问题和自己写的那一行联系起来。被拦下的键会写进解析结果，
 * 设置页据此提示。
 */
object MpvUserOptions {

    /** 不允许覆盖的选项。 */
    val PROTECTED_KEYS: Set<String> = setOf(
        "vo", "gpu-context", "gpu-api", "wid", "hwdec",
        "target-lut", "glsl-shaders", "glsl-shader",
        "user-agent", "http-header-fields", "tls-verify", "tls-ca-file", "stream-lavf-o",
        "cache-dir", "demuxer-cache-dir", "cache-on-disk",
        "osd-level", "osc", "input-default-bindings", "input-vo-keyboard",
        "idle", "ytdl", "screenshot-sw", "config", "config-dir", "include", "script", "scripts"
    )

    data class Parsed(
        val options: List<Pair<String, String>>,
        val rejected: List<String>,
        val invalidLines: List<Int>
    )

    fun parse(text: String): Parsed {
        val options = ArrayList<Pair<String, String>>()
        val rejected = ArrayList<String>()
        val invalid = ArrayList<Int>()
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            val eq = line.indexOf('=')
            val key = (if (eq < 0) line else line.substring(0, eq)).trim().removePrefix("--")
            val value = if (eq < 0) "yes" else line.substring(eq + 1).trim().removeSurrounding("\"")
            if (key.isEmpty() || key.any { it.isWhitespace() }) {
                invalid += index + 1
                return@forEachIndexed
            }
            if (key.lowercase() in PROTECTED_KEYS) {
                rejected += key
                return@forEachIndexed
            }
            options += key to value
        }
        return Parsed(options, rejected, invalid)
    }
}

/**
 * hdr-toys 着色器的随包资产解包（与 [MpvAnime4k.ensureShaders] 同一套 SHA-256 增量写入纪律）。
 */
object MpvHdrShaders {

    private const val TAG = "MpvHdrShaders"
    private const val ASSET_DIR = "mpv/shaders-hdr-toys"
    private const val DIR_NAME = "mpv/shaders-hdr-toys"

    /** 解包全部着色器，返回目录；任一失败返回 null（HDR 管线降级为 OFF，绝不挡住播放）。 */
    fun ensure(context: Context): File? {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        for (name in HdrToysChain.ALL_SHADERS) {
            val target = File(dir, name)
            val bytes = runCatching { context.assets.open("$ASSET_DIR/$name").use { it.readBytes() } }
                .onFailure { Log.w(TAG, "读取 hdr-toys 着色器失败：$name", it) }
                .getOrNull() ?: return null
            if (target.isFile && target.length() == bytes.size.toLong() && sha256(target.readBytes()).contentEquals(sha256(bytes))) {
                continue
            }
            val ok = runCatching {
                val tmp = File(dir, "$name.tmp")
                FileOutputStream(tmp).use { it.write(bytes) }
                if (target.isFile) target.delete()
                tmp.renameTo(target)
            }.getOrDefault(false)
            if (!ok || !target.isFile) {
                Log.w(TAG, "hdr-toys 着色器落地失败：${target.absolutePath}")
                return null
            }
        }
        return dir
    }

    /** 某条链的绝对路径列表。 */
    fun paths(dir: String, chain: HdrToysChain): List<String> = chain.shaders.map { "$dir/$it" }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/**
 * HDR 管线涉及的全部 mpv 属性。切换时**整组**下发，保证不同模式之间的切换与历史无关
 * （只改一部分会残留上一个模式的 target-prim/trc，画面颜色取决于切换顺序）。
 */
internal object HdrPipelineProperties {

    fun forChain(chain: HdrToysChain): List<Pair<String, String>> = listOf(
        "target-colorspace-hint" to "no",
        "target-prim" to chain.targetPrim,
        "target-trc" to chain.targetTrc,
        "inverse-tone-mapping" to "no",
        "hdr-compute-peak" to "no",
        "glsl-shader-opts" to chain.shaderOpts
    )

    fun linear(): List<Pair<String, String>> = listOf(
        "target-colorspace-hint" to "yes",
        "target-prim" to "auto",
        "target-trc" to "auto",
        "inverse-tone-mapping" to "no",
        "hdr-compute-peak" to "yes",
        "glsl-shader-opts" to ""
    )

    fun off(): List<Pair<String, String>> = listOf(
        "target-colorspace-hint" to "auto",
        "target-prim" to "auto",
        "target-trc" to "auto",
        "inverse-tone-mapping" to "no",
        "hdr-compute-peak" to "auto",
        "glsl-shader-opts" to ""
    )
}
