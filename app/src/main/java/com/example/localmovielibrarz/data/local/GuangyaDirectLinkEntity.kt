package com.example.localmovielibrarz.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 光鸭直链缓存表（镜像 direct_links，键为 fileId）。 */
@Entity(
    tableName = "guangya_direct_links",
    indices = [Index(value = ["expiresAt"])]
)
data class GuangyaDirectLinkEntity(
    @PrimaryKey val fileId: String,
    val url: String,
    val expiresAt: Long,
    val updatedAt: Long
)
