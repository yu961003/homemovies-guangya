#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# libmpv.so 获取 / 构建脚本
#
# 为什么需要这个脚本：
#   本工程把 libmpv 当作「外部构建产物」——app/src/main/cpp/CMakeLists.txt 只
#   IMPORTED 链接 app/src/main/jniLibs/<abi>/libmpv.so，仓库本身不携带二进制
#   （libmpv + FFmpeg 全量 .so 约 40MB/ABI，不适合进 Git）。
#
#   构建链复用 mpv-android 的 buildscripts（它把 FFmpeg / libass / libplacebo /
#   libass / lua / curl 等全部交叉编译好，正是 libmpv 在 Android 上跑起来所必需
#   的那一套），而不是自己从 mpv-player/mpv 手搓 meson 交叉文件。
#
# 用法：
#   ./scripts/fetch-libmpv.sh                    # 只克隆构建链，不编译（快速）
#   ./scripts/fetch-libmpv.sh --build arm64      # 编译 arm64 并装进 jniLibs
#   ./scripts/fetch-libmpv.sh --build all        # 编译 arm64+armv7l+x86_64
#   ./scripts/fetch-libmpv.sh --verify           # 检查 jniLibs 里是否已有可用产物
#   ./scripts/fetch-libmpv.sh --yaozhi           # 打 mpv-Yaozhi-core 里适用于 Android 的通用补丁
#   ./scripts/fetch-libmpv.sh --yaozhi-repo PATH # 指定 Yaozhi 补丁仓库位置（默认 ../mpv-Yaozhi-core）
#   ./scripts/fetch-libmpv.sh --extra-patches <补丁目录>
#                                             # 与 --build 同用时，先给 mpv/FFmpeg 源码打补丁再编
#
# 注意：构建**只能在 Linux / macOS 上进行**（上游 buildscripts 明确不支持
# Windows）。Windows 开发者请用 CI 或 WSL2 / Docker，见
# .github/workflows/build-libmpv.yml。
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# 构建链版本 pin。升级这里 = 升级 libmpv 版本，属于显式决策，不要静默漂移。
MPV_ANDROID_REPO="https://github.com/mpv-android/mpv-android.git"
MPV_ANDROID_REF="73a7c69e5f3d9a7e9b3a27e38ca5a8ed702cf866"   # MPVView: set --flatten-editions

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
BUILD_DIR="$APP_DIR/build/mpv-android"
JNI_LIBS="$APP_DIR/app/src/main/jniLibs"

# ---------------------------------------------------------------------------
# 日志
# ---------------------------------------------------------------------------
log()  { printf '\033[1;34m[libmpv]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[libmpv]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[libmpv] %s\033[0m\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 平台检查
# ---------------------------------------------------------------------------
check_platform() {
    case "$(uname -s)" in
        Linux|Darwin) : ;;
        MINGW*|MSYS*|CYGWIN*)
            warn "检测到 Windows / MSYS 环境。上游 buildscripts 不支持 Windows 构建。"
            warn "请改用："
            warn "  1) GitHub Actions: .github/workflows/build-libmpv.yml 手动触发后下载 artifact"
            warn "  2) WSL2: wsl ./scripts/fetch-libmpv.sh --build arm64"
            warn "  3) Docker: docker run --rm -v \"\$PWD:/w\" -w /w ubuntu:24.04 bash -c 'apt-get update && ./scripts/fetch-libmpv.sh --build arm64'"
            warn ""
            warn "本脚本仍可执行克隆步骤（--clone-only）以便检视构建链。"
            return 1 ;;
        *) die "未知平台：$(uname -s)" ;;
    esac
    return 0
}

# ---------------------------------------------------------------------------
# 克隆 / 更新构建链
# ---------------------------------------------------------------------------
clone_buildscripts() {
    if [ -d "$BUILD_DIR/.git" ]; then
        log "构建链已存在：$BUILD_DIR"
        local cur
        cur="$(git -C "$BUILD_DIR" rev-parse HEAD)"
        if [ "$cur" != "$MPV_ANDROID_REF" ]; then
            log "切换 $cur -> $MPV_ANDROID_REF"
            git -C "$BUILD_DIR" fetch --depth 1 origin "$MPV_ANDROID_REF" || \
                git -C "$BUILD_DIR" fetch origin
            git -C "$BUILD_DIR" checkout -q "$MPV_ANDROID_REF"
        fi
        return 0
    fi

    log "克隆 mpv-android @ ${MPV_ANDROID_REF:0:12}"
    mkdir -p "$(dirname "$BUILD_DIR")"
    git init -q "$BUILD_DIR"
    git -C "$BUILD_DIR" remote add origin "$MPV_ANDROID_REPO"
    git -C "$BUILD_DIR" fetch --depth 1 origin "$MPV_ANDROID_REF"
    git -C "$BUILD_DIR" checkout -q FETCH_HEAD
    log "构建链就绪：$BUILD_DIR"
}

