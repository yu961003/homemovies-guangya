package com.example.localmovielibrarz.playback.lut

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest

/**
 * 导入用户的 `.cube` 风格 LUT（v2 新增能力）。
 *
 * 流程：读取（限 32 MB）→ [CubeLut.parse] 校验（只接受 3D、0..1 定义域）→ 以内容 SHA-1 命名
 * 复制进 `filesDir/luts/` → 返回私有路径写进 [LutSettings.overlayLutPath]。
 *
 * 为什么复制而不是存 content:// URI：SAF 授权会随来源 App 卸载/文件移动失效，而 LUT 每次生成都要读；
 * 以内容哈希命名还让 [LutSettings.signature] 不必 stat 文件就能唯一标识内容。
 */
object LutOverlayImporter {

    private const val DIR = "luts"
    private const val MAX_BYTES = 32L * 1024 * 1024
    private const val KEEP = 8

    data class Imported(val path: String, val displayName: String, val size: Int)

    fun import(context: Context, uri: Uri): Result<Imported> = runCatching {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "lut.cube"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                total += n
                require(total <= MAX_BYTES) { "文件超过 32 MB，不像是 .cube LUT" }
                buffer.write(chunk, 0, n)
            }
            buffer.toByteArray()
        } ?: error("无法读取所选文件")
        val lut = CubeLut.parse(String(bytes, Charsets.UTF_8))
        val hash = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val target = File(dir, "$hash.cube")
        if (!target.isFile) target.writeBytes(bytes)
        prune(dir, keep = target.name)
        Imported(target.absolutePath, name, lut.size)
    }

    /** 只保留最近导入的若干个，避免目录无限增长。 */
    private fun prune(dir: File, keep: String) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".cube") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP).filter { it.name != keep }.forEach { runCatching { it.delete() } }
    }
}
