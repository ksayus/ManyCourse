package com.tof.manycourse.data

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.io.File
import java.util.concurrent.Executors

/** 开发者模式里"数据落到哪儿了"的如实汇报（全部字段都给 UI 用，不猜、不美化）*/
data class MapStorageState(
    /** 实际导出的目录绝对路径；还没 attach 时为 null */
    val directory: String? = null,
    /** true = 真的是"系统根目录下的 ManyCourse"；false = 回落到了应用专属目录（见 [MapPointStore.sharedDirectory]）*/
    val shared: Boolean = false,
    /** 上次成功导出的时间（毫秒）；0 = 还没导出过 */
    val lastExportAt: Long = 0L,
    /** 上次导出的错误原因；null = 没出错 */
    val lastError: String? = null,
)

/**
 * 采集到的**点位与标定**的仓库：内存状态 + 落盘 + **自动同步到系统根目录下的 `ManyCourse/`**。
 *
 * ## 两份文件，谁是事实来源
 *
 * ```
 *   app 私有目录 filesDir/mappoints.txt   ← 事实来源（永远可写，不需要任何权限）
 *            │  每次增删都同步写一份
 *            ▼
 *   <系统根目录>/ManyCourse/mappoints.txt ← 给人看的/给别的工具看的
 *   <系统根目录>/ManyCourse/mappoints.csv
 *   <系统根目录>/ManyCourse/mapanchors.csv
 * ```
 *
 * 为什么事实来源**不是**那个"系统根目录"的文件：写它需要 `MANAGE_EXTERNAL_STORAGE`
 * （Android 11 起，直接往 `/sdcard/xxx` 写文件必须用户手动授予"所有文件访问权限"），
 * 而权限随时可能没给 / 被撤销 / 存储没挂载。要是拿它当事实来源，
 * 权限一没，用户采了一下午的点位就**读不回来**了。
 * 私有目录不需要权限、也删不掉，所以它做事实来源；公开目录那份是**导出产物**，可以随时重写。
 *
 * ## 权限没给会怎样
 *
 * 自动回落到**应用专属外部目录** `Android/data/<包名>/files/ManyCourse/`
 * （不需要任何权限，`adb pull` 也能取），并在 [storage] 里如实标记
 * `shared = false`，开发者模式面板会把真实路径显示出来 ——
 * 而不是假装写成功了（那会让人以为数据在 `/sdcard/ManyCourse` 里，去找却是空的）。
 *
 * ## 线程
 *
 * - **内存状态的读写必须在主线程**（`mutableStateListOf` 是 Compose 快照状态）；
 * - 私有目录那份**同步写**（内网小文件，几 KB）；
 * - 公开目录那份**丢到单线程后台**去写（外置存储可能慢，不值得卡住 UI），
 *   写完再切回主线程更新 [storage]。
 */
object MapPointStore {

    private const val TAG = "MapPointStore"

    /** 私有目录里那份（事实来源）*/
    private const val FILE_NAME = "mappoints.txt"

    /** 系统根目录下给用户看的那个目录名（需求原话就叫这个）*/
    const val SHARED_DIR_NAME = "ManyCourse"

    /** 采集到的点位（全部校区混在一起，各自带 campusId）—— **只装"我自己采的"** */
    val points = mutableStateListOf<MapPoint>()

    /** 标定锚点（同样是"我自己采的"）*/
    val anchors = mutableStateListOf<MapAnchor>()

    /**
     * **随正式版下发的**点位（`assets/indoor/`，见 [BundledIndoorData]）—— 只读。
     *
     * 为什么不和 [points] 混在一起：
     *  - 落盘/导出只该写"我自己采的"，混进去等于把内置数据抄成自己的，越滚越多；
     *  - 内置点位**删不掉**（它随 APK 走），列表里要让用户看得出来哪些是自己采的。
     */
    val bundledPoints = mutableStateListOf<MapPoint>()

    /** 随正式版下发的锚点（只读）—— 只在我自己**还没标定**这个校区时采用 */
    val bundledAnchors = mutableStateListOf<MapAnchor>()

    /** 全部点位（自己采的 + 内置的）—— **匹配教室名时用这个** */
    val allPoints: List<MapPoint> get() = points + bundledPoints

    /** 落盘/导出的如实状态，给开发者模式面板显示 */
    val storage = mutableStateOf(MapStorageState())

    private var appContext: Context? = null

    /** 导出走单线程：外置存储写的顺序必须稳定（后写的不该被前写的盖掉）*/
    private val exportExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mappoint-export").apply { isDaemon = true }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 幂等初始化，由 `ManyCourseApp.onCreate` 调用 */
    fun attach(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        load(app)
        mergeBundled(app)
        // 一进来就同步一次：这样 `/sdcard/ManyCourse/` 目录和文件当场就存在，
        // 用户不必"先采一个点"才知道数据会落到哪
        exportAsync(app)
    }

