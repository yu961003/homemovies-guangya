#!/usr/bin/env python3
"""Simulate the Android dynamic loader over the APK's lib/<abi>/ payload.

Purpose: prove that `System.loadLibrary("player")` can resolve every symbol it
needs from the libraries actually shipped inside the APK.  The original bug
reported "未检测到 libmpv.so：原生库缺失" -- i.e. the payload was incomplete, not
mis-wired.  This script is the cheap, host-only evidence for "the payload is now
complete and self-consistent" on a machine where no device/emulator is usable.

Checks per ABI:
  1. libplayer.so DT_NEEDED is satisfied by files present (or by a system lib).
  2. Every UNDEFINED dynsym of every bundled lib resolves against
     (a) another bundled lib's exported dynsym, or
     (b) a public NDK system library (libc/libm/libdl/liblog/libandroid/...).
  3. libmpv.so exports the C entry points libplayer.so imports.
  4. No DT_TEXTREL (loader would refuse / warn).
  5. LOAD p_align >= 0x4000 (16 KB page requirement) is reported, not enforced.

System libraries are resolved against the NDK sysroot stubs for the ABI's
minimum API level (--sysroot/--api), NOT against a hand-written name list: the
stubs carry the exact exported symbol set for that API, which is what the
device loader will actually see.

Usage: verify-native-closure.py <dir-with-abi-subdirs> --sysroot <ndk sysroot usr/lib>
                                --api 26 [--readelf path] [--json out.json]
Exit code 0 = all hard checks pass.
"""
import argparse
import json
import os
import re
import struct
import subprocess
import sys

# Public NDK system libraries guaranteed present on a device image.
SYSTEM_LIBS = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so",
    "libstdc++.so", "libEGL.so", "libGLESv2.so", "libGLESv1_CM.so",
    "libOpenSLES.so", "libmediandk.so", "libjnigraphics.so", "libaaudio.so",
    "libOpenMAXAL.so", "libcamera2ndk.so", "libvulkan.so", "libsync.so",
    "libnativewindow.so", "libnativehelper.so", "libhidlbase.so",
}

KNOWN_BAD_SYMBOLS = {
    # C++ runtime symbols that must come from libc++_shared.so when NDK r26+
    # builds with -static-libstdc++ off; if these are undefined and
    # libc++_shared.so is missing the app crashes at dlopen time.
}

ET_DYN = 3
ABI_TRIPLE = {
    "arm64-v8a": "aarch64-linux-android",
    "armeabi-v7a": "arm-linux-androideabi",
    "x86": "i686-linux-android",
    "x86_64": "x86_64-linux-android",
}
SHT_DYNSYM = 11
DT_NEEDED = 1
DT_TEXTREL = 22
PT_LOAD = 1


