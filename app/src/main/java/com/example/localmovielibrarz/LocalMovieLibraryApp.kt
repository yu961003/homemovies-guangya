package com.example.localmovielibrarz

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import com.example.localmovielibrarz.data.AppContainer
import com.example.localmovielibrarz.scraper.browser.ForegroundActivityTracker
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
    }

    private companion object {
        private const val TAG = "LocalMovieLibraryApp"
        private const val BUILD_MARKER = "BUILD_MARKER=2026-06-12-room-paged-dao-player-subtitles"
    }
}
