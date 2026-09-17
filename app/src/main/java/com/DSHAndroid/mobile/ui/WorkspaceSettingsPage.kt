package com.DSHAndroid.mobile.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.DSHAndroid.mobile.settings.AppPrefs

/** 工作区所在的 guest 路径（Kernel 把用户选的宿主文件夹绑到这里）。 */
private const val GUEST_WORKSPACE = "/workspace"

/**
 * 二级页：工作区。
 *
 * 只做查看 + 重新选择。重新选择不会当场生效 —— 工作区必须在 dsh 启动**之前**
 * 写进它的注册表，所以这里只是把「已问过」的标记清掉，下次冷启动会重新走选择流程。
 */
@Composable
fun WorkspaceSettingsPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var reset by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(title = "工作区", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SectionLabel("当前工作区")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    AppPrefs.workspaceHostPath ?: "App 私有目录（未选择文件夹）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    buildString {
                        append("容器内路径：")
                        append(GUEST_WORKSPACE)
                        AppPrefs.workspaceTitle?.let { append("　·　显示名：").append(it) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionLabel("文件访问权限")
            Text(
                if (hasAllFilesAccess()) {
                    "✅ 已获得「所有文件访问」权限，可以选择任意文件夹"
                } else {
                    "❌ 未获得「所有文件访问」权限。容器是原生进程、用不了安卓的文件夹授权，" +
                        "所以没这档权限时只能待在 App 私有目录里。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )

            SectionLabel("更改工作区")
            Text(
                "工作区要在 dsh 启动**之前**写进它的注册表，所以重新选择不会当场生效，" +
                    "需要冷启动一次 App。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = {
                resetWorkspace(ctx)
                reset = true
            }) {
                Text("重新选择文件夹")
            }
            if (reset) {
                Text(
                    "已记录。请完全退出并重新打开 App —— 启动时会再问你一次要把哪个文件夹当工作区。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** 只清「已问过」标记，保留当前路径 —— 用户没重选之前，现有工作区照常可用。 */
private fun resetWorkspace(ctx: Context) {
    AppPrefs.resetWorkspace(ctx)
}
