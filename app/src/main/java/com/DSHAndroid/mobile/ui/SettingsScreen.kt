package com.DSHAndroid.mobile.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.DSHAndroid.mobile.settings.AppPrefs
import kotlin.math.roundToInt

/**
 * 原生设置页（二级结构）。
 *
 * 为什么不用 dsh 自带的设置弹窗：那是 desktop-first 的两栏布局，
 * 手机上要么挤成竖排、要么看着像个对话框，跟 AI 客户端的观感差很远。
 * 这里用 Compose 重做：一级列表 → 点进去是二级页，带水平推入过渡。
 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onOpenDshSettings: () -> Unit,
    onShowLog: () -> Unit,
) {
    var page by remember { mutableStateOf("root") }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState != "root"
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(250)) { dir * it / 3 } + fadeIn(tween(200))) togetherWith
                        (slideOutHorizontally(tween(250)) { -dir * it } + fadeOut(tween(150)))
            },
            label = "settings-nav",
        ) { p ->
            when (p) {
                "root" -> SettingsRoot(
                    onBack = onClose,
                    onOpen = { page = it },
                )
                "appearance" -> AppearancePage(onBack = { page = "root" })
                "workspace" -> WorkspaceSettingsPage(onBack = { page = "root" })
                "advanced" -> AdvancedPage(
                    onBack = { page = "root" },
                    onOpenDshSettings = onOpenDshSettings,
                    onShowLog = onShowLog,
                )
                else -> PlaceholderPage(title = pageTitle(p), onBack = { page = "root" })
            }
        }
    }
}

private fun pageTitle(k: String) = when (k) {
    "models" -> "模型与 API"
    "workspace" -> "工作区"
    "skills" -> "技能与插件"
    "about" -> "关于"
    else -> k
}

// ---------------- 一级 ----------------

@Composable
private fun SettingsRoot(onBack: () -> Unit, onOpen: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(title = "设置", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SettingRow("外观", "主题色 · 深色模式 · 字号 · 界面缩放") { onOpen("appearance") }
            SettingRow("模型与 API", "供应商 · 密钥 · 模型") { onOpen("models") }
            SettingRow("工作区", "文件访问 · 权限") { onOpen("workspace") }
            SettingRow("技能与插件", "Skill · MCP · 插件市场") { onOpen("skills") }
            SettingRow("高级", "dsh 原生设置 · 内核日志") { onOpen("advanced") }
            SettingRow("关于", "版本信息") { onOpen("about") }
        }
    }
}

// ---------------- 二级：外观 ----------------

@Composable
private fun AppearancePage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(title = "外观", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionLabel("主题色")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppPrefs.ACCENTS.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { (key, name) ->
                            AccentChip(
                                key = key,
                                name = name,
                                selected = AppPrefs.accent == key,
                                onClick = { AppPrefs.setAccent(ctx, key) },
                            )
                        }
                    }
                }
            }

            SectionLabel("外观模式")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEach { (v, n) ->
                    ChoicePill(n, AppPrefs.darkMode == v) { AppPrefs.setDarkMode(ctx, v) }
                }
            }

            SectionLabel("会话字号")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = AppPrefs.fontPx.toFloat(),
                    onValueChange = { AppPrefs.setFontPx(ctx, it.roundToInt()) },
                    valueRange = 12f..20f,
                    steps = 7,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${AppPrefs.fontPx}",
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SectionLabel("界面缩放")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = AppPrefs.uiScale.toFloat(),
                    onValueChange = { AppPrefs.setUiScale(ctx, (it / 5f).roundToInt() * 5) },
                    valueRange = 70f..120f,
                    steps = 9,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${AppPrefs.uiScale}%",
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                "dsh 的界面是按桌面尺寸设计的，手机上整体偏大。缩到 80~90% 一次能看更多内容，" +
                    "观感也更接近原生客户端。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("界面动效", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "关掉后界面切换不做过渡，低端机更顺滑",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = AppPrefs.animEnabled,
                    onCheckedChange = { AppPrefs.setAnimEnabled(ctx, it) },
                )
            }

            Text(
                "改动会立刻作用到 dsh 界面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun AccentChip(key: String, name: String, selected: Boolean, onClick: () -> Unit) {
    // 选中环的粗细与颜色都做过渡，圆块再轻微放大一点
    val ring by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = tween(durationMillis = 220),
        label = "chip-ring",
    )
    val ringWidth by animateDpAsState(
        targetValue = if (selected) 3.dp else 1.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
        label = "chip-ring-w",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "chip-scale",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(accentColorOf(key))
                .border(width = ringWidth, color = ring, shape = CircleShape)
                .clickable(onClick = onClick),
        )
        Text(
            name,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 与 AppPrefs 里的色值保持一致，仅用于色块展示 */
private fun accentColorOf(key: String): Color = when (key) {
    "blue" -> Color(0xFF2F6FED)
    "violet" -> Color(0xFF7C5CF0)
    "teal" -> Color(0xFF0D9488)
    "green" -> Color(0xFF16A34A)
    "amber" -> Color(0xFFD97706)
    "rose" -> Color(0xFFE0526E)
    "slate" -> Color(0xFF546A8E)
    else -> Color(0xFF4176E6)
}

// ---------------- 二级：高级 ----------------

@Composable
private fun AdvancedPage(onBack: () -> Unit, onOpenDshSettings: () -> Unit, onShowLog: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(title = "高级", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SettingRow("dsh 原生设置", "权限模式 · 语言 · 其他") { onOpenDshSettings() }
            SettingRow("内核日志", "proot / node / dsh 的运行输出") { onShowLog() }
        }
    }
}

@Composable
private fun PlaceholderPage(title: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(title = title, onBack = onBack)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("开发中", style = MaterialTheme.typography.titleMedium)
                Text(
                    "这一页还在做，先占个位",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

// ---------------- 公共件 ----------------

@Composable
internal fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Text("‹", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.outline)
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ChoicePill(text: String, selected: Boolean, onClick: () -> Unit) {
    // 底色 / 文字色 / 描边都是渐变过去，切换时不再是硬跳
    val bg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        animationSpec = tween(durationMillis = 220),
        label = "pill-bg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 220),
        label = "pill-fg",
    )
    val outline by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        else Color.Transparent,
        animationSpec = tween(durationMillis = 220),
        label = "pill-outline",
    )
    // 选中时轻微「弹」一下。用 graphicsLayer 做缩放，不参与布局，不会把同行挤动
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.96f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 650f),
        label = "pill-scale",
    )
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = bg,
        border = BorderStroke(1.dp, outline),
        modifier = Modifier
            .height(36.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = fg,
            )
        }
    }
}
