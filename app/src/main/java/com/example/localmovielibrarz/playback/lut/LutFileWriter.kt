package com.example.localmovielibrarz.playback.lut

import java.io.File
import java.io.FileOutputStream

/**
 * 把 [CubeLut] 落到 mpv 能读的 `.cube` 文件，并管住目录里的旧文件。
 *
 * ## 为什么要落盘
 *
 * mpv 的 `--target-lut` 只认文件路径，没有"内存 LUT"这条通道。所以"实时调色"的实际链路是：
 * 参数变化 → 生成 LUT → 写成文件 → `set target-lut <路径>`。
 *
 * ## 命名即内容
 *
 * 文件名由 [LutSettings.signature] 派生，签名覆盖了所有影响生成结果的输入：
 * 同一套设置重复下发不需要重新生成；不同设置绝不会复用旧文件。
 *
 * ## v2：流式写入
 *
 * v1 先拼一个几 MB 的 `String` 再 `writeText`（UTF-8 编码再复制一遍）；v2 由
 * [CubeLut.writeCube] 直接把 ASCII 字节写进文件流，内存峰值与耗时都显著下降。
 */
class LutFileWriter(private val directory: File) {

    /** 保证目录存在；返回目录本身便于链式调用。 */
    fun ensureDirectory(): File {
        if (!directory.exists()) directory.mkdirs()
        return directory
    }

    /**
     * 确保 [settings] 对应的 LUT 文件存在，返回该文件；[settings] 不需要生效时返回 null。
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
        // 先写临时文件再改名：mpv 可能恰好在此时读这个路径，半截文件会让它加载失败
        // 并保持上一张 LUT（画面停在旧调色），这种"偶发不生效"极难复现。
        val tmp = File(directory, "$name.tmp")
        FileOutputStream(tmp).use { lut.writeCube(it) }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            // rename 在部分文件系统上会失败，退化成直接覆盖写。
            FileOutputStream(target).use { lut.writeCube(it) }
            tmp.delete()
        }
        cleanup(keep = name)
        return target
    }

    /** 删除除 [keep] 之外的所有 LUT 文件，并清掉 `.tmp` 残留。 */
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
