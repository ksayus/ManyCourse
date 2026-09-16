# 教务系统 API 接入与调用指南

> 面向要**改**这套接口、或要在页面里**调**它的人。
> 读完能做到四件事：**加一所学校** / **改某所学校的登录** / **加一个业务接口** / **把它接到 UI 上**。
>
> 文中标「实测」的数字与路径都来自真机抓包验证；没验证过的地方一律写成 `TODO(接入)`，不要当成已知事实。

---

## 0. 速查表

| 我想做什么 | 动哪里 |
|---|---|
| 加一所学校 | 复制 `gr_api/schools/NhjcxyApi.kt` → 改 4 个值 → 在 `SchoolRegistry.apis` 加一行 |
| 改登录地址 / 表单字段名 | 那所学校的 `loginUrl`、`usernameField`、`passwordField` |
| 密码不是明文提交 | 覆写 `encodePassword()`（RSA 见 `GzusApi` + `api/RsaCipher.kt`；国密 SM2 见 `NhjcxyApi` + `api/Sm2Cipher.kt`） |
| 登录前要先拿 token / 公钥 | 覆写 `prepare()` |
| "登录成功"判定不对 | 覆写 `judge()`（返回 JSON 的系统要自己判，别用关键字启发式） |
| 登录不止一步（先登 A 再登 B） | 别继承 `WebLoginSchoolApi`，直接实现 `SchoolApi`（见 `NhjcxyApi`） |
| 加「拉课表」这类业务接口 | 覆写 `fetchSchedule()`；接口上有默认实现，不写也能编译 |
| 把学生姓名同步到「我的」页 | 覆写 `fetchProfile()`，`CourseSync` 会自动写进 `ProfileRepository` |
| 在页面里调用 | `SchoolRegistry.apiOf(id)?.xxx(...) { 回调 }`，回调在**工作线程**，自己切主线程 |
| 接口还没接好 | `override val configured = false` → 用户看到「接口未接入」而不是"密码错误" |
| 换学校 / 退出登录 | 调 `http.clearCookies()`（见 §4.4） |
| 加学校地图 | 图片放 `res/drawable-nodpi/`，在 `data/SchoolMap.kt` 登记一行 → 见 §9 |
| 让学校支持「维持登录」 | 覆写 `exportSession` / `importSession` / `clearSession`（继承 `WebLoginSchoolApi` 的话已经自带）→ 见 §10 |

---

## 1. 结构总览

```
data/School.kt                 学校纯数据模型（id / 名称 / 根地址 / 系统类型）
data/SchoolCourse.kt           ★ 教务系统课程 + 学生信息（StudentProfile）的中立模型
data/SchoolMap.kt              学校 id → 校园地图图片（见 §9）
data/LoginSettings.kt          「上次选的学校」持久化（SharedPreferences）
data/SessionStore.kt           ★ 登录会话：内存态 + 加密落盘 + 退出登录（见 §10）
data/SessionBlob.kt            会话存档的内容与编解码（纯逻辑，可单测）
data/SessionVault.kt           会话存档的加密存储（Android Keystore + AES-GCM）
data/CourseSync.kt             ★ 登录后「拉姓名 + 拉课表 → 写仓库」的编排（见 §4.3）
      ↑ UI 只依赖 data/，不依赖任何网络实现

gr_api/SchoolApi.kt            ★ 契约：login / fetchSchedule / fetchProfile / 会话三件套
gr_api/LoginCaptcha.kt         验证码载体（token + 图片 + 用户填的答案），见 §3.2.4
gr_api/WebLoginSchoolApi.kt    ★ 「单次表单登录」型教务系统的通用骨架（新学校抄它）
gr_api/SchoolRegistry.kt       ★★ 学校清单 —— 唯一的「加学校」入口
gr_api/Html.kt                 取隐藏字段 / span 文本 / 表格的极简 HTML 工具
gr_api/schools/GzusApi.kt      广州软件学院（正方 V9，**CAS 统一身份认证登录**，已实测）
gr_api/schools/GzusScheduleParser.kt  广软课表 JSON 解析（纯逻辑 + JSON 适配两层，见 §6）
gr_api/schools/NhjcxyApi.kt    南京航空航天大学金城学院（MVC + EaWeb 两段式登录，已实测）
gr_api/schools/NhjcPageParser.kt  金城学院页面解析（单独拆出来是为了能单测，见 §5.4）

api/HttpMethod.kt              唯一的网络出口：OkHttp + 内存 Cookie 会话 + 异步表单 POST
api/CookieCodec.kt             Cookie 会话的导出/导入格式（纯逻辑，可单测）
api/RsaCipher.kt               正方用的「裸 RSA」（大端、无填充、hex）
api/CasRsaCipher.kt            ★ 金智 CAS 用的「裸 RSA」（**小端**、末尾补零、hex）—— 两者别混
api/Sm2Cipher.kt               ★ 国密 SM2 加密 + SM3 摘要（自己实现，不引 BouncyCastle）
```

运行时数据流（登录 → 同步）：

```
SchoolRegistry.schools ─▶ SchoolDropdownAdapter ─▶ 登录页下拉（EditSchool）
                                │ 用户选中
                                ▼
                    selectedSchool + LoginSettings（持久化学校 id）
                                │ 点「登录」
                                ▼
        SchoolRegistry.apiOf(id).login(账号, 密码) { LoginResult }
                                │ 回调在 OkHttp 工作线程
                                ▼
        MainActivity → SessionStore.onLogin(学校, 账号)   ┐
                          │                            │ 顺手把 Cookie 会话
                          │                            │ 加密写进 SessionVault
                          ▼                            ┘
                     进 ManyCourseMain
                                │
                                ▼
        ManyCourseMain.onCreate → CourseSync.sync(学校id, 账号)
                                │  按学校分发，回调同样是工作线程，内部切主线程
              ┌─────────────────┴─────────────────┐
              ▼                                   ▼
   fetchProfile() → ProfileRepository      fetchSchedule() → CourseRepository
   （★「我的」页的昵称 = 学校系统里的姓名）   （★课表页显示的就是这些课）
```

冷启动（**维持登录**，见 §10）：

```
ManyCourseApp.onCreate → SessionStore.attach(context)
        │  SessionVault 解密 → SessionBlob 解开 → api.importSession(Cookie)
        ▼
MainActivity.onCreate  发现「已登录」→ 直接 startActivity(ManyCourseMain) 并 finish()
        │  （登录页压根不会被绘制，所以看不到闪屏）
        ▼
ManyCourseMain → CourseSync.sync(...) 正常拉数据
```

**两条铁律：**

1. **学校 `id` 是持久化主键，定了就不许改**（改了 = 清空老用户"上次选的学校"）。全小写英文短名。
2. **加业务接口不要新建 `HttpMethod`**：登录成功后会话（Cookie）在 `SchoolApi` 实例的 `http` 里，新建实例等于换个空会话，接口必然跳登录页。

---

## 2. 契约速查（签名）

### 2.0 加一所学校最小清单

`SchoolApi` 现在有三个方法，其中两个有默认实现（回失败），所以**新学校只接登录也能跑**：

| 方法 | 必须覆写？ | 作用 |
|---|---|---|
| `school` | 必须 | 学校纯数据 |
| `login(account, pwd, cb)` | 必须 | 登录；`configured = false` 时给明确提示 |
| `fetchSchedule(account, cb)` | 可选 | 拉课表 → `List<SchoolCourse>` |
| `fetchProfile(account, cb)` | 可选 | 拉学生信息 → `StudentProfile`（**姓名在这里**） |

未覆写的两个方法会回一个 `UnsupportedOperationException`，课表页会把它显示成
「「XX大学」还没接入课表接口」——不会崩，也不会静默空着。

### 2.1 `School` —— 学校的纯数据（`data/School.kt`）

```kotlin
data class School(
    val id: String,        // 稳定标识，持久化主键，全小写英文，如 "gzus"
    val name: String,      // 下拉里显示的中文全称
    val baseUrl: String,   // 根地址，必须 https、不带结尾斜杠
    val system: String = "", // 教务系统类型备注，只用于排查
) {
    val host: String       // 下拉副标题用，如 "jwxt.gzus.edu.cn"
}
```

### 2.2 `SchoolApi` —— 一所学校要提供什么（`gr_api/SchoolApi.kt`）

```kotlin
interface SchoolApi {
    val school: School

    /** 登录参数是否已填好；预写的空壳返回 false */
    val configured: Boolean get() = true

    /** 异步登录；回调在工作线程，保证只回调一次。captcha 缺省 = 首次登录 */
    fun login(
        account: String,
        password: String,
        captcha: LoginCaptcha? = null,
        callback: (LoginResult) -> Unit,
    )

    /** 拉课表。**默认实现直接回失败**，新学校不写也能编译 */
    fun fetchSchedule(account: String, callback: (Result<List<SchoolCourse>>) -> Unit) {
        callback(Result.failure(UnsupportedOperationException("「${school.name}」还没接入课表接口")))
    }

    /** 拉学生信息（姓名在这里）。默认实现同上 */
    fun fetchProfile(account: String, callback: (Result<StudentProfile>) -> Unit) { /* 同上 */ }

    // ── 会话持久化（「维持登录」，见 §10）────────────────────────────────
    /** 导出 Cookie 会话；返回 null = 这所学校不支持持久化（上层就不存盘）*/
    fun exportSession(): String? = null
    /** 灌回会话；返回 false = 这次的存档没恢复出东西（上层回落到登录页）*/
    fun importSession(data: String): Boolean = false
    /** 退出登录 / 会话过期时清掉服务端登录态与缓存 */
    fun clearSession() {}
}
```

两个业务接口**必须先登录成功**再调：会话（Cookie）就在实现类自己的 `http` 里，
而 `SchoolRegistry.apiOf(id)` 返回的是**单例**，所以"登录用的实例"和"拉数据用的实例"天然是同一个。

> `fetchSchedule` 为什么要传 `account`：正方这类系统要在 URL 上带 `su=学号`。
> 登录态里其实已经有身份了，但把它显式传进来，实现类就不用自己去猜。

**会话三件套的默认实现是"不支持持久化"**：继承 `WebLoginSchoolApi` 的学校已经自带
（一个 `HttpMethod` 就是整个登录态），直接实现 `SchoolApi` 的学校照 `NhjcxyApi` 抄三行即可。
广软还在这三件套里多塞了一个 CAS 的 TGT（`GzusSessionCodec`），做法见 §10.1。

