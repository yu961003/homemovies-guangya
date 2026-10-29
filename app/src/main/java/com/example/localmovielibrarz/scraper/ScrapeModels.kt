package com.example.localmovielibrarz.scraper

enum class ScrapeSource {
    Priority,
    Dmm,
    Dmm2,
    Official,
    Mgstage,
    Javbus,
    TheJavDB,

    /**
     * r18.dev：DMM 数字版目录的 JSON 镜像。
     *
     * 位置放在枚举尾部的 `CustomJson` 之前，是为了让设置页的来源勾选顺序读起来仍是
     * 「DMM 系 → 官方 → 社区索引 → 镜像」；枚举序号不参与任何业务判断（路由与结算都走显式表）。
     */
    R18Dev,

    /**
     * peach 链新接入的三家：AVBase（社区索引）、一本道（无码官网）、FC2（官方文章页）。
     *
     * 为什么必须成为枚举成员而不是藏在 peach 链内部：用户可以在设置里显式点名一个来源，
     * 详情页的重刮按钮也要显示「这次用的是哪一家」。如果这三家只活在 peach 链的私有账本里，
     * 手动点名与重刮提示都表达不了它们，等于接了个用户看不见的源。
     *
     * 位置仍放在 `CustomJson` 之前，理由同上：设置页按枚举顺序渲染，读起来应该还是
     * 「DMM 系 → 官方 → 社区索引 → 镜像 → peach 新源」。
     */
    Avbase,
    OnePondo,
    Fc2,

    /**
     * peach 里靠「浏览器通道」或「amane 来源桥」才能取到页的那六家。
     *
     * 之前这六家在 [PeachSourceCatalog] 里是 `wired = false`：链跑到它们就记一条 `skipped`，
     * 用户能在日志里看到「peach 有这一步，但本工程没接」。这一轮把两条通道补上之后，
     * 它们变成真的可执行步骤，所以必须进枚举——否则设置页点名、详情页「这次用的哪一家」、
     * 来源健康度（冷却 / 拦截阶梯）这三处都没有地方挂它们。
     *
     * 分组的理由不是「同类站点」，而是**取数通道**：
     * - [Faleno] / [Dahlia] / [MakerSite] 走官方站 + 浏览器回落（faleno 对非日本出口直接 403）；
     * - [Fc2Cmadb] / [Fc2PpvDb] / [Javten] 是 FC2 系的社区索引，站点都靠 Cloudflare，
     *   peach 侧 `browser: True`，本工程等价为「HTTP 带 Cookie 先试，被拦再上浏览器」。
     *
     * 位置仍在 `CustomJson` 之前：设置页按枚举顺序渲染，用户自建端点应该排在所有内置来源之后。
     */
    Faleno,
    Dahlia,
    MakerSite,
    Fc2Cmadb,
    Fc2PpvDb,
    Javten,

    CustomJson;

    /**
     * 展示名，唯一来源。
     *
     * 在此之前，`StrmScrapeRepository` / `SettingsScreen` / `DetailViewModel` 各自有一份
     * 逐字相同的 `when (source) { ... -> "JavBus" }` 私有扩展——三份副本意味着将来改文案
     * 必然漏改一处。更麻烦的是刮削链（`ScrapeSourceChain`）在两个文件之外，够不着任何一份，
     * 只能自己再抄第四份。收敛到枚举成员后，链层的失败原因、设置页的当前来源、详情页的
     * 重刮按钮文案都取同一个值。
     *
     * 文案与既有三份保持一致（"优先级刮削" 而不是 "优先级链"），避免这一轮增强顺带改掉用户
     * 已经习惯的措辞。
     */
    val label: String
        get() = when (this) {
            Priority -> "优先级刮削"
            Dmm -> "DMM"
            Dmm2 -> "DMM2"
            Official -> "Official"
            Mgstage -> "MGStage"
            Javbus -> "JavBus"
            TheJavDB -> "TheJavDB"
            R18Dev -> "r18.dev"
            Avbase -> "AVBase"
            OnePondo -> "一本道"
            Fc2 -> "FC2"
            Faleno -> "FALENO 官网"
            Dahlia -> "DAHLIA 官网"
            MakerSite -> "片商官网"
            Fc2Cmadb -> "FC2CMADB"
            Fc2PpvDb -> "FC2PPV-DB"
            Javten -> "JAVten"
            CustomJson -> "自定义 JSON"
        }

    companion object {
        fun fromStoredName(value: String?): ScrapeSource? {
            val normalized = value?.trim().orEmpty()
            if (normalized.equals("Javdb", ignoreCase = true)) return TheJavDB
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
        }
    }
}

data class ScrapedMovieInfo(
    val number: String,
    val title: String,
    val originalTitle: String = title,
    val plot: String = "",
    val outline: String = plot,
    val year: String = "",
    val premiered: String = "",
    val runtime: String = "",
    val studio: String = "",
    val publisher: String = "",
    val series: String = "",
    val directors: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val actorImageUrls: Map<String, String> = emptyMap(),
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val rating: String = "",
    val trailer: String = "",
    val website: String = "",
    val source: String = "",
    val thumbUrl: String = "",
    val posterUrl: String = "",
    val fanartUrl: String = "",
    val thumbImageUrls: List<String> = emptyList(),
    val posterImageUrls: List<String> = emptyList()
)

data class ScrapeRunResult(
    val scanned: Int,
    val success: Int,
    val skipped: Int,
    val failed: Int
)
