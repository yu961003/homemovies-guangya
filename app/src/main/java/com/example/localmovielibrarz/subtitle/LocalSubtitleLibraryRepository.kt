package com.example.localmovielibrarz.subtitle

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.localmovielibrarz.data.local.LocalSubtitleLibraryDao
import com.example.localmovielibrarz.data.repository.AppSettingsRepository
import com.example.localmovielibrarz.util.normalizeMovieNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地字幕库：手机里某个文件夹被用户选成「字幕库」后，扫描结果落进 `local_subtitle_library` 表，
 * 播放时按番号检索同一归一规则命中的字幕文件。
 *
 * 为什么不复用 `LocalSubtitleStore`：那是「视频旁边有没有字幕」的即扫即用逻辑，只在单个
 * 视频所在目录里找；本地字幕库是跨目录、跨影片的索引，需要落库才能在几千条里按番号秒查。
 *
 * 为什么索引要落库而不是每次现扫：SAF 目录遍历在几千个文件上要几秒到几十秒，
 * 而「打开字幕面板 → 切到本地字幕库」是用户高频、低容忍的操作。
 */
class LocalSubtitleLibraryRepository(
    private val context: Context,
    private val dao: LocalSubtitleLibraryDao,
    private val settingsRepository: AppSettingsRepository
) {
    /** 当前选中的字幕库根目录；没选过返回 null。 */
    fun libraryRootUri(): Uri? =
        settingsRepository.getLocalSubtitleLibraryUri()?.let { uri ->
            runCatching { Uri.parse(uri) }.getOrNull()
        }

    fun libraryRootDisplayName(): String =
        settingsRepository.getLocalSubtitleLibraryDisplayName()

    /**
     * 扫描并重建索引。整个根目录的索引一次性替换：扫描器产出的是全集，
     * 所以用户删掉或移动过的字幕会自然从索引里消失，不会留下「点了报文件不存在」的脏行。
     *
     * @return 索引里最终有多少条
     */
    suspend fun scanAndIndex(
        rootUri: Uri,
        onProgress: (scanned: Int, found: Int) -> Unit = { _, _ -> }
    ): Int {
        val items = LocalSubtitleLibraryScanner.scan(context, rootUri, onProgress)
        val indexedAt = System.currentTimeMillis()
        dao.replaceRoot(rootUri.toString(), items.map { it.copy(indexedAt = indexedAt) })
        settingsRepository.saveLocalSubtitleLibraryIndexedAt(indexedAt)
        Log.i(TAG, "本地字幕库索引完成：${items.size} 条（根目录=$rootUri）")
        return items.size
    }

    /**
     * 按番号找字幕。番号走和影片库同一套 [normalizeMovieNumber]，所以 `AB-12` / `ab012` /
     * `[组]AB-12.chs.srt` 都能对上同一行索引。
     */
    suspend fun searchByNumber(number: String): List<LocalSubtitleFile> {
        if (number.isBlank()) return emptyList()
        val key = normalizeMovieNumber(number) ?: number.trim().uppercase()
        return dao.searchByNumber(key).mapNotNull { entity ->
            runCatching { Uri.parse(entity.uri) }
                .getOrNull()
                ?.let { uri -> LocalSubtitleFile(name = entity.fileName, uri = uri) }
        }
    }

    /** 找不到番号时兜底：按文件名片段搜（用户可能只记得「中文字幕」这类线索）。 */
    suspend fun searchByKeyword(keyword: String, limit: Int = 50): List<LocalSubtitleFile> {
        if (keyword.isBlank()) return emptyList()
        val escaped = keyword
            .replace("\\", "\\\\")
            .replace("%", "\%")
            .replace("_", "\_")
        return dao.searchByKeyword(escaped, limit).mapNotNull { entity ->
            runCatching { Uri.parse(entity.uri) }
                .getOrNull()
                ?.let { uri -> LocalSubtitleFile(name = entity.fileName, uri = uri) }
        }
    }

    suspend fun count(): Int {
        val root = libraryRootUri()?.toString() ?: return 0
        return dao.countByRoot(root)
    }

    suspend fun clear() {
        val root = libraryRootUri()?.toString() ?: return
        dao.deleteByRoot(root)
        settingsRepository.saveLocalSubtitleLibraryIndexedAt(0L)
    }

    private companion object {
        const val TAG = "LocalSubtitleLibrary"
    }
}
