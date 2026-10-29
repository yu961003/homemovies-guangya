package com.example.localmovielibrarz.util

import android.net.Uri
import java.util.Locale

/**
 * 视频格式唯一事实来源（Single Source of Truth）。
 *
 * 背景：此前「视频扩展名白名单」在 8 个文件里各写一份（LibraryScanner / Cloud115StrmRepository /
 * GuangyaStrmRepository / CloudFolderBatchTaskRunner / CloudBrowserScreen / CloudBrowserViewModel /
 * GuangyaBrowserScreen / GuangyaFileItem），增删一个格式要改 8 处，极易漏改并造成
 * 「网盘列表认得出、媒体库扫不进来」这类不一致。本文件收敛为唯一定义。
 *
 * ★ 本轮新增 WMV 支持：WMV（Windows Media Video）原本只出现在云盘侧白名单里，
 *   媒体库扫描（LibraryScanner）与播放器容器识别都未纳入，导致「网盘能看到、扫进媒体库却放不了」。
 *   现统一纳入 [VIDEO_EXTENSIONS]，并补齐 [MIME_TYPES] 与 [isWindowsMediaFile] 判断。
 */
object VideoFormats {

    /**
     * 支持的视频容器扩展名（小写、不含点）。
     *
     * 排序约定：常见度优先，便于日志阅读；判定一律走 [isVideoExtension]，与顺序无关。
     */
    val VIDEO_EXTENSIONS: Set<String> = linkedSetOf(
        // —— 主流容器 ——
        "mp4", "mkv", "avi", "mov", "m4v", "webm", "wmv",
        // —— 流媒体/分段 ——
        "ts", "m2ts", "mts", "flv", "f4v", "mpg", "mpeg", "vob",
        // —— 光盘镜像（115/光鸭常见「ISO 原盘」）——
        "iso",
        // —— 网盘直链占位文件（不代表视频容器本身）——
        "strm"
    )

    /** 需要真机渲染的「Windows Media」系列，播放器需注意编码兼容性（见 [isWindowsMediaExtension]）。 */
    val WINDOWS_MEDIA_EXTENSIONS: Set<String> = setOf("wmv", "asf", "wma")

    /** 扩展名 → MIME 类型。用于 Media3 [androidx.media3.common.MimeTypes] 与系统 Intent 兜底。 */
    val MIME_TYPES: Map<String, String> = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/x-m4v",
        "mkv" to "video/x-matroska",
        "webm" to "video/webm",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        // —— WMV/WMA 系列（新增）——
        "wmv" to "video/x-ms-wmv",
        "asf" to "video/x-ms-asf",
        // —— 其它 ——
        "ts" to "video/mp2t",
        "m2ts" to "video/mp2t",
        "mts" to "video/mp2t",
        "flv" to "video/x-flv",
        "f4v" to "video/x-f4v",
        "mpg" to "video/mpeg",
        "mpeg" to "video/mpeg",
        "vob" to "video/dvd",
        "iso" to "application/x-iso9660-image",
        "strm" to "text/plain"
    )

    /** 带前导点的扩展名列表（兼容历史代码里 `listOf(".mp4", ...)` 的形态）。 */
    fun dotExtensions(): List<String> = VIDEO_EXTENSIONS.map { ".$it" }

    /**
     * 兜底 MIME：扩展名未知时统一交给 Media3 自行嗅探。
     * 注意：值形如 `video/` + 通配符，写在注释里会提前闭合 KDoc，故此处不复述字面量。
     */
    const val FALLBACK_MIME = "video/*"

    /** 从文件名/路径/Uri 中提取小写扩展名（不含点）；无扩展名返回空串。 */
    fun extensionOf(nameOrPath: String?): String {
        if (nameOrPath.isNullOrBlank()) return ""
        // 去掉 query/fragment 后取最后一段，兼容 http 直链
        val path = nameOrPath.substringBefore('?').substringBefore('#')
        val lastDot = path.lastIndexOf('.')
        if (lastDot < 0 || lastDot == path.length - 1) return ""
        val ext = path.substring(lastDot + 1)
        // 扩展名不应包含路径分隔符（防御 "a.b/c" 这类畸形输入）
        if (ext.any { it == '/' || it == '\\' }) return ""
        return ext.lowercase(Locale.ROOT)
    }

    /** 是否为本 App 认可的视频扩展名。 */
    fun isVideoExtension(ext: String?): Boolean =
        !ext.isNullOrBlank() && ext.lowercase(Locale.ROOT) in VIDEO_EXTENSIONS

    /** 文件名是否为视频（按扩展名判定）。 */
    fun isVideoFileName(name: String?): Boolean = isVideoExtension(extensionOf(name))

    /** 是否为 WMV/ASF 系列（供播放器做兼容性提示）。 */
    fun isWindowsMediaExtension(ext: String?): Boolean =
        !ext.isNullOrBlank() && ext.lowercase(Locale.ROOT) in WINDOWS_MEDIA_EXTENSIONS

    /** 扩展名对应的 MIME；未知返回 [FALLBACK_MIME]。 */
    fun mimeTypeOf(ext: String?): String =
        if (ext.isNullOrBlank()) FALLBACK_MIME
        else MIME_TYPES[ext.lowercase(Locale.ROOT)] ?: FALLBACK_MIME

    /** 文件名/Uri → MIME。 */
    fun mimeTypeOfName(nameOrPath: String?): String = mimeTypeOf(extensionOf(nameOrPath))

    /** 按文件名或 file:// / content:// Uri 判定是否视频（兼容无扩展名的网盘直链）。 */
    fun looksLikeVideo(uri: Uri?, fileName: String? = null): Boolean {
        if (isVideoFileName(fileName)) return true
        val path = uri?.lastPathSegment ?: uri?.path
        return isVideoFileName(path)
    }

    /** 归一化用户手输的扩展名：补前导点、转小写、去空白；空输入返回 null。 */
    fun normalizeDotExtension(raw: String?): String? {
        val t = raw?.trim()?.trimStart('.')?.lowercase(Locale.ROOT)
        return t?.takeIf { it.isNotEmpty() }?.let { ".$it" }
    }
}
