package com.example.localmovielibrarz.scanner

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrarz.data.local.MovieEntity
import com.example.localmovielibrarz.data.repository.StoredDocumentLocator
import com.example.localmovielibrarz.playback.PickcodeExtractor
import com.example.localmovielibrarz.util.VideoFormats
import com.example.localmovielibrarz.util.movieKeyFromText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 媒体库扫描器。
 *
 * 性能优化（审查报告 4.0）：
 * - F6：单次遍历分类，避免对同一批 children 重复访问 isFile/isDirectory/name。
 * - F8：优先用 DocumentsContract 批量查询（读 Cursor 列，跳过 DocumentFile 实例创建），
 *       查询失败（厂商兼容问题）时自动降级为 DocumentFile.listFiles()。
 * - F9：目录级并发扫描，Semaphore 限制最多 [SCAN_CONCURRENCY] 个目录同时
 *       listChildren + buildMovie（NFO 解析 IO 也随之并发），内存峰值可控。
 * - F10：Regex 提取为常量。
 * - F1：STRM pickcode 扫描时一次性读取并缓存到 MovieEntity.cachedPickcode。
 */
class LibraryScanner(
    private val context: Context,
    private val storedDocumentLocator: StoredDocumentLocator = StoredDocumentLocator(context)
) {
    /**
     * ★ 视频扩展名统一取自 [VideoFormats]（唯一事实来源）。
     * 此前这里硬编码 10 个扩展名，与云盘侧白名单不一致：
     * 典型的 wmv/ts/iso/flv 在云盘列表认得出，但扫进媒体库时被丢弃。
     * 本轮新增 WMV 支持后，媒体库扫描与播放器识别一并纳入。
     */
    private val videoExtensions: Set<String> = VideoFormats.VIDEO_EXTENSIONS
    private val excludedDirectoryNames = setOf(
        "extrafanart",
        "behind the scenes"
    )
    private val scanSemaphore = Semaphore(SCAN_CONCURRENCY)

    /**
     * 扫描媒体库。
     * @param onProgress 每处理完一个目录回调一次（scanned=已发现影片数，currentPath=当前目录名）。
     *                   回调频率为每目录一次，不会过密；调用方可自行节流。
     */
    suspend fun scan(
        rootUri: Uri,
        onProgress: (scanned: Int, currentPath: String) -> Unit = { _, _ -> }
    ): List<MovieEntity> {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return emptyList()
        val movies = ArrayList<MovieEntity>()
        val scannedCounter = AtomicInteger(0)
        scanDirectory(
            rootUri = rootUri.toString(),
            treeUri = root.uri,
            documentId = null,
            directoryName = root.name.orEmpty(),
            movies = movies,
            scannedCounter = scannedCounter,
            onProgress = onProgress
        )
        onProgress(movies.size, root.name.orEmpty())
        return movies.deduplicateByMovieNumber()
    }

    suspend fun scanFile(rootUri: Uri, videoUri: Uri): MovieEntity? {
        val target = storedDocumentLocator.find(rootUri.toString(), videoUri.toString()) ?: return null
        val siblings = target.directory.listFiles().toList()
        val filesByLowerName = HashMap<String, ChildEntry>()
        siblings.forEach { sibling ->
            val name = sibling.name ?: return@forEach
            if (sibling.isFile) {
                filesByLowerName[name.lowercase(Locale.ROOT)] = sibling.toChildEntry()
            }
        }
        return buildMovie(
            rootUri = rootUri.toString(),
            video = target.file.toChildEntry(),
            filesByLowerName = filesByLowerName
        )
    }

    /**
     * 递归扫描目录。
     * 当前目录的 listChildren + buildMovie 整体在 [scanSemaphore] 内执行（最多 [SCAN_CONCURRENCY] 路并行）；
     * 子目录通过协程递归，但不持有信号量（避免并发满时父子互相等待造成死锁）。
     */
    private suspend fun scanDirectory(
        rootUri: String,
        treeUri: Uri,
        documentId: String?,
        directoryName: String,
        movies: MutableList<MovieEntity>,
        scannedCounter: AtomicInteger,
        onProgress: (scanned: Int, currentPath: String) -> Unit
    ) {
        val scanned = scanSemaphore.withPermit {
            val children = listChildren(treeUri, documentId) ?: fallbackListChildren(treeUri, documentId)

            // F6：单次遍历分类
            val filesByLowerName = HashMap<String, ChildEntry>(children.size / 2)
            val videoFiles = ArrayList<ChildEntry>(children.size / 4)
            val subDirectories = ArrayList<ChildEntry>(children.size / 4)
            children.forEach { child ->
                val lowerName = child.name.lowercase(Locale.ROOT)
                if (child.isDirectory) {
                    if (!child.name.isExcludedMediaAssetDirectory()) subDirectories += child
                } else {
                    filesByLowerName[lowerName] = child
                    if (lowerName.substringAfterLast('.', "") in videoExtensions) videoFiles += child
                }
            }

            // 目录内顺序 buildMovie（目录间已由信号量并发）
            val localMovies = ArrayList<MovieEntity>(videoFiles.size)
            videoFiles.forEach { video ->
                localMovies += buildMovie(rootUri, video, filesByLowerName)
            }
            localMovies to subDirectories
        }
        val (localMovies, subDirectories) = scanned
        synchronized(movies) {
            movies.addAll(localMovies)
        }
        scannedCounter.addAndGet(localMovies.size)

        // 子目录并发递归（不持有信号量，各自竞争 permit）
        if (subDirectories.isNotEmpty()) {
            coroutineScope {
                subDirectories.forEach { child ->
                    launch {
                        scanDirectory(
                            rootUri = rootUri,
                            treeUri = treeUri,
                            documentId = child.documentId,
                            directoryName = child.name,
                            movies = movies,
                            scannedCounter = scannedCounter,
                            onProgress = onProgress
                        )
                    }
                }
            }
        }
        // 每目录一次进度回调（目录处理完成后），频率低，不阻塞扫描主流程
        onProgress(scannedCounter.get(), directoryName)
    }

    /**
     * F8：DocumentsContract 批量查询子目录项，直接读 Cursor 列，跳过 DocumentFile 实例创建。
     * @return 查询成功返回子项列表（可能为空）；查询失败（异常/必需列缺失）返回 null，由调用方降级。
     */
    private fun listChildren(treeUri: Uri, documentId: String?): List<ChildEntry>? {
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri,
                documentId ?: DocumentsContract.getTreeDocumentId(treeUri)
            )
        } catch (e: Exception) {
            return null
        }
        val result = ArrayList<ChildEntry>()
        try {
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                if (idIdx < 0 || mimeIdx < 0 || nameIdx < 0) return null
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(idIdx) ?: continue
                    val name = cursor.getString(nameIdx) ?: continue
                    val mime = cursor.getString(mimeIdx)
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    result += ChildEntry(
                        documentId = docId,
                        uri = uri,
                        name = name,
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    )
                }
            } ?: return null
        } catch (e: Exception) {
            return null
        }
        return result
    }

    /** F8 降级路径：DocumentsContract 查询失败时回退 DocumentFile.listFiles()。 */
    private fun fallbackListChildren(treeUri: Uri, documentId: String?): List<ChildEntry> {
        val directory = if (documentId != null) {
            // 子目录：fromSingleUri 失败时按空目录处理，绝不回退到根目录（避免错列）
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
                .let { DocumentFile.fromSingleUri(context, it) }
        } else {
            DocumentFile.fromTreeUri(context, treeUri)
        } ?: return emptyList()
        return directory.listFiles().toList().map { it.toChildEntry() }
    }

    private fun DocumentFile.toChildEntry(): ChildEntry = ChildEntry(
        documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: uri.toString(),
        uri = uri,
        name = name.orEmpty(),
        isDirectory = isDirectory,
        size = length()
    )

    private fun buildMovie(
        rootUri: String,
        video: ChildEntry,
        filesByLowerName: Map<String, ChildEntry>
    ): MovieEntity {
        val videoName = video.name
        val baseName = videoName.substringBeforeLast('.', videoName)
        val nfoFile = findFirstExisting(
            filesByLowerName,
            listOf("$baseName.nfo", "movie.nfo", "tvshow.nfo")
        ) ?: filesByLowerName.values.firstOrNull { it.name.endsWith(".nfo", ignoreCase = true) }

        val metadata = nfoFile?.let { NfoParser(context.contentResolver).parse(it.uri) } ?: NfoMetadata()
        val posterFile = findImage(filesByLowerName, baseName, ImageKind.Poster)
        val fanartFile = findImage(filesByLowerName, baseName, ImageKind.Fanart)
        val thumbFile = findImage(filesByLowerName, baseName, ImageKind.Thumb)
        val fallbackTitle = baseName.replace('.', ' ').replace('_', ' ').trim()
        val title = metadata.title?.takeIf { it.isNotBlank() } ?: fallbackTitle

        // F1 优化：仅对 STRM 文件在扫描时读取一次内容并缓存 pickcode，
        // 后续同步流程（synchronizedMovies / stale 检测 / STRM 记录更新）不再重复打开文件。
        val cachedPickcode = if (videoName.endsWith(".strm", ignoreCase = true)) {
            runCatching {
                context.contentResolver.openInputStream(video.uri)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
            }.getOrNull().orEmpty().let { content -> PickcodeExtractor.extract(content) }
        } else {
            null
        }

        return MovieEntity(
            libraryRootUri = rootUri,
            videoUri = video.uri.toString(),
            videoName = videoName,
            sortTitle = title.removePrefix("The ").removePrefix("A "),
            title = title,
            originalTitle = metadata.originalTitle,
            plot = metadata.plot,
            outline = metadata.outline,
            year = metadata.year,
            premiered = metadata.premiered,
            runtimeMinutes = metadata.runtimeMinutes,
            mpaa = metadata.mpaa,
            studios = metadata.studios,
            series = metadata.series,
            directors = metadata.directors,
            actors = metadata.actors,
            genres = metadata.genres,
            tags = metadata.tags,
            rating = metadata.rating,
            uniqueIds = metadata.uniqueIds,
            posterUri = posterFile?.uri?.toString(),
            fanartUri = fanartFile?.uri?.toString(),
            thumbUri = thumbFile?.uri?.toString(),
            nfoUri = nfoFile?.uri?.toString(),
            scannedAtMillis = System.currentTimeMillis()
        ).apply {
            // F1：STRM pickcode 缓存为类体属性（Room @Ignore），构造后赋值
            this.cachedPickcode = cachedPickcode
        }
    }

    private fun String.isExcludedMediaAssetDirectory(): Boolean {
        val normalizedName = trim()
            .lowercase(Locale.ROOT)
            .replace('_', ' ')
            .replace('-', ' ')
            .replace(WHITESPACE_REGEX, " ")
        return normalizedName in excludedDirectoryNames
    }

    private fun findImage(
        filesByLowerName: Map<String, ChildEntry>,
        baseName: String,
        kind: ImageKind
    ): ChildEntry? {
        val suffixes = when (kind) {
            ImageKind.Poster -> listOf("$baseName-poster", "poster", "movie-poster")
            ImageKind.Fanart -> listOf("$baseName-fanart", "fanart", "movie-fanart")
            ImageKind.Thumb -> listOf("$baseName-thumb", "thumb", "$baseName-fanart", "fanart", "movie-fanart")
        }
        val extensions = listOf("jpg", "jpeg", "png", "webp")
        val candidates = suffixes.flatMap { suffix -> extensions.map { ext -> "$suffix.$ext" } }
        return findFirstExisting(filesByLowerName, candidates)
            ?: findFirstImageBySuffix(filesByLowerName, kind, extensions)
    }

    private fun findFirstImageBySuffix(
        filesByLowerName: Map<String, ChildEntry>,
        kind: ImageKind,
        extensions: List<String>
    ): ChildEntry? {
        val suffixes = when (kind) {
            ImageKind.Poster -> listOf("-poster", "poster")
            ImageKind.Fanart -> listOf("-fanart", "fanart")
            ImageKind.Thumb -> listOf("-thumb", "thumb", "-fanart", "fanart")
        }
        return filesByLowerName.entries
            .sortedBy { it.key.length }
            .firstOrNull { (name, file) ->
                !file.isDirectory &&
                    extensions.any { ext -> name.endsWith(".$ext") } &&
                    suffixes.any { suffix -> name.substringBeforeLast('.').endsWith(suffix) }
            }
            ?.value
    }

    private fun findFirstExisting(
        filesByLowerName: Map<String, ChildEntry>,
        candidates: List<String>
    ): ChildEntry? = candidates.firstNotNullOfOrNull { candidate ->
        filesByLowerName[candidate.lowercase(Locale.ROOT)]
    }

    private fun List<MovieEntity>.deduplicateByMovieNumber(): List<MovieEntity> {
        return groupBy { movie -> movie.movieNumberKey() ?: "uri:${movie.videoUri}" }
            .values
            .map { duplicates ->
                duplicates.maxWith(
                    compareBy<MovieEntity> { if (it.nfoUri != null) 1 else 0 }
                        .thenBy { if (it.posterUri != null) 1 else 0 }
                        .thenBy { if (it.fanartUri != null) 1 else 0 }
                        .thenBy { if (it.thumbUri != null) 1 else 0 }
                        .thenBy { it.videoUri.length }
                )
            }
    }

    private fun MovieEntity.movieNumberKey(): String? {
        val source = listOf(videoName, title, originalTitle.orEmpty(), uniqueIds.joinToString(" "))
            .joinToString(" ")
        return movieKeyFromText(source)
    }

    private companion object {
        const val SCAN_CONCURRENCY = 4

        // F10 优化：Regex 提取为常量，避免每次调用重复编译。
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}

/** F8：SAF 子项精简投影（代替 DocumentFile 实例）。 */
private data class ChildEntry(
    val documentId: String,
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val size: Long
)

private enum class ImageKind {
    Poster,
    Fanart,
    Thumb
}
