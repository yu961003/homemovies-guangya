package com.example.localmovielibrarz.playback.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.ColorLut
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import androidx.media3.effect.SingleColorLut
import com.example.localmovielibrarz.playback.lut.CubeLut
import com.example.localmovielibrarz.playback.lut.LutSettings
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * exo 内核的 3D LUT 着色（§5.2-6）。
 *
 * media3 1.9.1 的 `SingleColorLut` + 内置 `fragment_shader_lut_es2.glsl` 就是与 mpv `target-lut`
 * 同语义的 3D LUT 采样。这里**不重写着色器**，只做三件事：把 [CubeLut] 转成 media3 要的三维
 * `int` 立方、包一层 [GlEffect]、在 HDR 时降级。
 *
 * ## 尺寸与内存
 *
 * media3 把 3D LUT 摊平成 `N × N²` 的 ARGB_8888 位图再上传成纹理。33³ ≈ 143KB，65³ ≈ 1.1MB。
 */
@UnstableApi
object ExoLutEffect {

    /**
     * 把 [lut] 转成 media3 的 [ColorLut]。
     *
     * 索引方向是 `cube[red][green][blue]`（推导见 v1 注释：位图行号 `N*red + green`、列号 `blue`）。
     * v2 直接按 [CubeLut.values] 的展平偏移读取，不再经过 `at()` 的 `Triple` 装箱；
     * 量化改为四舍五入（v1 是截断，整体会偏暗约半个 8 bit 色阶）。
     */
    fun createColorLut(lut: CubeLut): ColorLut {
        val size = lut.size
        val values = lut.values
        val cube = Array(size) { Array(size) { IntArray(size) } }
        var i = 0
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    cube[r][g][b] = packArgb(values[i], values[i + 1], values[i + 2])
                    i += 3
                }
            }
        }
        return SingleColorLut.createFromCube(cube)
    }

    /**
     * 包成 [GlEffect]，截住 HDR 分支：`SingleColorLut` 在 `useHdr=true` 时 `checkState` 抛异常
     * 会中断整条渲染管线（黑屏），所以 HDR 时原样透传。这是有意的功能降级。
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

    /** `0f..1f` 的 rgb → ARGB_8888 打包值（四舍五入）。 */
    private fun packArgb(r: Float, g: Float, b: Float): Int {
        val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}

/**
 * exo 侧 LUT 下发的状态机。
 *
 * ## v2 变化：生成移出主线程
 *
 * v1 在调用线程（主线程）同步生成 LUT：33³ 尚可，65³ 或带创意层/风格 LUT 时会让 UI 卡一帧以上，
 * 滑杆拖动时尤其明显。v2：
 * 1. 生成放到单线程执行器（与 mpv 侧 `lutExecutor` 同一思路），数据来自 [LutSettings.buildLut]
 *    的进程级缓存——mpv 刚生成过的同一组设置这里直接命中；
 * 2. 用「代次」防止旧结果晚到覆盖新结果（v1 正是为了回避这个竞态才坚持同步生成的）：
 *    每次请求递增代次，生成完成回到主线程时只接受最新一代；
 * 3. [applyEffects] 仍在主线程调用（`ExoPlayer.setVideoEffects` 要求在播放器线程）。
 *
 * ## 为什么下发回调是每次传入
 *
 * exo 播放器实例会因内核切换、解码失败重试而被重建；每次调用传入回调天然跟随当前实例。
 *
 * @param onError 出错回调。LUT 失败**绝不能**影响播放，这是硬约束。
 */
@UnstableApi
class ExoLutApplier(
    private val onError: (Throwable) -> Unit = {}
) {

    @Volatile
    private var lastSignature: String? = null

    private val generation = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "exo-lut").apply { isDaemon = true }
    }

    /**
     * 按需下发 [settings]，[applyEffects] 是真正调用 `ExoPlayer.setVideoEffects` 的回调。
     * 必须在主线程调用。
     */
    fun apply(settings: LutSettings, applyEffects: (List<GlEffect>) -> Unit) {
        val signature = if (settings.shouldApply) settings.signature() else ""
        if (signature == lastSignature) return
        lastSignature = signature
        val token = generation.incrementAndGet()
        if (signature.isEmpty()) {
            runCatching { applyEffects(emptyList()) }.onFailure(onError)
            return
        }
        val submitted = runCatching {
            executor.execute {
        val effects = runCatching {
            listOf(ExoLutEffect.wrap(ExoLutEffect.createColorLut(settings.buildLut())))
                }
                mainHandler.post {
                    if (token != generation.get()) return@post // 已被更新的请求覆盖
                    effects
                        .onSuccess { list ->
                            runCatching { applyEffects(list) }.onFailure { error ->
                                lastSignature = null
                                onError(error)
                            }
                        }
                        .onFailure { error ->
            // 生成失败就把签名清掉，允许下一次重试（否则一次 OOM 会永久禁用调色）。
            lastSignature = null
            onError(error)
        }
                }
            }
        }
        if (submitted.isFailure) {
            lastSignature = null
            submitted.exceptionOrNull()?.let(onError)
        }
    }

    /** 播放器被重建时调用：丢弃签名，让新实例能重新收到效果。 */
    fun reset() {
        lastSignature = null
        generation.incrementAndGet()
    }

    /** 释放后台线程（ViewModel 销毁时调用）。 */
    fun release() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }
}
