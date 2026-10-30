package com.example.localmovielibrarz.subtitle

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.example.localmovielibrarz.data.local.LocalSubtitleLibraryEntity
import com.example.localmovielibrarz.util.normalizeMovieNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地字幕库扫描器：把一个 SAF 目录树递归扫成 [LocalSubtitleLibraryEntity] 列表。
 *
 * 为什么单独建索引而不是播放时现查：
 * [LocalSubtitleStore.listLocalSubtitles] 只 list 视频「所在的那一个目录」，
 * 认的是 `ABC-123.chs.srt` 这种侧挂写法；但真实库里常见的是
 * `ABC-123/字幕/ABC-123.chs.srt`，或者整个库的字幕集中放在 `subs/` 下，
 * 这两种都落在「同目录」假设之外。建好索引后，「番号 -> 字幕」就是一次 SQL 查询。
 *
 * 扫描只读 SAF 元数据（documentId / 名字 / mime / 大小 / 修改时间），不 openInputStream：
 * 字幕内容由播放器真正挂载时才读，扫描阶段读内容会把几千个文件的 binder 与磁盘 IO
 * 全压进一次进度里，对建索引毫无必要。
 *
 * 并发模型与 `scanner.LibraryScanner` 一致：目录级并发 + [Semaphore] 上界，
 * 且**只有「列目录」这一步持有 permit**，子目录递归不持有——否则并发跑满时
 * 父目录等子目录、子目录等父目录释放 permit，会死锁。
 */
object LocalSubtitleLibraryScanner {
    private const val TAG = "LocalSubtitleLibraryScanner"

    /** 与 [LocalSubtitleStore] 认的侧挂字幕扩展名同集（小写、不含点）。 */
    private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")

    /** 与 LibraryScanner 同值：4 路目录并发足以填满 SAF 的 binder 串行段，又不会把内存打爆。 */
    private const val SCAN_CONCURRENCY = 4

    /**
     * 递归深度上限：从库根算起最多下钻 12 层目录。
     *
     * 为什么需要：部分第三方 provider（把网盘 / NAS 挂成 SAF 的那些）在目录出错时
     * 会把父目录当作子目录返回。没有上限的递归会一直转下去，内存与用户等待都无界；
     * 12 层远超正常影片库（通常不超过 4 层），撞到这个数就说明目录结构异常。
     */
    private const val MAX_DEPTH = 12

    /** 进度回调的最小间隔；见 [ProgressThrottle]。 */
    private const val PROGRESS_INTERVAL_MS = 200L

    /** 进度回调的最小步长：慢速 provider 下靠它兜底，避免界面长时间静默。 */
    private const val PROGRESS_STEP = 50

