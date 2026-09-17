# 构建说明

本文档记录在 **Windows** 上从零把本项目构建出来的完整过程，包含工具链版本、
以及一堆平台特有的坑。Linux / macOS 大同小异。

---

## 一、工具链版本组合（已验证可用，别乱升）

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| AGP | **9.4.0** | 内置 KGP 2.2.10 |
| Kotlin | 2.2.10 | 由 AGP 内置，**不能再 apply `org.jetbrains.kotlin.android`** |
| Compose 编译器插件 | 2.2.10 | 仍须显式声明，且版本必须与内置 KGP 一致 |
| Gradle | 9.7.1 | AGP 9.4 要求 ≥ 9.5.0 |
| Compose BOM | 2026.09.00 | |
| compileSdk / targetSdk | 37 | 平台目录名是 `android-37.0`，不是 `android-37` |
| buildToolsVersion | **37.0.0** | AGP 9 默认 36.0.0，必须显式指定 |
| minSdk | 29 | Android 10 |
| JDK | 17+ | |

`build.gradle.kts` 里两处**不能删**的配置：

```kotlin
android {
    buildToolsVersion = "37.0.0"
    packaging {
        jniLibs { useLegacyPackaging = true }   // 关键！否则 .so 不会被解到 nativeLibraryDir
    }
}
```

---

## 二、准备 assets（构建前必须做）

仓库不含 `app/src/main/assets/` 下的两个大文件。它们需要自行生成：

### 2.1 rootfs.bin（Ubuntu base，约 30 MB）

```bash
python3 tools/kernel/prepare_rootfs.py
```

它做两件事：下载 ubuntu-base 的 arm64 tar.gz，然后**把 tar 里的硬链接条目改写成真实文件副本**，
输出到 `app/src/main/assets/rootfs.bin`。

**为什么必须改写硬链接**：Android 上 `link(2)` 会被 SELinux 拒绝，
tar 解压硬链接条目会报 `can't link ... Permission denied`。
`ubuntu-base` 里只有 2 个硬链接（perl / uncompress），改写代价约 +0.6 MB。

（`--url` 可换源，`--out` 可换输出位置。）

### 2.2 seed.bin（Node 24 + dsh，约 95 MB）

需要在 Linux（或容器）里准备一个目录树，然后 `tar czf`：

```
/opt/node/bin/node                      ← Node 24 (linux-arm64)
/opt/node/lib/node_modules/npm          ← 保留，供用户联网升级
/opt/dsh/node_modules                   ← dsh 0.1.5-rc.1 的依赖
/usr/local/bin/node  → /opt/node/bin/node
/usr/local/bin/npm   → /opt/node/lib/node_modules/npm/bin/npm-cli.js
/usr/local/bin/dsh   → /opt/dsh/node_modules/.bin/dsh
```

⚠️ **Node 的 `include/` 目录（约 67 MB 开发头文件）要丢掉**，那是纯浪费。

### 2.3 两个文件都必须命名为 `.bin`

**不要用 `.tar.gz`** —— AGP 会认出 `.gz` 扩展名并自动解压改名，
把 106 MB 的裸 tar 原样塞进 APK（实测 APK 从 58 MB 涨到 136 MB）。
同时**不要**给 assets 设 `noCompress`，让 zip 自己 deflate 即可。

---

## 三、proot 那四个 .so（仓库已包含）

`app/src/main/jniLibs/arm64-v8a/` 下必须有：

| 文件 | 来源 |
| --- | --- |
| `libproot.so` | Termux `proot_5.1.107.92_aarch64.deb` 的 `usr/bin/proot` |
| `libproot-loader.so` | 同上，`usr/libexec/proot/loader` |
| `libtalloc_2.so` | Termux `libtalloc_2.4.3_aarch64.deb`，原名 `libtalloc.so.2` |
| `libandroid-shmem.so` | Termux `libandroid-shmem_0.7_aarch64.deb` |

### ⚠️ 如果重新拉了 proot，必须打补丁

```bash
python3 tools/patch_proot_soname.py
```

它把 `libproot.so` 的 `DT_NEEDED` 里 `libtalloc.so.2` **原地等长替换**成
`libtalloc_2.so`（都是 14 字符）。

**为什么要这么做**：APK 只会把「`lib` 前缀 + `.so` 结尾」的文件解到 nativeLibraryDir，
而 Termux 版 proot 的 `DT_NEEDED` 写死是 `libtalloc.so.2` ——
名字对不上就报 `library "libtalloc.so.2" not found`。
把两边改成一致的名字即可，不需要软链。

跑完应输出：

```
复核 DT_NEEDED = ['libtalloc_2.so', 'libandroid-shmem.so', 'libc.so']
```

⚠️ **绝不要用 patchelf**。bionic 会拒绝被重排过节结构的 ELF。
等长字符串替换不属于 patchelf。

---

## 四、构建

```bash
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 155 MB）

调试签名密钥在 `keystore/debug.keystore`（口令 `android`，别名 `androiddebugkey`），
**故意放进工程内**而不是用默认的 `~/.android/debug.keystore` ——
默认位置在受限环境（只读主目录 / CI）会直接
`AccessDeniedException: debug.keystore.lock` 把打包卡死。

---

## 五、Windows 上踩过的坑

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `sdkmanager` 报 "Failed to create bin dir" | 新版 Android CLI 要往用户主目录写东西 | 设 `ANDROID_USER_HOME` 到可写目录；或直接用 `android sdk install` |
| Gradle `Couldn't open current thread, error = 5` | 原生文件监视被拒 | `gradle.properties` 里 `org.gradle.vfs.watch=false` |
| `AccessDeniedException: debug.keystore.lock` | AGP 要写 `~/.android/debug.keystore` | 改用工程内密钥（已内置） |
| `You cannot add Provider instances to the Android SourceSet API` | AGP 默认禁止 | `android.sourceset.disallowProvider=false` |
| PowerShell 下载全失败（schannel 拒绝凭证） | 某些 Windows 上 PS 5.1 的 TLS 不可用 | 改用 Node 的 `fetch` 下载 |
| `AndroidVersion.ApiLevel=37.0` 让老加载器找不到 target | 官方 SDK 的 `source.properties` 写的是 `37.0` | 若报 `Failed to find target with hash string 'android-37.0'`，把该值改成整数 `37` |

---

## 六、构建后自检

```bash
aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk
```

应该看到：

- APK 内含 `lib/arm64-v8a/{libproot.so, libproot-loader.so, libtalloc_2.so, libandroid-shmem.so}`
- APK 内含 `assets/{rootfs.bin, seed.bin}`，以及移动适配插件的 `assets/mobile-shell.bin`
- 包内存在 `res/xml/network_security_config.xml`（为 `127.0.0.1` 开明文）
- 编译应**零警告**
