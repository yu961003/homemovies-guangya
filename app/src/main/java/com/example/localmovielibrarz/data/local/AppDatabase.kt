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
        TaskRunEntity::class,
        // ★ 20 -> 21：本地字幕库索引。用户把手机里某个文件夹选成「字幕库」后，
        // 扫描结果落这张表，播放时按番号直接查；不塞进 movies 是因为字幕与影片是
        // 多对一（一部片可以有简中/繁中/日文多份），塞进主表会让主表按字幕数量膨胀。
        LocalSubtitleLibraryEntity::class
    ],
    version = 21,
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
    abstract fun localSubtitleLibraryDao(): LocalSubtitleLibraryDao
}
