package com.example.localmovielibrarz.playback.lut

import java.io.File

/**
 * LUT 生成的进程级缓存与风格 LUT 装载（v2 新增）。
 *
 * ## 解决的问题
 *
 * v1 里同一组设置会被重复生成多次：
 * - mpv 侧 `LutFileWriter.materialize` 生成一次（落盘）；
 * - 切到 exo 时 `ExoLutApplier` 又在**主线程**同步生成一次；
 * - 切回 mpv、或者新建 mpv 实例时，`prepareInitialLut` 再生成一次。
 *
 * 现在所有入口都走 [lutFor]：按 [LutSettings.signature] 命中内存里的最近 [CAPACITY] 张，
 * 不命中才生成。滑杆来回拖动（A→B→A）也能直接命中。
 *
 * 风格 LUT（用户导入的 `.cube`）按路径缓存解析结果：解析一张 65³ 文本在 v1 实现里要近半秒，
 * 每次调参数都重新解析是不可接受的。
 */
object LutEngine {

    /** 最近使用的 LUT 张数。65³ 一张约 3.3 MB（float），3 张是内存与命中率的折中。 */
    private const val CAPACITY = 3

    private val cache = object : LinkedHashMap<String, CubeLut>(CAPACITY + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CubeLut>?): Boolean =
            size > CAPACITY
    }

    private var overlayPath: String? = null
    private var overlayStamp: Long = 0L
    private var overlay: CubeLut? = null

    /** 命中统计，供诊断页与基准测试读取。 */
    @Volatile
    var hits: Int = 0
        private set

    @Volatile
    var misses: Int = 0
        private set

    /** 取 [settings] 对应的 LUT；不命中时生成并放入缓存。线程安全。 */
    fun lutFor(settings: LutSettings): CubeLut {
        val key = settings.signature()
        synchronized(cache) {
            cache[key]?.let {
                hits++
                return it
            }
        }
        val overlayLut = if (settings.hasOverlay) loadOverlay(settings.overlayLutPath!!) else null
        val lut = LutColorMath.generateScaledLut(
            size = settings.lutSize,
            params = settings.params,
            creative = settings.creative,
            strengthPercent = settings.strengthPercent,
            overlay = overlayLut,
            overlayMixPercent = settings.overlayMixPercent
        )
        synchronized(cache) {
            misses++
            cache[key] = lut
        }
        return lut
    }

    /**
     * 装载风格 LUT。文件不存在或解析失败时返回 null（调用方按「无叠加」生成），
     * 绝不让一张坏文件挡住播放。
     */
    @Synchronized
    fun loadOverlay(path: String): CubeLut? {
        val file = File(path)
        if (!file.isFile) return null
        val stamp = file.lastModified() xor file.length()
        if (path == overlayPath && stamp == overlayStamp) return overlay
        val parsed = runCatching { CubeLut.parse(file.readText(Charsets.US_ASCII)) }.getOrNull()
        overlayPath = path
        overlayStamp = stamp
        overlay = parsed
        return parsed
    }

    /** 清空缓存（低内存回调、或单测之间隔离用）。 */
    fun clear() {
        synchronized(cache) { cache.clear() }
        synchronized(this) {
            overlayPath = null
            overlay = null
        }
    }
}
