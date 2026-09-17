// dsh-client-ui-mobile-shell —— DSH Mobile 自研的移动端适配客户端插件（浏览器半边）
//
// 设计原则（都是这个项目踩出来的，见交接包 03-LESSONS）：
//   1. 只作用于窄屏（<=768px），桌面端一个像素都不改。
//   2. 不猜 DOM 结构：所有选择器都基于 dsh 自己的 CSS Modules「语义后缀」而不是哈希值，
//      所以 dsh 小版本升级导致哈希变化时，插件不会跟着一起失效。
//      （语义名 -> 哈希 的对照表见工程 recon/class-map.txt）
//   3. 抽屉开合不靠推断，读 dsh 写在 frame inline style 里的 gridTemplateColumns 第一个数值
//      —— 那是第一手状态，不受我们注入的 CSS 影响，也不依赖界面语言。
//   4. 绝不给侧栏加 transform / will-change：设置浮层是侧栏的后代，这两个属性会创建
//      包含块，把 position:fixed 的设置面板锚到抽屉上导致被裁切。动画只动 left。
//   5. CSS 里不写 vh / dvh / svh：这个 WebView 把它们全部算成 0。需要视口高度时
//      用 JS 发布的 --dshm-vh 像素变量，或者干脆用 position:fixed + inset:0。
window.__ModuleLoader__.load({
	id: "dsh-client-ui-mobile-shell",
	factory: (require) => {
		var module = { exports: {} };
		var exports = module.exports;
		Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });

		let react = require("react");

		//#region 语义选择器
		/**
		* dsh 的 CSS Modules 类名形如 `<hash>_<语义名>`，例如 pI_x6G_sidebarCol。
		* 这里只用「语义名」匹配：既命中 class 中间的完整词，也命中结尾的完整词，
		* 因此不会把 `_row` 误命中 `_rowText` 这类前缀相同的名字。
		* @param name - CSS Modules 里的语义名（camelCase 原样）。
		* @returns 一个属性选择器表达式。
		*/
		function has(name) {
			return ':is([class*="_' + name + ' "],[class$="_' + name + '"])';
		}

		/** 插件自己元素的类名前缀，绝不会与 dsh 的类名冲突。 */
		const SELF = "dshm";

		/** 抽屉状态挂在 html 上，CSS 靠它开合。 */
		const DRAWER_ATTR = "data-" + SELF + "-drawer";

		/**
		* 主题切换瞬间挂在 html 上的标记。
		* 只在切换后的 260ms 内存在 —— 让这一次颜色变化走过渡，而不是硬切；
		* 平时不挂，避免全局 transition 拖慢样式计算。
		*/
		const THEME_FLIP_ATTR = "data-" + SELF + "-theme-flip";

		/** dsh 把亮/暗标记写在这个属性上。 */
		const DARK_ATTR = "data-ds-dark-theme";

		/** 等待遮罩的开关挂在 html 上。 */
		const WAIT_ATTR = "data-" + SELF + "-wait";

		/**
		* 「还没选工作区」时输入框卡片会带上这个类名（dsh 的 InputBar 加了 workspace 触发行为）。
		* 这是判断「会话还没就绪」最稳的语义指纹。
		*/
		const INERT_COMPOSER = has("cardWorkspaceTrigger");

		/** 兜底：类名万一变了，用占位文案再判一次（这个 App 只跑中文界面）。 */
		const INERT_TEXT = "选择一个工作区";

		/** 视口像素变量：这个 WebView 里 vh/dvh/svh 甚至 vw 都可能算成 0，所以一律用 JS 发布的像素值。 */
		const VH_VAR = "--" + SELF + "-vh";
		const VW_VAR = "--" + SELF + "-vw";

		/** 抽屉宽度（px）。由 JS 按视口算好写进变量，CSS 只读变量，宽度和位移因此永远一致。 */
		const DRAWER_W_VAR = "--" + SELF + "-drawer-w";

		/** grid 第一列超过这个值就算「抽屉展开」。收起时是 56px 图标栏，展开是 264~420px。 */
		const DRAWER_OPEN_MIN_PX = 120;

		/**
		* dsh 设置浮层的根元素。用内部的 navList 当指纹 —— 别的 _panel 没有这个结构，
		* 所以能精确命中设置面板而不误伤其它同名类。（has 是函数声明，会被提升。）
		*/
		const SETTINGS_PANEL = has("panel") + ":has(" + has("navList") + ")";

		/**
		* dsh 设置浮层的容器（panel 的父层，铺满屏幕并带遮罩）。
		*
		* ★ 它和 SETTINGS_PANEL 同样是 sidebarCol 的后代，这一点很要命：
		*   抽屉收起时我给 sidebarCol 设了 pointer-events:none，而 pointer-events 是
		*   **继承**属性 —— 整个设置面板会跟着变成不可点击，表现为「设置页开着却点不动、
		*   像卡死」。所以必须在设置浮层上显式恢复。这个 bug 靠 384×844 的真实渲染
		*   量出来（overlay 的 computed pointerEvents 是 none），不是猜的。
		*/
		const SETTINGS_OVERLAY = has("overlay") + ":has(" + has("navList") + ")";
		//#endregion

		//#region 样式
		/** 只在小屏生效的移动端适配样式表。 */
		const CSS = [
			// ---- 自检用：桌面端把插件元素全部藏掉 ----
			"@media (min-width:769px){." + SELF + "-burger,." + SELF + "-scrim{display:none !important}}",

			"@media (max-width:768px){",

			// 1) 去掉 Android Chrome 的点击蓝框（只关自己的元素不够，dsh 的元素也要关）
			"*,*::before,*::after{-webkit-tap-highlight-color:transparent !important}",

			// 1b) 亮/暗主题切换时做一次颜色过渡，避免整屏硬切。
			//     ★ 只在切换后的 260ms 内挂全局 transition（见 JS 里的 themeFlip），
			//       常驻的全局 transition 会让几百个元素每帧都参与样式计算 —— 那是卡顿的来源。
			"html[" + THEME_FLIP_ATTR + "] *,"
				+ "html[" + THEME_FLIP_ATTR + "] *::before,"
				+ "html[" + THEME_FLIP_ATTR + "] *::after{"
				+ "transition:background-color 240ms ease,color 240ms ease,"
				+ "border-color 240ms ease,fill 240ms ease !important}",

			// 2) 单栏布局：两侧轨道压成 0，中栏吃满整宽。
			//    frame 的 grid-template-columns 是 dsh 写的 inline style，必须 !important 才能压过；
			//    但 inline 值本身仍是 dsh 的第一手状态，我们靠它判断抽屉开合，所以不冲突。
			has("frame") + "{grid-template-columns:0 minmax(0,1fr) 0 !important}",

			// 2b) ★ 必须显式指定列号。侧栏一旦脱离 grid 流（下面第 3 条把它变成 fixed），
			//     后面的网格项会自动往前顶一格：centerCol 被塞进第一列（0px）、
			//     rightbarCol 占掉本该属于中栏的那一列 —— 表现就是「主页一片空白」。
			//     frame 里在流内的网格项只有这三个，overlayLayer 和 handle 都是 absolute。
			has("centerCol") + "{grid-column:2 !important;min-width:0 !important}",
			has("rightbarCol") + "{grid-column:3 !important;min-width:0 !important}",

			// 3) 侧栏 → 浮层抽屉。
			//    ★ 不能出现 transform / will-change / filter：它们会创建包含块，
			//      把作为其后代的设置浮层（position:fixed）锚到抽屉宽度上。
			//      所以位移动画只动 left，并且用固定像素而不是百分比。
			has("sidebarCol") + "{"
				+ "position:fixed !important;top:0 !important;bottom:0 !important;left:0 !important;"
				+ "z-index:60 !important;width:var(" + DRAWER_W_VAR + ",320px) !important;"
				+ "max-width:var(" + DRAWER_W_VAR + ",320px) !important;"
				+ "height:auto !important;overflow:hidden !important;"
				+ "transform:none !important;will-change:auto !important;"
				+ "transition:left 300ms cubic-bezier(.22,.61,.36,1) !important}",
			// 投影只在展开时给：收起时抽屉停在屏幕外，但投影会往右铺出一条可见的渐变。
			"html[" + DRAWER_ATTR + "=open] " + has("sidebarCol") + "{"
				+ "box-shadow:0 18px 48px rgb(0 0 0 / .34)}",
			"html[" + DRAWER_ATTR + "=closed] " + has("sidebarCol") + "{"
				+ "left:calc(-1 * var(" + DRAWER_W_VAR + ",320px)) !important;"
				+ "box-shadow:none !important;pointer-events:none !important}",

			// 3b) ★ 把设置浮层的可点击性恢复回来（pointer-events 会被子元素继承，
			//     上面那条 none 会把整个设置面板一起废掉）。
			SETTINGS_OVERLAY + "{pointer-events:auto !important}",

			// 4) 右栏在手机上不该和中栏并排：改成整屏浮层，并且只在 dsh 认为它开着的时候才盖上去，
			//    否则那个空轨道会变成一层挡住全部点击的透明板。
			has("frame") + ":not([data-rightbar-collapsed]) " + has("rightbarCol") + "{"
				+ "position:fixed !important;inset:0 !important;z-index:70 !important;"
				+ "width:auto !important;max-width:none !important}",
			has("frame") + "[data-rightbar-collapsed] " + has("rightbarCol") + "{"
				+ "pointer-events:none !important;overflow:hidden !important}",

			// 5) 会话头部给汉堡按钮让位
			has("root") + ":has(" + has("scrollBody") + ") " + has("header") + "{padding-left:52px !important}",

			// 6) 输入栏工具行强制单行，触发器限宽，别把发送键挤出屏幕
			has("composerSeat") + " " + has("tools") + "{flex-wrap:nowrap !important;overflow-x:auto;gap:6px !important}",
			has("composerSeat") + " " + has("trigger") + "{max-width:112px !important}",
			has("composerSeat") + " " + has("row") + "{flex-wrap:nowrap !important}",

			// 7) 设置浮层：整屏，且内部改成上下堆叠。
			//    dsh 默认排布是「左侧导航 | 右侧内容」并排。实测在 384px 屏上
			//    nav 占 188px、content 只剩 196px，中文会被挤成一列一个字。
			//    这里把导航改成顶部横向可滑的标签条，内容吃满整宽。
			//    高度全部用 inset:0，不写视口单位（这个 WebView 里 vh/dvh 恒为 0）。
			SETTINGS_PANEL + "{"
				+ "position:fixed !important;inset:0 !important;"
				+ "width:auto !important;max-width:none !important;"
				+ "height:auto !important;max-height:none !important;"
				+ "border-radius:0 !important;z-index:90 !important;"
				+ "flex-direction:column !important}",
			SETTINGS_PANEL + " " + has("nav") + "{"
				+ "width:100% !important;height:auto !important;flex:0 0 auto !important;"
				+ "flex-direction:column !important;border-right:0 !important}",
			SETTINGS_PANEL + " " + has("navTitle") + "{"
				+ "height:auto !important;padding:10px 12px 2px !important}",
			SETTINGS_PANEL + " " + has("navList") + "{"
				+ "width:100% !important;height:auto !important;flex-direction:row !important;"
				+ "overflow-x:auto !important;overflow-y:hidden !important;"
				+ "gap:6px !important;padding:4px 10px 10px !important;box-sizing:border-box !important}",
			SETTINGS_PANEL + " " + has("navList") + "> *{"
				+ "flex:0 0 auto !important;width:auto !important;height:34px !important;"
				+ "white-space:nowrap !important}",
			SETTINGS_PANEL + " " + has("content") + "{"
				+ "width:100% !important;height:auto !important;min-height:0 !important;"
				+ "flex:1 1 auto !important;min-width:0 !important}",
			SETTINGS_PANEL + " " + has("options") + "{"
				+ "width:100% !important;box-sizing:border-box !important;"
				+ "padding-left:12px !important;padding-right:12px !important}",

			// 8) content-box 陷阱：这些容器带左右 padding 又用 width:100%，
			//    会宽出视口把右侧控件切掉。
			has("panel") + "," + has("content") + "," + has("options") + ","
				+ has("section") + "," + has("navCell") + "{box-sizing:border-box !important}",

			// 9) 设置导航项：图标别撑宽、条目别拉满整行
			has("navList") + "> *{width:auto !important}",
			has("navList") + " svg{width:16px !important;height:16px !important;"
				+ "min-width:16px !important;max-width:16px !important;flex:0 0 16px !important}",

			// 10) 弹层别出屏（同样不依赖 vw，用 JS 发布的像素值）
			has("menu") + "," + has("panel") + "{max-width:calc(var(" + VW_VAR + ",360px) - 16px) !important}",

			// ---- 插件自己的元素 ----
			"." + SELF + "-scrim{"
				+ "position:fixed;inset:0;z-index:55;background:rgb(0 0 0 / .42);"
				+ "opacity:0;pointer-events:none !important;"
				+ "transition:opacity 300ms cubic-bezier(.22,.61,.36,1)}",
			"html[" + DRAWER_ATTR + "=open] ." + SELF + "-scrim{"
				+ "opacity:1;pointer-events:auto !important}",

			"." + SELF + "-burger{"
				+ "position:fixed;z-index:58;"
				+ "top:calc(env(safe-area-inset-top,0px) + 10px);"
				+ "left:calc(env(safe-area-inset-left,0px) + 10px);"
				+ "width:36px;height:36px;padding:0;margin:0;"
				+ "display:flex;flex-direction:column;align-items:center;justify-content:center;gap:4px;"
				+ "border:0;border-radius:10px;"
				+ "background:var(--dsw-alias-bg-base,rgb(255 255 255 / .72));"
				+ "color:var(--dsw-alias-text-l1,currentColor);"
				+ "box-shadow:0 2px 10px rgb(0 0 0 / .18);"
				+ "pointer-events:auto !important;cursor:pointer;"
				+ "transition:opacity 200ms ease}",
			"html[" + DRAWER_ATTR + "=open] ." + SELF + "-burger{opacity:0;pointer-events:none !important}",
			"." + SELF + "-burger>span{"
				+ "display:block;width:17px;height:2px;border-radius:2px;"
				+ "background:currentColor}",

			// ---- 等待遮罩：会话还没就绪时挡住误触 ----
			// 会话创建要过 proot，手机上要几十秒；这段时间输入框是「未选工作区」的禁用态，
			// 用户一点就会把 dsh 那个又慢又卡的原生目录选择器撞出来。盖一层提示挡掉它。
			"." + SELF + "-wait{"
				+ "position:fixed;inset:0;z-index:80;display:none;"
				+ "flex-direction:column;align-items:center;justify-content:center;gap:14px;"
				+ "background:var(--dsw-alias-bg-base,#fff);"
				+ "color:var(--dsw-alias-label-secondary,#666);"
				+ "font-size:14px;line-height:22px;text-align:center;padding:24px;box-sizing:border-box}",
			"html[" + WAIT_ATTR + "] ." + SELF + "-wait{display:flex}",
			"." + SELF + "-wait-spin{"
				+ "width:26px;height:26px;border-radius:50%;"
				+ "border:2.5px solid currentColor;border-top-color:transparent;"
				+ "animation:" + SELF + "-spin 900ms linear infinite}",
			"@keyframes " + SELF + "-spin{to{transform:rotate(360deg)}}",

			"}",
		].join("\n");

		/** 把样式表插进 head；重复调用只插一次。 */
		function installStyles() {
			const tagId = "dsh-client-ui-mobile-shell/mobile.css";
			if (document.querySelector("style[data-plugin-css=" + JSON.stringify(tagId) + "]") !== null) return;
			const tag = document.createElement("style");
			tag.dataset.plugin = "dsh-client-ui-mobile-shell";
			tag.dataset.pluginCss = tagId;
			tag.textContent = CSS;
			document.head.appendChild(tag);
		}
		//#endregion

		//#region 抽屉状态同步
		/** 取布局 frame 元素（三段 grid 的那个容器）。 */
		function frameEl() {
			return document.querySelector(has("frame"));
		}

		/**
		* 读 dsh 写在 frame inline style 里的第一列宽度，判断抽屉开没开。
		* 不用元素宽度、不用 aria 文案、不用「sidebarCol 是否存在」——这三种都在交接包里错过。
		* @returns true / false，取不到状态时返回 null（此时不动 DOM）。
		*/
		function readDrawerOpen() {
			const frame = frameEl();
			if (frame === null) return null;
			const raw = frame.style.gridTemplateColumns;
			if (!raw) return null;
			const first = Number.parseFloat(raw);
			if (!Number.isFinite(first)) return null;
			return first >= DRAWER_OPEN_MIN_PX;
		}

		/** 发布视口像素值与抽屉宽度，供 CSS 在视口单位失效时使用。 */
		function publishViewport() {
			const root = document.documentElement;
			const vw = window.innerWidth;
			root.style.setProperty(VH_VAR, String(window.innerHeight) + "px");
			root.style.setProperty(VW_VAR, String(vw) + "px");
			// 抽屉占屏宽 84%，但不超过 320px；width 与位移共用这一个值，不会失配。
			root.style.setProperty(DRAWER_W_VAR, String(Math.min(320, Math.round(vw * 0.84))) + "px");
		}

		/**
		* 启动抽屉状态同步：MutationObserver 监听 style 变化 + rAF 合并，
		* 状态没变就一个字节的 DOM 都不碰（这正是上一版动画卡顿的根因）。
		* @returns 清理函数。
		*/
		function startDrawerSync() {
			const root = document.documentElement;
			let last = null;
			let queued = false;

			const sync = () => {
				queued = false;
				const open = readDrawerOpen();
				if (open === null || open === last) return;
				last = open;
				root.setAttribute(DRAWER_ATTR, open ? "open" : "closed");
			};

			const schedule = () => {
				if (queued) return;
				queued = true;
				window.requestAnimationFrame(sync);
			};

			if (!root.hasAttribute(DRAWER_ATTR)) root.setAttribute(DRAWER_ATTR, "closed");
			publishViewport();
			sync();

			const observer = new MutationObserver(schedule);
			observer.observe(root, { subtree: true, attributes: true, attributeFilter: ["style"] });

			// 兜底轮询：只读一个字符串，不做布局测量，开销可忽略。
			const timer = window.setInterval(sync, 500);
			const onResize = () => {
				last = null;
				publishViewport();
				schedule();
			};
			window.addEventListener("resize", onResize);
			window.addEventListener("orientationchange", onResize);

			return () => {
				observer.disconnect();
				window.clearInterval(timer);
				window.removeEventListener("resize", onResize);
				window.removeEventListener("orientationchange", onResize);
			};
		}
		//#endregion

		//#region 边缘手势
		/** 左缘多少像素内起手才算「拉抽屉」，太小会和页面纵向滚动打架。 */
		const EDGE_ZONE = 28;
		/** 水平位移超过多少像素才算一次滑动。 */
		const SWIPE_MIN = 55;
		/** 纵向位移超过这个值就当作滚动，不触发抽屉。 */
		const SWIPE_MAX_CROSS = 60;

		/**
		* 左缘右滑打开抽屉、抽屉内左滑关闭。
		*
		* 只在窄屏生效；关着的时候必须从左缘起手，否则会和页面自身的横向滚动/文本选择冲突。
		* @param ctx - 客户端上下文（用 ctx.layout.toggleSidebar 驱动 dsh 自己的开合）。
		* @returns 清理函数。
		*/
		function startEdgeGesture(ctx) {
			const state = { active: false, x: 0, y: 0 };
			const narrow = () => window.innerWidth <= 768;
			/** 设置浮层是否开着 —— 它铺满全屏，此时不该再抢抽屉手势。 */
			const settingsOpen = () => document.querySelector(SETTINGS_OVERLAY) !== null;

			const onStart = (e) => {
				state.active = false;
				if (!narrow() || e.touches.length !== 1) return;
				// 设置页开着时不参与：手势会先把抽屉收起来（侧栏闪一下），
				// 而设置面板在这过程中会被重排，用户感受就是「闪一下然后卡住」。
				if (settingsOpen()) return;
				const t = e.touches[0];
				const open = readDrawerOpen() === true;
				if (open || t.clientX <= EDGE_ZONE) {
					state.active = true;
					state.x = t.clientX;
					state.y = t.clientY;
				}
			};

			const onEnd = (e) => {
				if (!state.active) return;
				state.active = false;
				const t = e.changedTouches[0];
				if (t === undefined) return;
				const dx = t.clientX - state.x;
				if (Math.abs(t.clientY - state.y) > SWIPE_MAX_CROSS) return;
				const open = readDrawerOpen() === true;
				if (!open && dx >= SWIPE_MIN) ctx.layout.toggleSidebar();
				else if (open && dx <= -SWIPE_MIN) ctx.layout.toggleSidebar();
			};

			const onCancel = () => {
				state.active = false;
			};

			document.addEventListener("touchstart", onStart, { passive: true });
			document.addEventListener("touchend", onEnd, { passive: true });
			document.addEventListener("touchcancel", onCancel, { passive: true });
			return () => {
				document.removeEventListener("touchstart", onStart);
				document.removeEventListener("touchend", onEnd);
				document.removeEventListener("touchcancel", onCancel);
			};
		}
		//#endregion

		//#region 等待遮罩
		/** 等超过这个时长就换一句提示（毫秒）。会话创建走 proot，一分钟上下是正常量级。 */
		const WAIT_HINT_AFTER = 60000;

		/** 输入框是否处于「还没选工作区」的禁用态。 */
		function composerInert() {
			if (document.querySelector(INERT_COMPOSER) !== null) return true;
			// 类名万一变了就用占位文案兜底
			const ph = document.querySelector(has("placeholder"));
			return ph !== null && (ph.textContent || "").indexOf(INERT_TEXT) !== -1;
		}

		/**
		* 会话还没就绪时盖一层提示，挡住用户误触 dsh 那个又慢又卡的原生目录选择器。
		*
		* 背景：会话创建要经过 proot，手机上要几十秒到一分钟；这段时间输入框是禁用态，
		* 用户一点就会把「选择工作区目录」那个弹层撞出来，然后卡在「加载中」。
		*
		* ★ 故意**不给手动出口**：会话是 dsh 自己在后台建的，跟用户在目录选择器里点什么无关 ——
		*   之前留的「手动选择」按钮只会把人引到一条没用的路上。就老实等，好了自动撤。
		* @returns 清理函数。
		*/
		function startWaitGate() {
			const root = document.documentElement;
			let shownAt = 0;
			let hinted = false;

			const el = document.createElement("div");
			el.className = SELF + "-wait";
			el.innerHTML = '<div class="' + SELF + '-wait-spin"></div>';

			const text = document.createElement("div");
			text.innerHTML = "正在准备工作区…<br>首次进入要建会话，手机上大概需要一分钟";
			el.appendChild(text);
			document.body.appendChild(el);

			const timer = window.setInterval(() => {
				if (!composerInert()) {
					root.removeAttribute(WAIT_ATTR);
					shownAt = 0;
					return;
				}
				if (!root.hasAttribute(WAIT_ATTR)) {
					root.setAttribute(WAIT_ATTR, "");
					shownAt = Date.now();
				} else if (!hinted && shownAt !== 0 && Date.now() - shownAt > WAIT_HINT_AFTER) {
					hinted = true;
					text.innerHTML = "还在准备中，已经等了一分钟…<br>可以先切到后台再回来，通常就好了";
				}
			}, 400);

			return () => {
				window.clearInterval(timer);
				root.removeAttribute(WAIT_ATTR);
				el.remove();
			};
		}
		//#endregion

		//#region 主题切换过渡
		/**
		* 亮/暗主题切换时给 html 挂一个临时标记，让这一次颜色变化走 240ms 过渡而不是硬切。
		*
		* 标记只存在 260ms，平时不挂 —— 常驻的全局 transition 会让页面上几百个元素
		* 每帧都参与样式计算，那正是之前动画卡顿的根因之一。
		* @returns 清理函数。
		*/
		function startThemeFlipWatch() {
			const root = document.documentElement;
			let timer = 0;
			let last = document.body === null ? null : document.body.getAttribute(DARK_ATTR);

			const flip = () => {
				root.setAttribute(THEME_FLIP_ATTR, "");
				if (timer !== 0) window.clearTimeout(timer);
				timer = window.setTimeout(() => {
					timer = 0;
					root.removeAttribute(THEME_FLIP_ATTR);
				}, 260);
			};

			const onChange = () => {
				const now = document.body === null ? null : document.body.getAttribute(DARK_ATTR);
				if (now === last) return;
				last = now;
				flip();
			};

			// 标记可能挂在 body 上（dsh 的 ThemePresenter 这么写），也可能挂在 html 上，两处都看
			const observers = [];
			for (const target of [document.body, root]) {
				if (target === null) continue;
				const o = new MutationObserver(onChange);
				o.observe(target, { attributes: true, attributeFilter: [DARK_ATTR] });
				observers.push(o);
			}

			return () => {
				for (const o of observers) o.disconnect();
				if (timer !== 0) window.clearTimeout(timer);
				root.removeAttribute(THEME_FLIP_ATTR);
			};
		}
		//#endregion

		//#region 设置入口桥
		/** App 在 DshWebView 里 addJavascriptInterface 注入的桥名。 */
		const BRIDGE = "DSHMobile";

		/**
		* 设置入口的真实指纹：dsh 给它的类是 `VOzbGW_trigger VOzbGW_rail`。
		* 单用 _rail 会误伤 attachment / cordis / workspace 里的同名类，
		* 所以要求同一个元素同时带 _trigger 和 _rail 两个语义名。
		*/
		const SETTINGS_TRIGGER = has("rail") + ":is([class*=\"_trigger \"],[class$=\"_trigger\"])";

		/**
		* 把侧栏左下角那个「设置」入口接到 App 的原生设置页上。
		*
		* 在**捕获阶段**拦下来并阻止冒泡，dsh 的浮层就不会打开，改由 JS 桥交给 App。
		* 没有桥时（例如在电脑浏览器里预览）保持原样，方便对照排查。
		* @returns 清理函数。
		*/
		function startSettingsBridge() {
			// 启动时把桥的状态打出来：真机上「设置入口没进原生页」时，
			// 这一行能立刻区分是「桥没注入」还是「选择器没命中」。
			const bridgeReady = window[BRIDGE] !== undefined &&
				typeof window[BRIDGE].openAppSettings === "function";
			console.debug("[ui-mobile-shell] 设置入口桥: " + (bridgeReady ? "已注入" : "未注入（设置会走 dsh 自带的浮层）"));

			const probeMissedOnce = { done: false };

			const onClick = (e) => {
				if (!(e.target instanceof Element)) return;
				// App 自己要从「高级 → dsh 原生设置」打开 dsh 面板时会先置这个旁路标记，
				// 否则这次点击会被我们劫持回 App 设置，形成「App 设置 ↔ dsh 设置」死循环。
				if (window.__dshmBypassSettingsBridge === true) return;
				const hit = e.target.closest(SETTINGS_TRIGGER);
				if (hit === null) {
					// 没命中也报一次：真机上「点了设置却进了 dsh 浮层」时，
					// 这行会告诉我们实际点到的是什么元素，好把选择器改准。
					if (!probeMissedOnce.done) {
						const el = e.target.closest("button,[role=button],a") || e.target;
						const cls = (el.className || "").toString().slice(0, 100);
						console.debug(
							"[ui-mobile-shell] 点击未命中设置入口，实际元素: " +
								el.tagName.toLowerCase() + " [" + cls + "]",
						);
						probeMissedOnce.done = true;
					}
					return;
				}
				const bridge = window[BRIDGE];
				if (!bridge || typeof bridge.openAppSettings !== "function") return;
				e.preventDefault();
				e.stopPropagation();
				console.debug("[ui-mobile-shell] 设置入口被劫持 → App 原生设置");
				try {
					bridge.openAppSettings();
				} catch (err) {
					/* 桥调用失败就让 dsh 自己的行为兜底 */
					console.debug("[ui-mobile-shell] 桥调用失败: " + err);
				}
			};
			document.addEventListener("click", onClick, true);
			return () => document.removeEventListener("click", onClick, true);
		}
		//#endregion

		//#region 自检
		/** 结构类名：任何时候都该在，缺了就是移动适配真的失效了。 */
		const REQUIRED = ["frame", "sidebarCol", "centerCol", "rightbarCol"];

		/**
		* 场景类名：只在对应界面打开时才有意义。
		* 例如没建会话时本来就没有输入栏，拿它当「失效」报警只会天天误报。
		*/
		const SCENARIO = ["composerSeat", "tools", "scrollBody", "navList"];

		/**
		* 核对语义类名是否还在 DOM 里；缺失时给出可操作的告警而不是静默失效。
		* dsh 的 CSS 是异步按模块加载的，所以隔一段时间跑两轮。
		*/
		function selfCheck() {
			const missing = REQUIRED.filter((name) => document.querySelector(has(name)) === null);
			if (missing.length > 0) {
				console.warn(
					"[ui-mobile-shell] 结构类名缺失，移动适配已失效：",
					missing,
					"—— dsh 的前端构建变了，请按 recon/class-map.txt 重新核对语义名。",
				);
			}
			const absent = SCENARIO.filter((name) => document.querySelector(has(name)) === null);
			if (absent.length > 0) {
				console.debug(
					"[ui-mobile-shell] 这些场景类名当前不在 DOM 里（对应界面没打开，属正常）：",
					absent,
				);
			}
		}
		//#endregion

		//#region 插件
		/**
		* 客户端插件体：装样式、起状态同步，并往 shell.overlay 槽位挂汉堡按钮与遮罩。
		* @param ctx - 客户端根上下文（需要 slots 与 layout 两个服务）。
		*/
		function apply(ctx) {
			ctx.effect(() => {
				installStyles();
				const stopSync = startDrawerSync();
				const stopGesture = startEdgeGesture(ctx);
				const stopBridge = startSettingsBridge();
				const stopTheme = startThemeFlipWatch();
				const stopWait = startWaitGate();
				const t1 = window.setTimeout(selfCheck, 3000);
				const t2 = window.setTimeout(selfCheck, 9000);
				return () => {
					window.clearTimeout(t1);
					window.clearTimeout(t2);
					stopWait();
					stopTheme();
					stopBridge();
					stopGesture();
					stopSync();
				};
			}, "ui-mobile-shell: 样式、抽屉状态、边缘手势、设置入口与主题过渡");

			const closeDrawer = () => {
				if (readDrawerOpen() === true) ctx.layout.toggleSidebar();
			};

			const Hamburger = () =>
				react.createElement(
					"button",
					{
						type: "button",
						className: SELF + "-burger",
						"aria-label": "打开侧栏",
						onClick: () => {
							ctx.layout.toggleSidebar();
						},
					},
					react.createElement("span", null),
					react.createElement("span", null),
					react.createElement("span", null),
				);

			const Scrim = () =>
				react.createElement("div", {
					className: SELF + "-scrim",
					"aria-hidden": "true",
					onClick: closeDrawer,
				});

			ctx.effect(
				() =>
					ctx.slots.inject("shell.overlay", () =>
						ctx.slots.register(
							{ name: "shell.overlay", id: "mobile-shell-scrim", order: 10, label: "移动端遮罩" },
							Scrim,
						),
					),
				"ui-mobile-shell: 遮罩",
			);

			ctx.effect(
				() =>
					ctx.slots.inject("shell.overlay", () =>
						ctx.slots.register(
							{ name: "shell.overlay", id: "mobile-shell-burger", order: 20, label: "移动端汉堡" },
							Hamburger,
						),
					),
				"ui-mobile-shell: 汉堡按钮",
			);
		}
		//#endregion

		exports.apply = apply;
		exports.inject = ["slots", "layout"];
		return module.exports;
	},
});
