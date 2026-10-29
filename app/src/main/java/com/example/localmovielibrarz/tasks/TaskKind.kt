package com.example.localmovielibrarz.tasks

/**
 * 统一账本里的任务种类。
 *
 * 为什么单独建枚举而不是复用各 runner 的类型：三套旧任务（文件夹批量、单视频入库、影片刮削）
 * 加上两类纯后台补全（元数据、直链）需要出现在同一张 `task_runs` 表、同一个设置页横幅里。
 * 账本只关心「哪一类任务」，不关心「哪套实现」——旧实体继续用各自的状态列，种类只作为分类维度。
 *
 * [displayName] 是 UI 文案的唯一来源：设置页/首页横幅按种类分组时直接取用，避免文案散落在 Compose 里，
 * 也避免「刮削 / 补全 / 刷新」这类近义词在不同页面被翻译成不同中文。
 */
enum class TaskKind(val displayName: String) {
    /** 网盘文件夹批量入库（对应 `CloudFolderBatchTaskRunner`）。 */
    FolderBatch("文件夹批量入库"),

    /** 单个视频入队入库（对应 `CloudVideoTaskRunner`）。 */
    VideoAdd("单视频入库"),

    /** 影片元数据刮削（对应 `MovieScrapeTaskRunner`）。 */
    MovieScrape("影片刮削"),

    /** 已入库影片的元数据补全（缺 NFO/海报等字段的批量回填）。 */
    MetadataRefresh("元数据补全"),

    /** 播放直链刷新（光鸭/115 直链过期后的批量续签）。 */
    LinkRefresh("直链刷新");

    companion object {
        /**
         * 宽松解析持久化字符串：去空白、忽略大小写；未知值返回 `null` 由调用方决定降级策略。
         *
         * 为什么不回退到某个默认种类：种类决定横幅分组与统计口径，猜错会让统计串味；
         * 宁可让调用方跳过这条脏数据（或打日志），也不要把它塞进 [FolderBatch]。
         */
        fun parse(raw: String?): TaskKind? {
            val normalized = raw?.trim().orEmpty()
            if (normalized.isEmpty()) return null
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        }
    }
}
