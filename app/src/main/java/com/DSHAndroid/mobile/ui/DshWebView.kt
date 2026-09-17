package com.DSHAndroid.mobile.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 用 WebView 承载 dsh 的 Web UI。
 *
 * 移动端布局适配已经**不再由 App 侧注入 CSS** —— 那条路改过二十多轮、反复出问题，
 * 根因是从外面猜 dsh 的 DOM 结构。现在改走 dsh 官方的客户端插件机制：
 * 插件（dsh-client-ui-mobile-shell）随 APK 以 assets 分发，由 Kernel 在拉起 dsh 之前
 * 装进 web profile，由 dsh 自己加载。插件里用的是 dsh 构建产物的语义类名，
 * 不再猜结构，桌面端也分毫不动。
 *
 * 这里只保留三处绕不开 WebView 自身缺陷的注入（插件在页面里同样受这个缺陷影响）：
 *
 *   1. JS_HEIGHT_FIX —— 这个 WebView 的 CSS 视口单位算出来是 0（实测 innerHeight=844
 *      但 100vh/100dvh/100svh 全为 0），html 的 height:100% 因此塌陷成 0 导致白屏。
 *      用 window.innerHeight 的像素值直接顶死 html / body。
 *   2. JS_UNIT_SHIM —— 扫所有样式表，把含 vh/dvh/svh 的声明换算成像素值重新注入。
 *      dsh 自己的弹层写的是 max-height: calc(100dvh - 48px)，不纠正就被 clamp 成一条线。
 *   3. JS_ERROR_HOOK —— 把 console / onerror / unhandledrejection 转发到 Android 日志。
 */

/** 把任意字符串包成 JS 字符串字面量（转义引号/反斜杠/换行） */
fun jsStr(s: String): String {
    val sb = StringBuilder("'")
    for (c in s) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '\'' -> sb.append("\\'")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            else -> sb.append(c)
        }
    }
    return sb.append("'").toString()
}

/** 每次构建都改这里，界面上会直接显示，避免装错包。 */
const val BUILD_TAG = "0.33.0"

