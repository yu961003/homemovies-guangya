package com.example.localmovielibrarz.subtitle

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.example.localmovielibrarz.playback.SubtitleSidecarLocator
import com.example.localmovielibrarz.data.repository.AppSettingsRepository
import com.example.localmovielibrarz.diagnostics.RuntimeErrorLog
import com.example.localmovielibrarz.util.normalizeMovieNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class LocalSubtitleFile(
    val name: String,
    val uri: Uri
)

data class SubtitleSearchResult(
    val cid: String,
    val ext: String,
    val name: String,
    val durationMs: Long?,
    val language: String,
    val extraName: String,
    val timestamp: Long,
    val signature: String,
    val provider: SubtitleSearchProvider
) {
    val displayName: String = buildString {
        append(provider.label)
        if (language.isNotBlank()) append(" · ").append(language)
        if (extraName.isNotBlank()) append(" · ").append(extraName)
        durationMs?.takeIf { it > 0L }?.let { append(" · ").append(formatDuration(it)) }
    }
}

class LocalSubtitleStore(
    context: Context,
    private val settingsRepository: AppSettingsRepository
) {
    private val appContext = context.applicationContext
    private val errorLog = RuntimeErrorLog(appContext)

    suspend fun listLocalSubtitles(
        videoUri: Uri,
        fileName: String,
        storageSourceUri: Uri? = null
    ): List<LocalSubtitleFile> =
        withContext(Dispatchers.IO) {
            val baseNames = subtitleBaseNames(videoUri, fileName)
            val candidates = storageCandidates(videoUri, storageSourceUri)
            val documentParents = candidates
                .mapNotNull { resolveDocumentParent(it) }
                .distinctBy { it.uri.toString() }
            val documentFiles = documentParents
                .flatMap { parent ->
                    parent.listFiles()
                        .filter { it.isFile && it.name.orEmpty().isSubtitleNameFor(baseNames) }
                        .map { LocalSubtitleFile(it.name.orEmpty(), it.uri) }
                }
            val localFiles = candidates
                .mapNotNull { resolveLocalParent(it) }
                .distinctBy { it.absolutePath }
                .flatMap { parent ->
                    parent.listFiles()
                        ?.filter { it.isFile && it.name.isSubtitleNameFor(baseNames) }
                        ?.map { LocalSubtitleFile(it.name, Uri.fromFile(it)) }
                        .orEmpty()
                }
            orderSidecars(videoUri, fileName, (documentFiles + localFiles).distinctBy { it.uri.toString() })
        }

    /**
     * 按侧挂字幕的「语言优先级」排序，而不是单纯按文件名字典序。
     *
     * 为什么：同一部影片旁边经常躺着 `ABC-123.srt` / `ABC-123.chs.srt` / `ABC-123.en.srt`，
     * 字典序会把英文排在中文字幕前面，播放器默认加载的就是英文——用户每次都得手动切。
     * `SubtitleSidecarLocator` 已经实现了「已知语言优先、forced 垫底、同级按名字」的排序，
     * 这里复用它，避免在 store 里再写一套会与播放器模块漂移的规则。
     *
     * 认不出语言的候选（用户随手命名的 `ABC-123.大结局.srt`）不会被丢弃，只是排在后面——
     * 排序可以猜，取舍不能替用户做。
     */
    private fun orderSidecars(
        videoUri: Uri,
        fileName: String,
        files: List<LocalSubtitleFile>
    ): List<LocalSubtitleFile> {
        if (files.size <= 1) return files
        val videoName = fileName.ifBlank {
            DocumentFile.fromSingleUri(appContext, videoUri)?.name.orEmpty()
        }
        if (videoName.isBlank()) return files.sortedBy { it.name.lowercase(Locale.ROOT) }
        val byName = files.associateBy { it.name }
        val ordered = SubtitleSidecarLocator.find(videoName, files.map { it.name })
            .mapNotNull { byName[it.candidate] }
            .distinctBy { it.uri.toString() }
        if (ordered.isEmpty()) return files.sortedBy { it.name.lowercase(Locale.ROOT) }
        // find() 只认得「基名.语言.srt」这一种写法；`ABC-123-chs.srt`（连字符分隔）不在其中，
        // 这些漏网的候选按原顺序跟在后面，不能被排序顺手丢掉。
        val orderedUris = ordered.map { it.uri.toString() }.toSet()
        val rest = files.filterNot { it.uri.toString() in orderedUris }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        return ordered + rest
    }

    suspend fun deleteLocalSubtitle(subtitle: LocalSubtitleFile): Boolean = withContext(Dispatchers.IO) {
        when (subtitle.uri.scheme) {
            "file", null -> {
                val path = subtitle.uri.path ?: return@withContext false
                File(path).takeIf { it.isFile }?.delete() == true
            }
            "content" -> {
                DocumentFile.fromSingleUri(appContext, subtitle.uri)?.delete() == true ||
                    runCatching {
                        appContext.contentResolver.delete(subtitle.uri, null, null) > 0
                    }.getOrDefault(false)
            }
            else -> false
        }
    }

    suspend fun saveSubtitleBytes(
        videoUri: Uri,
        fileName: String,
        result: SubtitleSearchResult,
        bytes: ByteArray,
        storageSourceUri: Uri? = null
    ): LocalSubtitleFile = withContext(Dispatchers.IO) {
        val candidates = storageCandidates(videoUri, storageSourceUri)
        val directDocumentParents = candidates
            .mapNotNull { resolveDocumentParent(it) }
            .distinctBy { it.uri.toString() }
        val documentParents = directDocumentParents
        val writableDocumentParents = documentParents.filter { isWritableDocumentParent(it) }
        val localParent = candidates.firstNotNullOfOrNull { resolveLocalParent(it) }
        errorLog.append(
            event = "subtitle.file.save.begin",
            details = mapOf(
                "videoUri" to videoUri.toString(),
                "fileName" to fileName,
                "storageSourceUri" to storageSourceUri?.toString(),
                "candidateUris" to candidates.joinToString(" | ") { it.toString() },
                "documentParents" to documentParents.joinToString(" | ") { "${it.name}:${it.uri}" },
                "writableDocumentParents" to writableDocumentParents.joinToString(" | ") { "${it.name}:${it.uri}" },
                "hasLocalParent" to (localParent != null).toString(),
                "resultName" to result.name,
                "resultExt" to result.ext,
                "provider" to result.provider.id
            )
        )
        if (writableDocumentParents.isEmpty() && localParent == null) {
            val message = if (documentParents.isNotEmpty()) {
                "无法在视频目录创建字幕文件，请在目录设置中重新选择影片库目录并授予写入权限"
            } else {
                "无法定位视频所在目录"
            }
            errorLog.append(
                event = if (documentParents.isNotEmpty()) {
                    "subtitle.file.save.noWritableParentBeforeRequest"
                } else {
                    "subtitle.file.save.noParentBeforeRequest"
                },
                details = mapOf(
                    "videoUri" to videoUri.toString(),
                    "fileName" to fileName,
                    "storageSourceUri" to storageSourceUri?.toString(),
                    "candidateUris" to candidates.joinToString(" | ") { it.toString() },
                    "documentParentCount" to documentParents.size.toString(),
                    "documentParents" to documentParents.joinToString(" | ") { "${it.name}:${it.uri}" }
                )
            )
            error(message)
        }
        for (documentParent in writableDocumentParents) {
            writeSubtitleToDocumentParent(documentParent, videoUri, fileName, result, bytes)
                ?.let { return@withContext it }
        }
        val targetName = uniqueSubtitleName(videoUri, fileName, result, storageSourceUri)
        val targetLocalParent = localParent
            ?: run {
                val message = if (documentParents.isNotEmpty()) {
                    "无法在视频目录创建字幕文件，请在目录设置中重新选择影片库目录并授予写入权限"
                } else {
                    "无法定位视频所在目录"
                }
                errorLog.append(
                    event = if (documentParents.isNotEmpty()) {
                        "subtitle.file.save.noWritableParent"
                    } else {
                        "subtitle.file.save.noLocalParent"
                    },
                    details = mapOf(
                        "videoUri" to videoUri.toString(),
                        "fileName" to fileName,
                        "storageSourceUri" to storageSourceUri?.toString(),
                        "candidateUris" to candidates.joinToString(" | ") { it.toString() },
                        "documentParentCount" to documentParents.size.toString(),
                        "documentParents" to documentParents.joinToString(" | ") { "${it.name}:${it.uri}" }
                    )
                )
                error(message)
            }
        if (!targetLocalParent.exists()) targetLocalParent.mkdirs()
        val file = targetLocalParent.resolve(targetName)
        runCatching { file.writeBytes(bytes) }.getOrElse { error ->
            errorLog.append(
                event = "subtitle.file.save.localWriteFailed",
                details = mapOf(
                    "targetFile" to file.absolutePath,
                    "targetName" to targetName
                ),
                error = error
            )
            throw error
        }
        LocalSubtitleFile(file.name, Uri.fromFile(file))
    }

    private fun writeSubtitleToDocumentParent(
        documentParent: DocumentFile,
        videoUri: Uri,
        fileName: String,
        result: SubtitleSearchResult,
        bytes: ByteArray
    ): LocalSubtitleFile? {
        val preferredName = uniqueSubtitleName(videoUri, fileName, result, documentParent)
        val fallbackBase = normalizeMovieNumber(fileName)
            ?: normalizeMovieNumber(videoUri.toString())
            ?: result.name.substringBeforeLast('.', result.name).sanitizeFileName()
        val ext = result.ext.takeIf { it.isNotBlank() } ?: "srt"
        val nameCandidates = listOf(
            preferredName,
            "${fallbackBase.sanitizeFileName()}.$ext",
            "${fallbackBase.sanitizeFileName()}.${result.provider.fileSuffix}.$ext",
            "subtitle.$ext"
        ).distinct()
        for (targetName in nameCandidates) {
            val file = createSubtitleDocument(documentParent, ext, targetName) ?: continue
            val wrote = runCatching {
                appContext.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(bytes) } != null
            }.onFailure { error ->
                errorLog.append(
                    event = "subtitle.file.save.documentWriteFailed",
                    details = mapOf(
                        "parentName" to documentParent.name,
                        "parentUri" to documentParent.uri.toString(),
                        "targetName" to targetName,
                        "createdName" to file.name,
                        "createdUri" to file.uri.toString()
                    ),
                    error = error
                )
            }.getOrDefault(false)
            if (wrote) {
                errorLog.append(
                    event = "subtitle.file.save.documentWriteSuccess",
                    details = mapOf(
                        "parentName" to documentParent.name,
                        "parentUri" to documentParent.uri.toString(),
                        "targetName" to targetName,
                        "createdName" to file.name,
                        "createdUri" to file.uri.toString()
                    )
                )
                return LocalSubtitleFile(file.name.orEmpty().ifBlank { targetName }, file.uri)
            }
            runCatching { file.delete() }
        }
        errorLog.append(
            event = "subtitle.file.save.documentCreateFailed",
            details = mapOf(
                "parentName" to documentParent.name,
                "parentUri" to documentParent.uri.toString(),
                "fileName" to fileName,
                "nameCandidates" to nameCandidates.joinToString(" | "),
                "ext" to ext
            )
        )
        return null
    }

    private fun uniqueSubtitleName(
        videoUri: Uri,
        fileName: String,
        result: SubtitleSearchResult,
        storageSourceUri: Uri?
    ): String {
        val base = subtitleBaseNames(videoUri, fileName).firstOrNull().orEmpty().ifBlank {
            result.name.substringBeforeLast('.', result.name)
        }
        val safeBase = base.sanitizeFileName()
        val ext = result.ext.takeIf { it.isNotBlank() } ?: "srt"
        val preferred = "$safeBase.${result.provider.fileSuffix}.$ext"
        val used = listLocalSubtitleNames(videoUri, storageSourceUri).map { it.lowercase(Locale.ROOT) }.toSet()
        if (preferred.lowercase(Locale.ROOT) !in used) return preferred
        var index = 2
        while (true) {
            val name = "$safeBase.${result.provider.fileSuffix}-$index.$ext"
            if (name.lowercase(Locale.ROOT) !in used) return name
            index++
        }
    }

    private fun uniqueSubtitleName(
        videoUri: Uri,
        fileName: String,
        result: SubtitleSearchResult,
        documentParent: DocumentFile
    ): String {
        val base = subtitleBaseNames(videoUri, fileName).firstOrNull().orEmpty().ifBlank {
            result.name.substringBeforeLast('.', result.name)
        }
        val safeBase = base.sanitizeFileName()
        val ext = result.ext.takeIf { it.isNotBlank() } ?: "srt"
        val used = documentParent.listFiles()
            .mapNotNull { it.name }
            .map { it.lowercase(Locale.ROOT) }
            .toSet()
        val preferred = "$safeBase.${result.provider.fileSuffix}.$ext"
        if (preferred.lowercase(Locale.ROOT) !in used) return preferred
        var index = 2
        while (true) {
            val name = "$safeBase.${result.provider.fileSuffix}-$index.$ext"
            if (name.lowercase(Locale.ROOT) !in used) return name
            index++
        }
    }

    private fun createSubtitleDocument(parent: DocumentFile, ext: String, targetName: String): DocumentFile? {
        val mime = mimeTypeFor(ext)
        val nameWithoutExt = targetName.substringBeforeLast('.', targetName)
        return runCreateSubtitleDocument(parent, mime, targetName)
            ?: runCreateSubtitleDocument(parent, "text/plain", targetName)
            ?: runCreateSubtitleDocument(parent, "application/octet-stream", targetName)
            ?: runCreateSubtitleDocument(parent, mime, nameWithoutExt)
            ?: runCreateSubtitleDocument(parent, "text/plain", nameWithoutExt)
            ?: createSubtitleDocumentDirect(parent, mime, targetName)
            ?: createSubtitleDocumentDirect(parent, "text/plain", targetName)
            ?: createSubtitleDocumentDirect(parent, "application/octet-stream", targetName)
    }

    private fun runCreateSubtitleDocument(parent: DocumentFile, mimeType: String, targetName: String): DocumentFile? =
        runCatching {
            parent.createFile(mimeType, targetName)
        }.onFailure { error ->
            errorLog.append(
                event = "subtitle.file.save.documentFileCreateException",
                details = mapOf(
                    "parentName" to parent.name,
                    "parentUri" to parent.uri.toString(),
                    "mimeType" to mimeType,
                    "targetName" to targetName
                ),
                error = error
            )
        }.getOrNull()

    private fun createSubtitleDocumentDirect(parent: DocumentFile, mimeType: String, targetName: String): DocumentFile? {
        val uri = runCatching {
            DocumentsContract.createDocument(appContext.contentResolver, parent.uri, mimeType, targetName)
        }.onFailure { error ->
            errorLog.append(
                event = "subtitle.file.save.documentsContractCreateException",
                details = mapOf(
                    "parentName" to parent.name,
                    "parentUri" to parent.uri.toString(),
                    "mimeType" to mimeType,
                    "targetName" to targetName
                ),
                error = error
            )
        }.getOrNull() ?: return null
        return DocumentFile.fromSingleUri(appContext, uri)
    }

    private fun listLocalSubtitleNames(videoUri: Uri, storageSourceUri: Uri?): List<String> {
        val candidates = storageCandidates(videoUri, storageSourceUri)
        val documentNames = candidates
            .mapNotNull { resolveDocumentParent(it) }
            .distinctBy { it.uri.toString() }
            .flatMap { it.listFiles().mapNotNull { file -> file.name } }
        val localNames = candidates
            .mapNotNull { resolveLocalParent(it) }
            .distinctBy { it.absolutePath }
            .flatMap { it.listFiles()?.map { file -> file.name }.orEmpty() }
        return documentNames + localNames
    }

    private fun storageCandidates(videoUri: Uri, storageSourceUri: Uri?): List<Uri> =
        listOfNotNull(storageSourceUri, videoUri).distinctBy { it.toString() }

    private fun subtitleBaseNames(videoUri: Uri, fileName: String): List<String> {
        val rawName = fileName.ifBlank {
            DocumentFile.fromSingleUri(appContext, videoUri)?.name.orEmpty()
        }.substringBeforeLast('.', fileName)
        return listOf(rawName, rawName.substringBeforeLast('.', rawName))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun resolveDocumentParent(videoUri: Uri): DocumentFile? {
        if (videoUri.scheme != "content") return null
        val documentId = runCatching { DocumentsContract.getDocumentId(videoUri) }.getOrNull() ?: return null
        val roots = listOf(videoUri.toString()) + settingsRepository.getKnownLibraryRootUris() + settingsRepository.getKnownStrmTreeUris()
        for (root in roots) {
            val rootUri = runCatching { Uri.parse(root) }.getOrNull() ?: continue
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(rootUri) }.getOrNull() ?: continue
            if (documentId != rootId && !documentId.startsWith("$rootId/")) continue
            val treeUri = DocumentsContract.buildTreeDocumentUri(rootUri.authority, rootId)
            var current = DocumentFile.fromTreeUri(appContext, treeUri) ?: continue
            val relative = documentId.removePrefix(rootId).trimStart('/')
            val parentSegments = relative.split('/').filter { it.isNotBlank() }.dropLast(1)
            var resolved = true
            for (segment in parentSegments) {
                val next = current.findFile(segment)
                if (next == null) {
                    resolved = false
                    break
                }
                current = next
            }
            if (resolved) return current
        }
        return null
    }

    private fun isWritableDocumentParent(parent: DocumentFile): Boolean {
        if (!parent.canWrite()) return false
        return hasPersistedWritePermission(parent.uri)
    }

    private fun hasPersistedWritePermission(uri: Uri): Boolean {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        val permissions = appContext.contentResolver.persistedUriPermissions
            .filter { it.isWritePermission && it.uri.authority == uri.authority }
        if (permissions.isEmpty()) return false
        return permissions.any { permission ->
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull()
            when {
                rootId != null && documentId != null -> documentId == rootId || documentId.startsWith("$rootId/")
                else -> permission.uri == uri
            }
        }
    }

    private fun resolveLocalParent(videoUri: Uri): File? {
        val path = when (videoUri.scheme) {
            "file" -> videoUri.path
            null -> videoUri.path
            else -> null
        } ?: return null
        return File(path).parentFile
    }

    private fun String.isSubtitleNameFor(baseNames: List<String>): Boolean {
        val lower = lowercase(Locale.ROOT)
        if (substringAfterLast('.', "").lowercase(Locale.ROOT) !in SUBTITLE_EXTENSIONS) return false
        val nameBase = substringBeforeLast('.', this).lowercase(Locale.ROOT)
        return baseNames.any { base ->
            val normalized = base.lowercase(Locale.ROOT)
            nameBase == normalized || nameBase.startsWith("$normalized.") || nameBase.startsWith("$normalized-")
        }
    }

    private fun String.sanitizeFileName(): String =
        replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifBlank { "subtitle" }

    private fun mimeTypeFor(ext: String): String =
        when (ext.lowercase(Locale.ROOT)) {
            "vtt" -> "text/vtt"
            "ass", "ssa" -> "text/x-ssa"
            else -> "application/x-subrip"
        }

    companion object {
        private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")
    }
}

fun normalizeDefaultSubtitleNumber(number: String): String {
    val value = number.trim().uppercase(Locale.ROOT)
    val match = Regex("""^([A-Z]+)-0*(\d+)$""").matchEntire(value) ?: return value
    val prefix = match.groupValues[1]
    val digits = match.groupValues[2].toIntOrNull()?.toString() ?: match.groupValues[2]
    return "$prefix-$digits"
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
