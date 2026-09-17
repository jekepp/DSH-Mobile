# 第三方组件许可

DSH Mobile 自身以 **GNU General Public License v2.0** 授权（见 [LICENSE](LICENSE)）。

但本项目分发的 APK 与源码中**包含第三方组件**，它们的版权与许可如下。
分发本项目的构建产物时，请注意这些约束。

---

## 一、随 APK 分发

### proot（Termux 维护分支）

| 项 | 内容 |
| --- | --- |
| 许可 | **GPL-2.0** |
| 来源 | https://github.com/termux/proot |
| 出处文件 | `app/src/main/jniLibs/arm64-v8a/libproot.so`<br>`app/src/main/jniLibs/arm64-v8a/libproot-loader.so` |
| 版权 | Copyright (C) 2015 STMicroelectronics — Cédric Vincent 等 |

上游为 [proot-me/proot](https://github.com/proot-me/proot)，本项目使用的是 Termux 的移植版本
（增加了 Android 平台支持与 `--link2symlink` 等特性）。

> **说明**：本项目对 `libproot.so` 做了两处二进制改动 ——
> ① 将 `DT_NEEDED` 中的 `libtalloc.so.2` 原地等长改写为 `libtalloc_2.so`
> （Android 只解包 `lib*.so` 命名规则的文件，长名会被丢弃）；
> ② 随之将同名依赖文件一并改名为 `libtalloc_2.so` 随包分发。
> 改动脚本见 [`tools/patch_proot_soname.py`](tools/patch_proot_soname.py)。
> 这是对二进制文件名的修改，不改变其功能与行为。

### libtalloc

| 项 | 内容 |
| --- | --- |
| 许可 | **GPL-3.0**（Termux 打包声明） |
| 来源 | https://github.com/termux/termux-packages/tree/master/packages/libtalloc |
| 出处文件 | `app/src/main/jniLibs/arm64-v8a/libtalloc_2.so` |
| 说明 | proot 的运行时依赖（内存池库） |

### libandroid-shmem

| 项 | 内容 |
| --- | --- |
| 许可 | **BSD 3-Clause** |
| 来源 | https://github.com/termux/libandroid-shmem |
| 出处文件 | `app/src/main/jniLibs/arm64-v8a/libandroid-shmem.so` |

### Ubuntu Base rootfs

| 项 | 内容 |
| --- | --- |
| 许可 | 各组件分属不同自由软件许可（GPL / LGPL / BSD / MIT 等） |
| 来源 | https://cdimage.ubuntu.com/ubuntu-base/ |
| 出处 | 构建期生成的 `app/src/main/assets/rootfs.bin`（**不随仓库分发**） |

rootfs 内含 bash、coreutils、dpkg、apt 等大量 GPL/LGPL 组件。
其完整源码可从 Ubuntu 官方归档获取：https://archive.ubuntu.com/ubuntu/

### Node.js

| 项 | 内容 |
| --- | --- |
| 许可 | **MIT** |
| 来源 | https://nodejs.org |
| 出处 | 构建期打进 `app/src/main/assets/seed.bin`（**不随仓库分发**） |

### @deepseek-ai/dsh

| 项 | 内容 |
| --- | --- |
| 许可 | **MIT** |
| 来源 | https://github.com/deepseek-ai/deepseek-harness |
| 出处 | 同上，打进 `seed.bin` |

---

## 二、源码内

### dsh-client-ui-mobile-shell（本仓库自带）

| 项 | 内容 |
| --- | --- |
| 许可 | MIT（见 `plugin/dsh-client-ui-mobile-shell/package.json`） |
| 说明 | 本项目自行实现的 dsh 客户端插件，非第三方代码 |

---

## 三、参考但**未使用代码**的项目

以下项目仅提供了设计思路与结论参考，本项目**没有引入其任何代码**：

- [Hotsteel2901/dsh-client-ui-mobile-adapt](https://github.com/Hotsteel2901/dsh-client-ui-mobile-adapt)（MIT）

---

## 四、合规提示

本项目的 APK 内嵌分发了 **GPL-2.0**（proot）与 **GPL-3.0**（libtalloc）授权的二进制，
因此**整个 APK 的分发受 GPL 约束**：

- 分发 APK 时须一并提供（或提供获取途径）其对应源码；
- 不得将包含这些二进制的产物以专有许可分发。

本项目源码已公开于本仓库，可作为该义务的履行方式。