/**
 * 用 WebView 承载 dsh 的 Web UI。
 *
 * 这个 WebView（vivo V2505A / Android 16 / Chrome 138）有个致命怪癖：
 * **CSS 视口高度是 0**。实测（JS_UNIT_PROBE）：
 *     innerHeight = 844，但 100vh / 100dvh / 100svh / calc() 全部 = 0
 * 而 dsh 的弹层高度写的是 `max-height: calc(100dvh - 48px)`，
 * 于是 calc(0 - 48px) 变负数、被 clamp 成 0，弹层就被压成一条线。
 * 同一个病根也解释了 `html{height:100%}` = 0 导致的白屏。
 *
 * 对策（两层，都是 App 侧注入，不改 dsh）：
 *   1. JS_HEIGHT_FIX：用 window.innerHeight 的像素值强压 html / body 高度。
 *   2. JS_UNIT_SHIM：扫所有样式表，把含 vh/dvh/svh 的声明换算成像素值，
 *      以 !important 重新注入 —— 不管那个 0 从哪来，值都会被纠正。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DshWebView(
    url: String,
    modifier: Modifier = Modifier,
    onViewCreated: (WebView) -> Unit = {},
    onLog: (String) -> Unit = {},
    onDiag: (String) -> Unit = {},
    /** App 设置生成的主题 CSS；变化时会重新注入 */
    extraCss: String = "",
    /** extraCss 的版本号，变一次就重注入一次 */
    extraCssKey: Int = 0,
    /** 页面里的移动适配插件调用 window.DSHMobile.openAppSettings() 时触发 */
    onOpenAppSettings: () -> Unit = {},
) {
    // WebView 一旦销毁就不能再调 evaluateJavascript（会抛异常）。
    // 而我们有几个 postDelayed 的诊断回调，必须用这个旗标挡住。
    val alive = remember { java.util.concurrent.atomic.AtomicBoolean(true) }
    // 记住上一次注入的 extraCssKey，避免每次重组都改 DOM
    val lastKey = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            val main = Handler(Looper.getMainLooper())

            fun runDiag(wv: WebView, round: Int) {
                if (!alive.get()) return
                wv.evaluateJavascript(DIAG_JS) { r -> if (alive.get()) onDiag("DIAG#$round $r") }
            }

            fun js(wv: WebView, code: String, tag: String? = null) {
                if (!alive.get()) return
                wv.evaluateJavascript(code) { r ->
                    if (alive.get() && tag != null) onDiag("$tag $r")
                }
            }

            WebView(context).apply {
                onViewCreated(this)
                // 页面里的移动适配插件用这个桥把「设置」入口交回 App：
                // 抽屉左下角那个原生位置点下去，打开的是 App 自己的设置页，而不是 dsh 的浮层。
                // 只暴露一个无参方法，且这个 WebView 只加载本机 127.0.0.1 的页面。
                addJavascriptInterface(
                    object {
                        @android.webkit.JavascriptInterface
                        fun openAppSettings() {
                            main.post { onOpenAppSettings() }
                        }
                    },
                    "DSHMobile",
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(false)
                    builtInZoomControls = false
                    displayZoomControls = false
                    textZoom = 100
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                isVerticalScrollBarEnabled = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    isForceDarkAllowed = false
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                        onLog("加载中: $u")
                    }

                    override fun onPageFinished(view: WebView?, u: String?) {
                        onLog("已加载: $u")
                        val wv = view ?: return
                        if (!alive.get()) return
                        js(wv, JS_ERROR_HOOK)
                        if (extraCss.isNotBlank()) js(wv, "(()=>{if(document.getElementById('__dshm_theme'))return;var s=document.createElement('style');s.id='__dshm_theme';s.textContent=" + jsStr(extraCss) + ";document.head.appendChild(s);})()")
                        js(wv, JS_HEIGHT_FIX)
                        js(wv, JS_UNIT_SHIM, "UNITSHIM")
                        js(wv, JS_UNIT_PROBE, "UNITPROBE")
                        listOf(2500L, 6000L, 11000L).forEachIndexed { i, delay ->
                            main.postDelayed({ runDiag(wv, i + 1) }, delay)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        if (request?.isForMainFrame == true) {
                            onLog("!! 主文档错误 ${request.url}: ${error?.description}")
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean = false
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(m: ConsoleMessage?): Boolean {
                        if (m != null) {
                            val tag = when (m.messageLevel()) {
                                ConsoleMessage.MessageLevel.ERROR -> "console.ERROR"
                                ConsoleMessage.MessageLevel.WARNING -> "console.WARN"
                                else -> "console"
                            }
                            onLog("$tag: ${m.message()}  (${m.sourceId()}:${m.lineNumber()})")
                        }
                        return true
                    }
                }

                onLog("加载 $url")
                loadUrl(url)
            }
        },
        update = { wv ->
            // 主题 CSS 变了就重新注入（用 key 判断，避免每帧重写）
            if (extraCssKey != lastKey.intValue) {
                lastKey.intValue = extraCssKey
                if (alive.get()) {
                    wv.evaluateJavascript(
                        "(()=>{var s=document.getElementById('__dshm_theme');" +
                        "if(!s){s=document.createElement('style');s.id='__dshm_theme';document.head.appendChild(s);}" +
                        "s.textContent=" + jsStr(extraCss) + ";})()", null
                    )
                }
            }
        },
        onRelease = { wv ->
            alive.set(false)
            wv.stopLoading()
            wv.loadUrl("about:blank")
            wv.destroy()
        },
    )
}

private const val JS_ERROR_HOOK = """
(function(){
  if (window.__dshMobileHooked) return;
  window.__dshMobileHooked = true;
  window.addEventListener('error', function(e){
    try { console.error('JSERROR: ' + (e.message || '') + ' @ ' + (e.filename || '?') + ':' + (e.lineno || 0)); } catch (_) {}
  }, true);
  window.addEventListener('unhandledrejection', function(e){
    try { var r = e.reason; console.error('JSREJECT: ' + ((r && (r.stack || r.message)) || String(r))); } catch (_) {}
  });
  try {
    var st = document.createElement('style');
    st.id = '__dshm_safety';
    st.textContent = 'html,body{height:100% !important;min-height:100% !important;}';
    (document.head || document.documentElement).appendChild(st);
    console.error('safety css injected');
  } catch (_) {}
})();
"""

/**
 * 高度链修复：把 html / body 的高度用像素值顶死。
 * 真机实测：不加这个，`html` 的 height 计算值是 0px，整棵树全塌。
 */
