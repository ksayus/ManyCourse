# ManyCourse · 课多多

> 登录学校教务系统，课表自动到手 —— 一款面向中国高校学生的原生 Android 课表 / 日历 / 校园地图应用。

<p>
<img alt="platform" src="https://img.shields.io/badge/Platform-Android%2012%2B-3DDC84?logo=android&logoColor=white">
<img alt="kotlin" src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white">
<img alt="compose" src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white">
<img alt="minSdk" src="https://img.shields.io/badge/minSdk-31%20(Android%2012)-blue">
<img alt="schools" src="https://img.shields.io/badge/%E5%B7%B2%E6%8E%A5%E5%85%A5%E5%AD%A6%E6%A0%A1-3-brightgreen">
<img alt="ci" src="https://img.shields.io/badge/CI-GitHub%20Actions-2088FF?logo=githubactions&logoColor=white">
</p>

> 下载安装包 / 项目介绍页：**[index.html](index.html)**（部署后即为 GitHub Pages 的线上地址）。

ManyCourse（中文名「课多多」）不做"又一个手输课表 App"。它的核心是**把各校教务系统的登录与课表接口一个个接进来**：
填一次学号密码（或过一道验证码），姓名、课表、教学周自动同步到本地，
配合周视图 / 月历 / 校园地图三个页面，开学第一周不用再对着教务网站截图。

```
     登录页              课表页              日历页              地图页               我的      
┌──────────────┐      ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
│    学校 ▾    │      │ 周一  第1-2节 │    │   2026.03    │    │   [校区条]   │     │  昵称/专业   │
│     学号     │      │   高等数学    │    │   ▪ ▪  ● ▪   │    │  校园平面图  │     │   课程统计   │
│     密码     │ ──▶ │  8:00 A-101  │    │ ─ 今日课程 ─  │    │   双指缩放   │     │  设置/退出   │
│   [登 录]    │      │ [＋添加课程]  │    │  ▪ ▪  ● ▪    │    │   双击复位   │     │   账号卡片   │
└──────────────┘      └──────────────┘    └──────────────┘    └──────────────┘    └──────────────┘
```

---

## 目录

