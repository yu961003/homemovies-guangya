package com.example.localmovielibrarz.scraper

/**
 * 封面择优（peach `jav_cover_fetch.py` 的鸭影版）。
 *
 * peach 的做法值得照搬的地方只有一处：**候选不是靠 URL 猜大小，而是真的去图片头部量像素**。
 * 它用 Range 请求只取前 64 KB（`PROBE_BYTES`），从文件头读出宽高，然后按
 * 「角色（product > cover > preview）优先，像素次之」判谁更好。这样做的原因是社区站给的
 * 「封面」经常是 200×300 的缩略图或「准备中」占位图，而 URL 里看不出区别——按 URL 打分等于猜。
 *
 * 本对象只放**纯逻辑**：文件头量尺寸、候选打分、是否值得换。Range 请求与下载留在
 * `PeachCoverFetcher`（那个文件 import okhttp3，不参与 pure-JVM 编译）。
 *
 * 为什么不用 `BitmapFactory`：那份实现要 Android 运行时，纯 JVM 单测跑不了；
 * 而 JPEG/PNG/GIF/WebP 的尺寸都写在文件头的前几十字节里，自己解析反而是**可测且更省**的做法
 * （BitmapFactory 会把整张图解码进内存）。
 */
object PeachCoverPick {

    /** peach 的 `PROBE_BYTES`：量尺寸只需这么多字节，正常 JPEG 的 SOF 段远在 64 KB 之内。 */
    const val PROBE_BYTES = 64 * 1024

    /**
     * 候选的**角色**：数字越大越该留。
     *
     * 与 peach 的 `_CANDIDATE_QUALITY` 同序（preview=0 / cover=1 / product=2）。
     * 角色比像素更硬：一张 2000px 的剧照（preview）也不该顶掉官方的商品图（product），
     * 因为剧照常常是别的片的截图或粉丝加工图。
     */
    enum class CoverRole(val quality: Int, val label: String) {
        Preview(0, "预览图"),
        Cover(1, "封面"),
        Product(2, "商品图")
    }

    /** 一个封面候选。 */
    data class Candidate(
        val url: String,
        val role: CoverRole = CoverRole.Cover,
        val source: ScrapeSource
    )

    /** 量出来的尺寸；量不出来时用 [Unknown]。 */
    sealed interface Size {
        /** 宽×高（像素）。 */
        data class Known(val width: Int, val height: Int) : Size {
            val pixels: Int get() = width * height
        }

        /** 取不到或认不出文件头。 */
        data object Unknown : Size
    }

    /**
     * 从图片字节的前若干字节里读尺寸。
     *
     * 支持 JPEG（SOF0/1/2/3/5/6/7/9/10/11/13/14/15）、PNG、GIF、WebP。
     * 这四种之外一律 [Size.Unknown]——**不要**退回「按体积估」：体积与像素不相关
     * （高压缩率的剧照可以比低压缩率的封面更大），那会给出假精确的结果。
     */
    fun sizeOf(header: ByteArray): Size {
        if (header.size < 16) return Size.Unknown
        return when {
            isPng(header) -> pngSize(header)
            isJpeg(header) -> jpegSize(header)
            isGif(header) -> gifSize(header)
            isWebp(header) -> webpSize(header)
            else -> Size.Unknown
        }
    }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.u16be(index: Int): Int = (u8(index) shl 8) or u8(index + 1)

    private fun ByteArray.u16le(index: Int): Int = u8(index) or (u8(index + 1) shl 8)

    private fun ByteArray.u24le(index: Int): Int = u8(index) or (u8(index + 1) shl 8) or (u8(index + 2) shl 16)

    private fun ByteArray.u32be(index: Int): Long =
        ((u8(index).toLong() shl 24) or (u8(index + 1).toLong() shl 16) or
            (u8(index + 2).toLong() shl 8) or u8(index + 3).toLong())

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes.u8(0) == 0x89 && bytes.u8(1) == 0x50 &&
            bytes.u8(2) == 0x4E && bytes.u8(3) == 0x47