### 2.2b `WeekScheduleApi` —— 「按教学周查课表」（可选，日历页用）

```kotlin
// gr_api/WeekScheduleApi.kt —— 独立于 SchoolApi，实现了才用得上
interface WeekScheduleApi {
    /** 教学周列表：第几周从哪天到哪天（服务端给的，不要自己推）*/
    fun fetchWeeks(callback: (Result<List<SchoolWeek>>) -> Unit)
    /** 某一周真的会上的课 */
    fun fetchWeekCourses(week: Int, callback: (Result<List<SchoolCourse>>) -> Unit)
}
```

和 `SchoolApi` 分开是刻意的：**它不是每所学校都有**，也不该让没接的学校被迫写空实现。
消费方是 `data/WeekScheduleStore.kt`（日历页的真实日期），判断方式：

```kotlin
SchoolRegistry.apiOf(schoolId) as? WeekScheduleApi   // null = 这所学校不支持，日历页走本地课表兜底
```

目前只有广软实现了它（`N2154` 周次课表，见 §6）。加新学校时如果对方也有
"周次课表/教学周"这类页面，照 `GzusApi.fetchWeeks` 抄即可。

`LoginResult` 是**多态**而不是 `Boolean`，不要退化成两态：

| 取值 | 含义 | 登录页表现 |
|---|---|---|
| `LoginResult.Success(message)` | 账号密码通过 | 绿字 + 进主界面 |
| `LoginResult.Failure(message)` | 明确失败（密码错 / 网络不通 / 服务端异常） | 红字显示 message |
| `LoginResult.NeedCaptcha(message, captcha)` | 服务端要人工识别的验证码 | 显示图片 + 输入框，填完带 `LoginCaptcha` 重调 |
| `LoginResult.Pending(message)` | 这所学校的接口还没接入 | 红字提示去补哪个文件 |

> `Pending` 存在的意义：预写的学校点登录时，用户看到的是"接口未接入"，
> 而不是被误导成"我密码打错了"。

### 2.3 `WebLoginSchoolApi` —— 表单登录骨架（`gr_api/WebLoginSchoolApi.kt`）

子类只要填 3 个抽象值，可选覆写 3 个钩子：

| 成员 | 必须/可选 | 作用 |
|---|---|---|
| `school` | 必须 | 这所学校（含根地址） |
| `loginUrl` | 必须 | 登录表单提交的**完整 URL** |
| `usernameField` | 必须 | 表单里用户名的 key，如 `yhm` |
| `passwordField` | 必须 | 表单里密码的 key，如 `mm` |
| `http` | 继承 | 该校专属的 HTTP 会话（独立 Cookie） |
| `encodePassword(pwd)` | 可选覆写 | 密码字段填什么值；默认原样 |
| `prepare(callback)` | 可选覆写 | 登录前先 GET 登录页/公钥，返回**要合并进表单的额外字段** |
| `judge(response)` | 可选覆写 | 判定成功/失败；默认关键字启发式 |

默认执行顺序：

```
prepare()  ──▶  表单 POST { usernameField=账号, passwordField=encodePassword(密码), ...额外字段 }  ──▶  judge()
```

### 2.4 `HttpMethod` —— 唯一的网络出口（`api/HttpMethod.kt`）

**异步（推荐，登录/业务都用这套）** —— 回调在 OkHttp 工作线程：

```kotlin
fun getAsync(url: String, headers: Map<String,String> = emptyMap(),
             callback: (Result<HttpResponse>) -> Unit)

fun postFormAsync(url: String, form: Map<String,String>, headers: Map<String,String> = emptyMap(),
                  callback: (Result<HttpResponse>) -> Unit)
```

**同步（只能在子线程调，主线程会抛 NetworkOnMainThreadException）**：

```kotlin
fun getResponse(url: String, headers: Map<String,String> = emptyMap()): HttpResponse
fun postForm(url: String, form: Map<String,String>, headers: Map<String,String> = emptyMap()): HttpResponse
```

**旧接口（保留未删，返回裸 String）**：`get(url)`、`post(url, json: JSONObject)`。

**会话与工具**：

```kotlin
fun cookieValue(url: String, name: String): String?  // 取某个 Cookie 值（回填 csrftoken 时用）
fun cookiesFor(url: String): List<Cookie>
fun clearCookies()                                    // 换学校 / 退出登录
```

`HttpResponse` 是响应快照（body 只能读一次，所以先落成不可变对象再交给业务）：

```kotlin
data class HttpResponse(
    val code: Int,
    val body: String,
    val location: String?,   // 重定向目标：登录成功的典型信号
    val finalUrl: String,    // 跟随重定向后的最终地址
) { val isRedirect: Boolean }
```

### 2.5 `RsaCipher`（`api/RsaCipher.kt`）

```kotlin
/** 裸 RSA（无填充）加密，返回小写十六进制 */
RsaCipher.encryptToHex(plain: String, modulusBase64: String, exponentBase64: String): String
```

用的是 `java.util.Base64` + `BigInteger.modPow`，纯 JVM 逻辑，单测覆盖在 `RsaCipherTest`。

### 2.6 `Sm2Cipher` / `Sm3Digest`（`api/Sm2Cipher.kt`）

```kotlin
/** 国密 SM2 公钥加密，返回 `04 ‖ x1 ‖ y1 ‖ C2 ‖ C3` 的小写十六进制（C1C2C3 顺序） */
Sm2Cipher.encryptToHex(plain: String, publicKeyHex: String): String

/** SM3 摘要（SM2 的 C3 与 KDF 都基于它），返回 32 字节 */
Sm3Digest.hash(data: ByteArray): ByteArray
```

国密算法不在 JDK / Android 标准库里，项目又不想为一个登录引 BouncyCastle，
所以是自己按 GB/T 32918 / GB/T 32905 实现的。**两条绝对不能改的细节**：

1. **密文顺序是 `C1C2C3`，不是国标默认的 `C1C3C2`**。这是因为要跟对方页面里
   `/Mvc/Scripts/js/Sm2/lib/sm2.js` 的 `sm2Encrypt(data, key, 0)` 对齐。
   改错了服务端解不开，表现只是「用户名或密码错误」。
2. **公钥超过 128 个十六进制字符时只取最后 128 个**（剥掉 `04` 前缀），和那段 JS 一致。

单测（`Sm3DigestTest` / `Sm2CipherTest`）分三层：
官方向量（`SM3("abc")` 等国标值）、与参考实现的逐字节比对、
以及**在测试内部用私钥 d=1 的测试公钥独立解密一遍**。

---

## 3. 改动：加一所学校 / 改一所学校

### 3.0 先判断属于哪一种

| 学校长什么样 | 抄哪个 |
|---|---|
| 一张 `<form>` POST 一次就完事（正方、强智、URP…） | 继承 `WebLoginSchoolApi`，见 §3.1 |
| **登录要多步**（先登录 A 系统拿会话，再登录 B 系统）/ 返回 JSON | 直接实现 `SchoolApi`，照 `NhjcxyApi` 抄，见 §3.5 |

### 3.1 加一所新学校（3 步）

**第 1 步：抓包拿到登录请求**（方法见 §5.1），得到三个值：Request URL、用户名 key、密码 key。

**第 2 步：复制模板。** 把 `gr_api/schools/NhjcxyApi.kt` 复制成 `XxxApi.kt`：

```kotlin
package com.tof.manycourse.gr_api.schools

import com.tof.manycourse.data.School
import com.tof.manycourse.gr_api.WebLoginSchoolApi

class XxxApi : WebLoginSchoolApi() {

    override val school = School(
        id = "xxx",                          // ★ 全小写英文短名，定了别再改
        name = "XX 大学",
        baseUrl = "https://jwxt.xx.edu.cn",  // 不带结尾斜杠
        system = "正方教务系统 V-9.0",         // 仅备注
    )

    override val loginUrl = "${school.baseUrl}/jwglxt/xtgl/login_slogin.html"
    override val usernameField = "yhm"
    override val passwordField = "mm"
}
```

**第 3 步：注册。** 在 `gr_api/SchoolRegistry.kt` 的 `apis` 里加一行（顺序 = 登录页下拉顺序）：

```kotlin
private val apis: List<SchoolApi> = listOf(
    GzusApi(),
    NhjcxyApi(),
    XxxApi(),        // ← 加这里
)
```

**第 4 步（可选）：接课表 / 学生信息。** 在那所学校里覆写两个方法即可，
默认实现会回一个"还没接入"，不覆写也不会崩：

```kotlin
override fun fetchSchedule(account: String, callback: (Result<List<SchoolCourse>>) -> Unit) {
    http.getAsync("${school.baseUrl}/xxx/xskb.do?su=$account") { result ->
        callback(result.mapCatching { parseSchedule(it.body) })
    }
}

override fun fetchProfile(account: String, callback: (Result<StudentProfile>) -> Unit) {
    http.getAsync("${school.baseUrl}/xxx/xsgrxx.html") { result ->
        callback(result.mapCatching { parseProfile(it.body) })
    }
}
```

> **把 HTML 解析拆到一个 internal object 里**（照 `NhjcPageParser` 抄）。
> 解析器失效时不会报错，只会"课表空了 / 姓名没同步上"，
> 拆出来才能用单测把真实页面结构钉住（见 §5.4）。

**验证**：`gradlew :app:testDebugUnitTest`（`SchoolRegistryTest` 会自动校验 id 唯一 / 必须 https / 不能带结尾斜杠 / 清单与实现一一对应），然后真机登录一次看 Logcat（§5.2）。

> 到此为止 UI 一行都不用改：下拉、持久化、登录分发、课表与姓名的同步都是自动的。

### 3.2 改一所学校的登录

#### 3.2.1 表单字段名不对
改 `usernameField` / `passwordField` 即可。字段名以抓包里的 **Form Data key** 为准。

#### 3.2.2 密码不是明文（RSA / MD5 / 其他）
覆写 `encodePassword`。RSA 的完整例子见 `GzusApi`：

```kotlin
override fun encodePassword(password: String): String {
    val modulus = publicKeyModulus ?: return password
    val exponent = publicKeyExponent ?: return password
    return RsaCipher.encryptToHex(password, modulus, exponent)
}
```

