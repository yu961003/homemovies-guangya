package com.example.localmovielibrarz

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import com.example.localmovielibrarz.data.AppContainer
import com.example.localmovielibrarz.scraper.browser.ForegroundActivityTracker
import com.example.localmovielibrarz.subtitle.SubtitleLibraryRescanScheduler
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import android.os.SystemClock
import com.example.localmovielibrarz.ui.shared.clearUriImageMemoryCache
import com.example.localmovielibrarz.ui.shared.trimUriImageMemoryCache

class LocalMovieLibraryApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, BUILD_MARKER)
        // 浏览器回落通道要知道「现在有没有前台界面可以挂 WebView」。这个观察必须在这里装：
        // 它靠 registerActivityLifecycleCallbacks 收事件，装晚了（比如首次刮削时才装）就会
        // 漏掉「应用已经在前台」这个事实，导致第一次刮削永远拿不到浏览器通道。
        ForegroundActivityTracker.install(this)
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var startedAt = 0L
            override fun onStart(owner: LifecycleOwner) { startedAt = SystemClock.elapsedRealtime() }
            override fun onStop(owner: LifecycleOwner) {
                if (startedAt > 0L) container.usageStatsRepository.addAppMillis(SystemClock.elapsedRealtime() - startedAt)
                startedAt = 0L
            }
            override fun onDestroy(owner: LifecycleOwner) {
                // 审查 B1：进程退出时释放光鸭离线下载协程作用域，避免 ioScope 常驻泄漏
                container.guangyaOfflineMemory.dispose()
            }
        })
        // 审查 P2：此前全仓 0 处内存回收，48MB 图片缓存从不释放
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) = trimUriImageMemoryCache(level)
            override fun onLowMemory() = clearUriImageMemoryCache()
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        })
        // §5.2-7 字幕库自动增量重扫：登记一个 WorkManager 周期任务，把用户新拷进字幕库目录的
        // 字幕自动收进索引，不再依赖「设置页手动点重扫」（缺陷 B-2）。放在 onCreate 末尾，
        // 是因为 Worker 自己要从 container 里读配置与数据库，必须等 container 构造完再登记。
        // WorkManager 未初始化 / 厂商 ROM 限制都可能在登记时抛异常，Application 启动不能被
        // 一个后台任务拖垮，所以整体兜底：失败只记日志，下次启动会重新登记。
        try {
            SubtitleLibraryRescanScheduler.schedule(this)
        } catch (t: Throwable) {
            Log.w(TAG, "字幕库周期重扫调度失败", t)
        }
    }

    private companion object {
        private const val TAG = "LocalMovieLibraryApp"
        private const val BUILD_MARKER = "BUILD_MARKER=2026-06-12-room-paged-dao-player-subtitles"
    }
}
