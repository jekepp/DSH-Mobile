package com.DSHAndroid.mobile.kernel

import android.content.Context
import android.net.ConnectivityManager
import com.DSHAndroid.mobile.settings.AppPrefs
import java.io.File
import java.nio.file.Files

/**
 * 内核层：proot + Ubuntu rootfs + Node + dsh。
 *
 * Android 10+ 的四条硬约束（决定了这里的写法）：
 *
 *  1. 应用私有目录里的文件带 app_data_file 标签，proot 的 loader 无法在那边 exec。
 *     唯一可靠的路径是 nativeLibraryDir（APK 里 lib/<abi>/ 下的 lib 前缀 .so 被解到那里，
 *     标签是 apk_data_file）。所以 proot 及其 loader 都以 lib 前缀 .so 随包分发。
 *
 *  2. proot 启动时默认把 loader 解到 $PROOT_TMP_DIR 再 execve，这一步会被拒。
 *     因此 loader 预置在 nativeLibraryDir，用 PROOT_LOADER 指过去，跳过解压+exec。
 *
 *  3. APK 只解 lib 前缀 .so，而 Termux 版 proot 的 DT_NEEDED 写死 libtalloc.so.2，
 *     名字对不上就 "library libtalloc.so.2 not found"。
 *     解法见 tools/patch_proot_soname.py：DT_NEEDED 原地等长替换成 libtalloc_2.so，
 *     文件也以 libtalloc_2.so 随包分发，两边对齐，不需要任何软链。
 *
 *  4. 直接 exec proot 会静默 exit 255，必须经 /system/bin/sh -c 中介。
 *
 * 另外两个打包期的坑：
 *  - AGP 会认出扩展名 .gz 的 asset 并自动解压改名（30MB 的 tar.gz 变成 106MB 裸 tar
 *    塞进包）。所以 assets 里的 tar.gz 一律改名成 .bin。
 *  - 解压用 toybox tar；Android 上 link(2) 会被拒，tar 里的硬链接条目会报
 *    "can't link ... Permission denied"。构建期已在 prepare_rootfs.py 里
 *    把硬链接改写成真实副本，所以设备上应看到退出码 0。
 */
object Kernel {

    const val ROOTFS_ASSET = "rootfs.bin"
    const val SEED_ASSET = "seed.bin"
    private const val MARK = ".installed"
    private const val SEED_MARK = ".seed-installed"

    /** proot 的 DT_NEEDED 实测值（补丁后），诊断时对照用 */
    const val EXPECTED_NEEDED = "libtalloc_2.so, libandroid-shmem.so, libc.so"

    /** dsh web 启动后打印的那行带 token 的 URL */
    val TOKEN_RE = Regex("http://127\\.0\\.0\\.1:(\\d+)/\\?token=(\\S+)")

    // ---- 路径 ----