private const val JS_HEIGHT_FIX = """
(function(){
  if (window.__dshmFixHooked) return;
  window.__dshmFixHooked = true;

  function snapshot(tag){
    try {
      var de = document.documentElement, bd = document.body;
      console.error('HTMLBODY ' + JSON.stringify({
        tag: tag,
        htmlH: getComputedStyle(de).height,
        bodyH: bd ? getComputedStyle(bd).height : null,
        bodyClientH: bd ? bd.clientHeight : null,
        innerH: window.innerHeight
      }));
    } catch (e) {}
  }

  function applyFix(){
    try {
      var h = window.innerHeight + 'px';
      var de = document.documentElement;
      de.style.setProperty('height', h, 'important');
      de.style.setProperty('min-height', h, 'important');
      if (document.body) {
        document.body.style.setProperty('height', h, 'important');
        document.body.style.setProperty('min-height', h, 'important');
      }
    } catch (e) {}
  }

  function boot(){
    snapshot('before');
    applyFix();
    setTimeout(function(){ snapshot('after-fix'); }, 250);
    var n = 0;
    var timer = setInterval(function(){
      applyFix();
      n++;
      if (n === 4) snapshot('t2s');
      if (n === 16) snapshot('t8s');
      if (n >= 30) clearInterval(timer);
    }, 500);
    window.addEventListener('resize', applyFix);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
"""

/**
 * vh / dvh / svh 垫片 —— 这是修弹层塌陷的关键。
 *
 * 真机实测 CSS 视口高度 = 0，导致所有视口相对单位算成 0：
 *     vh=0  dvh=0  svh=0  calc(100dvh - 48px) = 0
 * 而 dsh 的弹层写着 `max-height: calc(100dvh - 48px)`，
 * calc(0 - 48px) 是负数 → clamp 到 0 → 弹层压成一条线。
 *
 * 做法：遍历所有样式表（含 @media 等分组规则，递归），
 * 把含 vh/dvh/svh/dvw/vw 的声明换算成像素值，以 !important 注入一张覆盖表。
 * 顺带也扫一遍元素的行内样式。
 *
 * 因为 dsh 的 CSS 是异步按模块加载的，这里要重复跑很多次。
 */
private const val JS_UNIT_SHIM = """
(function(){
  var TAG = '__dshm_unitshim';

  function conv(v, H, W){
    return v
      .replace(/([\d.]+)(dvh|svh|lvh|vh)\b/g, function(_, n){ return (parseFloat(n) * H / 100).toFixed(2) + 'px'; })
      .replace(/([\d.]+)(dvw|svw|lvw|vw)\b/g, function(_, n){ return (parseFloat(n) * W / 100).toFixed(2) + 'px'; });
  }

  function hasVU(v){
    return /[\d.)](dvh|svh|lvh|vh|dvw|svw|lvw|vw)\b/.test(v);
  }

  function run(){
    try {
      var H = window.innerHeight, W = window.innerWidth;
      if (!H) return 'skip: innerHeight=0';
      var out = [];
      var hitSelectors = [];

      function walk(rules){
        for (var i = 0; i < rules.length; i++) {
          var r = rules[i];
          // 分组规则（@media / @supports / @layer）递归进去
          if (r.cssRules && !r.selectorText) { walk(r.cssRules); continue; }
          if (!r.selectorText || !r.style) continue;
          var decls = [];
          for (var k = 0; k < r.style.length; k++) {
            var p = r.style[k];
            var v = r.style.getPropertyValue(p);
            if (v && hasVU(v)) decls.push(p + ':' + conv(v, H, W) + ' !important');
          }
          if (decls.length) {
            out.push(r.selectorText + '{' + decls.join(';') + '}');
            hitSelectors.push(r.selectorText);
          }
        }
      }

      var sheets = document.styleSheets;
      for (var i = 0; i < sheets.length; i++) {
        var list = null;
        try { list = sheets[i].cssRules; } catch (e) { continue; }  // 跨域表忽略
        if (list) walk(list);
      }

      // 行内样式也扫一遍
      var inlineCount = 0;
      var all = document.querySelectorAll('[style]');
      for (var j = 0; j < all.length; j++) {
        var el = all[j];
        var props = el.style;
        for (var m = 0; m < props.length; m++) {
          var pp = props[m];
          var vv = props.getPropertyValue(pp);
          if (vv && hasVU(vv)) {
            el.style.setProperty(pp, conv(vv, H, W), 'important');
            inlineCount++;
          }
        }
      }

      var node = document.getElementById(TAG);
      if (!node) {
        node = document.createElement('style');
        node.id = TAG;
        (document.head || document.documentElement).appendChild(node);
      }
      node.textContent = out.join('\n');
      return JSON.stringify({
        rules: out.length,
        inline: inlineCount,
        sample: hitSelectors.slice(0, 6)
      });
    } catch (e) {
      return 'ERR ' + (e && e.message);
    }
  }

  var first = run();
  console.error('UNITSHIM applied ' + first);
  // dsh 的 CSS 异步加载，多跑几轮兜住
  [500, 3000, 9000].forEach(function(d){
    setTimeout(function(){ run(); }, d);
  });
  window.addEventListener('resize', function(){ run(); });
})();
"""

