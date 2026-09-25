#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 LongQT-sea <long025733@gmail.com>
"""
Rebuild ptyd for all ABIs on this aarch64 host.

The bionic static binaries must be built with the x86_64 NDK toolchain, which
cannot execute natively here, so every stage (clang driver -> cc1 -> ld.lld)
runs under qemu-x86_64. Child processes of clang (cc1/ld) would fail without
binfmt_misc, so we capture the exact -cc1 and ld.lld command lines from the
driver's -### dry-run and execute each stage manually under qemu.

Bionic AArch64 requires the PT_TLS segment to be aligned to 64 bytes. The
NDK default compiles __thread vars with EMULATED TLS (no .tdata/.tbss), which
loses that alignment — so we force ELF TLS with -fno-emulated-tls, matching the
previously shipped (device-proven) binaries (PT_TLS 0x8/0x80/align 0x40).
"""

import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NDKBIN = os.environ.get(
    "NDKBIN", "/opt/android-ndk-r26d/toolchains/llvm/prebuilt/linux-x86_64/bin"
)
QEMU = os.environ.get("QEMU", "qemu-x86_64")
QEMU_SYSROOT = os.environ.get("QEMU_SYSROOT", "/usr/x86_64-linux-gnu")
QEMU_CMD = [QEMU, "-L", QEMU_SYSROOT]
CLANG = f"{NDKBIN}/clang-17"
LINKER = f"{NDKBIN}/ld.lld"
SRC = ROOT / "tool" / "ptyd.c"
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "ptyd"
CWD = ROOT / "tool"

ABIS = {
    "arm64-v8a": "aarch64-linux-android24",
    "armeabi-v7a": "armv7a-linux-androideabi24",
    "x86_64": "x86_64-linux-android24",
}

TOKEN_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')


def tokens(line):
    return TOKEN_RE.findall(line)


def run(args):
    p = subprocess.run(args, capture_output=True, text=True, cwd=CWD)
    if p.returncode != 0:
        sys.exit(f"FAILED: {p.args[0:5]}... rc={p.returncode}\n{p.stderr[-3000:]}")
    return p


def dry_run(target):
    p = run([*QEMU_CMD, CLANG, f"--target={target}", "-O2", "-static",
             "-o", "/tmp/pty-out", "ptyd.c", "-###"])
    # clang -### prints the commands to stderr
    out = p.stderr + "\n" + p.stdout
    cc1_line = next(l for l in out.splitlines() if ' "-cc1" ' in l)
    ld_line = next(l for l in out.splitlines() if 'ld.lld' in l)
    return cc1_line, ld_line


def fix_output(args, out_path):
    """Set the -o argument of a captured command line to out_path."""
    args = list(args)
    for i in range(len(args) - 1):
        if args[i] == "-o":
            args[i + 1] = out_path
    return args


def main():
    for abi, target in ABIS.items():
        obj = f"/tmp/ptyd-{abi}.o"
        final = f"{ASSETS}/{abi}/ptyd"

        cc1_line, ld_line = dry_run(target)

        # ---- compile stage: clang -cc1 under qemu, with real ELF TLS
        cc1_args = tokens(cc1_line)
        assert cc1_args[0].endswith("clang-17"), cc1_args[0]
        cc1_args = fix_output(cc1_args[1:], obj)  # drop the binary token
        cc1_args.append("-fno-emulated-tls")
        run([*QEMU_CMD, CLANG, *cc1_args])
        print(f"[{abi}] cc1 -> {obj}")

        # ---- link stage: ld.lld under qemu (swap in our object)
        ld_args = tokens(ld_line)
        assert ld_args[0].endswith("ld.lld"), ld_args[0]
        ld_args = ld_args[1:]
        ld_args = fix_output(ld_args, final)
        # replace the driver's temp object path with our object
        for i, a in enumerate(ld_args):
            if a.startswith("/tmp/") and a.endswith(".o"):
                ld_args[i] = obj
        run([*QEMU_CMD, LINKER, *ld_args])
        print(f"[{abi}] ld.lld -> {final}")


if __name__ == "__main__":
    main()