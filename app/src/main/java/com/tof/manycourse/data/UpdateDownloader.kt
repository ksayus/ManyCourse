package com.tof.manycourse.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 下载更新包并把它交给系统安装器。
 *
 * ## 装到哪：**应用专属外部目录**，不是 `/sdcard/ManyCourse`
 *
 * 安装包属于"用完就该扔"的东西，而且要通过 `FileProvider` 交给系统安装器 ——
 * 那要求文件位于**本应用能对外授权**的目录里（`getExternalFilesDir` 正好是）。
 * 放到需要 `MANAGE_EXTERNAL_STORAGE` 的公共目录反而更麻烦，还得不到任何好处。
 * 卸载应用时这个目录会一起消失，不用留垃圾。
 *
 * ## 为什么不做"下载完直接悄悄装"
 *
 * Android 从 8.0 起要求用户**逐次确认**安装（系统安装器界面），而且应用必须先拿到
 * 「安装未知应用」权限（去系统设置里开）。所以这里的流程是：
 *
 * ```
 *   下载完成 → canRequestPackageInstalls() ?
 *              ├─ 是 → 拉起系统安装器（用户点"安装"）
 *              └─ 否 → 如实说明 + 给一个「去授权」入口，不硬跳（跳转是打断用户的行为，要点一下才做）
 * ```
 */
object UpdateDownloader {

    /** 下载更新包的连接/读取超时给得比检查更新宽：APK 有十几 MB，走移动网络还要更慢 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    private const val UPDATE_DIR = "updates"

    /** 更新包落地的目录（外部专属目录不可用时退回内部 cache）*/
    fun directory(context: Context): File {
        val root = context.getExternalFilesDir(null) ?: context.cacheDir
        return File(root, UPDATE_DIR)
    }

    /** 这个版本对应的目标文件（`ManyCourse-1.2.0.3.apk`）*/
    fun targetFile(context: Context, update: AvailableUpdate): File {
        val safeName = update.apkName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "ManyCourse.apk" }
        return File(directory(context), safeName)
    }

    /** 已经下好且**大小对得上**的文件（大小对不上说明上次下到一半断了，必须重下）*/
    fun existingFile(context: Context, update: AvailableUpdate): File? {
        val file = targetFile(context, update)
        if (!file.isFile) return null
        if (update.sizeBytes > 0L && file.length() != update.sizeBytes) return null
        return file
    }

    /**
     * 下载。
     *
     * @param onProgress (已下载字节, 总字节)；总字节未知时为 0，调用方据此显示"不确定进度"
     * @return 落地后的文件
     */
    suspend fun download(
        context: Context,
        update: AvailableUpdate,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        // 先下到 .part 再改名：中途断网/被杀不会留下一个"看起来下好了"的半截 APK
        val target = targetFile(context, update)
        val temporary = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()

        val request = Request.Builder()
            .url(update.apkUrl)
            .header("User-Agent", UpdateConfig.USER_AGENT)
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("响应没有内容")
            val total = body.contentLength().takeIf { it > 0 } ?: update.sizeBytes
            var read = 0L
            body.byteStream().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        read += count
                        onProgress(read, total)
                    }
                    output.flush()
                }
            }
        }

        // 服务端给了大小就得对得上：对不上说明这份包不完整，宁可不装
        if (update.sizeBytes > 0L && temporary.length() != update.sizeBytes) {
            temporary.delete()
            error("下载不完整（${temporary.length()} / ${update.sizeBytes} 字节）")
        }
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        target
    }

    /** 拉起系统安装器；调用方要先确认 [canInstallPackages] */
    fun installIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 「安装未知应用」的授权页（这是逐应用授权的，普通权限弹窗给不了）*/
    fun installPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
