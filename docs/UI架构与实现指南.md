# ManyCourse UI 底层开发指南（架构 / 状态 / 渲染 / 性能）

> 面向要**改 UI 实现本身**的开发（不是改业务数据）。
> 姊妹文档：`docs/UI使用文档.md`（面向后端，讲数据模型与对接点）。
> 本文所有"实测"数字都来自 Android 14 真机（1080×2400 / 440dpi）逐像素采样，不是估算。

---

## 1. 技术栈与结构

| 项 | 取值 | 备注 |
|---|---|---|
| 语言 / 构建 | Kotlin 2.2.10、AGP 9.3.2、Gradle 9.5 | Compose 编译器插件随 Kotlin |
| UI | **Compose（BOM 2026.02.01）+ 少量 XML** | 登录页是 XML View，主界面全是 Compose |
| 最低 / 目标 | minSdk **31**、targetSdk / compileSdk **37** | 31 是硬前提：RenderEffect / Haze 的实时模糊依赖它 |
| 模糊 | Haze **1.7.3** | 只用 `hazeSource` + `hazeEffect`，不用 RenderScript |
| 导航 | **无 Navigation 组件** | Fragment `add/hide/show` 手工切换 |
| 持久化 | **SharedPreferences**（`data/UiSettings.kt` / `data/LoginSettings.kt`） | 项目没有 DataStore，未额外引入；两者都在 `ManyCourseApp.onCreate` 幂等 attach |

```
MainActivity（XML 登录页，导出）
   └─ startActivity → ManyCourseMain（Compose 宿主，未导出）
        └─ fragment_container（FrameLayout）
             ├─ ClassScheduleFragment（TAB_INDEX=0）┐
             ├─ CalenderFragment     （TAB_INDEX=1）├ 三个 Fragment 常驻，hide/show 切换
             └─ SelfFragment         （TAB_INDEX=2）┘
                  每个 Fragment = 一个 ComposeView
                  每个 Fragment 内部：Box{ hazeSource(背景) ; Column{ GlassHeader / 内容 / GlassBottomNav } ; 浮层 }
```

**关键点**：三个 Fragment 同时存在（不销毁），因此任何"逐帧成本"都会被乘以 3——见 §5。

---

## 2. 分层职责

### 2.1 状态层

| 文件 | 内容 | 约定 |
|---|---|---|
| `ui/MainTabStore.kt` | `currentIndex`（当前 Tab） | 全局单例，Activity 与 Compose 共享 |
| `data/Course.kt` | `CourseRepository`（课程，内存 `mutableStateListOf`）、`ProfileRepository`（昵称/专业） | UI 直接读，改后自动重组 |
| `data/UiSettings.kt` | `glassMode`（玻璃风格）、`notificationsEnabled`（通知开关）+ SharedPreferences 读写 | **唯一持久化入口**，由 `ManyCourseApp.onCreate` 幂等初始化 |

规则：
- **跨页面共享的状态**放单例（`MainTabStore` / `UiSettings` / Repository），不要塞进某个 Fragment。
- **派生值**用 `derivedStateOf` 包住，只有当结果变化时才向下游传播失效（见 §5 R8 的实测收益）。
- 不要新增"能推导出来的状态"（例如"今天的课"由仓库实时算，见 `CourseRepository.todayCourseCount`）。

### 2.2 令牌层（改样式只改这里）

文件：`ui/theme/GlassTokens.kt`

```kotlin
GlassMode  { Gaussian（默认）, Liquid }        // 设置页可切，持久化
GlassLevel { Bar, Panel }                      // Bar=页头/底栏；Panel=弹窗/表单
GlassEdge  { None, Top, Bottom }               // 分隔线画在哪一侧（None=完全不画）
GlassTokens                                    // 所有颜色/圆角/阴影/模糊半径的唯一来源
LocalGlassTokens / LocalGlassMode              // 由 ManyCourseTheme 提供
```

`ManyCourseTheme` 里依据 `UiSettings.glassMode` × `isSystemInDarkTheme()` 算出一份 `GlassTokens` 注入组合树，**切换模式无需重启，所有组件自动重组**。