> **易错点**：正方用的是**裸 RSA（无填充）**，即 `c = m^e mod n`。
> 用 `RSA/ECB/PKCS1Padding` 会填随机数，服务端解出来带垃圾字节 ——
> 表现和"密码错了"一模一样，极难排查。`RsaCipherTest` 专门守着这条。

MD5 之类直接：

```kotlin
override fun encodePassword(password: String): String =
    java.security.MessageDigest.getInstance("MD5")
        .digest(password.toByteArray()).joinToString("") { "%02x".format(it) }
```

#### 3.2.3 登录前要先拿 token（CSRF）
覆写 `prepare`，把要回填的字段返回去。`GzusApi` 的真实写法（实测有效）：

```kotlin
override fun prepare(callback: (Result<Map<String, String>>) -> Unit) {
    // 1) GET 登录页：为了让服务端下发 CSRFTOKEN Cookie
    http.getAsync(loginUrl) { page ->
        page.fold(
            onSuccess = { loadPublicKey(callback) },   // 2) 再取公钥
            onFailure = { callback(Result.failure(it)) },
        )
    }
}
// 返回的 map 会被合并进登录表单：
linkedMapOf<String, String>().apply {
    http.cookieValue(loginUrl, "CSRFTOKEN")?.let { put("csrftoken", it) }
    put("language", "zh_CN")
}
```

> `callback` **必须且只能调用一次**：成功/失败两条分支都要覆盖，否则登录页会一直转圈。
> 另外 `prepare` 里缓存的公钥是**每次登录都会重新取**的 —— 学校换密钥也不用管。

#### 3.2.4 有验证码
骨架（`WebLoginSchoolApi`）没有验证码位，**但框架已经支持**：`LoginResult`
多了一态 `NeedCaptcha`，`SchoolApi.login()` 也多了一个默认参数 `captcha`：

```kotlin
login(account: String, password: String, captcha: LoginCaptcha? = null, callback: (LoginResult) -> Unit)
```

`captcha` 排在 `callback` **前面**且带默认值，是为了两种写法都顺手：
不需要验证码的学校照旧 `api.login(账号, 密码) { … }`（尾随 lambda）；
需要的学校写 `api.login(账号, 密码, captcha) { … }`。
（把 `captcha` 放最后会破坏尾随 lambda —— Kotlin 会把大括号当成 captcha。）

实现方式（照 `GzusApi`）：服务端要验证码时返回
`LoginResult.NeedCaptcha(说明, LoginCaptcha(token, 图片, answer=""))`，
登录页把图片显示出来、把用户填的得数塞回 `answer`，再调一次 `login`。

登录页那块的控件已经加好了，直接复用即可：
`res/layout/activity_login.xml` 的 `@id/captchaBlock` / `@id/captchaImage` / `@id/EditCaptcha`
（默认 `visibility="gone"`），`MainActivity.showCaptcha()` 负责显示。

> 注意实现细节：**取验证码千万别在回调里阻塞等待另一个请求** ——
> 回调本身就跑在 OkHttp 的 dispatcher 线程上，阻塞它再去发同 host 的请求，
> 最坏会和"同一 host 最多 5 个并发"的限制撞成死锁。`GzusApi.fetchCaptcha` 是全异步的。

#### 3.2.5 判定规则不对
默认 `judge` 是关键字启发式（`LoginJudge.byKeywords`）：命中"用户名或密码错误"等文案即失败，
**其余 200/302 一律按成功处理**。这是"够用就好"的策略，误判就覆写：

```kotlin
override fun judge(response: HttpResponse): LoginResult {
    val result = LoginJudge.byKeywords(response)
    // 兜底：正方失败时是「200 + 重新渲染登录页」，用"页面里还带着登录表单"再判一次
    if (result is LoginResult.Success && response.body.contains("login_slogin")) {
        return LoginResult.Failure("用户名或密码错误")
    }
    return result
}
```

### 3.3 下架 / 暂不开放某所学校

- **暂不开放（参数没填全）**：`override val configured = false`。用户点登录会看到明确提示。
- **彻底下架**：从 `SchoolRegistry.apis` 里删掉那一行。老用户持久化里的旧 id 会被
  `SchoolRegistry.find()` 解析成 `null`，登录页自动回落到"未选择"（这条有测试守着）。
  注意：**别删了还留着 id 复用**，会串到别的学校。

### 3.4 改显示名 / 根地址

`name`、`baseUrl`、`system` 随便改。**`id` 不能改**（见 §1 铁律 1）。

### 3.5 多步登录 / JSON 判定的学校：照 `NhjcxyApi` 抄

金城学院是个典型例子：**一个域名下叠了两套系统，登录要过两道**。
这种就不要继承 `WebLoginSchoolApi`（它的流程被固定成"一次表单 POST + 关键字判定"了），
直接实现 `SchoolApi`，照 `NhjcxyApi` 的骨架走：

```
① GET  登录页 A         → 从页面里抠出公钥 / token
② POST 登录表单 A       → 判定（返回 JSON 就按 JSON 判，别用关键字启发式）
③ GET  登录页 B         → 抠出 WebForms 的 __VIEWSTATE
④ POST 登录表单 B       → 判定（302 且不是跳回登录页 = 成功）
⑤ 之后才能访问业务页
```

三个要点：

1. **一个 `HttpMethod` 贯穿全程**。两段登录的 Cookie 挂在同一个 host 上，
   共用一个实例才能把第一段的会话带进第二段。
2. **两段都成功才算成功**。只判第一段的话，用户会"登录成功但什么数据都拉不到"——
   那种体验比直接报错还糟。
3. **判定要分开写**（`judgeMvcLogin` / `judgeLegacyLogin`），
   把"为什么失败"翻译成能直接展示的中文。返回 JSON 的系统尤其不能套关键字启发式：
   对着一坨 JSON 找"用户名或密码错误"只会永远判成功。

---

## 4. 调用：怎么用

### 4.1 登录现在是怎么被调用的

登录页（`MainActivity`）里的真实调用：

```kotlin
private fun doLogin() {
    val school = selectedSchool ?: return showLoginError(getString(R.string.login_page_error_school))
    // ... 账号密码校验、本地调试账号 ...

    val api = SchoolRegistry.apiOf(school.id) ?: return showLoginError(...)
    setLoading(true)

    // login() 的回调在 OkHttp 工作线程上，必须切回主线程再动 View
    api.login(account, pwd) { result ->
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread   // ★ 别在销毁后动 View
            setLoading(false)
                is LoginResult.Success ->
                    // ★ 先把「当前账号 + 学校」记进会话，再进主界面。
                    //   顺序不能反：主界面 onCreate 会立刻用它们去同步课表（见 §4.3）
                    onLoginSuccess(result.message, account, localDebug = false)
                is LoginResult.Failure -> showLoginError(result.message)
                is LoginResult.Pending -> showLoginError(result.message)
            }
        }
    }
}
```

三个要点：**按 id 取实现 → 异步调 → 回主线程三态分支**。

### 4.2 加一个业务接口（以"拉课表"为例）

业务接口写在**具体学校类**里，复用它的 `http` —— 会话是现成的，不要再 new 一个 `HttpMethod`。
`SchoolApi` 上已经留好了 `fetchSchedule` / `fetchProfile` 两个**带默认实现**的方法，
所以正常只需要覆写，不用改接口：

```kotlin
class GzusApi : WebLoginSchoolApi() {

    private val root = "${school.baseUrl}/jwglxt"

    override fun fetchSchedule(account: String, callback: (Result<List<SchoolCourse>>) -> Unit) {
        http.getAsync("$root/xskb/xskb_list.do?gnmkdm=N253508&su=$account") { result ->
            // mapCatching：把解析异常也变成 Result.failure，调用方只看一个分支
            callback(result.mapCatching { parseScheduleTable(it.body) })
        }
    }
}
```

**两种放法的取舍**（现在默认是第二种）：

| 场景 | 做法 |
|---|---|
| 只有某所学校有，且 UI 不需要统一调用 | 放在具体类里，调用方 `as? XxxApi` |
| 跨学校通用、UI 要统一调用 | 提到 `SchoolApi` 接口 + 给**默认实现**（现在的做法）|

> **解析 HTML 的提醒**：正方等系统的课表接口返回的是 HTML 表格，不是 JSON。
> 项目没有引入 HTML 解析库，用 `gr_api/Html.kt` 里那几个够用的小工具
> （取隐藏字段 / 取 id 元素文本 / 取表格行 / 按 `<br>` 拆行）。
> 真到了要解析复杂 DOM 的程度再加 Jsoup 不迟；但**解析器一定要单独拆出来配单测**（§5.4）。
> 另外 `HttpResponse.body` 是完整字符串，大页面注意别在日志里整份打出来。

### 4.3 把网络数据喂给 UI（`CourseSync`）

这一层已经写好了，**接课表时不用自己动 UI**：登录成功后 `ManyCourseMain.onCreate`
会调一次 `CourseSync.sync(学校id, 账号)`，它负责：

```kotlin
// data/CourseSync.kt（简化）
state.value = State.Loading

api.fetchProfile(account) { profile ->
    main {                                     // ★ 回调在工作线程，必须切主线程
        profile.onSuccess { student ->
            ProfileRepository.nickname.value = student.name   // ★ 姓名 = 学校系统里显示的那个
            ProfileRepository.major.value = student.subtitle  //   「专业 · 学院」
        }
        api.fetchSchedule(account) { schedule ->
            main {
                schedule.fold(
                    onSuccess = { CourseRepository.replaceAll(it); state.value = State.Ready(...) },
                    onFailure = { state.value = State.Failed(it.readableMessage()) },
                )
            }
        }
    }
}
```

几个已经处理掉的坑，改的时候别踩回去：

| 坑 | 处理 |
|---|---|
| 回调在工作线程改 Compose 状态会崩快照 | 所有仓库写入都经 `CourseSync.main { }` 切主线程 |
| 转屏 / Activity 重建会重复拉一遍 | 用「学校id+账号」当 key 记住上次同步，`force = true` 才重拉 |
| 换账号后还显示上一个人的课和名字 | 登录成功时 `CourseSync.reset()`；退出登录用 `clearOnLogout()` |
| 失败后页面空着，用户以为 App 坏了 | 课表页顶部有状态条：同步中 / 失败原因 + **重试**按钮 |
| 手动加的课与教务课表混在一起 | `replaceAll` 是**整体替换**（教务系统才是权威数据源），不合并 |

