package com.example.localmovielibrarz.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地字幕库索引行：一行 = 用户授权过的某个 SAF 目录树里的一个字幕文件。
 *
 * 为什么不复用 `movies` 表、也不在播放时现查目录：
 * 字幕在真实库里散落在任意层级（`ABC-123/字幕/ABC-123.chs.srt` 很常见），
 * 播放路径上一次目录列举就会把 SAF 查询打到关键路径上。先扫一遍建索引，
 * 后续「番号 -> 字幕」只剩一次本地 SQL。
 *
 * `uri` 存 [String] 而不是 [android.net.Uri]：Room 没有内置的 Uri 转换器，
 * 全局 [MovieTypeConverters] 只覆盖 List<String> 等少数类型；为一张旁路表
 * 去动全局转换器不划算，而字符串在带唯一索引的列上比较也更直接。
 *
 * 字段取舍：
 * - `relativePath` 无法由 `uri` 推出来：uri 里的 documentId 是 SAF 内部编码
 *   （例如 `primary:Movies/subs/ABC-123.srt`），换设备或换 provider 后不可读，
 *   展示与「同名字幕去重」只认这个相对库根的路径。
 * - `indexedAt`（这次扫描写入的时间）与 `lastModified` / `sizeBytes`（文件自身
 *   的元数据）分开存：增量扫描靠后两者判断是否要重扫，前者只用于展示「上次索引于」。
 */
@Entity(
    tableName = "local_subtitle_library",
    indices = [
        Index(value = ["uri"], unique = true),
        Index(value = ["movieNumber"]),
        Index(value = ["libraryRootUri"]),
        Index(value = ["fileName"])
    ]
)
data class LocalSubtitleLibraryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 字幕文件的 SAF uri 字符串（唯一）；同一文件重复入库由 REPLACE 覆盖，不会新增行。 */
    val uri: String,
    /** 显示名，含扩展名，例如 `ABC-123.chs.srt`。 */
    val fileName: String,
    /** 相对库根的路径，`/` 分隔，例如 `ABC-123/subs/ABC-123.chs.srt`。 */
    val relativePath: String,
    /** 小写扩展名，取值 `srt` / `vtt` / `ass` / `ssa`。 */
    val ext: String,
    /** 从文件名解析出的番号（形如 `ABC-123`）；解析不出就是 null——不猜。 */
    val movieNumber: String?,
    val sizeBytes: Long,
    val lastModified: Long,
    /** 该行所属库根的 tree uri 字符串；删索引、重建索引都以它为界。 */
    val libraryRootUri: String,
    /** 本行写入索引的时间（毫秒）。 */
    val indexedAt: Long
)