令牌取值（浅色 / 深色）：

| 令牌 | Gaussian | Liquid | 用途 |
|---|---|---|---|
| `barTint` | 白 52% / `#0F172A` 62% | 白 46% / `#0F172A` 58% | 页头底栏底色（比内容**亮一档**，见 §3） |
| `panelTint` | 白 94% / `#1E293B` 94% | 白 90% / `#1E293B` 92% | 弹窗/表单面板 |
| `cardTint` / `cardTintHighlighted` | 白 78% / 86% | 白 72% / 82% | 列表卡片（静态） |
| `fieldTint` | `#E3EBF7` / `#16202F` | `#E8EFFA` / `#16202F` | 面板内输入框（必须比面板**暗一档**） |
| `barBlur` | 14dp | 16dp | 只有页头/底栏做实时模糊 |
| `barShadow` | 12dp | 14dp | 页头**下方**投影的渐隐高度 |
| `panelShadow` / `cardShadow` | 8dp / 3dp | 14dp / 6dp | 独立表面的阴影 |
| `highlight` | 白 14%→2%→0 | 白 30%→6%→2% | 顶部柔光 |
| `saturation` | 0.94（灰纱收敛） | 1.10（品牌色增强） | 仅独立表面 |
| `edgeLight` / `innerShadow` | 全透明 / 全透明 | 白 60% / 黑 5% | 仅独立表面（液态玻璃的边缘光与厚度） |
| `backgroundGradient` | 浅色/深色各一组 | 同上（彩度更高） | 页面底 |
| `blobAlphaScale` | 0.75 / 0.7 | 1.0 | 背景光斑色感 |
| `barSeparator` | `CampusSlate200` / `#334155` | 白 60% / 白 24% | 底栏与内容的分界线 |

新增样式差异时，**先加令牌**，再在 `glassOverlays` 里消费；不要在组件里写 `if (mode == Liquid)`。

### 2.3 渲染层

文件：`ui/components/LiquidGlass.kt`

| API | 用在哪 | 是否实时模糊 | 说明 |
|---|---|---|---|
| `LiquidGlassBackground(animate)` | 每个 Fragment 的背景（`hazeSource` 内） | — | 渐变 + 3 个轨道光斑；`animate=false` 时冻结 |
| `Modifier.liquidGlass(hazeState, shape, level, separatorEdge)` | **只有页头/底栏** | ✅ Haze | Bar 层：不画整圈描边、不吃内阴影/色感纱 |
| `Modifier.glassPanel(shape)` | 弹窗/表单容器（`GlassPanel`） | ❌ 静态 | 高不透明度 + 描边 + 阴影 + 边缘光 + 内阴影 |
| `Modifier.glassSurface(shape, highlighted)` | 列表卡片、`GlassCard` | ❌ 静态 | 零逐帧成本 |
| `glassOverlays(...)` | 内部 | — | 高光 / 描边 / 内阴影 / 边缘光 / 色感纱的唯一实现点 |

### 2.4 组件层

| 组件 | 说明 |
|---|---|
| `GlassHeader(hazeState, title)` | 56dp + 状态栏避让；**四周不画线**；下方带柔和投影（`barShadow`） |
| `GlassBottomNav(hazeState, onSelect)` | 64dp；**内部**读取 `MainTabStore.currentIndex`（见 §5 R8）；顶部一条分界线 |
| `GlassOverlay(visible, onDismissRequest, panel)` | 浮层宿主：遮罩淡入淡出 + 面板淡入/上移、返回键关闭、点击遮罩关闭、横屏安全区、IME 避让、面板限高 92% |
| `GlassCard` / `GlassPanel` / `CourseCard` / `CampusChip` / `CampusButton` | 静态玻璃与基础控件 |
| `isTabForeground(tabIndex)` | 页面可见且前台的门控标志（冻结动画用） |

约定：**新玻璃组件必须走令牌 + `glassOverlays`**，不要自己写一套背景/描边。