    /** 判断一项是什么所需的最小列集；多查一列就是多一次跨进程 binder 往返。 */
    private val DOCUMENT_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_SIZE
    )

    /**
     * 递归扫描 [rootUri] 指向的目录树，返回识别到的字幕（按相对路径排序、按 uri 去重）。
     *
     * @param onProgress 进度回调：`scanned` = 已判定的目录项数，`found` = 已识别的字幕数。
     *                   回调已节流（约 200ms 或 50 项一次），但**可能来自任意 IO 线程**，
     *                   要更新 UI 的调用方需自行切回主线程。
     * @return 已扫到的条目。中途权限被撤销时返回的是部分结果而不是空表——部分索引
     *         永远好过没有索引，要不要留着由调用方决定。
     */
    suspend fun scan(
        context: Context,
        rootUri: Uri,
        onProgress: (scanned: Int, found: Int) -> Unit = { _, _ -> }
    ): List<LocalSubtitleLibraryEntity> = withContext(Dispatchers.IO) {
        val rootDocumentId = try {
            DocumentsContract.getTreeDocumentId(rootUri)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "不是可用的 tree uri，跳过扫描：$rootUri", e)
            null
        }
        if (rootDocumentId.isNullOrBlank()) {
            onProgress(0, 0)
            return@withContext emptyList<LocalSubtitleLibraryEntity>()
        }
        val session = ScanSession(
            contentResolver = context.applicationContext.contentResolver,
            treeUri = rootUri,
            rootUriString = rootUri.toString(),
            results = ArrayList(),
            scanned = AtomicInteger(0),
            found = AtomicInteger(0),
            throttle = ProgressThrottle(onProgress)
        )
        try {
            scanDirectory(session, rootDocumentId, parentPath = "", depth = MAX_DEPTH)
        } catch (e: SecurityException) {
            // 用户在系统设置里收回了该目录的授权。已扫到的条目仍然有效：
            // 返回部分结果，让上层照常写索引，而不是把整次扫描判为失败。
            session.permissionLost.set(true)
            Log.w(TAG, "扫描被权限撤销中断：已识别 ${session.found.get()} 条", e)
        }
        session.throttle.flushFinal(session.scanned.get(), session.found.get())
        session.results
            .distinctBy { it.uri }
            .sortedBy { it.relativePath.lowercase(Locale.ROOT) }
    }

    /**
     * 递归一个目录。
     *
     * @param parentPath 该目录相对库根的路径（根传空串），用于拼 [LocalSubtitleLibraryEntity.relativePath]。
     * @param depth 还能再下钻几层；每次递归减 1，减到 0 就只停在这一层，不再往下走。
     */
    private suspend fun scanDirectory(
        session: ScanSession,
        documentId: String,
        parentPath: String,
        depth: Int
    ) {
        if (session.permissionLost.get()) return
        if (depth <= 0) {
            Log.w(TAG, "目录深度达到 $MAX_DEPTH 层上限，跳过：${parentPath.ifEmpty { "/" }}")
            return
        }
        // permit 只包住列目录这一步：查询本身是阻塞的 binder 调用，真正的并发收益在这里；
        // 把递归也包进来会让父子目录互相等待 permit，直接死锁（LibraryScanner 的注释同此）。
        val children = session.semaphore.withPermit { listChildren(session, documentId) } ?: return

        val localSubtitles = ArrayList<LocalSubtitleLibraryEntity>()
        val subDirectories = ArrayList<ChildEntry>()
        // 单次遍历分类：同一批 children 上同时判目录与扩展名，避免为了分流再走一遍。
        children.forEach { child ->
            session.scanned.incrementAndGet()
            if (child.isDirectory) {
                if (!child.name.startsWith(".")) {
                    // 隐藏目录（.thumbnails / .trashed / 用户自建的 .cache）里不会有用户想看的字幕，
                    // 下钻只会让一次扫描翻倍变慢。但隐藏「文件」照收：有些用户会把字幕命名成
                    // `.ABC-123.srt` 交给播放器自动加载，那类正是要索引的。
                    subDirectories += child
                }
            } else {
                val ext = child.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext in SUBTITLE_EXTENSIONS) {
                    buildEntity(session, child, parentPath, ext)?.let { entity ->
                        localSubtitles += entity
                        session.found.incrementAndGet()
                    }
                }
            }
            session.throttle.onEntry(session.scanned.get(), session.found.get())
        }
        if (localSubtitles.isNotEmpty()) {
            // 多个目录协程共用一个 ArrayList，写入必须串行（LibraryScanner 同款做法）。
            synchronized(session.results) { session.results.addAll(localSubtitles) }
        }
        if (subDirectories.isEmpty()) return

        val nextPath = { child: ChildEntry ->
            if (parentPath.isEmpty()) child.name else "$parentPath/${child.name}"
        }
        coroutineScope {
            subDirectories.forEach { child ->
                launch {
                    scanDirectory(session, child.documentId, nextPath(child), depth - 1)
                }
            }
        }
    }

    /**
     * 列举一个目录的全部子项。
     *
     * 直接读 Cursor 的 5 列而不构造 DocumentFile：`DocumentFile.listFiles()` 每条子项
     * 都要单独走一次 query，一个几千文件的库就是几千次 binder 往返；这里一次 query 拿完。
     *
     * @return null 表示这个目录读不出来（权限撤销 / 厂商 provider 异常），
     *         调用方跳过该目录即可，已扫到的结果不受影响。
     */
    private fun listChildren(session: ScanSession, documentId: String): List<ChildEntry>? {
        val childrenUri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(session.treeUri, documentId)
        } catch (e: SecurityException) {
            session.permissionLost.set(true)
            Log.w(TAG, "构造子目录 uri 时权限已失效：$documentId", e)
            return null
        } catch (e: Exception) {
            Log.w(TAG, "无法为 $documentId 构造子目录 uri", e)
            return null
        }
        val cursor = try {
            session.contentResolver.query(childrenUri, DOCUMENT_PROJECTION, null, null, null)
        } catch (e: SecurityException) {
            session.permissionLost.set(true)
            Log.w(TAG, "查询子项时权限已失效：$documentId", e)
            return null
        } catch (e: Exception) {
            Log.w(TAG, "查询子项失败：$documentId", e)
            return null
        } ?: return null

        return cursor.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val modifiedIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            // id / 名字 / mime 是判定「这一项是什么」的必要列。缺列说明 provider 不兼容，
            // 按空目录处理而不是返回 null：null 会被上层当成权限问题，导致整棵树提前收工。
            if (idIdx < 0 || nameIdx < 0 || mimeIdx < 0) {
                Log.w(TAG, "provider 未提供必要列，跳过目录：$documentId")
                return@use emptyList<ChildEntry>()
            }
            val entries = ArrayList<ChildEntry>()
            while (c.moveToNext()) {
                val childId = c.getString(idIdx) ?: continue
                val name = c.getString(nameIdx) ?: continue
                entries += ChildEntry(
                    documentId = childId,
                    name = name,
                    isDirectory = c.getString(mimeIdx) == DocumentsContract.Document.MIME_TYPE_DIR,
                    lastModified = if (modifiedIdx >= 0) c.getLong(modifiedIdx) else 0L,
                    sizeBytes = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                )
            }
            entries
        }
    }

    /**
     * 把一条 SAF 子项投影成实体行。
     *
     * @return 构造不出 document uri 时返回 null：`uri` 是表里的唯一键，
     *         没有它这一行既查不到也删不掉，留着反而是脏数据。
     */
    private fun buildEntity(
        session: ScanSession,
        child: ChildEntry,
        parentPath: String,
        ext: String
    ): LocalSubtitleLibraryEntity? {
        val documentUri = try {
            DocumentsContract.buildDocumentUriUsingTree(session.treeUri, child.documentId)
        } catch (e: Exception) {
            Log.w(TAG, "无法构造 document uri，跳过：${child.documentId}", e)
            return null
        }
        return LocalSubtitleLibraryEntity(
            uri = documentUri.toString(),
            fileName = child.name,
            relativePath = if (parentPath.isEmpty()) child.name else "$parentPath/${child.name}",
            ext = ext,
            movieNumber = resolveMovieNumber(child.name),
            sizeBytes = child.sizeBytes,
            lastModified = child.lastModified,
            libraryRootUri = session.rootUriString,
            indexedAt = System.currentTimeMillis()
        )
    }

    /**
     * 从字幕文件名推断番号，复用 `util.MovieNumberUtils` 而不是在扫描器里另写一套正则。
     *
     * 为什么挑 normalizeMovieNumber 而不是 movieKeyFromText / extractMovieSourceIdentity：
     * 三者共用同一个 MOVIE_NUMBER_PATTERN，但后两者会再跑 detectMovieVariant 去猜
     * 分集（P2）与版本后缀。字幕文件名里的 `chs` / `en` / `forced` 是语言标记不是分集，
     * 让它们参与 part 推断，只会把同一部影片的不同语言字幕拆进不同的 part 标签里。
     *
     * 更关键的是归一规则必须与媒体库扫描同源：`movies` 表里的番号就是这套规则产出的
     * （`AB-12` 会被补成 `AB-012`），不同源的话两张表永远对不上号。
     *
     * 解析不出来（普通片名、随手命名的 `字幕1.srt`）时返回 null，**不猜**：
     * 猜错的番号会把不相干的字幕挂到别的影片上，比没有番号更糟。
     */
    private fun resolveMovieNumber(fileName: String): String? = normalizeMovieNumber(fileName)

    /**
     * 一次扫描的共享状态。
     *
     * 把计数器、结果表、信号量收进一个类，而不是给 [scanDirectory] 加七八个参数：
     * 递归函数本来就难读，参数越长越容易在下一次改动里传错一个。
     */
    private class ScanSession(
        val contentResolver: ContentResolver,
        val treeUri: Uri,
        val rootUriString: String,
        val results: MutableList<LocalSubtitleLibraryEntity>,
        val scanned: AtomicInteger,
        val found: AtomicInteger,
        val throttle: ProgressThrottle
    ) {
        /**
         * 每次扫描各建一个信号量，而不是挂在 object 上共享：
         * 用户连续重选两个目录时两次扫描会重叠，共享一个信号量只会让两边吞吐量减半；
         * 各自 4 路（同时最多 8 路）仍在 SAF 能承受的范围内。
         */
        val semaphore = Semaphore(SCAN_CONCURRENCY)

        /** 权限撤销后置位，让还在排队的目录协程尽早退出，而不是白跑一遍 IO。 */
        val permissionLost = AtomicBoolean(false)
    }

    /**
     * 进度回调节流器：时间优先、数量兜底。
     *
     * 为什么节流：一个大库遍历下来是几千到上万个目录项，每项回调一次会让消费方
     * 把每一帧都花在重组上（进度文字只有人眼几帧/秒的需求）。「距上次回调 >=200ms
     * 就发」保证界面一直在动，「累计 >=50 项就发」保证慢速 provider 下界面不长时间静默；
     * 两者是「或」的关系，先命中哪个算哪个。
     *
     * 锁只保护计数字段，回调放在锁外调用：消费方哪怕在做跨线程跳转，
     * 也不该阻塞其它目录协程。
     */
    private class ProgressThrottle(
        private val onProgress: (scanned: Int, found: Int) -> Unit
    ) {
        private val lock = Any()
        private var lastEmitAt = System.currentTimeMillis()
        private var pending = 0

        fun onEntry(scanned: Int, found: Int) {
            val now = System.currentTimeMillis()
            val emit = synchronized(lock) {
                pending += 1
                if (now - lastEmitAt >= PROGRESS_INTERVAL_MS || pending >= PROGRESS_STEP) {
                    lastEmitAt = now
                    pending = 0
                    true
                } else {
                    false
                }
            }
            if (emit) onProgress(scanned, found)
        }

        /** 收尾强制发一次：节流会把最后一帧吞掉，进度条会停在 97% 这类数字上。 */
        fun flushFinal(scanned: Int, found: Int) {
            synchronized(lock) {
                lastEmitAt = System.currentTimeMillis()
                pending = 0
            }
            onProgress(scanned, found)
        }
    }
}

/** SAF 子项的精简投影：只留建索引要的 5 个字段（LibraryScanner 的 ChildEntry 同款做法）。 */
private data class ChildEntry(
    val documentId: String,
    val name: String,
    val isDirectory: Boolean,
    val lastModified: Long,
    val sizeBytes: Long
)
