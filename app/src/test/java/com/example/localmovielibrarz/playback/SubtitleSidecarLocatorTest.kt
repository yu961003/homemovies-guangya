package com.example.localmovielibrarz.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 侧挂字幕定位器的纯函数测试：只吃文件名列表，不需要 Android 环境或临时文件。
 */
class SubtitleSidecarLocatorTest {

    @Test
    fun `finds language suffixed sidecars and rejects unrelated files`() {
        val matches = SubtitleSidecarLocator.find(
            videoFileName = "ABC-123.mp4",
            candidates = listOf(
                "ABC-123.srt",
                "ABC-123.chs.srt",
                "ABC-123.zh-CN.ass",
                "ABC-123.简.srt",
                "ABC-123.forced.vtt",
                "ABC-123.chs.forced.ass",
                "ABC-1234.srt",
                "ABC-123.srt.bak",
                "ABC-123.txt",
                "XYZ-999.srt"
            )
        )

        assertEquals(
            listOf(
                "ABC-123.chs.srt",
                "ABC-123.zh-CN.ass",
                "ABC-123.简.srt",
                "ABC-123.chs.forced.ass",
                "ABC-123.srt",
                "ABC-123.forced.vtt"
            ),
            matches.map { it.candidate }
        )
        assertTrue(matches.all { it.baseName == "ABC-123" })
        assertEquals("zh", matches.first { it.candidate == "ABC-123.chs.forced.ass" }.languageTag)
        assertEquals("zh", matches.first { it.candidate == "ABC-123.chs.srt" }.languageTag)
        assertEquals("zh", matches.first { it.candidate == "ABC-123.zh-CN.ass" }.languageTag)
        assertEquals("zh", matches.first { it.candidate == "ABC-123.简.srt" }.languageTag)
        assertNull(matches.first { it.candidate == "ABC-123.srt" }.languageTag)
        assertEquals("forced", matches.first { it.candidate == "ABC-123.forced.vtt" }.languageTag)
        assertEquals(120, matches.first { it.candidate == "ABC-123.chs.srt" }.score)
        assertEquals(100, matches.first { it.candidate == "ABC-123.srt" }.score)
        assertEquals(90, matches.first { it.candidate == "ABC-123.forced.vtt" }.score)
    }

    @Test
    fun `infers only explicit language tokens`() {
        assertEquals("zh", SubtitleSidecarLocator.inferLanguageTag("chs"))
        assertEquals("zh", SubtitleSidecarLocator.inferLanguageTag("CHS"))
        assertEquals("zh", SubtitleSidecarLocator.inferLanguageTag("chi"))
        assertEquals("zh", SubtitleSidecarLocator.inferLanguageTag("zh"))
        assertEquals("zh", SubtitleSidecarLocator.inferLanguageTag("zh-CN"))
        assertEquals("zh", SubtitleSidecarLocator.inferLanguageTag("简"))
        assertEquals("zh", SubtitleSidecarLocator.inferLanguageTag("简体"))
        assertEquals("zh", SubtitleSidecarLocator.inferLanguageTag("中字"))

        assertEquals("zh-Hant", SubtitleSidecarLocator.inferLanguageTag("cht"))
        assertEquals("zh-Hant", SubtitleSidecarLocator.inferLanguageTag("繁"))
        assertEquals("zh-Hant", SubtitleSidecarLocator.inferLanguageTag("繁体"))

        assertEquals("en", SubtitleSidecarLocator.inferLanguageTag("en"))
        assertEquals("en", SubtitleSidecarLocator.inferLanguageTag("eng"))
        assertEquals("en", SubtitleSidecarLocator.inferLanguageTag("英文"))

        assertEquals("ja", SubtitleSidecarLocator.inferLanguageTag("jp"))
        assertEquals("ja", SubtitleSidecarLocator.inferLanguageTag("ja"))
        assertEquals("ja", SubtitleSidecarLocator.inferLanguageTag("日"))
        assertEquals("ja", SubtitleSidecarLocator.inferLanguageTag("日语"))

        assertEquals("forced", SubtitleSidecarLocator.inferLanguageTag("forced"))
        assertEquals("forced", SubtitleSidecarLocator.inferLanguageTag("强制"))

        assertNull(SubtitleSidecarLocator.inferLanguageTag("korean"))
        assertNull(SubtitleSidecarLocator.inferLanguageTag(""))
    }

    @Test
    fun `does not match a longer or shorter number`() {
        assertTrue(
            SubtitleSidecarLocator.find(
                videoFileName = "ABC-123.mp4",
                candidates = listOf(
                    "ABC-1234.srt",
                    "ABC-1234.chs.srt",
                    "ABC-1234.forced.vtt",
                    "ABC-12.srt"
                )
            ).isEmpty()
        )
        assertTrue(
            SubtitleSidecarLocator.find(
                videoFileName = "ABC-1234.mp4",
                candidates = listOf("ABC-123.srt", "ABC-123.chs.srt")
            ).isEmpty()
        )
    }

    @Test
    fun `ranks forced subtitles after unknown language subtitles`() {
        val ranked = SubtitleSidecarLocator.find(
            videoFileName = "ABC-123.mp4",
            candidates = listOf(
                "ABC-123.forced.vtt",
                "ABC-123.srt",
                "ABC-123.en.srt"
            )
        )

        assertEquals(
            listOf("ABC-123.en.srt", "ABC-123.srt", "ABC-123.forced.vtt"),
            ranked.map { it.candidate }
        )
    }

