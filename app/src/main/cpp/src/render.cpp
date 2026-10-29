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
}

/**
 * 双播放器架构下 surface 的生命周期比播放内核长：
 * Compose 重组、Activity 旋转、引擎切换都会重挂 surface，而 mpv 实例可能被复用。
 * 因此这里所有调用都容忍「mpv 未初始化」与「重复挂载」，并返回错误码给 Kotlin，
 * 由 Kotlin 决定是重试还是等 surfaceCreated。
 */
static jobject g_surface = nullptr;

jni_func(jint, attachSurface, jobject surface) {
    MPV_CHECK_INT();

    if (g_surface) {
        ALOGW("attachSurface(): 旧 surface 未解绑，先解绑");
        env->DeleteGlobalRef(g_surface);
        g_surface = nullptr;
    }

    g_surface = env->NewGlobalRef(surface);
    if (!g_surface) {
        ALOGE("attachSurface(): NewGlobalRef 失败");
        return -10;
    }

    // wid 是「运行期可写」属性：必须在 mpv_initialize 之后用 set_property 设置，
    // 用 set_option 会静默无效。
    int64_t wid = reinterpret_cast<intptr_t>(g_surface);
    int result = mpv_set_property(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
    if (result < 0) {
        ALOGE("mpv_set_property(wid) 返回错误 %s", mpv_error_string(result));
        return result;
    }
    return 0;
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