### 2.5 动效层

| 位置 | 实现 |
|---|---|
| Tab 切换 | `ManyCourseMain.selectTab()` 里 `setCustomAnimations(tab_enter, tab_exit, tab_pop_enter, tab_pop_exit)`（`res/anim/*`，240ms，FastOutSlowIn，**纯淡入淡出，不做位移**） |
| 浮层进出场 | `GlassOverlay` 的 `AnimatedVisibility`（淡入淡出 + 轻微位移，**不做缩放**） |
| 登录 → 主界面 | `MainActivity.applyLoginTransition()`：API 34+ 用 `overrideActivityTransition`，低版本 `overridePendingTransition`（已 `@Suppress`） |
| 统一参数 | `ui/Motion.kt`（时长、缓动、`Motion.enabled()`） |

**尊重系统设置**：`Motion.enabled()`（= `ValueAnimator.areAnimatorsEnabled()`）为 false 时不挂动画/不设过渡。Compose 动画本身也跟随系统动画缩放。

**★ 为什么 Tab 切换不能做位移**：动画是加在**整个 Fragment 视图**上的，而底部导航栏
长在 Fragment 里 —— 一旦有 `translate`，整条底栏就跟着页面上下抖。
所以这里是**纯 alpha 交叉淡入**，并且：

1. `tab_enter` / `tab_exit` 的**时长与插值器必须完全一致**，这样 `out + in` 的 alpha 恒等于 1，
   交叉期间不会整体发暗；
2. 背景动画相位是**进程级共享**的（`LiquidGlass.kt` 的 `BackdropPhase`，只有当前可见页驱动），
   否则两页的液态玻璃光斑相位不同，交叉淡入时背景会"化开"，底栏的实时模糊也跟着闪。

### 2.6 适配层

| 事项 | 做法 |
|---|---|
| 状态栏 / 挖孔 | `WindowInsets.statusBars ∪ displayCutout`，`.only(Top + Horizontal)`（**不含 IME**） |
| 底栏 / 手势条 | `WindowInsets.navigationBars ∪ displayCutout`，`.only(Bottom + Horizontal)` |
| 中间内容 | `WindowInsets.safeDrawing.only(Horizontal)`（竖屏为 0，横屏/异形屏才生效） |
| XML 登录页 | `MainActivity` 的 insets 监听：`systemBars() or displayCutout()`，四边都加 padding |
| 屏幕方向 | 两个 Activity 都 `android:screenOrientation="portrait"`（去掉系统"旋转屏幕"黑色提示；Android 16+ 大屏会忽略该限制） |
| 深色 | Compose 走 `MaterialTheme` 角色色；XML 登录页走 `values-night/colors.xml` |
| 多语言 | 应用名：`values-zh/strings.xml` 覆盖 `app_name=课多多`，其他语言回退 `values/strings.xml` |

---

## 3. 硬性规则（每条都踩过）

> 违反这些规则会产生"看起来像 bug"的视觉效果，而且很难从代码上看出来。

**R1 · 半透明玻璃后面禁止垫 `Modifier.shadow`**
阴影会透过半透明玻璃，把整条压暗。
✅ 正确：投影画在玻璃**下方**（`GlassHeader` 里那条 `barShadow` 高度、由 `shadowColor` 渐隐到透明的 `Box`）。
❌ 实测：给页头加 `Modifier.shadow(5.dp)` 后，页头亮度从 234.8 掉到 **218.7**（比内容还暗）。

**R2 · 页头/底栏不吃"独立表面"的三种装饰**：整圈描边、底部内阴影、色感纱层。
- 整圈描边：在贴屏幕边缘的那侧会被圆角屏/曲面屏裁掉，出现"线被圆角切断"
- 内阴影（Liquid 黑 5%）：让"顶部"看起来**沉在内容下面**
- 色感纱层（蓝纱）：压 R 通道，页头比内容暗
✅ 实现：`glassOverlays(standalone = ...)`，`standalone = level == Panel`（页头/底栏传 false）。