`CourseRepository.courses` 是 `SnapshotStateList`，改完所有页面自动刷新，**不需要通知谁**。
`Course.add()` 会分配**本地自增 id**；要保留服务端 id 的话，按
`docs/UI使用文档.md` §2 的建议扩展 `Course` 加 `remoteId`。

已知限制：课表仍按"每周循环"渲染，**不区分起止周/单双周**。从教务系统拉回来的课
会把原始周次存进 `Course.weeks` 并在课程卡片上显示（如 `3-5,8-20周`），
让人至少知道"这课不是每周都上"（详见 `UI使用文档.md` §4）。

### 4.4 线程与生命周期（最容易出错的地方）

| 规则 | 原因 |
|---|---|
| `login` / `getAsync` / `postFormAsync` 的回调在**工作线程** | OkHttp `enqueue` 的回调跑在 dispatcher 线程 |
| 动 View、动 Compose 状态**必须切主线程** | 否则 `CalledFromWrongThreadException` / Compose 快照崩溃 |
| `getResponse` / `postForm` / `get` / `post` 是**同步**的 | 主线程调用直接 `NetworkOnMainThreadException`；要就放子线程 |
| Activity 销毁后回调可能仍然到达 | 先判 `isFinishing \|\| isDestroyed` 再动 View（`MainActivity` 就是这么做的） |
| 一个 `HttpMethod` = 一个独立 Cookie 会话 | 不同学校天然隔离；**换学校/退出登录要 `clearCookies()`** |
| `SchoolRegistry` 持有的是**单例** | 会话在 App 生命周期内一直有效，登录一次后业务接口直接用 |

切主线程的三种写法，按场景选：

```kotlin
runOnUiThread { ... }                       // Activity 里最省事
view.post { ... }                           // 只有 View 引用时
Handler(Looper.getMainLooper()).post { }    // 非 Activity 上下文（仓库/工具类）
```

### 4.5 错误文案

`Throwable.readableMessage()`（`gr_api/SchoolApi.kt`，`internal`）把常见异常翻成人话，
`WebLoginSchoolApi` 已经用它拼好了「无法连接教务系统 / 网络异常」两类文案。
自定义接口直接复用：

```kotlin
onFailure = { showError(it.readableMessage()) }
```

想加新的异常类型 → 在 `readableMessage()` 的 `when` 里加一条分支即可。

---

## 5. 调试

### 5.1 抓包找接口（三步）

1. 电脑浏览器打开教务系统，等它跳转完停在登录页；
2. `F12` → **Network** → 勾上 **Preserve log**（一定要勾：登录成功会跳转，不勾记录会被清掉）；
3. 输账号密码登录一次，找那条 **POST** 且 `Content-Type: application/x-www-form-urlencoded` 的记录：
   - **Request URL** → `loginUrl`
   - **Form Data** 里的用户名 key → `usernameField`
   - **Form Data** 里的密码 key → `passwordField`
   - 表单里还有 `csrftoken` / `lt` / `execution` 这类**每次登录都变**的字段 → 覆写 `prepare()`
   - 密码值是一长串十六进制 = RSA；32 位定长串 = MD5；和明文一样 = 直接提交

业务接口同理：登录后进目标页面，找那条返回数据的请求（课表通常是 HTML，成绩/课程列表有的返回 JSON）。

> **登录要过两道系统时**（金城学院就是这样）：两道都得抓。
> 第一道是新的 MVC 前端（返回 JSON），第二道是老 WebForms（返回 302）。
> 判据分别是 `IsSuccess` 字段和"302 且不是跳回 `Login.aspx`"，
> 千万别只看第一道 —— 只看第一道的结果是"登录成功但课表永远拉不到"。

#### 用 `tools/` 里的脚本离线啃抓包（省得反复开浏览器）

导出的 HAR 很大（3~4 MB），肉眼翻不动。仓库里带了几个 Node 小工具（**不参与打包**，
凭据一律走环境变量、不落盘到源码里）：

| 脚本 | 干什么 |
|---|---|
| `tools/har_dump.mjs <har> [--list] [--grep X] [--body N]` | 把 HAR 压成一页可读清单：方法 / 状态 / 大小 / 路径 / 关键请求头 / 响应体片段 |
| `tools/har_extract.mjs <har> <序号,序号> <outDir>` | 把指定条目的请求与响应原文导成文件，便于精读 |
| `tools/png_to_ascii.mjs <png> <阈值> x num\|levels` | 把 PNG 渲染成字符点阵 —— **验证码是算术题图**，没有看图能力时靠它读数 |
| `tools/cas_probe.mjs captcha\|validate\|login\|tgt-reuse\|tgt-follow\|kb` | 广软 CAS 的真实联调探针（要 `GZUS_USER` / `GZUS_PASS` 两个环境变量） |
| `tools/verify_app_flow.mjs` | **端到端复现 App 的流程**：只用 TGT（零 Cookie）→ `renewSession` → `fetchWeeks` → `fetchWeekCourses` |
| `tools/tgt_watch.mjs [间隔分钟] [小时]` | 每隔一阵用纯 TGT 换一次票，把"到底能活多久"记进 `build/cas/tgt_lifetime.log`（实测约 1 小时） |

两个踩过的坑，写脚本复现链路时必须知道：

- **Node 的 `fetch` 在 `redirect:'follow'` 下既拿不到中间跳的 `Set-Cookie`、也不会带下去**，
  而正方会话（`JSESSIONID`）恰恰是在第 2 跳种下的 —— 必须自己 `redirect:'manual'` 手动跟，
  才能和 OkHttp 的 `CookieJar` 行为对齐（`tools/verify_app_flow.mjs` 的 `reqFollow` 就是干这个的）；
- HAR 里 `cookies` / `Set-Cookie` 经常是**空的**（导出时被清理），别指望从 HAR 里捞登录态；
  要真凭据就得上探针脚本。

### 5.2 Logcat

```bash
adb logcat -s OkHttp:D    # HttpLoggingInterceptor 打的完整请求/响应（级别 BODY）
adb logcat -s Login:D     # 登录页自己的日志（登录时的学校/账号、校名自适应）
```

`HttpLoggingInterceptor` 是 **BODY 级别**，请求头、表单内容、响应体全都有 ——
排查字段名/加密问题基本靠它。**注意别把密码打进去**：登录页的日志刻意只打学校和账号。

### 5.3 失败对照表

| 现象 | 大概率原因 | 处理 |
|---|---|---|
| 「用户名或密码错误」，但密码没错 | 密码没加密 / 加密方式不对（用了 PKCS1Padding） | 覆写 `encodePassword`，参考 `RsaCipher` |
| 同上，且是国密系统 | SM2 密文顺序写成 `C1C3C2` 了 | 必须 `C1C2C3`，见 §2.6 |
| 「无法连接教务系统」 | 域名写错、学校仅限内网、`INTERNET` 权限缺失 | 核对 `baseUrl` 与 `AndroidManifest.xml` |
| 登录提示成功，但后续接口跳登录页 | 没复用会话（新建了 `HttpMethod`）| 用同一个 `SchoolApi` 实例的 `http` |
| 抓包看是 302，App 说失败 | `judge` 判定与真实响应不匹配 | 覆写 `judge`，用 Logcat 看真实响应体 |
| 返回 200 但内容是登录页 | 缺 `CSRFTOKEN` / 会话过期 | 覆写 `prepare` 先 GET 登录页 |
| 提示「xxx 的登录接口还没接入」 | `configured = false` | 填好参数后改成 `true` |
| 主线程崩 `NetworkOnMainThreadException` | 调了同步方法 | 换异步接口，或自己放子线程 |
| WebForms 页 POST 回来 **HTTP 500「验证视图状态 MAC 失败」** | 回传 ViewState 时**漏了 `__VIEWSTATEENCRYPTED`** | 把它（空串）一起带上，见 §7.1 |
| 课表接口 200 但表是空的（不报错） | 查询条件没给对：金城学院要按**班号**查 | 先拉学籍页拿 `LabBh`，再按班级 POST |
| 姓名/课表一直没同步上，也不报错 | 页面改版，`LabXxx` / `TabSchedule` 没了 | 解析器会抛异常并显示出来；按 §5.4 补一条 fixture 测试 |
| 冷启动提示「登录已过期」，但账号密码没错 | 正方 `JSESSIONID` 是**会话级**的，服务端早超时了 | 广软会先拿 TGT 静默续期（§10.1）；续期也失败才是真要重新登录 |
| 换票（`POST /v1/tickets/<TGT>`）解析不出票据 | 成功时返回的是**纯文本 `ST-…`**，不是 JSON | 两种都认；只有"首次账号密码登录"才是 JSON 信封 |
| 周次课表第 1 周返回 0 门课 | 正常 —— 那一周还没开学 | 别当失败处理，也别把"0 门课"和"没拉到"混成一个值 |
| 从 HAR 里找不到 `Cookie` / `Set-Cookie` | 导出时被清理了（实测三个 HAR 全是空的） | 别指望从 HAR 捞登录态，用 `tools/cas_probe.mjs` 真跑一遍 |
| 脚本里跟 302 之后会话是空的 | Node `fetch` 的 `redirect:'follow'` **不带 Cookie 跨跳转** | 自己 `redirect:'manual'` 手动跟，见 §5.1 |

### 5.4 页面解析一定要配"结构回归"测试

`Html.kt` 那套正则解析失效时**不会报错**，只会表现为"课表空了""姓名没同步上"。
所以约定：**每接一所学校，就把抓下来的页面结构（换成假姓名/假学号）
固化进 `app/src/test/.../<学校>PageParserTest.kt`**。

照 `NhjcPageParserTest` 抄，至少覆盖这几件事：

| 要测的 | 为什么 |
|---|---|
| 星期几是从**表头**读的，不是按列号猜的 | 系统只渲染"有课的星期"，列号会整体左移 |
| 一个格子里两门课能拆开 | 合班/分周次的课会挤在一起 |
| 缺教室/缺教师的行不能错位 | 实测「体育」就是这样，班号很容易被当成教师名 |
| `&nbsp;` / `<br>` / `<br/>` 都认 | 老系统的写法不统一 |
| 表格没了要**抛异常** | 静默返回空表会被误读成"这学期没课" |

