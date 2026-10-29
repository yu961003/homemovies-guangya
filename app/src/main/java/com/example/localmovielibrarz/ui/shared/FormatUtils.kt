package com.example.localmovielibrarz.ui.shared

import java.util.Locale

/**
 * 统一的文件大小格式化。
 *
 * 此前全仓有 6 套实现（精度从 %.0f 到 %.2f 不等、有无空格不一致、最大单位只到 MB），
 * 导致同一个 2.35 GB 的文件在详情页显示「2.35 GB」、在网盘列表显示「2.4 GB」。
 * 现统一为：B 取整，KB / MB / GB / TB 保留 1 位小数。
 */
private val SIZE_UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024 && idx < SIZE_UNITS.lastIndex) {
        value /= 1024
        idx++
    }
    return if (idx == 0) {
        "$bytes B"
    } else {
        // 显式指定 Locale，避免部分语言环境下小数点被格式化成逗号
        String.format(Locale.getDefault(), "%.1f %s", value, SIZE_UNITS[idx])
    }
}

/**
 * 可空版本：大小未知时返回「大小未知」。
 * 此前 CloudBrowserScreen 在未知时返回「文件」，语义错误。
 */
fun formatFileSizeOrUnknown(bytes: Long?): String =
    if (bytes == null || bytes <= 0L) "大小未知" else formatFileSize(bytes)
