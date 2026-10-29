package com.example.localmovielibrarz.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        MovieEntity::class,
        DirectLinkEntity::class,
        PlaybackProgressEntity::class,
        CloudStrmRecordEntity::class,
        CloudFolderBatchTaskEntity::class,
        CloudVideoTaskEntity::class,
        GuangyaDirectLinkEntity::class,
        // ★ 19 -> 20：四个跨模块的旁路表。都在下面的 MIGRATION_19_20 里建 DDL，
        // 之所以拆成独立表而不是继续塞进 movies / cloud_folder_batch_tasks：
        // 它们的数据量与原表不同级（字段归属按「字段×影片」增长、账本按「运行次数」增长），
        // 混在一起会让主表越迁移越重。
        MovieFieldProvenanceEntity::class,
        EntityAliasEntity::class,
        SourceHealthEntity::class,
        TaskRunEntity::class
    ],
    version = 20,
    exportSchema = true
)
@TypeConverters(MovieTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun movieDao(): MovieDao
    abstract fun directLinkDao(): DirectLinkDao
    abstract fun playbackProgressDao(): PlaybackProgressDao
    abstract fun cloudStrmRecordDao(): CloudStrmRecordDao
    abstract fun cloudFolderBatchTaskDao(): CloudFolderBatchTaskDao
    abstract fun cloudVideoTaskDao(): CloudVideoTaskDao
    abstract fun guangyaDirectLinkDao(): GuangyaDirectLinkDao
    abstract fun movieFieldProvenanceDao(): MovieFieldProvenanceDao
    abstract fun entityAliasDao(): EntityAliasDao
    abstract fun sourceHealthDao(): SourceHealthDao
    abstract fun taskRunDao(): TaskRunDao
}
