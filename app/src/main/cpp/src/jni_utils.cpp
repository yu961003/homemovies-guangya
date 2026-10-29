#define UTIL_EXTERN
#include "jni_utils.h"

#include <jni.h>
#include <stdlib.h>

bool acquire_jni_env(JavaVM *vm, JNIEnv **env)
{
    if (!vm)
        return false;
    int ret = vm->GetEnv((void **) env, JNI_VERSION_1_6);
    if (ret == JNI_EDETACHED)
        return vm->AttachCurrentThread(env, NULL) == 0;
    else
        return ret == JNI_OK;
}

// 上游注释保留：FindClass / GetMethodID 每次调用都有开销，缓存全局引用。
void init_methods_cache(JNIEnv *env)
{
    static bool methods_initialized = false;
    if (methods_initialized)
        return;

    #define FIND_CLASS(name) reinterpret_cast<jclass>(env->NewGlobalRef(env->FindClass(name)))
    java_Integer = FIND_CLASS("java/lang/Integer");
    java_Integer_init = env->GetMethodID(java_Integer, "<init>", "(I)V");
    java_Double = FIND_CLASS("java/lang/Double");
    java_Double_init = env->GetMethodID(java_Double, "<init>", "(D)V");
    java_Boolean = FIND_CLASS("java/lang/Boolean");
    java_Boolean_init = env->GetMethodID(java_Boolean, "<init>", "(Z)V");

    mpv_MPVLib = FIND_CLASS("com/example/localmovielibrarz/mpv/MPVLib");
    mpv_MPVLib_eventProperty_S  = env->GetStaticMethodID(mpv_MPVLib, "eventProperty", "(Ljava/lang/String;)V");
    mpv_MPVLib_eventProperty_Sb = env->GetStaticMethodID(mpv_MPVLib, "eventProperty", "(Ljava/lang/String;Z)V");
    mpv_MPVLib_eventProperty_Sl = env->GetStaticMethodID(mpv_MPVLib, "eventProperty", "(Ljava/lang/String;J)V");
    mpv_MPVLib_eventProperty_Sd = env->GetStaticMethodID(mpv_MPVLib, "eventProperty", "(Ljava/lang/String;D)V");
    mpv_MPVLib_eventProperty_SS = env->GetStaticMethodID(mpv_MPVLib, "eventProperty", "(Ljava/lang/String;Ljava/lang/String;)V");
    mpv_MPVLib_event = env->GetStaticMethodID(mpv_MPVLib, "event", "(I)V");
    mpv_MPVLib_eventEndFile_ii = env->GetStaticMethodID(mpv_MPVLib, "eventEndFile", "(II)V");
    mpv_MPVLib_logMessage_SiS = env->GetStaticMethodID(mpv_MPVLib, "logMessage", "(Ljava/lang/String;ILjava/lang/String;)V");
    #undef FIND_CLASS

    methods_initialized = true;
}