---

## 6. 现状

| id | 学校 | 根地址 | 状态 |
|---|---|---|---|
| `gzus` | 广州软件学院 | <https://jwxt.gzus.edu.cn> | ✅ **CAS 统一身份认证登录 + 课表 + 学生信息 + 周次课表（日历真实日期）+ TGT 免密续期**，链路每一跳都出自抓包核对；登录需人工填验证码（见下），但**存下 TGT 之后冷启动不用再填** |
| `nhjcxy` | 南京航空航天大学金城学院 | <https://jcjx.nhjcxy.edu.cn> | ✅ 登录 + 课表 + 学生信息**全部实测打通** |

### 广州软件学院：实测记录（登录走统一身份认证）

> **一句话**：广软的师生账号是**统一身份认证（CAS）账号，正方那边没有对应密码** ——
> 所以"正方账号密码直登"对这所学校**根本行不通**，必须走 CAS。

实测证据：拿真实的统一身份认证账号去 POST 正方的 `login_slogin.html`
（RSA 流程完全按 `RsaCipher` 走，`csrftoken` 从页面与 Cookie 各取一份都试过），
服务端**既不跳转也不报错**，只是把登录页原样返回 ——
"密码错"和"账号不存在"表现完全相同，用户没法自查。

#### 登录链路（每一跳都出自抓包核对）

```
① GET  https://cas.gzus.edu.cn/lyuapServer/login?service=<正方SSO地址>
        ↳ 正方SSO地址 = https://jwxt.gzus.edu.cn/sso/lyiotlogin
② POST https://cas.gzus.edu.cn/lyuapServer/v1/tickets          ← 表单编码，不是 JSON！
        username / password(裸RSA,hex) / service / loginType="" / id / code
        ↳ 成功时响应顶层带 ticket（ST 票据）
③ GET  https://jwxt.gzus.edu.cn/sso/lyiotlogin?ticket=<ST>      ← 跟随后续 302
        └→ /sso/lyiotlogin → /jwglxt/ticketlogin?uid=…&verify=… → login_slogin.html
           （正方会话就是在这里建立/续期的；uid/verify 由服务端在 302 里给出，客户端不用自己拼）
④ POST https://jwxt.gzus.edu.cn/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151
        xnm=2026&xqm=3&kzlx=ck&xsdm=&kclbdm=&kclxdm=      → 课表 JSON
```

**不需要经过"办事大厅"**：抓包显示正方自己就有 SSO 入口 `/sso/lyiotlogin`，
让 CAS 的 `service` 直接指向它即可（办事大厅那边用的是同一个 CAS，只是多绕一层）。

#### 四个必须照抄、错了就只表现为"密码错误"的细节

| 细节 | 说明 |
|---|---|
| ★ **请求体是表单编码，不是 JSON** | 同一个请求发 `application/json` → **HTTP 500「系统内部错误」**；发 `application/x-www-form-urlencoded` → 200 + 业务码。原因：前端用 axios，其默认 `Content-Type` 就是 form-urlencoded，而代码里又 `JSON.stringify(...)` —— 属"错配但真实存在"的行为 |
| ★ **密码是"裸 RSA + 小端 + 末尾补零"，输出十六进制** | 和正方那套**像但不同**：正方是大端、无补零；CAS 是**小端**、末尾补零到 `chunkSize`(=126) 字节、输出定长 hex。详见 `api/CasRsaCipher.kt` |
| 公钥**硬编码在前端 bundle 里** | 1024 位（`TAG="lyasp"`、`exponent=010001`），就在 `/assets/js/app.<hash>.js` 里，不是服务端下发的 |
| 两个自定义头 | `loginUserToken` = `RSA(TAG + 服务端毫秒时间)`（服务端时间取响应 `Date` 头）、`loginToken` = `"loginToken"` |

> **`loginUserToken` 实测服务端并不校验**：同一个登录请求，不带任何 token 头、
> 带一段乱写的定长十六进制、带我们算出来的值，服务端都返回同样的业务码（`CODEFALSE`）。
> 所以不必纠结它的精确算法（浏览器那个值里掺了别的东西，用 TAG+时间复算不出来 —— 大小端、
> 秒/毫秒、±3 秒窗口都试过）。`GzusApi` 仍然带着它，是为了"万一哪天开始校验"。
> 但**密码**用的是同一个 JS 函数，那个必须精确。

#### 验证码：每次登录都要，且只能人工识别

服务端 `GET /lyuapServer/loginType` 返回：

```json
{"isAccount":"1","isVerifyCode":"1","isVerifyCodeType":"1","isTwoVerify":"0",
 "defaultLoginUrl":"https://ehall.gzus.edu.cn", ...}
```

`isVerifyCode=1` 意味着**每次**账号密码登录都要验证码（与"输错几次才要"无关）。
验证码来自 `GET /lyuapServer/kaptcha?uid=<任意>` → `{kaptchaType:"1", uid, content:"data:image/png;base64,…"}`，
是一张 **100×25 的算术题图片**（前端输入框是 0~82 的数字）。**只能由人看**。

所以登录被设计成两步（见 `gr_api/LoginCaptcha.kt`）：

```
login(账号, 密码)                → LoginResult.NeedCaptcha(图 + token)
界面显示图、用户填得数
login(账号, 密码, captcha=LoginCaptcha(token, 图, 得数)) → Success
```

登录页的验证码区块在 `res/layout/activity_login.xml` 的 `@id/captchaBlock`（默认 `gone`）。

失败业务码（取自前端 bundle 的映射表）：

| code | 含义 |
|---|---|
| `CODEFALSE` | 要验证码（**实测确认**） |
| `FALSE` / `NOUSER` / `PASSERROR` | 账号密码错 / 账号不存在 / 密码错（`PASSERROR` 会带锁定次数） |
| `ISMODIFYPASS` | 必须先改密码 |
| `ISPHONEOREMAILORANSWER` | 需要短信 / 邮箱 / 密保二次验证 |
| `ISBINDWX` / `NETWORKCOMMITMENT` / `PEOPLEMOREACCOUNT` | 要先绑定微信 / 签承诺书 / 该身份有多个账号 |
| `NOREGISTER` / `NOAUTHORIZATION` | 无权限 |

#### 课表 / 学生信息（同一个接口）

| 用途 | 请求 |
|---|---|
| 个人课表（**信息查询 → 个人课表**） | `GET /jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default` —— 只为读页面里 `#xnm`/`#xqm` 的**默认选中值**（实测 `2026` / `3`），不在客户端猜学期 |
| 课表**数据** | `POST /jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151`，表单 `xnm&xqm&kzlx=ck&xsdm=&kclbdm=&kclxdm=` → **JSON** |

响应里 `xsxx` 是学生信息（`XM` 姓名 / `XH` 学号 / `BJMC` 班级 / `ZYMC` 专业），
`kbList` 是课程数组 —— **一个接口同时给出姓名和课表**，所以 `GzusApi` 只调一次并缓存
（`CourseSync` 会先 `fetchProfile` 再 `fetchSchedule`，不缓存就会把同一份数据打两遍）。

`kbList` 每条的关键字段（**有两个极易写错的**）：

| 字段 | 含义 | 坑 |
|---|---|---|
| `kcmc` | 课程名 | |
| `xm` | **教师姓名** | |
| `zcmc` | **教师职称**（"讲师（高校）"） | ⚠️ 名字像"周次名称"，其实**是职称** —— 既不是教师也不是周次 |
| `zcd` | 周次（"4-19周"） | 周次的真名是 `zcd` |
| `lh` / `cdmc` | 教学楼 / 教室 | 拼起来才是完整地点（"笃行楼U" + "U204"） |
| `xqj` | 星期几（1-7） | 服务端直接给数字；`xqjmc` 才是"星期一" |
| `jcs` | 节次（"1-2"） | 拆成 startPeriod + periodCount |
| `xqmc` | 校区 | |

解析逻辑在 `gr_api/schools/GzusScheduleParser.kt`：
**纯逻辑层（[toCourse]/[parsePeriods]/[parseTerm]）吃字符串**，由 JVM 单测覆盖；
**JSON 取字段层**由真机测试 `GzusScheduleJsonTest` 覆盖 ——
因为 `org.json` 在 JVM 单测里是未实现的 stub（一调用就抛 "not mocked"），
这个分层就是为此而拆的。

#### 遗留：诊断用的两个细节

- 正方登录页的隐藏字段 `#yzcskz=3` / `#dlsbsdsj=3` 表示"密码连错 3 次要验证码 / 会临时锁定"。
  但既然广软走 CAS，这两条只对"有正方密码的部署"有意义 —— `GzusApi` 已不再是表单登录骨架，
  相应提示随 `WebLoginSchoolApi` 一起留给将来接正方直登的学校。
- CAS 前端 bundle 里会先 POST `/lyuapServer/validateLoginCode`，但**这台服务器上它是 404**
  —— 线上 CAS 版本与 bundle 并不完全一致，接新东西时**以实际响应为准**。
  （前端 bundle 里那个调用是"先校验验证码再登录"，404 之后桌面布局的登录会卡住；
  移动布局走的是"直接登录"，所以手机浏览器能用。我们自己实现时直接照移动布局来。）

#### ★ 持久化登录：扒出来的是 **TGT**，不是"7 天保持登录"

登录页上确实有一个「七天保持登录状态」的勾选框（locale key `remember_pass_seven_days`），
但**它跟服务端没有任何关系**，扒它没有任何价值：

| 问题 | 实测结论 |
|---|---|
| 勾选框会发给服务端吗？ | **不会**。整个登录请求体只有 `username / password / service / loginType / id / code`；`rememberMe` 只在前端 Redux state 里传来传去（登录 action 的形参里有它，但构造请求体时**没用上**） |
| 勾了之后发生了什么？ | 前端把 `用户名 & RSA(密码)` 写进自己的 `accountInfo` Cookie；下次打开登录页时读回来，用 bundle 里**硬编码的私钥**（`private_exponent`）解密后回填输入框 |
| 服务端会话被延长了吗？ | **没有**。`loginType` 返回的 `tgcTimeout` 仍是 3600，且每次打开都还得重新看验证码图算加法 |