**R3 · 会做进出场动画的表面禁止实时模糊**
Haze 的 `RenderEffect` 放在缩放/淡入图层里会重采样 → 表现为"卡片闪几下"。
✅ 弹窗/表单用静态 `glassPanel`（本来不透明度就 90%+，模糊观感差异极小）。

**R4 · 贴屏幕物理边缘不画线**
只在"与内容相接"的一侧画分界线；页头当前**完全不画**（内容在自己的滚动容器里，不会滚到页头下）；底栏画顶边。

**R5 · 带 insets padding 的 ViewGroup 必须 `clipToPadding="false"`**
`ViewGroup` 默认 `clipToPadding=true`，会在 padding 边界裁掉子 View。
❌ 实测：登录页装饰光斑被状态栏那条线切断，y≈94 处出现 4.8 级亮度台阶（"状态栏和页面色差"）。
✅ `activity_login.xml` 的 `loginContainer` 已显式设为 `false`。

**R6 · `MaterialCardView` 不要设 `cardElevation`（或不透明度要求"背景铺满"时）**
它会按阴影给自身背景加内边距 → 卡片背景比控件内缩一圈，看起来像"卡片里还有一张小卡片"。
❌ 实测：登录卡片背景在 y=1160 那行从 x≈80 起，而不是卡片边界 66。
✅ 层次靠底色 + 1dp 描边；`cardPreventCornerOverlap/cardUseCompatPadding=false`。

**R7 · 列表/滚动场景不用实时模糊**
`GlassCard`/`CourseCard` 走 `glassSurface`（静态）。只有两个页头/底栏共 2 处实时模糊。

**R8 · 状态读取位置决定重组范围**
`MainTabStore.currentIndex` 如果在 **Fragment 组合作用域**里读（例如作为 `GlassBottomNav` 的参数），切一次 Tab 会让**三个全屏 Fragment 全部重组**。
✅ 两处修正：① `GlassBottomNav` 内部读取索引；② `isTabForeground` 用 `derivedStateOf` 包住 tab 比较 → 只有可见性真正变化的那一页重组。

**R9 · 隐藏页 / 退后台必须冻结动画**
三个 Fragment 常驻，隐藏页不绘制但动画仍在逐帧跑。
✅ `isTabForeground` + `LiquidGlassBackground(animate)`：用 `Animatable` + `LaunchedEffect(animate)`，不可见时协程取消（数值冻结），恢复时按 LinearEasing 从当前值续走，位置不跳变。
（`rememberInfiniteTransition` 没有公开的暂停 API，所以没用它。）

**R10 · 动画值要量化后再喂给图层**
角度步进 1°、缩放步进 1%（`derivedStateOf`）：光斑是超柔和渐变 + 32 秒一圈，肉眼无差，但背景层失效频率从 60/s 降到约 15/s，Haze 的重模糊次数同步下降。

**R11 · 文字颜色走 `MaterialTheme.colorScheme` 角色，不要硬编码 `CampusSlate900/500/200`**
本项目浅色方案的 `onSurface/onSurfaceVariant/outline` 取值**与原来的硬编码完全一致**，所以替换是零视觉变化的，但深色模式会立刻正确。
（XML 登录页没有角色色可继承，因此用 `@color/login_*` + `values-night` 覆盖。）

**R12 · 不引入废弃 API；生命周期用 `androidx.lifecycle.compose.LocalLifecycleOwner`**
`androidx.compose.ui.platform.LocalLifecycleOwner`、`overridePendingTransition`(API34+)、`isStatusBarContrastEnforced`(API35+) 都已废弃/受限：
- 生命周期：依赖 `lifecycle-runtime-compose`
- 状态栏对比度：`ui/WindowExt.kt` 里统一 `@Suppress("DEPRECATION")`
- 应用内 `screenOrientation` 已加 `tools:ignore="LockedOrientationActivity"`

---

## 4. 常见扩展配方

