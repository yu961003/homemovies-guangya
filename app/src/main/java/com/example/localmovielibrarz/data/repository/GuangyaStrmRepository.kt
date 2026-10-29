package com.example.localmovielibrarz.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrarz.cloudguangya.GuangyaApiClient
import com.example.localmovielibrarz.cloudguangya.GuangyaFileItem
import com.example.localmovielibrarz.util.MovieVariant
import com.example.localmovielibrarz.util.detectMovieVariant
import com.example.localmovielibrarz.util.extractMovieNumberInfo
import com.example.localmovielibrarz.util.playbackSourceSuffix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.localmovielibrarz.util.VideoFormats

/**
 * 光鸭 STRM 生成仓储（镜像 Cloud115StrmRepository）。
 *
 * 关键铁律：STRM 内容只写稳定 fileId，不写临时 signedURL：
 *   ${strmBaseUrl}/guangya_res/$fileId/$encodedName
 * 播放时由 PlaybackResolver 按 route 分流到 GuangyaDirectLinkResolver 实时解析直链。
 *
 * 注意：下方复用的 GeneratedStrmFile / WrittenStrmFile 由同包的 Cloud115StrmRepository 定义，
 * 本文件不再重复声明（避免同一包内数据类重声明冲突）。
 */
class GuangyaStrmRepository(
    private val context: Context,
    private val guangyaClient: GuangyaApiClient,
    private val settingsRepository: AppSettingsRepository,
    private val recordRepository: CloudStrmRecordRepository
) {
    suspend fun listFiles(parentId: String): List<GuangyaFileItem> = guangyaClient.listFiles(parentId)

    suspend fun generateStrmForVideo(item: GuangyaFileItem): GeneratedStrmFile = withContext(Dispatchers.IO) {
        if (item.isDir) error("请选择一个视频文件")
        if (!item.isVideoFile()) error("当前文件不是支持的视频格式")
        val fileId = item.fileId.takeIf { it.isNotBlank() } ?: error("这个文件没有 fileId，无法生成 STRM")

        recordRepository.getCached(fileId)?.let { existing ->
            return@withContext GeneratedStrmFile(
                fileName = existing.fileName,
                pickcode = fileId,
                strmUri = existing.strmUri,
                created = false,
                shouldScrape = false // 审查 Y2：对齐 115 镜像，缓存命中不应再触发刮削
            )
        }

        val targetRoot = requireWritableStrmRoot()
        val segmentInfo = extractMovieNumberInfo(item.name)
        val variant = detectMovieVariant(item.name)

        // ★ 第九轮（问题 4 修复 4-3）：对齐 115 三路归档——分段/变体 STRM 归档到同番号目录并强制命名，
        // 杜绝散落库根目录导致无法进入「番号目录」结构（批次 6a）。
        if (
            segmentInfo != null &&
            (segmentInfo.partLabel != null || variant != MovieVariant.Standard)
        ) {
            findExistingMovieDirectoryFast(targetRoot, segmentInfo.number)?.let { movieDirectory ->
                val sourceSuffix = playbackSourceSuffix(segmentInfo.partLabel, variant)
                val variantFile = writeStrmFile(
                    root = movieDirectory,
                    videoName = item.name,
                    fileId = fileId,
                    forcedBaseName = "${movieDirectory.name}$sourceSuffix"
                )
                recordRepository.upsertGenerated(
                    pickcode = fileId, // 复用记录表，以 fileId 作为主键
                    source = "GUANGYA", // ★ 第九轮：来源标识
                    fileName = variantFile.name,
                    strmUri = variantFile.uri,
                    libraryRootUri = settingsRepository.getLibraryRootUri(),
                    videoSizeBytes = item.size
                )
                return@withContext GeneratedStrmFile(
                    fileName = variantFile.name,
                    pickcode = fileId,
                    strmUri = variantFile.uri,
                    created = true,
                    movieNumberHint = segmentInfo.number,
                    partLabel = segmentInfo.partLabel
                )
            }
        }
        if (
            segmentInfo != null &&
            segmentInfo.partLabel == null &&
            variant == MovieVariant.Standard
        ) {
            findExistingMovieDirectoryFast(targetRoot, segmentInfo.number)?.let { movieDirectory ->
                val standardFile = writeStrmFile(
                    root = movieDirectory,
                    videoName = item.name,
                    fileId = fileId,
                    forcedBaseName = movieDirectory.name
                )
                recordRepository.upsertGenerated(
                    pickcode = fileId, // 复用记录表，以 fileId 作为主键
                    source = "GUANGYA", // ★ 第九轮：来源标识
                    fileName = standardFile.name,
                    strmUri = standardFile.uri,
                    libraryRootUri = settingsRepository.getLibraryRootUri(),
                    videoSizeBytes = item.size
                )
                return@withContext GeneratedStrmFile(
                    fileName = standardFile.name,
                    pickcode = fileId,
                    strmUri = standardFile.uri,
                    created = true,
                    movieNumberHint = segmentInfo.number
                )
            }
        }

        // 兜底：库根目录直写（与 115 第三路同构）
        val file = writeStrmFile(targetRoot, item.name, fileId)
        recordRepository.upsertGenerated(
            pickcode = fileId, // 复用记录表，以 fileId 作为主键
            source = "GUANGYA", // ★ 第九轮：来源标识
            fileName = file.name,
            strmUri = file.uri,
            libraryRootUri = settingsRepository.getLibraryRootUri(),
            videoSizeBytes = item.size
        )
        GeneratedStrmFile(
            fileName = file.name,
            pickcode = fileId,
            strmUri = file.uri,
            created = true,
            movieNumberHint = extractMovieNumberInfo(file.name)?.number,
            partLabel = extractMovieNumberInfo(file.name)?.partLabel
        )
    }

    private fun requireWritableStrmRoot(): DocumentFile {
        val treeUri = settingsRepository.getStrmTreeUri()
            ?: error("请先到设置页选择影片库目录")
        val targetRoot = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: error("影片库目录不可用")
        if (!targetRoot.canWrite()) error("影片库目录没有写入权限")
        return targetRoot
    }

    private fun writeStrmFile(
        root: DocumentFile,
        videoName: String,
        fileId: String,
        forcedBaseName: String? = null
    ): GuangyaWrittenStrm {
        val baseName = (forcedBaseName ?: videoName.substringBeforeLast('.', videoName)).sanitizeFileName()
        val fileName = uniqueStrmName(root, baseName, fileId)
        val file = root.createFile("application/octet-stream", fileName)
            ?: error("无法创建 STRM 文件：$fileName")
        val encodedName = Uri.encode(videoName)
        val content = "${settingsRepository.getStrmBaseUrl()}/guangya_res/$fileId/$encodedName"
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
        } ?: error("无法写入 STRM 文件：$fileName")
        return GuangyaWrittenStrm(name = fileName, uri = file.uri.toString())
    }

    private fun uniqueStrmName(root: DocumentFile, baseName: String, fileId: String): String {
        val first = "$baseName.strm"
        if (root.findFile(first) == null) return first
        return "${baseName}_$fileId.strm"
    }

    // ---- 以下目录定位辅助与 Cloud115StrmRepository 同构（private 无法直接复用，平移副本） ----

    private suspend fun findExistingMovieDirectoryFast(root: DocumentFile, number: String): DocumentFile? {
        recordRepository.getByMovieNumber(number)
            .asSequence()
            .mapNotNull { record -> findParentDirectory(root, record.strmUri) }
            .firstOrNull()
            ?.let { return it }
        return null
    }

    private fun findParentDirectory(root: DocumentFile, fileUriString: String): DocumentFile? {
        val treeUri = settingsRepository.getStrmTreeUri() ?: return null
        val rootDocId = Uri.parse(treeUri).treeDocumentId() ?: return null
        val fileDocId = Uri.parse(fileUriString).documentId() ?: return null
        val parentDocId = fileDocId.substringBeforeLast('/', missingDelimiterValue = "")
        if (parentDocId.isBlank() || parentDocId == rootDocId) return null
        if (!parentDocId.startsWith("$rootDocId/")) return null
        val relativePath = parentDocId.removePrefix("$rootDocId/")
        return relativePath.split('/')
            .filter { it.isNotBlank() }
            .fold(root as DocumentFile?) { current, segment ->
                current?.findFile(segment)?.takeIf { it.isDirectory }
            }
            ?.takeIf { it.findFileByUri(fileUriString) != null }
    }

    private fun DocumentFile.findFileByUri(uriString: String): DocumentFile? {
        return listFiles().firstOrNull { it.isFile && it.uri.toString() == uriString }
    }

    private fun Uri.treeDocumentId(): String? {
        val index = pathSegments.indexOf("tree")
        return index.takeIf { it >= 0 && it + 1 < pathSegments.size }
            ?.let { Uri.decode(pathSegments[it + 1]) }
    }

    private fun Uri.documentId(): String? {
        val index = pathSegments.indexOf("document")
        return index.takeIf { it >= 0 && it + 1 < pathSegments.size }
            ?.let { Uri.decode(pathSegments[it + 1]) }
    }

    private fun String.sanitizeFileName(): String =
        replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "video" }

    companion object {
        /** ★ 统一取自 [VideoFormats]，避免各处白名单漂移。 */
        val VIDEO_EXTENSIONS: List<String> = VideoFormats.dotExtensions()
    }
}

/** 本文件内部使用的临时结构（与 Cloud115StrmRepository 的 WrittenStrmFile 隔离命名，避免重声明）。 */
private data class GuangyaWrittenStrm(
    val name: String,
    val uri: String
)