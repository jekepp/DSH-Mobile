package com.DSHAndroid.mobile.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 选工作区文件夹。
 *
 * 为什么需要这个页面：dsh 需要一个工作区才能发消息，而没有工作区时它自己的选择器
 * 在 Android WebView 里表现很糟（会把 proot 的启动目录 `/root` 当成工作区，标题就叫
 * `root`，用户在文件管理器里根本找不到）。所以这里用安卓原生的方式先把工作区定下来，
 * dsh 启动时 registry 里已经有记录，就不会再弹它自己那一套。
 *
 * 只要用户选了文件夹，App 就把它绑定到容器内的 `/workspace`，并把该 guest 路径写进
 * dsh 的工作区注册表。
 *
 * 权限：proot 是原生进程，用不了 SAF 授权，所以读写用户选的文件夹必须拿到
 * MANAGE_EXTERNAL_STORAGE（所有文件访问）。没授权时功能退回 App 私有目录，不会崩。
 */
@Composable
fun WorkspacePickerScreen(
    onDone: (hostPath: String?, title: String?) -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember { mutableStateOf(hasAllFilesAccess()) }
    var picked by remember { mutableStateOf<String?>(null) }
    // 从系统设置页回来后重新读一次权限状态
    var tick by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                granted = hasAllFilesAccess()
                tick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val path = treeUriToPath(uri)
            if (path != null) {
                picked = path
            }
        }
    }

    // 记住 tick 让重组时重新计算按钮可用性
    val canPick = granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || tick >= 0

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "选择工作区文件夹",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "工作区是 Agent 读写文件的地方，它会绑定到容器内的 /workspace。" +
                    "建议选一个你在文件管理器里找得到、也方便备份的文件夹。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(2.dp))

            // ---- 权限卡片 ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(14.dp),
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (granted) "✅ 已获得「所有文件访问」权限" else "需要「所有文件访问」权限",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                if (!granted) {
                    Text(
                        "容器（proot）是原生进程，用不了安卓的文件夹授权机制。" +
                            "要读写你选的文件夹，App 必须拿到这一档权限 —— 这是权限里最重的一个，" +
                            "应用商店通常不允许，但本包是侧载自用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { openAllFilesAccessSettings(ctx) }) {
                        Text("去授权")
                    }
                }
            }

            // ---- 文件夹选择 ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(14.dp),
                    )
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("工作区文件夹", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    picked ?: "尚未选择",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (picked == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Button(
                    onClick = { treePicker.launch(null) },
                    enabled = canPick,
                ) {
                    Text(if (picked == null) "选择文件夹" else "重新选择")
                }
                if (!canPick) {
                    Text(
                        "请先授予上面的权限，否则容器读不到你选的文件夹。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        val p = picked ?: return@Button
                        onDone(p, p.trimEnd('/').substringAfterLast('/').ifBlank { "workspace" })
                    },
                    enabled = picked != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("用这个文件夹")
                }
                TextButton(onClick = { onDone(null, null) }) {
                    Text("用默认目录")
                }
            }

            Text(
                "「用默认目录」= App 私有目录里的 workspace，" +
                    "不需要权限，但在文件管理器里不太好找。之后可以在「设置 → 工作区」里再改。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 是否已拿到「所有文件访问」。Android 11 以下没有这个概念，直接看传统存储权限。 */
fun hasAllFilesAccess(): Boolean = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
} catch (_: Throwable) {
    false
}

/** 跳到本应用的「所有文件访问」设置页。 */
private fun openAllFilesAccessSettings(ctx: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData(Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Throwable) {
        // 个别 ROM 没有这个页面，退回总列表
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
        }
    }
}

/**
 * 把 SAF 的 tree URI 还原成真实文件系统路径。
 *
 * `ACTION_OPEN_DOCUMENT_TREE` 返回的是 `content://` URI，proot 不认；而拿到
 * 「所有文件访问」之后，同一位置是可以用普通路径访问的。常见形态：
 *     content://com.android.externalstorage.documents/tree/primary%3ADownload%2FProj
 *     → documentId = "primary:Download/Proj" → /storage/emulated/0/Download/Proj
 * 部分提供方（如 MT 管理器）会给 `raw:/storage/...` 前缀，这里也一并处理。
 *
 * @returns 真实路径；无法还原时返回 null（调用方据此提示用户重选）。
 */
fun treeUriToPath(uri: Uri): String? = try {
    val docId = try {
        DocumentsContract.getTreeDocumentId(uri)
    } catch (_: Throwable) {
        null
    } ?: return null

    when {
        docId.startsWith("raw:") -> docId.removePrefix("raw:").ifBlank { null }
        docId.contains(":") -> {
            val volume = docId.substringBefore(":")
            val rel = docId.substringAfter(":").trim('/')
            val base = if (volume.equals("primary", ignoreCase = true)) {
                Environment.getExternalStorageDirectory().absolutePath
            } else {
                "/storage/$volume"
            }
            if (rel.isEmpty()) base else "$base/$rel"
        }
        else -> null
    }
} catch (_: Throwable) {
    null
}