/**
 * 单位实测（改良版）。上一版把探针挂在 height:0 的宿主里，导致 100% 这类
 * 相对父元素的测法失真。这一版给每个探针一个明确尺寸的宿主。
 */
const val JS_UNIT_PROBE = """
(function(){
  try {
    var H = window.innerHeight, W = window.innerWidth;
    function inHost(parentH, decl){
      var par = document.createElement('div');
      par.setAttribute('style','position:absolute;left:-9999px;top:0;width:200px;height:' + parentH + 'px;overflow:visible');
      document.body.appendChild(par);
      var d = document.createElement('div');
      d.setAttribute('style','width:1px;' + decl);
      par.appendChild(d);
      var h = d.getBoundingClientRect().height;
      document.body.removeChild(par);
      return Math.round(h * 100) / 100;
    }
    var r = {
      innerH: H, innerW: W,
      px: inHost(400, 'height:100px'),
      vh: inHost(400, 'height:100vh'),
      dvh: inHost(400, 'height:100dvh'),
      svh: inHost(400, 'height:100svh'),
      pct: inHost(400, 'height:100%'),
      calcDvh: inHost(400, 'height:calc(100dvh - 48px)'),
      calcPct: inHost(400, 'height:calc(100% - 48px)')
    };
    return JSON.stringify(r);
  } catch (e) {
    return 'UNITPROBE-ERR ' + (e && e.message);
  }
})();
"""

/**
 * 深度定位：找「又宽又扁」的可见元素并打印其祖先链高度。
 * 需在弹层展开状态下点「诊断」才有意义。
 */
const val JS_DEEP_DIAG = """
(function(){
  try {
    function chainOf(el, maxDepth){
      var out = [], n = el, i = 0;
      while (n && i < maxDepth) {
        var cs = getComputedStyle(n);
        var r = n.getBoundingClientRect();
        out.push({
          tag: n.tagName.toLowerCase(),
          cls: String(n.className || '').slice(0, 30),
          box: [Math.round(r.width), Math.round(r.height)],
          h: cs.height, mh: cs.maxHeight, ov: cs.overflow, disp: cs.display
        });
        n = n.parentElement;
        i++;
      }
      return out;
    }
    var bad = [];
    var all = document.querySelectorAll('*');
    for (var i = 0; i < all.length && bad.length < 4; i++) {
      var e = all[i];
      var r = e.getBoundingClientRect();
      if (r.width > 180 && r.height >= 1 && r.height < 70) {
        var cs = getComputedStyle(e);
        if (cs.display === 'none' || cs.visibility === 'hidden') continue;
        if (e.children.length === 0 && !e.textContent.trim()) continue;
        bad.push({
          cls: String(e.className || '').slice(0, 40),
          box: [Math.round(r.width), Math.round(r.height)],
          h: cs.height, mh: cs.maxHeight, ov: cs.overflow,
          chain: chainOf(e, 5)
        });
      }
    }
    return JSON.stringify({squashedCount: bad.length, items: bad});
  } catch (e) {
    return 'DEEP-ERR ' + (e && e.message);
  }
})();
"""

private const val DIAG_JS = """
(function(){
  try {
    var out = {};
    var de = document.documentElement, bd = document.body;
    out.innerW = window.innerWidth;
    out.innerH = window.innerHeight;
    out.htmlBox = [de.clientWidth, de.clientHeight];
    out.bodyBox = bd ? [bd.clientWidth, bd.clientHeight] : null;
    out.htmlHeightStyle = getComputedStyle(de).height;
    out.bodyHeightStyle = bd ? getComputedStyle(bd).height : null;
    var rt = document.getElementById('root');
    out.rootEl = rt ? [rt.clientWidth, rt.clientHeight, getComputedStyle(rt).height] : 'no#root';
    out.shimRules = (function(){
      var n = document.getElementById('__dshm_unitshim');
      return n ? n.textContent.length : -1;
    })();
    var fixed = [];
    var all = de.getElementsByTagName('*');
    for (var i = 0; i < all.length && fixed.length < 6; i++) {
      var el = all[i];
      var cs = getComputedStyle(el);
      if (cs.position !== 'fixed' && cs.position !== 'absolute') continue;
      var r = el.getBoundingClientRect();
      if (r.width < 40 && r.height < 40) continue;
      fixed.push({cls: String(el.className || '').slice(0, 36), box: [Math.round(r.width), Math.round(r.height)],
                  h: cs.height, mh: cs.maxHeight});
    }
    out.fixed = fixed;
    return JSON.stringify(out);
  } catch (e) {
    return 'DIAG-ERR ' + (e && e.message);
  }
})();
"""
