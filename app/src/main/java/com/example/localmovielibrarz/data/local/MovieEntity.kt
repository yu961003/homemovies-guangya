package com.example.localmovielibrarz.data.local

import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "movies",
    indices = [
        Index(value = ["videoUri"], unique = true),
        Index(value = ["libraryRootUri"]),
        Index(value = ["sortTitle"]),
        Index(value = ["scannedAtMillis"]),
        Index(value = ["updatedAt"]),
        Index(value = ["year"]),
        Index(value = ["isFavorite"]),
        Index(value = ["isWatched"]),
        Index(value = ["scrapeTaskStatus"])
    ]
)
data class MovieEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val libraryRootUri: String,
    val videoUri: String,
    val videoName: String,
    val sortTitle: String,
    val title: String,
    val originalTitle: String?,
    val plot: String?,
    val outline: String?,
    val year: Int?,
    val premiered: String?,
    val runtimeMinutes: Int?,
    val mpaa: String?,
    val studios: List<String>,
    val series: String?,
    val directors: List<String>,
    val actors: List<String>,
    val genres: List<String>,
    val tags: List<String>,
    val rating: Double?,
    val uniqueIds: List<String>,
    val posterUri: String?,
    val fanartUri: String?,
    val thumbUri: String?,
    val nfoUri: String?,
    val scannedAtMillis: Long,
    val isFavorite: Boolean = false,
    val isWatched: Boolean = false,
    val updatedAt: Long = 0,
    val scrapeFailureReason: String? = null,
    val scrapeTaskStatus: String = ScrapeTaskStatus.None.name
) {
    /**
     * 扫描阶段一次性读取 STRM 内容得到的 pickcode 缓存（不落库）。
     * 声明为类体属性而非构造参数：Room 2.6.1 KSP 对 data class 构造器中的
     * @Ignore 参数存在 "Cannot find setter for field" 缺陷，类体 @Ignore 属性是标准用法。
     * 注意：data class 的 copy()/equals()/hashCode() 不包含该类体属性，使用方需自行处理。
     */
    @Ignore
    var cachedPickcode: String? = null
}

enum class ScrapeTaskStatus {
    None,
    Pending,
    Running,
    Completed,
    Failed;

    companion object {
        val unfinishedNames: List<String> = listOf(Pending.name, Running.name, Failed.name)
    }
}
