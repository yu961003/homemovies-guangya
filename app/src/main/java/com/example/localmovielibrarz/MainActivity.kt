package com.example.localmovielibrarz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.example.localmovielibrarz.ui.LocalMovieLibraryAppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appContainer = (application as LocalMovieLibraryApp).container
        // 主题统一由 LocalMovieLibraryAppRoot 内的 HomeMovieTheme 提供（跟随用户设置）。
        // 此前这里还套了一层薄荷绿 AppTheme，与 ui/AppTheme.kt 的青蓝主题冲突且会被引擎覆盖，已移除。
        setContent {
            LocalMovieLibraryAppRoot(appContainer = appContainer)
        }
    }
}