    @Test
    fun `accepts only subtitle extensions`() {
        val matches = SubtitleSidecarLocator.find(
            videoFileName = "ABC-123.mp4",
            candidates = listOf(
                "ABC-123.srt",
                "ABC-123.ass",
                "ABC-123.ssa",
                "ABC-123.vtt",
                "ABC-123.sub",
                "ABC-123.VTT",
                "ABC-123.mp4",
                "ABC-123.txt",
                "ABC-123.srtx",
                "ABC-123"
            )
        )

        assertEquals(
            setOf(
                "ABC-123.srt",
                "ABC-123.ass",
                "ABC-123.ssa",
                "ABC-123.vtt",
                "ABC-123.sub",
                "ABC-123.VTT"
            ),
            matches.map { it.candidate }.toSet()
        )
    }

    @Test
    fun `ranks forced subtitles after non forced ones of the same language`() {
        val ranked = SubtitleSidecarLocator.find(
            videoFileName = "ABC-123.mp4",
            candidates = listOf(
                "ABC-123.chs.forced.ass",
                "ABC-123.chs.srt",
                "ABC-123.forced.srt",
                "ABC-123.en.forced.ass",
                "ABC-123.en.srt"
            )
        )

        // 先按语言档（已知 0 / 未知 1）分组，再在同一语言档内把 forced 全部压到最后，
        // 最后按文件名小写字典序；所以两个非 forced 的 chs/en 排在两个 forced 之前。
        assertEquals(
            listOf(
                "ABC-123.chs.srt",
                "ABC-123.en.srt",
                "ABC-123.chs.forced.ass",
                "ABC-123.en.forced.ass",
                "ABC-123.forced.srt"
            ),
            ranked.map { it.candidate }
        )
        // forced 不改变语言推断本身：chs.forced 仍然是中文，只是排序被压到同档末尾。
        assertEquals("zh", ranked.first { it.candidate == "ABC-123.chs.forced.ass" }.languageTag)
        assertEquals("forced", ranked.first { it.candidate == "ABC-123.forced.srt" }.languageTag)
    }

    @Test
    fun `ranks known languages by name and keeps equal keys stable`() {
        val matches = listOf(
            SubtitleSidecarMatchForTest(name = "ABC-123.srt", tag = null),
            SubtitleSidecarMatchForTest(name = "ABC-123.en.srt", tag = "en"),
            SubtitleSidecarMatchForTest(name = "ABC-123.forced.vtt", tag = "forced"),
            SubtitleSidecarMatchForTest(name = "ABC-123.chs.srt", tag = "zh")
        ).map { it.toMatch() }

        val ranked = SubtitleSidecarLocator.rank(matches)

        assertEquals(
            listOf(
                "ABC-123.chs.srt",
                "ABC-123.en.srt",
                "ABC-123.srt",
                "ABC-123.forced.vtt"
            ),
            ranked.map { it.candidate }
        )

        // 排序键完全相同的条目（只差大小写）保持入参顺序，证明排序是稳定的。
        val first = SubtitleSidecarMatchForTest(name = "ABC-123.SRT", tag = null).toMatch()
        val second = SubtitleSidecarMatchForTest(name = "abc-123.srt", tag = null).toMatch()
        assertEquals(
            listOf("ABC-123.SRT", "abc-123.srt"),
            SubtitleSidecarLocator.rank(listOf(first, second)).map { it.candidate }
        )
    }

    @Test
    fun `matches case insensitively and ignores directory prefixes`() {
        val matches = SubtitleSidecarLocator.find(
            videoFileName = "Abc-123.MP4",
            candidates = listOf("/storage/emulated/0/movies/subs/abc-123.chs.SRT")
        )

        assertEquals(1, matches.size)
        assertEquals("abc-123.chs.SRT", matches[0].candidate)
        assertEquals("Abc-123", matches[0].baseName)
        assertEquals("zh", matches[0].languageTag)
    }

    @Test
    fun `keeps dotted video base names intact`() {
        val matches = SubtitleSidecarLocator.find(
            videoFileName = "Movie.2024.1080p.mkv",
            candidates = listOf(
                "Movie.2024.1080p.chs.srt",
                "Movie.2024.1080p4.srt",
                "Movie.2024.chs.srt"
            )
        )

        assertEquals(listOf("Movie.2024.1080p.chs.srt"), matches.map { it.candidate })
    }

    @Test
    fun `handles a video name without extension and rejects a blank name`() {
        val matches = SubtitleSidecarLocator.find(
            videoFileName = "ABC-123",
            candidates = listOf("ABC-123.chs.srt")
        )

        assertEquals(1, matches.size)
        assertEquals("ABC-123", matches[0].baseName)
        assertTrue(SubtitleSidecarLocator.find("", listOf("ABC-123.srt")).isEmpty())
        assertTrue(SubtitleSidecarLocator.find("   ", listOf("ABC-123.srt")).isEmpty())
    }

    /** 构造 rank() 输入的小工具，避免每处重复写四个参数。 */
    private data class SubtitleSidecarMatchForTest(val name: String, val tag: String?) {
        fun toMatch(): SubtitleSidecarLocator.SidecarMatch =
            SubtitleSidecarLocator.SidecarMatch(
                candidate = name,
                baseName = "ABC-123",
                languageTag = tag,
                score = 0
            )
    }
}
