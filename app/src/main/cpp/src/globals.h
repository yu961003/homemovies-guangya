#pragma once

#include <mpv/client.h>
#include <atomic>
#include <jni.h>

extern JavaVM *g_vm;
extern mpv_handle *g_mpv;
extern std::atomic<bool> g_event_thread_request_exit;
extern jobject g_appctx;

// 所有导出的 JNI 函数开头都先过这道闸：mpv 实例可能因为切播放器、Activity 重建
// 而处于「已销毁但 surface 回调还没到」的窗口期，直接解引用会 SIGSEGV。
#define MPV_CHECK_VOID()                                                       \
    do {                                                                       \
        if (!g_mpv) {                                                          \
            ALOGE("%s: mpv 未初始化", __func__);                               \
            return;                                                            \
        }                                                                      \
    } while (0)

#define MPV_CHECK_INT()                                                        \
    do {                                                                       \
        if (!g_mpv) {                                                          \
            ALOGE("%s: mpv 未初始化", __func__);                               \
            return MPV_ERROR_UNINITIALIZED_LOCAL;                              \
        }                                                                      \
    } while (0)

// 仅本仓库内部使用，不导出给 libmpv（mpv.h 里没有这个错误码）。
#define MPV_ERROR_UNINITIALIZED_LOCAL (-900)
