#include <jni.h>

#include <mpv/client.h>

#include "globals.h"
#include "jni_utils.h"
#include "log.h"

static void sendPropertyUpdateToJava(JNIEnv *env, mpv_event_property *prop)
{
    jstring jprop = env->NewStringUTF(prop->name);
    jstring jvalue = nullptr;
    switch (prop->format) {
    case MPV_FORMAT_NONE:
        env->CallStaticVoidMethod(mpv_MPVLib, mpv_MPVLib_eventProperty_S, jprop);
        break;
    case MPV_FORMAT_FLAG:
        env->CallStaticVoidMethod(mpv_MPVLib, mpv_MPVLib_eventProperty_Sb, jprop,
                                  static_cast<jboolean>(*(int *) prop->data != 0));
        break;
    case MPV_FORMAT_INT64:
        env->CallStaticVoidMethod(mpv_MPVLib, mpv_MPVLib_eventProperty_Sl, jprop,
                                  static_cast<jlong>(*(int64_t *) prop->data));
        break;
    case MPV_FORMAT_DOUBLE:
        env->CallStaticVoidMethod(mpv_MPVLib, mpv_MPVLib_eventProperty_Sd, jprop,
                                  static_cast<jdouble>(*(double *) prop->data));
        break;
    case MPV_FORMAT_STRING:
        jvalue = env->NewStringUTF(*(const char **) prop->data);
        env->CallStaticVoidMethod(mpv_MPVLib, mpv_MPVLib_eventProperty_SS, jprop, jvalue);
        break;
    default:
        ALOGV("未知的属性更新格式: %d", prop->format);
        break;
    }
    if (jprop)
        env->DeleteLocalRef(jprop);
    if (jvalue)
        env->DeleteLocalRef(jvalue);
}

static void sendEventToJava(JNIEnv *env, int event)
{
    env->CallStaticVoidMethod(mpv_MPVLib, mpv_MPVLib_event, event);
}

static void sendEndFileToJava(JNIEnv *env, mpv_event_end_file *end)
{
    // 双播放器下「播完」和「出错」的后续处理完全不同：播完要标记已看、清进度；
    // 出错要弹错误并可切回 Exo。上游只透传 event_id，Kotlin 侧分不出两者，
    // 所以这里额外把 (reason, error) 一起送回 Java 侧。
    env->CallStaticVoidMethod(mpv_MPVLib, mpv_MPVLib_eventEndFile_ii,
                              static_cast<jint>(end->reason),
                              static_cast<jint>(end->error));
}

static void sendLogMessageToJava(JNIEnv *env, mpv_event_log_message *msg)
{
    if (!msg->prefix || !msg->text)
        return;

    // Java 侧 NewStringUTF 遇到非法 UTF-8 会直接失败，先挡掉最常见的几类。
    const auto invalid_utf8 = [](unsigned char c) {
        return c == 0xc0 || c == 0xc1 || c >= 0xf5;
    };
    for (int i = 0; msg->text[i]; i++) {
        if (invalid_utf8(static_cast<unsigned char>(msg->text[i])))
            return;
    }

    jstring jprefix = env->NewStringUTF(msg->prefix);
    jstring jtext = env->NewStringUTF(msg->text);

    env->CallStaticVoidMethod(mpv_MPVLib, mpv_MPVLib_logMessage_SiS,
                              jprefix, static_cast<jint>(msg->log_level), jtext);

    if (jprefix)
        env->DeleteLocalRef(jprefix);
    if (jtext)
        env->DeleteLocalRef(jtext);
}

void *event_thread(void *arg)
{
    JNIEnv *env = nullptr;
    if (!g_vm || g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) {
        ALOGE("event_thread: 无法附加到 JavaVM");
        return nullptr;
    }

    while (true) {
        mpv_event *mp_event = mpv_wait_event(g_mpv, -1.0);

        if (g_event_thread_request_exit)
            break;

        if (!mp_event || mp_event->event_id == MPV_EVENT_NONE)
            continue;

        // Java 回调里抛异常时必须清理，否则后续 CallStaticVoidMethod 会不再生效。
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }

        switch (mp_event->event_id) {
        case MPV_EVENT_LOG_MESSAGE:
            sendLogMessageToJava(env, static_cast<mpv_event_log_message *>(mp_event->data));
            break;
        case MPV_EVENT_PROPERTY_CHANGE:
            sendPropertyUpdateToJava(env, static_cast<mpv_event_property *>(mp_event->data));
            break;
        case MPV_EVENT_END_FILE:
            sendEndFileToJava(env, static_cast<mpv_event_end_file *>(mp_event->data));
            break;
        default:
            ALOGV("event: %s", mpv_event_name(mp_event->event_id));
            sendEventToJava(env, mp_event->event_id);
            break;
        }
    }

    g_vm->DetachCurrentThread();
    return nullptr;
}