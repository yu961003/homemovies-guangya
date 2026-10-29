package com.example.localmovielibrarz.playback.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 一次装载真正交给 mpv 的 URL，外加它背后必须保活的资源。
 *
 * 为什么需要这层（这是 reason=4 / error=-13 的根因）：
 *
 * - mpv 的 stream 层按 **scheme** 选实现，只认得自己在
 *   `stream/stream_lavf.c` 的 `protocols[]` 里登记过的名字（http / https / rtsp /
 *   rtmp / mms / lavf …）。**`content` 不在其中**——对该 AAR 里的
 *   `libmpv.so` 做 `llvm-strings libmpv.so | grep -x content` 命中 0 条，
 *   而 `http` / `https` / `lavf` / `rtsp` 都在。
 * - 于是 `loadfile content://…` 在 stream 打开阶段就失败，mpv 置
 *   `MPV_ERROR_LOADING_FAILED = -13`，并抛出
 *   `end-file(reason = MPV_END_FILE_REASON_ERROR = 4, error = -13)`。
 *   UI 上看到的就是「mpv 播放失败（reason=4, error=-13）」。
 *   ExoPlayer 不受影响——它自己走 ContentResolver。
 * - 字幕早就有 `MpvSubtitleBridge` 做同样的落盘，**视频主链路漏了这一步**，
 *   这是移植缺陷，不是「Native 整体失败」：libmpv 能被加载、能 create/init、
 *   还能把 mpv 自己的错误码回传，恰恰证明 JNI 桥是通的。
 *
 * 解法（不依赖 mpv 是否编进 `content` 协议）：
 *
 * 1. 可 seek 的普通文件（绝大多数 SAF / MediaStore 情形）→ 交给 mpv
 *    `/proc/self/fd/<fd>`。mpv 用普通 POSIX 打开它，内核把 fd 复制一份，
 *    之后我们的 fd 关不关都不影响它读；`seek` / `SI` / 时间轴跳转全部正常。
 * 2. provider 只给管道（`statSize == -1`）→ 先落盘到 `cacheDir/mpv-media/`
 *    再给绝对路径。MP4 的 moov 在尾部，不可 seek 的流根本 demux 不了。
 * 3. `file://` → 去掉 scheme 给纯路径（mpv 两种都认，纯路径还能让
 *    `sub-auto=fuzzy` 正常找同名字幕）。
 * 4. http(s) 等其它 scheme **原样透传**，不碰。
 */
class MpvMediaSource internal constructor(
    /** 交给 `loadfile` 的字符串。 */
    val url: String,
    /** 原始 scheme，仅用于日志与错误文案（content / file / http …）。 */
    val scheme: String,
    /** 非空表示这个 URL 依赖一个打开的 fd 或落盘文件，装载期间不能关。 */
    private val held: Closeable?
) : Closeable {

    override fun close() {
        held?.let { runCatching { it.close() } }
    }

    override fun toString(): String = "MpvMediaSource(url=$url, scheme=$scheme)"
}

object MpvMediaSources {

    private const val TAG = "MpvMediaSource"

    /** 落盘目录名，与字幕缓存（mpv-subtitles）分开，清理时互不影响。 */
    private const val MEDIA_CACHE_DIR = "mpv-media"

    /**
     * 把 [rawUri] 变成 mpv 打得开的 URL。**必须在 IO 线程调用**（可能落盘）。
     *
     * @return 永远不为 null；无法处理的 scheme 原样返回，让 mpv 自己报错。
     */
    fun resolve(context: Context, rawUri: String, cacheDir: File): MpvMediaSource {
        val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
            ?: return MpvMediaSource(rawUri, "", null)
        val scheme = uri.scheme?.lowercase(Locale.ROOT).orEmpty()
        return when (scheme) {
            "content" -> resolveContent(context, uri, cacheDir)
            "file" -> {
                val path = uri.path?.takeIf { it.isNotEmpty() }
                if (path != null) MpvMediaSource(path, scheme, null)
                else MpvMediaSource(rawUri, scheme, null)
            }
            else -> MpvMediaSource(rawUri, scheme, null)
        }
    }

    private fun resolveContent(context: Context, uri: Uri, cacheDir: File): MpvMediaSource {
        val pfd: ParcelFileDescriptor? = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")
        }.onFailure {
            // SAF 授权被回收（重启后没有 takePersistableUriPermission）就走这里。
            Log.w(TAG, "openFileDescriptor 失败，provider 可能已失去授权：$uri", it)
        }.getOrNull()

        if (pfd != null) {
            val size = runCatching { pfd.statSize }.getOrDefault(-1L)
            if (size >= 0L) {
                val path = "/proc/self/fd/${pfd.fd}"
                Log.i(TAG, "content:// → $path (seekable, size=$size) 原=$uri")
                return MpvMediaSource(path, "content", pfd)
            }
            // statSize = -1：管道或大小未知，mpv 无法 seek，MP4/MKV 的尾部索引读不到。
            Log.i(TAG, "content:// 不可 seek (size=$size)，改为落盘：$uri")
            runCatching { pfd.close() }
        }

        val copied = copyToCache(context, uri, cacheDir)
        if (copied != null) {
            Log.i(TAG, "content:// → ${copied.absolutePath} (${copied.length()} B) 原=$uri")
            return MpvMediaSource(copied.absolutePath, "content", null)
        }

        // 两条路都失败：原样交回去，让 mpv 报出真实错误码，错误文案里带 url 便于定位。
        Log.e(TAG, "content:// 既不能取 fd 也不能落盘，退化为原 URI：$uri")
        return MpvMediaSource(uri.toString(), "content", null)
    }

    private fun copyToCache(context: Context, uri: Uri, cacheDir: File): File? {
        val dir = File(cacheDir, MEDIA_CACHE_DIR).apply { runCatching { mkdirs() } }
        val ext = guessExtension(uri)
        val name = sha1(uri.toString()) + "." + ext
        val target = File(dir, name)
        // 命中即复用：同一个 URI 重播不再复制一遍。
        if (target.isFile && target.length() > 0L) return target

        val tmp = File(dir, "$name.part")
        return runCatching {
            val input = context.contentResolver.openInputStream(uri)
                ?: run {
                    Log.e(TAG, "openInputStream 返回 null：$uri")
                    return null
                }
            input.use { source ->
                tmp.outputStream().use { sink -> source.copyTo(sink, DEFAULT_BUFFER_SIZE) }
            }
            if (tmp.length() <= 0L) {
                Log.e(TAG, "落盘后文件为空：$uri")
                tmp.delete()
                return null
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target
        }.onFailure {
            Log.e(TAG, "content:// 落盘失败：$uri", it)
            tmp.delete()
        }.getOrNull()
    }

    /** 只在扩展名可信时采用；`content://media/external/video/media/123` 这类没有后缀的给 bin。 */
    private fun guessExtension(uri: Uri): String {
        val segment = uri.lastPathSegment ?: return "bin"
        val ext = segment.substringAfterLast('.', "")
        val ok = ext.length in 1..8 && ext.all { it.isLetterOrDigit() }
        return if (ok) ext.lowercase(Locale.ROOT) else "bin"
    }

    private fun sha1(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
        val out = StringBuilder(digest.size * 2)
        for (b in digest) out.append(String.format(Locale.ROOT, "%02x", b))
        return out.toString()
    }
}
