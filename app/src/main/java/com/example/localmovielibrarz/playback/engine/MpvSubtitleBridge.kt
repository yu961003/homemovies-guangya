package com.example.localmovielibrarz.playback.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * 把外挂字幕变成 mpv 可读的本地路径。
 *
 * 为什么必须落盘：
 * - mpv 的 `sub-add` 只接受文件路径或 URL，**不经过 Android 的 ContentResolver**，
 *   所以 `content://`（SAF / 各网盘 provider / 字幕站下载缓存）一律不可直接传入；
 * - moov/字体/编码探测也需要一个可随机读的常规文件。
 *
 * 复用策略：若 [ExternalSubtitle.localFile] 已存在且可读，直接用；否则复制到
 * [cacheDir]/mpv-subtitles/ 下（文件名带内容哈希，命中即复用，避免每次播放重复 IO）。
 */
object MpvSubtitleBridge {

    private const val TAG = "MpvSubtitleBridge"

    fun materialize(context: Context, subtitle: ExternalSubtitle, cacheDir: File): String? {
        subtitle.localFile?.let { file ->
            if (file.exists() && file.canRead() && file.length() > 0) return file.absolutePath
        }

        val uri: Uri = subtitle.uri
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val file = File(path)
            if (file.exists() && file.canRead()) return file.absolutePath
            return null
        }

        val dir = File(cacheDir, "mpv-subtitles").apply { mkdirs() }
        val extension = subtitle.name.substringAfterLast('.', "srt").lowercase(Locale.ROOT)
        val digest = sha1("${uri}|${subtitle.name}")
        val target = File(dir, "$digest.$extension")
        if (target.exists() && target.length() > 0) return target.absolutePath

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                Log.e(TAG, "openInputStream 返回 null: $uri")
                return null
            }
            if (target.length() > 0) target.absolutePath else null
        } catch (t: Throwable) {
            Log.e(TAG, "字幕落盘失败: $uri", t)
            target.delete()
            null
        }
    }

    private fun sha1(value: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { String.format(Locale.US, "%02x", it) }.take(24)
    }
}