真正能"免密免验证码"复活会话的是登录响应里的 **`tgt`**：

```jsonc
// POST /lyuapServer/v1/tickets  ← 第一次登录（表单编码）
{"tgt":"TGT-120804-462a21905e484d668497aa782073facc",
 "ticket":"ST-120804-530ea326290b4f18b0d62b7dd214e4b5"}
```

```http
POST https://cas.gzus.edu.cn/lyuapServer/v1/tickets/TGT-120804-462a21905e484d668497aa782073facc
Content-Type: application/x-www-form-urlencoded;charset=UTF-8

service=https%3A%2F%2Fjwxt.gzus.edu.cn%2Fsso%2Flyiotlogin&loginToken=loginToken

→ 200  text/plain   ST-121538-04254d3bcb9f400cbfd720207f4bcc66
```

**三个容易踩的点：**

| 点 | 说明 |
|---|---|
| 换票成功返回的是**纯文本 `ST-…`**，不是 JSON | 只有"第一次账号密码登录"才是 JSON 信封（`{"tgt":…,"ticket":…}`）。写解析时两种都要认 |
| **不需要任何 Cookie** | 实测把 CookieJar 清空、只带 TGT 去换票，照样出 ST —— 所以 TGT 是"拿着就能用"的凭据，**必须和密码同级保护**（落盘走 Keystore，见 §10） |
| TGT **不轮换** | 换票响应里不会再给新 TGT，原 TGT 可以反复用（`tools/cas_probe.mjs tgt-reuse` 连换多次都成功） |

拿到 ST 之后走原来的第 ③ 步（`GET /sso/lyiotlogin?ticket=<ST>` 跟随后续 302）就重新建立了
正方会话。**实测完整链路**（`tools/verify_app_flow.mjs`，只用 TGT、零 Cookie）：

```
302 /sso/lyiotlogin → 302 /sso/lyiotlogin → 302 /jwglxt/ticketlogin
→ 302 /jwglxt/xtgl/login_slogin.html → 302 → 302 /jwglxt/xtgl/index_initMenu.html → 200
CookieJar 拿到：JSESSIONID, route
```

所以 `GzusApi` 的会话存档是 **TGT + Cookie** 两样（`gr_api/schools/GzusSessionCodec.kt`）：
Cookie 让"立刻就能用"，TGT 负责"Cookie 死了还能静默复活"。
请求过程中一旦发现登录态失效（正文/最终地址出现 `login_slogin`），
`withSessionRetry` 会用 TGT 换一套新会话**自动重跑一次**，用户完全无感。

> **有效期：实测 ≈1 小时**（不是"7 天"）。`tools/tgt_watch.mjs` 每 15 分钟用纯 TGT 换一次票：
>
> ```
> +0min  ✅   +15min ✅   +30min ✅   +45min ✅   +60min ❌ HTTP 500「TGT :TGT-…已失效」
> ```
>
> 正好对上 `loginType` 里的 `tgcTimeout: 3600`（秒）—— 那个值就是服务端 TGT 的真实寿命。
> 所以 TGT 能买到的是：**"一小时内冷启动不用再算验证码"**，而不是"七天免登录"。
> （那个「七天保持登录」的勾确实没给服务端任何提示，见上表。）
>
> 过期时服务端返回 **HTTP 500 + 纯文本 `TGT :TGT-…已失效`**（不是 JSON、也没有 `ST-` 前缀），
> 于是 `extractTicket` 自然取不到票 → `GzusApi` 清掉 TGT、退回"登录已过期，请重新登录"。
> 这条路径不需要特判错误码，靠的是"**拿不到 ST 前缀就当失效**"。

#### 周次课表（N2154）：日历页的真实日期从这来

「信息查询 → 周次课表」是另一个页面，和个人课表（N2151）不是一回事：

| 用途 | 请求 |
|---|---|
| 周次课表**页面**（读教学周列表 + 默认学年学期） | `GET /jwglxt/kbcx/xskbcxZccx_cxXskbcxIndex.html?gnmkdm=N2154&layout=default` |
| 某一周的**数据** | `POST /jwglxt/kbcx/xskbcxMobile_cxXsKb.html?gnmkdm=N2154`，表单 `xnm&xqm&zs=<周次>&kblx=1&doType=app&xh=` |

页面里那个 `#zs` 下拉框**自带每一周的起止日期**，带 `selected` 的就是当前教学周：

```html
<select name="zs" id="zs">
  <option value="3" selected="selected">3(2026-09-14至2026-09-20)</option>
  <option value="20">20(2027-01-11至2027-01-17)</option>
</select>
```

实测（2026-09-16）读出 20 个教学周，第 1 周 = 08-31 ~ 09-06 …… 第 20 周 = 2027-01-11 ~ 01-17，
服务端标的当前周 = 3，和真实日期一致。**"今天是第几周"永远读这个，不要在客户端按开学日期推算**。

响应结构和个人课表的 `xsxx`/`kbList` **同构**（姓名、班级、专业、课程字段全一样），
所以 `GzusScheduleParser.parseSchedule` / `parseProfile` **一行都不用改**就能吃这份数据：

```jsonc
{"zs":"3",
 "xsxx": { "XM":"…","XH":"…","BJMC":"…","ZYMC":"…","XNMC":"…" },
 "rqazcList":[ {"xqj":1,"xqjmc":"星期一","rq":"2026-09-14"}, … ],   // 本周一到周日的真实日期
 "kbList":  [ { "kcmc":"军事理论","xm":"鲁鲜亮","lh":"笃行楼T","cdmc":"T301",
                "xqj":"1","jcs":"3-4","zcd":"3周" }, … ] }           // ★ 只含这一周真的会上的课
```

两点值得注意：

- `kbList` 里的 `zcd` 变成**单个周次**（`"3周"` / `"2-3周"`），而不是个人课表那种区间（`"4-19周"`）；
- 第 1 周返回 **0 门课**（还没开学）—— 这正是"按周查"的价值：
  个人课表接口会把军事理论这条"2-4 周"的课一直摆在那儿，
  日历页按星期几循环渲染的话，第 1 周和第 20 周都会显示它。

界面侧由 `data/WeekScheduleStore.kt` 承接（教学周列表 + 周次→课程的内存缓存），
`WeekScheduleApi` 是它的可选接口，**只有实现了它的学校**（目前是广软）日历页才走真实日期。
见 `docs/UI使用文档.md` §3.3。

> **金城学院不实现它，而且是刻意的**：它的课表是"按班级查一张整学期表"，
> 拿不到「某一周有哪些课」这种粒度 —— 硬接的结果是只能拉到本周、
> 日历照样填不满，反而多一个必然失败的请求。
> 这条边界有测试钉着：`SchoolRegistryTest.onlySchoolsWithPerWeekQueries_implementWeekScheduleApi`。
>
> **没有按周数据的学校，日历页只给「本周」兜底**（含今天的那一个自然周，见
> `data/SchoolWeek.kt` 的 `naturalWeekOf`），其他周留空。理由：本地课表是"整学期课 + 周次串"，
> 按星期几循环渲染会把**本周的课画满整个学期** —— 没有权威数据时，宁可不显示，也不显示错的。

### 南京航空航天大学金城学院：实测记录

系统由**两套前后端叠在一起**，登录要过两道（根路径 `/` 只是个 JS 跳转壳，
`location.href = ".../mvc"`，所以静态抓取只能看到一个「正在进入系统…」的空页）：

| 步骤 | 请求 | 实测结果 |
|---|---|---|
| ① | `GET /Mvc/Base/Login` | 200，登录页；页内 `#PublicKey` = 130 位十六进制 SM2 公钥；引 `/Mvc/Scripts/js/Sm2/lib/sm2.js` |
| ② | `POST /Mvc/Base/Login` | **返回 JSON**：成功是 `{"IsSuccess":true,"Message":""}`。表单：`U_Account` / `U_Password`（**都是 SM2 密文**）、`U_LoginType=TqPlatform`、`U_Remark`（=「强制登录」）等 |
| ②′ | 同一账号已在别处登录 | `IsSuccess:false`，`Message` = `用户"某同学(0222010102)"已于…，从(Computer-…)登录。在线时间:…分。` → 带 `U_Remark=true` 重发即可通过 |
| ③ | `GET /Mvc/Base/Home` | 200，**空响应（0 字节）** —— 新前端只是个壳，业务页面全在老系统里 |
| ④ | `GET /EaWeb/Manager/Login.aspx` | 200，老系统登录页（WebForms，有 `__VIEWSTATE`）|
| ⑤ | `POST /EaWeb/Manager/Login.aspx` | **密码明文**提交；成功是 `302 → Messages.aspx` |
| ⑥ | `GET /EaWeb/Manager/Module/NetEa/SchoolRoll/Student/Show/Default.aspx` | 200，学籍页：`LabXm`=姓名、`LabXh`=学号、`LabBh`=**班号**、`LabZym1`=专业、`LabXsm1`=学院 |
| ⑦ | `GET /EaWeb/Manager/Module/NetEa/Schedule/Query/Default.aspx` | 200，课表查询页（要 POST）|
| ⑧ | `POST` 同上 | `ctl00$PageBody$RbtlType=班级` + `ctl00$PageBody$TxbInput=<班号>` + `ctl00$PageBody$BtnShowTable=查看课表` → 返回 `<table id="TabSchedule">` |

登录成功后老系统还会下发 `U_NameCn` Cookie（URL 编码的姓名），作为姓名解析的兜底。

#### 「同一账号已在别处登录」是**必现路径**，不是边角情况

站点对并发登录有拦截：只要浏览器那边还没退出（或上次会话还没超时），
App 登录就会拿到 `IsSuccess:false` + 那段「已于…登录」的文案。
Web 端的处理是**把按钮换成「强制登录」让用户点一下**（脚本里 `$("#MandatoryLogin").val('true')`）。

`NhjcxyApi` 的处理是**自动替用户点这一下**：识别出这段文案后带 `U_Remark=true` 重发一次
（`isConcurrentSessionConflict` + `postMvcLogin(force = true)`），并且**重新加密**一次
（同一条密文不重复使用）。不这么做的话，"先在电脑上登过"的用户在手机上永远登不进来，
而且提示看着像密码错。代价是会顶掉电脑上的会话 —— 这正是「强制登录」按钮本身的含义。

