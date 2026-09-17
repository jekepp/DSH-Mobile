#!/usr/bin/env python3
"""把 libproot.so 的 DT_NEEDED 里 libtalloc.so.2 改成等长的 libtalloc_2.so。

为什么：APK 只会把 lib 前缀 .so 形式的文件解到 nativeLibraryDir，
而 Termux 版 proot 的 DT_NEEDED 写死是 libtalloc.so.2 —— 名字对不上就报
"library libtalloc.so.2 not found"，proot 根本起不来。

旧名字  libtalloc.so.2  = 14 字符
新名字  libtalloc_2.so  = 14 字符

长度相等 ⇒ 只需在 .dynstr 里原地替换这 14 个字节，
不移动任何节、不改任何偏移、不需要 patchelf，
（Nexus-Mind 的经验是「绝不 patchelf 面向 Android 的 ELF」，因为重排会惹恼 bionic；
  原地等长替换不属于那种情况。）
"""
import struct
import sys

OLD = b"libtalloc.so.2"
NEW = b"libtalloc_2.so"
assert len(OLD) == len(NEW), "必须等长，否则要改 .dynstr 尺寸和所有偏移"

PATH = sys.argv[1] if len(sys.argv) > 1 else (
    "/workspace/dsh-android/app/src/main/jniLibs/arm64-v8a/libproot.so")


def read_dynstr(data):
    e_shoff = struct.unpack_from("<Q", data, 0x28)[0]
    e_shentsize = struct.unpack_from("<H", data, 0x3A)[0]
    e_shnum = struct.unpack_from("<H", data, 0x3C)[0]
    e_shstrndx = struct.unpack_from("<H", data, 0x3E)[0]
    secs = []
    for i in range(e_shnum):
        o = e_shoff + i * e_shentsize
        vals = struct.unpack_from("<IIQQQQIIQQ", data, o)
        secs.append(dict(name=vals[0], off=vals[4], size=vals[5]))
    shstr = secs[e_shstrndx]
    for s in secs:
        i = shstr["off"] + s["name"]
        nm = data[i:data.index(b"\x00", i)].decode()
        if nm == ".dynstr":
            return s
    raise SystemExit("找不到 .dynstr")


def main():
    data = bytearray(open(PATH, "rb").read())
    assert data[:4] == b"\x7fELF", "不是 ELF"
    ds = read_dynstr(data)
    blob = bytes(data[ds["off"]:ds["off"] + ds["size"]])
    idx = blob.find(OLD + b"\x00")
    if idx < 0:
        # 已经是新名字就幂等退出
        if blob.find(NEW + b"\x00") >= 0:
            print("已是新名字，无需处理")
            return
        raise SystemExit(f"在 .dynstr 里找不到 {OLD!r}")
    abs_off = ds["off"] + idx
    print(f".dynstr @ {ds['off']} size {ds['size']}  命中偏移 {abs_off}")
    print(f"  before: {bytes(data[abs_off:abs_off+16])!r}")
    data[abs_off:abs_off + len(OLD)] = NEW
    print(f"  after : {bytes(data[abs_off:abs_off+16])!r}")
    open(PATH, "wb").write(bytes(data))
    print(f"已写回 {PATH}")

    # 复核
    d2 = open(PATH, "rb").read()
    e_shoff = struct.unpack_from("<Q", d2, 0x28)[0]
    e_shentsize = struct.unpack_from("<H", d2, 0x3A)[0]
    e_shnum = struct.unpack_from("<H", d2, 0x3C)[0]
    e_shstrndx = struct.unpack_from("<H", d2, 0x3E)[0]
    secs = []
    for i in range(e_shnum):
        o = e_shoff + i * e_shentsize
        v = struct.unpack_from("<IIQQQQIIQQ", d2, o)
        secs.append(dict(name=v[0], off=v[4], size=v[5], link=v[6], entsize=v[9]))
    shstr = secs[e_shstrndx]
    nm = lambda x: d2[shstr["off"] + x:d2.index(b"\x00", shstr["off"] + x)].decode()
    dynstr = next(s for s in secs if nm(s["name"]) == ".dynstr")
    dynamic = next(s for s in secs if nm(s["name"]) == ".dynamic")
    needed = []
    for i in range(0, dynamic["size"], 16):
        tag, val = struct.unpack_from("<QQ", d2, dynamic["off"] + i)
        if tag == 0:
            break
        if tag == 1:
            j = dynstr["off"] + val
            needed.append(d2[j:d2.index(b"\x00", j)].decode())
    print("复核 DT_NEEDED =", needed)


if __name__ == "__main__":
    main()
