package com.example.localmovielibrarz.scraper

import android.util.Log
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

/**
 * 刮削出口的 DNS：**IPv4 优先，并过滤掉污染投出来的不可路由 IPv6**。
 *
 * ## 为什么需要它
 *
 * 国内 DNS 对若干 AV 站点返回的是投毒结果：`s1s1s1.com` 在权威解析里只有 A 记录
 * （AWS 东京三台），但 `dns.alidns.com` 会额外给出 `2001::45c5:99b4` 这种
 * `2001::/32`（Teredo 保留段）的地址，外加一条不属于该站的 A 记录。
 * `2001::/32` 在互联网上不可路由，于是 OkHttp 的连接尝试必然卡到
 * `connectTimeout`（30 秒）才失败——日志里「连接 `2001::76b8:4e4e` 超时」就是这条路径。
 *
 * 这件事**必须在 DNS 层修**，不能靠调大超时：慢的根源不是站点慢，是地址本身是假的。
 *
 * ## 它做什么
 *
 * 1. 先用系统解析拿全部地址；
 * 2. 丢掉 `2001::/32`（Teredo）与全零/回环。
 * 3. IPv4 排在 IPv6 前面——同一台站两个协议族都通时，IPv4 的握手路径更短，
 *    而且国内出口对 IPv4 的连通性明显更好。
 *
 * ## 它不做什么
 *
 * **不过滤 IPv6**（只过滤 Teredo 段）：只有 IPv6 的站仍然能用，不会因为这条策略变慢或变坏。
 * **不替换系统 DNS**：DoH 需要额外依赖与跨境延迟评估，属于后续项（见
 * `docs/SCRAPE-FAILURE-ANALYSIS-20261030.md` §四 P2-11）；这一层只做「拿到地址之后怎么排队」。
 *
 * 过滤后若一个地址都不剩（极端情况：站点真的只有 Teredo），**回落到原始列表**——
 * 宁可让上层超时，也不要在这里抛 `UnknownHostException` 把一次刮削变成「域名不存在」。
 */
class PollutionSafeDns(
    private val delegate: Dns = Dns.SYSTEM
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = try {
            delegate.lookup(hostname)
        } catch (error: UnknownHostException) {
            // 解析失败原样上抛：上层把它归成 Network（可重试），与「域名写错」的 Config 不同。
            throw error
        }
        if (resolved.isEmpty()) return resolved

        val usable = resolved.filterNot { isUnroutable(it) }
        val pool = usable.ifEmpty { resolved }
        // stable sort：同一协议族内保持系统给的顺序（系统通常已按 RFC 6724 排过）。
        val ordered = pool.filter { it is java.net.Inet4Address } +
            pool.filterNot { it is java.net.Inet4Address }
        if (ordered.size != resolved.size || ordered != resolved) {
            Log.i(
                TAG,
                "DNS $hostname：系统给 ${resolved.size} 个地址，" +
                    "可用 ${ordered.size} 个（IPv4 优先，已剔除 Teredo/不可路由）"
            )
        }
        return ordered
    }

    /** `2001::/32`（Teredo）与全零地址：投毒结果最常见的两种形态。 */
    private fun isUnroutable(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress) return true
        val bytes = address.address
        if (bytes.size != 16) return false
        // 2001:0000::/32 —— 前两字节 0x20 0x01，且第 3、4 字节为 0。
        return (bytes[0].toInt() and 0xFF) == 0x20 &&
            (bytes[1].toInt() and 0xFF) == 0x01 &&
            bytes[2].toInt() == 0 &&
            bytes[3].toInt() == 0
    }

    private companion object {
        const val TAG = "PollutionSafeDns"
    }
}
