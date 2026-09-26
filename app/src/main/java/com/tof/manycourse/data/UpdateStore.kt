package com.tof.manycourse.data

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * **自动更新**的状态与流程（检查 / 下载 / 交给系统安装器）。
 *
 * ## 为什么放单例而不是塞在某个 Composable 里
 *
 * 同一份状态有**两个界面**要用：
 *  - 启动时的自动检查 + 弹窗（挂在「课表」页那棵 Compose 树上，见 `ui/UpdateUi.kt` 的 `UpdateHost`）；
 *  - 设置页里的「检查更新」卡片（手动检查、看当前版本、选更新源）。
 *
 * 塞进某一个界面就得靠回调把状态递上来递下去；放单例两边都直接读，和
 * `MapDevSession` / `WalkRecorder` 是同一个套路。
 *
 * ## 自动检查的节流
 *
 * 每次冷启动都打一次 GitHub/Gitee 不合适（费流量、也可能被 GitHub 限流：未认证 **60 次/小时/IP**）。
 * 所以自动检查有两条闸门：开关（[UiSettings.autoUpdateCheck]）+ **间隔**（[AUTO_CHECK_INTERVAL_MS]），
 * 时间戳落盘，杀进程也不会把闸门弄丢。
 */
object UpdateStore {

    private const val TAG = "UpdateStore"

    /**
     * 自动检查的最小间隔：12 小时。
     *
     * 为什么不是"每次启动"：一天开十次 App 就打十次接口，而版本一天最多发一次；
     * 为什么不是"一天一次整"：晚上发版、第二天早上开 App 就能看到，够及时。
     */
    const val AUTO_CHECK_INTERVAL_MS = 12L * 60L * 60L * 1000L

    /** 正在检查（界面显示"检查中…"）*/
    val checking = mutableStateOf(false)

    /** 上一次检查结果；null = 还没查过 */
    val lastResult = mutableStateOf<UpdateCheckResult?>(null)

    /** 非 null = 正在弹"发现新版本"的弹窗 */
    val dialogUpdate = mutableStateOf<AvailableUpdate?>(null)

    /** 正在下载 */
    val downloading = mutableStateOf(false)

    /** 下载进度 0~1；总长度未知时恒为 0（界面显示不确定进度）*/
    val progress = mutableStateOf(0f)

    /** 已经下载好的安装包 */
    val downloadedFile = mutableStateOf<File?>(null)

    /** 给界面看的一句话（成功/失败都写在这里，不静默）*/
    val message = mutableStateOf<String?>(null)

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * 冷启动时调用：满足"开了开关 + 距上次检查够久"才真去查，查到新版本就弹窗。
     *
     * 静默检查**不报错**（连不上就什么都不做）—— 用户没有主动要求这一刻检查，
     * 弹一个"检查更新失败"只会打扰他。手动检查才会把失败原因说出来。
     */
    fun autoCheck(context: Context) {
        if (!UiSettings.autoUpdateCheck.value) return
        val now = System.currentTimeMillis()
        if (now - UiSettings.lastUpdateCheckAt.value < AUTO_CHECK_INTERVAL_MS) return
        check(context, silent = true)
    }

    /**
     * 检查更新。
     *
     * @param silent true = 自动检查（没更新/失败都不出声）
     */
    fun check(context: Context, silent: Boolean = false) {
        if (checking.value || downloading.value) return
        val app = context.applicationContext
        checking.value = true
        if (!silent) message.value = null

        scope.launch {
            val outcome = runCatching { UpdateChecker.check(app, UiSettings.updateSource.value) }
            checking.value = false
            // 无论成败都记时间：失败后立刻重试只会连撞同一个网络问题
            UiSettings.setLastUpdateCheckAt(System.currentTimeMillis())

            outcome.fold(
                onSuccess = { result ->
                    lastResult.value = result
                    val update = result.update
                    when {
                        update != null -> {
                            downloadedFile.value = UpdateDownloader.existingFile(app, update)
                            progress.value = 0f
                            dialogUpdate.value = update
                        }

                        silent -> Unit
                        result.failures.isNotEmpty() && result.failures.size >= 2 ->
                            message.value = "检查失败：" + describeFailures(result)

                        result.failures.isNotEmpty() ->
                            message.value = "已是最新版本（${result.currentVersion}）；" +
                                describeFailures(result)

                        else -> message.value = "已是最新版本（${result.currentVersion}）"
                    }
                },
                onFailure = { error ->
                    Log.w(TAG, "检查更新异常：${error.message}")
                    if (!silent) message.value = "检查失败：${error.message ?: error.javaClass.simpleName}"
                },
            )
        }
    }

    /** 关掉弹窗（链接式「以后再说」走这里；下载中不允许关，避免状态对不上）*/
    fun dismissDialog() {
        if (downloading.value) return
        dialogUpdate.value = null
    }

    /** 下载；下载完**直接交给系统安装器**（能不能装由系统决定，见 UpdateDownloader 的注释）*/
    fun download(context: Context) {
        val update = dialogUpdate.value ?: return
        if (downloading.value) return
        val app = context.applicationContext
        downloading.value = true
        progress.value = 0f
        message.value = null

        scope.launch {
            val outcome = runCatching {
                UpdateDownloader.download(app, update) { read, total ->
                    if (total > 0L) progress.value = (read.toDouble() / total).toFloat().coerceIn(0f, 1f)
                }
            }
            downloading.value = false
            outcome.fold(
                onSuccess = { file ->
                    downloadedFile.value = file
                    message.value = "已下载 ${file.name}（${formatBytes(file.length())}）"
                    install(app)
                },
                onFailure = { error ->
                    downloadedFile.value = null
                    message.value = "下载失败：${error.message ?: error.javaClass.simpleName}"
                },
            )
        }
    }

    /**
     * 拉起系统安装器。
     *
     * 没拿「安装未知应用」权限时**不硬跳**，只把状态写成一句话 ——
     * 界面会据此显示一个「去授权」按钮，由用户点（自动跳系统设置是打断行为）。
     */
    fun install(context: Context) {
        val file = downloadedFile.value ?: return
        if (!file.isFile) {
            message.value = "安装包不在了，请重新下载"
            downloadedFile.value = null
            return
        }
        if (!canInstallPackages(context)) {
            message.value = "系统还没允许本应用安装应用：点「去授权」打开后回来再装"
            return
        }
        runCatching { context.startActivity(UpdateDownloader.installIntent(context, file)) }
            .onFailure { message.value = "打开安装器失败：${it.message ?: it.javaClass.simpleName}" }
    }

    /** 去开「安装未知应用」（必须在 Activity 上下文里调，所以从界面传进来）*/
    fun openInstallPermission(context: Context) {
        runCatching { context.startActivity(UpdateDownloader.installPermissionIntent(context)) }
            .onFailure { message.value = "打不开授权页：${it.message ?: it.javaClass.simpleName}" }
    }

    /** 需要先授权才能安装（界面用它决定显示「安装」还是「去授权」）*/
    fun needsInstallPermission(context: Context): Boolean = !canInstallPackages(context)

    /** 把各源的失败原因拼成一句人话（两个源都失败时用户最需要看到它）*/
    private fun describeFailures(result: UpdateCheckResult): String =
        result.failures.entries.joinToString("；") { (source, reason) -> "${source.label}：$reason" }
}
