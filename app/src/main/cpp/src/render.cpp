#include <jni.h>
#include <stdio.h>

#include <mpv/client.h>

#include "jni_utils.h"
#include "log.h"
#include "globals.h"

extern "C" {
jni_func(jint, attachSurface, jobject surface);
jni_func(jint, detachSurface);
jni_func(jint, setSurfaceSize, jint width, jint height);
jni_func(jboolean, hasSurface);
jni_func(jint, ensureSurface);
}

// 供 main.cpp 在 mpv_initialize() 之后重放：本文件对外的 C++ 入口（不走 JNI）。
int apply_cached_surface_to_current_handle();

/**
 * 双播放器架构下 surface 的生命周期比播放内核长：
 * Compose 重组、Activity 旋转、引擎切换都会重挂 surface，而 mpv 实例可能被复用。
 * 因此这里所有调用都容忍「mpv 未初始化」与「重复挂载」，并返回错误码给 Kotlin，
 * 由 Kotlin 决定是重试还是等 surfaceCreated。
 */
static jobject g_surface = nullptr;

// wid 是**按句柄**生效的：mpv 把它存在该实例自己的 vo_opts 里。
// 原生 create() 遇到旧实例会先 destroy 再重建，新句柄的 wid 就是 0；
// 而 g_surface（本文件静态）与 Kotlin 的 surfacePresent（object 静态）都跨句柄存活，
// 于是出现「Kotlin 认为已挂载、mpv 认为没有 surface」——
// 正是 `[vo/gpu/android] Missing surface pointer` 与 `渲染面=已挂载` 并存的成因。
// 记下 wid 写进的是哪一个句柄，才能判断「现在这个句柄到底能不能出画面」。
static mpv_handle *g_surface_handle = nullptr;

// 把缓存的 surface 写成本句柄的 wid。
// 返回 mpv 错误码（0 = 已生效）；g_surface 为空时返回 0（无事可做）。
static int apply_wid_locked() {
    if (!g_surface) {
        g_surface_handle = nullptr;
        return 0;
    }
    if (!g_mpv) {
        g_surface_handle = nullptr;
        return MPV_ERROR_UNINITIALIZED_LOCAL;
    }

    int64_t wid = reinterpret_cast<intptr_t>(g_surface);
    int result = mpv_set_property(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0) {
        ALOGE("mpv_set_property(wid) 返回错误 %s", mpv_error_string(result));
        g_surface_handle = nullptr;
        return result;
    }

    // 回读只做诊断：set 成功但值没落地（例如在 initialize 之前调用）是可能的，
    // 这时上面那句会返回错误；如果 set 返回 0 而回读对不上，说明是别的时序问题，
    // 记一条日志即可，不因此把"已应用"改成"未应用"——那会让等待方永远等不到。
    int64_t readback = 0;
    if (mpv_get_property(g_mpv, "wid", MPV_FORMAT_INT64, &readback) >= 0 &&
        readback != wid) {
        ALOGW("wid 回读不一致：写入 %lld，读到 %lld",
              (long long) wid, (long long) readback);
    }

    g_surface_handle = g_mpv;
    return 0;
}

jni_func(jint, attachSurface, jobject surface) {
    // 没有句柄时照样把 surface 记下来：Compose 的 AndroidView 常常先于
    // createEngine 完成挂载，早到的 surface 必须留住，等 init 之后重放。
    if (g_surface) {
        ALOGW("attachSurface(): 旧 surface 未解绑，先解绑");
        env->DeleteGlobalRef(g_surface);
        g_surface = nullptr;
        g_surface_handle = nullptr;
    }

    g_surface = env->NewGlobalRef(surface);
    if (!g_surface) {
        ALOGE("attachSurface(): NewGlobalRef 失败");
        return -10;
    }

    if (!g_mpv) {
        ALOGV("attachSurface(): mpv 尚未创建，surface 已缓存，等 init 后重放");
        return 0;
    }

    // wid 是「运行期可写」属性：必须在 mpv_initialize 之后用 set_property 设置，
    // 用 set_option 会静默无效。
    return apply_wid_locked();
}

jni_func(jboolean, hasSurface) {
    if (!g_mpv || !g_surface_handle)
        return JNI_FALSE;
    // 句柄换了（destroy + create）就算没挂上——这一点正是旧实现漏掉的。
    return g_surface_handle == g_mpv ? JNI_TRUE : JNI_FALSE;
}

jni_func(jint, ensureSurface) {
    if (!g_surface)
        return 0;
    if (!g_mpv)
        return MPV_ERROR_UNINITIALIZED_LOCAL;
    if (g_surface_handle == g_mpv)
        return 0;
    ALOGW("ensureSurface(): 当前 mpv 句柄尚未持有 surface，重放 wid");
    return apply_wid_locked();
}

// main.cpp 在 mpv_initialize() 之后调用：initialize 之前的 set_property 不落地。
int apply_cached_surface_to_current_handle() {
    return apply_wid_locked();
}

jni_func(jint, detachSurface) {
    MPV_CHECK_INT();

    if (!g_surface) {
        ALOGV("detachSurface(): 当前没有挂载 surface");
        return 0;
    }

    int64_t wid = 0;
    int result = mpv_set_property(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0)
        ALOGE("mpv_set_property(wid=0) 返回错误 %s", mpv_error_string(result));
    g_surface_handle = nullptr;

    env->DeleteGlobalRef(g_surface);
    g_surface = nullptr;
    return result < 0 ? result : 0;
}

jni_func(jint, setSurfaceSize, jint width, jint height) {
    MPV_CHECK_INT();
    if (width <= 0 || height <= 0)
        return 0;

    char buf[32];
    snprintf(buf, sizeof(buf), "%dx%d", static_cast<int>(width), static_cast<int>(height));
    int result = mpv_set_property_string(g_mpv, "android-surface-size", buf);
    if (result < 0)
        ALOGV("android-surface-size=%s 设置失败: %s", buf, mpv_error_string(result));
    return result;
}