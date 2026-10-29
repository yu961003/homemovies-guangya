package com.example.localmovielibrarz.playback.lut

import java.io.File

/**
 * 把 [CubeLut] 落到 mpv 能读的 `.cube` 文件，并管住目录里的旧文件。
 *
 * ## 为什么要落盘
 *
 * mpv 的 `--target-lut` 只认文件路径，没有"内存 LUT"这条通道（libmpv 的
 * `MPV_FORMAT_*` 属性体系里也没有 3D LUT 类型）。所以"实时调色"的实际链路是：
 * 参数变化 → 生成 LUT → 写成文件 → `set target-lut <路径>`。参考实现
 * （`mpv_realtime_lut.py:1681-1697`）走的也是这条路。
 *
 * ## 命名即内容
 *
 * 文件名由 [LutSettings.signature] 派生，签名覆盖了所有影响生成结果的输入。
 * 因此：
 * - 同一套设置重复下发**不需要重新生成**——文件已经在，直接复用（滑杆停下再动、
 *   切换内核后重新挂载，都命中这条路径）；
 * - 不同设置绝不会复用旧文件——这是"改了参数画面没变"这类 bug 的根源。
 *
 * ## 清理
 *
 * 只保留当前有效的那一份。旧文件不删会让缓存目录随调色次数线性增长
 * （65³ 的一份 LUT 约 4.7MB 文本）。
 */
class LutFileWriter(private val directory: File) {

    /** 保证目录存在；返回目录本身便于链式调用。 */
    fun ensureDirectory(): File {
        if (!directory.exists()) directory.mkdirs()
        return directory
    }

    /**
     * 确保 [settings] 对应的 LUT 文件存在，返回该文件；[settings] 不需要生效时返回 null。
     *
     * 内部先按签名查文件，存在即返回，不存在才生成——这是滑杆连续拖动时避免
     * 重复计算的关键。
     */
    fun materialize(settings: LutSettings): File? {
        val name = settings.effectiveFileName() ?: return null
        val target = File(directory, name)
        if (target.exists() && target.length() > 0L) {
            cleanup(keep = name)
            return target
        }
        ensureDirectory()
        val lut = settings.buildLut()
        val text = lut.toCubeText()
        // 先写临时文件再改名：mpv 可能恰好在此时读这个路径，半截文件会让它加载失败
        // 并保持上一张 LUT（画面停在旧调色），这种"偶发不生效"极难复现。
        val tmp = File(directory, "$name.tmp")
        tmp.writeText(text)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // rename 在部分文件系统上会失败（跨挂载点、Windows 上目标被占用），
            // 退化成直接覆盖写；代价是理论上存在极短窗口，可接受。
            target.writeText(text)
            tmp.delete()
        }
        cleanup(keep = name)
        return target
    }

    /**
     * 删除除 [keep] 之外的所有 LUT 文件。
     *
     * 同时清掉 `.tmp` 残留：上次进程被杀时可能留下半截临时文件。
     */
    fun cleanup(keep: String?) {
        if (!directory.exists()) return
        val names = directory.list()?.toList().orEmpty()
        LutSettings.staleFileNames(names, keep).forEach { name ->
            runCatching { File(directory, name).delete() }
        }
        names.filter { it.endsWith(".tmp") }.forEach { name ->
            runCatching { File(directory, name).delete() }
        }
    }

    /** 当前目录里所有 LUT 文件的字节数，供诊断页展示缓存占用。 */
    fun totalBytes(): Long {
        if (!directory.exists()) return 0L
        return directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
    }
}