    private fun pngSize(bytes: ByteArray): Size {
        if (bytes.size < 24) return Size.Unknown
        // IHDR 固定是第一个块：4 字节长度 + "IHDR" + 宽 4 + 高 4（都是大端）。
        val width = bytes.u32be(16)
        val height = bytes.u32be(20)
        return if (width in 1..100_000 && height in 1..100_000) {
            Size.Known(width.toInt(), height.toInt())
        } else {
            Size.Unknown
        }
    }

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.u8(0) == 0xFF && bytes.u8(1) == 0xD8

    private fun jpegSize(bytes: ByteArray): Size {
        var index = 2
        // 逐个段扫：FF xx len(2) payload(len-2)，直到撞上 SOFn。
        while (index + 9 < bytes.size) {
            if (bytes.u8(index) != 0xFF) {
                index += 1
                continue
            }
            val marker = bytes.u8(index + 1)
            // 填充字节与无长度的 standalone marker。
            if (marker == 0xFF) {
                index += 1
                continue
            }
            if (marker == 0xD8 || marker == 0x01 || (marker in 0xD0..0xD7)) {
                index += 2
                continue
            }
            val length = bytes.u16be(index + 2)
            if (length < 2) return Size.Unknown
            val isSof = marker in setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)
            if (isSof) {
                if (index + 9 >= bytes.size) return Size.Unknown
                val height = bytes.u16be(index + 5)
                val width = bytes.u16be(index + 7)
                return if (width in 1..100_000 && height in 1..100_000) {
                    Size.Known(width, height)
                } else {
                    Size.Unknown
                }
            }
            index += 2 + length
        }
        return Size.Unknown
    }

    private fun isGif(bytes: ByteArray): Boolean =
        bytes.size >= 6 && bytes.u8(0) == 0x47 && bytes.u8(1) == 0x49 && bytes.u8(2) == 0x46

    private fun gifSize(bytes: ByteArray): Size {
        if (bytes.size < 10) return Size.Unknown
        // GIF 的逻辑屏幕尺寸是小端 2 字节。
        val width = bytes.u16le(6)
        val height = bytes.u16le(8)
        return if (width > 0 && height > 0) Size.Known(width, height) else Size.Unknown
    }

    /** 本工程能认的 WebP 变体。 */
    private enum class WebpKind { Lossy, Lossless, Extended }

    private fun isWebp(bytes: ByteArray): Boolean =
        bytes.size >= 12 && bytes.u8(0) == 0x52 && bytes.u8(1) == 0x49 &&
            bytes.u8(2) == 0x46 && bytes.u8(3) == 0x46 &&
            bytes.u8(8) == 0x57 && bytes.u8(9) == 0x45 &&
            bytes.u8(10) == 0x42 && bytes.u8(11) == 0x50

    private fun webpKind(bytes: ByteArray): WebpKind? {
        if (bytes.size < 16) return null
        return when {
            bytes.u8(12) == 0x56 && bytes.u8(13) == 0x50 && bytes.u8(14) == 0x38 && bytes.u8(15) == 0x20 -> WebpKind.Lossy
            bytes.u8(12) == 0x56 && bytes.u8(13) == 0x50 && bytes.u8(14) == 0x38 && bytes.u8(15) == 0x4C -> WebpKind.Lossless
            bytes.u8(12) == 0x56 && bytes.u8(13) == 0x50 && bytes.u8(14) == 0x38 && bytes.u8(15) == 0x58 -> WebpKind.Extended
            else -> null
        }
    }

    private fun webpSize(bytes: ByteArray): Size {
        return when (webpKind(bytes)) {
            WebpKind.Lossy -> {
                // VP8：帧头之后 2 字节起始码 0x9D 0x01，再 2+2 字节宽高（14 位）。
                if (bytes.size < 30) return Size.Unknown
                if (bytes.u8(23) != 0x9D || bytes.u8(24) != 0x01 || bytes.u8(25) != 0x2A) return Size.Unknown
                val width = bytes.u16le(26) and 0x3FFF
                val height = bytes.u16le(28) and 0x3FFF
                if (width > 0 && height > 0) Size.Known(width, height) else Size.Unknown
            }

            WebpKind.Lossless -> {
                if (bytes.size < 25) return Size.Unknown
                // VP8L：签名 0x2F 之后 14 位宽、14 位高（各减 1）。
                if (bytes.u8(20) != 0x2F) return Size.Unknown
                val bits = bytes.u8(21) or (bytes.u8(22) shl 8) or (bytes.u8(23) shl 16) or (bytes.u8(24) shl 24)
                val width = (bits and 0x3FFF) + 1
                val height = ((bits shr 14) and 0x3FFF) + 1
                Size.Known(width, height)
            }

            WebpKind.Extended -> {
                if (bytes.size < 30) return Size.Unknown
                // VP8X：24 位减一的宽高，跟在 4 字节 flags 之后。
                val width = bytes.u24le(24) + 1
                val height = bytes.u24le(27) + 1
                Size.Known(width, height)
            }

            null -> Size.Unknown
        }
    }

    /**
     * 新候选是否比当前最好的一份更值得留（peach 的 `candidate_improves`）。
     *
     * 判据与 peach 一致：**角色严格更好**或**像素严格更多**，二者满足其一即换。
     * 用「严格」而不是「≥」是为了让先到的那份在打平时留下——链是按层级排好的，
     * 前面那家本来就该赢，换成后来的只会让结果随网络快慢漂移。
     */
    fun improves(candidate: Candidate, candidateSize: Size, best: Candidate, bestSize: Size): Boolean {
        if (candidate.role.quality > best.role.quality) return true
        if (candidate.role.quality < best.role.quality) return false
        val candidatePixels = (candidateSize as? Size.Known)?.pixels ?: return false
        val bestPixels = (bestSize as? Size.Known)?.pixels ?: return true
        return candidatePixels > bestPixels
    }

    /**
     * 从一组候选里挑最终的封面 URL；一个可用都没有时返回 null。
     *
     * `sizeOf` 由调用方注入（真机上它是「Range 取 64 KB 再量头」）——这样本函数保持纯逻辑，
     * 单测可以直接给假尺寸，不必联网。
     */
    fun pickBest(
        candidates: List<Candidate>,
        sizeOf: (String) -> Size
    ): Candidate? {
        var best: Candidate? = null
        var bestSize: Size = Size.Unknown
        for (candidate in candidates) {
            if (candidate.url.isBlank()) continue
            val size = sizeOf(candidate.url)
            // 量不出尺寸的候选只在「还没有任何候补」时占位，之后一律让位——
            // 它不是「更好」，只是「可能是唯一一个能用的」。
            if (best == null) {
                best = candidate
                bestSize = size
                continue
            }
            if (improves(candidate, size, best, bestSize)) {
                best = candidate
                bestSize = size
            }
        }
        return best
    }

    /**
     * 这个 URL 看起来是不是缩略图/占位图（peach 的 `THUMBNAIL` / `PLACEHOLDER`）。
     *
     * 这只是**粗筛**，不参与判胜——真正的判据是量出来的像素。它存在的意义是省一次
     * Range 请求：站点给的 `ps.jpg` / `now_printing` 一眼就知道不必去量。
     */
    fun looksLikeThumbnailOrPlaceholder(url: String): Boolean {
        val lower = url.lowercase()
        if (PLACEHOLDER_MARKERS.any { lower.contains(it) }) return true
        return THUMBNAIL_MARKERS.any { lower.contains(it) }
    }

    private val PLACEHOLDER_MARKERS = listOf("now_printing", "nowprinting", "no-image", "noimage", "dummy")

    private val THUMBNAIL_MARKERS = listOf("/thumb", "thumbnail", "/small", "/icon", "/ts/", "-s.jpg", "_s.jpg", "ps.jpg")
}