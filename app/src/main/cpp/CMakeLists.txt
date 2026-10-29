cmake_minimum_required(VERSION 3.22.1)
project(mpv_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ─────────────────────────────────────────────────────────────────────────────
# libmpv 是外部构建产物（源码在 mpv-player/mpv，构建链见 scripts/fetch-libmpv.sh
# 与 .github/workflows/build-libmpv.yml）。仓库默认不带二进制：
#
#   app/src/main/jniLibs/<abi>/libmpv.so        ← 必需（含其 FFmpeg 兄弟 .so）
#   app/src/main/jniLibs/<abi>/libav*.so 等
#
# 本 CMake 只负责编译 JNI 封装层 libplayer.so。找不到 libmpv.so 时编译 stub 版，
# 让 Kotlin 侧能安全地判定「mpv 引擎不可用」并回退 Exo，而不是抛 UnsatisfiedLinkError。
# ─────────────────────────────────────────────────────────────────────────────
set(MPV_ABI_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
set(MPV_SO "${MPV_ABI_DIR}/libmpv.so")

if(EXISTS "${MPV_SO}")
    message(STATUS "[mpv] 使用预编译 libmpv: ${MPV_SO}")

    add_library(mpv SHARED IMPORTED)
    set_target_properties(mpv PROPERTIES IMPORTED_LOCATION "${MPV_SO}")

    add_library(player SHARED
            src/main.cpp
            src/property.cpp
            src/render.cpp
            src/event.cpp
            src/log.cpp
            src/jni_utils.cpp)
    target_compile_definitions(player PRIVATE MPV_AVAILABLE=1)
    target_include_directories(player PRIVATE include src)
    target_link_libraries(player PRIVATE mpv android log)
else()
    # 缺 libmpv.so 有两种截然不同的情况，日志级别必须分开：
    #
    #   a) 整个 ABI 目录里一个 .so 都没有 —— 这是仓库的**正常状态**（jniLibs 刻意
    #      不带二进制，见 jniLibs/README.md）。用 STATUS 打印，别用 WARNING：
    #      configure 会跑很多遍（每个 ABI 一次，ninja 重生成时还会再来），
    #      WARNING 会在 4 个 ABI × N 次配置里刷满日志，看起来像构建失败，
    #      实际只是走设计内的 ExoPlayer 降级路径。
    #
    #   b) 目录里已经有别的 .so，唯独缺 libmpv.so —— 这是**装到一半**，是真的
    #      配置错误，保留 WARNING 让人注意到。
    file(GLOB _mpv_sibling_libs "${MPV_ABI_DIR}/*.so")
    if(_mpv_sibling_libs)
        message(WARNING "[mpv] ${ANDROID_ABI}: 目录里已有兄弟库但缺少 libmpv.so —— "
                "libmpv 装到一半了。运行期会 dlopen 失败，mpv 引擎不可用。"
                "请重跑 scripts/fetch-libmpv.sh（或 build-libmpv workflow）"
                "并把整组 .so 一起放进 ${MPV_ABI_DIR}/")
    else()
        message(STATUS "[mpv] ${ANDROID_ABI}: 未安装 libmpv.so，编译 stub 版 libplayer.so，"
                "运行期 mpv 引擎判定为不可用（设计内的降级路径，应用走 ExoPlayer）")
    endif()

    add_library(player SHARED src/stub.cpp)
    target_compile_definitions(player PRIVATE MPV_AVAILABLE=0)
    target_include_directories(player PRIVATE include src)
    target_link_libraries(player PRIVATE android log)
endif()

target_compile_options(player PRIVATE
        -Wall -Wextra -Wno-unused-parameter -fno-exceptions -fno-rtti)

# ─────────────────────────────────────────────────────────────────────────────
# 16 KB 页对齐（Android 15 / API 35 硬性要求）
#
# NDK r28+ 默认按 16 KB 对齐，r27 仍是 4 KB。本项目 pin 的是 r27，所以这里显式
# 给链接器加 max-page-size。build.gradle.kts 里的
# -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON 是同一件事的 CMake 侧开关，两处都写
# 是为了「单跑 CMakeLists 时也不会退化」。
#
# 注意：这个标志只影响本 CMake 产出的 libplayer.so。jniLibs 下的 libmpv.so 及其
# 兄弟库是 meson 构建的外部产物，必须由 buildscripts 侧对齐——见
# scripts/fetch-libmpv.sh 与 .github/workflows/build-libmpv.yml 里的对齐校验。
# ─────────────────────────────────────────────────────────────────────────────
target_link_options(player PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")