> 想改成"先问用户"的话：把 `login()` 里 `isConcurrentSessionConflict` 那个分支
> 换成直接 `callback(verdict)`，再在登录页加一个「强制登录」按钮传 `force = true` 即可。

#### 课表单元格结构

**一门课占两行**，字段间是两个及以上 `&nbsp;`：

```html
<td>英语听说（一）&nbsp;&nbsp;A1N403&nbsp;&nbsp;禄口<br>孔雁&nbsp;&nbsp;02220101&nbsp;&nbsp;3-5,8-20周</td>
```

即 `课程名 教室 校区` / `教师 班级列表 周次`；「体育」这类没教室没教师的课会缺字段，
解析器要能降级（见 §5.4）。

---

## 7. 踩坑清单

### 7.1 接口层（全部真机验证过）

| 坑 | 现象 | 处理 |
|---|---|---|
| OkHttp **默认不存 Cookie** | 第二步请求就掉登录态，登录永远失败 | `HttpMethod` 内置内存 CookieJar；每校一个独立会话 |
| 登录页是 `<form>` 不是 JSON | 用 JSON 提交，服务端拿不到参数 | `postFormAsync` 用 `application/x-www-form-urlencoded`(UTF-8) |
| 缺 `CSRFTOKEN` | 正方直接拒掉 POST | `prepare()` 先 GET 登录页，再把 Cookie 值回填表单 |
| 密码用 `PKCS1Padding` | 永远"用户名或密码错误" | 必须裸 RSA，见 `RsaCipher` |
| 公钥 base64 首字节 `0x00` | 按有符号解析模数变负数，密文服务端解不开 | `BigInteger(1, bytes)` 按无符号解析（`RsaCipherTest` 守着） |
| 部分系统按 UA 判浏览器 | 返回错误页 / 空页 | `HttpMethod` 统一伪装手机 Chrome UA |
| 缺 `INTERNET` 权限 | 点登录毫无反应 | `AndroidManifest.xml` 已补 |
| 明文密码进 Logcat | 有 adb 就能读到 | 登录日志只打学校与账号；`HttpMethod` 的 **BODY 级日志还会按字段名给密码打码**（`LoginPass` / `mm` / `password`…），否则老系统那一段明文密码会直接出现在 logcat 里 |
| **国密系统只加密了密码** | 永远"用户名或密码错误" | 金城学院**账号也要 SM2 加密**（页面 JS 就是两个都加密） |
| **SM2 密文顺序** | 同上 | 必须 `C1C2C3`（对齐对方 `sm2.js`），不是国标的 `C1C3C2` |
| **同一公钥缓存复用** | 隔一段时间登录必失败 | 公钥**每次 GET 登录页重新取**，不要缓存 |
| **账号已在别处登录** | 电脑上登过之后，手机一直登不进来，提示像密码错 | 识别文案后带 `U_Remark=true`（强制登录）重发一次，见 §6 |
| **WebForms 漏传 `__VIEWSTATEENCRYPTED`** | POST 回来 HTTP 500「验证视图状态 MAC 失败」，看起来像服务器/集群故障 | 该字段值虽然是空串也必须回传；ViewState 是加密态时服务端靠**这个字段是否存在**决定要不要解密 |
| **课表按"班级"查却传了专业/姓名** | 接口 200，但返回一张空表（**不报错**）| 班号 = 学号去掉后两位，或从学籍页 `LabBh` 取 |
| 学籍/课表页都要求老系统会话 | 业务接口 302 跳 `Login.aspx?ReturnUrl=…` | 两段登录必须共用同一个 `HttpMethod` 实例 |
| `ViewState` 里带 `+` `/` `=` | 手工拼表单时被 URL 解码坏掉 | 用 `postFormAsync`（FormBody 会正确编码），别自己拼字符串 |
| ★ **服务端自己下发明文跳转** | `CLEARTEXT communication to … not permitted by network security policy`，登录卡在"换取教务系统会话" | 见下面 §7.2 —— 已在 `res/xml/network_security_config.xml` 里按域名开豁免 |
| **想用拦截器把 http 改成 https** | 改不动跳转：应用拦截器看不到 302 出来的地址，网络拦截器又会丢 `Secure` Cookie | 别走这条路，原因见 §7.2 |

### 7.2 ★ 服务端下发明文（http）跳转 —— Android 会直接拦掉

**现象**：`CLEARTEXT communication to jwxt.gzus.edu.cn not permitted by network security policy`，
登录卡在"换取教务系统会话"。

**原因**：广州软件学院的正方**自己在 `Location` 里写 http**（nginx 配置问题）：

```
① https://jwxt.gzus.edu.cn/jwglxt/ticketlogin?uid=…  → 302 → http://jwxt.gzus.edu.cn/jwglxt/xtgl/login_slogin.html
② http://jwxt.gzus.edu.cn/jwglxt/xtgl/login_slogin.html → 302 → https://…（同一个路径）
③ https://jwxt.gzus.edu.cn/jwglxt/xtgl/login_slogin.html → 302 → http://jwxt.gzus.edu.cn/jwglxt/xtgl/index_initMenu.html
④ http://jwxt.gzus.edu.cn/jwglxt/xtgl/index_initMenu.html → 302 → https://…（同一个路径）
```

而 Android 从 targetSdk 28 起**默认禁止明文流量**（本项目没开豁免），
OkHttp 一到那两条 http 地址就抛异常。**会话过期时也会撞上** ——
未登录访问 `https://…/index_initMenu.html` 同样是 302 到 `http://…/login_slogin.html`。

**实测结论**：那些 http 地址就是**同一路径的 https 镜像** ——

| 请求 | 实测 |
|---|---|
| `http://…/xtgl/login_slogin.html` | 302 → `https://…/xtgl/login_slogin.html` |
| `https://…/xtgl/login_slogin.html` | **200**（直接可用） |
| `http://…/xtgl/index_initMenu.html?jsdm=xs` | 302 → `https://…` 同路径 |
| `https://…/xtgl/index_initMenu.html?jsdm=xs` | 302 → 登录页（未登录时的正常表现） |

**修法**：`res/xml/network_security_config.xml` 给**这一个域名**开明文豁免，并在清单里引用：

```xml
<base-config cleartextTrafficPermitted="false" />          <!-- 全局仍然禁止明文 -->
<domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="false">jwxt.gzus.edu.cn</domain>
</domain-config>
```

#### 为什么不是"在客户端把 http 改成 https"

那个方案看起来更漂亮（地址确实是 https 镜像），但**做不成**，原因值得记下来：

> **OkHttp 的自动重定向不会重跑应用拦截器。**
> 重定向发生在 `RetryAndFollowUpInterceptor` 里，而它在应用拦截器链条的**内部** ——
> 应用拦截器只在最初那一次请求上跑，服务端 302 出来的地址它根本看不到。
>
> 换成**网络拦截器**能被每次跳转调用到，但**加 Cookie 的 `BridgeInterceptor` 在网络拦截器之前**：
> 它会按 http 那个地址计算"该带哪些 Cookie"，于是 `Secure` 属性的会话 Cookie 被丢掉 ——
> 等于把登录态弄没，比明文报错更难查。
>
> 要真正干净地做，得自己实现整套重定向跟随（连 307/308 的方法/请求体语义一起），
> 代价与风险都不小，而且**没有真实会话就没法验证**。

而"开豁免"**完全等价于浏览器的行为** —— 浏览器就是这么走的，抓包已经证明它能登录成功。

安全影响（已评估，写在 XML 注释里）：

- **只对这一个域名放开**，全局仍然是禁止明文（`base-config` 是 `false`）；
- 那两条 http 跳转**不携带任何凭据**（只是 `login_slogin.html` / `index_initMenu.html` 这类页面地址，无参数），
  密码与票据全在 https 上传输；
- 学校修好 nginx 的 `Location` 之后，这份豁免应该删掉。

> 将来若有别的学校也这样，**只加它自己的域名**，别图省事改成全局
> `usesCleartextTraffic="true"`（那是对全世界放开明文）。
> 真遇到"只支持 http、没有 https"的学校时，用户会看到一句能照做的提示：
> 「该学校的地址只支持不安全的 http 连接，已被系统拦截……」（`SchoolApi.readableMessage`）。

### 7.3 附：登录页 UI 相关（与接口无关，但同属登录页）

| 坑 | 现象 | 处理 |
|---|---|---|
| 中文校名太长 | 被省略号截成「南京航空航天大学金城学…」 | 卡片 margin 24→16dp、内边距 16→12dp，文本区 574→640px，12 字校名以 16sp 放下；`ui/TextFit.kt` 兜底 |
| 想用 `startIconMinSize` 换宽度 | 图标**左移 33px 紧贴框边**，与相邻字段错位 | Material 把起始图标居中在预留区域里，缩区域图标就跟着挪 → 宽度要从容器上要 |
| 给 EditText 写 `paddingStart/End` | 没任何效果（文本区仍是 574px） | Material 的 `TextInputLayout` 会覆盖 EditText 的左右内边距 |
| 预估可用宽度 | 算出"放得下"、实际仍被截断 | Material 的图标内边距是**布局阶段才**加的（真机两趟 `Layout` 宽度 772→574px）→ 必须读 `Layout.getWidth()` |
| 靠 `maxLines="1"` 保证单行 | `MaterialAutoCompleteTextView`（`inputType="none"`）仍可能排成两行 | 由"实测文本宽度 ≤ 可用宽度"自己保证单行 |

---

## 8. 附：四份相关文档怎么分工

| 文档 | 讲什么 |
|---|---|
| **本文** | `gr_api/` 的接入与调用（改接口、加学校、调业务接口、调试） |
| `docs/UI使用文档.md` | 数据模型与字段口径、UI 侧的对接点（面向"要把本地仓库换成网络实现"的人） |
| `docs/UI架构与实现指南.md` | Compose 侧架构、玻璃令牌、性能清单 |
| 本文 §9 | 校园地图页：图片放哪儿、怎么加一所学校的地图 |
| 本文 §10 | 维持登录与退出账号：会话存哪儿、怎么加密、过期怎么办 |

---

