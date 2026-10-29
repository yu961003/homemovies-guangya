#!/usr/bin/env python3
"""Pack a SINGLE self-contained zip: source tree + one ABI's native libs.

Why: 交付通道只允许一个压缩包，且单文件上限 24 MB。三个 ABI 的 .so 共 74 MB
（压缩后仍 35 MB）装不下，而 arm64 一套压缩后约 11.5 MB，加上源码 2.7 MB 后仍在
上限内。于是「一个包」= 源码 + arm64 库，解开就能直接 ./gradlew 出可装的 APK。

app/build.gradle.kts 里的 mpvAbis 会按 jniLibs 下**实际有 .so 的目录**自动探测，
所以包里只有 arm64 时，不带任何参数构建就只编 arm64 —— 不需要读文档再加开关。
想要别的 ABI，用 libmpv-jnilibs-<abi>.zip 覆盖进去即可（覆盖后探测结果随之改变）。

用法：
    python scripts/pack-single-bundle.py <src-dir> <jnilibs-abi-dir> <out.zip> [root-name]
例：
    python scripts/pack-single-bundle.py . app/src/main/jniLibs/arm64-v8a \\
        ../deliver/homemovies_guangya_fix1028_single.zip homemovies_guangya
"""
import os
import shutil
import sys
import time
import zipfile

# 构建临时物 + 本机配置 + KSP 生成物，一律不进包
EXCLUDE_DIRS = {".gradle", ".kotlin", ".cxx", "build"}
EXCLUDE_FILES = {"local.properties"}
EXCLUDE_SUFFIXES = (".so",)
# KSP 每次构建会重写这个 schema，原始仓库里没有
EXCLUDE_REL = {"app/schemas/com.example.localmovielibrarz.data.local.AppDatabase/20.json"}

STAMP = time.mktime((2026, 10, 28, 0, 0, 0, 0, 0, -1))


def should_skip_dir(rel):
    parts = rel.split("/")
    return any(p in EXCLUDE_DIRS for p in parts if p)


def copy_tree(src, dst):
    copied = 0
    for dirpath, dirnames, filenames in os.walk(src):
        rel_dir = os.path.relpath(dirpath, src).replace("\\", "/")
        if rel_dir == ".":
            rel_dir = ""
        if should_skip_dir(rel_dir):
            dirnames[:] = []
            continue
        for name in sorted(dirnames):
            r = f"{rel_dir}/{name}" if rel_dir else name
            if should_skip_dir(r):
                continue
            os.makedirs(os.path.join(dst, r), exist_ok=True)
        for name in sorted(filenames):
            r = f"{rel_dir}/{name}" if rel_dir else name
            if name in EXCLUDE_FILES or name.endswith(EXCLUDE_SUFFIXES) or r in EXCLUDE_REL:
                continue
            target = os.path.join(dst, r)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            shutil.copy2(os.path.join(dirpath, name), target)
            copied += 1
    return copied


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    src, abi_dir, out = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2]), sys.argv[3]
    root_name = sys.argv[4] if len(sys.argv) > 4 else "homemovies_guangya"

    if not os.path.isdir(src):
        print(f"错误：源码目录不存在 {src}", file=sys.stderr)
        return 3
    sos = sorted(f for f in os.listdir(abi_dir) if f.endswith(".so")) if os.path.isdir(abi_dir) else []
    if not sos:
        print(f"错误：{abi_dir} 里没有 .so", file=sys.stderr)
        return 3

    abi = os.path.basename(abi_dir.rstrip("/\\"))
    stage = os.path.join(os.path.dirname(out), ".bundle-stage")
    shutil.rmtree(stage, ignore_errors=True)
    base = os.path.join(stage, root_name)
    os.makedirs(base, exist_ok=True)

    n = copy_tree(src, base)
    print(f"源码 {n} 个文件 → {root_name}/")

    # 只注入指定 ABI 的库；其它 ABI 目录保留 .gitkeep，解包后仍是合法工程
    dest = os.path.join(base, "app", "src", "main", "jniLibs", abi)
    os.makedirs(dest, exist_ok=True)
    total = 0
    for name in sos:
        shutil.copy2(os.path.join(abi_dir, name), os.path.join(dest, name))
        total += os.path.getsize(os.path.join(abi_dir, name))
    print(f"{abi}: {len(sos)} 个 .so（{total:,} B）→ app/src/main/jniLibs/{abi}/")

    for a in ("arm64-v8a", "armeabi-v7a", "x86_64"):
        d = os.path.join(base, "app", "src", "main", "jniLibs", a)
        if os.path.isdir(d) and not any(f.endswith(".so") for f in os.listdir(d)):
            open(os.path.join(d, ".gitkeep"), "w").close()
            print(f"{a}: 空目录（.gitkeep）—— 构建时不会被选为 ABI")

    entries = []
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames.sort()
        rel_dir = os.path.relpath(dirpath, stage).replace("\\", "/")
        if not dirnames and not filenames:
            entries.append((rel_dir + "/", None))
        for name in sorted(filenames):
            entries.append((os.path.relpath(os.path.join(dirpath, name), stage).replace("\\", "/"),
                            os.path.join(dirpath, name)))
    entries.sort(key=lambda e: e[0])

    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
        for rel, full in entries:
            info = zipfile.ZipInfo(rel, date_time=time.localtime(STAMP)[:6])
            info.compress_type = zipfile.ZIP_DEFLATED
            if full is None:
                info.external_attr = (0o40755 << 16) | 0x10
                zf.writestr(info, b"")
            else:
                info.external_attr = (0o100000 | (os.stat(full).st_mode & 0o777)) << 16
                with open(full, "rb") as fh:
                    zf.writestr(info, fh.read())
    shutil.rmtree(stage, ignore_errors=True)

    size = os.path.getsize(out)
    print(f"wrote {out}: {len(entries)} 条目, {size:,} B")
    if size > 24 * 1024 * 1024:
        print("警告：超过 24 MB 上传上限", file=sys.stderr)
        return 4
    return 0


if __name__ == "__main__":
    sys.exit(main())
