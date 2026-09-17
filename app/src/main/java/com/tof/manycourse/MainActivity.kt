package com.tof.manycourse

import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.tof.manycourse.data.LoginSettings
import com.tof.manycourse.data.School
import com.tof.manycourse.data.SessionStore
import com.tof.manycourse.data.UiSettings
import com.tof.manycourse.gr_api.LoginCaptcha
import com.tof.manycourse.gr_api.LoginResult
import com.tof.manycourse.gr_api.SchoolRegistry
import com.tof.manycourse.ui.Motion
import com.tof.manycourse.ui.SchoolDropdownAdapter
import com.tof.manycourse.ui.TextFit
import com.tof.manycourse.ui.disableContrastScrims
import com.tof.manycourse.ui.fitCurrentTextToOneLine
import com.tof.manycourse.ui.theme.glassTokens

/**
 * 登录页（全应用唯一的 XML 页面）。
 *
 * 「选择学校」的数据流：
 * ```
 *   SchoolRegistry.schools        ← 学校清单（唯一扩展点，见 gr_api/SchoolRegistry.kt）
 *        ↓  SchoolDropdownAdapter
 *   EditSchool 下拉  ──选中──▶  selectedSchool + LoginSettings（持久化）
 *        ↓ 点「登录」
 *   SchoolRegistry.apiOf(id).login(...)  ← 按学校分发到 gr_api/schools/ 下对应实现
 * ```
 * **加一所学校只需要动 `gr_api/`，这个 Activity 不用改。**
 */
class MainActivity : ComponentActivity() {

    private lateinit var schoolInput: MaterialAutoCompleteTextView
    private lateinit var username: TextInputEditText
    private lateinit var password: TextInputEditText
    private lateinit var loginBtn: Button
    private lateinit var loginInfoText: TextView
    private lateinit var schoolAdapter: SchoolDropdownAdapter

    /** 验证码区块（默认 gone）：只有学校返回 NeedCaptcha 时才显示 */
    private lateinit var captchaBlock: View
    private lateinit var captchaTip: View
    private lateinit var captchaImage: ImageView
    private lateinit var captchaEdit: TextInputEditText

    /**
     * 服务端给的验证码标识（CAS 里是 `uid`）；非 null = 这一次登录需要用户填验证码。
     *
     * 每次点登录都把它连同输入框里的得数一起回传 —— 学校要求验证码时，
     * 答案不对服务端会再回一次 CODEFALSE，我们会重新取一张新图并覆盖它。
     */
    private var pendingCaptcha: LoginCaptcha? = null

    /** 当前选中的学校；null = 还没选（点登录会被拦下来） */
    private var selectedSchool: School? = null

    /**
     * 校名的基准字号（px）：**取布局里声明的那份原值**。
     * 不要用 `spToPx(16)` 重算 —— 实测 XML 的 16sp 渲染成 51.0px，
     * 而 `spToPx(16)` 算出来是 50.545px，差 1%，会导致"明明放得下却把字号改掉"。
     */
    private var schoolNameBasePx = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ── 维持登录：已经登录（含冷启动恢复出来的会话）就直接进主界面 ──────────
        // 放在 setContentView 之前，登录页根本不会被绘制，所以看不到一闪而过的登录界面；
        // 会话是从 SessionStore（Application.onCreate 里恢复的）读的，这里只是一次状态判断。
        if (SessionStore.isLoggedIn) {
            Log.d(TAG, "检测到已登录会话（${SessionStore.schoolId.value}），跳过登录页")
            startActivity(Intent(this, ManyCourseMain::class.java))
            finish()
            return
        }

        // 透明状态栏/导航栏 + 关闭系统对比度保护层，让渐变背景铺满全屏
        // 用 auto（而非 light）：夜间模式下系统图标自动变白，否则深底上看不见图标
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        window.disableContrastScrims()
        setContentView(R.layout.activity_login)