### 4.1 新增一个页面（Tab）
1. 新建 `XxxFragment`：照抄 `SelfFragment` 结构（`hazeSource` 背景 + `GlassHeader` + 内容 Box（横向安全区）+ `GlassBottomNav` + 浮层），`TAB_INDEX` 取 0/1/2。
2. `ManyCourseMain` 的 `tabFragments` 与 `TAB_*` 常量同步加。
3. `GlassBottomNav` 的 `items` 列表加一项（图标放 `AppIcons`）。
4. 页面内容用 `GlassCard` 包卡片；文字用 `MaterialTheme.colorScheme` 角色色。

### 4.2 新增/调整样式
只改 `ui/theme/GlassTokens.kt`：加字段 → 在 `glassTokens(mode, dark)` 两处（Gaussian / Liquid）都给值 → 在 `glassOverlays` 或组件里消费。
⚠️ 同步更新 `app/src/test/java/com/tof/manycourse/GlassTokensTest.kt` 的区间断言（该测试会拦住"面板比卡片还透""输入框和面板同色"这类退化）。

### 4.3 新增浮层（对话框/表单）
```kotlin
@Composable
fun XxxDialog(visible: Boolean, onDismiss: () -> Unit) {
    GlassOverlay(visible = visible, onDismissRequest = onDismiss) {
        GlassPanel(cornerRadius = 24, modifier = Modifier.fillMaxWidth()) { /* 表单 */ }
    }
}
```
- 调用方**常驻组合**它（`visible` 控制），否则没有退出动画
- 表单状态在 `LaunchedEffect(visible) { if (visible) { /* 重置 */ } }` 里重置
- 面板内部**不要**用实时模糊（R3）

### 4.4 接入真实后端数据
现状：`api/HttpMethod.kt`（OkHttp，同步 `get`/`post` + 异步 `getAsync`/`postFormAsync` 与内存 Cookie 会话）、`gr_api/GRClassAPI.kt`（旧的 `loginSystem`，已弃用未接线）。

**登录已接线**：登录页的「选择学校」→ `gr_api/SchoolRegistry` → `gr_api/schools/*Api`（详见 `docs/教务API接入与调用指南.md`）。课程等业务数据仍来自内存仓库。
接法建议（保持 UI 不变）：
1. 把 `CourseRepository` 的 `courses: SnapshotStateList<Course>` 保留为**唯一 UI 数据源**，网络层只负责填充它（`courses.clear(); courses.addAll(remote)`）→ 所有页面自动刷新。
2. 网络调用走 `HttpMethod` 的异步接口（回调在工作线程，自己切主线程），**不要**用同步 `execute()` 卡主线程；登录成功后的 Cookie 会话在同一个 `SchoolApi` 实例里复用到拉取课表。
3. 增加三态（加载中 / 空 / 错误）时，用现有的静态玻璃组件包一层，例如空态参考 `CalendarScreen` 的"当日无课"卡片；错误态参考登录页的 `login_info_error` 令牌。
4. 账号态已由 `data/LoginSettings` 承接（目前只存"上次选的学校"，**不存密码**）。
5. 字段口径见 `docs/UI使用文档.md` 第 2 章（weekday 1=周一、节次 1~10 等）。

---

## 5. 性能清单

| 项 | 现状 |
|---|---|
| 实时模糊点 | 仅 2 处（页头、底栏），各 Fragment 一份 |
| 隐藏页动画 | 冻结（`isTabForeground`） |
| 退后台 | 冻结（生命周期 STARTED 门控） |
| 图层失效频率 | 量化后约 15/s（原 60/s） |
| 列表卡片 | 静态表面，零逐帧成本 |
| 弹窗面板 | 静态表面（同时规避 R3 的闪帧） |
| release 构建 | `optimization { enable = true }`（R8 已开启） |

排查卡顿的次序：① 是否 debug 包（debug 明显更慢，请用 release 复测）② 是否有新的实时模糊/每帧失效 ③ 是否有状态被放在大作用域里读（R8）④ 是否有"进后台还在跑的动画"（R9）。

---

## 6. 构建与验证工作流

