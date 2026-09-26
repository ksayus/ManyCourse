package com.tof.manycourse.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tof.manycourse.data.AvailableUpdate
import com.tof.manycourse.data.UpdateCheckResult
import com.tof.manycourse.data.UpdateConfig
import com.tof.manycourse.data.UpdateSourcePreference
import com.tof.manycourse.data.UpdateStore
import com.tof.manycourse.data.UiSettings
import com.tof.manycourse.data.formatBytes
import com.tof.manycourse.ui.components.CampusButton
import com.tof.manycourse.ui.components.CampusChip
import com.tof.manycourse.ui.components.GlassCard
import com.tof.manycourse.ui.theme.LocalGlassTokens

/**
 * 自动更新的**界面宿主**：冷启动时静默查一次，有新版本就弹窗。
 *
 * 挂在哪：`ClassScheduleFragment` 那棵 Compose 树（四个页面常驻，进主界面就会被组合）。
 * `AlertDialog` 是**独立窗口**，所以即使当前显示的是别的 Tab，弹窗照样浮在最上面 ——
 * 不需要为它单独加一个 Activity 或往 `manycourse_main.xml` 里塞 ComposeView。
 */
@Composable
internal fun UpdateHost() {
    val context = LocalContext.current
    // 只在进主界面时查一次（受开关与 12 小时间隔两道闸门，见 UpdateStore.autoCheck）
    LaunchedEffect(Unit) { UpdateStore.autoCheck(context) }

    UpdateStore.dialogUpdate.value?.let { update -> UpdateDialog(context, update) }
}

/** 「发现新版本」弹窗：说明版本/来源/大小，下载带进度，下载完交给系统安装器 */
@Composable
private fun UpdateDialog(context: Context, update: AvailableUpdate) {
    val tokens = LocalGlassTokens.current
    val downloading by UpdateStore.downloading
    val progress by UpdateStore.progress
    val file = UpdateStore.downloadedFile.value
    val message = UpdateStore.message.value
    val needsPermission = UpdateStore.needsInstallPermission(context)

    AlertDialog(
        // 下载中不让点外面关掉：进度条会消失，用户以为没在下载
        onDismissRequest = { if (!downloading) UpdateStore.dismissDialog() },
        title = { Text("发现新版本 ${update.version}", fontSize = 16.sp) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "${UpdateStore.lastResult.value?.currentVersion?.let { "当前 $it → " }.orEmpty()}" +
                        "新版本 ${update.version} · 来自 ${update.source.label} · ${formatBytes(update.sizeBytes)}",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (update.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "更新说明",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        // 按纯文本显示：Release 说明是 Markdown，但渲染 Markdown 要引一整套解析器，
                        // 而更新说明本来就是"看个大概"，不值得为它加依赖
                        text = update.notes.take(2000),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (downloading) {
                    Spacer(Modifier.height(12.dp))
                    if (progress > 0f) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "正在下载 ${(progress * 100).toInt()}%",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text("正在下载…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (file != null && !downloading) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "已下载：${file.name}（${formatBytes(file.length())}）",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (needsPermission) {
                        Text(
                            text = "系统还没允许本应用安装应用 —— 点「去授权」打开开关，回来再点安装。",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // 拿不到安装包时的出口：去浏览器看这一次发布（HTML 页面永远打得开）
                if (update.pageUrl.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "在浏览器里打开这一版",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { context.openUrl(update.pageUrl) }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            val label = when {
                downloading -> "下载中…"
                file != null && needsPermission -> "去授权"
                file != null -> "安装"
                else -> "下载并安装"
            }
            TextButton(
                enabled = !downloading,
                onClick = {
                    when {
                        file != null && needsPermission -> UpdateStore.openInstallPermission(context)
                        file != null -> UpdateStore.install(context)
                        else -> UpdateStore.download(context)
                    }
                },
            ) { Text(label) }
        },
        dismissButton = {
            if (!downloading) {
                TextButton(onClick = { UpdateStore.dismissDialog() }) { Text("以后再说") }
            }
        },
    )
}

/**
 * 设置页的「检查更新」卡片。
 *
 * 显示**当前版本**（用户核对"我装的是哪个"的唯一入口）与上一次检查的结果。
 */
@Composable
internal fun UpdateCheckCard() {
    val context = LocalContext.current
    val checking by UpdateStore.checking
    val result: UpdateCheckResult? = UpdateStore.lastResult.value
    val message = UpdateStore.message.value

    GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "检查更新",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "当前版本 ${result?.currentVersion ?: UpdateCheckerVersionFallback}",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = if (UpdateConfig.GITEE_REPO.isBlank()) {
                    "更新源：GitHub（Gitee 未配置，见 UpdateConfig.GITEE_REPO）"
                } else {
                    "更新源：GitHub + Gitee"
                },
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            message?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(12.dp))
            CampusButton(
                text = if (checking) "检查中…" else "检查更新",
                enabled = !checking,
                modifier = Modifier.fillMaxWidth(),
                onClick = { UpdateStore.check(context) },
            )
        }
    }
}

/** 更新源与自动检查的开关（一张卡）*/
@Composable
internal fun UpdateSourceCard() {
    val autoCheck by UiSettings.autoUpdateCheck
    val source by UiSettings.updateSource
    val giteeConfigured = UpdateConfig.GITEE_REPO.isNotBlank()

    GlassCard(cornerRadius = 12, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "启动时自动检查更新",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (autoCheck) {
                            "进主界面时静默查一次（最长 12 小时一次），只提示不自动下载"
                        } else {
                            "已关闭，只能手动检查"
                        },
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = autoCheck,
                    onCheckedChange = { UiSettings.setAutoUpdateCheck(it) },
                    modifier = Modifier.semantics { contentDescription = "自动检查更新开关" },
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                text = "更新源",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UpdateSourcePreference.entries.forEach { option ->
                    // Gitee 没配仓库时不给它单独选：选了也永远是"未配置"
                    val enabled = giteeConfigured || option != UpdateSourcePreference.Gitee
                    CampusChip(
                        text = option.label,
                        selected = source == option,
                        onClick = { if (enabled) UiSettings.setUpdateSource(option) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = source.summary + if (!giteeConfigured) {
                    "　（Gitee 源还没填仓库路径：UpdateConfig.GITEE_REPO 填上之后这里就能选）"
                } else {
                    ""
                },
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 检查结果里没有当前版本时的兜底文案（正常情况读得到，这里只是别让界面空着）*/
private const val UpdateCheckerVersionFallback = "读取失败"

/** 用系统浏览器打开一个地址（失败就算了：这只是"拿不到包"时的出口）*/
private fun Context.openUrl(url: String) {
    runCatching {
        startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
