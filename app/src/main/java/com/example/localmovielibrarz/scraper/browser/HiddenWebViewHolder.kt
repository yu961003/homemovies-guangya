package com.example.localmovielibrarz.scraper.browser

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import kotlinx.coroutines.sync.Mutex
import kotlin.math.roundToInt

/**
 * 进程内唯一的「隐藏 WebView」持有者。
 *
 * ## 为什么是这个形状
 *
 * **为什么必须挂进一个真实 Activity**：WebView 需要 `Context` 与一个可见的绘制宿主。三种
 * 「不挂在 Activity 上」的做法都行不通：
 * - 自建一个透明 `Activity`：要新增 `<activity>`、要处理它自己的生命周期与返回栈，用户按返回
 *   就会把它拆掉，而挑战还在跑；且后台启动 Activity 在 Android 10+ 直接受限。
 * - `WindowManager` 悬浮窗：需要 `SYSTEM_ALERT_WINDOW`，本工程没有这个权限，也不该为刮削去申请
 *   「显示在其他应用上层」——那是用户会警惕的权限。
 * - `GONE` / `INVISIBLE` 藏起来：这是**最容易踩的坑**。不可见的 WebView 会被系统与 Chromium
 *   判定为「后台」，`requestAnimationFrame` 停摆、`setTimeout` 被降频到分钟级，Cloudflare 的
 *   挑战脚本跑不完，于是永远停在「正在验证」。所以这里用 **1×1 px + `alpha = 0f`**——
 *   在布局上它「可见」、渲染管线照常工作，但用户看不见。需要人点时把它放大到 320×480 dp
 *   并恢复 `alpha = 1f`（见 [Lease.promote]）。
 *
 * **为什么是单例**：WebView 有进程级开销（渲染进程、Cookie 存储），一次刮削里可能要回落十几次。
 * 每次新建/销毁既慢又会把已通过的挑战 Cookie 丢掉——那正好是回落最值钱的东西。
 *
 * **为什么串行**：同一个 WebView 不能同时导航两条 URL，两次取页交叠会让两边都读错页。
 * [acquire] 用一把 [Mutex] 保证一次只有一个调用方在用。
 *
 * 除 [close] 外，所有对外方法都必须在主线程调用。
 */
object HiddenWebViewHolder {

    /** 正常尺寸：1×1 px 缩在角落。 */
    private const val REST_SIZE_PX = 1

    /** 弹窗尺寸（dp）。足够放下 Cloudflare 的勾选框与 Turnstile iframe。 */
    private const val PROMOTED_WIDTH_DP = 320
    private const val PROMOTED_HEIGHT_DP = 480

    private val mainHandler = Handler(Looper.getMainLooper())

    private val leaseMutex = Mutex()

    private var host: WebViewHolderHost? = null

    private var idleToken = 0

    /**
     * 一次性取页的租约。
     *
     * 用完必须 [release]，否则下一次取页会永久等待。因为持有 WebView，租约同时承担
     * 「弹出/收起窗口」的职责——调用方不该直接碰 WebView。
     */
    // 普通嵌套类，不是 inner：Lease 只是「借出一个 WebView 的一次性票据」，行为全部委托给
    // 外层单例（见下方对 HiddenWebViewHolder.xxx 的限定调用），不需要持有外层实例。
    // 改成 inner 会要求在 object 里构造外部实例，而 object 本身不可实例化——这正是原来的编译错误。
    class Lease internal constructor(private val host: WebViewHolderHost) {

        val webView: WebView get() = host.webView

        /** 把窗口提到前台让人点验证。 */
        fun promote() {
            HiddenWebViewHolder.promoteHost(host)
        }

        /** 收起窗口。取页结束时调。幂等。 */
        fun release() {
            HiddenWebViewHolder.mainHandler.post {
                HiddenWebViewHolder.demoteHost(host)
                HiddenWebViewHolder.scheduleIdleClose()
                val mutex = HiddenWebViewHolder.leaseMutex
                if (mutex.isLocked) mutex.unlock()
            }
        }
    }

    /**
     * 取一个可用的 WebView。没有前台 Activity 或 WebView 初始化失败返回 null。
     *
     * 必须在主线程调用（WebView 只能在主线程创建）。等待前一个租约释放时用的是挂起等待，
     * 不会阻塞界面。
     */
    suspend fun acquire(activity: Activity): Lease? {
        leaseMutex.lock()
        var handedOff = false
        try {
            val current = host
            val reusable = current != null && current.activity === activity &&
                current.container.parent === activity.findViewById<ViewGroup>(android.R.id.content)
            val leased = if (reusable) reattach(current!!) else {
                closeInternal()
                create(activity)
            }
            if (leased == null) return null
            leased.webView.loadUrl("about:blank")
            idleToken++
            handedOff = true
            return Lease(leased)
        } finally {
            if (!handedOff && leaseMutex.isLocked) leaseMutex.unlock()
        }
    }

