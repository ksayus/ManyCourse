package com.tof.manycourse.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.util.concurrent.Executors

/**
 * Wi-Fi 指纹库：内存状态 + 私有落盘 + **自动同步到系统根目录下的 `ManyCourse/wifi.txt`**。
 *
 * 与 `MapPointStore` 完全同一套路（事实来源放私有目录、公开目录那份当导出产物，
 * 理由见该类的注释），只是内容换成"位置 → 那儿的信号长什么样"。
 *
 * 库里有两批数据，**合并使用、优先级不同**：
 *  - **我自己走出来的**（`filesDir/wifi.txt`）—— 随时可增删；
 *  - **随正式版下发的**（`assets/indoor/` 下的文件，见 [BundledIndoorData]）—— 只读，谁都能用。
 *
 * 新用户装上就有指纹，不用先走一趟；我走完几圈再打进下一版，等于给所有人补数据。
 */
object WifiFingerprintStore {

    private const val TAG = "WifiFingerprintStore"

    /** 私有目录里那份（事实来源）*/
    private const val FILE_NAME = "wifi.txt"

    /** 自己走出来的指纹 */
    val ownSamples = mutableStateListOf<WifiFingerprint>()

    /** 随正式版下发的指纹（只读）*/
    val bundledSamples = mutableStateListOf<WifiFingerprint>()

    /** 库里一共有多少条（自己采的 + 内置的），给面板显示 */
    val totalCount = mutableStateOf(0)

    private var appContext: Context? = null

    private val exportExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wifi-export").apply { isDaemon = true }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 幂等初始化，由 `ManyCourseApp.onCreate` 调用 */
    fun attach(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        loadPrivate(app)
        bundledSamples.clear()
        bundledSamples.addAll(BundledIndoorData.load(app).fingerprints)
        refreshCount()
        // 自己一条都没有时不必导出（避免给内置数据凭空造一份"我自己采的"）
        if (ownSamples.isNotEmpty()) exportAsync(app)
    }

    /** 某个校区有多少条指纹可用（决定"我在这儿"能不能靠 Wi-Fi 做）*/
    fun countFor(campusId: String?): Int =
        if (campusId == null) 0 else (ownSamples + bundledSamples).count { it.campusId == campusId }

    /** 某个校区的全部指纹（自己采的在前，kNN 不分先后）*/
    fun samplesFor(campusId: String?): List<WifiFingerprint> =
        if (campusId == null) {
            emptyList()
        } else {
            (ownSamples + bundledSamples).filter { it.campusId == campusId }
        }

    /** 走完一趟把新指纹并进来 */
    fun addAll(context: Context, samples: List<WifiFingerprint>) {
        if (samples.isEmpty()) return
        ownSamples.addAll(samples)
        refreshCount()
        persistPrivate(context)
        exportAsync(context)
    }

    /** 把我自己采的全清掉（内置的不动）*/
    fun clearOwn(context: Context) {
        if (ownSamples.isEmpty()) return
        ownSamples.clear()
        refreshCount()
        persistPrivate(context)
        exportAsync(context)
    }

    private fun refreshCount() {
        totalCount.value = ownSamples.size + bundledSamples.size
    }

    private fun loadPrivate(context: Context) {
        val text = runCatching { File(context.filesDir, FILE_NAME).takeIf { it.isFile }?.readText() }
            .onFailure { Log.w(TAG, "指纹文件读取失败：${it.message}") }
            .getOrNull() ?: return
        val decoded = WifiFingerprintCodec.decode(text) ?: run {
            Log.w(TAG, "指纹文件头部不认识，已忽略（文件保留）")
            return
        }
        ownSamples.clear()
        ownSamples.addAll(decoded)
    }

    private fun persistPrivate(context: Context) {
        val text = WifiFingerprintCodec.encode(ownSamples.toList())
        runCatching { File(context.filesDir, FILE_NAME).writeText(text) }
            .onFailure { Log.w(TAG, "指纹私有存档写入失败：${it.message}") }
    }

    private fun exportAsync(context: Context) {
        val app = context.applicationContext
        val text = WifiFingerprintCodec.encode(ownSamples.toList())
        exportExecutor.execute {
            val directory = MapPointStore.sharedDirectory(app)
            val error = runCatching {
                if (!directory.isDirectory && !directory.mkdirs()) {
                    return@runCatching "建不了目录：${directory.absolutePath}"
                }
                File(directory, "wifi.txt").writeText(text)
                null
            }.getOrElse { it.message ?: it.javaClass.simpleName }
            if (error != null) main { Log.w(TAG, "指纹导出失败：$error") }
        }
    }

    private fun main(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
