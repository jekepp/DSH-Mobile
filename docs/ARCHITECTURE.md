# 架构与设计取舍

这份文档记录**为什么这么写**，尤其是那些踩过坑、看起来反直觉的地方。

---

## 一、三层结构

```
① Compose 原生层    MainActivity / BootScreen / SettingsScreen / WorkspacePickerScreen / AppPrefs
② WebView 层        DshWebView（承载 dsh 的 Web UI）+ 移动适配客户端插件
③ 内核层 (proot)    Kernel.kt：安装 rootfs / 启动 proot / 抓 token / 装插件 / 写工作区
```

---

## 二、内核层：Android 10+ 的四条硬约束

这四条决定了 proot 那套必须怎么写，**不要试图绕开**：

### ① 应用私有目录不可执行

`/data/data` 下的文件带 `app_data_file` 标签，**不可 exec，也无法 mmap(PROT_EXEC)**。
唯一可执行的路径是 `nativeLibraryDir`（APK 里 `lib/<abi>/` 下的文件被解到那里，
标签是 `apk_data_file`）。

→ 所以 proot 及其 loader 必须以 `lib*.so` 形式放在 `jniLibs/arm64-v8a/`，
并且 `packaging { jniLibs { useLegacyPackaging = true } }` **不能省**。

### ② loader 必须预置

proot 默认把 loader 解到 `$PROOT_TMP_DIR` 再 execve —— 这一步会被拒。
→ 把 loader 预置在 nativeLibraryDir，用 `PROOT_LOADER` 指过去。

### ③ `libtalloc.so.2` 名字对不上

APK 只解「`lib` 前缀 + `.so` 结尾」的文件，而 Termux 版 proot 的 `DT_NEEDED` 写死是
`libtalloc.so.2`。→ 见 `tools/patch_proot_soname.py`（等长替换，不动节结构）。

### ④ 必须经 `/system/bin/sh -c` 中介

直接 exec proot 会静默 exit 255。

---

## 三、移动端适配：为什么用客户端插件

### 走过的弯路

最初的做法是 **App 在 WebView 里注入一大坨 CSS**（`JS_MOBILE_CSS`）去改 dsh 的布局。
改了二十多轮，反复出问题。根因是：**从外面猜 dsh 的 DOM 结构**。

典型翻车：dsh 的 DOM 里有个隐蔽假设 —— **设置浮层是侧栏的后代**。
所以一旦给侧栏加 `transform`（做抽屉动画），就会创建包含块，
把里面 `position: fixed` 的设置面板锚到 280px 宽的侧栏上，整个人机界面歪掉。

### 现在的做法

改用一个 **dsh 官方格式的客户端插件**（`plugin/dsh-client-ui-mobile-shell/`）：

- 它是标准 dsh 客户端插件：`package.json` 里声明 `dsh.client.inject` + `platform: web`，
  bundle 用 `window.__ModuleLoader__.load({id, factory})` 注册
- 通过 `ctx.slots.inject("shell.overlay", ...)` 挂汉堡按钮和遮罩
- 通过 `ctx.layout.toggleSidebar()` 控制抽屉，**用 dsh 自己的状态，不自己造一套**

### 三个关键设计

**① 选择器基于语义类名，不硬编码哈希值**

dsh 的 CSS Modules 类名形如 `<hash>_<语义名>`（`pI_x6G_sidebarCol`）。
插件用：

```js
:is([class*="_sidebarCol "],[class$="_sidebarCol"])
```

匹配语义部分。所以 dsh 小版本升级换了哈希，插件不会跟着失效。
（同时匹配「中间带空格」和「结尾」两种位置，避免 `_row` 误命中 `_rowText`。）

**② 抽屉开合读 dsh 自己写的 inline grid 值**

```js
frame.style.gridTemplateColumns   // 收起 "56px ..." / 展开 "280px ..."
```

这是第一手状态：不受我们注入的 CSS 影响，也不依赖界面语言。
（试过的错误方法：读元素宽度、读 aria 文案、判断 sidebarCol 是否存在 —— 都错过。）

**③ 绝不给侧栏加 `transform` / `will-change`**

