package com.example.localmovielibrarz.playback.engine

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ColorLut
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import androidx.media3.effect.SingleColorLut
import com.example.localmovielibrarz.playback.lut.CubeLut
import com.example.localmovielibrarz.playback.lut.LutSettings

/**
 * exo 内核的 3D LUT 着色（§5.2-6）。
 *
 * ## 为什么要补这一层
 *
 * mpv 侧的下发通道是 `--target-lut <文件>`（见 `MpvPlaybackEngine`：必须 `vo=gpu-next`
 * 且在 `mpv_initialize()` 之前写入）。exo 侧原本文档里写的是「没有对应的色彩管线」。
 * media3 1.9.1 的 `media3-effect` 恰好把缺口补上了：`SingleColorLut` + 内置
 * `fragment_shader_lut_es2.glsl` 就是与 mpv `target-lut` 同语义的 3D LUT 采样
 * （三线性插值、红色索引变化最快）。
 *
 * 所以这里**不重写着色器**——重写一遍只会多一份与 mpv 侧不一致的取样实现。这一层只做
 * 三件事：把 [CubeLut] 转成 media3 要的三维 `int` 立方、包一层 [GlEffect]、在 HDR 时降级。
 *
 * ## 尺寸与内存
 *
 * media3 把 3D LUT 摊平成 `N × N²` 的 **ARGB_8888 位图**再上传成纹理。33³ 的位图是
 * 33×1089 ≈ 143KB，65³ 是 65×4225 ≈ 1.1MB。相对一帧 1080p 纹理可以忽略，但**每次
 * 参数变动都会重建一次**，所以调用方必须按 `LutSettings.signature()` 去重。
 */
@UnstableApi
object ExoLutEffect {

    /**
     * 把 [lut] 转成 media3 的 [ColorLut]。
     *
     * ## 索引方向为什么是 `cube[red][green][blue]`
     *
     * media3 的 `SingleColorLut.transformCubeIntoBitmap(int[][][])` 把下表写进位图：
     * ```
     * pixels[x + n * (y + n * z)] = cube[z][y][x]
     * ```
     * 而 `fragment_shader_lut_es2.glsl` 采样的行号是 `N * red + green`、列号是 `blue`。
     * 位图行号 `row = y + n*z`，代入即 `row = N*red + green`，所以
     * **z 轴是红、y 轴是绿、x 轴是蓝**，也就是 `cube[red][green][blue]`。
     * 写反这一维不会报错，只会让画面偏色——这是这类代码最典型的错法。
     *
     * 每个 `int` 必须是 **ARGB_8888 打包值**（`createFromCube` 直接把它当像素写）：
     * 高 8 位 alpha 必须是不透明的 0xFF，否则位图半透明，采样回来的 rgb 会不对。
     */
    fun createColorLut(lut: CubeLut): ColorLut {
        val size = lut.size
        val cube = Array(size) { Array(size) { IntArray(size) } }
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    // CubeLut.at() 的偏移是 ((b*size + g)*size + r)*3，红色索引变化最快，
                    // 与 .cube 规范一致；这里只做维度搬运，不做任何采样或重排。
                    val (rf, gf, bf) = lut.at(r, g, b)
                    cube[r][g][b] = packArgb(rf, gf, bf)
                }
            }
        }
        return SingleColorLut.createFromCube(cube)
    }

    /**
     * 包成 [GlEffect]。
     *
     * 这一层唯一的作用是**截住 HDR 分支**：`SingleColorLut.toGlShaderProgram(context, true)`
     * 内部是 `checkState(!useHdr, "HDR is currently not supported.")`，也就是 HDR 片源一旦
     * 走到 LUT 就会抛 [IllegalStateException] 并**中断整条视频渲染管线**（不是降级，是
     * 报错/黑屏）。用户开一次调色再看一部 HDR 片就崩，代价太大。
     *
     * 所以 HDR 时返回 [PassthroughShaderProgram]（原样透传）而不是让异常冒出去：
     * 画面保持片源原色，调色在 HDR 片源上静默不生效。这是**有意的功能降级**，不是遗漏——
     * 支持 HDR LUT 需要先做 HDR→SDR 色调映射，那是另一个量级的工程（§5.3 也已把
     * 「按 5.0.5 规格重做 LUT」列为不建议项）。
     */
    fun wrap(colorLut: ColorLut): GlEffect = HdrSafeColorLutEffect(colorLut)

    private class HdrSafeColorLutEffect(private val delegate: ColorLut) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
            if (useHdr) {
                PassthroughShaderProgram()
            } else {
                delegate.toGlShaderProgram(context, useHdr)
            }
    }

    /** `0f..1f` 的线性 rgb → ARGB_8888 打包值。 */
    private fun packArgb(r: Float, g: Float, b: Float): Int {
        val ri = (r.coerceIn(0f, 1f) * 255f).toInt()
        val gi = (g.coerceIn(0f, 1f) * 255f).toInt()
        val bi = (b.coerceIn(0f, 1f) * 255f).toInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}