    /** 立刻销毁。进程退出或用户关掉通道时调用。可以在任意线程调。 */
    fun close() {
        mainHandler.post { closeInternal() }
    }

    private fun promoteHost(host: WebViewHolderHost) {
        checkMain()
        demoteHost(host)
        val density = host.activity.resources.displayMetrics.density
        val params = FrameLayout.LayoutParams(
            (PROMOTED_WIDTH_DP * density).roundToInt(),
            (PROMOTED_HEIGHT_DP * density).roundToInt()
        )
        // 靠右下角：不挡返回键与标题，也不盖住用户自己可能正在看的页面主体。
        params.gravity = Gravity.END or Gravity.BOTTOM
        params.rightMargin = (12 * density).roundToInt()
        params.bottomMargin = (12 * density).roundToInt()
        host.container.layoutParams = params
        host.container.alpha = 1f
        host.container.isClickable = true
        host.webView.isFocusable = true
        host.webView.isClickable = true
        // 这行是「用户真的能点」的关键：alpha=1 但 isEnabled=false 的 WebView 会吞掉触摸。
        host.webView.isEnabled = true
        host.container.bringToFront()
        host.container.requestFocus()
    }

    private fun demoteHost(host: WebViewHolderHost) {
        checkMain()
        host.container.layoutParams = FrameLayout.LayoutParams(REST_SIZE_PX, REST_SIZE_PX)
        host.container.alpha = 0f
        host.container.isClickable = false
        host.webView.isFocusable = false
        host.webView.isClickable = false
    }

    private fun create(activity: Activity): WebViewHolderHost? {
        checkMain()
        // 宿主根：用 content 的父层还是 content 本身？用 content 自身的子层。
        // 往 content 里直接 addView 会盖在用户界面上（虽然 alpha=0 看不见），但只要尺寸是 1×1
        // 且不抢焦点，就既不影响布局测量，也不影响点击命中。
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        val webView = try {
            WebView(activity).apply {
                @Suppress("SetJavaScriptEnabled")
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.loadsImagesAutomatically = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                // UA 用系统默认：Android System WebView 的默认串已经带 "Android ... Mobile"，
                // 与真实浏览器一致；自造 UA 反而更容易被挑战页判成异常客户端。
                setBackgroundColor(Color.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
            }
        } catch (error: Throwable) {
            // 系统 WebView 被禁用 / 缺失时构造会抛。这一层捕获是为了让「通道不可用」成为
            // 一个明确的返回值，而不是让整次刮削带着堆栈崩掉。
            return null
        }
        val container = FrameLayout(activity).apply {
            alpha = 0f
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(REST_SIZE_PX, REST_SIZE_PX)
        }
        container.addView(webView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(container)
        // Cookie 策略：挑战通过后站点会发 `cf_clearance`，它必须被存下来，否则下一次回落
        // 又要从挑战开始。这里打开第三方 Cookie，因为挑战 iframe 常常挂在另一个域上。
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        val created = WebViewHolderHost(activity, container, webView)
        host = created
        return created
    }

    private fun reattach(existing: WebViewHolderHost): WebViewHolderHost? {
        checkMain()
        val root = existing.activity.findViewById<ViewGroup>(android.R.id.content) ?: return null
        if (existing.container.parent !== root) {
            (existing.container.parent as? ViewGroup)?.removeView(existing.container)
            root.addView(existing.container)
        }
        return existing
    }

    private fun closeInternal() {
        checkMain()
        val current = host ?: return
        host = null
        (current.container.parent as? ViewGroup)?.removeView(current.container)
        current.container.removeAllViews()
        current.webView.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            destroy()
        }
        // WebView 释放后必须让 Cookie 落盘，否则刚拿到的 cf_clearance 会随进程退出一起没。
        CookieManager.getInstance().flush()
    }

    /**
     * 空闲回收。用 token 而不是「先取消再重新 post」：每次取页都会 `++`，到点时只有
     * 「最后一次取页之后没人动过」才会真的关，避免长批刮里 WebView 被反复重建。
     */
    private fun scheduleIdleClose() {
        val token = ++idleToken
        mainHandler.postDelayed({
            if (token == idleToken) closeInternal()
        }, BrowserChallengeTimeline.IDLE_MILLIS)
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "HiddenWebViewHolder 只能在主线程调用"
        }
    }
}

/**
 * 一个 WebView 连同它在宿主 Activity 里的包装层。
 *
 * 独立成顶层类而不是 `HiddenWebViewHolder` 的内部私有类：`Lease` 是 public 的，
 * 它持有的类型不能比它更私密，否则 Kotlin 会拒编译（public API 暴露 private 类型）。
 */
internal class WebViewHolderHost(
    val activity: Activity,
    val container: FrameLayout,
    val webView: WebView
)