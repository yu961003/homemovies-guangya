package com.example.localmovielibrarz.metadata

/**
 * 影片元数据字段的稳定标识。
 *
 * 为什么要独立于数据库实体再定义一层枚举：字段权威记录、合并决策、待审清单都需要落库，
 * 而 Room 表无法直接引用实体属性；如果调用点到处手写列名字符串，改列名只能靠全局搜索，
 * 拼错也不会编译失败。枚举是唯一事实来源，[storageColumn] 负责映射回实体属性名，
 * 接线层因此只需要维护一份映射，不必在多处字符串之间对表。
 *
 * 本文件刻意不 import Android / Room，保持纯 Kotlin，便于在 JVM 单测与后台线程复用。
 */
enum class MetadataFieldKey(
    /** 对应 `MovieEntity` 的属性名，也就是 Room 表 `movies` 的列名。 */
    val storageColumn: String,
    /**
     * 该字段在实体里是否为 `List<String>`。
     *
     * 多值字段由 `MovieTypeConverters` 以 `\u001F` 连接为单列存储，因此候选值也用同一分隔符
     * 串接，从候选写入数据库列时不需要二次拆分；聚合演员/厂牌时按此标记逐项做名称归一。
     */
    val isMultiValue: Boolean = false
) {
    Title("title"),
    OriginalTitle("originalTitle"),
    Plot("plot"),
    Outline("outline"),
    Year("year"),
    Premiered("premiered"),

    // 实体属性叫 runtimeMinutes：它存的是分钟数。NFO 里的 <runtime> 是另一个量纲，不要混用。
    Runtime("runtimeMinutes"),
    Mpaa("mpaa"),
    Studios("studios", isMultiValue = true),
    Series("series"),
    Directors("directors", isMultiValue = true),
    Actors("actors", isMultiValue = true),
    Genres("genres", isMultiValue = true),
    Tags("tags", isMultiValue = true),
    Rating("rating"),

    // 实体里图片列叫 xxxUri；候选值通常是站点 URL，由接线层决定下载落盘后再写 Uri。
    Poster("posterUri"),
    Fanart("fanartUri"),
    Thumb("thumbUri");

    companion object {
        /**
         * 把数据库列名还原成字段枚举。
         *
         * 同时接受枚举名（如 `Runtime`）与列名（如 `runtimeMinutes`），因为持久化层既可能存列名，
         * 也可能直接存 `key.name`；本枚举内两者不会指向不同字段，所以两种写法都解析是安全的。
         * 匹配忽略大小写与首尾空白——SQLite 的列名比较本身就不区分大小写，调用方多传了空格
         * 也不该导致静默丢字段。
         */
        fun fromColumn(name: String): MetadataFieldKey? {
            val key = name.trim()
            if (key.isEmpty()) return null
            return entries.firstOrNull { it.storageColumn.equals(key, ignoreCase = true) }
                ?: entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
        }
    }
}
