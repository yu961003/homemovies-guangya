package com.example.localmovielibrarz.scraper

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 封面择优的**取数那一半**（peach `jav_cover_fetch.py` 的 Range 探测）。
 *
 * 判胜规则本身在 [PeachCoverPick] 里，是纯函数、有离线单测；这里只负责「量出一张图的像素」：
 * 带 `Range` 头只取头 64 KB，从字节里读文件头，再交给 [PeachCoverPick.pickBest] 挑。
 * 分两层的理由是量尺寸必须 `import okhttp3`，而一旦和判胜写在一起，这个文件就会被纯 JVM
 * 编译集排除——那意味着「角色优先于像素」这条最容易写反的规则失去离线回归。
 *
 * 为什么不把整张图下载下来量：一张 4K 封面动辄 2–6 MB，而一条链上可能有四五个候选。
 * peach 量尺寸只取 [PeachCoverPick.PROBE_BYTES]，正常 JPEG 的 SOF 段（尺寸所在）远在
 * 64 KB 之内；量不出来的候选按「未知尺寸」处理，不会因此把整条链拖死。
 */
internal class PeachCoverProbe(
    private val client: OkHttpClient,
    private val coverSource: ScrapeSource
) {

    /**
     * 量一张图的尺寸；取不到或认不出时回 [PeachCoverPick.Size.Unknown]。
     *
     * 这里**吞掉**所有异常：封面是可选字段，量不出尺寸只该让这张图落选，
     * 不该让整次刮削失败——正文都已经解析好了。
     */
    fun sizeOf(url: String): PeachCoverPick.Size {
        if (url.isBlank()) return PeachCoverPick.Size.Unknown
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ScrapeUserAgent.VALUE)
            .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
            .header("Range", "bytes=0-${PeachCoverPick.PROBE_BYTES - 1}")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body ?: return PeachCoverPick.Size.Unknown
                // 站点忽略 Range 时不读完整张图：`readLimited` 会在 64 KB 处停下并抛错，
                // 那次抛错被下面的 catch 接住，等价于「这张量不出来」。
                val header = ScraperResponseGuard.readLimited(body, PeachCoverPick.PROBE_BYTES.toLong())
                PeachCoverPick.sizeOf(header)
            }
        } catch (_: IOException) {
            PeachCoverPick.Size.Unknown
        } catch (_: IllegalArgumentException) {
            // URL 本身非法（站点给的是相对地址或空串）：同样只是这张落选。
            PeachCoverPick.Size.Unknown
        }
    }

    /**
     * 在一组封面地址里挑最终那一张；一个可用都没有时返回空串。
     *
     * 候选按到达顺序编号，角色一律记 [PeachCoverPick.CoverRole.Cover]——这三个源给的地址
     * 都已经是「作品图」，没有 peach 那种「剧照 vs 商品图」的区分；角色字段留着是为了
     * 将来某家给出多种图时不用改判胜逻辑。
     */
    fun pick(urls: List<String>): String {
        val candidates = urls
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            // 粗筛先剔掉 `ps.jpg`/`now_printing` 这类一眼就是缩略图或占位图的地址，
            // 省下的是一次 Range 请求。
            .filterNot { PeachCoverPick.looksLikeThumbnailOrPlaceholder(it) }
            .map { PeachCoverPick.Candidate(url = it, source = coverSource) }
        if (candidates.isEmpty()) return ""
        return PeachCoverPick.pickBest(candidates) { sizeOf(it) }?.url.orEmpty()
    }

    /**
     * 把挑出来的封面写进结果；挑不出来时保留解析器填的那张。
     *
     * 为什么保留原值而不是清空：解析器填的是「最像封面的那张」（通常是第一张），
     * 量不出尺寸时它仍是一张能用的图；清空会让用户从「有一张不确定的封面」变成「没有封面」。
     */
    fun applyTo(info: ScrapedMovieInfo, urls: List<String>): ScrapedMovieInfo {
        val picked = pick(urls)
        if (picked.isEmpty()) return info
        return info.copy(thumbUrl = picked, posterUrl = picked)
    }
}