    fun nativeDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir)
    fun prootBin(ctx: Context): File = File(nativeDir(ctx), "libproot.so")
    fun prootLoader(ctx: Context): File = File(nativeDir(ctx), "libproot-loader.so")

    fun baseDir(ctx: Context): File = File(ctx.filesDir, "dsh")
    fun rootfsDir(ctx: Context): File = File(baseDir(ctx), "linux")
    fun tmpDir(ctx: Context): File = File(baseDir(ctx), "tmp").apply { mkdirs() }
    fun homeDir(ctx: Context): File = File(baseDir(ctx), "home").apply { mkdirs() }
    fun wsDir(ctx: Context): File = File(baseDir(ctx), "workspace").apply { mkdirs() }

    /**
     * 实际绑进容器 `/workspace` 的宿主目录。
     *
     * 用户在首启选了文件夹就用它；没选、或者选的目录不在了（被删 / SD 卡拔了），
     * 一律退回 App 私有目录 —— 宁可工作区不好找，也不能让 proot 起不来。
     */
    fun workspaceHostDir(ctx: Context): File {
        val picked = AppPrefs.workspaceHostPath
        if (picked.isNullOrBlank()) return wsDir(ctx)
        val f = File(picked)
        return if (f.isDirectory && f.canRead()) f else wsDir(ctx)
    }
    fun libDir(ctx: Context): File = File(baseDir(ctx), "lib").apply { mkdirs() }
    fun tarballFile(ctx: Context): File = File(baseDir(ctx), ROOTFS_ASSET)
    fun seedFile(ctx: Context): File = File(baseDir(ctx), SEED_ASSET)

    fun isInstalled(ctx: Context): Boolean = File(rootfsDir(ctx), MARK).exists()
    fun isSeedInstalled(ctx: Context): Boolean = File(rootfsDir(ctx), SEED_MARK).exists()
    fun prootAvailable(ctx: Context): Boolean =
        prootBin(ctx).exists() && prootLoader(ctx).exists()

    private fun isSymlink(f: File) = try {
        Files.isSymbolicLink(f.toPath())
    } catch (_: Throwable) {
        false
    }

    private fun mark(f: File): String = when {
        !f.exists() && !isSymlink(f) -> "缺失"
        f.canExecute() -> "OK"
        else -> "存在但不可执行"
    }

    private fun sizeOf(f: File): String = try {
        if (f.exists()) "${f.length() / 1024}KB" else "-"
    } catch (_: Throwable) {
        "-"
    }

    fun statusReport(ctx: Context): String = buildString {
        appendLine("nativeLibraryDir : ${nativeDir(ctx)}")
        appendLine("libproot.so      : ${mark(prootBin(ctx))}  ${sizeOf(prootBin(ctx))}")
        appendLine("libproot-loader  : ${mark(prootLoader(ctx))}  ${sizeOf(prootLoader(ctx))}")
        appendLine("libtalloc_2.so   : ${mark(File(nativeDir(ctx), "libtalloc_2.so"))}")
        appendLine("libandroid-shmem : ${mark(File(nativeDir(ctx), "libandroid-shmem.so"))}")
        appendLine("rootfs           : ${if (isInstalled(ctx)) "已就绪" else "未安装"}")
        appendLine("node+dsh seed    : ${if (isSeedInstalled(ctx)) "已就绪" else "未安装"}")
        appendLine("移动适配插件     : ${mobileShellStatus(ctx)}")
        appendLine("workspace        : ${wsDir(ctx)}")
    }

    /** 移动适配插件的落盘状态，诊断用 */
    fun mobileShellStatus(ctx: Context): String {
        val home = dshHomeDir(ctx)
        val pkg = File(File(home, "profiles/node_modules"), MOBILE_SHELL_PKG)
        val patch = File(home, "cordis.patch.yml")
        val installed = File(pkg, "package.json").exists()
        val row = try {
            patch.exists() && patch.readText().contains(MOBILE_SHELL_ROW_ID)
        } catch (_: Throwable) {
            false
        }
        return when {
            installed && row -> "已安装并已挂载"
            installed -> "已安装但未挂载（cordis.patch.yml 缺插件行）"
            else -> "未安装"
        }
    }


    /** proot 与 guest 的运行环境 */
    fun env(ctx: Context): Map<String, String> = mapOf(
        "PROOT_TMP_DIR" to tmpDir(ctx).absolutePath,
        "PROOT_LOADER" to prootLoader(ctx).absolutePath,
        "PROOT_NO_SECCOMP" to "1",
        // libDir 在前：里面是 soname 别名软链，真身在可执行的 nativeLibraryDir
        "LD_LIBRARY_PATH" to "${libDir(ctx).absolutePath}:${nativeDir(ctx).absolutePath}",
        // 用 guest 内的路径更干净：proot 会把它们翻译到绑定目录
        "HOME" to "/root",
        "DSH_HOME" to GUEST_DSH_HOME,
        "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM" to "xterm-256color",
        "LANG" to "C.UTF-8",
    )

    fun prootArgs(ctx: Context, guestCmd: List<String>): List<String> {
        val a = ArrayList<String>()
        a += prootBin(ctx).absolutePath
        a += listOf("-r", rootfsDir(ctx).absolutePath)
        a += "-0"                       // 伪 root（uid/gid 0）
        a += "--link2symlink"           // Termux 补丁：硬链接转符号链接
        a += "--kill-on-exit"
        a += listOf("-b", "/dev")       // 内核管的文件系统交给宿主
        a += listOf("-b", "/proc")
        a += listOf("-b", "/sys")
        a += listOf("-b", "${workspaceHostDir(ctx).absolutePath}:/workspace")
        a += listOf("-b", "${homeDir(ctx).absolutePath}:/root")
        // ★ 工作目录必须是容器里的工作区，不能是 /root。
        //   dsh 判断会话能否挂到工作区用的是 `cwd !== workspace.path`（严格相等），
        //   所以这里的 -w 必须和写进注册表的 path 是同一个字符串。
        a += listOf("-w", guestWorkspace)
        a += guestCmd
        return a
    }

    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** 经 /system/bin/sh 中介启动 proot */
    fun start(ctx: Context, guestCmd: List<String>): Process {
        val line = "exec " + prootArgs(ctx, guestCmd).joinToString(" ") { q(it) }
        val pb = ProcessBuilder("/system/bin/sh", "-c", line)
        pb.environment().putAll(env(ctx))
        pb.redirectErrorStream(true)
        return pb.start()
    }

    /** 宿主侧命令（不经 proot） */
    fun host(ctx: Context, script: String): Process {
        val pb = ProcessBuilder("/system/bin/sh", "-c", script)
        pb.environment().putAll(env(ctx))
        pb.redirectErrorStream(true)
        return pb.start()
    }


    /**
     * 清掉上次残留的 dsh 进程。
     *
     * 为什么需要：App 进程被系统杀掉时，proot 里的 node 往往还活着、继续占着 3080，
     * 于是下一次启动就 EADDRINUSE 起不来。这里在启动前扫一遍 /proc，
     * 把 cmdline 里带 dsh 入口的进程全部 kill 掉。
     *
     * 安全性：本函数在「新进程还没启动」时调用，所以不会误杀自己。
     * 而且 dsh 的会话是落盘持久化的，杀掉进程不丢数据。
     */
    fun killStaleDsh(ctx: Context, log: (String) -> Unit): Int {
        val script = """
            killed=0
            for p in /proc/[0-9]*; do
              [ -r "${'$'}p/cmdline" ] || continue
              c=${'$'}(tr '\0' ' ' < "${'$'}p/cmdline" 2>/dev/null)
              case "${'$'}c" in
                *dsh/lib/bin.js*|*"dsh web"*|*dsh-web*)
                  pid=${'$'}{p#/proc/}
                  if [ "${'$'}pid" != "${'$'}${'$'}" ]; then
                    kill -9 "${'$'}pid" 2>/dev/null && killed=${'$'}((killed+1))
                  fi
                  ;;
              esac
            done
            echo "killed=${'$'}killed"
        """.trimIndent()
        log("清理残留 dsh 进程…")
        val p = start(ctx, listOf("/bin/sh", "-c", script))
        var n = -1
        p.inputStream.bufferedReader().forEachLine { line ->
            log("  $line")
            Regex("killed=(\\d+)").find(line)?.let { n = it.groupValues[1].toInt() }
        }
        p.waitFor()
        if (n > 0) log("  已清理 $n 个残留进程")
        return n
    }

    // ---- 安装 ----


    private fun extract(ctx: Context, assetName: String, cacheFile: File,
                        destDir: File, log: (String) -> Unit): Boolean {
        if (!cacheFile.exists() || cacheFile.length() < 1_000_000) {
            log("释放 assets/$assetName → ${cacheFile.name}")
            ctx.assets.open(assetName).use { ins ->
                cacheFile.outputStream().use { outs -> ins.copyTo(outs, 1 shl 16) }
            }
        }
        log("tarball: ${cacheFile.length() / 1048576} MB")
        destDir.mkdirs()
        log("解压到 ${destDir.absolutePath}（请稍候）")
        val p = host(ctx, "cd ${q(destDir.absolutePath)} && " +
                "/system/bin/toybox tar xzf ${q(cacheFile.absolutePath)} 2>&1")
        p.inputStream.bufferedReader().forEachLine { log("  $it") }
        val rc = p.waitFor()
        log("tar 退出码 $rc")
        return rc == 0
    }

    fun installRootfs(ctx: Context, log: (String) -> Unit): Boolean {
        baseDir(ctx).mkdirs()
        if (isInstalled(ctx)) {
            log("rootfs 已安装，跳过（要重来请点「全清」）")
            return true
        }
        extract(ctx, ROOTFS_ASSET, tarballFile(ctx), rootfsDir(ctx), log)
        val dest = rootfsDir(ctx)
        if (File(dest, "bin").exists() && File(dest, "etc").exists()) {
            File(dest, MARK).writeText("ok")
            log("rootfs 安装完成 ✅")
            return true
        }
        log("rootfs 安装失败：没找到 bin/ 与 etc/")
        return false
    }

    fun installSeed(ctx: Context, log: (String) -> Unit): Boolean {
        if (!isInstalled(ctx)) {
            log("!! 先装 rootfs")
            return false
        }
        if (isSeedInstalled(ctx)) {
            log("node+dsh 已安装，跳过")
            return true
        }
        extract(ctx, SEED_ASSET, seedFile(ctx), rootfsDir(ctx), log)
        val node = File(rootfsDir(ctx), "opt/node/bin/node")
        val dsh = File(rootfsDir(ctx), "opt/dsh/node_modules/@deepseek-ai/dsh/lib/bin.js")
        if (node.exists() && dsh.exists()) {
            File(rootfsDir(ctx), SEED_MARK).writeText("ok")
            log("node + dsh 安装完成 ✅")
            return true
        }
        log("seed 安装失败：node=${node.exists()} dsh=${dsh.exists()}")
        return false
    }

    // ---- 移动端适配插件 ----

    /** 随包的 dsh 客户端插件（tar.gz，改名 .bin 规避 AGP 的 .gz 自动解压） */
    const val MOBILE_SHELL_ASSET = "mobile-shell.bin"
    private const val MOBILE_SHELL_PKG = "dsh-client-ui-mobile-shell"
    private const val MOBILE_SHELL_ROW_ID = "ui-mobile-shell"

    /** 写进 $DSH_HOME/cordis.patch.yml 的那一行 */
    private val MOBILE_SHELL_PATCH_ROW = """
        |- insert:
        |    - id: $MOBILE_SHELL_ROW_ID
        |      name: '$MOBILE_SHELL_PKG'
        |""".trimMargin()

    /**
     * 把随包的移动端适配插件装进 dsh 的 profile。
     *
     * 为什么是这两个位置：
     *  - `$DSH_HOME/profiles/node_modules/` —— 所有 profile 共享，是 dsh 自己维护的
     *    模块回退目录（healProfilesModuleFallback 只往里补安装闭包的软链，不会删别的）。
     *    Node 解析从 `profiles/web/` 往上走，所以放这里 web profile 一定能 require 到。
     *  - `$DSH_HOME/profiles/web/node_modules/` —— profile 自己的权威目录，等价于
     *    `dsh plugin --profile web add` 的落地位置；只在 profile 已存在时才写，
     *    避免抢在 dsh 前面把目录建出来干扰它的初始化。
     *
     * 挂载点是 **home 级** 的 `$DSH_HOME/cordis.patch.yml`：dsh 会把它叠在每个 profile
     * 自己的 patch 层之上（见 @deepseek-ai/dsh profile-boot 的 allPatches 顺序），
     * 所以不用去动 profile 的 package.json，也不会被 profile 重建冲掉。
     *
     * 每次启动都重装一遍：插件才十几 KB，用「永远最新」换掉一整类版本不同步的问题。
     */
    fun installMobileShell(ctx: Context, log: (String) -> Unit): Boolean {
        val home = dshHomeDir(ctx)
        val profilesDir = File(home, "profiles")
        return try {
            val cache = File(baseDir(ctx), MOBILE_SHELL_ASSET)
            ctx.assets.open(MOBILE_SHELL_ASSET).use { ins ->
                cache.outputStream().use { outs -> ins.copyTo(outs, 1 shl 16) }
            }
            val staging = File(tmpDir(ctx), "mobile-shell")
            staging.deleteRecursively()
            staging.mkdirs()
            val p = host(ctx, "/system/bin/toybox tar xzf ${q(cache.absolutePath)} " +
                    "-C ${q(staging.absolutePath)} 2>&1")
            p.inputStream.bufferedReader().forEachLine { log("  $it") }
            if (p.waitFor() != 0) {
                log("移动适配插件：解包失败")
                return false
            }
            val pkg = File(staging, MOBILE_SHELL_PKG)
            if (!File(pkg, "package.json").exists()) {
                log("移动适配插件：包结构不对，缺 package.json")
                return false
            }

            val targets = ArrayList<File>()
            targets += File(profilesDir, "node_modules")
            if (File(profilesDir, "web").isDirectory) targets += File(profilesDir, "web/node_modules")

            for (modules in targets) {
                modules.mkdirs()
                val dest = File(modules, MOBILE_SHELL_PKG)
                dest.deleteRecursively()
                pkg.copyRecursively(dest, overwrite = true)
            }
            ensureMobileShellRow(home, log)
            log("移动适配插件已就位：$MOBILE_SHELL_PKG（${targets.size} 个位置）")
            true
        } catch (t: Throwable) {
            log("移动适配插件：安装失败 ${t.message}")
            false
        }
    }

    /**
     * 保证 $DSH_HOME/cordis.patch.yml 里有插件那一行。
     *
     * 这个文件是用户自己的东西，所以分三种情况处理，绝不整文件覆盖：
     *  - 不存在 → 新建
     *  - 只有注释和 `[]`（等价于空） → 改写成我们的内容
     *  - 已有真实内容 → 追加，保留用户原有条目
     */
    private fun ensureMobileShellRow(home: File, log: (String) -> Unit) {
        val patch = File(home, "cordis.patch.yml")
        val header = "# 由 DSH Mobile 维护：把自研的移动端适配客户端插件挂进 profile。\n" +
                "# 这是 home 级 patch 层，会叠加在每个 profile 自己的 patch 之上。\n"

        if (!patch.exists()) {
            patch.writeText(header + MOBILE_SHELL_PATCH_ROW)
            log("  cordis.patch.yml: 新建并挂上移动适配插件")
            return
        }

        val txt = try {
            patch.readText()
        } catch (_: Throwable) {
            ""
        }
        if (txt.contains(MOBILE_SHELL_ROW_ID)) {
            log("  cordis.patch.yml: 插件行已在")
            return
        }

        // 去掉注释行与空行后判断是否等价于空 patch
        val effective = txt.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString("\n")
        if (effective.isEmpty() || effective == "[]") {
            patch.writeText(header + MOBILE_SHELL_PATCH_ROW)
            log("  cordis.patch.yml: 原本是空 patch，写入插件行")
        } else {
            patch.appendText((if (txt.endsWith("\n")) "" else "\n") + MOBILE_SHELL_PATCH_ROW)
            log("  cordis.patch.yml: 追加插件行（保留原有内容）")
        }
    }

    // ---- DNS ----

    /** 从系统拿当前网络的 DNS 服务器 */
    fun androidDns(ctx: Context): List<String> = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nw = cm.activeNetwork
        val lp = nw?.let { cm.getLinkProperties(it) }
        lp?.dnsServers?.mapNotNull { it.hostAddress }?.filter { it.isNotBlank() } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    /** 给 guest 写 /etc/resolv.conf —— ubuntu-base 里这个是空的，不写就没 DNS */
    fun writeResolvConf(ctx: Context, log: (String) -> Unit) {
        val sys = androidDns(ctx)
        val dns = if (sys.isNotEmpty()) sys else listOf("1.1.1.1", "8.8.8.8")
        val f = File(rootfsDir(ctx), "etc/resolv.conf")
        f.parentFile?.mkdirs()
        f.writeText(dns.joinToString("\n") { "nameserver $it" } + "\n")
        log("resolv.conf ← ${dns.joinToString(", ")}" + if (sys.isEmpty()) "（系统取不到，用公共 DNS）" else "")
    }

    // ---- $DSH_HOME 预置 ----

    /** guest 内的 $DSH_HOME；宿主侧对应 homeDir/.dsh */
    const val GUEST_DSH_HOME = "/root/.dsh"

    fun dshHomeDir(ctx: Context): File = File(homeDir(ctx), ".dsh").apply { mkdirs() }

    /**
     * 预置 $DSH_HOME，把 dsh 的「首次使用引导」整条跳过。
     *
     * 为什么要这么做：dsh 首启有三道引导（内测声明 → 填 API key → 选工作区），
     * 其中「选工作区」那一屏在 Android WebView 里会变成白屏（浏览器端会用
     * File System Access API 之类的能力，WebView 不支持）。与其在那边修，
     * 不如直接把这些状态写好：
     *
     *  - settings.yaml : ui-onboarding.welcomeNoticeVersion → 内测声明已读
     *  - storages/workspace.json : 预置一个指向 guest /workspace 的工作区
     *    （也就是 App 私有目录里的 dsh/workspace，Agent 读写文件的地方）
     *
     * workspace.json 的 schema 来自 @deepseek-ai/dsh-workspace 的 spec：
     * unit{name,version} + global{initialized,workspaceIds,archivedSessionIds}
     * + tables.workspaces{<id>:{path,title,sessionIds,createdAt,updatedAt}}
     */
    fun seedDshHome(ctx: Context, log: (String) -> Unit) {
        val home = dshHomeDir(ctx)
        log("预置 DSH_HOME → ${home.absolutePath}")
        val settings = File(home, "settings.yaml")
        if (!settings.exists()) {
            settings.writeText("ui-onboarding:\n  welcomeNoticeVersion: 2026-08-13.1\n")
            log("  settings.yaml: 新建，内测声明标记为已读")
        } else {
            val txt = try {
                settings.readText()
            } catch (_: Throwable) {
                ""
            }
            when {
                txt.contains("welcomeNoticeVersion") ->
                    log("  settings.yaml: 内测声明已标记过")
                // 只在完全没有 ui-onboarding 段时才追加，避免造出重复 key 让 YAML 解析炸掉
                !txt.contains("ui-onboarding") -> {
                    settings.appendText(
                        (if (txt.endsWith("\n") || txt.isEmpty()) "" else "\n") +
                        "ui-onboarding:\n  welcomeNoticeVersion: 2026-08-13.1\n"
                    )
                    log("  settings.yaml: 追加内测声明已读标记")
                }
                else ->
                    log("  settings.yaml: 已有 ui-onboarding 段，保持原样")
            }
        }

        val storages = File(home, "storages").apply { mkdirs() }
        val ws = File(storages, "workspace.json")

        // 不再预置工作区。工作区交给用户自己建（App 侧有原生管理页）。
        // 如果之前版本塞过我们那个默认的 /workspace，这里顺手清掉，
        // 但只在「注册表里就只剩它一个」时才动，避免误删用户自己加的工作区。
        if (ws.exists()) {
            val onlyOurs = try {
                val root = org.json.JSONObject(ws.readText())
                val workspaces = root.optJSONObject("tables")?.optJSONObject("workspaces")
                val ids = workspaces?.keys()?.asSequence()?.toList().orEmpty()
                ids == listOf(LEGACY_PRESET_ID)
            } catch (_: Throwable) {
                false
            }
            if (onlyOurs) {
                ws.delete()
                log("  workspace.json: 清掉旧版预置的 $LEGACY_PRESET_ID（工作区改由 App 管理）")
            } else {
                log("  workspace.json: 保留（用户已有自己的工作区）")
            }
        }
    }

    /** 旧版本往 workspace.json 里塞过的默认工作区 id */
    private const val LEGACY_PRESET_ID = "dshmobile-default"

    /** 容器内工作区的挂载点。用户选的宿主文件夹会被绑到这里。 */
    const val GUEST_WORKSPACE = "/workspace"

    /**
     * 本次启动解析出来的容器内工作区**规范路径**。
     *
     * ★ 为什么不能直接用常量 `/workspace`：
     *   dsh 挂载会话的判断是 `cwd !== workspace.path` 就报错（严格相等，不是「在里面」），
     *   而它两边都会做 realpath。我们如果往注册表里写死 `/workspace`，
     *   一旦容器里 `/workspace` 是软链或挂载别名，realpath 出来的就不是这个字符串，
     *   会话永远挂不上工作区 —— 表现是「工作区选中了，但输入框一直禁用、打不了字」。
     *
     *   所以这里在容器里真的跑一次 `realpath /workspace`，把结果同时用于
     *   ① 写进 dsh 的工作区注册表 ② proot 的 -w 启动目录。两边同源，必然相等。
     */
    @Volatile
    private var guestWorkspace: String = GUEST_WORKSPACE

    fun guestWorkspacePath(): String = guestWorkspace

    /**
     * 在容器里解析 /workspace 的规范路径。
     * 解析失败就退回挂载点本身（此时注册表与 -w 仍用同一个值，不会不一致）。
     */
    private fun resolveGuestWorkspace(ctx: Context, log: (String) -> Unit): String {
        return try {
            // 一次问清三件事：
            //   REALPATH —— 规范路径（要写进注册表，dsh 那边是严格字符串比较）
            //   LS       —— 它到底是什么（目录？软链？权限？）
            //   WRITE    —— 可不可写（dsh 建会话时会 mkdir 这个目录，不可写就挂不上工作区）
            val script = "echo REALPATH=${'$'}(realpath /workspace 2>/dev/null || echo /workspace); " +
                "echo LS=${'$'}(ls -ld /workspace 2>&1 | head -1); " +
                "if touch /workspace/.dshm-probe 2>/dev/null; then echo WRITE=ok; " +
                "rm -f /workspace/.dshm-probe; else echo WRITE=fail; fi"
            val p = start(ctx, listOf("/bin/sh", "-c", script))
            val lines = p.inputStream.bufferedReader().useLines { it.toList() }
            p.waitFor()
            for (line in lines) log("  /workspace 体检: $line")
            val resolved = lines.firstOrNull { it.startsWith("REALPATH=") }
                ?.removePrefix("REALPATH=")?.trim()
                ?.takeIf { it.startsWith("/") }
                ?: GUEST_WORKSPACE
            log("容器内工作区规范路径 = $resolved")
            resolved
        } catch (t: Throwable) {
            log("解析 /workspace 规范路径失败（${t.message}），退回 $GUEST_WORKSPACE")
            GUEST_WORKSPACE
        }
    }

    /**
     * 把用户选的工作区写进 dsh 的工作区注册表。
     *
     * schema 来自 `@deepseek-ai/dsh-workspace` 的 `defineDomain`：
     *   unit{name,version} + global{initialized,workspaceIds,archivedSessionIds}
     *   + tables.workspaces{<uuid>:{path,title,sessionIds,createdAt,updatedAt}}
     * id 用 UUID —— dsh 自己也是 randomUUID；path 必须是 **guest 路径**（dsh 会对它做 realpath）。
     *
     * 另外清掉 path 正好是 `/root` 且没有会话的记录：那是 dsh 把 proot 的启动目录
     * 当成工作区留下的，标题显示成 `root`、用户根本找不到 —— 就是之前抱怨的那个东西。
     * 只删这一种，用户自己建的工作区一律保留。
     */
    fun writeWorkspaceRegistry(ctx: Context, log: (String) -> Unit) {
        val picked = AppPrefs.workspaceHostPath
        val storages = File(dshHomeDir(ctx), "storages").apply { mkdirs() }
        val file = File(storages, "workspace.json")
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())

        try {
            val root = if (file.exists()) {
                try {
                    org.json.JSONObject(file.readText())
                } catch (_: Throwable) {
                    org.json.JSONObject()
                }
            } else {
                org.json.JSONObject()
            }

            val tables = root.optJSONObject("tables") ?: org.json.JSONObject().also {
                root.put("tables", it)
            }
            val workspaces = tables.optJSONObject("workspaces") ?: org.json.JSONObject().also {
                tables.put("workspaces", it)
            }
            val global = root.optJSONObject("global") ?: org.json.JSONObject().also {
                root.put("global", it)
            }

            // ★ 取证：把「上一次 dsh 留下的注册表」原样打出来。
            //   这条能直接回答：我的记录还在不在？dsh 是不是又加了一条？哪条有会话？
            if (workspaces.length() == 0) {
                log("  [注册表] 原有工作区：空")
            } else {
                for (id in workspaces.keys()) {
                    val r = workspaces.optJSONObject(id) ?: continue
                    val sessions = r.optJSONArray("sessionIds")?.length() ?: 0
                    log(
                        "  [注册表] 原有工作区: path=${r.optString("path", "")} " +
                            "title=${r.optString("title", "")} sessions=$sessions id=${id.take(8)}"
                    )
                }
            }
            log("  [注册表] 顺序表 workspaceIds=" + (global.optJSONArray("workspaceIds")?.toString() ?: "[]"))

            // 1) 清掉「proot 启动目录」留下的空工作区
            var dropped = 0
            val toDrop = ArrayList<String>()
            for (id in workspaces.keys()) {
                val rec = workspaces.optJSONObject(id) ?: continue
                val path = rec.optString("path", "")
                val sessions = rec.optJSONArray("sessionIds")
                // 两类僵尸记录：proot 启动目录留下的 /root；
                // 以及早期版本写死的字面量 /workspace（当真实规范路径与它不同时）。
                val stale = path == "/root" ||
                    (path == GUEST_WORKSPACE && GUEST_WORKSPACE != guestWorkspace)
                if (stale && (sessions == null || sessions.length() == 0)) toDrop += id
            }
            for (id in toDrop) {
                workspaces.remove(id)
                dropped++
            }

            // 2) 保证选定的工作区在表里（已存在就复用，不重复建）
            var chosenId: String? = null
            for (id in workspaces.keys()) {
                if (workspaces.optJSONObject(id)?.optString("path", "") == guestWorkspace) {
                    chosenId = id
                    break
                }
            }
            if (picked != null && chosenId == null) {
                chosenId = java.util.UUID.randomUUID().toString()
                val rec = org.json.JSONObject()
                rec.put("path", guestWorkspace)
                rec.put(
                    "title",
                    AppPrefs.workspaceTitle
                        ?: File(picked).name.ifBlank { "workspace" },
                )
                rec.put("sessionIds", org.json.JSONArray())
                rec.put("createdAt", stamp)
                rec.put("updatedAt", stamp)
                workspaces.put(chosenId, rec)
            }

            // 3) 顺序表：选定的排最前，其余按原顺序保留
            val prior = global.optJSONArray("workspaceIds")
            val order = ArrayList<String>()
            if (chosenId != null) order += chosenId
            if (prior != null) {
                for (i in 0 until prior.length()) {
                    val id = prior.optString(i, "")
                    if (id.isNotEmpty() && id != chosenId && workspaces.has(id)) order += id
                }
            }
            // 兜底：表里还有不在顺序表里的，补进去，免得 dsh 认为注册表不一致
            for (id in workspaces.keys()) if (!order.contains(id)) order += id

            global.put("initialized", true)
            global.put("workspaceIds", org.json.JSONArray(order))
            if (!global.has("archivedSessionIds")) {
                global.put("archivedSessionIds", org.json.JSONArray())
            }

            val unit = root.optJSONObject("unit") ?: org.json.JSONObject()
            unit.put("name", "workspace")
            unit.put("version", 2)
            root.put("unit", unit)

            file.writeText(root.toString(2))
            val title = chosenId?.let { workspaces.optJSONObject(it)?.optString("title") } ?: "（无）"
            log(
                "工作区注册表: " +
                    (if (picked != null) "已设为 “$title” → $guestWorkspace" else "未选择，保持 dsh 默认") +
                    (if (dropped > 0) "，顺手清掉 $dropped 条 /root 残留" else "")
            )
        } catch (t: Throwable) {
            log("工作区注册表写入失败：${t.message}")
        }
    }


    private const val GUEST_SHELL =
        "unset LD_LIBRARY_PATH; " +
        "export PATH=/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin; "


    /**
     * 启动 dsh web，并把打印出来的 token URL 回调出去。
     * 进程活到 App 被杀为止（没有做自启保活，先用最简形态验证）。
     */
    fun startDshWeb(
        ctx: Context,
        port: Int,
        onLog: (String) -> Unit,
        onReady: (String) -> Unit,
        onExit: (Int) -> Unit,
    ): Process {
        writeResolvConf(ctx) { onLog(it) }
        seedDshHome(ctx, onLog)
        installMobileShell(ctx, onLog)
        killStaleDsh(ctx, onLog)

        // /workspace 是 proot 的 -b 挂载点、也是 -w 的工作目录，两者都不允许它缺失。
        // proot 一般会自己建挂载点，但这里显式建一下，免得个别环境直接起不来。
        File(rootfsDir(ctx), "workspace").mkdirs()

        // ★ 顺序很重要：先把容器里 /workspace 的规范路径解析出来，
        //   再写工作区注册表、再启动 dsh —— 这样注册表的 path 和 dsh 的 cwd
        //   用的是同一个字符串，dsh 的 `cwd !== path` 判断才会通过。
        guestWorkspace = resolveGuestWorkspace(ctx, onLog)
        writeWorkspaceRegistry(ctx, onLog)

        val p = start(ctx, listOf("/bin/sh", "-c",
            GUEST_SHELL + "exec dsh web --no-open --port $port"))
        Thread({ 
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    onLog(line)
                    TOKEN_RE.find(line)?.let { m ->
                        onReady("http://127.0.0.1:${m.groupValues[1]}/?token=${m.groupValues[2]}")
                    }
                }
            } catch (t: Throwable) {
                onLog("!! 读 stdout 出错: ${t.message}")
            }
            onExit(try { p.waitFor() } catch (_: Throwable) { -1 })
        }, "dsh-web").start()
        return p
    }

    fun stopDshWeb(p: Process?) {
        try {
            p?.destroy()
        } catch (_: Throwable) {
        }
    }
}
