#!/usr/bin/env python3
"""把预编译 libmpv（+ 兄弟 .so）装配进 app/src/main/jniLibs/<abi>/。

为什么需要这个脚本
------------------
本工程把 libmpv 当外部构建产物：CMakeLists 只 IMPORTED 链接
jniLibs/<abi>/libmpv.so，仓库默认不带二进制。上游 buildscripts 不支持
Windows（见 scripts/fetch-libmpv.sh 的平台检查），所以在 Windows 主机上
的可行路径是「取一份已经为本工程编译好的 libmpv 二进制，按 ABI 装进
jniLibs」。本脚本把这件事做成幂等、可校验、可复现的一步：

  1) 按 ABI 白名单装配（默认 arm64-v8a / armeabi-v7a / x86_64）
  2) 只拷 libmpv 真正需要的那一组（按 DT_NEEDED 算，不猜）
  3) 逐个文件校验 sha256（可选 --manifest）
  4) 装配完立即做完整性自检：libmpv.so 的每个 DT_NEEDED（非系统库）
     都必须在同一 ABI 目录里存在，否则退出码非 0

用法
----
  # 直接吃 AAR（推荐：内部取 jni/<abi>/*.so，不用先解包）
  python scripts/assemble-libmpv-jnilibs.py --source libmpv-1.0.0.aar

  # 从 AAR 解包目录装配（AAR 内布局：jni/<abi>/*.so）
  python scripts/assemble-libmpv-jnilibs.py --source build/libmpv-aar/jni

  # 从 CI artifact 目录装配（平铺布局：<abi>/*.so 或 *.so）
  python scripts/assemble-libmpv-jnilibs.py --source /path/to/libmpv-arm64

  # 校验模式：不写文件，只检查现有 jniLibs 是否完整
  python scripts/assemble-libmpv-jnilibs.py --verify-only

退出码：0 成功 / 2 装配后自检失败 / 3 参数或源目录错误
"""

from __future__ import annotations

import argparse
import atexit
import hashlib
import json
import os
import shutil
import struct
import sys
import tempfile
import zipfile

# 发布 ABI 白名单。与 app/build.gradle.kts 的 abiFilters 保持一致；
# 不一致会让某个 ABI 只有 libplayer.so 没有 libmpv.so（运行期静默降级）。
DEFAULT_ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")

# AAR 里带的 libplayer.so 是 dev.jdtech.mpv 自己的 JNI 封装，不是本工程的
# (本工程用 app/src/main/cpp 编自己的 libplayer.so)。混进 jniLibs 会和
# CMake 产物同名，AGP 会报 duplicate 或者运行期加载错的那个。
NEVER_COPY = frozenset({"libplayer.so"})

# 允许缺失的 DT_NEEDED：系统库由设备提供，不参与打包。
SYSTEM_LIBS = frozenset(
    {
        "libm.so",
        "libz.so",
        "libdl.so",
        "libc.so",
        "libstdc++.so",
        "liblog.so",
        "libandroid.so",
        "libEGL.so",
        "libGLESv2.so",
        "libGLESv3.so",
        "libOpenSLES.so",
        "libOpenMAXAL.so",
        "libjnigraphics.so",
        "libnativewindow.so",
        "libsync.so",
        "libvulkan.so",
        "libaaudio.so",
        "libmediandk.so",
        "libcamera2ndk.so",
        "libneuralnetworks.so",
    }
)