```bat
:: Windows：gradlew.bat；macOS/Linux 用 ./gradlew
gradlew.bat :app:assembleDebug                :: 要求 0 error / 0 warning（含废弃 API 警告）
gradlew.bat :app:assembleRelease              :: 验证 R8 下也能过
gradlew.bat :app:testDebugUnitTest            :: GlassTokensTest：令牌区间/层级断言
gradlew.bat :app:assembleDebugAndroidTest     :: 构建插桩测试 APK
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.tof.manycourse.GlassUiTest ^
    com.tof.manycourse.test/androidx.test.runner.AndroidJUnitRunner
```

`GlassUiTest` 覆盖：玻璃风格切换即时生效 + 持久化 + 重建保持、添加课程浮层返回键关闭、底部导航切换、通知开关可关 + 持久化。

### 6.1 视觉改动的验收手法（无 UI 自动化也能客观验证）
1. **`uiautomator dump`**：拿控件精确 bounds（`adb shell uiautomator dump /sdcard/u.xml` + `adb pull`），用于判断间距/内缩/贴边。
2. **逐像素剖面**：`adb exec-out screencap -p` 存图后，用脚本沿 x 或 y 逐像素输出 RGB/亮度，找"台阶"与"暗带"。
   - 例：页头下边界 1px 细扫，可判断有没有分隔线残留
   - 例：`y=0..6` 细扫，可判断系统是否在物理顶边画了东西
3. **`aapt2 dump badging`**：验证多语言资源（应用名等）与图标是否按 locale 打包正确。
4. **临时调试技巧**（用完必须还原）：把 `ManyCourseMain` 临时设为 `exported="true"` 以便直接启动主界面取图；把 `showAddCourse` 初值临时设为 `true` 以便打开浮层取图。

### 6.2 工具陷阱（重要）
- **不要用 PowerShell 的 `Set-Content`/`Out-File` 改源码**：该环境按 ANSI 落盘，会把含中文的 UTF-8 源文件写成无效 UTF-8（本次已因此损坏并重建 5 个文件）。改文件用 IDE/编辑器（本会话用 `write`/`edit` 工具）。
- **MIUI 设备禁止 shell 注入输入**：`adb shell input tap/keyevent/text` 报 `SecurityException: INJECT_EVENTS`，因此无法用 adb 驱动 UI；交互验证要么走插桩测试，要么靠上面的像素/层级手法。
- **MIUI 可能拒绝安装测试 APK**：`INSTALL_FAILED_USER_RESTRICTED`（需在开发者选项里打开"通过 USB 安装应用"），此时 `am instrument` 无法运行。
- **无线调试的 ddmlib 不稳**：`connectedAndroidTest` 可能报 `Unknown API Level` / `ShellCommandUnresponsiveException`，改用上面的 `adb install` + `am instrument` 手动路径。

---

## 7. 已知取舍与待办

| 项 | 现状 / 建议 |
|---|---|
| 弹窗面板不做实时模糊 | 为消除进出场闪帧（R3）；若日后要恢复，必须在动画结束后再开启模糊，且不能用缩放 |
| 页头无分隔线 | 靠"更亮的底色 + 下方柔和投影"表达层次；若想更明确，调 `barSeparator` 并改 `GlassEdge.Bottom` |
| 登录页图标 | 已去掉玻璃圆盘与投影（待替换正式图标，直接换 `android:src`） |
| 竖屏锁定 | 手机生效；Android 16+ 大屏会忽略，页面已做横屏安全区适配，不会错版 |
| 深色模式 | Compose 侧已按角色色适配；XML 登录页靠 `values-night`。新加 XML 页面请照做 |
| 数据层 | 课程仍是内存仓库；**登录已接真实教务系统**（`gr_api/SchoolRegistry`，见 §4.4 与 `docs/教务API接入与调用指南.md`），旧的 `GRClassAPI` 未接线、可删 |
| 通知开关 | 仅持久化开关状态，尚未接 `NotificationManager`/WorkManager 真正推送 |
| `AppIcons` | 自绘 `ImageVector`（`lazy`）；新增图标沿用该方式，避免引入图标库 |
