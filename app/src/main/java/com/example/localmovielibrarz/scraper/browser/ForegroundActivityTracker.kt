package com.example.localmovielibrarz.scraper.browser

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * 当前有没有一个「能给 WebView 当宿主」的 Activity。
 *
 * 为什么需要它：浏览器回落必须把 WebView 挂进一个真实存在的 Activity 才能跑 JS 挑战脚本
 * （见 [HiddenWebViewHolder] 里关于「为什么不能自建 Activity / 不能用悬浮窗」的说明）。
 * 而刮削可能在后台跑——批刮一开就是几百部，用户锁屏、切走都是常态。那种时候通道不可用，
 * 取数层应当**立刻**知道并采用「HTTP 已失败 + 无法回落」的结论，而不是等一个 60 秒超时。
 *
 * 只记 `resumed` 的那一个：`started` 但已 `paused` 的 Activity 不能挂 WebView（页面会被系统冻结，
 * 挑战脚本跑不完），把它算作可用会让取页卡在轮询里直到超时。
 */
object ForegroundActivityTracker : Application.ActivityLifecycleCallbacks {

    private var resumed: WeakReference<Activity>? = null

    private var installed = false

    /** 在 `Application.onCreate()` 里调一次。重复调用是安全的（不会重复注册）。 */
    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(this)
    }

    /** 当前的宿主 Activity；没有则 null。已 destroy 的引用会被顺手清掉。 */
    fun current(): Activity? {
        val activity = resumed?.get() ?: return null
        if (activity.isFinishing || activity.isDestroyed) {
            resumed = null
            return null
        }
        return activity
    }

    val hasForeground: Boolean get() = current() != null

    override fun onActivityResumed(activity: Activity) {
        resumed = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (resumed?.get() === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}