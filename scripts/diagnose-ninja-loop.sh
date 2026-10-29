#!/usr/bin/env bash
# 诊断 `ninja: error: manifest 'build.ninja' still dirty after 100 tries`。
#
# 这个错误的意思是：ninja 发现 build.ninja 比它的某个输入旧，于是重跑 cmake
# 重新生成；生成完再 stat 一次，发现还是旧；循环 100 次后放弃。
#
# 一条命令看不出哪个文件 dirty，必须让 ninja 自己说。`-d explain` 会把它判定
# 「dirty」的理由逐条打出来，`-n` 保证只干跑不真的编译。
#
# 用法（在仓库根目录、或者 GitHub Actions 的 run: 步骤里）：
#     bash scripts/diagnose-ninja-loop.sh
#     bash scripts/diagnose-ninja-loop.sh app/.cxx/Debug/4p1e2w5q/arm64-v8a
#
# 有结果后看输出里第一段 "explain" 的库：被点名「newer than」的文件就是罪魁。
# 绝大多数是 CI 缓存（actions/cache）还原出来的文件带着旧的/偏差的 mtime。

set -uo pipefail

BUILD_DIR="${1:-}"

if [ -z "$BUILD_DIR" ]; then
    # 自动找：app/.cxx/<buildType>/<hash>/<abi>/ 下有 build.ninja 的那个
    BUILD_DIR="$(find app/.cxx build/.cxx -name build.ninja -type f 2>/dev/null \
        | head -1 | xargs -r dirname)"
fi

if [ -z "$BUILD_DIR" ] || [ ! -f "$BUILD_DIR/build.ninja" ]; then
    echo "找不到 build.ninja。"
    echo "  先跑一次会失败的构建，再执行本脚本；或者手动传入目录："
    find . -name "android_gradle_build.json" -type f 2>/dev/null \
        | sed 's|/android_gradle_build.json|  ← 同目录下应该有 build.ninja|' | head -5
    exit 1
fi

echo "=== 构建目录 ==="
echo "  $BUILD_DIR"
echo

NINJA_BIN="$(find "${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk}" -name ninja -type f 2>/dev/null | head -1)"
NINJA_BIN="${NINJA_BIN:-ninja}"

echo "=== 1. ninja 自己解释为什么 dirty ==="
# explain 打到 stderr；只取前 40 行，够定位第一个被点名的输入
"$NINJA_BIN" -C "$BUILD_DIR" -d explain -n 2>&1 | head -40
echo

echo "=== 2. build.ninja 与它记录的输入做 mtime 对比 ==="
if [ -f "$BUILD_DIR/.ninja_log" ] && [ -f "$BUILD_DIR/build.ninja" ]; then
    python3 - "$BUILD_DIR" <<'PY'
import os, sys, re
d = sys.argv[1]
manifest = os.path.join(d, 'build.ninja')
m_mtime = os.path.getmtime(manifest)
print(f"  build.ninja mtime = {m_mtime:.0f}")
# CMake 在 build.ninja 里用 "build build.ninja: RERUN_CMAKE <输入们>" 记录依赖
try:
    txt = open(manifest, encoding='utf-8', errors='replace').read()
except OSError:
    sys.exit(0)
m = re.search(r'^build build\.ninja[^\n]*?:\s*RERUN_CMAKE\s*\|?(.*?)(?=^\S|\Z)',
              txt, re.M | re.S)
if not m:
    print("  没找到 RERUN_CMAKE 规则 —— 说明已经抑制了自动重生成"
          "（CMAKE_SUPPRESS_REGENERATION=ON 生效），或者构建目录不完整。")
    sys.exit(0)
inputs = [t for t in m.group(1).replace('$\n', ' ').split() if not t.startswith('|')]
newer = []
for f in inputs:
    p = f if os.path.isabs(f) else os.path.join(d, f)
    if not os.path.exists(p):
        newer.append((None, p, 'MISSING'))
    elif os.path.getmtime(p) > m_mtime:
        newer.append((os.path.getmtime(p), p, 'NEWER'))
print(f"  RERUN_CMAKE 记录的输入：{len(inputs)} 个")
if newer:
    print(f"  ✗ 其中 {len(newer)} 个导致反复重生成：")
    for mt, p, why in newer[:20]:
        if mt is None:
            print(f"      MISSING  {p}")
        else:
            print(f"      +{mt - m_mtime:>8.0f}s  {p}")
else:
    print("  ✓ 所有输入都不比 build.ninja 新 —— 循环不是 mtime 引起的，")
    print("    更可能是某个输入在构建过程中被重写（例如构建脚本在 configure 期间写文件）。")
PY
else
    echo "  缺 build.ninja 或 .ninja_log，跳过。"
fi
echo

echo "=== 3. 缓存还原痕迹（有的话基本就锁定了）==="
find . -path ./app/build -prune -o -name "*.so" -newer "$BUILD_DIR/build.ninja" -print 2>/dev/null | head -5
echo
echo "=== 怎么修 ==="
cat <<'EOF'
  A. 急性期：干净重来
       rm -rf app/.cxx app/build build
  B. 慢性期：本仓库已默认开启抑制（app/build.gradle.kts 里的
       -DCMAKE_SUPPRESS_REGENERATION=ON），ninja 不再自检 build.ninja，
       循环从根上消失。
  C. 如果 CI 上仍复现：检查 workflow 里是否 actions/cache 缓存了
       app/.cxx 或 build/ —— 缓存里带出的旧 mtime 就是复现根源，
       要么别缓存 native 产物，要么在 restore 后 touch 一遍源文件。
EOF