class Elf:
    """Minimal ELF reader: dynsym names + bindings, DT_NEEDED, PT_LOAD align."""

    def __init__(self, path):
        self.path = path
        with open(path, "rb") as fh:
            self.buf = fh.read()
        self.ok = self._parse()

    def _parse(self):
        b = self.buf
        if len(b) < 52 or b[:4] != b"\x7fELF":
            return False
        self.is64 = b[4] == 2
        self.endian = "<" if b[5] == 1 else ">"
        e = self.endian
        if self.is64:
            (self.shoff,) = struct.unpack_from(e + "Q", b, 0x28)
            self.shentsize, self.shnum = struct.unpack_from(e + "HH", b, 0x3A)
            (self.phoff,) = struct.unpack_from(e + "Q", b, 0x20)
            self.phentsize, self.phnum = struct.unpack_from(e + "HH", b, 0x36)
        else:
            (self.shoff,) = struct.unpack_from(e + "I", b, 0x20)
            self.shentsize, self.shnum = struct.unpack_from(e + "HH", b, 0x2E)
            (self.phoff,) = struct.unpack_from(e + "I", b, 0x1C)
            self.phentsize, self.phnum = struct.unpack_from(e + "HH", b, 0x2A)

        self.needed = []
        self.textrel = False
        self.load_align = []
        self.dynsym = []          # (name, bind, defined)
        self.dynstr = b""

        secs = []
        for i in range(self.shnum):
            off = self.shoff + i * self.shentsize
            if self.is64:
                sh_name, sh_type = struct.unpack_from(e + "II", b, off)
                (sh_off,) = struct.unpack_from(e + "Q", b, off + 0x18)
                (sh_size,) = struct.unpack_from(e + "Q", b, off + 0x20)
                (sh_link, sh_info) = struct.unpack_from(e + "II", b, off + 0x28)
                (sh_entsize,) = struct.unpack_from(e + "Q", b, off + 0x38)
            else:
                sh_name, sh_type = struct.unpack_from(e + "II", b, off)
                (sh_off,) = struct.unpack_from(e + "I", b, off + 0x10)
                (sh_size,) = struct.unpack_from(e + "I", b, off + 0x14)
                (sh_link, sh_info) = struct.unpack_from(e + "II", b, off + 0x18)
                (sh_entsize,) = struct.unpack_from(e + "I", b, off + 0x24)
            secs.append(dict(off=sh_off, size=sh_size, type=sh_type,
                             link=sh_link, info=sh_info, entsize=sh_entsize))

        dynsec = next((s for s in secs if s["type"] == SHT_DYNSYM), None)
        if dynsec:
            self.dynstr = self._sec_bytes(secs[dynsec["link"]])
            self._read_dynsym(dynsec)

        # DT_NEEDED / DT_TEXTREL from PT_DYNAMIC-equivalent section (.dynamic)
        for i in range(self.phnum):
            off = self.phoff + i * self.phentsize
            if self.is64:
                p_type, = struct.unpack_from(e + "I", b, off)
                (p_align,) = struct.unpack_from(e + "Q", b, off + 0x30)
            else:
                p_type, = struct.unpack_from(e + "I", b, off)
                (p_align,) = struct.unpack_from(e + "I", b, off + 0x1C)
            if p_type == PT_LOAD:
                self.load_align.append(p_align)

        dynamic = next((s for s in secs if s["type"] == 6), None)  # SHT_DYNAMIC
        if dynamic:
            self._read_dynamic(dynamic)
        return True

    def _sec_bytes(self, sec):
        return self.buf[sec["off"]:sec["off"] + sec["size"]]

    def _read_dynamic(self, sec):
        b, e = self.buf, self.endian
        step = 16 if self.is64 else 8
        fmt = e + ("qQ" if self.is64 else "iI")
        dstrtab = None
        entries = []
        for off in range(sec["off"], sec["off"] + sec["size"], step):
            try:
                tag, val = struct.unpack_from(fmt, b, off)
            except struct.error:
                break
            if tag == 0:
                break
            entries.append((tag, val))
            if tag == DT_NEEDED:
                pass
            if tag == DT_TEXTREL:
                self.textrel = True
        # need .dynstr which we already have from dynsym's sh_link
        for tag, val in entries:
            if tag == DT_NEEDED:
                self.needed.append(self._str(val))

    def _str(self, off):
        end = self.dynstr.find(b"\x00", off)
        return self.dynstr[off:end].decode("utf-8", "replace")

    def _read_dynsym(self, sec):
        b, e = self.buf, self.endian
        step = sec["entsize"] or (24 if self.is64 else 16)
        for off in range(sec["off"], sec["off"] + sec["size"], step):
            try:
                if self.is64:
                    st_name, st_info = struct.unpack_from(e + "IB", b, off)
                else:
                    st_name, = struct.unpack_from(e + "I", b, off)
                    st_info, = struct.unpack_from(e + "B", b, off + 12)
            except struct.error:
                break
            if st_name == 0:
                continue
            name = self._str(st_name)
            if not name:
                continue
            bind = st_info >> 4
            shndx = 0
            if self.is64:
                shndx, = struct.unpack_from(e + "H", b, off + 6)
            else:
                shndx, = struct.unpack_from(e + "H", b, off + 14)
            self.dynsym.append((name, bind, shndx != 0))

    def exported(self):
        """Symbols a dlopen() consumer can bind to: defined, not LOCAL, not UND."""
        return {n for n, bind, defined in self.dynsym if defined and bind != 0}

    def undefined(self):
        """Only STRONG undefined symbols can make dlopen()-time resolution fail.

        A WEAK undefined symbol is legal and the loader resolves it to 0; NDK
        headers emit exactly this shape for API-gated libc calls (e.g.
        memfd_create on arm64), so treating them as errors would be a false
        positive that hides the real problems.
        """
        return {n for n, bind, defined in self.dynsym if not defined and n and bind != 2}

    def weak_undefined(self):
        return {n for n, bind, defined in self.dynsym if not defined and n and bind == 2}


def system_symbols(sysroot, abi, api):
    """Export set of every NDK sysroot stub for this ABI at this API level.

    The stubs are the authoritative 'what the device libc/libEGL/... exports'
    reference for a given API, which is exactly the loader's view.
    """
    triple = ABI_TRIPLE.get(abi)
    if not sysroot or not triple:
        return set(), []
    d = os.path.join(sysroot, triple, str(api))
    if not os.path.isdir(d):
        # fall back to the nearest lower API dir that exists
        cands = sorted((int(x) for x in os.listdir(os.path.join(sysroot, triple))
                        if x.isdigit() and int(x) <= int(api)), reverse=True)
        if not cands:
            return set(), []
        d = os.path.join(sysroot, triple, str(cands[0]))
    syms, used = set(), []
    for f in sorted(os.listdir(d)):
        if not f.endswith(".so"):
            continue
        e = Elf(os.path.join(d, f))
        if e.ok:
            syms |= e.exported()
            used.append(f)
    return syms, used


