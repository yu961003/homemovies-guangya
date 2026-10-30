package com.example.localmovielibrarz.subtitle

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.example.localmovielibrarz.LocalMovieLibraryApp
import com.example.localmovielibrarz.data.AppContainer
import com.example.localmovielibrarz.data.local.LocalSubtitleLibraryEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地字幕库的**增量**周期重扫。
 *
 * 缺陷背景（B-2）：用户把字幕库目录选好之后，索引只会在设置页手动点「重扫」时更新，
 * 之后新拷进目录的字幕在 App 里搜不到。本 Worker 由
 * [SubtitleLibraryRescanScheduler] 每 12 小时拉起一次，把目录变化补进索引。
 *
 * 为什么强调「增量」：扫描器一次产出的是根目录下的全集，最省事的写法是
 * `dao.replaceRoot(root, scanned)`（先删后插）。但那张表随库规模线性增长，
 * 一次全表重插意味着几千行的 DELETE + INSERT、一次 WAL 膨胀、以及索引页大量重写，
 * 而这些行 99% 与上次扫描完全一样。真正需要写的只有「新拷进来 / 被覆盖 / 被删掉」的
 * 那几行。所以这里先读旧索引、在内存里按 uri 做 diff，只把差异落库。
 *
 * 为什么 diff 的比较键是 uri 而不是路径：`uri` 是 SAF 的 documentId 派生值，是表里
 * 唯一的稳定键（Entity 上就是唯一索引）；路径会随用户移动/改名变化，拿它当键会把
 * 「同一个文件改名」误判成「删一个、加一个」，白写两条。
 *
 * 并发说明：Worker 与设置页的手动重扫可能重叠（用户在扫描途中刚好到点）。
 * 两条路径都以 `uri` 为唯一键写入，REPLACE 语义下最终一致；没有跨模块锁可用，
 * 也不值得为一次后台任务引入全局锁，所以这里不额外加锁。
 */
class SubtitleLibraryRescanWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? LocalMovieLibraryApp
        if (app == null) {
            // 正常安装路径下不可能发生：Worker 的 applicationContext 就是本 App 的 Application。
            // 真出现说明有人把 Worker 拿去别的进程/宿主里用，重试也不会变好，直接判失败。
            Log.w(TAG, "applicationContext 不是 LocalMovieLibraryApp，放弃本次重扫")
            return Result.failure()
        }
        val container = try {
            app.container
        } catch (t: Throwable) {
            // WorkManager 的 startup 初始化早于 Application.onCreate：如果进程是被
            // 后台任务直接拉起、且执行器抢在主线程构造 AppContainer 之前跑到这里，
            // lateinit 还没赋值。这不是配置错误，交回 WorkManager 稍后重试即可。
            Log.w(TAG, "AppContainer 尚未初始化，稍后重试", t)
            return retryOrGiveUp()
        }

        val rootUriString = try {
            container.settingsRepository.getLocalSubtitleLibraryUri()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // 读 SharedPreferences 理论上不会失败，但主线程写 prefs 时的
            // "Can't use most-specific SharedPreferences impl" 这类异常确实存在，
            // 周期任务没有任何理由因为它冒泡到 WorkManager。
            Log.w(TAG, "读取字幕库目录配置失败", t)
            return retryOrGiveUp()
        }
        if (rootUriString.isNullOrBlank()) {
            // 没配字幕库目录的用户：这个任务对他没有意义。返回 success 而不是 failure，
            // 因为这不是「执行失败」——下个周期用户可能就配了，期间不该反复重试。
            Log.i(TAG, "未配置本地字幕库目录，跳过本周期自动重扫")
            return Result.success()
        }

        return try {
            rescanIncrementally(container, rootUriString)
            Result.success()
        } catch (t: Throwable) {
            // 协程取消不是「重扫失败」：继续走 catch 会把 CancellationException 当成普通错误
            // 再返回 retry，反而让已被取消的 Worker 留下一个待重试记录。原样抛回给 WorkManager。
            if (t is CancellationException) throw t
            Log.w(TAG, "本地字幕库增量重扫失败（本次尝试第 ${runAttemptCount + 1} 次）", t)
            retryOrGiveUp()
        }
    }

    /**
     * 一次增量重扫的主体：扫目录 → 读旧索引 → 逐行 diff → 只写差异。
     *
     * 顺序刻意是「先扫描、后读旧索引」：扫描要遍历整棵 SAF 目录树，耗时最长；
     * 先读旧索引的话，扫描期间发生的写入会让 diff 基于过期快照。反过来的窗口只有最后
     * 读索引到写库之间的几十毫秒，且写回仍以 uri REPLACE，风险更低。
     */
    private suspend fun rescanIncrementally(container: AppContainer, rootUriString: String) {
        val rootUri = Uri.parse(rootUriString)
        val dao = container.database.localSubtitleLibraryDao()

        // 扫描器返回的是「当前目录里有什么」的全集；它内部只读 SAF 元数据，不读字幕内容。
        val scanned = LocalSubtitleLibraryScanner.scan(applicationContext, rootUri)
        val existing = dao.listByRoot(rootUriString)
        val now = System.currentTimeMillis()

        val existingByUri = HashMap<String, LocalSubtitleLibraryEntity>(existing.size)
        existing.forEach { existingByUri[it.uri] = it }

        val scannedUris = HashSet<String>(scanned.size)
        val changed = ArrayList<LocalSubtitleLibraryEntity>(minOf(scanned.size, existing.size + 64))
        scanned.forEach { fresh ->
            scannedUris += fresh.uri
            val old = existingByUri[fresh.uri]
            // 旧索引里没有 → 新文件；有但元数据变了 → 覆盖过或改过名；剩下才是「未变化」。
            if (old != null && sameFile(old, fresh)) return@forEach
            // 只给真正要写的行盖新的 indexedAt：未变化行的 indexedAt 保持原值，
            // 否则每次重扫都会让「上次索引于」对全库失效，却什么都没变。
            changed += fresh.copy(indexedAt = now)
        }

        val removedUris: List<String> = when {
            existing.isEmpty() -> emptyList()
            scanned.isEmpty() -> {
                // 扫描结果为空、而索引里明明有行：最可能是 SAF 授权被系统回收 / provider
                // 暂时不可用（扫描器在这种情况返回的是部分结果甚至空表，而不是抛错）。
                // 这时如果照常删，用户整个字幕库索引会被一次异常扫描清空——宁可保留旧索引
                // 等下一个周期，也不做不可逆的删除。
                Log.w(
                    TAG,
                    "扫描结果为空但索引里有 ${existing.size} 行，疑似目录授权失效，本次不删除任何行"
                )
                emptyList()
            }
            else -> existing.asSequence()
                .map { it.uri }
                .filter { it !in scannedUris }
                .toList()
        }

        if (changed.isNotEmpty()) {
            // REPLACE 语义：新文件直接插入，被覆盖/改名的行按 uri 原位替换，不会产生重复行。
            dao.insertAll(changed)
        }
        if (removedUris.isNotEmpty()) {
            deleteByUri(container, rootUriString, removedUris)
        }
        // 记录「上一次成功检查索引的时间」。即使本次零变化也更新：它表示索引已与目录对齐，
        // 而不是「多久之前有文件被改动」。
        container.settingsRepository.saveLocalSubtitleLibraryIndexedAt(now)

        Log.i(
            TAG,
            "本地字幕库增量重扫完成：新增/更新 ${changed.size} 条，删除 ${removedUris.size} 条，" +
                "未变化 ${scanned.size - changed.size} 条（原索引 ${existing.size} 条）"
        )
    }

    /**
     * 判断一条旧索引行与刚扫到的文件是否「没有变化」。
     *
     * 判定字段分两类：
     * - 路径 / 文件名 / 扩展名 / 番号：变了就说明展示与检索口径变了（例如番号规则重解析），
     *   必须重写，否则搜索会命中旧番号。
     * - [LocalSubtitleLibraryEntity.lastModified] + [LocalSubtitleLibraryEntity.sizeBytes]：
     *   文件内容是否变化的代理（不 openInputStream 算哈希：几千个文件全读一遍的成本远超
     *   「偶尔多扫几个没变的文件」，SAF 这两列一次 query 就能拿到）。
     *
     * provider 元数据不可知时（lastModified / sizeBytes 返回 0）不拿该字段下判断：
     * 有些第三方 provider 恒返回 0，若把「同为 0」当成「没变」会漏更新同名覆盖的文件，
     * 若把「为 0」当成「变了」则每个周期都退化成全表重写。取折中：已知字段一致就跳过，
     * 新增文件本来就不在旧索引里（必然写入），所以缺陷 B-2 的核心场景（新拷进来的字幕
     * 搜不到）不受影响；只在「同名覆盖且 provider 不报修改时间」这种少见的组合上可能漏更新
     * 一次，等文件大小变化或用户手动重扫时会补上。
     */
    private fun sameFile(
        old: LocalSubtitleLibraryEntity,
        fresh: LocalSubtitleLibraryEntity
    ): Boolean {
        if (old.relativePath != fresh.relativePath) return false
        if (old.fileName != fresh.fileName) return false
        if (old.ext != fresh.ext) return false
        if (old.movieNumber != fresh.movieNumber) return false
        if (fresh.sizeBytes > 0L && old.sizeBytes != fresh.sizeBytes) return false
        if (fresh.lastModified > 0L && old.lastModified != fresh.lastModified) return false
        return true
    }

    /**
     * 按 uri 删除「目录里已经没有」的行。
     *
     * 为什么不走 DAO 的 `replaceRoot`：那是先把整个根删光再整批插回，等于每次有删除
     * 都全表重写，正是要避免的成本。
     *
     * 为什么直接写 SQL 而不是给 DAO 加一个 `deleteByUris`：`data/local/` 这批文件当前
     * 由另一位 owner 改（本任务约定不越界），加方法会和对方冲突。表名/列名在这里提成
     * 常量，把对 schema 的硬依赖收拢到一处；这张表的 DDL 同时被 Entity 与
     * AppDatabase 的 MIGRATION_20_21 约束，临时改名不会悄悄发生。
     *
     * 为什么分批：SQLite 的绑定参数数量有上限（旧实现 999），一次删几千行会直接抛
     * "too many SQL variables"。分批后每批 [DELETE_CHUNK_SIZE] 条，失败时最多留下
     * 部分已删除的行，下一次周期重扫会重新算出同一批 uri 再删一次，天然幂等。
     *
     * 为什么不用手机上的 Room 事务：`insertAll` 是 suspend DAO、`runInTransaction` 是阻塞式
     * Runnable，两者不能在同一个事务里混用；而这张表没有 Flow/LiveData 观察者，
     * 中间态不会漏给 UI，稳定比「强一致」更重要。
     */
    private suspend fun deleteByUri(
        container: AppContainer,
        rootUriString: String,
        uris: List<String>
    ) = withContext(Dispatchers.IO) {
        val database = container.database.openHelper.writableDatabase
        uris.chunked(DELETE_CHUNK_SIZE).forEach { chunk ->
            val placeholders = chunk.joinToString(separator = ",") { "?" }
            val args = arrayOfNulls<Any?>(chunk.size + 1)
            args[0] = rootUriString
            chunk.forEachIndexed { index, uri -> args[index + 1] = uri }
            database.execSQL(
                "DELETE FROM $TABLE_NAME WHERE $COLUMN_ROOT_URI = ? AND $COLUMN_URI IN ($placeholders)",
                args
            )
        }
    }

    /**
     * 失败后交给 WorkManager 退避重试；连续失败到上限就放弃本周期。
     *
     * 为什么不让它无限 retry：SAF 目录被撤销授权、外置存储被拔掉这类错误会一直失败，
     * 无限退避重试只会让 WorkManager 反复唤醒进程、白耗电量。放弃后该周期任务结束，
     * 下一个 App 进程启动时 [SubtitleLibraryRescanScheduler.schedule] 会以 KEEP 策略
     * 重新登记（已结束的 work 不会被 KEEP 挡住），用户修好目录后自然恢复。
     */
    private fun retryOrGiveUp(): Result {
        val attempt = runAttemptCount + 1
        return if (attempt >= MAX_ATTEMPTS) {
            Log.w(TAG, "本地字幕库重扫连续失败 $MAX_ATTEMPTS 次，放弃本周期")
            Result.failure()
        } else {
            Log.i(TAG, "本地字幕库重扫第 $attempt 次失败，交给 WorkManager 退避重试")
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "SubtitleLibraryRescanWorker"

        /** 单个周期内最多尝试几次（含首次）；见 [retryOrGiveUp]。 */
        const val MAX_ATTEMPTS = 3

        /** 每次 DELETE 的 uri 数量上限，避开 SQLite 的绑定参数上限。 */
        const val DELETE_CHUNK_SIZE = 400

        const val TABLE_NAME = "local_subtitle_library"
        const val COLUMN_ROOT_URI = "libraryRootUri"
        const val COLUMN_URI = "uri"
    }
}