## 9. 校园地图页（`res/` 资源与 `SchoolMap`）

底栏第 3 个 Tab「地图」显示**当前登录学校**的校园地图（`ui/MapScreen.kt` + `MapFragment.kt`）。

### 9.1 图片必须放在合法的资源目录里

✅ **原来的放法是错的**：地图原本放在 `app/src/main/res/school_map/`。
**`res/` 下只认固定的一批目录名**（`drawable` / `layout` / `values` / `raw` / `xml` …），
其他名字 AAPT2 会**整个忽略**，所以那两张图在代码里根本取不到（`R.drawable.xxx` 不会生成）。

现在已经挪好并改了名：

| 原路径 | 现在 | 对应学校 |
|---|---|---|
| `res/school_map/nhjc_school_map.jpg` | `res/drawable-nodpi/school_map_nhjcxy.jpg` | `nhjcxy` 金城学院 |
| `res/school_map/gr_school_map.jpg` | `res/drawable-nodpi/school_map_gzus.jpg` | `gzus` 广州软件学院 |

两个细节：

- **放 `drawable-nodpi`**：地图是"整张图缩放查看"的大图，`-nodpi` 表示不按屏幕密度放大。
  放普通 `drawable/` 会被 density bucket 放大 2~3 倍（1280×959 会变成 3800+ 像素），白吃内存。
- **资源名必须全小写 + 下划线**（`school_map_nhjcxy`），不能有驼峰/连字符/中文 —— AAPT2 的硬性要求。

### 9.2 加一所学校的地图

1. 图片按 `school_map_<学校id>.jpg` 放进 `res/drawable-nodpi/`（名字只是约定，小写即可）；
2. 在 `data/SchoolMap.kt` 的 `drawables` 里加一行：

```kotlin
private val drawables: Map<String, Int> = mapOf(
    "nhjcxy" to R.drawable.school_map_nhjcxy,
    "gzus"   to R.drawable.school_map_gzus,
    "xxx"    to R.drawable.school_map_xxx,   // ← 加这里
)
```

**键必须是 `SchoolRegistry` 里登记的学校 id**（也是持久化主键）。拼错了不会崩，
只会表现为"地图页说这学校没有地图"——所以 `SchoolMapTest` 会校验：
键都是真实学校 id、两所自带地图的学校能解析到资源、未知 id 安全返回 `null`。

> 没登记地图的学校**不会崩**：地图页显示「「XX大学」还没有地图」+ 怎么加，
> 而不是一片空白。

### 9.3 地图页的交互

- 双指缩放（1×~6×）、拖动、双击复位，放大后右下角出现「复位」按钮；
- 缩放位移走 `graphicsLayer`（绘制阶段），不改尺寸 → 不触发每帧重新布局；
- 位移做了**边界收拢**：最多拖到"图片边缘贴住容器边缘"，
  避免出现"地图被拖没了、看起来像加载失败"。

---

## 10. 维持登录与退出账号

### 10.1 为什么"存 Cookie"就够了，不用存密码

教务系统的登录态**完全在 Cookie 里**（`ASP.NET_SessionId`、正方的 `JSESSIONID`、
老系统的 Forms 票据）。所以"下次打开还是登录状态"只需要：

```
登录成功   → api.exportSession()  取出全部 Cookie
            → SessionBlob 打包（学校 id + 账号 + Cookie）
            → SessionVault 用 Keystore 密钥 AES-GCM 加密后落盘
冷启动     → 解密 → 解开 → api.importSession(Cookie) → 直接进主界面
```

**不需要重放登录请求，更不需要存密码。** 这一点已实测验证：
把登录后的 13 条 Cookie 存下来，灌进一个**全新的空会话**里，
不去做任何登录，直接取学籍页和课表页 —— 姓名和课表都能正常拿到。

#### 例外：广软还多存一个 **TGT**（同样不存密码）

正方的 `JSESSIONID` 是**会话级**的：服务端一超时、或者隔天再打开，那份 Cookie 就是废纸。
而广软的登录要过验证码（一张算术题图，只能人工识别），重来一次对用户是实打实的负担。

所以 `GzusApi` 的 `exportSession()` 存的是**两样东西**
（`gr_api/schools/GzusSessionCodec.kt`，格式 `manycourse-gzus-session/1`）：

| 存什么 | 作用 |
|---|---|
| 正方 Cookie | 当下就能用（和别的学校一样） |
| **CAS 的 TGT** | Cookie 死了也能**免密免验证码**换一套新会话 |

冷启动时如果发现登录态失效（正文/最终地址出现 `login_slogin`），
`GzusApi.withSessionRetry` 会自动 `POST /lyuapServer/v1/tickets/<TGT>` 换一张新 ST、
走一遍 `sso/lyiotlogin` 重建正方会话，然后**重跑刚才那次请求** —— 用户全程无感。
链路与实测见 §6「持久化登录」一节。

要注意的是 **TGT 等价于一张能反复换取会话的通行证**（实测不需要任何 Cookie），
所以它和密码同级敏感；它和 Cookie 一起躺在**同一个 Keystore 加密存档**里，
不额外落任何明文。TGT 一旦被服务端拒绝就立刻清掉，退回"重新登录"。

> **实测有效期 ≈1 小时**（见 §6）—— 所以它买到的是"一小时内冷启动免验证码"，
> 不是"七天免登录"。想要更久只能存密码自动重放，而广软**每次登录都要验证码**，
> 重放也过不去；存密码等于纯粹的风险，不做。

> 「加一所学校」时如果对方也有这类长效票据（remember-me token、refresh token），
> 照这个思路做：**导出时多带一个字段，导入时灌回去，请求失败时静默续期一次**。
> 但**不要把密码存下来重放** —— 广软这套里密码重放根本走不通（每次都要验证码）。

### 10.2 存档是加密的（这一步不能省）

存进去的东西等价于**一张能以该用户身份访问教务系统的通行证**，
而教务系统里躺着身份证号、家庭住址、成绩。所以：

| 做法 | 说明 |
|---|---|
| **Android Keystore + AES-GCM**（当前做法） | 密钥由系统（TEE/StrongBox）保管、**不落盘**；就算把 SharedPreferences 文件拷走也解不开。GCM 自带完整性校验，密文被改过会解密失败而不是解出乱码 |
| `EncryptedSharedPreferences` | 效果类似，但要引 `androidx.security:security-crypto` —— 只是想加密一小段文本，不值得 |
| ❌ 明文 SharedPreferences | root 设备 / `adb backup` / 云备份都能直接拿走，比存密码还危险（密码至少还要过一遍服务端校验） |

这跟 `LoginSettings` 里早就写下的规矩是一致的：
"**不存账号密码**，真要记住得走 EncryptedSharedPreferences / Keystore，不要图省事写明文"。
**账号（学号）也只存在这个加密存档里**，不另外写明文。

失败一律当作"没有会话"：密钥被系统作废（用户清了锁屏密码、恢复出厂、换设备）时
解密会抛异常 —— 此时清掉存档、回到登录页，绝不让 App 崩在启动路径上。

### 10.3 会话过期怎么办

Cookie 存下来了，但**服务端可能早就不认了**。这种情况必须能被识别出来，
否则用户会看到"已登录但课表空着"，然后对着"重试"反复点。

识别方式（`ensureSessionAlive`）：教务系统对未登录请求会 **302 跳登录页**，
而 OkHttp 默认跟随重定向 —— 到手的其实是 **200 + 登录页 HTML**，光看状态码发现不了。
所以看**最终地址**（`HttpResponse.finalUrl`）和正文特征，抛 `SessionExpiredException`：

| 学校 | 判据 |
|---|---|
| 金城学院 | 最终地址落在 `/EaWeb/Manager/Login.aspx`，或正文里还有 `LoginPass` 密码框 |
| 广州软件学院 | 最终地址或正文里出现 `login_slogin`（正方登录页特征） |

**识别出来之后先别急着报错**：广软那边还有 TGT，`GzusApi` 会先**静默续期一次**再决定
（`withSessionRetry`，见 §10.1）。只有续期也失败才往上抛，才轮到下面这套：

`CourseSync` 收到它就置为 `State.Expired`，课表页顶部显示
**「登录已过期，请重新登录」+ 重新登录按钮**（和 `Failed` 的"重试"分开 ——
过期了反复重试是点不好的）。日历页同理（`WeekScheduleStore.State.Expired`）。

### 10.4 退出账号

入口在「我的」页最下面的**账号卡片**：显示当前登录的学校与学号，
以及一个二次确认的「退出登录」。`SessionStore.logout()` 做四件事：

1. `api.clearSession()` —— 清服务端的 Cookie 会话。
   **先清它**：金城学院是"一个账号只能在线一处"，只清本地的话，
   下次用别的账号登录会被"已在别处登录"多拦一次（虽然会自动强制登录，但没必要绕）；
2. `SessionVault.clear()` —— 删掉本地加密存档；
3. 清掉内存里的账号 / 学校 / 调试标记；
4. `CourseSync.clearOnLogout()` —— 清掉姓名与**课表**（课表来自上一个账号的教务系统，
   留着就是数据串号），下次登录重新拉。

然后 `logoutAndBackToLogin()` 启动登录页并带 `CLEAR_TASK`：
**必须清返回栈**，否则用户按返回键会回到已经退出的主界面，看到上一个人的课表。
顺序也不能反 —— 先清会话再跳转，不然登录页一 `onCreate` 发现"还登录着"，
会立刻把用户弹回主界面（看起来就是"点了退出没反应"）。

### 10.5 加一所学校时要做的事

| 情况 | 要做的 |
|---|---|
| 继承 `WebLoginSchoolApi`（正方那类） | **什么都不用做**，会话三件套已自带 |
| 直接实现 `SchoolApi`（多步登录那类） | 照 `NhjcxyApi` 抄三行：`exportSession` / `importSession` / `clearSession` |
| 什么都不做 | 也能跑：这三件事的默认实现是"不支持持久化"—— 用户每次打开都要重新登录，`clearSession` 是空操作 |

配套测试：`CookieCodecTest`（Cookie 序列化，含教务系统那些畸形值）与
`SessionBlobCodecTest`（存档信封）。这两层是纯逻辑，必须测 ——
它们错了的表现是"重启后要求重新登录"或者更糟：**恢复出一个身份不明的会话**。
