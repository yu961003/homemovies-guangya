#include <jni.h>
#include <stdlib.h>
#include <stdio.h>
#include <stdarg.h>
#include <string.h>
#include <time.h>
#include <locale.h>
#include <atomic>
#include <dlfcn.h>

#include <mpv/client.h>

#include <pthread.h>

#include "log.h"
#include "jni_utils.h"
#include "event.h"
#include "globals.h"

// render.cpp：把已缓存的 Surface 重放成本句柄的 wid（见该文件里的注释）。
int apply_cached_surface_to_current_handle();
void invalidate_surface_handle();

#define ARRAYLEN(a) (sizeof(a) / sizeof(a[0]))

extern "C" {
jni_func(jboolean, isAvailable);
jni_func(jboolean, isHwdecBridgeAvailable);
jni_func(jint, create, jobject appctx);
jni_func(jint, init);
jni_func(void, destroy);
jni_func(void, command, jobjectArray jarray);
jni_func(jstring, lastError);
}

JavaVM *g_vm = nullptr;
mpv_handle *g_mpv = nullptr;
std::atomic<bool> g_event_thread_request_exit(false);
jobject g_appctx = nullptr;

static pthread_t event_thread_id;
static std::atomic<bool> g_event_thread_started(false);

// 最近一次失败的原因，供 Kotlin 侧组装面向用户的错误文案。
static char g_last_error[512] = {0};

static void set_last_error(const char *fmt, ...)
{
    va_list args;
    va_start(args, fmt);
    vsnprintf(g_last_error, sizeof(g_last_error), fmt, args);
    va_end(args);
    ALOGE("%s", g_last_error);
}

/**
 * Android 上 mediacodec 硬解这条路要能走通，FFmpeg 必须拿到 JavaVM 与 AppContext。
 *
 * 不直接链接 libavcodec：libmpv.so 的 FFmpeg 兄弟库 soname 随构建版本变化
 * （libavcodec.so / libavcodec.so.61 …），链接期强依赖会让「换了 FFmpeg 版本」
 * 直接编译失败。改用 dlopen + dlsym，符号拿不到时只是硬解不可用，软解照样跑。
 */
typedef int (*av_jni_set_java_vm_t)(void *vm, void *log_ctx);
typedef int (*av_jni_set_android_app_ctx_t)(void *app_ctx, void *log_ctx);

static std::atomic<bool> g_hwdec_bridge_ok(false);

static void bridge_ffmpeg_jni(jobject appctx)
{
    static const char *kCandidates[] = {
            "libavcodec.so",
            "libavcodec.so.62",
            "libavcodec.so.61",
            "libavcodec.so.60",
    };

    void *handle = nullptr;
    for (const char *name : kCandidates) {
        handle = dlopen(name, RTLD_NOW | RTLD_GLOBAL);
        if (handle)
            break;
    }
    if (!handle) {
        set_last_error("dlopen(libavcodec) 失败，硬解不可用：%s", dlerror());
        return;
    }

    auto set_vm = reinterpret_cast<av_jni_set_java_vm_t>(dlsym(handle, "av_jni_set_java_vm"));
    if (!set_vm) {
        set_last_error("dlsym(av_jni_set_java_vm) 失败，硬解不可用");
        return;
    }
    set_vm(g_vm, nullptr);

    auto set_ctx = reinterpret_cast<av_jni_set_android_app_ctx_t>(
            dlsym(handle, "av_jni_set_android_app_ctx"));
    if (set_ctx && appctx)
        set_ctx(appctx, nullptr);

    g_hwdec_bridge_ok = true;
    ALOGV("FFmpeg JNI 桥接完成，mediacodec 硬解可用");
}

static int prepare_environment(JNIEnv *env, jobject appctx)
{
    setlocale(LC_NUMERIC, "C");

    g_vm = nullptr;
    if (env->GetJavaVM(&g_vm) != JNI_OK || !g_vm) {
        set_last_error("GetJavaVM 失败");
        return -1;
    }

    if (g_appctx) {
        env->DeleteGlobalRef(g_appctx);
        g_appctx = nullptr;
    }
    if (appctx)
        g_appctx = env->NewGlobalRef(appctx);

    bridge_ffmpeg_jni(g_appctx);
    init_methods_cache(env);
    return 0;
}

/**
 * 销毁 mpv 实例（不含 JNI 全局引用）。
 * 抽成普通函数，让 destroy() 与 create() 的「重复创建」分支复用同一套收尾逻辑。
 */
