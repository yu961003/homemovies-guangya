package com.example.localmovielibrarz.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 封面择优（peach `jav_cover_fetch`）。
 *
 * peach 的做法是 Range 只取 64 KB，从左到右量出真实像素，多候选里挑「角色更好或像素更多」
 * 的那张。为什么不能按 URL 猜：同一部片的 `ps.jpg`（缩略）与 `/w1920/…`（原图）在文件名上
 * 只差几个字符，而剧照（preview）与商品图（product）可能同为 2000px——猜错的代价是一张
 * 别的片的截图被写成封面，用户在墙上看到的每一格都是错的。
 */
class PeachCoverPickTest {

    /** 造一张 PNG 头（签名 + IHDR 宽高）。 */
    private fun pngHeader(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(24)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(bytes, 0)
        bytes[8] = 0
        bytes[9] = 0
        bytes[10] = 0
        bytes[11] = 13
        bytes[12] = 'I'.code.toByte()
        bytes[13] = 'H'.code.toByte()
        bytes[14] = 'D'.code.toByte()
        bytes[15] = 'R'.code.toByte()
        writeU32(bytes, 16, width)
        writeU32(bytes, 20, height)
        return bytes
    }

    /** 造一张 JPEG 头：SOI + APP0（长度 16 的空段）+ SOF0（宽高）。 */
    private fun jpegHeader(width: Int, height: Int): ByteArray {
        val bytes = ByteArray(32)
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xD8.toByte()
        // APP0：FF E0 len=0x0010，payload 14 字节
        bytes[2] = 0xFF.toByte()
        bytes[3] = 0xE0.toByte()
        bytes[4] = 0x00
        bytes[5] = 0x10
        // SOF0 从 index 2+2+16 = 20 开始
        val sof = 20
        bytes[sof] = 0xFF.toByte()
        bytes[sof + 1] = 0xC0.toByte()
        bytes[sof + 2] = 0x00
        bytes[sof + 3] = 0x11
        bytes[sof + 4] = 0x08
        writeU16(bytes, sof + 5, height)
        writeU16(bytes, sof + 7, width)
        return bytes
    }

