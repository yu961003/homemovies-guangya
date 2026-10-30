package com.example.localmovielibrarz.scraper

import okhttp3.ResponseBody
import okio.Buffer
import java.io.IOException

/**
 * 刮削器 HTTP 响应健壮性守卫（安全审查修复）。
 *
 * - [readLimited]：限量读取响应体，防止异常网站返回超长 HTML/JSON 导致 OOM。
 * - [requireSafeScrapeNumber]：番号白名单校验，防止特殊字符改写 URL 主机/路径（SSRF/路径注入）。
 *
 * 上限按来源分档，对齐 peach 的 `SiteConfig.page_limit`（`sources/base.py:190`，默认 4 MiB）：
 * `dmm` 1 MiB（`sources/dmm.py:37`）、`r18dev` 2 MiB（`sources/r18dev.py:34`）、其余 4 MiB。
 *
 * 之前是一刀切的 20MB(HTML) / 8MB(JSON)：这个数量级下限额基本不会触发，等于把「异常大响应」
 * 的防线让给了内存。按来源收紧到 peach 的量级后，正常页面仍远小于限额
 * （列表页几十到几百 KB），超限只可能是站点在灌东西。
 */
internal object ScraperResponseGuard {
    /** peach 的默认 `page_limit`：4 MiB（`sources/base.py:108/190`）。 */
    const val DEFAULT_PAGE_LIMIT_BYTES = 4L * 1024 * 1024

    /** `dmm` 的 `page_limit`：1 MiB（`sources/dmm.py:37`）。 */
    const val DMM_PAGE_LIMIT_BYTES = 1L * 1024 * 1024

    /** `r18dev` 的 `page_limit`：2 MiB（`sources/r18dev.py:34`）。 */
    const val R18DEV_PAGE_LIMIT_BYTES = 2L * 1024 * 1024

    /** `1pondo` 的 `page_limit`：1 MiB（peach `sources/onepondo.py` 的 `SiteConfig`）。 */
    const val ONEPONDO_PAGE_LIMIT_BYTES = 1L * 1024 * 1024

    /** `fc2` 的 `page_limit`：2 MiB（peach `sources/fc2.py` 的 `SiteConfig`）。 */
    const val FC2_PAGE_LIMIT_BYTES = 2L * 1024 * 1024

    /**
     * 该来源允许读取的最大响应字节数。
     *
     * `Dmm2` 走同一套 DMM 主域机型页，与 [ScrapeSource.Dmm] 同档；
     * `CustomJson` 是用户在设置里填的任意接口，按默认档——抬高它没有意义，
     * 只会让一个配错的地址把上限拉满。
     */
    fun pageLimitBytes(source: ScrapeSource): Long = when (source) {
        ScrapeSource.Dmm, ScrapeSource.Dmm2 -> DMM_PAGE_LIMIT_BYTES
        ScrapeSource.R18Dev -> R18DEV_PAGE_LIMIT_BYTES
        // 一本道的详情是一页 JSON（几 KB），1 MiB 已经宽出两个数量级；FC2 的文章页带播放器
        // 外壳与推荐位，2 MiB 与 peach 一致。AVBase 是 Next.js 整页（`__NEXT_DATA__` 里塞着
        // 全部商品），peach 没给它单独设限，走 4 MiB 默认档。
        ScrapeSource.OnePondo -> ONEPONDO_PAGE_LIMIT_BYTES
        ScrapeSource.Fc2 -> FC2_PAGE_LIMIT_BYTES
        else -> DEFAULT_PAGE_LIMIT_BYTES
    }

    /**
     * 限量读取响应体；超限抛 IOException（上限见 [pageLimitBytes]）。
     *
     * ★ 2026-10-30 根因修复：原实现是 `source.readByteArray(maxBytes + 1)`。
     * Okio 的 `readByteArray(byteCount)` 语义是「**恰好**读满 byteCount 个字节，
     * 读不满就抛 EOFException」——而我们传的 byteCount 是上限（1~4 MiB），
     * 正常页面（几十到几百 KB）永远读不满，于是**每一次**读正文都抛 EOFException。
     *
     * 这解释了真机日志里的两个反常现象：
     * 1. Official / DMM2 / AVBase 三个毫不相干的站点，同一时刻都报裸 `EOFException`
     *    （Okio 那个分支抛的异常没有 message，所以日志里只有一个类名）；
     * 2. 只有 TheJavDB 正常返回 404 —— 它走 `body.string()` / 只看状态码，不经过这里。
     *
     * 现在改成「读到一个字节都不剩为止」：`source.read(buffer, remaining)` 在流结束时
     * 返回 -1 而不是抛错；只有真的读到超过 maxBytes 才抛超限。
     */
    fun readLimited(body: ResponseBody, maxBytes: Long = DEFAULT_PAGE_LIMIT_BYTES): ByteArray {
        val source = body.source()
        val buffer = Buffer()
        var total = 0L
        while (true) {
            // 最多再读「上限 + 1 - 已读」，多出的那 1 字节就是超限证据。
            val read = source.read(buffer, maxBytes + 1 - total)
            if (read == -1L) break
            total += read
            if (total > maxBytes) {
                throw IOException("响应体超过 ${maxBytes / 1024 / 1024}MB 限制，已中止读取")
            }
        }
        return buffer.readByteArray()
    }

    /** 按来源限量读取响应体。 */
    fun readLimited(body: ResponseBody, source: ScrapeSource): ByteArray {
        val bytes = readLimited(body, pageLimitBytes(source))
        // 顺手记一笔字节数：刮削链的 `max_bytes` 闸门靠它（见 ScrapeByteMeter 的取舍说明）。
        // 记的是「实际读进来多少」，超限抛错的那次不记——那次没有读完，记一半只会让预算看起来更宽。
        ScrapeByteMeter.record(source, bytes.size.toLong())
        return bytes
    }

    /** 番号白名单：仅允许字母、数字与连字符，长度 1-40。异常格式直接抛错（正常番号如 ABC-123 均符合）。 */
    fun requireSafeScrapeNumber(number: String): String {
        val trimmed = number.trim()
        if (trimmed.isEmpty() || !trimmed.matches(SAFE_NUMBER)) {
            throw IllegalArgumentException("番号格式异常，无法安全搜索：$number")
        }
        return trimmed
    }

    private val SAFE_NUMBER = Regex("[A-Za-z0-9-]{1,40}")
}