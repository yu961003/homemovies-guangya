package com.example.localmovielibrarz.subtitle

enum class SubtitleSearchProvider(
    val id: String,
    val label: String,
    val fileSuffix: String
) {
    Avsubtitles("avsubtitles", "AVSubtitles", "avsubtitles"),
    Xunlei("xunlei", "迅雷字幕", "xunlei"),
    Cloud115("cloud115", "网盘字幕", "cloud115");

    companion object {
        fun fromId(id: String?): SubtitleSearchProvider =
            entries.firstOrNull { it.id == id } ?: Xunlei
    }
}
