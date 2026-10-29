package com.example.localmovielibrarz.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cloud_folder_batch_tasks",
    indices = [
        Index(value = ["status"]),
        Index(value = ["folderCid"]),
        Index(value = ["updatedAt"])
    ]
)
data class CloudFolderBatchTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val folderCid: Long,
    val folderName: String,
    val status: String = CloudFolderBatchTaskStatus.Pending.name,
    val currentPath: String? = null,
    val currentFileName: String? = null,
    val queuedVideos: Int = 0,
    val processedVideos: Int = 0,
    val addedVideos: Int = 0,
    val scrapeFailedVideos: Int = 0,
    val skippedVideos: Int = 0,
    val failedVideos: Int = 0,
    val failedFolders: Int = 0,
    val failureMessage: String? = null,
    /**
     * ★ 断点续刮（F-1）：已处理 pickcode 及其结局，编码为 `pickcode=OUTCOME;pickcode=OUTCOME`。
     *
     * 这是「续刮」的唯一真相源：重启 / 暂停后继续时 runner 读回该集合，命中即跳过，
     * 不会重复 enqueue、重复生成 STRM，也不会把同一部片重复计入统计。
     * processedVideos / addedVideos / skippedVideos / scrapeFailedVideos 均可由该列推导，
     * 因此多次中断-恢复也不会出现计数漂移。
     */
    val processedPickcodesCsv: String = "",
    /** 最近一次进度落库时间，设置页据此判断任务是否长时间无进展。 */
    val lastProgressAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class CloudFolderBatchTaskStatus {
    Pending,
    Running,
    Paused,

    /** 用户主动取消（F-4）：与 Paused 区分——取消表示不再继续，需重新添加文件夹才会重跑。 */
    Cancelled,
    Completed,
    Failed
}

/**
 * 单个 pickcode 的终态（断点续跑用）。
 * 注意它与 [CloudFolderBatchTaskStatus] 不是一回事：后者描述**任务**，前者描述**一部影片**。
 */
enum class CloudPickcodeOutcome {
    /** STRM 已生成并成功入库。 */
    Added,

    /** 命中跳过策略（已在库 / 排除名单 / 无番号 / 体积过小 / 缺 pickcode）。 */
    Skipped,

    /** 已入库但刮削失败，续跑时不再重复入库。 */
    ScrapeFailed;

    companion object {
        fun parse(raw: String): CloudPickcodeOutcome? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}

/** `pickcode=OUTCOME;...` 编解码。pickcode 只含数字与大写字母，不含 `=` 与 `;`，无需转义。 */
object CloudPickcodeProgressCodec {
    private const val ENTRY_SEPARATOR = ';'
    private const val VALUE_SEPARATOR = '='

    fun decode(csv: String?): LinkedHashMap<String, CloudPickcodeOutcome> {
        val result = LinkedHashMap<String, CloudPickcodeOutcome>()
        if (csv.isNullOrBlank()) return result
        csv.split(ENTRY_SEPARATOR).forEach { entry ->
            if (entry.isBlank()) return@forEach
            val index = entry.lastIndexOf(VALUE_SEPARATOR)
            if (index <= 0 || index >= entry.lastIndex) return@forEach
            val pickcode = entry.substring(0, index).trim()
            val outcome = CloudPickcodeOutcome.parse(entry.substring(index + 1)) ?: return@forEach
            if (pickcode.isNotEmpty()) result[pickcode] = outcome
        }
        return result
    }

    fun encode(entries: Map<String, CloudPickcodeOutcome>): String =
        entries.entries
            .filter { it.key.isNotBlank() }
            .joinToString(ENTRY_SEPARATOR.toString()) { "${it.key}$VALUE_SEPARATOR${it.value.name}" }
}
