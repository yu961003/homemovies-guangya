package com.example.localmovielibrarz.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.localmovielibrarz.cloud115.Cloud115ApiClient
import com.example.localmovielibrarz.cloud115.Cloud115CookieProvider
import com.example.localmovielibrarz.cloud115.Cloud115QrLoginClient
import com.example.localmovielibrarz.data.repository.AppSettingsRepository
import com.example.localmovielibrarz.data.repository.AppUpdateRepository
import com.example.localmovielibrarz.data.repository.Cloud115StrmRepository
import com.example.localmovielibrarz.data.local.AppDatabase
import com.example.localmovielibrarz.data.repository.CloudFolderBatchTaskRepository
import com.example.localmovielibrarz.data.repository.CloudFolderBatchTaskRunner
import com.example.localmovielibrarz.data.repository.CloudStrmRecordRepository
import com.example.localmovielibrarz.data.repository.CloudVideoAddProcessor
import com.example.localmovielibrarz.data.repository.CloudVideoTaskRepository
import com.example.localmovielibrarz.data.repository.CloudVideoTaskRunner
import com.example.localmovielibrarz.data.repository.DirectLinkRepository
import com.example.localmovielibrarz.data.repository.MovieRepository
import com.example.localmovielibrarz.data.repository.MovieMetadataLedgerRepository
import com.example.localmovielibrarz.data.repository.MovieMetadataRefreshRunner
import com.example.localmovielibrarz.data.repository.MovieScrapeTaskRunner
import com.example.localmovielibrarz.subtitle.LocalSubtitleLibraryRepository
import com.example.localmovielibrarz.data.repository.SourceHealthRepository
import com.example.localmovielibrarz.data.repository.TaskRunRepository
import com.example.localmovielibrarz.data.repository.Top250RankingRepository
import com.example.localmovielibrarz.scraper.ScrapeSourceHealthTracker
import com.example.localmovielibrarz.diagnostics.RuntimeErrorLog
import com.example.localmovielibrarz.data.repository.PlaybackProgressRepository
import com.example.localmovielibrarz.data.repository.StrmScrapeRepository
import com.example.localmovielibrarz.data.repository.StrmRegenerationTaskRepository
import com.example.localmovielibrarz.data.repository.UsageStatsRepository
import com.example.localmovielibrarz.cloudguangya.GuangyaApiClient
import com.example.localmovielibrarz.cloudguangya.GuangyaBrowserRepository
import com.example.localmovielibrarz.cloudguangya.GuangyaLoginClient
import com.example.localmovielibrarz.cloudguangya.GuangyaOfflineMemory
import com.example.localmovielibrarz.cloudguangya.GuangyaOfflineSubmitter
import com.example.localmovielibrarz.cloudguangya.GuangyaRateLimiter
import com.example.localmovielibrarz.cloudguangya.GuangyaTokenProvider
import com.example.localmovielibrarz.cloudguangya.GuangyaTokenStore
import com.example.localmovielibrarz.data.local.GuangyaDirectLinkDao
import com.example.localmovielibrarz.data.repository.GuangyaDirectLinkResolver
import com.example.localmovielibrarz.data.repository.GuangyaStrmRepository
import com.example.localmovielibrarz.scanner.LibraryScanner
import com.example.localmovielibrarz.scraper.ScrapeLogStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val runtimeErrorLog: RuntimeErrorLog = RuntimeErrorLog(appContext)

    val database: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "local_movies.db"
    ).addMigrations(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_18_19,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_21_22
    ).addCallback(
        object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                applyNonRoomIndexes(db)
            }
        }
    ).build()

    val scanner = LibraryScanner(appContext)
    val scrapeLogStore = ScrapeLogStore(appContext)

    /**
     * 在数据库每次打开时幂等创建 Room @Index 无法表达的索引（COLLATE NOCASE）。
     * Room 的 schema 校验只检查表与列，不校验索引，因此此处额外建索引不会触发
     * "migration didn't properly handle" 错误；IF NOT EXISTS 保证重复执行安全。
     */
    private fun applyNonRoomIndexes(db: SupportSQLiteDatabase) {
        db.execSQL(SQL_STRM_RECORDS_MOVIE_NUMBER_NOCASE_INDEX)
    }
    val settingsRepository = AppSettingsRepository(appContext)
    val usageStatsRepository = UsageStatsRepository(appContext)
    val appUpdateRepository = AppUpdateRepository(appContext, settingsRepository)
    // 2.2 优化：非核心路径（115 网盘 / 光鸭云盘）改为懒加载，
    // 避免 App 启动时急初始化用户可能永远用不到的客户端与 Repository。
    val cloud115Client by lazy { Cloud115ApiClient(Cloud115CookieProvider(appContext)) }
    val cloud115QrLoginClient by lazy { Cloud115QrLoginClient(appContext, settingsRepository) }
    /**
     * 本地字幕库。
     *
     * 与 [cloud115Client] 同样走懒加载：没选过字幕库目录的用户永远用不到它，
     * 而它构造时会摸一遍 prefs。
     */
    val localSubtitleLibraryRepository by lazy {
        LocalSubtitleLibraryRepository(
            context = appContext,
            dao = database.localSubtitleLibraryDao(),
            settingsRepository = settingsRepository
        )
    }
    val cloudStrmRecordRepository = CloudStrmRecordRepository(
        context = appContext,
        dao = database.cloudStrmRecordDao(),
        movieDao = database.movieDao(),
        settingsRepository = settingsRepository
    )
    /**
     * JavDB TOP 榜单（来自 5.0.5）。
     *
     * 懒加载：没开启角标、也没在设置页刷过榜单的用户永远不付出实例化成本。
     * 它自己维护内存里的名次表和一份磁盘缓存，所以整个 App 只需要一个实例。
     */
    val top250RankingRepository by lazy {
        Top250RankingRepository(context = appContext)
    }
    val cloud115StrmRepository by lazy {
        Cloud115StrmRepository(
            context = appContext,
            cloud115Client = cloud115Client,
            settingsRepository = settingsRepository,
            recordRepository = cloudStrmRecordRepository
        )
    }
    // ===== 光鸭云盘（平行供应商，不影响 115） =====
    val guangyaRateLimiter by lazy { GuangyaRateLimiter() }
    val guangyaTokenStore by lazy { GuangyaTokenStore(appContext) }
    val guangyaLoginClient by lazy { GuangyaLoginClient(tokenStore = guangyaTokenStore) }
    val guangyaTokenProvider by lazy { GuangyaTokenProvider(guangyaTokenStore, guangyaLoginClient) }
    val guangyaApiClient by lazy {
        GuangyaApiClient(
            tokenProvider = guangyaTokenProvider,
            rateLimiter = guangyaRateLimiter,
            tokenStore = guangyaTokenStore
        )
    }
    val guangyaBrowserRepository by lazy { GuangyaBrowserRepository(guangyaApiClient) }
    val guangyaOfflineMemory by lazy { GuangyaOfflineMemory(appContext) }
    val guangyaOfflineSubmitter by lazy { GuangyaOfflineSubmitter(guangyaBrowserRepository, guangyaOfflineMemory) }
    val guangyaDirectLinkResolver by lazy {
        GuangyaDirectLinkResolver(
            dao = database.guangyaDirectLinkDao(),
            apiClient = guangyaApiClient
        )
    }
    val guangyaStrmRepository by lazy {
        GuangyaStrmRepository(
            context = appContext,
            guangyaClient = guangyaApiClient,
            settingsRepository = settingsRepository,
            recordRepository = cloudStrmRecordRepository
        )
    }
    /** 元数据归属账本：谁（刮削/手改/NFO）写了哪个字段。 */
    val movieMetadataLedgerRepository = MovieMetadataLedgerRepository(
        provenanceDao = database.movieFieldProvenanceDao(),
        aliasDao = database.entityAliasDao()
    )

    /** 来源健康度的持久层：重启后「哪些站点还在冷却」不再丢失。 */
    val sourceHealthRepository = SourceHealthRepository(database.sourceHealthDao())

    /**
     * 来源冷却的内存快路径。
     *
     * 由 AppContainer 持有而不是让 StrmScrapeRepository 自己 new 一个，是为了让冷启动能把
     * 库里残留的冷却回灌进来（[resumeInterruptedTasksAsync]）——仓库内部的私有实例外部够不着。
     */
    val strmScrapeTracker = ScrapeSourceHealthTracker()

    val strmScrapeRepository = StrmScrapeRepository(
        context = appContext,
        settingsRepository = settingsRepository,
        logStore = scrapeLogStore,
        sourceHealthTracker = strmScrapeTracker,
        sourceHealthRepository = sourceHealthRepository,
        metadataLedger = movieMetadataLedgerRepository
    )
    val directLinkRepository by lazy {
        DirectLinkRepository(
            directLinkDao = database.directLinkDao(),
            cloud115Client = cloud115Client,
            taskRunRepository = taskRunRepository
        )
    }
    val playbackProgressRepository = PlaybackProgressRepository(database.playbackProgressDao())
    val cloudFolderBatchTaskRepository = CloudFolderBatchTaskRepository(database.cloudFolderBatchTaskDao())
    val cloudVideoTaskRepository = CloudVideoTaskRepository(database.cloudVideoTaskDao())

    /**
     * STRM 再生成任务（`strm_regeneration_tasks`）。
     *
     * 与 [cloudVideoTaskRepository] 并列而不是合并：那张表记「这部片入没入过库」，
     * 这张表记「这部片的 .strm 被重新生成过几次、上次为什么失败」。分流点见
     * `CloudVideoAddProcessor.runLockedImport` —— 那里是单视频入库与文件夹批量共用的
     * STRM 写入阶段，再生成语义落在这一步。
     */
    val strmRegenerationTaskRepository = StrmRegenerationTaskRepository(database.strmRegenerationTaskDao())

    /**
     * 任务账本（task_runs）：跨模块的「谁在跑、跑到哪、为什么停」。
     *
     * 与既有的 `cloud_folder_batch_tasks` / `cloud_video_tasks` 不是替代关系：
     * 那两张表各自记业务进度（pickcode 集合、单个视频的处理状态），这张表记的是
     * **任务级**的生命周期与心跳，好处是设置页可以用一条查询同时展示文件夹批处理、
     * 单视频入库、影片刮削、直链刷新这四类互不相干的任务，而不必各写一套统计。
     */
    val taskRunRepository = TaskRunRepository(database.taskRunDao())

    val movieRepository = MovieRepository(
        context = appContext,
        movieDao = database.movieDao(),
        cloudStrmRecordDao = database.cloudStrmRecordDao(),
        scanner = scanner,
        contentResolver = appContext.contentResolver,
        logStore = scrapeLogStore
    )
    private val cloudVideoAddProcessor by lazy {
        CloudVideoAddProcessor(
            strmRepository = cloud115StrmRepository,
            recordRepository = cloudStrmRecordRepository,
            settingsRepository = settingsRepository,
            movieRepository = movieRepository,
            scrapeRepository = strmScrapeRepository,
            taskRunRepository = taskRunRepository,
            strmRegenerationTaskRepository = strmRegenerationTaskRepository
        )
    }
    val cloudVideoTaskRunner by lazy {
        CloudVideoTaskRunner(
            taskRepository = cloudVideoTaskRepository,
            processor = cloudVideoAddProcessor,
            settingsRepository = settingsRepository
        )
    }
    val cloudFolderBatchTaskRunner by lazy {
        CloudFolderBatchTaskRunner(
            taskRepository = cloudFolderBatchTaskRepository,
            strmRepository = cloud115StrmRepository,
            recordRepository = cloudStrmRecordRepository,
            settingsRepository = settingsRepository,
            movieRepository = movieRepository,
            scrapeRepository = strmScrapeRepository,
            videoAddProcessor = cloudVideoAddProcessor,
            cloudVideoTaskRunner = cloudVideoTaskRunner,
            taskRunRepository = taskRunRepository
        )
    }
    val movieScrapeTaskRunner = MovieScrapeTaskRunner(
        settingsRepository = settingsRepository,
        movieRepository = movieRepository,
        scrapeRepository = strmScrapeRepository,
        cloudVideoTaskRepository = cloudVideoTaskRepository,
        taskRunRepository = taskRunRepository
    )

    /**
     * 元数据补全任务（缺 actors/studios/genres/plot 的已刮削影片）。
     *
     * 与 `movieScrapeTaskRunner` 并存而不是合并：前者是"从无到有"（写 NFO、下海报），
     * 本项是"补空位"（只写文本列、不碰文件）。两者的失败后果完全不同，混成一个入口
     * 会让用户无法只跑低风险的那一半。
     */
    val movieMetadataRefreshRunner = MovieMetadataRefreshRunner(
        movieRepository = movieRepository,
        scrapeRepository = strmScrapeRepository,
        settingsRepository = settingsRepository,
        metadataLedger = movieMetadataLedgerRepository,
        taskRunRepository = taskRunRepository
    )

    // ★ 第九轮：播放来源自动回填（v18 迁移后仅执行一次；失败不置位，下次启动重试）
    private val sourceBackfillRunning = AtomicBoolean(false)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 冷启动中断恢复（对应修复方案 P0-1 第 3 点 / F-5 的数据侧）。
     *
     * 进程被系统杀掉时，跑了一半的任务只会留下 `Running` 这种「看起来还在跑」的状态。
     * 之前没人管它们：卡片一直转圈，用户以为在下载，其实早就没了。这里做两件事：
     * 1) 把上次留在库里的来源冷却回灌进内存 tracker —— 否则重启后立刻又去打那个刚 403 的站点；
     * 2) 账本里失联的 Running 归位（交由 `TaskRunPolicy` 判定，长任务心跳超时才算了失联），
     *    顺手清掉过老的历史行，避免账本无限膨胀。
     *
     * **不在这里拉起文件夹批处理 runner**：那条路径由 `CloudFolderBatchTaskRunner.resumeInterruptedIfNeeded()`
     * 负责，它只在「确实存在被中断的 Running」时才开跑；若无条件 start()，用户主动暂停的任务会被
     * 开机偷偷唤醒——这个边界不能破。
     *
     * 全部包在 runCatching 里：启动路径上任何一步失败都不该让 App 起不来，
     * 失败就下次启动再试（与既有的 maybeRunSourceBackfillAsync 同一套取舍）。
     */
    fun resumeInterruptedTasksAsync() {
        appScope.launch {
            runCatching {
                val primed = sourceHealthRepository.primeTracker(strmScrapeTracker)
                val recovered = taskRunRepository.recoverInterrupted()
                runCatching { taskRunRepository.pruneHistory() }
                if (primed > 0 || recovered > 0) {
                    scrapeLogStore.append("启动恢复：回灌来源冷却 $primed 条，重置中断任务 $recovered 条")
                }
            }
        }
    }

    fun maybeRunSourceBackfillAsync() {
        if (settingsRepository.isSourceBackfillDone()) return
        if (!sourceBackfillRunning.compareAndSet(false, true)) return
        appScope.launch {
            try {
                cloudStrmRecordRepository.backfillRecordSources()
                settingsRepository.setSourceBackfillDone()
            } catch (e: Throwable) {
                // 失败不置位：下次启动重试；不抛到启动路径
            } finally {
                sourceBackfillRunning.set(false)
            }
        }
    }

    private companion object {
        /**
         * cloud_strm_records 复合索引（COLLATE NOCASE）。
         * Room 的 @Index 注解不支持 COLLATE，因此在此处经 onOpen 回调幂等创建。
         * 用于加速 DAO 中 `ORDER BY ... COLLATE NOCASE` 查询（getByMovieNumber / getByMovieId / getPlaybackRecords）。
         * 命名带 _nocase 后缀以区别于 Room 生成的 BINARY 索引。
         * ⚠️ 若日后升级 DB schema，请将此处 DDL 一并迁入正式 Migration。
         */
        private const val SQL_STRM_RECORDS_MOVIE_NUMBER_NOCASE_INDEX =
            "CREATE INDEX IF NOT EXISTS index_cloud_strm_records_movieNumber_partLabel_variant_fileName_nocase " +
                "ON cloud_strm_records (" +
                "movieNumber COLLATE NOCASE, " +
                "partLabel COLLATE NOCASE, " +
                "variant COLLATE NOCASE, " +
                "fileName COLLATE NOCASE" +
                ")"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN mpaa TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE movies ADD COLUMN isWatched INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE movies ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `direct_links` (
                        `pickcode` TEXT NOT NULL, 
                        `url` TEXT NOT NULL, 
                        `expiresAt` INTEGER NOT NULL, 
                        `updatedAt` INTEGER NOT NULL, 
                        PRIMARY KEY(`pickcode`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playback_progress` (
                        `mediaKey` TEXT NOT NULL,
                        `positionMs` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`mediaKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN series TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cloud_strm_records` (
                        `pickcode` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `movieNumber` TEXT,
                        `variant` TEXT,
                        `partLabel` TEXT,
                        `strmUri` TEXT NOT NULL,
                        `libraryRootUri` TEXT,
                        `movieId` INTEGER,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`pickcode`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_strm_records_movieNumber` ON `cloud_strm_records` (`movieNumber`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_strm_records_libraryRootUri` ON `cloud_strm_records` (`libraryRootUri`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_strm_records_movieId` ON `cloud_strm_records` (`movieId`)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `domestic_movies` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `folderCid` INTEGER NOT NULL,
                        `folderName` TEXT NOT NULL,
                        `videoName` TEXT NOT NULL,
                        `videoPickcode` TEXT NOT NULL,
                        `imageName` TEXT,
                        `imagePickcode` TEXT,
                        `imageUrl` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_domestic_movies_folderCid` ON `domestic_movies` (`folderCid`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_domestic_movies_videoPickcode` ON `domestic_movies` (`videoPickcode`)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `domestic_video_sources` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `folderCid` INTEGER NOT NULL,
                        `videoName` TEXT NOT NULL,
                        `videoPickcode` TEXT NOT NULL,
                        `sortOrder` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_domestic_video_sources_folderCid` ON `domestic_video_sources` (`folderCid`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_domestic_video_sources_videoPickcode` ON `domestic_video_sources` (`videoPickcode`)")
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `domestic_video_sources` (
                        `folderCid`, `videoName`, `videoPickcode`, `sortOrder`, `createdAt`, `updatedAt`
                    )
                    SELECT `folderCid`, `videoName`, `videoPickcode`, 0, `createdAt`, `updatedAt`
                    FROM `domestic_movies`
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_sortTitle` ON `movies` (`sortTitle`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_scannedAtMillis` ON `movies` (`scannedAtMillis`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_updatedAt` ON `movies` (`updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_year` ON `movies` (`year`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_isFavorite` ON `movies` (`isFavorite`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_isWatched` ON `movies` (`isWatched`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_direct_links_expiresAt` ON `direct_links` (`expiresAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_progress_updatedAt` ON `playback_progress` (`updatedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_strm_records_movieNumber_partLabel_variant_fileName` ON `cloud_strm_records` (`movieNumber`, `partLabel`, `variant`, `fileName`)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN scrapeFailureReason TEXT")
            }
        }

        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE movies ADD COLUMN scrapeTaskStatus TEXT NOT NULL DEFAULT 'None'")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movies_scrapeTaskStatus` ON `movies` (`scrapeTaskStatus`)")
            }
        }

        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cloud_folder_batch_tasks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `folderCid` INTEGER NOT NULL,
                        `folderName` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `currentPath` TEXT,
                        `currentFileName` TEXT,
                        `queuedVideos` INTEGER NOT NULL,
                        `processedVideos` INTEGER NOT NULL,
                        `addedVideos` INTEGER NOT NULL,
                        `scrapeFailedVideos` INTEGER NOT NULL,
                        `skippedVideos` INTEGER NOT NULL,
                        `failedVideos` INTEGER NOT NULL,
                        `failedFolders` INTEGER NOT NULL,
                        `failureMessage` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_folder_batch_tasks_status` ON `cloud_folder_batch_tasks` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_folder_batch_tasks_folderCid` ON `cloud_folder_batch_tasks` (`folderCid`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_folder_batch_tasks_updatedAt` ON `cloud_folder_batch_tasks` (`updatedAt`)")
            }
        }

        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cloud_strm_records ADD COLUMN videoSizeBytes INTEGER")
            }
        }

        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cloud_video_tasks` (
                        `pickcode` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `cid` INTEGER,
                        `fid` INTEGER,
                        `size` INTEGER,
                        `addedAt` INTEGER,
                        `modifiedAt` INTEGER,
                        `forceDistinct` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `failureReason` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`pickcode`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_video_tasks_status` ON `cloud_video_tasks` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_video_tasks_createdAt` ON `cloud_video_tasks` (`createdAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cloud_video_tasks_updatedAt` ON `cloud_video_tasks` (`updatedAt`)")
            }
        }

        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `guangya_direct_links` (
                        `fileId` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `expiresAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`fileId`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_guangya_direct_links_expiresAt` ON `guangya_direct_links` (`expiresAt`)")
            }
        }

        /**
         * 16 -> 17：删除已废弃的「A目录/国产」功能相关表。
         * 旧迁移 7_8 / 8_9 仍保留（旧版本升级会按序执行建表），此处统一清理。
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `domestic_video_sources`")
                db.execSQL("DROP TABLE IF EXISTS `domestic_movies`")
            }
        }

        /**
         * 17 -> 18：cloud_strm_records 增加 source 列（"115" / "GUANGYA"）。
         * 存量回填不在 Migration 内做（9000+ 行文件 IO 会长时间持锁），
         * 由 SourceBackfillTask 在 DB 打开后异步执行（见 CloudStrmRecordRepository.backfillRecordSources）。
         * 顺带重建 nocase 复合索引（新版 schema 已含 source 无关，仅保持 DDL 一致）。
         */
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cloud_strm_records ADD COLUMN source TEXT NOT NULL DEFAULT '115'")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_cloud_strm_records_movieNumber_partLabel_variant_fileName_nocase " +
                        "ON cloud_strm_records (" +
                        "movieNumber COLLATE NOCASE, " +
                        "partLabel COLLATE NOCASE, " +
                        "variant COLLATE NOCASE, " +
                        "fileName COLLATE NOCASE" +
                        ")"
                )
            }
        }

        /**
         * 18 -> 19：网盘文件夹批量任务支持断点续跑（F-1）。
         *
         * 新增两列，均为增量信息，不需要回填：
         * - processedPickcodesCsv：已处理 pickcode 及其结局（`pickcode=OUTCOME;...`）。
         *   存量任务为空串，等价于「没有断点」，首次续跑会全量枚举——与旧行为一致。
         * - lastProgressAt：最近一次进度落库时间，设置页据此提示「长时间无进展」。
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE cloud_folder_batch_tasks " +
                        "ADD COLUMN processedPickcodesCsv TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE cloud_folder_batch_tasks " +
                        "ADD COLUMN lastProgressAt INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * 19 -> 20：四个模块的旁路表（元数据归属 / 实体别名 / 来源健康度 / 任务账本）。
         *
         * 全部是新表，不 ALTER 既有表，因此**不需要任何回填**：新表为空等价于
         * 「还没记录过归属与运行历史」，各模块的读路径都按「无记录 = 按默认规则处理」实现。
         *
         * DDL 必须与 Room 由 Entity 推导出的 schema 逐字一致（列顺序无关，类型/可空性/默认值/
         * 索引名相关），否则 App 打开库时会报 "Migration didn't properly handle"。
         * 无编译器环境下的核对依据：本文件每个 execSQL 都与对应 Entity 属性一一对应。
         */
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // ---- 元数据字段归属 ----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `movie_field_provenance` (" +
                        "`movieId` INTEGER NOT NULL, " +
                        "`fieldKey` TEXT NOT NULL, " +
                        "`authority` TEXT NOT NULL, " +
                        "`sourceLabel` TEXT NOT NULL, " +
                        "`confidence` REAL NOT NULL, " +
                        "`valuePreview` TEXT, " +
                        "`updatedAtMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`movieId`, `fieldKey`)" +
                        ")"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_movie_field_provenance_movieId` " +
                        "ON `movie_field_provenance` (`movieId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_movie_field_provenance_fieldKey` " +
                        "ON `movie_field_provenance` (`fieldKey`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_movie_field_provenance_updatedAtMillis` " +
                        "ON `movie_field_provenance` (`updatedAtMillis`)"
                )

                // ---- 实体别名（演员/厂牌/系列的多种写法归一） ----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `entity_aliases` (" +
                        "`kind` TEXT NOT NULL, " +
                        "`normalizedKey` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`canonicalKey` TEXT NOT NULL, " +
                        "`sourceLabel` TEXT NOT NULL, " +
                        "`updatedAtMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`kind`, `normalizedKey`)" +
                        ")"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_entity_aliases_kind` " +
                        "ON `entity_aliases` (`kind`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_entity_aliases_canonicalKey` " +
                        "ON `entity_aliases` (`canonicalKey`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_entity_aliases_updatedAtMillis` " +
                        "ON `entity_aliases` (`updatedAtMillis`)"
                )

                // ---- 来源健康度（跨进程冷却） ----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `source_health` (" +
                        "`source` TEXT NOT NULL, " +
                        "`consecutiveFailures` INTEGER NOT NULL, " +
                        "`coolingUntilMillis` INTEGER NOT NULL, " +
                        "`lastFailureKind` TEXT, " +
                        "`lastFailureMessage` TEXT, " +
                        "`lastSuccessAtMillis` INTEGER NOT NULL, " +
                        "`updatedAtMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`source`)" +
                        ")"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_source_health_coolingUntilMillis` " +
                        "ON `source_health` (`coolingUntilMillis`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_source_health_updatedAtMillis` " +
                        "ON `source_health` (`updatedAtMillis`)"
                )

                // ---- 任务运行账本 ----
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `task_runs` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, " +
                        "`dedupeKey` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`step` TEXT, " +
                        "`completedSteps` INTEGER NOT NULL, " +
                        "`totalSteps` INTEGER NOT NULL, " +
                        "`attempts` INTEGER NOT NULL, " +
                        "`failureMessage` TEXT, " +
                        "`ownerToken` TEXT, " +
                        "`startedAtMillis` INTEGER NOT NULL, " +
                        "`lastHeartbeatAtMillis` INTEGER NOT NULL, " +
                        "`updatedAtMillis` INTEGER NOT NULL" +
                        ")"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_runs_dedupeKey` " +
                        "ON `task_runs` (`dedupeKey`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_runs_state` " +
                        "ON `task_runs` (`state`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_runs_kind` " +
                        "ON `task_runs` (`kind`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_runs_updatedAtMillis` " +
                        "ON `task_runs` (`updatedAtMillis`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_task_runs_lastHeartbeatAtMillis` " +
                        "ON `task_runs` (`lastHeartbeatAtMillis`)"
                )
            }
        }

        /**
         * 20 -> 21：本地字幕库索引表。
         *
         * DDL 必须与 [com.example.localmovielibrarz.data.local.LocalSubtitleLibraryEntity]
         * 由 Room 推导出的 schema 逐字一致（列序无关；类型、可空性、索引名相关），
         * 否则 Room 会在启动时报 `Migration didn't properly handle`。
         *
         * 索引四件套与 Entity 上的 `@Entity(indices = …)` 一一对应：`uri` 唯一
         * （扫描是整根目录替换，重复 uri 说明扫描器有 bug，交给约束报错比静默去重好），
         * 其余三个服务检索（按番号、按根目录、按文件名）。
         */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `local_subtitle_library` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`uri` TEXT NOT NULL, " +
                        "`fileName` TEXT NOT NULL, " +
                        "`relativePath` TEXT NOT NULL, " +
                        "`ext` TEXT NOT NULL, " +
                        "`movieNumber` TEXT, " +
                        "`sizeBytes` INTEGER NOT NULL, " +
                        "`lastModified` INTEGER NOT NULL, " +
                        "`libraryRootUri` TEXT NOT NULL, " +
                        "`indexedAt` INTEGER NOT NULL" +
                        ")"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_local_subtitle_library_uri` " +
                        "ON `local_subtitle_library` (`uri`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_local_subtitle_library_movieNumber` " +
                        "ON `local_subtitle_library` (`movieNumber`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_local_subtitle_library_libraryRootUri` " +
                        "ON `local_subtitle_library` (`libraryRootUri`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_local_subtitle_library_fileName` " +
                        "ON `local_subtitle_library` (`fileName`)"
                )
            }
        }

        /**
         * 21 -> 22：STRM 再生成任务独立表。
         *
         * 为什么不复用 `cloud_video_tasks`：那张表以 pickcode 为主键、语义是「入没入过库」，
         * Completed 之后不再重跑；再生成是同一部片可以反复重写同一个 .strm 的动作，
         * 需要自己的 attempts / failureReason 才能按「再生成」维度统计与重试。
         *
         * DDL 必须与 [com.example.localmovielibrarz.data.local.StrmRegenerationTaskEntity]
         * 由 Room 推导出的 schema 逐字一致（列序无关；类型、可空性、NOT NULL、索引名、
         * 索引唯一性相关），否则 Room 启动时报 `Migration didn't properly handle`。
         * Kotlin 里的 `= 0L` / `= false` 等默认值**不会**变成 SQLite DEFAULT，因此这里
         * 不写 DEFAULT 子句，只写 NOT NULL。
         */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `strm_regeneration_tasks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`pickcode` TEXT NOT NULL, " +
                        "`fileName` TEXT NOT NULL, " +
                        "`cid` INTEGER, " +
                        "`fid` INTEGER, " +
                        "`size` INTEGER, " +
                        "`strmPath` TEXT, " +
                        "`forceDistinct` INTEGER NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`failureReason` TEXT, " +
                        "`attempts` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL" +
                        ")"
                )
                // 唯一：一个 pickcode 同时只该有一条再生成任务，重复入队走 REPLACE。
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_strm_regeneration_tasks_pickcode` " +
                        "ON `strm_regeneration_tasks` (`pickcode`)"
                )
                // 非唯一：worker 取 nextPending 与统计未完成数。
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_strm_regeneration_tasks_status` " +
                        "ON `strm_regeneration_tasks` (`status`)"
                )
                // 非唯一：设置页按最近活动排序。
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_strm_regeneration_tasks_updatedAt` " +
                        "ON `strm_regeneration_tasks` (`updatedAt`)"
                )
            }
        }
    }
}