/**
 * exo 侧 LUT 下发的状态机。
 *
 * 单独拎出来而不是塞进 `PlayerViewModel` 的原因：这段逻辑要按 `LutSettings.signature()`
 * 去重、要在关闭时清空效果、要吞掉所有异常（LUT 出错绝不能影响播放），是可被单测覆盖的
 * 纯逻辑；而 ViewModel 里那段代码跑在 Android 主线程、依赖 ExoPlayer 实例，测不了。
 *
 * ## 为什么下发回调是每次传入，而不是构造时绑定
 *
 * exo 播放器实例会因内核切换、解码失败重试而被重建。若构造时就把
 * `exoPlayer::setVideoEffects` 绑死，重建后仍在往旧实例下发效果——表现为「静默无效」，
 * 日志里也看不出来（`setVideoEffects` 不校验播放器状态）。每次调用传入回调则天然跟随
 * 当前实例。
 *
 * @param onError 出错回调。默认空实现——LUT 失败**绝不能**影响播放，这是硬约束。
 */
@UnstableApi
class ExoLutApplier(
    private val onError: (Throwable) -> Unit = {}
) {

    @Volatile
    private var lastSignature: String? = null

    /**
     * 按需下发 [settings]，[applyEffects] 是真正调用 `ExoPlayer.setVideoEffects` 的回调。
     *
     * 去重键是 `signature()`（覆盖 lutSize / strength / 7 个参数）。注意签名**不含**
     * `enabled`：开关由「下发空列表」表达，与 mpv 侧「下发空路径」同语义（见
     * `LutSettings.effectiveFileName`）。所以关闭时签名要记成空串而不是保留旧值，
     * 否则「关掉再打开同一组参数」会命中缓存、不再下发。
     */
    fun apply(settings: LutSettings, applyEffects: (List<GlEffect>) -> Unit) {
        val signature = if (settings.shouldApply) settings.signature() else ""
        if (signature == lastSignature) return
        lastSignature = signature
        if (signature.isEmpty()) {
            runCatching { applyEffects(emptyList()) }.onFailure(onError)
            return
        }
        // LUT 生成是纯 CPU、33³ 约 20ms。这里同步做（而不是丢到别的线程）有两个理由：
        // 1) 调用点在主线程，滑杆停止后才会触发，20ms 不构成可感知卡顿，
        //    与 mpv 侧 LutFileWriter.materialize 的同步生成代价一致；
        // 2) 异步下发会引入「旧 LUT 晚到、覆盖新 LUT」的竞态——签名去重就失效了。
        // 真正需要异步的是自动分析（几百次参数搜索），那条路径单独走 Dispatchers.Default。
        val effects = runCatching {
            listOf(ExoLutEffect.wrap(ExoLutEffect.createColorLut(settings.buildLut())))
        }.getOrElse { error ->
            // 生成失败就把签名清掉，允许下一次重试（否则一次 OOM 会永久禁用调色）。
            lastSignature = null
            onError(error)
            return
        }
        runCatching { applyEffects(effects) }.onFailure { error ->
            lastSignature = null
            onError(error)
        }
    }

    /** 播放器被重建时调用：丢弃签名，让新实例能重新收到效果。 */
    fun reset() {
        lastSignature = null
    }
}