    private fun writeU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value shr 24) and 0xFF).toByte()
        target[offset + 1] = ((value shr 16) and 0xFF).toByte()
        target[offset + 2] = ((value shr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value shr 8) and 0xFF).toByte()
        target[offset + 1] = (value and 0xFF).toByte()
    }

    @Test
    fun probeBytesMatchesPeach() {
        // peach 的 `PROBE_BYTES = 64 * 1024`：只取头部，不为量尺寸把整张图拉下来。
        assertEquals(64 * 1024, PeachCoverPick.PROBE_BYTES)
    }

    @Test
    fun pngSizeIsReadFromIhdr() {
        val size = PeachCoverPick.sizeOf(pngHeader(1920, 1080))
        assertEquals(PeachCoverPick.Size.Known(1920, 1080), size)
    }

    @Test
    fun jpegSizeIsReadFromSof() {
        val size = PeachCoverPick.sizeOf(jpegHeader(800, 538))
        assertEquals(PeachCoverPick.Size.Known(800, 538), size)
    }

    @Test
    fun unknownBytesGiveUnknownSize() {
        assertEquals(PeachCoverPick.Size.Unknown, PeachCoverPick.sizeOf(ByteArray(64)))
        assertEquals(PeachCoverPick.Size.Unknown, PeachCoverPick.sizeOf(ByteArray(8)))
    }

    @Test
    fun biggerPixelsWinWithinTheSameRole() {
        val best = PeachCoverPick.Candidate("https://a/1.jpg", source = ScrapeSource.TheJavDB)
        val better = PeachCoverPick.Candidate("https://a/2.jpg", source = ScrapeSource.Javbus)
        assertTrue(
            PeachCoverPick.improves(
                candidate = better,
                candidateSize = PeachCoverPick.Size.Known(1200, 1600),
                best = best,
                bestSize = PeachCoverPick.Size.Known(200, 300)
            )
        )
    }

    @Test
    fun roleBeatsPixels() {
        // 一张 2000px 的剧照不该顶掉官方的商品图：剧照常是别的片的截图或二次加工图。
        val best = PeachCoverPick.Candidate(
            "https://a/cover.jpg", role = PeachCoverPick.CoverRole.Product, source = ScrapeSource.TheJavDB
        )
        val preview = PeachCoverPick.Candidate(
            "https://a/still.jpg", role = PeachCoverPick.CoverRole.Preview, source = ScrapeSource.Javbus
        )
        assertFalse(
            PeachCoverPick.improves(
                candidate = preview,
                candidateSize = PeachCoverPick.Size.Known(2000, 2000),
                best = best,
                bestSize = PeachCoverPick.Size.Known(200, 300)
            )
        )
    }

    @Test
    fun tieKeepsTheFirstArrival() {
        // 打平时不换：链是按层级排好的，换成后来的会让结果随网络快慢漂移。
        val best = PeachCoverPick.Candidate("https://a/1.jpg", source = ScrapeSource.TheJavDB)
        val same = PeachCoverPick.Candidate("https://a/2.jpg", source = ScrapeSource.Javbus)
        assertFalse(
            PeachCoverPick.improves(
                candidate = same,
                candidateSize = PeachCoverPick.Size.Known(800, 538),
                best = best,
                bestSize = PeachCoverPick.Size.Known(800, 538)
            )
        )
    }

    @Test
    fun unmeasurableCandidateReplacesOnlyWhenNothingKnownYet() {
        val measured = PeachCoverPick.Candidate("https://a/measured.jpg", source = ScrapeSource.TheJavDB)
        val unmeasurable = PeachCoverPick.Candidate("https://a/broken.jpg", source = ScrapeSource.Javbus)
        assertFalse(
            PeachCoverPick.improves(
                candidate = unmeasurable,
                candidateSize = PeachCoverPick.Size.Unknown,
                best = measured,
                bestSize = PeachCoverPick.Size.Known(100, 100)
            )
        )
        // 反过来：已知的那张是 Unknown、新来的能量出像素，就让位。
        assertTrue(
            PeachCoverPick.improves(
                candidate = measured,
                candidateSize = PeachCoverPick.Size.Known(100, 100),
                best = unmeasurable,
                bestSize = PeachCoverPick.Size.Unknown
            )
        )
    }

    @Test
    fun pickBestReturnsNullWhenListIsEmpty() {
        assertNull(PeachCoverPick.pickBest(emptyList()) { PeachCoverPick.Size.Unknown })
    }

    @Test
    fun pickBestSkipsBlankUrls() {
        val chosen = PeachCoverPick.pickBest(
            listOf(
                PeachCoverPick.Candidate("", source = ScrapeSource.TheJavDB),
                PeachCoverPick.Candidate("https://a/real.jpg", source = ScrapeSource.Javbus)
            )
        ) { PeachCoverPick.Size.Known(800, 538) }
        assertEquals("https://a/real.jpg", chosen?.url)
    }

    @Test
    fun pickBestUsesInjectedSizes() {
        val chosen = PeachCoverPick.pickBest(
            listOf(
                PeachCoverPick.Candidate("https://a/small.jpg", source = ScrapeSource.TheJavDB),
                PeachCoverPick.Candidate("https://a/big.jpg", source = ScrapeSource.Javbus)
            )
        ) { url ->
            if (url.endsWith("big.jpg")) PeachCoverPick.Size.Known(1600, 1200) else PeachCoverPick.Size.Known(320, 240)
        }
        assertEquals("https://a/big.jpg", chosen?.url)
    }

    @Test
    fun thumbnailAndPlaceholderUrlsAreScreenedOutBeforeMeasuring() {
        // 粗筛只为省一次 Range 请求，不参与判胜。
        assertTrue(PeachCoverPick.looksLikeThumbnailOrPlaceholder("https://a/now_printing.jpg"))
        assertTrue(PeachCoverPick.looksLikeThumbnailOrPlaceholder("https://a/ps.jpg"))
        assertTrue(PeachCoverPick.looksLikeThumbnailOrPlaceholder("https://a/thumbnail/1.jpg"))
        assertFalse(PeachCoverPick.looksLikeThumbnailOrPlaceholder("https://a/storage92000.contents.fc2.com/file/1.jpg"))
    }
}