static void destroy_mpv_locked()
{
    if (!g_mpv)
        return;

    if (g_event_thread_started) {
        g_event_thread_request_exit = true;
        mpv_wakeup(g_mpv);
        pthread_join(event_thread_id, nullptr);
        g_event_thread_started = false;
    }

    mpv_terminate_destroy(g_mpv);
    g_mpv = nullptr;
    // 句柄没了，surface 的归属记录也跟着失效：mpv_create() 常复用同一块地址，
    // 留着旧指针会让 hasSurface() 对新句柄误判为「已挂载」。
    invalidate_surface_handle();
}

jni_func(jboolean, isAvailable) {
    return MPV_AVAILABLE ? JNI_TRUE : JNI_FALSE;
}

jni_func(jboolean, isHwdecBridgeAvailable) {
    return g_hwdec_bridge_ok ? JNI_TRUE : JNI_FALSE;
}

jni_func(jstring, lastError) {
    if (g_last_error[0] == 0)
        return nullptr;
    return env->NewStringUTF(g_last_error);
}

jni_func(jint, create, jobject appctx) {
    // 引擎切换时可能出现旧实例尚未回收的瞬间：先收尾再重建，不抛错。
    if (g_mpv) {
        ALOGW("create(): 已存在 mpv 实例，先销毁旧实例");
        destroy_mpv_locked();
    }

    if (prepare_environment(env, appctx) != 0)
        return -1;

    g_mpv = mpv_create();
    if (!g_mpv) {
        set_last_error("mpv_create() 返回 NULL");
        return -2;
    }

    mpv_request_log_messages(g_mpv, "terminal-default");
    if (mpv_set_option_string(g_mpv, "msg-level", "all=info") < 0)
        ALOGW("设置 msg-level 失败");

    return 0;
}

jni_func(jint, init) {
    MPV_CHECK_INT();
    if (mpv_initialize(g_mpv) < 0) {
        set_last_error("mpv_initialize() 失败");
        return -3;
    }

    g_event_thread_request_exit = false;
    if (pthread_create(&event_thread_id, nullptr, event_thread, nullptr) != 0) {
        set_last_error("event thread 创建失败");
        return -4;
    }
    g_event_thread_started = true;
    pthread_setname_np(event_thread_id, "mpv-event");

    // Surface 通常比 mpv 实例先到（Compose 的 AndroidView 一进来就 surfaceCreated）。
    // initialize 之前 mpv_set_property("wid") 不落地，只能在这一行把缓存重放进去；
    // 漏掉它，新建的句柄 wid 恒为 0，android VO 会 MP_FATAL("Missing surface pointer")，
    // 表现为「有声音、有进度、画面全黑」。
    if (apply_cached_surface_to_current_handle() < 0)
        ALOGW("initialize 后重放 surface 失败：画面可能起不来（见上面 wid 相关日志）");

    return 0;
}

jni_func(void, destroy) {
    if (!g_mpv) {
        ALOGV("destroy(): 已经没有 mpv 实例");
        return;
    }
    destroy_mpv_locked();
}

jni_func(void, command, jobjectArray jarray) {
    MPV_CHECK_VOID();

    jstring strings[64] = {nullptr};
    const char *arguments[64] = {nullptr};
    jsize len = env->GetArrayLength(jarray);
    if (len <= 0 || len >= static_cast<jsize>(ARRAYLEN(arguments))) {
        ALOGE("command(): 参数个数非法: %d", static_cast<int>(len));
        return;
    }

    for (jsize i = 0; i < len; ++i) {
        strings[i] = static_cast<jstring>(env->GetObjectArrayElement(jarray, i));
        if (!strings[i]) {
            ALOGE("command(): 第 %d 个参数为 null", static_cast<int>(i));
            for (jsize j = 0; j < i; ++j) {
                env->ReleaseStringUTFChars(strings[j], arguments[j]);
                env->DeleteLocalRef(strings[j]);
            }
            return;
        }
        arguments[i] = env->GetStringUTFChars(strings[i], nullptr);
    }

    int result = mpv_command(g_mpv, arguments);
    if (result < 0)
        ALOGE("command(%s) 返回错误 %s", arguments[0], mpv_error_string(result));

    for (jsize i = 0; i < len; ++i) {
        env->ReleaseStringUTFChars(strings[i], arguments[i]);
        env->DeleteLocalRef(strings[i]);
    }
}