    /**
     * 把随正式版下发的数据并进来（只读，删不掉）。
     *
     * 优先级**永远是"我自己采的"更高**：
     *  - 同名点位（同校区 + 归一化后同名）→ 跳过内置那条；
     *  - 这个校区我自己已经有 ≥ [MIN_ANCHORS] 个锚点（= 我自己标定过）→ 不用内置锚点，
     *    否则会把用户辛苦标的标定悄悄换掉。
     */
    private fun mergeBundled(context: Context) {
        val payload = BundledIndoorData.load(context)
        if (payload.isEmpty) return

        val takenNames = points.map { key(it.campusId, it.name) }.toMutableSet()
        payload.points.forEach { point ->
            val name = key(point.campusId, point.name)
            if (!takenNames.add(name)) return@forEach
            bundledPoints += point.copy(id = "bundled-${point.campusId}-${name.hashCode()}")
        }

        payload.anchors.groupBy { it.campusId }.forEach { (campus, bundledOfCampus) ->
            if (anchorsOf(campus).size >= MIN_ANCHORS) return@forEach
            bundledOfCampus.forEach { anchor ->
                if (bundledAnchors.none { it.campusId == anchor.campusId && it.name == anchor.name }) {
                    bundledAnchors += anchor
                }
            }
        }
        updateStorage(context)
    }

    /** 同名判定用的键：同校区 + 归一化后的名字（`A1N403` 和 `a1n403` 是同一个人地方）*/
    private fun key(campusId: String, name: String): String = "$campusId|${normalizePlace(name)}"

    // ── 读 ────────────────────────────────────────────────────────────────

    private fun load(context: Context) {
        val text = runCatching { File(context.filesDir, FILE_NAME).takeIf { it.isFile }?.readText() }
            .onFailure { Log.w(TAG, "点位文件读取失败：${it.message}") }
            .getOrNull() ?: return
        val file = MapPointCodec.decode(text)
        if (file == null) {
            // 头部不对（旧版本 / 别人的文件）：不猜、不合并，直接当作没有 —— 但**不删**它，
            // 免得把用户手工编辑过的东西一把抹掉
            Log.w(TAG, "点位文件头部不认识，已忽略（文件保留）")
            return
        }
        points.clear()
        points.addAll(file.points)
        anchors.clear()
        anchors.addAll(file.anchors)
    }

    /**
     * 从"系统根目录那份"重新读回来（开发者模式里的「重载」）。
     *
     * 用途：在电脑/文件管理器里把 CSV 改好、或者手工修好 `mappoints.txt` 之后，
     * 让 App 把改动吃进来。**只在用户主动点的时候做** —— 自动重载会在两边不一致时
     * 悄悄覆盖掉刚采的点位。
     *
     * @return true = 读到了且格式认得
     */
    fun reloadFromShared(context: Context): Boolean {
        val file = File(sharedDirectory(context), "mappoints.txt")
        val text = runCatching { file.takeIf { it.isFile }?.readText() }
            .onFailure { Log.w(TAG, "重载失败：${it.message}") }
            .getOrNull() ?: return false
        val decoded = MapPointCodec.decode(text) ?: return false
        points.clear()
        points.addAll(decoded.points)
        anchors.clear()
        anchors.addAll(decoded.anchors)
        persistPrivate(context)
        updateStorage(context)
        return true
    }

    // ── 写 ────────────────────────────────────────────────────────────────

    /** 加一个点位（原地采的 / 图上采的都在这里）。返回落库后的对象 */
    fun addPoint(context: Context, point: MapPoint): MapPoint {
        points.add(point)
        persistAndExport(context)
        return point
    }

    /** 删一个点位（按 id）*/
    fun removePoint(context: Context, id: String) {
        if (!points.removeAll { it.id == id }) return
        persistAndExport(context)
    }

    /** 加一个标定锚点 */
    fun addAnchor(context: Context, anchor: MapAnchor) {
        anchors.add(anchor)
        persistAndExport(context)
    }

    /** 删一个锚点（按"校区 + 名字"删：锚点没有 id，同名同校区的只该有一个）*/
    fun removeAnchor(context: Context, campusId: String, name: String) {
        if (!anchors.removeAll { it.campusId == campusId && it.name == name }) return
        persistAndExport(context)
    }

    /** 只清某个校区的点位与锚点（换学校/重采时用；别的校区不受影响）*/
    fun clearCampus(context: Context, campusId: String) {
        val removedPoints = points.removeAll { it.campusId == campusId }
        val removedAnchors = anchors.removeAll { it.campusId == campusId }
        if (!removedPoints && !removedAnchors) return
        persistAndExport(context)
    }

    // ── 查询 ──────────────────────────────────────────────────────────────

    /** 某个校区的**全部**点位（自己采的 + 内置的）—— 匹配教室名、画标记都用它 */
    fun pointsOf(campusId: String?): List<MapPoint> =
        if (campusId == null) emptyList() else allPoints.filter { it.campusId == campusId }

