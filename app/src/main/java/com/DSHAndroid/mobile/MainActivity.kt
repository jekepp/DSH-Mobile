package com.DSHAndroid.mobile

import android.content.Context
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.DSHAndroid.mobile.kernel.Kernel
import com.DSHAndroid.mobile.settings.AppPrefs
import com.DSHAndroid.mobile.ui.SettingsScreen
import com.DSHAndroid.mobile.ui.WorkspacePickerScreen
import com.DSHAndroid.mobile.ui.BUILD_TAG
import com.DSHAndroid.mobile.ui.BootScreen
import com.DSHAndroid.mobile.ui.DshWebView
import com.DSHAndroid.mobile.ui.JS_DEEP_DIAG
import com.DSHAndroid.mobile.ui.theme.DshTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    /** 跨重组保活：dsh web 的进程 */
    private var dshProcess: Process? = null
    private var webView: WebView? = null

    /** 启动流程只允许跑一次 —— 重组的副作用曾导致 dsh 被拉起两次、撞端口 */
    private val bootOnce = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.load(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DshTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot()
                }
            }
        }
    }

    override fun onDestroy() {
        // WebView 必须显式销毁，否则它会持有 Activity 引用造成泄漏
        webView?.let { wv ->
            (wv.parent as? android.view.ViewGroup)?.removeView(wv)
            wv.stopLoading()
            wv.destroy()
        }
        webView = null
        super.onDestroy()
    }

    @Composable
    private fun AppRoot() {
        val ctx = LocalContext.current
        val logs = remember { mutableStateListOf<String>() }
        var step by remember { mutableStateOf("检查内核") }
        var detail by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var url by remember { mutableStateOf<String?>(null) }
        var showLog by remember { mutableStateOf(false) }
        var showAppSettings by remember { mutableStateOf(false) }
        // 首启等用户选工作区：挂起启动流程，直到这个 Deferred 被 complete
        var workspacePending by remember {
            mutableStateOf<kotlinx.coroutines.CompletableDeferred<Pair<String?, String?>>?>(null)
        }
        // App 外观设置变化时，用这个 key 触发重新注入 CSS
        val themeKey = AppPrefs.accent.hashCode() * 31 +
            AppPrefs.fontPx * 7 +
            AppPrefs.uiScale * 131 +
            (if (AppPrefs.animEnabled) 1 else 0)
        var attempt by remember { mutableStateOf(0) }

        fun post(f: () -> Unit) {
            runOnUiThread { f() }
        }

        fun log(s: String) {
            logs.add(s)
            if (logs.size > 1500) logs.removeRange(0, logs.size - 1500)
        }

        // 诊断信息只进日志缓冲区。
        // 早期版本会顺手复制到剪贴板方便贴给我排查，这属于开发期的临时手段 ——
        // 正式发布版不该动用户的剪贴板，已移除。日志仍可在「设置 → 高级 → 内核日志」里看。
        fun onDiag(s: String) {
            log(s)
        }

        LaunchedEffect(attempt) {
            // 首次进入放行；重组导致的重复触发要拦住；点了「重试」放行
            val firstTime = bootOnce.compareAndSet(false, true)
            if (!firstTime && attempt == 0) return@LaunchedEffect
            error = null
            url = null
            log("")
            val append: (String) -> Unit = { s -> post { log(s) } }
            post { log("=== DSH Mobile build v${BUILD_TAG} ===") }

            try {
                step = "检查内核组件"
                if (!Kernel.prootAvailable(ctx)) {
                    error = "proot 不在 nativeLibraryDir 里\n\n" + Kernel.statusReport(ctx)
                    return@LaunchedEffect
                }

                if (!Kernel.isInstalled(ctx)) {
                    step = "解包 Linux 系统"
                    detail = "首次启动，约需 1~2 分钟"
                    withContext(Dispatchers.IO) { Kernel.installRootfs(ctx, append) }
                } else {
                    append("rootfs 已就绪，跳过")
                }

                if (!Kernel.isSeedInstalled(ctx)) {
                    step = "解包 Node 与 dsh"
                    detail = "约 430MB，请耐心等待"
                    withContext(Dispatchers.IO) { Kernel.installSeed(ctx, append) }
                } else {
                    append("node + dsh 已就绪，跳过")
                }

                // 运行环境的准备（resolv.conf / DSH_HOME / 移动适配插件 / 工作区注册表）
                // 统一由 Kernel.startDshWeb 负责，这里不再重复做一遍 ——
                // 之前两边都调，日志里能看到「预置 DSH_HOME」刷两次，纯属白做功。

                // 首启先让用户选工作区文件夹，选完才继续。
                // 顺序放在「启动 dsh」之前是有意的：dsh 一起来就会读工作区注册表，
                // 提前写好它就不会再弹自己那套选择器（那套在 WebView 里会把 proot 的
                // 启动目录 /root 当成工作区，标题显示成 root，用户在文件管理器里找不到）。
                if (!AppPrefs.workspaceAsked) {
                    step = "选择工作区"
                    detail = "Agent 读写文件的地方，选一个你找得到的文件夹"
                    val gate = kotlinx.coroutines.CompletableDeferred<Pair<String?, String?>>()
                    post { workspacePending = gate }
                    append("等用户选择工作区文件夹…")
                    val choice = gate.await()
                    AppPrefs.setWorkspace(ctx, choice.first, choice.second)
                    append("工作区 → " + (choice.first ?: "（用默认目录）"))
                }

                step = "启动 dsh"
                detail = "已拉起进程，等它打印 token"
                withContext(Dispatchers.IO) {
                    dshProcess = Kernel.startDshWeb(
                        ctx = ctx,
                        port = 3080,
                        onLog = append,
                        onReady = { readyUrl ->
                            post {
                                if (url == null) {
                                    url = readyUrl
                                    step = "就绪"
                                    log("✅ dsh web 就绪")
                                }
                            }
                        },
                        onExit = { rc ->
                            post {
                                step = "dsh 已退出"
                                log("dsh web 退出 code=$rc")
                                if (url == null) error = "dsh web 提前退出（code=$rc），看下面日志。"
                            }
                        },
                    )
                }
            } catch (t: Throwable) {
                post {
                    error = "${t.javaClass.simpleName}: ${t.message}"
                    log("!! ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val u = url
            if (u != null) {
                DshWebView(
                    url = u,
                    onViewCreated = { webView = it },
                    onLog = { l -> post { log(l) } },
                    onDiag = { d -> post { onDiag(d) } },
                    extraCss = AppPrefs.themeCss(),
                    extraCssKey = themeKey,
                    // 抽屉左下角的「设置」由插件代理到这里：
                    // 入口统一到侧栏里那个位置，不再浮一个右下角圆钮挡内容。
                    onOpenAppSettings = { post { showAppSettings = true } },
                )
            } else {
                BootScreen(
                    step = step,
                    detail = detail,
                    error = error,
                    logs = logs,
                    onRetry = { attempt++ },
                )
            }

        // 右下角小圆钮 → 打开 App 自己的设置页（外观 / 工作区 / 高级·内核日志 / 关于）。
        // 曾经为了「入口统一到侧栏」把它删掉、改由插件劫持侧栏那个设置入口，
        // 但劫持在真机上没生效，等于把唯一的入口弄丢了 —— 所以加回来。
        // 插件那边的劫持保留：能生效时两个入口都通，不生效时这个兜底。
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 10.dp, bottom = 10.dp),
        ) {
            androidx.compose.material3.FilledTonalIconButton(
                onClick = { showAppSettings = true },
                modifier = Modifier.size(36.dp),
            ) {
                Text("⚙", fontSize = 14.sp)
            }
        }

        // 首启：工作区选择整屏盖在最上面，选完才继续拉起 dsh
        val wsGate = workspacePending
        if (wsGate != null) {
            WorkspacePickerScreen(
                onDone = { hostPath, title ->
                    workspacePending = null
                    wsGate.complete(hostPath to title)
                },
            )
        }

        // 设置页整屏覆盖在 WebView 之上
        if (showAppSettings) {
            SettingsScreen(
                onClose = { showAppSettings = false },
                onOpenDshSettings = {
                    showAppSettings = false
                    // 打开 dsh 自己的设置浮层。
                    // 必须先置旁路标记：插件平时会把侧栏那个设置入口劫持到 App 设置，
                    // 不旁路的话这次点击会被它接走，变成「App 设置 ↔ dsh 设置」来回弹。
                    webView?.evaluateJavascript(
                        "(function(){window.__dshmBypassSettingsBridge=true;" +
                            "var e=document.querySelector('[class*=\"_trigger\"][class*=\"_rail\"]')" +
                            "||document.querySelector('[class*=\"_rail\"]');" +
                            "if(e){e.click();setTimeout(function(){window.__dshmBypassSettingsBridge=false;},400);return 'ok';}" +
                            "window.__dshmBypassSettingsBridge=false;return 'no';})()",
                        null
                    )
                },
                onShowLog = {
                    showAppSettings = false
                    showLog = true
                },
            )
        }

        if (showLog) {
            AlertDialog(
                onDismissRequest = { showLog = false },
                confirmButton = {
                    Row {
                        if (url != null) {
                            TextButton(onClick = {
                                webView?.evaluateJavascript(JS_DEEP_DIAG) { r ->
                                    post { onDiag("DEEPDIAG $r") }
                                }
                            }) { Text("诊断") }
                            TextButton(onClick = {
                                webView?.reload()
                                log("手动重载页面")
                            }) { Text("重载") }
                        }
                        TextButton(onClick = { showLog = false }) { Text("关闭") }
                    }
                },
                title = { Text("调试 · v$BUILD_TAG") },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            Kernel.statusReport(ctx),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                        )
                        LogList(logs, modifier = Modifier.padding(top = 8.dp))
                    }
                },
            )
        }
        }

        BackHandler(enabled = url != null && (webView?.canGoBack() == true)) {
            webView?.goBack()
        }
    }
}

@Composable
private fun LogList(logs: List<String>, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) state.animateScrollToItem(logs.size - 1)
    }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(logs) { l ->
            Text(l, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
}
