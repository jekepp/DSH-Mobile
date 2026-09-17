package com.DSHAndroid.mobile.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * App 自己的设置（不碰 dsh 的配置）。
 * 目前只有「外观」这一组；工作区 / 供应商 / 技能以后都挂这里。
 */
object AppPrefs {

    private const val FILE = "dsh_mobile_prefs"
    private const val K_THEME = "theme_accent"
    private const val K_DARK = "dark_mode"      // 0 跟随系统 / 1 浅色 / 2 深色
    private const val K_FONT = "font_scale"
    private const val K_ANIM = "anim_enabled"
    private const val K_SCALE = "ui_scale"
    private const val K_WS_PATH = "workspace_host_path"
    private const val K_WS_TITLE = "workspace_title"
    private const val K_WS_DONE = "workspace_asked"

    /** 主色预设：key 与展示名。default 表示用 dsh 自己的 DeepSeek 蓝 */
    val ACCENTS: List<Pair<String, String>> = listOf(
        "default" to "默认",
        "blue" to "海蓝",
        "violet" to "鸢尾紫",
        "teal" to "青碧",
        "green" to "松绿",
        "amber" to "琥珀",
        "rose" to "绯樱",
        "slate" to "石青",
    )

    private val ACCENT_HEX = mapOf(
        "blue" to "#2f6fed",
        "violet" to "#7c5cf0",
        "teal" to "#0d9488",
        "green" to "#16a34a",
        "amber" to "#d97706",
        "rose" to "#e0526e",
        "slate" to "#546a8e",
    )

    var accent by mutableStateOf("default")
        private set
    var darkMode by mutableStateOf(0)
        private set
    var fontPx by mutableStateOf(14)
        private set
    var animEnabled by mutableStateOf(true)
        private set

    /** 界面缩放百分比（70~120）。dsh 是桌面优先的尺寸，手机 384 CSS px 下缩到 80~90% 更接近原生客户端。 */
    var uiScale by mutableStateOf(100)
        private set

    /**
     * 用户选定的工作区文件夹（**宿主路径**，例如 /storage/emulated/0/Download/MyProj）。
     * null 表示用户跳过了选择，此时退回 App 私有目录。
     */
    var workspaceHostPath by mutableStateOf<String?>(null)
        private set

    /** 工作区显示名，默认取文件夹名 */
    var workspaceTitle by mutableStateOf<String?>(null)
        private set

    /** 是否已经问过「选哪个文件夹当工作区」，避免每次启动都弹 */
    var workspaceAsked by mutableStateOf(false)
        private set

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        accent = sp.getString(K_THEME, "default") ?: "default"
        darkMode = sp.getInt(K_DARK, 0)
        fontPx = sp.getInt(K_FONT, 14)
        animEnabled = sp.getBoolean(K_ANIM, true)
        uiScale = sp.getInt(K_SCALE, 100)
        workspaceHostPath = sp.getString(K_WS_PATH, null)
        workspaceTitle = sp.getString(K_WS_TITLE, null)
        workspaceAsked = sp.getBoolean(K_WS_DONE, false)
    }

    fun setWorkspace(ctx: Context, hostPath: String?, title: String?) {
        workspaceHostPath = hostPath
        workspaceTitle = title
        workspaceAsked = true
        sp(ctx).edit()
            .putString(K_WS_PATH, hostPath)
            .putString(K_WS_TITLE, title)
            .putBoolean(K_WS_DONE, true)
            .apply()
    }

    /** 用户跳过选择：记住别再问，工作区退回 App 私有目录 */
    fun skipWorkspace(ctx: Context) {
        setWorkspace(ctx, null, null)
    }

    /**
     * 只清「已问过」标记，**保留当前路径** —— 用户没重选之前现有工作区照常可用。
     * 下次冷启动会重新走一遍选择流程。
     */
    fun resetWorkspace(ctx: Context) {
        workspaceAsked = false
        sp(ctx).edit().putBoolean(K_WS_DONE, false).apply()
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun setAccent(ctx: Context, v: String) {
        accent = v
        sp(ctx).edit().putString(K_THEME, v).apply()
    }

    fun setDarkMode(ctx: Context, v: Int) {
        darkMode = v
        sp(ctx).edit().putInt(K_DARK, v).apply()
    }

    fun setFontPx(ctx: Context, v: Int) {
        fontPx = v
        sp(ctx).edit().putInt(K_FONT, v).apply()
    }

    fun setAnimEnabled(ctx: Context, v: Boolean) {
        animEnabled = v
        sp(ctx).edit().putBoolean(K_ANIM, v).apply()
    }

    fun setUiScale(ctx: Context, v: Int) {
        uiScale = v
        sp(ctx).edit().putInt(K_SCALE, v).apply()
    }

    /** 主色 hex，default 返回 null（表示不覆盖） */
    fun accentHex(): String? = ACCENT_HEX[accent]

    /**
     * 把外观设置变成注入 dsh 的 CSS。
     *
     * dsh 整个 UI 由 357 个 `--dsw-*` 变量驱动，官方也说「第三方主题 = 覆盖同名 alias 变量」，
     * 所以换肤只要覆盖几个语义变量即可。
     */
    fun themeCss(): String {
        val sb = StringBuilder()
        // 界面缩放放最前面：它改变的是整个页面的 CSS 像素基准，
        // 后面的变量覆盖不受影响。用 zoom 而不是 transform ——
        // zoom 会真正改变布局尺寸（拿到更多 CSS 像素、字号与卡片等比变小），
        // 而 transform 只是视觉缩放，还会创建包含块把抽屉里的 fixed 面板挤歪。
        if (uiScale != 100) {
            sb.append("html{zoom:${uiScale / 100.0} !important;}")
        }
        val hex = accentHex()
        if (hex != null) {
            sb.append(":root{")
            sb.append("--dsw-alias-brand-primary-new-colorprimary-new-color:$hex !important;")
            sb.append("--dsw-alias-link:$hex !important;")
            sb.append("--dsw-alias-button-info-fill:$hex !important;")
            sb.append("--dsw-alias-state-business-primary:$hex !important;")
            sb.append("--dsw-static-deepseek-500:$hex !important;")
            sb.append("}")
        }
        if (fontPx != 14) {
            sb.append("body{--dsh-content-font-size:${fontPx}px !important;}")
        }
        if (!animEnabled) {
            sb.append("*,*::before,*::after{animation:none !important;transition:none !important;}")
        }
        return sb.toString()
    }
}