# ---------------------------------------------------------------------------
# 编译并安装
# ---------------------------------------------------------------------------
# 上游 arch 名 -> Android ABI 目录名
abi_of() {
    case "$1" in
        arm64)  echo "arm64-v8a" ;;
        armv7l) echo "armeabi-v7a" ;;
        x86_64) echo "x86_64" ;;
        x86)    echo "x86" ;;
        *) die "未知架构：$1（支持 arm64 / armv7l / x86_64 / x86）" ;;
    esac
}

# ---------------------------------------------------------------------------
# 自定义补丁（可选）
#
# 本脚本默认构建**上游 Android libmpv**。mpv-Yaozhi-core 里的补丁栈绝大多数是
# Windows 桌面侧改动，但也有一部分改的是 FFmpeg/mpv 的通用代码，交叉编译到 Android
# 同样成立。--yaozhi 就是把这部分"通用"补丁单独挑出来打，而不是整目录套用。
#
# 为什么不能整目录套用（被排除的四类，逐条都给理由）：
#   * WASAPI / SDL2 音频布局：mpv-android 走 AudioTrack/OpenSL，没有 Windows 音频栈；
#   * 蓝光 ISO 相关（bluray / disc / DoVi FEL 配对）：依赖 libbluray 与光盘 demux，
#     Android 构建链不编 libbluray；
#   * AV3A / 私有解码器（avs3 / davs2 / libarcdav3a / nvenc）：依赖仓库里没有的私有库
#     与 Windows 构建钩子；缺库时表现为 meson 配置失败，不是补丁应用失败；
#   * Dolby Vision 拆分登记链（ffmpeg-dovi-split-* + mpv 侧 dovi_split.c）：成组依赖，
#     mpv 侧那个文件在仓库中并不存在，单打 FFmpeg 侧会直接编译不过。
#
# 剩下的才是 --yaozhi 的候选集，且**每条仍要过 git apply --check**：上游 mpv/FFmpeg
# 版本一变补丁就可能对不上，对不上就跳过并告警，绝不让源码停在半应用状态——那种状态
# 会让后续 meson 报出一堆与根因无关的错，排查成本远高于一条告警。
# ---------------------------------------------------------------------------

# --yaozhi 候选集：<仓库内相对路径>|<目标源码树>。
# 顺序即应用顺序：core-candidate 里 vo-frame-refs 是 hwdec-surface-pool 的前置提交，
# 必须排在它前面（见 mpv-Yaozhi-core/build/core-candidate/README.md）。
YAOZHI_PATCHES=(
    "build/vvc/ffmpeg-native-default.patch|ffmpeg"
    "build/ffmpeg9/ffmpeg-animated-webp-backport.patch|ffmpeg"
    "build/ffmpeg9/ffmpeg-he-aac-960-backport.patch|ffmpeg"
    "patches/0001-vo_gpu_next-add-image-subtitle-colorspace-control.patch|mpv"
    "patches/0004-video-expose-hdr-vivid-metadata.patch|mpv"
    "patches/0007-swresample-use-native-canonical-input-layout.patch|mpv"
    "build/core-candidate/mpv-8804-demux-interrupt-discarded-read.patch|mpv"
    "build/core-candidate/mpv-8805-vo-frame-refs.patch|mpv"
    "build/core-candidate/mpv-8805-hwdec-surface-pool.patch|mpv"
)

tree_dir() {
    case "$1" in
        mpv)    echo "$BUILD_DIR/deps/mpv" ;;
        ffmpeg) echo "$BUILD_DIR/deps/ffmpeg" ;;
        *) die "未知源码树：$1（只支持 mpv / ffmpeg）" ;;
    esac
}

ensure_deps() {
    # download.sh 会装 Android SDK / NDK 并克隆 FFmpeg、mpv 等源码。幂等：已下载的跳过。
    # 打补丁与编译都要用它，所以抽出来共用——否则首次 `--yaozhi` 运行会因为没有
    # deps/mpv 直接退出，而正确动作是先拉源码。
    if [ ! -d "$BUILD_DIR/sdk" ] || [ ! -d "$BUILD_DIR/deps" ]; then
        ( cd "$BUILD_DIR" && ./buildscripts/download.sh )
    else
        log "SDK / deps 已存在，跳过 download.sh"
    fi
}