def readelf_cross_check(path, readelf):
    """Independent opinion via NDK llvm-readelf, so the hand parser can be trusted."""
    out = subprocess.run([readelf, "-d", path], capture_output=True, text=True,
                         errors="replace").stdout
    needed = re.findall(r"NEEDED\).*\[(.*?)\]", out)
    return needed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("libroot", help="directory containing ABI subdirs with .so files")
    ap.add_argument("--sysroot", default=None,
                    help="NDK sysroot usr/lib (stub .so per triple/API); enables real "
                         "system-symbol resolution instead of a name allowlist")
    ap.add_argument("--api", type=int, default=26, help="minSdk API level for the stubs")
    ap.add_argument("--readelf", default=None, help="path to llvm-readelf for cross-check")
    ap.add_argument("--json", default=None)
    args = ap.parse_args()

    abis = sorted(d for d in os.listdir(args.libroot)
                  if os.path.isdir(os.path.join(args.libroot, d)))
    report = {"libroot": args.libroot, "sysroot": args.sysroot, "api": args.api,
              "abis": {}, "ok": True}

    for abi in abis:
        d = os.path.join(args.libroot, abi)
        libs = sorted(f for f in os.listdir(d) if f.endswith(".so"))
        elves = {}
        for f in libs:
            e = Elf(os.path.join(d, f))
            if e.ok:
                elves[f] = e
        present = set(elves)
        exported_union = set()
        for e in elves.values():
            exported_union |= e.exported()

        problems = []
        # 1. DT_NEEDED satisfiable
        for f, e in elves.items():
            for need in e.needed:
                if need not in present and need not in SYSTEM_LIBS:
                    problems.append(f"{abi}/{f}: DT_NEEDED '{need}' neither bundled nor a system lib")
            if e.textrel:
                problems.append(f"{abi}/{f}: has DT_TEXTREL (loader rejects on arm64)")
            # 16 KB page support only applies to 64-bit ABIs; 32-bit has no such
            # requirement and shipping p_align 0x1000 there is expected.
            if abi in ("arm64-v8a", "x86_64") and e.load_align and min(e.load_align) < 0x4000:
                problems.append(f"{abi}/{f}: LOAD p_align {hex(min(e.load_align))} < 0x4000 "
                                f"(16 KB page incompatible)")
        # 2. symbol closure: bundled exports + NDK sysroot stubs for this API
        sys_syms, sys_libs = system_symbols(args.sysroot, abi, args.api)
        unresolved = {}
        weak_unresolved = {}
        for f, e in elves.items():
            w = sorted(n for n in e.weak_undefined()
                       if n not in exported_union and n not in sys_syms)
            if w:
                weak_unresolved[f] = w
            missing = sorted(n for n in e.undefined()
                             if n not in exported_union and n not in sys_syms
                             and n not in KNOWN_BAD_SYMBOLS)
            if missing:
                unresolved[f] = missing
        if unresolved:
            problems.append(f"{abi}: unresolved dynamic symbols -> {json.dumps(unresolved)}")

        # 3. libplayer -> libmpv linkage specifically (the reported failure)
        player = elves.get("libplayer.so")
        mpv = elves.get("libmpv.so")
        link_ok = bool(player and mpv and "libmpv.so" in player.needed)
        mpv_api = ["mpv_create", "mpv_initialize", "mpv_command", "mpv_set_option_string",
                   "mpv_observe_property", "mpv_wait_event", "mpv_render_context_create"]
        api_missing = sorted(s for s in mpv_api if mpv and s not in mpv.exported())

        # 4. cross-check DT_NEEDED with llvm-readelf when available
        cross_ok = None
        if args.readelf and player:
            try:
                cross = readelf_cross_check(os.path.join(d, "libplayer.so"), args.readelf)
                cross_ok = ("libmpv.so" in cross) == link_ok
            except Exception as exc:  # noqa: BLE001
                cross_ok = f"skipped: {exc}"

        entry = {
            "libs": libs,
            "count": len(libs),
            "player_links_libmpv": link_ok,
            "libmpv_api_missing": api_missing,
            "min_load_align": (hex(min(player.load_align)) if player and player.load_align else None),
            "sysroot_stubs_used": sys_libs,
            "unresolved_symbols": unresolved,
            "weak_undefined_symbols": weak_unresolved,
            "cross_check_agrees": cross_ok,
            "problems": problems,
        }
        if not link_ok:
            entry["problems"].append(f"{abi}: libplayer.so does not DT_NEEDED libmpv.so")
        if api_missing:
            entry["problems"].append(f"{abi}: libmpv.so missing API {api_missing}")
        if entry["problems"]:
            report["ok"] = False
        report["abis"][abi] = entry

    text = json.dumps(report, indent=2, ensure_ascii=False)
    if args.json:
        with open(args.json, "w", encoding="utf-8") as fh:
            fh.write(text)
    print(text)
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
