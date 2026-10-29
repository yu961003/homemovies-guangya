#include <jni.h>
#include <stdlib.h>

#include <mpv/client.h>

#include "jni_utils.h"
#include "log.h"
#include "globals.h"

extern "C" {
jni_func(jint, setOptionString, jstring option, jstring value);

jni_func(jobject, getPropertyInt, jstring property);
jni_func(void, setPropertyInt, jstring property, jint value);
jni_func(jobject, getPropertyDouble, jstring property);
jni_func(jint, setPropertyDouble, jstring property, jdouble value);
jni_func(jobject, getPropertyBoolean, jstring property);
jni_func(void, setPropertyBoolean, jstring property, jboolean value);
jni_func(jstring, getPropertyString, jstring jproperty);
jni_func(jint, setPropertyString, jstring jproperty, jstring jvalue);

jni_func(void, observeProperty, jstring property, jint format);
jni_func(void, unobserveProperty, jstring property);
}

jni_func(jint, setOptionString, jstring joption, jstring jvalue) {
    MPV_CHECK_INT();

    const char *option = env->GetStringUTFChars(joption, nullptr);
    const char *value = env->GetStringUTFChars(jvalue, nullptr);

    int result = mpv_set_option_string(g_mpv, option, value);
    if (result < 0)
        ALOGE("mpv_set_option_string(%s, %s) 返回错误 %s",
              option, value, mpv_error_string(result));

    env->ReleaseStringUTFChars(joption, option);
    env->ReleaseStringUTFChars(jvalue, value);

    return result;
}

static int common_get_property(JNIEnv *env, jstring jproperty, mpv_format format, void *output)
{
    MPV_CHECK_INT();

    const char *prop = env->GetStringUTFChars(jproperty, nullptr);
    int result = mpv_get_property(g_mpv, prop, format, output);
    if (result == MPV_ERROR_PROPERTY_UNAVAILABLE)
        ALOGV("mpv_get_property(%s) format %d 当前不可用", prop, format);
    else if (result < 0)
        ALOGE("mpv_get_property(%s) format %d 返回错误 %s",
              prop, format, mpv_error_string(result));
    env->ReleaseStringUTFChars(jproperty, prop);

    return result;
}

static int common_set_property(JNIEnv *env, jstring jproperty, mpv_format format, void *value)
{
    MPV_CHECK_INT();

    const char *prop = env->GetStringUTFChars(jproperty, nullptr);
    int result = mpv_set_property(g_mpv, prop, format, value);
    if (result < 0)
        ALOGE("mpv_set_property(%s) format %d 返回错误 %s",
              prop, format, mpv_error_string(result));
    env->ReleaseStringUTFChars(jproperty, prop);

    return result;
}

jni_func(jobject, getPropertyInt, jstring jproperty) {
    int64_t value = 0;
    if (common_get_property(env, jproperty, MPV_FORMAT_INT64, &value) < 0)
        return nullptr;
    return env->NewObject(java_Integer, java_Integer_init, static_cast<jint>(value));
}

jni_func(jobject, getPropertyDouble, jstring jproperty) {
    double value = 0;
    if (common_get_property(env, jproperty, MPV_FORMAT_DOUBLE, &value) < 0)
        return nullptr;
    return env->NewObject(java_Double, java_Double_init, static_cast<jdouble>(value));
}

jni_func(jobject, getPropertyBoolean, jstring jproperty) {
    int value = 0;
    if (common_get_property(env, jproperty, MPV_FORMAT_FLAG, &value) < 0)
        return nullptr;
    return env->NewObject(java_Boolean, java_Boolean_init,
                          static_cast<jboolean>(value != 0));
}

jni_func(jstring, getPropertyString, jstring jproperty) {
    char *value = nullptr;
    if (common_get_property(env, jproperty, MPV_FORMAT_STRING, &value) < 0 || !value)
        return nullptr;
    jstring jvalue = env->NewStringUTF(value);
    mpv_free(value);
    return jvalue;
}

jni_func(void, setPropertyInt, jstring jproperty, jint jvalue) {
    int64_t value = static_cast<int64_t>(jvalue);
    common_set_property(env, jproperty, MPV_FORMAT_INT64, &value);
}

jni_func(jint, setPropertyDouble, jstring jproperty, jdouble jvalue) {
    double value = static_cast<double>(jvalue);
    return common_set_property(env, jproperty, MPV_FORMAT_DOUBLE, &value);
}

jni_func(void, setPropertyBoolean, jstring jproperty, jboolean jvalue) {
    int value = jvalue == JNI_TRUE ? 1 : 0;
    common_set_property(env, jproperty, MPV_FORMAT_FLAG, &value);
}

jni_func(jint, setPropertyString, jstring jproperty, jstring jvalue) {
    const char *value = env->GetStringUTFChars(jvalue, nullptr);
    int result = common_set_property(env, jproperty, MPV_FORMAT_STRING, &value);
    env->ReleaseStringUTFChars(jvalue, value);
    return result;
}

jni_func(void, observeProperty, jstring property, jint format) {
    MPV_CHECK_VOID();
    const char *prop = env->GetStringUTFChars(property, nullptr);
    int result = mpv_observe_property(g_mpv, 0, prop, static_cast<mpv_format>(format));
    if (result < 0)
        ALOGE("mpv_observe_property(%s) format %d 返回错误 %s",
              prop, format, mpv_error_string(result));
    env->ReleaseStringUTFChars(property, prop);
}

jni_func(void, unobserveProperty, jstring property) {
    MPV_CHECK_VOID();
    const char *prop = env->GetStringUTFChars(property, nullptr);
    int result = mpv_unobserve_property(g_mpv, 0);
    if (result < 0)
        ALOGE("mpv_unobserve_property(%s) 返回错误 %s", prop, mpv_error_string(result));
    env->ReleaseStringUTFChars(property, prop);
}
