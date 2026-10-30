package com.example.localmovielibrarz.subtitle

enum class SubtitleSearchProvider(
    val id: String,
    val label: String,
    val fileSuffix: String
) {
    Avsubtitles("avsubtitles", "AVSubtitles", "avsubtitles"),
    Xunlei("xunlei", "迅雷字幕", "xunlei"),
    Cloud115("cloud115", "网盘字幕", "cloud115"),

    /**
     * 用户在设置里选的手机本地字幕库目录（SAF tree）。
     *
     * 它是唯一一个「全程不出网」的来源：命中就直接把文件 URI 交给播放器挂载，
     * 不走下载、不写视频所在目录，因此也不需要 `fileSuffix` 参与落盘命名；
     * 这里给一个占位后缀，只为满足既有构造签名。
     */
    LocalLibrary("local_library", "本地字幕库", "locallib");

    companion object {
        fun fromId(id: String?): SubtitleSearchProvider =
            entries.firstOrNull { it.id == id } ?: Xunlei
    }
}
