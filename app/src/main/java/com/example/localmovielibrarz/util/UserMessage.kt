package com.example.localmovielibrarz.util

/**
 * 面向用户的错误文案。
 *
 * 此前多处直接把 `error.message ?: error::class.java.simpleName` 拼进提示语，
 * 用户会看到 `SocketTimeoutException`、`JSONException` 之类的类名。
 * 这里统一剥掉类名，只保留人类可读的部分。
 */
fun Throwable.userMessage(fallback: String = "操作失败"): String {
    val raw = message?.trim().orEmpty()
    if (raw.isBlank()) return fallback
    // OkHttp / java.io 常见噪声：只留最后一段有意义的描述
    val cleaned = raw.lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { raw }
    return if (cleaned.length > 120) cleaned.take(120) + "…" else cleaned
}
