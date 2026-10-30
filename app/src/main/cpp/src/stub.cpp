/**
 * libmpv.so 缺失时的占位实现。
 *
 * 存在的意义：让 app 在没有预编译 libmpv 的环境下依然能 assemble 成功，
 * 并在运行期明确判定「mpv 引擎不可用」，由 EngineFactory 回退到 ExoPlayer。
 *
 * 所有函数与真实实现保持完全一致的 JNI 签名（否则 Kotlin 侧 external 声明会在
 * 首次调用时抛 UnsatisfiedLinkError），行为一律是「什么都不做」。
 */
#include <jni.h>

#include "jni_utils.h"

extern "C" {
jni_func(jboolean, isAvailable) { return JNI_FALSE; }
jni_func(jboolean, isHwdecBridgeAvailable) { return JNI_FALSE; }
jni_func(jint, create, jobject appctx) { return -2; }
jni_func(jint, init) { return -3; }
jni_func(void, destroy) {}
jni_func(void, command, jobjectArray jarray) {}
jni_func(jstring, lastError) { return nullptr; }
jni_func(jint, attachSurface, jobject surface) { return -10; }
jni_func(jint, detachSurface) { return 0; }
jni_func(jint, setSurfaceSize, jint width, jint height) { return 0; }
jni_func(jboolean, hasSurface) { return JNI_FALSE; }
jni_func(jint, ensureSurface) { return -10; }
jni_func(jint, setOptionString, jstring option, jstring value) { return -1; }
jni_func(jobject, getPropertyInt, jstring property) { return nullptr; }
jni_func(void, setPropertyInt, jstring property, jint value) {}
jni_func(jobject, getPropertyDouble, jstring property) { return nullptr; }
jni_func(jint, setPropertyDouble, jstring property, jdouble value) { return -1; }
jni_func(jobject, getPropertyBoolean, jstring property) { return nullptr; }
jni_func(void, setPropertyBoolean, jstring property, jboolean value) {}
jni_func(jstring, getPropertyString, jstring property) { return nullptr; }
jni_func(jint, setPropertyString, jstring property, jstring value) { return -1; }
jni_func(void, observeProperty, jstring property, jint format) {}
jni_func(void, unobserveProperty, jstring property) {}
}