    /** 某个校区**我自己采的**点位（列表里可删的那些）*/
    fun ownPointsOf(campusId: String?): List<MapPoint> =
        if (campusId == null) emptyList() else points.filter { it.campusId == campusId }

    /** 某个校区的锚点（自己采的 + 内置的）—— 标定计算用 */
    fun anchorsOf(campusId: String?): List<MapAnchor> =
        if (campusId == null) emptyList() else anchors.filter { it.campusId == campusId } +
            bundledAnchors.filter { it.campusId == campusId }

    /** 某个校区**我自己采的**锚点（列表里可删的那些）*/
    fun ownAnchorsOf(campusId: String?): List<MapAnchor> =
        if (campusId == null) emptyList() else anchors.filter { it.campusId == campusId }

    /**
     * 某个校区**算好的标定**；锚点不够（< [MIN_ANCHORS]）或解不出来时返回 null。
     *
     * 每次调用现算（几个锚点的最小二乘，微秒级）：这样"刚采了一个锚点"就能立刻生效，
     * 不需要在任何地方缓存、也就不会出现"缓存过期了显示的还是旧标定"。
     */
    fun calibrationOf(campusId: String?): MapCalibration? =
        campusId?.let { buildCalibration(anchorsOf(it)) }

    // ── 落盘与导出 ────────────────────────────────────────────────────────

    /**
     * 系统根目录下的 `ManyCourse/`；拿不到"所有文件访问权限"时回落到应用专属外部目录。
     *
     * @see storage 里的 `shared` 字段会如实标记用的是哪一个
     */
    @Suppress("DEPRECATION") // 拿到"所有文件访问"之后，这个 API 仍是"系统根目录"的唯一入口
    fun sharedDirectory(context: Context): File {
        val root = if (hasAllFilesAccess()) {
            Environment.getExternalStorageDirectory()
        } else {
            context.getExternalFilesDir(null) ?: context.filesDir
        }
        return File(root, SHARED_DIR_NAME)
    }

    /**
     * 有没有"所有文件访问权限"（`MANAGE_EXTERNAL_STORAGE`）。
     *
     * 这条路是**必须**的：Android 11 起，往 `/sdcard/自己起的目录/` 写文件不再允许，
     * 要么用 MediaStore（不适合这种结构化文本）、要么申请这个特殊权限。
     * 它要用户去系统设置里手动打开（不是一个弹窗能给的），所以开发者模式面板上
     * 给一个「去授权」按钮，并在没授权时**如实显示回落路径**。
     */
    fun hasAllFilesAccess(): Boolean = runCatching {
        Environment.isExternalStorageManager()
    }.getOrDefault(false)

    private fun persistAndExport(context: Context) {
        persistPrivate(context)
        exportAsync(context)
    }

    /** 事实来源那份：同步写（内网小文件）*/
    private fun persistPrivate(context: Context) {
        val text = MapPointCodec.encode(MapPointFile(points.toList(), anchors.toList()))
        runCatching { File(context.filesDir, FILE_NAME).writeText(text) }
            .onFailure { Log.w(TAG, "点位私有存档写入失败：${it.message}") }
    }

    /**
     * 导出到 `ManyCourse/`：**三份**
     *  - `mappoints.txt` —— 应用自己读的那份（重载时用）；
     *  - `mappoints.csv` / `mapanchors.csv` —— 给人看、拿去改代码的。
     */
    private fun exportAsync(context: Context) {
        val app = context.applicationContext
        val file = MapPointFile(points.toList(), anchors.toList())
        exportExecutor.execute {
            val directory = sharedDirectory(app)
            val error = writeExport(directory, file)

            main {
                storage.value = MapStorageState(
                    directory = directory.absolutePath,
                    shared = hasAllFilesAccess(),
                    lastExportAt = if (error == null) System.currentTimeMillis() else storage.value.lastExportAt,
                    lastError = error,
                )
                if (error != null) Log.w(TAG, "点位导出失败：$error")
            }
        }
    }

    /** 真正落盘那一步；返回 null = 成功，否则是人话的错误原因 */
    private fun writeExport(directory: File, file: MapPointFile): String? = runCatching {
        if (!directory.isDirectory && !directory.mkdirs()) {
            return@runCatching "建不了目录：${directory.absolutePath}"
        }
        File(directory, "mappoints.txt").writeText(MapPointCodec.encode(file))
        File(directory, "mappoints.csv").writeText(pointsToCsv(file.points))
        File(directory, "mapanchors.csv").writeText(anchorsToCsv(file.anchors))
        null
    }.getOrElse { it.message ?: it.javaClass.simpleName }

    /** 只刷新 [storage]（权限可能在系统设置里刚被打开/关掉，面板要跟着变）*/
    fun updateStorage(context: Context) {
        val directory = sharedDirectory(context)
        storage.value = storage.value.copy(
            directory = directory.absolutePath,
            shared = hasAllFilesAccess(),
        )
    }

    /** 把一段逻辑切回主线程执行（与 `WeekScheduleStore` 同一套路）*/
    private fun main(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