它们会创建包含块，把作为其后代的设置浮层挤歪。动画**只动 `left`**。

**④ CSS 里一个视口单位都不写**

这个 WebView 把 `vh` / `dvh` / `svh` 甚至 `vw` 全部算成 0（实测 `innerHeight=844` 但 `100vh=0`）。
需要视口尺寸时用 JS 发布的 `--dshm-vw/vh/drawer-w` 像素变量。

---

## 四、侧栏脱离 grid 流会导致「主页一片空白」

这是个很隐蔽的坑，值得单独说：

把侧栏设成 `position: fixed` 之后，它**脱离了 grid 流**。
于是后面两个网格项自动往前顶一格：

- `centerCol` 被塞进第一列（被我们压成 `0px`）
- `rightbarCol` 占掉本该属于中栏的那一列

**表现就是主页整片空白**（内容宽度为 0）。

→ 必须给 `centerCol` / `rightbarCol` 显式写 `grid-column`。

---

## 五、工作区：会话 cwd 必须与工作区 path **严格相等**

判断在 dsh 的 `dsh-workspace/lib/index.js`：

```js
if (cwd !== this.record.path) throw new Error(
  `cannot attach session ... : its cwd resolves to '${cwd}'`)
```

注意是 `!==`（**严格相等**，不是「cwd 落在工作区里面」）。而且两边都会做 realpath。

会话的 cwd 来自 `dsh-api-session-controller`：

```js
const cwd = workspace?.path ?? request.cwd ?? this.defaultCwd;
```

**所以 cwd 就是工作区记录的 path。** 失败链是：

> 会话挂不上工作区 → 没有可用会话 → 前端 `const inert = sessionId === void 0 || ...` 成立
> → **输入框永远禁用**

→ 修法：启动 dsh **之前**先在容器里跑一次 `realpath /workspace`，
把结果同时用于 ① 写进工作区注册表的 `path` ② proot 的 `-w` 启动目录。两边同源，必然相等。

注册表 schema（来自 `dsh-workspace` 的 `defineDomain`）：

```
unit{name:"workspace", version:2}
global{initialized:true, workspaceIds:[uuid], archivedSessionIds:[]}
tables.workspaces{<uuid>:{path,title,sessionIds,createdAt,updatedAt}}
```

---

## 六、等待遮罩：为什么不做「手动选择」出口

dsh 启动后会自己创建一个会话。这一步要经过 proot（每个文件操作都被 ptrace 拦截），
手机上需要**几十秒到一分钟**。

这段时间输入框是「未选工作区」的禁用态，用户一点就会把 dsh 那个又慢又卡的原生目录
选择器撞出来，然后卡在「加载中」。

→ 插件检测到输入框处于该状态时盖一层「正在准备工作区…」遮罩，
会话一就绪立刻撤掉。

**故意不给「手动选择」按钮**：会话是 dsh 自己在后台建的，
跟用户在目录选择器里点什么毫无关系 —— 留那个按钮只会把人引到一条没用的路上。

判断「未就绪」用的是 dsh 的语义类名 `_cardWorkspaceTrigger`（已核对存在于
`dsh-client-ui-conversation`），万一以后变了还有占位文案兜底。

---

## 七、主题切换过渡：为什么用「临时标记」

亮/暗切换时给 `html` 挂一个**只存在 260ms** 的标记，让这次颜色变化走 CSS 过渡。

不用常驻的全局 `transition` —— 那会让页面上几百个元素每帧都参与样式计算，
**正是之前动画卡顿的根因之一**。

---

## 八、App 侧为什么还留着三处 JS 注入

`DshWebView.kt` 里保留了三个注入，它们绕开的是 **WebView 自身的缺陷**，
插件在页面里同样受这个缺陷影响，所以不能搬进插件：

| 注入 | 作用 |
| --- | --- |
| `JS_HEIGHT_FIX` | CSS 视口单位为 0，用 `innerHeight` 像素值顶死 html/body 高度 |
| `JS_UNIT_SHIM` | 扫样式表把 vh/dvh/svh 换算成像素值重新注入 |
| `JS_ERROR_HOOK` | console / onerror 转发到 Android 日志 |
