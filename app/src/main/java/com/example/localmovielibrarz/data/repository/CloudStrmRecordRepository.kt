package com.example.localmovielibrarz.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrarz.data.local.CloudStrmRecordDao
import com.example.localmovielibrarz.data.local.CloudStrmRecordEntity
import com.example.localmovielibrarz.data.local.MovieDao
import com.example.localmovielibrarz.data.local.MovieEntity
import com.example.localmovielibrarz.data.local.ScrapeTaskStatus
import com.example.localmovielibrarz.playback.PickcodeExtractor
import com.example.localmovielibrarz.util.MovieVariant
import com.example.localmovielibrarz.util.detectMovieVariant
import com.example.localmovielibrarz.util.extractMovieNumberInfo
import com.example.localmovielibrarz.util.playbackSourceSuffix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CloudStrmRecordRepository(
    private val context: Context,
    private val dao: CloudStrmRecordDao,
    private val movieDao: MovieDao,
    private val settingsRepository: AppSettingsRepository
) {
    suspend fun get(pickcode: String): CloudStrmRecordEntity? = withContext(Dispatchers.IO) {
        dao.get(pickcode)
    }

    suspend fun getCached(pickcode: String): CloudStrmRecordEntity? = withContext(Dispatchers.IO) {
        dao.get(pickcode)
    }


    suspend fun existingPickcodesForVisibleItems(pickcodes: Set<String>): Set<String> = withContext(Dispatchers.IO) {
        if (pickcodes.isEmpty()) return@withContext emptySet()
        dao.getExistingPickcodes(pickcodes.toList()).toSet()
    }

    suspend fun existingRecordsForVisibleItems(pickcodes: Set<String>): List<CloudStrmRecordEntity> = withContext(Dispatchers.IO) {
        if (pickcodes.isEmpty()) return@withContext emptyList()
        dao.getExistingRecords(pickcodes.toList())
    }

    /**
     * ★ F-2：把「记录存在」细分成三态，供云盘浏览页区分按钮文案。
     *
     * 旧实现只看 `cloud_strm_records` 里有没有这一行就显示「已添加」，于是：
     * - STRM 生成了但没挂到影片（movieId == null）→ 实际还在队列里，却显示已添加；
     * - 影片挂上了但刮削失败 → 实际需要用户重试，也显示已添加。
     * 用户点不动、也看不到该做什么。三态化之后文案与实际状态一致。
     */
    suspend fun classifyVisibleItems(pickcodes: Set<String>): CloudVisibleItemState = withContext(Dispatchers.IO) {
        if (pickcodes.isEmpty()) return@withContext CloudVisibleItemState()
        val records = dao.getExistingRecords(pickcodes.toList())
        if (records.isEmpty()) return@withContext CloudVisibleItemState()
        val added = records.mapTo(linkedSetOf()) { it.pickcode }
        val movieIds = records.mapNotNull { it.movieId }.distinct()
        val statuses = if (movieIds.isEmpty()) {
            emptyMap<Long, String>()
        } else {
            movieDao.getScrapeStatuses(movieIds).associate { it.id to it.scrapeTaskStatus }
        }
        val queued = linkedSetOf<String>()
        val failed = linkedSetOf<String>()
        records.forEach { record ->
            val status = record.movieId?.let(statuses::get)
            when {
                status == null -> queued += record.pickcode
                status == ScrapeTaskStatus.Failed.name -> failed += record.pickcode
            }
        }
        CloudVisibleItemState(
            addedPickcodes = added,
            queuedPickcodes = queued,
            failedPickcodes = failed
        )
    }

    suspend fun getByMovieId(movieId: Long): List<CloudStrmRecordEntity> = withContext(Dispatchers.IO) {
        dao.getByMovieId(movieId)
    }

    suspend fun saveVideoSize(pickcode: String, sizeBytes: Long) = withContext(Dispatchers.IO) {
        if (sizeBytes <= 0L) return@withContext
        dao.get(pickcode)?.let { record ->
            dao.upsert(record.copy(videoSizeBytes = sizeBytes, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun upsertGenerated(
        pickcode: String,
        source: String, // ★ 第九轮：来源标识 "115" | "GUANGYA"
        fileName: String,
        strmUri: String,
        libraryRootUri: String?,
        videoSizeBytes: Long? = null,
        movieId: Long? = null
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = dao.get(pickcode)
        val info = extractMovieNumberInfo(fileName)
        dao.upsert(
            CloudStrmRecordEntity(
                pickcode = pickcode,
                source = source,
                fileName = fileName,
                movieNumber = info?.number,
                variant = detectMovieVariant(fileName).suffix.takeIf { it.isNotBlank() },
                partLabel = info?.partLabel,
                strmUri = strmUri,
                libraryRootUri = libraryRootUri,
                movieId = movieId ?: existing?.movieId,
                videoSizeBytes = videoSizeBytes ?: existing?.videoSizeBytes,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now
            )
        )
    }

    suspend fun attachMovie(pickcode: String, movieId: Long) = withContext(Dispatchers.IO) {
        val existing = dao.get(pickcode) ?: return@withContext
        dao.upsert(existing.copy(movieId = movieId, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateStrmLocation(
        pickcode: String,
        strmUri: String,
        libraryRootUri: String?,
        movieId: Long?
    ) = withContext(Dispatchers.IO) {
        val existing = dao.get(pickcode) ?: return@withContext
        val fileName = DocumentFile.fromSingleUri(context, Uri.parse(strmUri))
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: existing.fileName
        val info = extractMovieNumberInfo(fileName)
        dao.upsert(
            existing.copy(
                fileName = fileName,
                movieNumber = info?.number ?: existing.movieNumber,
                variant = detectMovieVariant(fileName).suffix.takeIf { it.isNotBlank() },
                partLabel = info?.partLabel,
                strmUri = strmUri,
                libraryRootUri = libraryRootUri ?: existing.libraryRootUri,
                movieId = movieId ?: existing.movieId,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteForMovie(movieId: Long, pickcodes: Set<String>) = withContext(Dispatchers.IO) {
        if (pickcodes.isNotEmpty()) {
            dao.deleteByPickcodes(pickcodes.toList())
        }
        dao.deleteByMovieId(movieId)
    }

    suspend fun getByMovieNumber(movieNumber: String): List<CloudStrmRecordEntity> = withContext(Dispatchers.IO) {
        dao.getByMovieNumber(movieNumber)
    }

    suspend fun findSubtitleStorageRecord(
        pickcode: String?,
        fileName: String,
        title: String
    ): CloudStrmRecordEntity? = withContext(Dispatchers.IO) {
        pickcode
            ?.takeIf { it.isNotBlank() }
            ?.let { dao.get(it) }
            ?.takeIf { it.strmUri.isNotBlank() }
            ?.takeIf { canOpenUri(it.strmUri) }
            ?.let { return@withContext it }

        val number = extractMovieNumberInfo(fileName)?.number
            ?: extractMovieNumberInfo(title)?.number
            ?: return@withContext null
        dao.getByMovieNumber(number)
            .firstOrNull { it.strmUri.isNotBlank() && canOpenUri(it.strmUri) }
    }

    private fun canOpenUri(uriString: String): Boolean =
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(uriString))?.use { true } == true
        }.getOrDefault(false)

    suspend fun findStandardSameNumberCandidate(fileName: String, newPickcode: String): CloudStrmRecordEntity? = withContext(Dispatchers.IO) {
        val info = extractMovieNumberInfo(fileName) ?: return@withContext null
        if (info.partLabel != null) return@withContext null
        if (detectMovieVariant(fileName) != MovieVariant.Standard) return@withContext null
        dao.getStandardSameNumberCandidate(info.number, newPickcode)
    }

    /**
     * ★ 第九轮：一次性来源回填——遍历 cloud_strm_records，按 strmUri 内容的 route 更新 source。
     * v18 迁移后首次启动自动触发（SettingsViewModel 以 source_backfilled flag 保证仅一次），
     * 设置页也可手动触发。9000+ 行 SAF 文件读约 3-10 分钟，Dispatchers.IO 后台跑不阻塞 UI。
     */
    suspend fun backfillRecordSources(
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }
    ): SourceBackfillResult = withContext(Dispatchers.IO) {
        val records = dao.getAllRecordsForBackfill()
        var done = 0; var updated = 0; var unreadable = 0; var local = 0
        records.chunked(200).forEach { batch ->
            batch.forEach { record ->
                val content = runCatching {
                    context.contentResolver.openInputStream(Uri.parse(record.strmUri))
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull()
                val source = when {
                    content == null -> null // 文件不可读 → 保留现值，计数上报
                    else -> when (PickcodeExtractor.routeOf(content)) {
                        "guangya_res", "guangya_vod" -> "GUANGYA"
                        "download_m3u", "play", "video_proxy" -> "115"
                        else -> null // 本地/未知路由 → NULL
                    }
                }
                if (source != null && source != record.source) {
                    dao.updateSource(record.pickcode, source)
                    updated++
                }
                if (content == null) unreadable++ else if (source == null) local++
                done++
            }
            onProgress(done, records.size)
        }
        SourceBackfillResult(total = records.size, updated = updated, unreadable = unreadable, local = local)
    }

    suspend fun replacePickcode(
        oldPickcode: String,
        newPickcode: String,
        newVideoName: String
    ): CloudStrmRecordEntity = withContext(Dispatchers.IO) {
        val existing = dao.get(oldPickcode) ?: error("没有找到要替换的旧 STRM 记录")
        val uri = Uri.parse(existing.strmUri)
        val content = "${settingsRepository.getStrmBaseUrl()}/download_m3u/$newPickcode/${Uri.encode(newVideoName)}"
        context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
        } ?: error("无法写入旧 STRM 文件，不能替换 pickcode")
        dao.deleteByPickcode(oldPickcode)
        val updated = existing.copy(
            pickcode = newPickcode,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(updated)
        updated
    }

    suspend fun rebuildIndexAndNormalizeSegments(): CloudStrmIndexResult = withContext(Dispatchers.IO) {
        val rootUris = (settingsRepository.getKnownStrmTreeUris() + settingsRepository.getKnownLibraryRootUris())
            .filter { it.isNotBlank() }
            .distinct()
        if (rootUris.isEmpty()) return@withContext CloudStrmIndexResult()
        val records = mutableListOf<CloudStrmRecordEntity>()
        var renamed = 0
        val now = System.currentTimeMillis()

        fun walk(directory: DocumentFile, rootUri: String) {
            directory.listFiles().forEach { file ->
                if (file.isDirectory) {
                    walk(file, rootUri)
                    return@forEach
                }
                if (!file.name.orEmpty().endsWith(".strm", ignoreCase = true)) return@forEach
                val content = context.contentResolver.openInputStream(file.uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                val pickcode = PickcodeExtractor.extract(content) ?: return@forEach
                val info = extractMovieNumberInfo(file.name.orEmpty())
                val normalized = normalizedSegmentName(directory, file.name.orEmpty(), info)
                val targetFile = if (normalized != null && normalized != file.name) {
                    copyTextFile(directory, normalized, content)?.also {
                        if (file.delete()) renamed += 1
                    } ?: file
                } else {
                    file
                }
                records += CloudStrmRecordEntity(
                    pickcode = pickcode,
                    // ★ 第九轮：来源由 STRM 内容 route 判定（重建索引的 walk 分支已读取 content）
                    source = content.sourceOfRoute() ?: "115",
                    fileName = targetFile.name.orEmpty(),
                    movieNumber = info?.number,
                    variant = detectMovieVariant(targetFile.name.orEmpty()).suffix.takeIf { it.isNotBlank() },
                    partLabel = info?.partLabel,
                    strmUri = targetFile.uri.toString(),
                    libraryRootUri = rootUri,
                    movieId = null,
                    videoSizeBytes = null,
                    createdAt = now,
                    updatedAt = now
                )
            }
        }

        rootUris.forEach { rootUri ->
            DocumentFile.fromTreeUri(context, Uri.parse(rootUri))?.let { root ->
                walk(root, rootUri)
            }
        }
        records += indexKnownMovieRecords(records.map { it.pickcode }.toSet())
        dao.clear()
        dao.upsertAll(records)
        CloudStrmIndexResult(indexed = records.size, renamed = renamed)
    }

    private suspend fun indexKnownMovieRecords(existingPickcodes: Set<String>): List<CloudStrmRecordEntity> {
        val now = System.currentTimeMillis()
        return movieDao.getMoviesSnapshotLite()
            .asSequence()
            .filter { it.videoName.endsWith(".strm", ignoreCase = true) || it.videoUri.endsWith(".strm", ignoreCase = true) }
            .mapNotNull { movie -> movie.toCloudStrmRecord(now, existingPickcodes) }
            .toList()
    }

    private fun MovieEntity.toCloudStrmRecord(now: Long, existingPickcodes: Set<String>): CloudStrmRecordEntity? {
        val content = runCatching {
            context.contentResolver.openInputStream(Uri.parse(videoUri))
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
        }.getOrNull().orEmpty()
        val pickcode = PickcodeExtractor.extract(content) ?: return null
        if (pickcode in existingPickcodes) return null
        val info = extractMovieNumberInfo(videoName) ?: extractMovieNumberInfo(title)
        return CloudStrmRecordEntity(
            pickcode = pickcode,
            // ★ 第九轮：来源由 STRM 内容 route 判定
            source = content.sourceOfRoute() ?: "115",
            fileName = videoName,
            movieNumber = info?.number,
            variant = detectMovieVariant(videoName).suffix.takeIf { it.isNotBlank() },
            partLabel = info?.partLabel,
            strmUri = videoUri,
            libraryRootUri = libraryRootUri,
            movieId = id,
            videoSizeBytes = null,
            createdAt = scannedAtMillis.takeIf { it > 0 } ?: now,
            updatedAt = now
        )
    }

    private fun normalizedSegmentName(
        directory: DocumentFile,
        currentName: String,
        info: com.example.localmovielibrarz.util.MovieNumberInfo?
    ): String? {
        val part = info?.partLabel ?: return null
        val base = directory.name?.takeIf { it.contains(info.number, ignoreCase = true) } ?: info.number
        val desired = "$base${playbackSourceSuffix(part, detectMovieVariant(currentName))}.strm".sanitizeFileName()
        return desired.takeIf { it != currentName && directory.findFile(it) == null }
    }

    private fun copyTextFile(directory: DocumentFile, fileName: String, content: String): DocumentFile? {
        val file = directory.createFile("application/octet-stream", fileName) ?: return null
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
        }
        return file
    }

    private fun String.sanitizeFileName(): String =
        replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "video.strm" }

}

/** ★ 第九轮：STRM 内容 → 来源标识（"GUANGYA" / "115" / null=本地或未知路由）。 */
private fun String.sourceOfRoute(): String? = when (PickcodeExtractor.routeOf(this)) {
    "guangya_res", "guangya_vod" -> "GUANGYA"
    "download_m3u", "play", "video_proxy" -> "115"
    else -> null
}

/** ★ 第九轮：来源回填结果统计。 */
data class SourceBackfillResult(
    val total: Int = 0,
    val updated: Int = 0,
    val unreadable: Int = 0,
    val local: Int = 0
)

data class CloudStrmIndexResult(
    val indexed: Int = 0,
    val renamed: Int = 0
)

/**
 * ★ F-2：云盘可见条目的三态快照。
 *
 * - [addedPickcodes]：已有 STRM 记录（父集）。
 * - [queuedPickcodes]：记录存在但还没挂到影片（movieId == null），实际仍在入库/刮削队列里。
 * - [failedPickcodes]：已挂影片但刮削失败，值得给用户一个重试入口。
 */
data class CloudVisibleItemState(
    val addedPickcodes: Set<String> = emptySet(),
    val queuedPickcodes: Set<String> = emptySet(),
    val failedPickcodes: Set<String> = emptySet()
) {
    val isEmpty: Boolean
        get() = addedPickcodes.isEmpty() && queuedPickcodes.isEmpty() && failedPickcodes.isEmpty()
}