- [1. 这个项目是什么](#1-这个项目是什么)
  - [1.1 功能](#11-功能)
  - [1.2 已接入的学校](#12-已接入的学校)
  - [1.3 技术栈](#13-技术栈)
- [2. 快速开始](#2-快速开始)
- [3. 项目结构](#3-项目结构)
- [4. 校园 API 添加指南（加一所学校）](#4-校园-api-添加指南加一所学校)
  - [4.1 先判断属于哪一种学校](#41-先判断属于哪一种学校)
  - [4.2 抓包：拿到三个值](#42-抓包拿到三个值)
  - [4.3 三步接入（表单登录型）](#43-三步接入表单登录型)
  - [4.4 多步登录 / JSON 判定的学校](#44-多步登录--json-判定的学校)
  - [4.5 接口契约速查](#45-接口契约速查)
  - [4.6 密码加密：四种常见形态](#46-密码加密四种常见形态)
  - [4.7 验证码](#47-验证码)
  - [4.8 拉课表与拉学生信息](#48-拉课表与拉学生信息)
  - [4.9 节次表（作息）是学校的事实](#49-节次表作息是学校的事实)
  - [4.10 「维持登录」：会话持久化](#410-维持登录会话持久化)
  - [4.11 提交前自查清单](#411-提交前自查清单)
- [5. 地图添加指南（加校区地图）](#5-地图添加指南加校区地图)
  - [5.1 图片放哪儿：必须是合法资源目录](#51-图片放哪儿必须是合法资源目录)
  - [5.2 登记校区（含坐标）](#52-登记校区含坐标)
  - [5.3 坐标从哪来](#53-坐标从哪来)
  - [5.4 定位与"显示哪张图"](#54-定位与显示哪张图)
  - [5.5 地图页交互](#55-地图页交互)
  - [5.6 校验](#56-校验)
- [6. 节假日数据（放假当天的课自动不打亮）](#6-节假日数据放假当天的课自动不打亮)
  - [6.1 它解决什么问题](#61-它解决什么问题)
  - [6.2 数据从哪来：内置表，不联网](#62-数据从哪来内置表不联网)
  - [6.3 只置灰放假，不猜补课](#63-只置灰放假不猜补课)
  - [6.4 加新一年：每年更新一次](#64-加新一年每年更新一次)
  - [6.5 校验](#65-校验)
- [7. 桌面小组件与课前提醒](#7-桌面小组件与课前提醒)
  - [7.1 2x2 桌面小组件](#71-2x2-桌面小组件)
  - [7.2 课前提醒](#72-课前提醒)
  - [7.3 放假日整天跳过](#73-放假日整天跳过)
  - [7.4 小米超级岛 / 焦点通知：三档展示增强](#74-小米超级岛--焦点通知三档展示增强)
  - [7.5 已知限制：没收录作息的节次不提醒](#75-已知限制没收录作息的节次不提醒)
  - [7.6 校验](#76-校验)
- [8. 构建](#8-构建)
  - [8.1 环境要求](#81-环境要求)
  - [8.2 命令行构建](#82-命令行构建)
  - [8.3 运行测试](#83-运行测试)
  - [8.4 签名与发布包](#84-签名与发布包)
  - [8.5 常见构建问题](#85-常见构建问题)
- [9. CI/CD 自动构建](#9-cicd-自动构建)
  - [9.1 两个 Job 分别做什么](#91-两个-job-分别做什么)
  - [9.2 配置签名 Secrets](#92-配置签名-secrets)
  - [9.3 打一个正式版](#93-打一个正式版)
  - [9.4 下载页（index.html）](#94-下载页indexhtml)
  - [9.5 常见 CI 失败](#95-常见-ci-失败)
- [10. 延伸文档](#10-延伸文档)
- [11. 免责声明](#11-免责声明)

---

## 1. 这个项目是什么

### 1.1 功能

| 能力 | 说明 |
|---|---|
| **多学校登录** | 登录页下拉选择学校，一所学校一份独立实现；登录页、下拉、持久化全部由学校清单驱动，加学校**不用改 UI** |
| **课表自动同步** | 登录成功后按学校分发到对应接口，把课表拉进本地仓库；课表页显示的就是教务系统里的课 |
| **姓名自动同步** | 拉学生信息拿"学校系统里显示的名字"，自动写进「我的」页；**用户自己改过的昵称不会被覆盖**。同步回来的姓名 / 专业会**按账号落盘**，所以下次离线打开（课表走缓存）也显示对人 |
| **手动刷新数据** | **下拉课表页**或点页头的刷新按钮，强制绕开"6 小时内不联网"的缓存判断重新拉一遍（姓名 + 学期课表 + 教学周）；本地调试账号 / 未登录时下拉不会静默失败，会说明原因 |
| **周视图课表** | 按星期 × 节次排版，当前课程高亮；支持手动添加 / 删除课程（本地课程与同步课程共存） |
| **月历视图** | 一个月的课程密度一览（每天一课一圆点，多课多色），点日期看当天课程；教学周与本日定位来自教务系统的周次接口。周视图与月视图**换内容都是颜色淡入淡出**（换周 / 翻月 / 点另一天），**只改颜色、不做位移** —— 课表的位置本身就是信息。周视图的课表区**高度恒定**：周次滑块、来源提示条、节假日提示都是"按最坏情况留好位置"，不会因为某一行出现/换文案把课表挤矮（字号放大时宁可网格内部滚动，也不截断说明文案） |
| **节假日自动置灰** | 法定放假日（国务院办公厅的放假安排，内置 2025–2026）当天的课**照旧列在课表上，但一门都不点亮**（灰着），并用一行字说明"某某节放假 · 当天的课都不上"；调休上班日只标一个「班」、课表不变；可在设置里关掉 |
| **校园地图** | 一所学校**多个校区多张图**，进页面取一次定位，自动挑"离你最近的那个校区"那张图；可手动切换，双指缩放 / 拖拽 / 双击复位 |
| **维持登录** | 会话（Cookie）用 Android Keystore 密钥 AES-GCM 加密后落盘，冷启动直接进主界面；**绝不存密码** |
| **登录过期识别** | 区分"网络失败（可重试）"与"会话过期（必须重新登录）"，过期时给重新登录入口而不是让人反复点重试 |
| **深色模式 / 玻璃拟态 UI** | Jetpack Compose + Material 3，玻璃底栏 / 玻璃头部 / 液体玻璃背景，深色模式自适应 |
| **桌面小组件（2x2）** | 桌面上直接看「下一节课」：课程名 / 节次 / 时刻 / 教室 + 倒计时。没数据时说"打开 App 同步课表"，**绝不拿预置的演示课顶替** |
| **课前提醒** | 上课前自动通知（提前量 5/10/15/20/30 分钟可选，默认 15）。**放假当天整天跳过**（不会把你叫去教室）；没有收录作息的节次不提醒、也不编时间 |
| **小米展示增强** | 通知按**运行时能力探测**分三档：标准通知 / 小米**焦点通知** / 小米**超级岛**（社区库，仅小米机型且系统声明支持时尝试，不成立就回退）。**不承诺上岛** —— 见[第 7 章](#7-桌面小组件与课前提醒) |

### 1.2 已接入的学校

三所学校的登录与课表接口**都经过真机抓包验证**（不是猜的）：

| 学校 | id | 教务系统 | 登录方式 | 密码形态 | 课表 |
|---|---|---|---|---|---|
| 广州软件学院 | `gzus` | 正方教务系统 V9 | 统一身份认证（CAS）+ **算术题验证码** | 裸 RSA（PKCS#1 v1.5 + base64） | ✅ JSON 接口 |
| 南京航空航天大学金城学院 | `nhjcxy` | ASP.NET MVC + EaWeb 两段式 | 两次登录（新前端 + 老后台） | **国密 SM2**（自己实现，不引 BouncyCastle） | ✅ 按班级查表 |
| 广东工业大学 | `gdut` | 金智统一身份认证 + jxfw | authserver 单点登录 | **AES**（`randomString(64) + 密码`） | ✅ `xsAllKbList` |

> **想让你的学校也进来？** 照 [第 4 章](#4-校园-api-添加指南加一所学校) 走一遍，通常只要一个新文件 + 一行注册。
> 想贡献地图只要一张图 + 一行登记，见 [第 5 章](#5-地图添加指南加校区地图)。

### 1.3 技术栈

| 项 | 值 |
|---|---|
| 语言 | Kotlin 2.2（`kotlin.code.style=official`） |
| UI | Jetpack Compose（Compose BOM 2026.02.01）+ Material 3 + Haze 玻璃效果 |
| 页面承载 | 4 个 Fragment 各持一个 ComposeView，`ManyCourseMain` 负责挂载与切换 |
| 网络 | OkHttp 5（每个学校一个实例 = 一个独立 Cookie 会话），无 Retrofit、无 codegen |
| 加密 | 自实现裸 RSA / 国密 SM2 + SM3 / 金智 AES（`api/` 包，全部有单测） |
| 会话存储 | Android Keystore + AES-GCM（`data/SessionVault.kt`） |
| 构建 | AGP 9.3.2 + Gradle 9.5（wrapper 已带 `distributionSha256Sum` 校验） |
| SDK | minSdk **31**（Android 12）/ targetSdk **37** / compileSdk **37** |
| 版本号 | `versionName` 四位：`大版本.小版本.已修 Bug 数.支持的学校数`（当前 `1.0.0.3`）；CI 用 tag 覆盖 |
| 通知与小组件 | 系统 `AlarmManager`（单条链式，不引 WorkManager）+ `NotificationManager` + `AppWidgetProvider`/`RemoteViews`（不引 Glance）；小米展示增强走社区库 `io.github.d4viddf:hyperisland_kit`（Maven Central） |
| 测试 | JVM 单测（解析器、加密、选校区、节次表、下一节课、提醒时刻、展示档位、焦点通知 JSON） + 真机插桩测试（UI、实弹登录、通知与小组件现场取证） |

---

## 2. 快速开始

```powershell
# 1) 克隆
git clone https://github.com/ksayus/--ManyCourse.git
cd --ManyCourse

# 2) 写本机 SDK 路径（local.properties 不进仓库，Android Studio 会自动生成）
"D:\DevelopEnvironment\AndroidSDK" | ForEach-Object { "sdk.dir=$_" } | Set-Content local.properties

# 3) 跑单测 + 出 debug 包
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug

# macOS / Linux：./gradlew :app:testDebugUnitTest :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`，`adb install -r` 装上即可。

只想看效果、不想编译？点开 [`index.html`](index.html)（或用 GitHub Pages 的线上地址）直接下载已构建好的 APK。

---

## 3. 项目结构

```
ManyCourse/
├─ app/                                    唯一的应用模块
│  ├─ src/main/java/com/tof/manycourse/
│  │  ├─ MainActivity.kt                   登录页（学校下拉 / 账号密码 / 验证码）
│  │  ├─ ManyCourseMain.kt                 主界面：4 个 Tab 的挂载与切换 + 同步起点
│  │  ├─ ClassScheduleFragment.kt          课表 Tab 载体
│  │  ├─ CalenderFragment.kt               日历 Tab 载体
│  │  ├─ MapFragment.kt                    地图 Tab 载体
│  │  ├─ SelfFragment.kt                   我的 Tab 载体
│  │  ├─ api/                              ★ 网络与密码学底座（与"学校"无关的可复用件）
│  │  │  ├─ HttpMethod.kt                  OkHttp + 内存 Cookie 会话 + 异步表单 POST
│  │  │  ├─ CookieCodec.kt                 Cookie 会话的导出/导入格式
│  │  │  ├─ RsaCipher.kt                   正方用的「裸 RSA」（大端、无填充、hex）
│  │  │  ├─ CasRsaCipher.kt                金智 CAS 的「裸 RSA」（小端、末尾补零）—— 与上面别混
│  │  │  ├─ Sm2Cipher.kt                   国密 SM2 加密 + SM3 摘要
│  │  │  └─ AuthserverAesCipher.kt         金智统一身份认证的密码 AES
│  │  ├─ data/                             ★ 与 UI 交互的本地仓库（UI 只依赖这一层）
│  │  │  ├─ School.kt                      学校纯数据（id / 名称 / 根地址 / 节次表）
│  │  │  ├─ SchoolCourse.kt                教务系统课程 + StudentProfile 的中立模型
│  │  │  ├─ SchoolMap.kt                   ★ 学校 id → 校区（地图图 + 坐标）
│  │  │  ├─ CampusPicker.kt                ★ 显示哪个校区的纯函数（可单测）
│  │  │  ├─ HolidayCalendar.kt             ★ 法定节假日表（哪天放假 / 哪天调休上班）
│  │  │  ├─ Timetable.kt                   ★ 各校节次表（作息）
│  │  │  ├─ Course.kt / CourseEntries.kt   本地课程仓库与增删
│  │  │  ├─ CourseSync.kt                  ★ 登录后「拉姓名 + 拉课表 → 写仓库」的编排
│  │  │  ├─ SessionStore.kt / SessionBlob.kt / SessionVault.kt   会话：内存态 / 信封 / 加密落盘
│  │  │  ├─ ProfileRepository.kt           个人资料（昵称 / 专业 / 统计）
│  │  │  ├─ WeekScheduleStore.kt           教学周课表（日历页用）
│  │  │  └─ NextClass.kt                   ★★「下一节课」唯一解析口径（小组件与提醒共用）
│  │  ├─ reminder/                         ★ 课前提醒：单条 AlarmManager 链式调度 + 开机/时区重排
│  │  ├─ notify/                           ★ 通知展示：标准通知 + 小米焦点通知/超级岛 三档降级
│  │  ├─ widget/                           ★ 2x2「下一节课」桌面小组件（RemoteViews）
│  │  ├─ gr_api/                           ★ 学校接口层（一所学校一个文件）
│  │  │  ├─ SchoolApi.kt                   契约：login / fetchSchedule / fetchProfile / 会话三件套
│  │  │  ├─ WebLoginSchoolApi.kt           「单次表单登录」型教务系统的通用骨架
│  │  │  ├─ SchoolRegistry.kt              ★★ 学校清单 —— 整个应用唯一的「加学校」入口
│  │  │  ├─ WeekScheduleApi.kt             「按教学周查课表」可选接口
│  │  │  ├─ LoginCaptcha.kt / Html.kt      验证码载体 / 极简 HTML 取值工具
│  │  │  └─ schools/                       GdutApi · GzusApi · NhjcxyApi 及各自的解析器
│  │  └─ ui/                               Compose 界面（课表 / 日历 / 地图 / 我的 / 设置 / 玻璃组件）
│  ├─ src/test/                            JVM 单测（解析、加密、选校区、节次表、仓库）
│  └─ src/androidTest/                     真机插桩测试（UI、实弹登录、通知与小组件现场取证）
├─ .github/workflows/android-ci.yml        ★ CI/CD：测试 / 构建 / 发布 / 部署下载页
├─ index.html                              ★ 项目介绍 + APK 下载页（GitHub Pages）
├─ tools/                                  抓包摘要、CAS 联调、图标生成等开发脚本
├─ gradle/libs.versions.toml               依赖与版本的唯一出处
└─ docs/                                   设计文档（地图/接口细节、UI 架构，本机留存）
```

**两条铁律**（改代码前先记住）：

1. **学校 `id` 是持久化主键，定了就不许改** —— 改了就相当于把老用户"上次选的学校"清空。全小写英文短名。
2. **加业务接口不要新建 `HttpMethod`** —— 登录成功后会话（Cookie）就在 `SchoolApi` 实例的 `http` 里，
   新建实例等于换了个空会话，接口必然跳登录页。

---

## 4. 校园 API 添加指南（加一所学校）

> 这一章是「怎么把一所新学校接进来」的完整流程。更细的实弹记录（含每所学校的抓包原文）在
> `docs/教务API接入与调用指南.md`。

### 4.1 先判断属于哪一种学校

| 学校长什么样 | 抄哪个 | 工作量 |
|---|---|---|
| 一张 `<form>` POST 一次就完事（正方、强智、URP、青果…） | 继承 `WebLoginSchoolApi` | 一个文件 ~20 行 + 注册 1 行 |
| **登录要多步**（先登 A 系统再登 B 系统）/ 登录返回 JSON | 直接实现 `SchoolApi`，抄 `NhjcxyApi` | 一个文件，含 2~4 个请求 |
| 走统一身份认证（CAS / OAuth / 金智 authserver） | 抄 `GzusApi`（CAS）或 `GdutApi`（authserver） | 中等，注意跨 host 会话 |

只有登录、暂时接不了课表也可以先提交：`fetchSchedule` / `fetchProfile` 有默认实现（回"还没接入"），
界面会显示明确提示而不是空着，**也不会崩**。

### 4.2 抓包：拿到三个值

用电脑浏览器打开学校教务系统 → `F12` → **Network** → 勾上 **Preserve log** → 登录一次 → 找那条
`POST` 且 `Content-Type: application/x-www-form-urlencoded` 的请求：

| 要拿的值 | 在哪儿看 | 例 |
|---|---|---|
| **Request URL**（登录地址） | 请求头 General | `https://jwxt.xxx.edu.cn/jwglxt/xtgl/login_slogin.html` |
| **用户名字段名** | Form Data 的 key | `yhm` / `username` / `userAccount` |
| **密码字段名** | Form Data 的 key | `mm` / `password` / `userPassword` |

再顺手确认三件事：

- 密码是**明文提交**还是密文（密文就看前端 JS 是 RSA / SM2 / AES / MD5）；
- 有没有**验证码**（每次都要，还是错了才要）；
- 登录成功后是 **302 跳首页** 还是 **200 + JSON**（决定 `judge()` 怎么写）。

> 抓包文件（HAR）里含姓名、学号、Cookie，属于个人信息。`web_fetch/` 已经在 `.gitignore` 里，**不要提交**。

### 4.3 三步接入（表单登录型）

**第 1 步：复制模板。** 把 `gr_api/schools/NhjcxyApi.kt`（或 `GzusApi.kt`）复制成 `XxxApi.kt`：

```kotlin
package com.tof.manycourse.gr_api.schools

import com.tof.manycourse.data.School
import com.tof.manycourse.gr_api.WebLoginSchoolApi

class XxxApi : WebLoginSchoolApi() {

    override val school = School(
        id = "xxx",                          // ★ 全小写英文短名，定了别再改（持久化主键）
        name = "XX 大学",
        baseUrl = "https://jwxt.xx.edu.cn",  // 必须带协议、不带结尾斜杠
        system = "正方教务系统 V-9.0",         // 仅备注，会显示在下拉副标题
    )

    override val loginUrl = "${school.baseUrl}/jwglxt/xtgl/login_slogin.html"
    override val usernameField = "yhm"
    override val passwordField = "mm"
}
```

**第 2 步：注册。** 在 `gr_api/SchoolRegistry.kt` 的 `apis` 里加一行（**顺序 = 登录页下拉顺序**）：

```kotlin
private val apis: List<SchoolApi> = listOf(
    GzusApi(),
    NhjcxyApi(),
    GdutApi(),
    XxxApi(),        // ← 加这里
)
```

**第 3 步：验证。**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SchoolRegistryTest*'
```

`SchoolRegistryTest` 会自动校验：id 唯一、`baseUrl` 必须 https 且不带结尾斜杠、清单与实现一一对应、
以及**已下架学校的旧 id 会被安全解析成 null**（登录页回落"未选择"而不是显示一个不存在的学校）。

到此为止 **UI 一行都不用改** —— 下拉、持久化、登录分发、姓名与课表同步都是自动的。

### 4.4 多步登录 / JSON 判定的学校

典型是金城学院：**一个域名下叠了两套系统，登录要过两道**。这种不要继承 `WebLoginSchoolApi`
（它把流程固定成"一次表单 POST + 关键字判定"了），直接实现 `SchoolApi`：

```
① GET  登录页 A         → 从页面里抠出公钥 / token
② POST 登录表单 A       → 判定（返回 JSON 就按 JSON 判）
③ GET  登录页 B         → 抠出 WebForms 的 __VIEWSTATE
④ POST 登录表单 B       → 判定（302 且不是跳回登录页 = 成功）
⑤ 之后才能访问业务页
```

三个必须照做的要点：

1. **一个 `HttpMethod` 贯穿全程**：两段登录的 Cookie 挂在同一个 host 上，共用一个实例才能把第一段的会话带进第二段；
2. **两段都成功才算成功**：只判第一段的话，用户会"登录成功但什么数据都拉不到"，比直接报错更糟；
3. **判定分开写**（`judgeMvcLogin` / `judgeLegacyLogin`）：返回 JSON 的系统**不能**套关键字启发式 ——
   对着一坨 JSON 找"用户名或密码错误"只会永远判成功。

### 4.5 接口契约速查

一所学校要提供什么（`gr_api/SchoolApi.kt`）：

| 成员 | 必须覆写？ | 作用 |
|---|---|---|
| `school` | ✅ | 学校纯数据（`data/School.kt`） |
| `configured` | 可选（默认 `true`） | `false` = 接口还没接好；用户会看到「接口未接入」而不是"密码错误" |
| `login(account, password, captcha?) { }` | ✅ | 登录；回调在**工作线程**，实现方保证只回调一次 |
| `fetchSchedule(account) { }` | 可选 | 拉课表 → `List<SchoolCourse>` |
| `fetchProfile(account) { }` | 可选 | 拉学生信息 → `StudentProfile`（**姓名在这里**） |
| `exportSession() / importSession() / clearSession()` | 可选 | 会话持久化三件套（继承 `WebLoginSchoolApi` 时已自带） |

`LoginResult` 是**多态**的，不是 `Boolean` —— 这样登录页才能给出准确提示：

| 状态 | 界面表现 |
|---|---|
| `Success` | 进主界面 |
| `Failure(message)` | 直接展示 `message`（密码错 / 网络不通 / 服务端异常） |
| `NeedCaptcha(message, captcha)` | 显示验证码图，用户填完得数再调一次 `login` |
| `Pending(message)` | 「这所学校还没接入」—— **不能退化成 `Failure`**，否则用户会一直以为是自己密码错了 |

调用方式（在页面里）：

```kotlin
SchoolRegistry.apiOf(schoolId)?.login(account, password) { result -> /* 工作线程！自己切主线程 */ }
```

### 4.6 密码加密：四种常见形态

覆写 `encodePassword()` 即可。四种都已经在 `api/` 里实现好并有单测：

| 形态 | 用哪个 | 坑 |
|---|---|---|
| 明文 | 默认（不用覆写） | —— |
| **裸 RSA**（正方：`c = m^e mod n`，无填充） | `RsaCipher.encryptToHex` | 用 `RSA/ECB/PKCS1Padding` 会填随机数，服务端解出垃圾字节，**表现和"密码错了"一模一样** |
| **金智 CAS 的 RSA** | `CasRsaCipher` | **小端 + 末尾补零**，和上面那个别混 |
| **国密 SM2** | `Sm2Cipher` | `C1C2C3` 顺序 + `04` 前缀；**有的学校连账号也要一起加密**（金城学院就是） |
| **AES**（金智 authserver） | `AuthserverAesCipher` | 明文是 `randomString(64) + 密码`，不是光密码 |
| MD5 等摘要 | 直接 `MessageDigest` | —— |

```kotlin
// 需要先取公钥的（正方）：在 prepare() 里 GET 登录页/公钥接口，再把密文塞进表单
override fun encodePassword(password: String): String {
    val modulus = publicKeyModulus ?: return password
    val exponent = publicKeyExponent ?: return password
    return RsaCipher.encryptToHex(password, modulus, exponent)
}

// 登录前要先拿 token（CSRF）：
override fun prepare(callback: (Result<Map<String, String>>) -> Unit) {
    http.getAsync(loginUrl) { page ->
        page.fold(
            onSuccess = { loadPublicKey(callback) },
            onFailure = { callback(Result.failure(it)) },
        )
    }
}
```

> `prepare` 的 `callback` **必须且只能调用一次**（成功/失败两条分支都要覆盖），否则登录页会一直转圈。
> 另外：**取验证码/公钥千万别在回调里阻塞等待另一个请求** —— 回调本身就跑在 OkHttp 的 dispatcher 线程上，
> 阻塞它再去发同 host 的请求，最坏会和"同一 host 最多 5 个并发"的限制撞成**死锁**。

### 4.7 验证码

框架已经支持，登录页的控件也加好了（`res/layout/activity_login.xml` 的
`@id/captchaBlock` / `@id/captchaImage` / `@id/EditCaptcha`，默认 `visibility="gone"`）。

实现方只需在服务端要验证码时返回：

```kotlin
callback(LoginResult.NeedCaptcha("请输入图片中算式的得数", LoginCaptcha(token, imageBytes, answer = "")))
```

登录页会显示图片，把用户填的得数塞回 `answer`，再调一次 `login(账号, 密码, captcha) { … }`。

> `captcha` 参数排在 `callback` **前面**且带默认值，是为了两种写法都顺手：
> 不需要验证码的学校照旧 `login(账号, 密码) { … }`（尾随 lambda）。
> 把它放最后会破坏尾随 lambda —— Kotlin 会把大括号当成 `captcha`。

### 4.8 拉课表与拉学生信息

```kotlin
override fun fetchSchedule(account: String, callback: (Result<List<SchoolCourse>>) -> Unit) {
    http.getAsync("${school.baseUrl}/xxx/xskb.do?su=$account") { result ->
        callback(result.mapCatching { XxxScheduleParser.parse(it.body) })
    }
}

override fun fetchProfile(account: String, callback: (Result<StudentProfile>) -> Unit) {
    http.getAsync("${school.baseUrl}/xxx/xsgrxx.html") { result ->
        callback(result.mapCatching { XxxPageParser.parse(it.body) })
    }
}
```

**把 HTML/JSON 解析拆到一个 `internal object` 里**（照 `NhjcPageParser` / `GzusScheduleParser` 抄）。
原因很实在：解析器失效时**不会报错**，只会表现为"课表空了 / 姓名没同步上"，
拆出来才能用单测把真实页面结构钉住（把抓包响应体存进 `src/test/resources/` 当样本）。

失败时的返回要遵守两条：

- 抛 **`SessionExpiredException`**（而不是普通 `IOException`）表示"登录态没了" ——
  上层据此提示"重新登录"而不是"重试"；
- 其他错误用**能直接展示的中文**（`readableMessage()` 会把 `UnknownHostException` 之类翻成人话）。

### 4.9 节次表（作息）是学校的事实

各校节数与作息**根本不一样**：广软、金城是 16 节 / 8 个两节块；广工是 **12 节 / 6 块**，而且它**块内两节的间隔并不相等**（5 / 5 / 5 / 15 / 25 / 5 分钟）——
所以节次表挂在 `School.timetable` 上，**跟着学校一起声明**：

```kotlin
override val school = School(
    id = "xxx", name = "XX 大学", baseUrl = "https://…",
    timetable = Timetables.default,   // 或自己写一张
)
```

> **拿不到准确作息就别编**：宁可 `Timetable.Block("", "")`（那一行的时间轴只画「第N-M节」，
> 落在这一块里的课也不排课前提醒），也不编一个"看起来很确定"的时刻骗人 —— 这是本项目的既定规矩。
> 拿到作息后**只改 `Timetables.xxx.blocks` 一处**即可：时刻、时间轴、课程详情、「下一节课」
> 与课前提醒会自动开始工作，别的代码一行都不用动。
>
> ⚠️ 块里**第二节的开始时刻**如果推不出来（也就是块内两节的间隔不相等），必须显式写
> `Block(start, end, secondStart = "…")`。广工就是这种情况：它的第 7-8 节隔 15 分钟、
> 第 9-10 节隔 **25** 分钟 —— 不写 `secondStart` 的话第 10 节会被算成 18:05（实际 18:30），
> 课前提醒就会早 25 分钟。那不是"不够精确"，是**把错误的时刻当成事实显示**。

### 4.10 「维持登录」：会话持久化

教务系统的登录态**完全在 Cookie 里**，所以"下次打开还是登录状态"只需要：

```
登录成功 → api.exportSession() 取出全部 Cookie
         → SessionBlob 打包（学校 id + 账号 + Cookie）
         → SessionVault 用 Keystore 密钥 AES-GCM 加密后落盘
冷启动   → 解密 → 解开 → api.importSession(Cookie) → 直接进主界面
```

| 情况 | 要做的 |
|---|---|
| 继承 `WebLoginSchoolApi` | **什么都不用做**，三件套已自带（`CookieCodec` 编解码） |
| 直接实现 `SchoolApi` | 照 `NhjcxyApi` 抄三行 |
| 长效凭据就在 Cookie 里（金智 `CASTGC`） | 照 `GdutApi`：`exportSession` = `CookieCodec.encode(http.exportCookies())`；续期 = 再 GET 一次认证登录页，看它跳不跳回目标系统 |
| 长效凭据在响应体里（广软 CAS 的 `TGT`） | 照 `GzusApi` + `GzusSessionCodec`：导出时多带一个字段，导入时灌回去，请求失败时**静默续期一次** |

**绝不存密码重放** —— 广软每次登录都要验证码，重放也过不去；存密码等于纯粹的风险。
存档之所以必须加密：它等价于**一张能以该用户身份访问教务系统的通行证**，
而教务系统里躺着身份证号、家庭住址、成绩。

### 4.11 提交前自查清单

- [ ] `id` 全小写英文、唯一、没改过老的 id
- [ ] `baseUrl` 带协议、不带结尾斜杠（`SchoolRegistryTest` 会拦）
- [ ] 在 `SchoolRegistry.apis` 里注册了（顺序 = 下拉顺序）
- [ ] `configured` 与实际情况一致（没接好就别写 `true`，否则用户看到的是"密码错误"）
- [ ] 回调**只调一次**，且不阻塞 OkHttp 线程
- [ ] 解析逻辑拆成独立 object 且有单测样本
- [ ] 会话过期抛 `SessionExpiredException`
- [ ] `.\gradlew.bat :app:testDebugUnitTest` 全绿
- [ ] 真机登录一次，用 Logcat 里的 `HttpLoggingInterceptor` 核对请求/响应

---

## 5. 地图添加指南（加校区地图）

底栏第 3 个 Tab「地图」显示**当前学校**的校园平面图。一所学校可以有**多个校区多张图**
（广工 5 个、广软 2 个），进页面取一次定位，挑**离得最近**的校区那张。

### 5.1 图片放哪儿：必须是合法资源目录

⚠️ **`res/` 下只认固定的一批目录名**（`drawable` / `layout` / `values` / `raw` / `xml` …），
其他名字 AAPT2 **整个忽略** —— 放到 `res/school_map/` 里的图在代码里根本取不到
（`R.drawable.xxx` 压根不会生成）。所以地图统一放：

```
app/src/main/res/drawable-nodpi/school_map_<学校id>[_<校区id>].jpg
```

两个细节：

- **`-nodpi`**：地图是"整张图缩放查看"的大图，放 `-nodpi` 表示不按屏幕密度放大。
  放普通 `drawable/` 会被 density bucket 放大 2~3 倍（1280×959 会变成 3800+ 像素），白吃内存；
- **资源名必须全小写 + 下划线**（`school_map_gdut_panyu`），不能有驼峰 / 连字符 / 中文 —— AAPT2 硬性要求。
  ⚠️ 拼错（比如把 `school` 写成 `shcool`）**不会报错**，只表现为"取不到图"。

### 5.2 登记校区（含坐标）

在 `data/SchoolMap.kt` 的 `campuses` 里加一行：

```kotlin
private val campuses: Map<String, List<SchoolCampus>> = mapOf(
    // 已有：gdut(5) / gzus(2) / nhjcxy(1)
    "xxx" to listOf(
        // ★ 第一个 = 主校区（没定位时兜底显示这张）
        SchoolCampus("main", "主校区", R.drawable.school_map_xxx, 31.7030146, 118.8790195),
        SchoolCampus("north", "北校区", R.drawable.school_map_xxx_north, 31.7412000, 118.8830000),
    ),
)
```

三条规则：

1. **键必须是 `SchoolRegistry` 里登记的学校 id**（拼错了只表现为"地图页说这学校没有地图"，有单测拦）；
2. **列表第一个是主校区**：没定位、没权限、拿不到位置时就用它 —— 所以顺序不能随便排；
3. **坐标不能抄成一样的**：那样永远只有列表第一个赢；`SchoolMapTest` 会校验"同校任意两校区至少隔开 2 km"。

### 5.3 坐标从哪来

- 取自 **OpenStreetMap** 的校区几何中心（`amenity=university` 那块的中心，误差几十米级，挑校区完全够用）；
- **不显示、不导航**，唯一用途是"按定位挑最近的那张图"，所以**不需要精确到门口**；
- OSM 上还没画多边形的新校区（如广软江门校区），就用**校门口的公交站点位**顶着
  （差几百米，对"挑校区"没有任何影响）。

### 5.4 定位与"显示哪张图"

判定逻辑是 `data/CampusPicker.kt` 里的**纯函数** `pickCampus(campuses, manualCampusId, location)`，
所以能直接单测。优先级从高到低：

1. **用户手选的校区**（压过定位结果 —— 定位可能飘到隔壁校区，而"我看哪张图"用户一眼就能判断）；
2. **定位最近的校区**（`SchoolMap.nearestCampus`）；3 km 以内才敢说"你就在这个校区"，否则文案退成"最近的是…"；
3. **主校区**（没权限 / 还没定位 / 定位失败都走这里）。

> 手选的校区 id **换学校后自动作废**：否则从广工的"东风路校区"切到广软，
> 会拿一个广软没有的 id 去查，页面变成空白。

取位置：`ui/CampusLocation.kt` 的 `currentLocation()` —— 系统 `LocationManager.getCurrentLocation()`，
**8 秒超时**，provider 顺序 `fused → network → gps`（只有精确权限时才带 `gps`，否则会抛 `SecurityException`）。

权限方面：`ACCESS_COARSE_LOCATION` + `ACCESS_FINE_LOCATION` **两条都声明、都申请**
（Android 12 起用户可以在弹窗里只给"大致位置"，只声明 FINE 的话那种情况什么都拿不到）；
**刻意不声明**后台定位 —— 只在地图页前台取一次。

### 5.5 地图页交互

- 双指缩放（1×~6×）、拖动、双击复位，放大后右下角出现「复位」按钮；
- 缩放位移走 `graphicsLayer`（绘制阶段），不改尺寸 → 不触发每帧重新布局；
- 位移**边界收拢**：最多拖到"图片边缘贴住容器边缘"，避免"地图被拖没了、看起来像加载失败"；
- **换校区时缩放与位移重置**（`key(campus.id)` 重建），否则新图会继承上一张的放大倍数与偏移。

### 5.6 校验

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*SchoolMapTest*' --tests '*CampusPickerTest*'
```

`SchoolMapTest` 校验：键都是真实学校 id、每个校区的名字 / 资源 id / 坐标非空、
经纬度没写反（落在中国范围内）、同校两校区至少隔开 2 km、未知 id 安全返回 `null`。

> **没登记地图的学校不会崩**：地图页显示「「XX 大学」还没有地图」+ 怎么加，而不是一片空白。

---

## 6. 节假日数据（放假当天的课自动不打亮）

### 6.1 它解决什么问题

教务系统的"按周课表"是**按教学周次**排的，它**不知道国庆节是哪几天**：
第 5 周的周三该有什么课，它就返回什么课 —— 于是 10 月 1 日那天，
App 上照样亮着一整天的课，看着像要照常上课。

这个功能就是补上这一层：**放假那天的课照旧列在课表上，但一门都不点亮**（灰着），
并明确告诉你为什么：

```
课表页                                     日历页
┌──────────────────────────────┐         ┌──────────────────────────────┐
│ 休 中秋节放假 · 当天的课都不上   │         │ 9月25日 休  26  27            │  ← 日期旁挂「休」
│ 周五 · 3 门课程                │         │ 休 中秋节放假 · 当天的课都不上   │
│ ▏高等数学（灰着）              │         │ ▏高等数学（灰着）              │
│ ▏大学英语（灰着）              │         │ ▏大学英语（灰着）              │
└──────────────────────────────┘         └──────────────────────────────┘
```

**为什么是"不点亮"而不是"不显示"**：把放假的课从数据里删掉的话，卡片会直接消失，
用户看到的是"课表空了"——分不清是放假、还是同步挂了、还是自己没选课。
灰着则一眼就知道"这天排了课、但今天不上"，而且**不需要另教一套图例**：
周视图下方那行说明写的就是「亮 = 本周会上 · 灰 = 本周不上」，放假只是"上不上"的另一种情形。

判据只有一处（`data/CourseEntries.kt` 的 `dayIsLit`），课表页、日历页、
周视图网格、月历圆点全都问它 —— 所以不会出现"课表页说放假、日历上还亮着"。

| 日子 | 课表 | 界面 |
|---|---|---|
| **放假**（如 10 月 1 日） | 当天的课**照旧列出，只是不点亮**（灰着） | 日期旁标「休」+ 一行说明 |
| **调休上班**（如 10 月 10 日周六） | **照常点亮**，一个字不动 | 日期旁标「班」+ 一行说明 |
| 表外年份（还没收录） | **照常点亮**（宁可不置灰，也不猜） | 设置页如实写"已收录 2025-2026 年" |

设置页有「节假日自动置灰」开关（默认开）。这是**唯一一处 App 覆盖教务系统课表**的地方，
所以必须留一个退回去的口子：学校真有假期补课、或者就想让放假当天和平时长得一样时关掉它即可 ——
关掉之后连那行说明也一起消失（既不置灰，也不解释）。

> ⚠️ **别把它做成"把课删掉"**：试过，很难用 —— 课程卡片直接消失，用户只会觉得课表坏了。
> 课表页、日历页、周视图三处都走 `dayIsLit(date)` 这一个判据，改口径只改它一处。

### 6.2 数据从哪来：内置表，不联网

表在 **[`data/HolidayCalendar.kt`](app/src/main/java/com/tof/manycourse/data/HolidayCalendar.kt)**，
数据源是**国务院办公厅的通知原文**（一年一份，每年 11 月发下一年的）：

| 年份 | 文件 |
|---|---|
| 2025 | 《国务院办公厅关于2025年部分节假日安排的通知》（2024-11-12） |
| 2026 | 《国务院办公厅关于2026年部分节假日安排的通知》（2025-11-04） |

为什么内置而不查接口：

- 节假日是**一年一次、发文定死的常量**，不是每天都在变的实时数据 ——
  联网查等于为一个常量引入一个可能挂掉的服务；
- 断网、教务系统挂了的时候，标记节假日本来就该照常工作（它和"课表从哪来"没关系）；
- 与本项目其余部分同一条规矩：**拿不到权威数据就别编**。所以只认已发文的年份，
  不拿"一般是 10 月 1 日放假"去猜没发文的年份。

### 6.3 只置灰放假，不猜补课

通知里只说了两件事：哪些日子放假、哪些日子（原本是周末）要上班。
**"调休那天补哪一天的课"通知里没有**，各校通知里才有（"10月10日按10月9日（周五）课表上课"），
而且各校口径不一（有补周五的、有补周一的）。

所以本项目：

- 放假 → 当天的课**照旧列出、但不点亮**（通知说得清的就这一件）；
- 调休上班 → 只在日子上标一个「班」，**课表一个字不改**。

不猜的理由很简单：猜错的代价是**凭空造出一门那天不上的课**（人会白跑一趟教室），
而少标一个「班」顶多少一句提示 —— 两者不对等。

### 6.4 加新一年：每年更新一次

每年 11 月国务院发完下一年的通知后，在 `HolidayCalendar.spans` 里**加一组即可**，
别处一行都不用改（年份、覆盖范围提示都是从这张表现算的）：

```kotlin
// ══════════════ 2027 年（国务院办公厅 2026-11-XX 通知 全文照抄）══════════════
// 一、元旦：1月1日（周五）至3日（周日）放假调休，共3天。1月4日（周一）上班。
rest("元旦", "2027-01-01", "2027-01-03"),
work("元旦", "2027-01-04"),
```

两条硬规矩：

1. **原文照抄**，并且在注释里带上通知的原话（"共N天""（周X）"）——
   单测会拿天数与星期逐条核对，写错一个字就会被拦下来；
2. **`work(...)` 给的是调休上班日**（必然落在周六/周日），别和放假区间写反 ——
   写反了就是"周六放假、周日上课"，单测同样会拦。

同时把本节表格里的年份、以及 `app/src/test/.../HolidayCalendarTest.kt` 里的
`rest2025` / `rest2026` / `work2025` / `work2026` 各加一组。

### 6.5 校验

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*HolidayCalendarTest*' --tests '*HolidayNoticeTest*' --tests '*CourseEntriesTest*'
```

| 测试 | 拦什么 |
|---|---|
| `HolidayCalendarTest` | 天数与通知不符、起止日周几对不上、调休上班日不是周末、放假日与上班日重叠、节假日前后一天被误判、表外年份被误置灰 |
| `HolidayNoticeTest` | 提示文案分不清"放假/调休"、说成"已滤除"（课其实还列着），或长得像「共 N 门课程」（会干扰真机上的两页对账测试） |
| `CourseEntriesTest` | 口径被改回"把课删掉"（放假那天取值必须照旧有课）、放假/调休/表外年份的亮灭判定、以及那个开关真的能关掉置灰 |

---

## 7. 桌面小组件与课前提醒

这两个功能回答的是**同一个问题**：「下一节课是什么、什么时候上」。
所以它们共用**唯一一处**取值口径 —— `data/NextClass.kt`：它自己不另算一套课表，
只在 `data/CourseEntries.kt` 的 `coursesOfDate()`（课表页 / 日历页 / 我的页同源）
给出的课表上，回答"从这一刻起的下一门要上的课是哪一门"。

```
        coursesOfDate(date)          ← 某天上什么课（唯一取值口径）
        dayIsLit(date)               ← 这一天到底上不上（唯一判据）
                ↓
        NextClass.resolve(...)       ← 「下一节课」（纯函数，有单测钉住）
          ├─ widget/    → 2x2 桌面小组件
          └─ reminder/  → 课前提醒（AlarmManager 单条链式调度）
                ↓
          notify/       → 展示档位：标准通知 / 焦点通知 / 超级岛
```

### 7.1 2x2 桌面小组件（下一节课）

| 项 | 值 |
|---|---|
| 尺寸 | **2x2**（`minWidth/minHeight = 110dp` + `targetCellWidth/Height = 2`，两条都写才稳） |
| 渲染 | `AppWidgetProvider` + **`RemoteViews`**（不用 Glance：2x2 用不上，还多一个依赖） |
| 刷新 | 系统每 30 分钟一次 **+ App 内数据变化时主动刷**（见下） |
| 点击 | 打开 App 的课表页（走 `MainActivity`，冷启动时它才会按登录态把启动链走对） |

**刷新时机**是最容易做漏的一环：系统只知道"每 30 分钟叫醒你一次"，
而"课表刚同步完""用户刚退出登录""课前提醒刚响过"这三个时机系统**完全不知道**。
所以渲染只有一份实现（`widget/WidgetRefresh.kt` 的 `updateAll`），
由这几处一起调 —— 各写各的话，迟早出现"点进 App 是对的、桌面上还停在上周"。

**四种状态必须分开显示**（混成一句"暂无课程"，用户分不清是课表空了还是 App 坏了）：

| 状态 | 文案 |
|---|---|
| 没有可信课表数据（没登录 / 从没同步过） | 打开 App 同步课表 |
| 有数据，但往后七天都没课 | 今天的课都上完了 |
| 这节正在上 | 正在上课 |
| 正常 | 课程名 / 第N-M节 / `9:00~10:20` / 教室 + 「还有 N 分钟」 |

> ⚠️ **`CourseRepository.init` 里预置了 7 门演示课**（高等数学、大学英语…）。
> 没有登录态时 `coursesOfDate()` **照样会把它们返回**，所以小组件必须
> **先问"有没有可信数据"、再问"下一节课是什么"** —— 顺序反了，
> 一个从没登录过的手机上会理直气壮地显示"高等数学"，而用户没有任何办法知道那是假的。
>
> 这个洞是**真机测试里踩出来的**：`connectedDebugAndroidTest` 跑完会卸载应用，
> 重装后没有会话，小组件照样显示出了"高等数学"。
> 判据是 `UpcomingClass.hasScheduleData()`（磁盘上有这个账号的课表快照，
> 或本次进程成功拉到过教学周 —— 两者都只可能由教务系统的数据满足），
> 并有真机插桩测试钉住（`ClassReminderDeviceTest`）。

小组件**没有任何常驻资源**（不注册监听、不起服务、不持 wake lock），
所以"最后一个实例被删掉"不需要清理，`onEnabled` / `onDisabled` / `onDeleted` 都刻意不覆写。

### 7.2 课前提醒

- **只排一条闹钟**：算出"下一次该提醒的时刻" → 登记一条 → 触发时发通知
  **并用最新课表重算下一条**。比"每节课一条"或 WorkManager 轮询都省电，
  而且课表怎么变都不会留下孤儿闹钟（漏撤一条 = 上一份课表把你叫起来）。
- 提前量 **5 / 10 / 15 / 20 / 30 分钟可选**（默认 15），存在 `UiSettings`。
  读盘和落盘都过一遍 `coerceLeadMinutes` —— 这个值要跨版本存活，
  读出 `0` 的后果不是崩溃，而是**"课前 0 分钟提醒"**（课都开始了我才通知你，等于没通知），
  而且界面上看不出来哪里坏了。
- **重排时机是刻意冗余的**，任意一条失效都不会让提醒永远失效：
  进程每次启动（`ManyCourseApp.onCreate`）、开机 / 改时间 / 换时区 / 应用更新
  （`RescheduleReceiver`）、上一条闹钟醒来、回到主界面（`ManyCourseMain.onResume`）、
  改开关或提前量。其中**只有"进程启动"是每次必到的** —— 用户点开一次 App 就恢复。
- **闹钟醒来时会重新解一遍课表再决定发不发**：登记时的那份课表到点可能已经不作数了
  （刚同步过 / 学校临时调课 / 这天变成了法定假日）。所以不会发出与当前课表不符的提醒。
- **同一节课只提醒一次**：用"这节课的稳定身份"（`日期|节次|课名`）落盘记住"已经提醒过"。
  不加这一步会死循环：醒来 → 发通知 → 重排 → 解出来的还是这节课 → 立刻再响一次。
  这个标记**必须落盘**（闹钟触发时进程往往是刚被拉起来的），
  并且**退出登录时必须清掉**，否则下一个账号的第一节课只要恰好同天同节次同名就会被静默跳过。

**权限**（两条都可降级，任一不给都**不影响课表本身**）：

| 权限 | 不给的后果 | 处理 |
|---|---|---|
| `POST_NOTIFICATIONS`（Android 13+） | 提醒发不出来 | 设置页如实显示"未授予"，并给「去授权」入口 |
| `USE_EXACT_ALARM`（安装即得）+ `SCHEDULE_EXACT_ALARM` | 只能用不精确闹钟，提醒可能晚几分钟 | `canScheduleExactAlarms()` 为 false 时自动降级成 `setAndAllowWhileIdle`，**并在设置页如实写明当前精度** |

> `USE_EXACT_ALARM` 在 Google Play 上只允许**闹钟 / 日历**类应用申报。
> 本应用是课表 / 日历，且**侧载分发**（见第 9 章），所以可以接受；
> **要上架 Play 的话这一条得先去掉** —— 与 `MANAGE_EXTERNAL_STORAGE` 是同一条取舍。

### 7.3 一条容易漏、代价很大的规则：**放假日整天跳过**

本项目对法定放假日的处理是「课照旧列在课表上，但**一门都不点亮**」——
意思是**那天的课不上**。所以"下一节课"问到放假日时，正确回答是**跳过这一整天**
去问下一天，而不是把那天第一门课当成待上的课。

漏了这条的表现很具体：**国庆节早上七点，提醒把用户叫去教室**。
`data/CourseEntries.kt` 里原有的 `nextCourseKey()` 只回答"当天高亮哪一门"、
不看放假（那是课表页的职责），所以这条规则在 `NextClass` 里补上，并配了单测。

### 7.4 小米超级岛 / 焦点通知：三档展示增强

```
标准 Android 通知                        ← 所有机型、任何情况下都建好（基线）
  ├─ 档位 = Island → hyperisland_kit     ← 第三方社区库（小米 + 系统声明支持岛）
  ├─ 档位 = Focus  → miui.focus.param    ← 小米焦点通知（OS2 起）
  └─ 档位 = Standard → 什么都不加          ← 非小米机型：不掺任何私货
```

三层是**叠加**而不是替换：增强只是往同一条通知的 `extras` 里多塞几个键，
所以哪怕系统完全不认这些参数，用户收到的仍然是一条正常的课前提醒。
超级岛那一层包在 `runCatching` 里，失败就**降级成焦点通知** ——
它是全项目唯一依赖第三方非官方协议的代码路径，不能带走"课前提醒"这个功能
（这正是需求里"试了不行就改成焦点通知"那条回退）。

档位由**运行时探测**决定（`notify/VendorCapability.kt`，**不按机型硬编码**）：

| 探测什么 | 怎么问 | 用途 |
|---|---|---|
| 是不是小米系（小米 / Redmi / POCO） | `Build.MANUFACTURER` + `Build.BRAND`（**两个都看**） | 决定要不要走小米私有参数 |
| 焦点通知协议版本 | `Settings.System["notification_focus_protocol"]`（0/1 = 不支持，2 = OS2，3 = OS3） | 决定焦点通知档 |
| 系统是否声明支持岛 | `persist.sys.feature.island`（反射读，小米官方示例就是这么写的） | 与上一项同时成立才可能上岛 |

`MIN_FOCUS_PROTOCOL` 定在 **2**：官方 Q&A 明确说 OS1 当前阶段不建议接入，
所以协议版本 1 对我们等于"不支持"。

#### ⚠️ 不承诺项（这一段比上面那张表重要）

- **官方准入拿不到。** 小米超级岛 / 焦点通知是**受平台准入控制**的 OEM 能力：
  官方接入流程要求企业开发者认证、逐场景预审 + 正式审核、配置证书指纹、
  **设备白名单（30 天 / 最多 10 台）**，上线验证后才有正式权限。
  本项目是**个人侧载**（见第 9 章），走不通这套流程。
- 所以"上岛"这一档是**尝试**，不是保证：能否显示由**系统版本 + 设备能力 + 用户设置 +
  小米平台权限**共同决定。设置页因此不写"已开启超级岛"，而是把探测到的**原始事实**
  摆出来（机型 / 协议版本 / 岛属性 / 最终档位），让用户自己看到"这台机器为什么是这一档"。
- **实测记录**（Redmi `22101316UCP`，HyperOS **OS2.0** / `V816` / Android 14）：

  | 探测项 | 实测值 |
  |---|---|
  | `Build.MANUFACTURER` / `Build.BRAND` | `Xiaomi` / `Redmi` |
  | `notification_focus_protocol` | `2`（OS2） |
  | `persist.sys.feature.island` | **不存在** |

  → 社区库 `HyperIslandNotification.isSupported()` 判为 **false**
  （它同时要求"是小米"、"有焦点通知权限"、"`persist.sys.feature.island` 为真"，
  而该库的文档还要求 HyperOS 3+）→ 落 **焦点通知** 档。
  也就是说在这台机器上，超级岛这条路**确实走不通**，回退的焦点通知是可用的。

#### 焦点通知参数为什么自己拼

社区库 `hyperisland_kit` 的 `buildResourceBundle()` **一进门就查 `isSupported()`**，
不满足就直接返回空 Bundle —— 它的定位是"超级岛模板库"，不是"焦点通知库"。
而焦点通知这一档恰恰是**最需要它可靠**的一档（超级岛试不成之后的回退路线），
所以只按小米官方开发指南的字段自己拼（`notify/FocusParam.kt`），
并对**整体结构与转义**都配了单测：这段 JSON 写错**不会报错**，只会静默失效，
而且值没转义时只在"课程名里带英文双引号"这类特定数据上才出问题，极难查。

### 7.5 已知限制：**没收录作息的节次不提醒**

判定是**逐块**的（`Timetable.Block.start` 为空 = 这一块还没收录作息），取舍与 [4.9](#49-节次表作息是学校的事实) 一致：

- **小组件**照旧显示这一节课：写「第1-2节」而不是时刻，状态行退化成
  「今天 / 明天 / 周三」（不写倒计时，也不写一个编出来的时刻）；
- **落在这一块里的课不排课前提醒** —— 没时刻就没法定闹钟，随便挑一个时刻等于编一个
  "看起来很确定"的时间骗人。

> 目前**三所已接入学校的作息都已收录**，所以这条规则暂时没有实例 ——
> 但它是项目的底线规矩，不是"某个学校的临时状态"：加新学校时拿不到作息就照上面留空，**别编**。

### 7.6 校验

```powershell
# 纯逻辑（下一节课 / 提醒时刻 / 提前量档位 / 展示档位判定 / 焦点通知 JSON）
.\gradlew.bat :app:testDebugUnitTest --tests '*NextClassTest*' --tests '*ReminderPlanTest*' `
  --tests '*ReminderLeadTest*' --tests '*VendorCapabilityTest*' --tests '*FocusParamTest*'

# 真机（能力探测 / 通知真的发出来且挂上焦点参数 / 小组件渲染）
.\gradlew.bat :app:connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.tof.manycourse.ClassReminderDeviceTest
```

| 测试 | 拦什么 |
|---|---|
| `NextClassTest` | 放假当天没跳过、没有作息表却编出时刻、三节连排第二节课就被判成"上完了"、没有数据却编出课来 |
| `ReminderPlanTest` | 提醒时刻算错、开课之后还在发通知、同一节课的身份不稳定（会导致重复提醒或静默漏提醒） |
| `ReminderLeadTest` | 提前量落在档位之外（"课前 0 分钟提醒"= 等于没提醒，且界面看不出来） |
| `VendorCapabilityTest` | 非小米机型被塞小米私有参数、档位与探测到的事实不自洽 |
| `FocusParamTest` | 字段名 / 结构写错、值没转义（坏 JSON 一样静默失效，且只在特定课程名上出现） |
| `ClassReminderDeviceTest`（真机） | 通知根本没发出来、`miui.focus.param` 没挂上去、小组件拿预置的演示课当真实课表 |

> ⚠️ `connectedDebugAndroidTest` 跑完 **AGP 会卸载应用与测试包**（连同登录态和课表缓存）。
> 想跑完留着，加 `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`。

## 8. 构建

### 8.1 环境要求

| 需要 | 版本 | 备注 |
|---|---|---|
| JDK | **17 或以上**（本项目在 JDK 21 / 25 上验证过） | Gradle 9 + AGP 9 的要求 |
| Android SDK | **Platform 37** + Build-Tools 36 | `sdkmanager "platforms;android-37" "build-tools;36.0.0"` |
| Gradle | **不用装** | 用仓库自带的 wrapper（9.5.0，带 SHA-256 校验） |
| Android Studio | 最新稳定版（可选） | 直接打开根目录即可，会自动生成 `local.properties` |

`local.properties`（**已 gitignore，不要提交**）里写本机 SDK 路径：

```properties
sdk.dir=D\:\\DevelopEnvironment\\AndroidSDK
```

### 8.2 命令行构建

```powershell
# Debug 包（可直接安装）
.\gradlew.bat :app:assembleDebug
#  → app/build/outputs/apk/debug/app-debug.apk

# Release 包（开启 R8 优化；没配签名时产出 *-unsigned.apk）
.\gradlew.bat :app:assembleRelease
#  → app/build/outputs/apk/release/app-release.apk

# 清理
.\gradlew.bat clean
```

> **性能请以 release 包为准**：debug 包带着大量调试断言且未优化，Compose 的流畅度差别很明显。
> 顺带一提，`org.gradle.configuration-cache=true` 已开启，第二次构建会快很多。

### 8.3 运行测试

```powershell
# JVM 单测（解析器 / 加密 / 选校区 / 节次表 / 仓库，秒级）
.\gradlew.bat :app:testDebugUnitTest

# 只跑某一组
.\gradlew.bat :app:testDebugUnitTest --tests '*GdutScheduleParserTest*'

# 真机插桩测试（需要连设备，含 UI 流程与实弹登录，会真的访问教务系统）
.\gradlew.bat :app:connectedDebugAndroidTest
```

单测报告：`app/build/reports/tests/testDebugUnitTest/index.html`。
CI 会把报告与失败截图一起上传成 artifact。

### 8.4 签名与发布包

签名信息**不写进仓库**，从 Gradle 属性读：

```powershell
# 1) 生成密钥（只需一次；生成的文件已在 .gitignore 里，别提交）
#    注意：JDK 20+ 的 keytool 默认生成 PKCS#12（.p12），JKS 已不再可创建 —— 所以不要加 -storetype JKS
keytool -genkeypair -v -keystore release.p12 -alias manycourse `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=ManyCourse, O=YourName, C=CN"

# 2) 带签名构建
.\gradlew.bat :app:assembleRelease `
  -Pmanycourse.storeFile=release.p12 `
  -Pmanycourse.storePassword=你的口令 `
  -Pmanycourse.keyAlias=manycourse `
  -Pmanycourse.keyPassword=你的口令
```

也可以在 `~/.gradle/gradle.properties` 里长期配置（不进仓库）：

```properties
manycourse.storeFile=C:/keys/release.p12
manycourse.storePassword=…
manycourse.keyAlias=manycourse
manycourse.keyPassword=…
```

**没配密钥也照样能构建**：`assembleRelease` 会产出 `app-release-unsigned.apk`
（`zipalign` 之后的未签名包**不能直接安装**）。CI 里则由 GitHub Secrets 提供口令，见下一章。

### 8.5 常见构建问题

| 现象 | 原因 / 处理 |
|---|---|
| `SDK location not found` | 缺 `local.properties`，或 `sdk.dir` 路径不对（Windows 上冒号要转义成 `D\:\\…`） |
| `Failed to find Platform SDK with path: platforms;android-37` | SDK 里没装 Platform 37，`sdkmanager "platforms;android-37"` |
| `Unsupported class file major version` / AGP 报 JDK 版本 | Gradle 用的 JDK 太老。设 `JAVA_HOME` 指向 JDK 17+（Android Studio 里改 `Gradle JDK`） |
| Compose 编译期卡住 / OOM | 调 `gradle.properties` 的 `org.gradle.jvmargs=-Xmx2048m`（机器内存小就降到 1536m） |
| 登录点不动 / 报 `CLEARTEXT` | `targetSdk ≥ 28` 默认禁明文 http；正常路径已被 `UpgradeCleartextInterceptor` 升级成 https，若对方**只支持 http**，见 `docs/教务API接入与调用指南.md` §7 |
| 地图页说"这学校没有地图" | 学校 id 或资源名拼错（不报错，只是查不到）→ 见 [5.2](#52-登记校区含坐标) |

---

## 9. CI/CD 自动构建

工作流：**[`.github/workflows/android-ci.yml`](.github/workflows/android-ci.yml)** —— 内置了 `gradlew` 与完整签名流程，不需要任何额外插件。

### 9.1 两个 Job 分别做什么

| Job | 触发条件 | 做什么 | 产物 |
|---|---|---|---|
| **`build`**（CI） | push 到 `master`/`main`、任何 PR、手动 | 跑全部 JVM 单测 → 出 `app-debug.apk` + **未签名** `app-release-unsigned.apk` | workflow artifact（保留 14 天） |
| **`release`**（CD） | 推 `v*` 标签（如 `v1.0.0`）、手动（可指定 tag） | 用 Secrets 签名出正式 APK → 自动创建 **GitHub Release** 并附上 APK | Release 附件 |

> 没有部署 Pages 的 job：下载页是纯静态的，由 Pages 的「Deploy from a branch」直接发布，
> 不需要 CI 参与（详见 [9.4](#94-下载页indexhtml)）。

细节上做到了这几点：

- **Gradle 缓存**：缓存 `~/.gradle/caches` 与 `~/.gradle/wrapper`，命中时构建从数分钟降到一分钟级；
  缓存失效路径也接了 `gradle/actions/setup-gradle` 的依赖图，改依赖时会重算；
- **JDK 21** 显式安装（AGP 9 的最低要求之上），并启用 GitHub Actions 的 `gradle` 缓存开关；
- **签名注入**：由 Secrets 生成 `release.jks` 与 `~/.gradle/gradle.properties`，
  构建完**立即删除密钥文件**；没有 Secrets 时自动跳过签名，产出未签名包（不会失败）；
- **测试报告**：无论成功失败都上传 `app/build/reports/tests/`，失败时能在 Actions 页面直接看是哪条断言挂了；
- **版本号**：Release 的 `versionName` 取自 git tag（`v1.0.0.4` → `1.0.0.4`），`versionCode` 用 run number ——
  这样每个 tag 产出的包版本号都不一样，不会出现"装不上、提示已安装更高版本"
  （本地默认值是四位版本号 `1.0.0.3`，CI 只在 `-PversionName=` 传参时覆盖它）；
- **权限最小化**：只申请 `contents: write`（发 Release），其余按默认只读 ——
  不再需要 `pages: write` / `id-token: write`（没有部署 Pages 的 job 了）。

### 9.2 配置签名 Secrets

在 GitHub 仓库 → **Settings → Secrets and variables → Actions** 里加四个 Secret：

| Secret | 值 |
|---|---|
| `SIGNING_KEY_STORE_BASE64` | 密钥文件的 Base64：`[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.p12"))`（Linux/macOS：`base64 -w0 release.p12`） |
| `SIGNING_STORE_PASSWORD` | keystore 口令 |
| `SIGNING_KEY_ALIAS` | 密钥别名（如 `manycourse`） |
| `SIGNING_KEY_PASSWORD` | 密钥口令 |

> 不配也能跑：`release` 任务会输出**未签名**的 `app-release-unsigned.apk`，能验证构建是否通过，
> 但那个包不能直接安装到手机上。

### 9.3 打一个正式版

```powershell
git tag v1.0.0.4        # 版本号建议沿用四位规则：大版本.小版本.Bug数.学校数
git push origin v1.0.0.4
```

推送标签后 CI 自动：跑测试 → 签名构建 → 建 Release → 上传 `ManyCourse-1.0.0.4.apk`。
下载页会**自动**把最新 Release 里的 APK 找出来挂到下载按钮上 —— 那是页面在浏览器里
运行时调 GitHub API 拿的，跟 CI 无关，无需手工改版本号或链接。

> 只想构建不发布：Actions 页面选 **Android CI/CD** → **Run workflow**，标签留空即可
> （只跑 `build`，产出 artifact）。

### 9.4 下载页（index.html）

[`index.html`](index.html) 是一个**单文件、零依赖**的项目介绍 + 下载页：

- 介绍功能、已接入学校、技术栈、构建方式与安装步骤；
- 下载按钮**不写死链接**：页面用 `fetch` 调 GitHub Releases API 拿最新 release 的 APK 资源，
  自动显示版本号、文件大小与发布时间；还没发过 Release、或 API 被限流 / 网络不通时，
  回落到"去 Releases 页面 / 从 Actions artifact 下载"的静态链接，并给出原因提示；
- 仓库名、分支、APK 命名规则集中在一个 JS 配置块里（文件顶部 `CONFIG`），改一处即可；
- 深浅色自适应、移动端适配、`prefers-reduced-motion` 尊重、无外部字体 / 框架 / CDN ——
  把文件丢到任何静态托管都能用。

**部署到 GitHub Pages（一次配置）**：仓库 → **Settings → Pages** → Source 选 **Deploy from a branch**
（分支 `master`、目录 `/ (root)`）。

页面是**纯静态**的：下载按钮在浏览器里 `fetch` GitHub Releases API 拿最新 APK，
所以 GitHub 每次从分支发布出来的就是最新页面，**不需要 CI 参与** —— workflow 里因此
**没有** `pages` job（原来那个要求 Source 是「GitHub Actions」，而那是必须人工点一次的
仓库级设置：`GITHUB_TOKEN` 无权创建/修改 Pages 站点，所以它只会让每次 push 白红一次）。

线上地址按仓库名推得：

```
https://ksayus.github.io/--ManyCourse/
```

> 不需要额外的 `gh-pages` 分支，也不用把 `index.html` 挪进 `docs/`。

### 9.5 常见 CI 失败

| 报错 | 处理 |
|---|---|
| `SDK location not found` | 不要提交 `local.properties`；CI 是靠 runner 自带的 `ANDROID_HOME` 工作的 |
| `Failed to find package 'platforms;android-37'` | 包名错了：API 37 起平台按**次版本**发布，叫 `platforms;android-37.0`（装完就是 `platforms/android-37.0/`）。workflow 现在会自己从仓库索引里挑 `platforms;android-37.x`，不用手写 |
| `Permission denied: ./gradlew` | 仓库里的 `gradlew` 没有可执行位（在 Windows 上提交的常见坑）：已在仓库里改成 `100755`（`git update-index --chmod=+x gradlew`），workflow 里也把 `chmod +x gradlew` 放在所有 `./gradlew` 之前兜底 |
| Release 里没有 APK | 没配签名 Secrets，或 tag 不是 `v*` 形式（`v1.0.0` 才对，`1.0.0` 不触发） |

---

## 10. 延伸文档

| 文档 | 讲什么 |
|---|---|
| `docs/教务API接入与调用指南.md` | 接口层的完整手册：抓包方法、每所学校的实测链路、踩坑清单、会话与地图细节 |
| `docs/UI使用文档.md` | 数据模型与字段口径、UI 侧的对接点 |
| `docs/UI架构与实现指南.md` | Compose 侧架构、玻璃令牌体系、性能清单 |
| `docs/开发会话记录-UI重构.md` | UI 重构的过程记录（"这东西是怎么做出来的"） |
| [`index.html`](index.html) | 项目介绍 + APK 下载页（GitHub Pages） |

> ⚠️ **注意**：`docs/` 整个目录在 `.gitignore` 里（它记录的是开发过程与真机抓包细节，不随仓库分发），
> 所以上面几份文档**在你的工作副本里才有**，从 GitHub 克隆下来的仓库里没有。
> 需要给别人看的内容应当写进本 README；`docs/` 里的路径引用只对本地开发有效。

---

## 11. 免责声明

- 本项目只是把**用户自己的账号**在教务系统里的数据取回来显示到本机，**不提供任何数据给第三方**，
  也不做服务端转发；
- **不存储账号密码**：登录态以加密的 Cookie 存档保存在本机（Android Keystore + AES-GCM），
  退出登录会连同课表一起清掉；
- 各校教务系统的接口地址、表单字段与页面结构属于对方系统，**可能随时变更**导致同步失败 ——
  这类失效只会表现为"课表空了 / 提示登录过期"，不会损坏任何数据；欢迎提 Issue 或 PR 修好；
- 请遵守你所在学校的网络与信息系统使用规定，本项目仅供个人学习与开发交流使用。

---

<p align="center">
  <sub>ManyCourse · 课多多 —— <i>本周课表，一目了然。</i></sub>
</p>