require_sources() {
    local t
    for t in mpv ffmpeg; do
        [ -d "$(tree_dir "$t")" ] \
            || die "缺少源码树 $(tree_dir "$t") —— 请先不带补丁跑一次 --build（触发 download.sh），或手工确认 deps 已就绪"
    done
}

# 打一个补丁。三态语义：
#   0 = 已应用（本次新打，或此前已打过——用 --reverse 探测，保证脚本可重复执行）
#   1 = 不适用（跳过，源码不变）
apply_one_patch() {
    local tree="$1" patch="$2" name="$3"
    local src
    src="$(tree_dir "$tree")"

    # 已打过：反向能干净 apply 就说明源码里已经有这段改动，直接算成功。
    # 没有这一步的话，第二次运行会把它当成"不匹配"告警，误导使用者。
    if git -C "$src" apply --check --reverse "$patch" 2>/dev/null; then
        log "已是应用状态 [$tree] $name"
        return 0
    fi

    if git -C "$src" apply --check "$patch" 2>/dev/null; then
        git -C "$src" apply "$patch"
        log "已应用 [$tree] $name"
        return 0
    fi

    # 少数补丁是裸 unified diff（缺 diff --git 头），git apply 不认时退回 GNU patch。
    # 同样先 --dry-run，避免半应用。
    if command -v patch >/dev/null 2>&1 \
        && patch -p1 -d "$src" --dry-run -s -f <"$patch" >/dev/null 2>&1; then
        patch -p1 -d "$src" -s -f <"$patch" >/dev/null
        log "已应用 [$tree] $name（patch -p1 回退）"
        return 0
    fi

    return 1
}

apply_yaozhi() {
    local repo="$1"
    [ -d "$repo" ] || die "Yaozhi 补丁仓库不存在：$repo（用 --yaozhi-repo 指定路径）"
    ensure_deps
    require_sources

    local applied=0 skipped=0 entry tree rel file
    for entry in "${YAOZHI_PATCHES[@]}"; do
        rel="${entry%%|*}"
        tree="${entry##*|}"
        file="$repo/$rel"
        if [ ! -f "$file" ]; then
            warn "跳过（仓库中不存在）：$rel"
            skipped=$((skipped + 1))
            continue
        fi
        if apply_one_patch "$tree" "$file" "$(basename "$rel")"; then
            applied=$((applied + 1))
        else
            warn "跳过（与当前 mpv/FFmpeg 版本不匹配）：$rel"
            skipped=$((skipped + 1))
        fi
    done
    log "Yaozhi 通用补丁：应用 $applied 个、跳过 $skipped 个（候选共 ${#YAOZHI_PATCHES[@]} 个）"
}

