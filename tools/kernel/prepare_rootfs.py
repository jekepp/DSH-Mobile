#!/usr/bin/env python3
"""生成 rootfs.bin —— 从 Ubuntu Base 得到一份能在 Android 上解包的 rootfs。

做两件事：
  1. 下载 ubuntu-base 的 arm64 tar.gz
  2. ★ 把 tar 里的**硬链接条目改写成真实文件副本**，再输出成 rootfs.bin

为什么第 2 步必须做：
    Android 上 link(2) 会被 SELinux 拒绝，tar 解压硬链接条目会报
        tar: can't link 'usr/bin/perl5.38.2' -> 'usr/bin/perl': Permission denied
    而 toybox tar 没有 proot 的 --link2symlink 能力。
    ubuntu-base 里只有 2 个硬链接（perl / uncompress），改写代价约 +0.6MB。

为什么输出叫 .bin 而不是 .tar.gz：
    AGP 会认出 .gz 扩展名并自动解压改名，把 106MB 的裸 tar 原样塞进 APK。

用法:
    python3 tools/kernel/prepare_rootfs.py [--url URL] [--out PATH] [--keep-tar]

默认输出到 app/src/main/assets/rootfs.bin
"""
from __future__ import annotations

import argparse
import gzip
import io
import os
import sys
import tarfile
import urllib.request

DEFAULT_URL = (
    "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
    "ubuntu-base-24.04.5-base-arm64.tar.gz"
)
DEFAULT_OUT = os.path.join("app", "src", "main", "assets", "rootfs.bin")
BLOCK = 512


def download(url: str, dest: str) -> None:
    if os.path.exists(dest) and os.path.getsize(dest) > 20 * 1024 * 1024:
        print(f"已存在，跳过下载: {dest} ({os.path.getsize(dest) // 1048576} MB)")
        return
    os.makedirs(os.path.dirname(dest) or ".", exist_ok=True)
    print(f"下载 {url}")
    with urllib.request.urlopen(url) as r, open(dest + ".part", "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        got = 0
        while True:
            chunk = r.read(1 << 16)
            if not chunk:
                break
            f.write(chunk)
            got += len(chunk)
            if total:
                print(f"\r  {got / 1048576:.1f} / {total / 1048576:.1f} MB", end="")
    print()
    os.replace(dest + ".part", dest)


def rewrite_hardlinks(src_tar_gz: str, out_bin: str) -> int:
    """把硬链接条目改写成完整文件内容，输出 gzip 压缩的 tar（命名为 .bin）。"""
    rewritten = 0
    seen_link: dict[tuple[int, int], bytes] = {}

    with tarfile.open(src_tar_gz, "r:gz") as tf, \
            open(out_bin, "wb") as raw, \
            gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0) as gz:

        for member in tf:
            if member.islnk():
                # 硬链接：找到它指向的那个成员，改成真实文件内容
                key = (member.linkname is not None) and member.linkname or ""
                target = tf.getmember(key) if key else None
                if target is None or not target.isreg():
                    print(f"  !! 无法解析硬链接 {member.name} -> {member.linkname}，跳过", file=sys.stderr)
                    continue
                data = tf.extractfile(target)
                payload = data.read() if data else b""

                member.type = tarfile.REGTYPE
                member.linkname = ""
                member.size = len(payload)

                # 手工写头 + 内容 + 对齐填充
                gz.write(member.tobuf())
                gz.write(payload)
                pad = (-len(payload)) % BLOCK
                if pad:
                    gz.write(b"\0" * pad)
                rewritten += 1
                continue

            if member.isreg():
                src = tf.extractfile(member)
                payload = src.read() if src else b""
                gz.write(member.tobuf())
                gz.write(payload)
                pad = (-len(payload)) % BLOCK
                if pad:
                    gz.write(b"\0" * pad)
                continue

            if member.isdir() or member.issym():
                member.size = 0
                gz.write(member.tobuf())
                continue

            # 其余类型（fifo / dev 等）原样跳过，rootfs 里一般没有
            member.size = 0
            gz.write(member.tobuf())

        # tar 结束标记：两个 512 字节全零块
        gz.write(b"\0" * (BLOCK * 2))

    return rewritten


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default=DEFAULT_URL)
    ap.add_argument("--out", default=DEFAULT_OUT)
    ap.add_argument("--keep-tar", action="store_true", help="保留下载的原始 tar.gz")
    args = ap.parse_args()

    tgz = args.out + ".tar.gz"
    download(args.url, tgz)

    print("改写硬链接并输出 …")
    n = rewrite_hardlinks(tgz, args.out)
    size = os.path.getsize(args.out) / 1048576
    print(f"完成: {args.out}  {size:.1f} MB  改写了 {n} 个硬链接")

    if not args.keep_tar and os.path.exists(tgz):
        os.remove(tgz)

    if n == 0:
        print("提示: 没改写任何硬链接。若设备上 tar 报 link 失败，请确认源包版本。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
