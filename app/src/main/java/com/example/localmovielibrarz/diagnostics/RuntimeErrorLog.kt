package com.example.localmovielibrarz.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RuntimeErrorLog(context: Context) {
    private val appContext = context.applicationContext
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    fun append(event: String, details: Map<String, String?> = emptyMap(), error: Throwable? = null) {
        val text = buildString {
            append('[').append(timestamp()).append("] ").append(event).append('\n')
            details.forEach { (key, value) ->
                append("  ").append(key).append(": ").append(value.orEmpty()).append('\n')
            }
            if (error != null) {
                append("  errorType: ").append(error::class.java.name).append('\n')
                append("  errorMessage: ").append(error.message.orEmpty()).append('\n')
                append("  stack: ").append(error.stackTraceToString().lineSequence().take(24).joinToString("\\n")).append('\n')
            }
            append('\n')
        }
        writerScope.launch {
            mutex.withLock {
                try {
                    val file = logFile()
                    file.parentFile?.mkdirs()
                    file.appendText(text, Charsets.UTF_8)
                    trimIfNeeded(file)
                } catch (error: Throwable) {
                    // 写盘失败不崩溃：记到系统日志，避免 IOException 直达未捕获处理器
                    Log.w("RuntimeErrorLog", "写入运行时错误日志失败", error)
                }
            }
        }
    }

    fun filePath(): String = logFile().absolutePath

    fun clear() {
        writerScope.launch {
            mutex.withLock {
                try {
                    logFile().delete()
                } catch (error: Throwable) {
                    Log.w("RuntimeErrorLog", "清空运行时错误日志失败", error)
                }
            }
        }
    }

    private fun logFile(): File =
        File(appContext.filesDir, "diagnostics/runtime-errors.log")

    private fun trimIfNeeded(file: File) {
        try {
            if (!file.exists() || file.length() <= MAX_BYTES) return
            val text = file.readText(Charsets.UTF_8)
            val trimmed = text.takeLast(TRIM_TO_CHARS)
            file.writeText(trimmed, Charsets.UTF_8)
        } catch (_: Throwable) {
            // 裁剪失败不影响追加日志功能
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())

    private companion object {
        const val MAX_BYTES = 512 * 1024
        const val TRIM_TO_CHARS = 256 * 1024
    }
}