apply_extra_patches() {
    local dir="$1"
    [ -d "$dir" ] || die "补丁目录不存在：$dir"
    ensure_deps
    require_sources

    local applied=0 skipped=0 patch name tree
    for patch in "$dir"/*.patch "$dir"/*.diff; do
        [ -e "$patch" ] || continue
        name="$(basename "$patch")"
        # 默认按文件名推断目标树：ffmpeg-* 归 FFmpeg，其余归 mpv。
        # 想精确控制就把补丁按目标树分目录，用 --yaozhi 之外的方式分批执行。
        case "$name" in
            ffmpeg-*) tree="ffmpeg" ;;
            *)        tree="mpv" ;;
        esac
        if apply_one_patch "$tree" "$patch" "$name"; then
            applied=$((applied + 1))
        else
            warn "跳过补丁（不适用于当前版本或与已应用补丁冲突）：$name"
            skipped=$((skipped + 1))
        fi
    done
    log "自定义补丁：应用 $applied 个、跳过 $skipped 个"
}

build_and_install() {
    local arch="$1"
    local abi
    abi="$(abi_of "$arch")"

    log "编译 libmpv ($arch -> $abi)"

    ensure_deps

    # 只构建 "mpv" 目标（不构建上游 app 壳），产物落到 buildscripts/prefix/<arch>/lib/
    #
    # 16 KB 页对齐（Android 15 / API 35）：
    # 上游 buildscripts 锁的 NDK 版本低于 r28 时默认按 4 KB 对齐，产物装进 APK 后
    # 会被 Play Console 判定为「未进行 16 KB 对齐」。NDK r28+ 默认已是 16 KB，
    # 这里无条件追加一次 LDFLAGS 是幂等的——r28+ 上重复指定同样的值是安全的。
    #
    # 为什么放在命令行前缀而不是 export：buildscripts 内部会重置若干编译变量，
    # 命令行前缀优先级更高，能穿透到 meson 生成的 ninja 规则里。
    ( cd "$BUILD_DIR" && \
      LDFLAGS="${LDFLAGS:-} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384" \
      ./buildscripts/buildall.sh --arch "$arch" mpv )

    local prefix="$BUILD_DIR/buildscripts/prefix/$arch"
    [ -f "$prefix/lib/libmpv.so" ] || die "构建结束但找不到 $prefix/lib/libmpv.so"

    # 把 prefix 里的全部运行时 .so 装进 jniLibs。
    # 为什么是全部：libmpv.so 在运行期按 DT_NEEDED 加载 libavcodec/libass/libplacebo
    # 等兄弟库，缺一个就是 dlopen 失败；Android 打包时 jniLibs 里的 .so 会被原样解压，
    # 所以整套一起放，不能只放 libmpv.so。
    mkdir -p "$JNI_LIBS/$abi"
    local n=0
    for so in "$prefix"/lib/*.so; do
        [ -e "$so" ] || continue
        cp -f "$so" "$JNI_LIBS/$abi/"
        n=$((n + 1))
    done
    log "已安装 $n 个 .so -> $JNI_LIBS/$abi"

    verify_abi "$abi"
}

# ---------------------------------------------------------------------------
# 产物校验
# ---------------------------------------------------------------------------
verify_abi() {
    local abi="$1"
    local so="$JNI_LIBS/$abi/libmpv.so"

    if [ ! -f "$so" ]; then
        warn "$abi: 缺少 libmpv.so"
        return 1
    fi

    local size
    size=$(wc -c < "$so" | tr -d ' ')
    if [ "$size" -lt 100000 ]; then
        die "$abi: libmpv.so 只有 $size 字节，明显不是真实产物"
    fi

    # ELF 魔数
    if [ "$(head -c 4 "$so" | od -An -tx1 | tr -d ' \n')" != "7f454c46" ]; then
        die "$abi: libmpv.so 不是 ELF 文件"
    fi

    # 架构匹配
    local elfclass
    elfclass=$(od -An -tu1 -j4 -N1 "$so" | tr -d ' ')
    local machine
    machine=$(od -An -tu1 -j18 -N1 "$so" | tr -d ' ')
    case "$abi" in
        arm64-v8a)   [ "$elfclass" = "2" ] && [ "$machine" = "183" ] || die "$abi: 不是 AArch64 ELF (class=$elfclass machine=$machine)" ;;
        armeabi-v7a) [ "$elfclass" = "1" ] && [ "$machine" = "40" ]  || die "$abi: 不是 ARM ELF (class=$elfclass machine=$machine)" ;;
        x86_64)      [ "$elfclass" = "2" ] && [ "$machine" = "62" ]  || die "$abi: 不是 x86-64 ELF (class=$elfclass machine=$machine)" ;;
    esac

    log "$abi: OK  libmpv.so = ${size} bytes, ELF class=$elfclass machine=$machine, $(ls "$JNI_LIBS/$abi"/*.so 2>/dev/null | wc -l | tr -d ' ') 个 .so"

    check_16kb_alignment "$abi"
}

# 16 KB 页对齐校验（Android 15 / API 35 硬性要求）。
#
# 为什么必须在这里查：libmpv.so 及其 FFmpeg/libass/libplacebo 兄弟库是 meson 构建的
# 外部产物，我们的 CMakeLists 管不到它们。上游 buildscripts 锁的 NDK 版本一旦低于
# r28，产出的 .so 就是 4 KB 对齐，装进 APK 后会被 Play Console 标为不兼容——
# 而且这个错误在构建期完全不报，只有上传时才发现。所以在安装阶段就拦下来。
#
# 判定：每个 PT_LOAD 段的 p_align 必须 >= 0x4000。
# 读法：Elf64_Phdr 的 p_align 在偏移 48（8 字节小端）。
check_16kb_alignment() {
    local abi="$1"
    local dir="$JNI_LIBS/$abi"
    local bad=0
    local total=0
    local f

    for f in "$dir"/*.so; do
        [ -e "$f" ] || continue
        total=$((total + 1))

        # 用 od 直接读 program header 里的 p_align，不依赖 readelf（CI 镜像未必有 binutils）
        local klass phoff phnum phentsize
        klass=$(od -An -tu1 -j4 -N1 "$f" | tr -d ' ')
        if [ "$klass" = "2" ]; then
            phoff=$(od -An -tu8 -j32 -N8 "$f" | tr -d ' ')
            phentsize=$(od -An -tu2 -j54 -N2 "$f" | tr -d ' ')
            phnum=$(od -An -tu2 -j56 -N2 "$f" | tr -d ' ')
            local align_off=48
            local align_size=8
        else
            phoff=$(od -An -tu4 -j28 -N4 "$f" | tr -d ' ')
            phentsize=$(od -An -tu2 -j42 -N2 "$f" | tr -d ' ')
            phnum=$(od -An -tu2 -j44 -N2 "$f" | tr -d ' ')
            local align_off=28
            local align_size=4
        fi

        local i ptype palign
        local min_align=999999
        i=0
        while [ "$i" -lt "$phnum" ]; do
            local base=$((phoff + i * phentsize))
            ptype=$(od -An -tu4 -j"$base" -N4 "$f" | tr -d ' ')
            if [ "$ptype" = "1" ]; then   # PT_LOAD
                if [ "$align_size" = "8" ]; then
                    palign=$(od -An -tu8 -j$((base + align_off)) -N8 "$f" | tr -d ' ')
                else
                    palign=$(od -An -tu4 -j$((base + align_off)) -N4 "$f" | tr -d ' ')
                fi
                if [ "$palign" -lt "$min_align" ]; then min_align="$palign"; fi
            fi
            i=$((i + 1))
        done

        if [ "$min_align" -lt 16384 ]; then
            bad=$((bad + 1))
            warn "  $abi: $(basename "$f") p_align=$min_align < 16384（未对齐 16 KB）"
        fi
    done

    if [ "$bad" -gt 0 ]; then
        warn "$abi: $total 个 .so 中有 $bad 个未按 16 KB 对齐"
        warn "  影响：Android 15 的 64 位设备（API 35+）可能无法加载这些库，"
        warn "        Play Console 也会报「应用兼容性 - 未进行 16 KB 对齐」。"
        warn "  修法：让上游 buildscripts 用 NDK r28+ 构建（默认 16 KB 对齐），"
        warn "        或在构建时给 LDFLAGS 追加 -Wl,-z,max-page-size=16384。"
        return 1
    fi

    log "$abi: 16 KB 对齐 OK（$total 个 .so 全部 p_align >= 0x4000）"
    return 0
}

verify_all() {
    local found=0
    for abi in arm64-v8a armeabi-v7a x86_64; do
        if [ -f "$JNI_LIBS/$abi/libmpv.so" ]; then
            verify_abi "$abi" || found=1
            found=0
        else
            warn "$abi: 未安装 libmpv.so（该 ABI 上 mpv 引擎会在运行期判定为不可用）"
        fi
    done
}

# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------
main() {
    local do_build=0
    local archs=""
    local verify_only=0
    local use_yaozhi=0
    local yaozhi_repo="$APP_DIR/../mpv-Yaozhi-core"
    local extra_patches=""

    while [ $# -gt 0 ]; do
        case "$1" in
            --build)  do_build=1; shift; archs="${1:-arm64}"; shift || true ;;
            --verify) verify_only=1; shift ;;
            --extra-patches) shift; extra_patches="${1:-}"; shift || true ;;
            --yaozhi) use_yaozhi=1; shift ;;
            --yaozhi-repo) shift; yaozhi_repo="${1:-}"; shift || true ;;
            --clone-only) clone_buildscripts; exit 0 ;;
            -h|--help) sed -n '2,/^set -euo pipefail$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; /^set -euo/d'; exit 0 ;;
            *) die "未知参数：$1（用 --help 查看用法）" ;;
        esac
    done

    if [ "$verify_only" = "1" ]; then
        verify_all
        exit 0
    fi

    clone_buildscripts

    if [ "$do_build" = "0" ]; then
        log "仅克隆完成。要真正编译请加 --build arm64（需要 Linux/macOS + 约 15GB 磁盘 + 40~90 分钟）"
        exit 0
    fi

    check_platform || exit 2

    if [ "$archs" = "all" ]; then
        archs="arm64 armv7l x86_64"
    fi

    # 顺序：先打使用者显式指定的目录（意图更强、更具体），再打 --yaozhi 通用集。
    # 两者都要求 deps 已就绪，且都只在本轮第一次进 build_and_install 之前执行一次。
    if [ -n "$extra_patches" ]; then
        apply_extra_patches "$extra_patches"
    fi
    if [ "$use_yaozhi" = "1" ]; then
        apply_yaozhi "$yaozhi_repo"
    fi

    for a in $archs; do
        build_and_install "$a"
    done

    log "全部完成。"
}

main "$@"
