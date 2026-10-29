package com.example.localmovielibrarz.scraper

import com.example.localmovielibrarz.data.repository.AppSettingsRepository
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

/**
 * 刮削出口的代理选择器：按 [ScrapeProxyMode] 三档分派，语义对齐 peach 的
 * `peach_proxy.client_options()`（environment / direct / proxy）。
 *
 * 关键约束（踩过的坑写在前面）：
 * 1. **不能返回空列表**。OkHttp 拿到 `select()` 的空列表会直接抛
 *    `SocketException: No route to host`，而不是回落直连。三个分支都必须保证非空。
 * 2. **不能吞掉系统代理**。旧实现在「开关关」时返回 `NO_PROXY`，等于显式直连，
 *    Wi-Fi 代理与 VPN 下发代理全部失效——这正是「所有刮削都报网络错误」的根因所在。
 * 3. 跟随系统时**每次连接都重新问一次** `ProxySelector.getDefault()`：系统代理可能在
 *    运行期间变化（切换 Wi-Fi、VPN 起落），缓存它会让改动不生效。
 */
class ScrapeProxySelector(
    private val settingsRepository: AppSettingsRepository
) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> = when (settingsRepository.getScrapeProxyMode()) {
        ScrapeProxyMode.Direct -> listOf(Proxy.NO_PROXY)
        ScrapeProxyMode.Proxy -> listOf(settingsRepository.getScrapeProxy() ?: systemProxy(uri))
        ScrapeProxyMode.Environment -> listOf(systemProxy(uri))
    }

    /**
     * 系统代理；拿不到（无默认选择器、选择器抛异常、返回空列表）时回落直连。
     *
     * 这里必须自己兜底：`ProxySelector.getDefault()` 在某些运行环境下返回 `null`，
     * 而它的 `select` 也可能返回空列表或抛 [IOException]。任何一种都不能把异常抛给 OkHttp。
     */
    private fun systemProxy(uri: URI?): Proxy {
        val target = uri ?: return Proxy.NO_PROXY
        val selector = ProxySelector.getDefault() ?: return Proxy.NO_PROXY
        return runCatching { selector.select(target).firstOrNull() }.getOrNull() ?: Proxy.NO_PROXY
    }

    override fun connectFailed(uri: URI?, sa: java.net.SocketAddress?, ioe: IOException?) = Unit
}