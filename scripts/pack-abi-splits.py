#!/usr/bin/env python3
"""Split the 77 MB libmpv set into per-ABI upload-sized zips.

交付通道单文件 25 MB 上限，整套 3 ABI 共 77 MB 装不进去；单个 ABI 压缩后
只有 11-13 MB，所以按 ABI 拆包。包内布局就是 app/src/main/jniLibs/<abi>/，
解压后直接覆盖到工程根即可，不需要再找路径。

Usage: python pack-abi-splits.py <aar-or-jni-dir> <out-dir>
"""
import io
import os
import sys
import time
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from importlib.machinery import SourceFileLoader

here = os.path.dirname(os.path.abspath(__file__))
# 装配脚本的真实副本在 src/scripts（它是工程的一部分，要随包发布）；
# 这个打包工具放在 work 侧，所以按候选路径找。
assemble_path = next(
    p
    for p in (
        os.path.join(here, "assemble-libmpv-jnilibs.py"),
        os.path.join(here, "..", "src", "scripts", "assemble-libmpv-jnilibs.py"),
    )
    if os.path.isfile(p)
)
assemble = SourceFileLoader("assemble_mod", assemble_path).load_module()

STAMP = time.mktime((2026, 10, 29, 0, 0, 0, 0, 0, -1))


def main() -> int:
    src, out_dir = sys.argv[1], sys.argv[2]
    staged = None
    if os.path.isfile(src):
        staged = src = assemble.stage_aar(src)
    layout = assemble.collect_source_layout(src)
    layout.pop("__flat__", None)

    os.makedirs(out_dir, exist_ok=True)
    for abi in assemble.DEFAULT_ABIS:
        files = [f for f in layout.get(abi, []) if os.path.basename(f) not in assemble.NEVER_COPY]
        if not files:
            print(f"[skip] 源里没有 {abi}")
            continue
        out = os.path.join(out_dir, f"libmpv-jnilibs-{abi}.zip")
        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as zf:
            for f in files:
                arc = f"app/src/main/jniLibs/{abi}/{os.path.basename(f)}"
                info = zipfile.ZipInfo(arc, date_time=time.localtime(STAMP)[:6])
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (0o100000 | 0o644) << 16
                with open(f, "rb") as fh:
                    zf.writestr(info, fh.read())
        size = os.path.getsize(out)
        print(f"{abi:12s} {len(files):2d} libs -> {os.path.basename(out)} {size:,d}B")
        if size > 25 * 1024 * 1024:
            print(f"  !! {abi} 仍超过 25 MB 上限", file=sys.stderr)

    if staged:
        import shutil

        shutil.rmtree(staged, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())