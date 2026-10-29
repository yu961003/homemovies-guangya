#include "log.h"

// 上游 log.cpp 只有 die()。这里保留同名函数以兼容可能残留的调用点，
// 但不再退出进程：die 语义降级为「记一条 error 日志」。
void die(const char *msg)
{
    ALOGE("%s", msg);
}
