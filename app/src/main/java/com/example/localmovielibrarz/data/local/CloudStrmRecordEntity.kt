package com.example.localmovielibrarz.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cloud_strm_records",
    indices = [
        Index(value = ["movieNumber"]),
        Index(value = ["libraryRootUri"]),
        Index(value = ["movieId"]),
        Index(value = ["movieNumber", "partLabel", "variant", "fileName"])
    ]
)
data class CloudStrmRecordEntity(
    @PrimaryKey
    val pickcode: String,
    /** ★ 第九轮新增：来源标识 "115" | "GUANGYA"（播放源徽标 / 筛选 / 回填任务使用）。 */
    val source: String,
    val fileName: String,
    val movieNumber: String?,
    val variant: String?,
    val partLabel: String?,
    val strmUri: String,
    val libraryRootUri: String?,
    val movieId: Long?,
    val videoSizeBytes: Long?,
    val createdAt: Long,
    val updatedAt: Long
)