def sha256_of(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def elf_dt_needed(path: str) -> list[str]:
    """读 ELF 的 .dynamic → DT_NEEDED。支持 ELF32/ELF64、小端。"""
    with open(path, "rb") as f:
        data = f.read()

    if data[:4] != b"\x7fELF":
        raise ValueError(f"{path}: 不是 ELF")
    is64 = data[4] == 2
    endian = "<" if data[5] == 1 else ">"

    if is64:
        e_shoff = struct.unpack_from(endian + "Q", data, 0x28)[0]
        e_shentsize = struct.unpack_from(endian + "H", data, 0x3A)[0]
        e_shnum = struct.unpack_from(endian + "H", data, 0x3C)[0]
        e_shstrndx = struct.unpack_from(endian + "H", data, 0x3E)[0]
    else:
        e_shoff = struct.unpack_from(endian + "I", data, 0x20)[0]
        e_shentsize = struct.unpack_from(endian + "H", data, 0x2E)[0]
        e_shnum = struct.unpack_from(endian + "H", data, 0x30)[0]
        e_shstrndx = struct.unpack_from(endian + "H", data, 0x32)[0]

    sections = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        if is64:
            fields = struct.unpack_from(endian + "IIQQQQIIQQ", data, off)
        else:
            fields = struct.unpack_from(endian + "IIIIIIIIII", data, off)
        sections.append(
            {
                "name_off": fields[0],
                "type": fields[1],
                "offset": fields[4],
                "size": fields[5],
                "entsize": fields[9],
            }
        )

    def section_name(sec) -> str:
        base = sections[e_shstrndx]["offset"]
        start = base + sec["name_off"]
        end = data.find(b"\x00", start)
        return data[start:end].decode("ascii", "replace")

    dyn = next((s for s in sections if section_name(s) == ".dynamic"), None)
    strtab = next((s for s in sections if section_name(s) == ".dynstr"), None)
    if dyn is None or strtab is None:
        return []

    entsize = dyn["entsize"] or (16 if is64 else 8)
    neededs: list[str] = []
    for i in range(dyn["size"] // entsize):
        off = dyn["offset"] + i * entsize
        if is64:
            tag, val = struct.unpack_from(endian + "qQ", data, off)
        else:
            tag, val = struct.unpack_from(endian + "iI", data, off)
        if tag == 0:  # DT_NULL
            break
        if tag == 1:  # DT_NEEDED
            start = strtab["offset"] + val
            end = data.find(b"\x00", start)
            neededs.append(data[start:end].decode("ascii", "replace"))
    return neededs


def collect_source_layout(source: str) -> dict[str, list[str]]:
    """把源目录归一化成 {abi: [so 绝对路径, ...]}。

    支持两种布局：
      A) AAR 解包布局     <source>/<abi>/*.so
      B) 单 ABI 平铺布局   <source>/*.so        （此时用 --abi 指定归属）
    """
    layout: dict[str, list[str]] = {}
    for entry in sorted(os.listdir(source)):
        full = os.path.join(source, entry)
        if os.path.isdir(full):
            sos = [
                os.path.join(full, f)
                for f in sorted(os.listdir(full))
                if f.endswith(".so") and f not in NEVER_COPY
            ]
            if sos:
                layout[entry] = sos
    flat = [
        os.path.join(source, f)
        for f in sorted(os.listdir(source))
        if f.endswith(".so") and f not in NEVER_COPY and os.path.isfile(os.path.join(source, f))
    ]
    if flat:
        layout.setdefault("__flat__", flat)
    return layout


def verify_abi_dir(abi_dir: str) -> list[str]:
    """检查一个 ABI 目录的依赖闭合性。返回问题列表（空 = 通过）。"""
    problems: list[str] = []
    mpv = os.path.join(abi_dir, "libmpv.so")
    if not os.path.isfile(mpv):
        return [f"{abi_dir}: 缺 libmpv.so"]

    present = {f for f in os.listdir(abi_dir) if f.endswith(".so")}
    for needed in elf_dt_needed(mpv):
        if needed in SYSTEM_LIBS:
            continue
        if needed not in present:
            problems.append(f"{abi_dir}: libmpv.so 需要 {needed}，但目录里没有")

    # 兄弟库自己也可能有非系统依赖（FFmpeg 之间互相依赖），一并查。
    for name in sorted(present):
        if name == "libmpv.so":
            continue
        for needed in elf_dt_needed(os.path.join(abi_dir, name)):
            if needed in SYSTEM_LIBS or needed in present:
                continue
            problems.append(f"{abi_dir}: {name} 需要 {needed}，但目录里没有")
    return problems


def stage_aar(source: str) -> str:
    """把 .aar/.zip 里的 jni/<abi>/*.so 摊到一个临时目录，返回该目录。

    存在的理由：AAR 内层是 jni/，而本脚本吃的是“含 ABI 子目录的目录”。让
    用户先手工 unzip 再记得补一个 /jni 后缀，是最典型的用法错——直接把 .aar
    丢进来，路径这一步就没有可错的地方。只取 jni/ 下的 .so，AAR 里的 classes.jar、
    R.txt 之类不进临时目录，也就不会被误拷进 jniLibs。
    """
    tmp = tempfile.mkdtemp(prefix="libmpv-aar-")
    n = 0
    with zipfile.ZipFile(source) as zf:
        for info in zf.infolist():
            parts = info.filename.split("/")
            # 布局 A：jni/<abi>/<name>.so（AAR 规范）
            # 布局 B：<abi>/<name>.so（有人直接把 jni 目录打成了 zip）
            if info.filename.startswith("jni/"):
                parts = parts[1:]
            if len(parts) != 2 or not parts[1].endswith(".so"):
                continue
            abi, name = parts
            if abi not in DEFAULT_ABIS:
                continue
            os.makedirs(os.path.join(tmp, abi), exist_ok=True)
            with zf.open(info) as src_fh, open(os.path.join(tmp, abi, name), "wb") as dst_fh:
                shutil.copyfileobj(src_fh, dst_fh)
            n += 1
    print(f"[assemble] 从 {os.path.basename(source)} 解出 {n} 个 .so → {tmp}")
    return tmp


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--source", help="预编译 .so 所在目录（AAR 解包目录的 jni/ 或单 ABI 目录）")
    ap.add_argument("--abi", help="--source 是单 ABI 平铺布局时，指定它属于哪个 ABI")
    ap.add_argument(
        "--dest",
        default=None,
        help="jniLibs 目标目录（默认 <repo>/app/src/main/jniLibs）",
    )
    ap.add_argument(
        "--abis",
        default=",".join(DEFAULT_ABIS),
        help="要装配的 ABI，逗号分隔（默认 %s）" % ",".join(DEFAULT_ABIS),
    )
    ap.add_argument("--manifest", help="可选：JSON 清单，逐文件校验 sha256（格式 {relpath: {sha256: ...}}）")
    ap.add_argument("--write-manifest", help="可选：装配后把实际 sha256 写成 JSON 清单")
    ap.add_argument("--verify-only", action="store_true", help="只校验目标目录，不写文件")
    ap.add_argument("--allow-partial", action="store_true", help="源目录缺某个 ABI 时跳过而不是报错")
    args = ap.parse_args()

    repo = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    dest = args.dest or os.path.join(repo, "app", "src", "main", "jniLibs")
    abis = [a.strip() for a in args.abis.split(",") if a.strip()]

    if args.verify_only:
        problems: list[str] = []
        for abi in abis:
            abi_dir = os.path.join(dest, abi)
            if not os.path.isdir(abi_dir):
                problems.append(f"{abi_dir}: 目录不存在")
                continue
            problems.extend(verify_abi_dir(abi_dir))
        if problems:
            print("[verify-only] FAILED")
            for p in problems:
                print("  -", p)
            return 2
        print(f"[verify-only] OK — {', '.join(abis)} 依赖闭合")
        return 0

    if not args.source:
        print("错误：需要 --source（或用 --verify-only）", file=sys.stderr)
        return 3
    source = os.path.abspath(args.source)
    staged = None
    if os.path.isfile(source):
        # .aar / .zip：直接收，省掉“先解包、再补 /jni”这一步
        if not zipfile.is_zipfile(source):
            print(f"错误：--source 是文件但不是 zip/aar：{source}", file=sys.stderr)
            return 3
        staged = source = stage_aar(source)
        # 临时目录随进程退出清理；中途 return 也不会漏
        atexit.register(shutil.rmtree, staged, ignore_errors=True)
    if not os.path.isdir(source):
        print(f"错误：源目录不存在 {source}", file=sys.stderr)
        return 3

    layout = collect_source_layout(source)
    if not layout:
        print(f"错误：{source} 下没找到任何 .so", file=sys.stderr)
        return 3

    manifest = {}
    if args.manifest and os.path.isfile(args.manifest):
        with open(args.manifest, encoding="utf8") as f:
            manifest = json.load(f)

    flat = layout.pop("__flat__", None)
    if flat:
        if not args.abi:
            print("错误：--source 是单 ABI 平铺布局，必须用 --abi 指定归属", file=sys.stderr)
            return 3
        layout[args.abi] = flat

    wrote = 0
    for abi in abis:
        files = layout.get(abi)
        if not files:
            msg = f"源目录没有 {abi}"
            if args.allow_partial:
                print(f"[skip] {msg}")
                continue
            print(f"错误：{msg}（如确需跳过请加 --allow-partial）", file=sys.stderr)
            return 3

        abi_dir = os.path.join(dest, abi)
        os.makedirs(abi_dir, exist_ok=True)

        # 先清掉上一轮留下的 .so，避免旧版本/newer ABI 混装。
        for f in os.listdir(abi_dir):
            if f.endswith(".so"):
                os.remove(os.path.join(abi_dir, f))

        for src in files:
            name = os.path.basename(src)
            if name in NEVER_COPY:
                continue
            digest = sha256_of(src)
            key = f"{abi}/{name}"
            if manifest:
                want = manifest.get(key, {}).get("sha256")
                if want and want != digest:
                    print(f"错误：{key} sha256 不匹配\n  期望 {want}\n  实际 {digest}", file=sys.stderr)
                    return 3
            shutil.copy2(src, os.path.join(abi_dir, name))
            wrote += 1
            print(f"  {key:42s} {os.path.getsize(src):>10d}B  {digest[:16]}")

    # 装配完立刻自检：这一步是「真的装全了」的唯一判据。
    problems = []
    for abi in abis:
        abi_dir = os.path.join(dest, abi)
        if os.path.isdir(abi_dir):
            problems.extend(verify_abi_dir(abi_dir))
    if problems:
        print("\n[assemble] 依赖不闭合！")
        for p in problems:
            print("  -", p)
        return 2

    if args.write_manifest:
        out = {}
        for abi in abis:
            abi_dir = os.path.join(dest, abi)
            if not os.path.isdir(abi_dir):
                continue
            for f in sorted(os.listdir(abi_dir)):
                if f.endswith(".so"):
                    p = os.path.join(abi_dir, f)
                    out[f"{abi}/{f}"] = {"sha256": sha256_of(p), "size": os.path.getsize(p)}
        with open(args.write_manifest, "w", encoding="utf8") as f:
            json.dump(out, f, indent=1)
        print(f"[assemble] manifest → {args.write_manifest}")

    print(f"[assemble] OK — {wrote} 个 .so → {dest}（{', '.join(abis)}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
