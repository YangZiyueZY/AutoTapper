#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 AGP 产出的 APK 重新打包成"可复现"的 APK。

为什么需要它：
  AGP 打 APK 时会把每个 zip 条目的 mtime 写成构建时间，v1/v2 签名又对这些字节做摘要，
  于是同一份源码、同一个签名密钥，两次构建出来的 APK 哈希都不一样：
  "签名每次都不一样"其实不是签名算法随机，而是**被签名的内容每次不同**。

这个脚本做三件事，让产物只取决于输入内容：
  1. 解包，按路径排序后以**固定时间戳（1980-01-01）**和固定压缩方式重新打包；
  2. zipalign -p 4（固定对齐，同样不影响已定序的条目内容）；
  3. 用 apksigner 重新签名（debug 密钥固定 → 签名结果确定）。

校验方式：同一份源码连续构建两次，对最终 APK 取 sha256，应当完全一致。

用法（需要 Android SDK build-tools 里的 zipalign 与 apksigner）：
    python tools/reproducible_apk.py \
        --apk app/build/outputs/apk/debug/app-debug.apk \
        --build-tools "%ANDROID_HOME%/build-tools/35.0.0" \
        --ks "%USERPROFILE%/.android/debug.keystore" \
        --ks-alias androiddebugkey --ks-pass android --key-pass android
"""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

FIXED_TIME = (1980, 1, 1, 0, 0, 0)


def tool(build_tools: str, name: str) -> str:
    for candidate in (name, name + ".bat", name + ".exe"):
        path = os.path.join(build_tools, candidate)
        if os.path.isfile(path):
            return path
    raise SystemExit("找不到 {}（在 {} 里）".format(name, build_tools))


def repack(src: str, dst: str) -> None:
    with zipfile.ZipFile(src) as zin:
        entries = sorted(zin.infolist(), key=lambda i: i.filename)
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
            for info in entries:
                if info.filename.endswith("/"):
                    continue
                data = zin.read(info.filename)
                out = zipfile.ZipInfo(info.filename, date_time=FIXED_TIME)
                # resources.arsc 必须**不压缩**：Android 11+（targetSdk 30+）安装时会校验
                # 它是否以 stored 方式存放且 4 字节对齐，压了就直接 INSTALL_PARSE_FAILED。
                # 原生库同理（.so 必须是 stored，zipalign -p 会按页对齐）。
                if info.filename == "resources.arsc" or info.filename.endswith(".so"):
                    out.compress_type = zipfile.ZIP_STORED
                else:
                    out.compress_type = zipfile.ZIP_DEFLATED
                # 权限位与外部属性固定，避免受源文件影响
                out.external_attr = 0o644 << 16
                out.create_system = 0
                zout.writestr(out, data)


def run(cmd: list[str]) -> None:
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit("命令失败：{}\n{}\n{}".format(" ".join(cmd), result.stdout, result.stderr))


def main() -> int:
    parser = argparse.ArgumentParser(description="重新打包并签名，产出可复现的 APK")
    parser.add_argument("--apk", required=True, help="AGP 产出的 APK")
    parser.add_argument("--build-tools", required=True, help="Android build-tools 目录")
    parser.add_argument("--ks", required=True, help="签名用 keystore")
    parser.add_argument("--ks-alias", required=True)
    parser.add_argument("--ks-pass", required=True)
    parser.add_argument("--key-pass", required=True)
    parser.add_argument("--out", default=None, help="输出路径，默认覆盖原 APK")
    args = parser.parse_args()

    out_path = args.out or args.apk
    work = tempfile.mkdtemp(prefix="repro-apk-")
    try:
        unsigned = os.path.join(work, "repacked.apk")
        aligned = os.path.join(work, "aligned.apk")
        signed = os.path.join(work, "signed.apk")

        repack(args.apk, unsigned)
        run([tool(args.build_tools, "zipalign"), "-f", "-p", "4", unsigned, aligned])
        run([
            tool(args.build_tools, "apksigner"), "sign",
            "--ks", args.ks,
            "--ks-key-alias", args.ks_alias,
            "--ks-pass", "pass:" + args.ks_pass,
            "--key-pass", "pass:" + args.key_pass,
            "--v1-signing-enabled", "true",
            "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true",
            "--out", signed, aligned,
        ])
        shutil.copyfile(signed, out_path)
        print("已生成可复现 APK：{}".format(out_path))
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())