        // MIUI 等系统会为手势提示条强制绘制白色底条，隐藏导航栏（上滑可临时呼出）以实现真全屏
        // 注意：需在 decorView 附着到窗口后执行，否则 hide 不生效
        window.decorView.post {
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.navigationBars())
            }
        }

        // 全屏背景 + 内容避让：把系统栏/挖孔/键盘高度转为容器内边距
        // 用 systemBars + displayCutout（而不只是 systemBars）：异形屏横屏时挖孔在侧边，
        // 只取 systemBars 会让标题/输入框被挖孔或圆角压住
        val container = findViewById<View>(R.id.loginContainer)
        val basePaddingTop = container.paddingTop
        val basePaddingBottom = container.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(
                left = safe.left,
                right = safe.right,
                top = basePaddingTop + safe.top,
                bottom = basePaddingBottom + maxOf(safe.bottom, ime.bottom)
            )
            insets
        }

        username = findViewById<TextInputEditText>(R.id.EditUserName)
        password = findViewById<TextInputEditText>(R.id.EditPassword)
        loginBtn = findViewById<Button>(R.id.login_button)
        loginInfoText = findViewById<TextView>(R.id.login_info_textView)
        captchaBlock = findViewById(R.id.captchaBlock)
        captchaTip = findViewById(R.id.captchaTip)
        captchaImage = findViewById(R.id.captchaImage)
        captchaEdit = findViewById(R.id.EditCaptcha)
        // 在验证码框里按软键盘的「完成」直接提交，省一次点击
        captchaEdit.setOnEditorActionListener { _, _, _ ->
            doLogin()
            true
        }

        // 登录页图标由布局的 android:src 直接给出，这里不再 setImageResource ——
        // 图和 View 都写在 activity_login.xml 里，改图标只需要换资源文件

        // 登录页玻璃卡片跟随全局玻璃风格设置
        applyGlassMode()

        setupSchoolPicker()

        loginBtn.setOnClickListener { doLogin() }
    }

    // ── 选择学校 ────────────────────────────────────────────────────────────

    private fun setupSchoolPicker() {
        schoolInput = findViewById(R.id.EditSchool)
        schoolNameBasePx = schoolInput.textSize

        schoolAdapter = SchoolDropdownAdapter(this, SchoolRegistry.schools)
        schoolInput.setAdapter(schoolAdapter)
        // 弹窗是独立 Window，不会继承玻璃卡片的背景，必须自带圆角底色
        // （XML 里也写了 android:popupBackground，这里再设一次是为了防 Material 主题覆盖）
        schoolInput.setDropDownBackgroundDrawable(
            requireNotNull(ContextCompat.getDrawable(this, R.drawable.bg_school_dropdown))
        )
        // 点整行都能展开：AutoCompleteTextView 只在拿到焦点时才自动弹，
        // 而 inputType=none 的只读下拉体验上应该"点到就开"
        schoolInput.setOnClickListener { schoolInput.showDropDown() }
        schoolInput.setOnItemClickListener { _, _, position, _ ->
            showSelectedSchool(schoolAdapter.getItem(position))
        }

        // 每次布局变化都重算一次（首次布局、Material 补上起始图标内边距、
        // 分屏改宽、系统字号变化都算在内）。
        // applySchoolNameFit 是幂等的：放得下时一个字都不动，
        // 所以这里不需要"宽度是否变了"之类的判断，也不会自我触发成死循环。
        schoolInput.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applySchoolNameFit() }

        // 恢复上次选的学校。学校被下架时 find 返回 null —— 故意回落到"未选择"，
        // 逼用户重新确认一次，而不是悄悄登录到一个已经不在清单里的学校。
        showSelectedSchool(SchoolRegistry.find(LoginSettings.selectedSchoolId.value))
    }

    /**
     * 选中（或启动时恢复）一所学校：写文本 → 按需缩放字号把它压进一行 → 持久化。
     *
     * 字号自适应见 `ui/TextFit.kt`：短校名保持基准 16sp，只有放不下的长校名
     * （如「南京航空航天大学金城学院」）才缩小，保证单行完整显示、不折行。
     */
    private fun showSelectedSchool(school: School?) {
        selectedSchool = school
        schoolInput.setText(school?.name.orEmpty(), false)
        // TextView 在「定宽 + 高度不变」时是就地换一份 Layout、**不会**触发布局回调的，
        // 所以这里必须主动算一次；布局监听负责其余场景（内边距补齐、分屏、系统字号变化）。
        applySchoolNameFit()
        LoginSettings.setSelectedSchoolId(school?.id)
    }

    /** 把校名压进一行。幂等：放得下时不做任何改动，所以可以随便重复调用。 */
    private fun applySchoolNameFit() {
        if (schoolNameBasePx <= 0f) return
        val metrics = resources.displayMetrics
        val appliedPx = schoolInput.fitCurrentTextToOneLine(
            baseSizePx = schoolNameBasePx,
            minSizePx = TextFit.spToPx(SCHOOL_NAME_MIN_SP, metrics),
            stepSizePx = TextFit.spToPx(SCHOOL_NAME_STEP_SP, metrics),
        )
        if (appliedPx != null) {
            Log.d(TAG, "校名自适应：字号 → ${appliedPx}px（基准 ${schoolNameBasePx}px）")
        }
    }

    // ── 登录 ────────────────────────────────────────────────────────────────

    private fun doLogin() {
        val school = selectedSchool
        val account = username.text?.toString()?.trim().orEmpty()
        val pwd = password.text?.toString().orEmpty()

        // 校验失败走 @color/login_info_error 令牌（夜间模式自动换色，深底上仍清晰）
        if (school == null) return showLoginError(getString(R.string.login_page_error_school))
        if (account.isEmpty()) return showLoginError(getString(R.string.login_page_error_username))
        if (pwd.isEmpty()) return showLoginError(getString(R.string.login_page_error_password))

        // 上一步已经要过验证码：得数没填就不用发请求了（服务端只会再回一次 CODEFALSE）
        val captchaAnswer = captchaEdit.text?.toString()?.trim().orEmpty()
        val captcha = pendingCaptcha?.let { previous ->
            if (captchaAnswer.isEmpty()) {
                showLoginError(getString(R.string.login_page_error_captcha))
                return
            }
            // 带上用户填的得数回传
            previous.copy(answer = captchaAnswer)
        }

        // 日志里不要打密码：以前这里把明文密码写进了 Logcat，
        // 任何有 adb 权限的人都能读到，装到真机上就是事故。
        Log.d(TAG, "登录：school=${school.name}(${school.baseUrl}) account=$account")
        if (captcha != null) Log.d(TAG, "本次登录附带验证码（token=${captcha.token}）")

        // 本地调试账号：不联网直接进主界面。保留原有调试通路（改 UI 时不用每次都连教务系统），
        // TODO(发布前): 正式发版前删掉这段，或者用 BuildConfig.DEBUG 包起来。
        if (account == DEBUG_ACCOUNT && pwd == DEBUG_PASSWORD) {
            onLoginSuccess(getString(R.string.login_page_login_success_local), account, localDebug = true)
            return
        }

        val api = SchoolRegistry.apiOf(school.id)
        if (api == null) return showLoginError(getString(R.string.login_page_error_school))

        // 收起键盘，否则登录结果那行小字会被输入法盖住
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.ime())
        setLoading(true)
        loginInfoText.setTextColor(ContextCompat.getColor(this, R.color.login_info_ok))
        loginInfoText.text = getString(R.string.login_page_logging_in)

        // login() 的回调在 OkHttp 工作线程上，必须切回主线程再动 View。
        // captcha 排在回调前面，所以这里仍然是自然的尾随 lambda 写法。
        api.login(account, pwd, captcha) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                setLoading(false)
                when (result) {
                    is LoginResult.Success ->
                        onLoginSuccess(result.message, account, localDebug = false)
                    is LoginResult.NeedCaptcha -> showCaptcha(result)
                    is LoginResult.Failure -> {
                        // 失败时把验证码收起来：下次点登录会重新取一张新图，
                        // 免得用户对着已经作废的旧图反复输入
                        hideCaptcha()
                        showLoginError(result.message)
                    }
                    is LoginResult.Pending -> showLoginError(result.message)
                }
            }
        }
    }

    /**
     * 服务端要求验证码：把图显示出来，让用户看得数。
     *
     * 这一步是**必须**的，不是"某些学校的小概率情况"：广州软件学院的统一身份认证
     * 对每一次账号密码登录都要验证码（`loginType` 接口的 `isVerifyCode=1`），
     * 而且它是一张算术题图片，只能人工识别。
     */
    private fun showCaptcha(result: LoginResult.NeedCaptcha) {
        pendingCaptcha = result.captcha
        val bytes = runCatching {
            Base64.decode(result.captcha.rawBase64, Base64.DEFAULT)
        }.getOrNull()
        val bitmap = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bitmap == null) {
            // 图片解不出来（服务端换了格式）：明确报错，别让用户对着空白框发呆
            hideCaptcha()
            showLoginError("验证码图片无法显示，请稍后重试")
            return
        }
        captchaImage.setImageBitmap(bitmap)
        captchaBlock.visibility = View.VISIBLE
        captchaTip.visibility = View.VISIBLE
        captchaEdit.setText("")
        captchaEdit.requestFocus()

        loginInfoText.setTextColor(ContextCompat.getColor(this, R.color.login_info_error))
        loginInfoText.text = result.message
    }

    private fun hideCaptcha() {
        pendingCaptcha = null
        captchaBlock.visibility = View.GONE
        captchaTip.visibility = View.GONE
        captchaEdit.setText("")
        captchaImage.setImageDrawable(null)
    }

    private fun showLoginError(message: String) {
        setLoading(false)
        loginInfoText.setTextColor(ContextCompat.getColor(this, R.color.login_info_error))
        loginInfoText.text = message
    }

    /**
     * 登录成功：**先把会话记下来（并加密落盘），再进主界面**。
     *
     * 顺序不能反：主界面 `onCreate` 里会立刻按「登录的学校 + 这个账号」去拉
     * 课表和姓名（`CourseSync`），会话要是还没写进 `SessionStore`，
     * 那一次同步就会因为"没有登录账号"直接失败。
     *
     * `SessionStore.onLogin` 会把 Cookie 会话加密后存盘 —— 这就是"下次打开不用再登"的来源。
     */
    private fun onLoginSuccess(message: String, account: String, localDebug: Boolean) {
        setLoading(false)
        hideCaptcha()
        SessionStore.onLogin(
            school = selectedSchool?.id.orEmpty(),
            userAccount = account,
            localDebug = localDebug,
        )
        loginInfoText.setTextColor(ContextCompat.getColor(this, R.color.login_info_ok))
        loginInfoText.text = message
        startActivity(Intent(this, ManyCourseMain::class.java))
        applyLoginTransition()
        finish()
    }

    /**
     * 登录中：禁用整张表单并给按钮降透明度。
     * 按钮的背景是自定义 drawable（`bg_login_button`）+ `backgroundTint=@null`，
     * MaterialButton 的禁用态着色对它不生效，所以透明度得自己给。
     */
    private fun setLoading(loading: Boolean) {
        loginBtn.isEnabled = !loading
        loginBtn.alpha = if (loading) 0.6f else 1f
        loginBtn.text = getString(
            if (loading) R.string.login_page_logging_in else R.string.login_page_login_button
        )
        schoolInput.isEnabled = !loading
        username.isEnabled = !loading
        password.isEnabled = !loading
        captchaEdit.isEnabled = !loading
    }

    /**
     * 登录页 → 主界面的页面切换动画：淡入淡出 + 轻微位移（与 Compose 侧动效规范一致）。
     * API 34+ 用 overrideActivityTransition（overridePendingTransition 已废弃）；
     * 系统关闭动画时不设置，走系统默认（无动画）。
     */
    private fun applyLoginTransition() {
        if (!Motion.enabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                R.anim.activity_enter,
                R.anim.activity_exit,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.activity_enter, R.anim.activity_exit)
        }
    }

    /**
     * 登录页的玻璃卡片跟随全局「高斯模糊 / 液态玻璃」设置：
     * 高斯模糊 = 更实的磨砂底；液态玻璃 = 更透的底 + 更强的描边。
     * 布局仍是 XML（仅此一页），所以这里做局部属性更新，不重建 Activity。
     *
     * 注意：**不要**在这里改 cardElevation / strokeWidth——
     * MaterialCardView 会按阴影与描边给自身背景加内边距，导致卡片背景比控件内缩一圈，
     * 视觉上就是"卡片里还有一张小一圈、没铺满的卡片"。
     * 层次感由背景色 + 1dp 描边（XML 固定）承担。
     */
    private fun applyGlassMode() {
        val card = findViewById<MaterialCardView>(R.id.glassCard) ?: return
        val tokens = glassTokens(UiSettings.glassMode.value, isNightMode())
        card.setCardBackgroundColor(tokens.panelTint.toArgb())
        card.strokeColor = tokens.border.toArgb()
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private companion object {
        const val TAG = "Login"

        /** 本地调试账号（见 doLogin 里的 TODO）：保留原行为，避免调试时每次都要连教务系统 */
        const val DEBUG_ACCOUNT = "admin"
        const val DEBUG_PASSWORD = "2481"

        /** 校名缩到这个字号就不再缩：再小影响可读性，此时宁可让省略号兜底 */
        const val SCHOOL_NAME_MIN_SP = 12f

        /** 字号步进：0.25sp 足够细，观感上没有台阶 */
        const val SCHOOL_NAME_STEP_SP = 0.25f
    }
}
