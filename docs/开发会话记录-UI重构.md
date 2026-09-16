# 开发会话记录：ManyCourse UI 重构与打磨（压缩版）

> 本文是这段时间 UI 相关开发过程的**压缩记录**：每一轮的需求 → 诊断（含实测数据）→ 改动 → 结果。
> 目的是让后来者知道"为什么代码长成现在这样"，以及哪些坑已经踩过。
> 配套文档：`docs/UI架构与实现指南.md`（规则与配方）、`docs/UI使用文档.md`（数据模型对接）。

**环境**：Kotlin 2.2.10 / AGP 9.3.2 / Compose BOM 2026.02.01 / Haze 1.7.3 / minSdk 31 / targetSdk 37。
**验证设备**：Android 14（1080×2400 @440dpi，小米）、临时用过 Android 17（1220×2656 @520dpi）。
**贯穿全程的约束**：不削减视觉效果、不引入大型依赖、按现有规范最小改动。

---

## 轮次 1 · 液态玻璃"更通透" + 流畅度

| 项 | 内容 |
|---|---|
| 需求 | 玻璃再通透一点；流畅度不够，要在不减效果的前提下优化性能 |
| 诊断 | ① 三个 Fragment 常驻且用 `hide()`，隐藏页的无限动画仍在逐帧跑（3 倍开销）；② 可见页 60fps 的光斑动画让 `hazeSource` 每帧失效 → Haze 每帧重渲染快照 + 重模糊；③ `release` 里 `optimization { enable = false }`，R8 全关 |
| 改动 | ① `LiquidGlassBackground(animate)` + `isTabForeground()`：改用 `Animatable` + `LaunchedEffect(animate)`（`rememberInfiniteTransition` 无公开暂停 API），不可见时冻结、恢复时无缝续走；② 动画值量化（角度 1°、缩放 1%，`derivedStateOf`）→ 图层失效 60/s → ~15/s；③ `build.gradle.kts` 打开 R8；④ tint 降低（页头 0.40→0.28、底栏 0.45→0.32），blurRadius 保持 18dp 不减质感 |
| 结果 | 编译通过；release 包性能显著优于 debug（后续所有性能结论都要求用 release 复测） |

---

## 轮次 2 · UI/交互优化三件事

| 项 | 内容 |
|---|---|
| 需求 | ① 页面切换加平滑动画；② 添加课程页玻璃太透、要弱化；③ 全局提供"高斯模糊 / 液态玻璃"两种模式并可持久化 |
| 技术栈判断 | 无 Navigation 组件 → 用 `FragmentTransaction.setCustomAnimations`；多 Activity（登录→主界面）→ `overrideActivityTransition`（API34+）/`overridePendingTransition`；状态存 SharedPreferences（项目原本无持久化方案） |
| 改动 | ① 新增 `res/anim/{tab_enter,tab_exit,tab_pop_enter,tab_pop_exit}.xml`（240/180ms、FastOutSlowIn、淡入淡出 + 8dp/-4dp）+ `MainActivity` 的 Activity 过渡；② 新增 `GlassTokens` / `GlassMode` / `GlassLevel` / `GlassEdge` + `LocalGlassTokens`，`Modifier.liquidGlass`/`glassSurface` 全部改为令牌驱动；③ 新增 `UiSettings`（glass_mode + notifications_enabled）+ `ManyCourseApp` 幂等初始化；④ 个人页新增"外观 · 玻璃风格"切换卡；⑤ 新增 `GlassOverlay`（遮罩淡入淡出 + 面板淡入上移 + 返回键关闭 + 点击遮罩关闭 + 面板限高 92%）；⑥ 新增 `uiautomator`+像素采样验证脚本（用完即删） |
| 结果 | 切换动画/模式切换/持久化全部落地；添加课程面板不透明度提到 78%–84%、加 1dp 描边 + 阴影（当时仍用 Haze，见轮次 5 被替换） |
| 遗留 | 5 个含中文的源文件在批量替换调用点时被 PowerShell 按 ANSI 写坏（无效 UTF-8），已整份重建 —— **此后禁止用 shell 重定向写源码** |

---

## 轮次 3 · 登录页三个问题

| 项 | 内容 |
|---|---|
| 需求 | ① 登录页"登录块"布局有问题；② 登录页夜间模式没做好；③ 通知设置不能关 |
| 诊断① | 用户描述："卡片内有一个比卡片小一圈的卡片没铺满" → 像素扫描发现卡片背景在 y=1160 行从 **x≈80** 起，而卡片边界是 **66** → 卡内缩约 13px。根因：`applyGlassMode()` 给 MaterialCardView 设了 `cardElevation`（≈8dp），**CardView 会按阴影给自身背景加内边距**（= 上一轮我自己引入的回归） |
| 诊断② | 布局与 4 个 drawable 全硬编码浅色；`SystemBarStyle.light` 让深色模式状态栏图标与背景同色 |
| 改动 | ① 不再运行时改 elevation/strokeWidth；卡片内边距 20dp→16dp；`MaterialButton` 设 `insetTop/insetBottom=0`（渐变胶囊不被阴影内缩）；圆角改为非同心（卡片 24dp / 输入框 12dp）；卡片底部固定 24dp 留白；`cardPreventCornerOverlap/cardUseCompatPadding=false`；② 新增 `values/colors.xml` 的 `login_*` 令牌 + **`values-night/colors.xml`** 覆盖，5 个 drawable 与布局全部改引用；状态文字色改用 `@color/login_info_*`（原硬编码 `Color.RED/GREEN`）；两个 Activity 的 `SystemBarStyle.light → auto`；③ 个人页通知改为真正的 M3 `Switch` + 持久化（并把 `GlassSettings` 重命名为 `UiSettings`） |
| 实测 | 暗色标题对比度 14.2:1、输入框提示 6.2:1、浅色标题 7.9:1（均 ≥4.5:1）；卡片背景修复后精确铺满 [66,1013] |

---

## 轮次 4 · 圆角屏描边不适配

| 项 | 内容 |
|---|---|
| 需求 | 导航栏与上部白色描边没适应圆角屏幕那一部分 |
| 诊断 | 页头/底栏用的是**整圈描边**，其中顶边/两侧（页头）与底边/两侧（底栏）正好落在屏幕**物理边缘**上；圆角屏/曲面屏/挖孔屏会裁切屏幕边缘 → 线被圆角切断或贴边 |
| 改动 | ① 新增 `GlassEdge`：贴物理边缘一侧**不画任何线**，只在"与内容相接"的一侧画 1dp 分隔线（页头下边、底栏上边），颜色走 `barSeparator` 令牌（高斯=发丝线，液态=亮白边缘光）；② 安全区从 `systemBars` 升级为 `statusBars/navigationBars ∪ displayCutout`（含横屏侧边挖孔），中间内容与浮层加横向安全区；③ 登录页 insets 监听改为 `systemBars() or displayCutout()` 且四边都加 |
| 实测（液态模式） | y=0 与 y=20 同色（顶边无线）；x=0..3 与内部 x=20 同色（两侧无线）；页头 y≈246 出现亮白分隔线；底栏 y=2172 出现亮白分隔线、底边 y=2390..2399 只有内阴影无描边 |

---

## 轮次 5 · 切页卡顿 + 添加课程卡片闪帧

| 项 | 内容 |
|---|---|
| 需求 | 切换页面一开始不流畅；添加课程卡片有时候会闪几下 |
| 诊断① | `MainTabStore.currentIndex` 被**当作参数**在 Fragment 组合作用域读取（`GlassBottomNav(hazeState, MainTabStore.currentIndex.intValue)`）→ 切一次 Tab，三个全屏 Fragment 全部重组；另外底栏用 `safeDrawing`（含 IME），键盘弹出会引起底栏重排 |
| 诊断② | 三个因素叠加：面板用 Haze 实时模糊（`RenderEffect` 在 `AnimatedVisibility` 的缩放/淡入图层里会重采样 → 闪帧）；同时叠了 `shadow` + `scaleIn/scaleOut` + RenderEffect 三层；`EnterTransition` 每次重组新建（表单在打开瞬间重置字段触发重组 → 动画可能被中断重放） |
| 改动 | ① `GlassBottomNav` 去掉 `activeIndex` 参数、**内部**读取；`isTabForeground` 用 `derivedStateOf` 包住 tab 比较（只有可见性真正变化的那页重组）；底栏安全区改 `navigationBars ∪ displayCutout`；② `GlassPanel` 改为**静态**高不透明度表面（去掉实时模糊、去掉 `panelBlur` 令牌与浮层的 `HazeState` 参数）；③ `GlassOverlay` 的 enter/exit 过渡全部 `remember`；④ 进出场去掉缩放，只保留淡入淡出 + 轻微位移 |
| 顺带 | `LocalLifecycleOwner` 迁移到 `androidx.lifecycle.compose`（显式声明 `lifecycle-runtime-compose`），构建恢复 0 警告 |

---

## 轮次 6 · 应用名本地化

| 项 | 内容 |
|---|---|
| 需求 | 用户能在后台（最近任务）看到的软件名改为"课多多"，其他系统语言保持现有名字 |
| 改动 | 新增 `values-zh/strings.xml` **只覆盖 `app_name=课多多`**；`values/strings.xml` 的 `ManyCourse` 不动（其他语言回退） |
| 实测 | `aapt2 dump badging`：`application-label:'ManyCourse'`、`application-label-zh/zh-CN:'课多多'`、`application-label-ja:'ManyCourse'`；真机（zh-CN）系统"应用信息"页解析为**课多多** |

---

## 轮次 7 · 添加课程面板仍偏透

| 项 | 内容 |
|---|---|
| 需求 | 添加课程页面太透了，稍微削弱一点 |
| 诊断 | 实测面板 (241,244,248)、**输入框 (241,245,248) —— 与面板几乎同色**，字段"消失"在面板里，整体发糊发透 |
| 改动 | ① `panelTint`：高斯 84%→**94%**、液态 78%→**90%**（深色同步 85%→94% / 80%→92%）；② 新增 `fieldTint` 令牌（浅色 `#E3EBF7`/`#E8EFFA`，深色 `#16202F`），输入框不再用 `surfaceVariant.copy(alpha=0.55f)`；③ 单测补三条断言：面板不透明度区间、面板必须比卡片更不透明、输入框必须不透明且比面板暗一档（其中第三条第一次运行时抓出了我自己写反的判断） |
| 实测 | 面板 (242,245,249) 接近实底白；输入框 (232,239,250)，与面板拉开约 10 级 |

---

## 轮次 8 · 旋转黑色提示

| 项 | 内容 |
|---|---|
| 需求 | 把旋转界面的黑色提示去除，不可在软件内点击旋转 |
| 诊断 | 代码里没有任何旋转 UI/提示；设备「自动旋转」关闭 + 应用允许旋转 → 物理转动手机时系统在应用上层浮出可点击的"旋转屏幕"黑色提示。基线实测：`wm user-rotation lock 1` 后应用变成 **2400×1080**（确实可旋转） |
| 改动 | 两个 Activity 都声明 `android:screenOrientation="portrait"`（+ `tools:ignore="LockedOrientationActivity"`），并在 manifest 顶部写清原因与 Android 16+ 大屏例外 |
| 实测 | 同样强制横屏，应用保持 **1080×2400**（锁定生效）；验证后已还原设备的 `user_rotation` |

---

## 轮次 9 · 登录页割裂感 + 主界面顶部层次（本会话最后一项）

需求原话："软件本身和顶部状态栏有割裂感" → 澄清："我是指登录页面，而且你刚刚主页面这个阴影错了，顶部应该在上层才对"。

**这一轮改了两次错地方，值得记录：**

1. 第一次误判：以为说的是主界面页头的 1dp 分隔线 → 去掉后实测页头下边界从 `225 → 226,232,240(暗线) → 228` 变成平滑单级过渡（Δ3）。但用户要的是**登录页**。
2. 第二次误判：给页头加 `Modifier.shadow(5.dp)` 想做"浮起" → 实测页头亮度 **234.8 → 218.7**，反而更暗。根因：**半透明玻璃会把垫在它下面的阴影透上来**（和登录页图标圆盘"发脏"是同一个坑）。

**最终定位的两处根因：**

- **登录页状态栏色差带**：`loginContainer` 被运行时加了"状态栏高度"的 padding，而 `ViewGroup` 的 `clipToPadding` 默认 true → 两个装饰光斑被**正好在状态栏那条线处裁掉**。实测 y=92 (224,237,254) → y=96 (224,237,253) 亮度跳变 4.8 级，且只在光斑覆盖的水平位置出现（x=120 有、x=540 没有）。**修复：`android:clipToPadding="false"`** → 现在 235.7 → 235.4 → 234.5 平滑。
- **主界面"顶部应该在上层"**：页头在液态玻璃模式下**也吃到了 `innerShadow`（底部内阴影）**，等于给顶栏做了"内凹"；另外还被蓝色"色感纱层"压了 R 通道 → 实测页头比内容暗 15 级。**修复：内阴影/色感纱层/整圈描边只用于"独立玻璃表面"（`standalone = level == Panel`），页头/底栏不吃；页头底色提亮（高斯 0.34→0.52、液态 0.26→0.46 白）；投影改为画在玻璃条下方（`barShadow` 12/14dp 渐隐）**。

**实测（x=540）**

| | 页头 | 页头下方 |
|---|---|---|
| 改前 | 218（比内容暗 15 级） | 直接跳到内容色 |
| 改后 | **233（与内容同色、无暗带）** | **211.8 → 233.7 在 14dp 内渐隐的柔和投影** |

另外按用户要求**去掉了登录页图标底下的玻璃圆盘与投影**（半透明圆盘上的投影会透出来显脏），图标现在直接以 96dp 渲染；用户表示后续会替换正式图标（只需换 `android:src`）。

---

## 关键决策一览

| 决策 | 原因 |
|---|---|
| 实时模糊只保留页头/底栏两处 | 列表/弹窗做实时模糊既贵（每帧重录快照 + 重模糊）又与进出场动画冲突（闪帧） |
| 弹窗/表单用**静态**高不透明度表面 | 可读性优先 + 消除闪帧 + 零逐帧成本（不透明度本来就有 90%+） |
| 页头不画分隔线，改用"更亮底色 + 下方柔和投影" | 贴屏幕边缘画线会被圆角/挖孔裁切；垫在玻璃底下的阴影会把顶栏压暗 |
| 隐藏页/退后台冻结动画 | 三个 Fragment 常驻，不冻结就是 3 倍逐帧开销 |
| 状态就近读取 + `derivedStateOf` | 避免"切一次 Tab 三张全屏页面一起重组" |
| 竖屏锁定 | 去掉系统"旋转屏幕"黑色提示；大屏例外已在注释与文档说明 |
| 持久化用 SharedPreferences | 项目原本无持久化方案，不为一个开关引入 DataStore |
| 应用名用 `values-zh` 覆盖 | 一处改动同时覆盖桌面、最近任务、系统设置里的应用名 |

---

## 遗留 / 下一步建议

1. **数据层**：`CourseRepository` 仍是内存数据，`gr_api/GRClassAPI.kt` + `api/HttpMethod.kt` 未接线；`HttpMethod` 用的是同步 `execute()`，接线时务必切到后台线程（见指南 §4.4）。
2. **通知开关**：目前只持久化状态，尚未真正接入通知/WorkManager。
3. **插桩测试**：`GlassUiTest`（4 个用例）已写好，但目标机 MIUI 拒绝安装测试 APK（`INSTALL_FAILED_USER_RESTRICTED`），需在开发者选项打开"通过 USB 安装应用"后才能 `am instrument` 运行。
4. **性能复测**：所有性能结论都应以 **release 包**复测（debug 明显更慢）。
5. **弹窗面板模糊**：若日后要求恢复实时模糊，必须在进出场动画结束后再开启，且不能用缩放。

---

## 附：本会话用到的验证手法（可复用）

1. **构建零告警**：`:app:assembleDebug/assembleRelease + testDebugUnitTest`，把 `^e:`/`^w:` 作为验收项。
2. **像素剖面**：截图后用脚本沿 x/y 逐像素输出 RGB 与亮度，找"台阶""暗带""内缩"——本会话几乎每个视觉结论都来自它（分隔线残留、卡片内缩 13px、状态栏 4.8 级台阶、页头 15 级偏暗、输入框与面板同色…）。
3. **控件精确 bounds**：`uiautomator dump` + `adb pull`，判断间距与贴边。
4. **资源打包核验**：`aapt2 dump badging` 验证多语言应用名。
5. **临时代码技巧（用完必须还原并复核）**：临时把 `ManyCourseMain` 设为 `exported="true"` 以直接启动主界面取图；临时把 `showAddCourse` 初值设为 `true` 以打开浮层取图。

**工具坑（务必避开）**：不要用 PowerShell 的 `Set-Content`/`Out-File` 改源码（该环境按 ANSI 落盘，会毁掉含中文的 UTF-8 源文件）；MIUI 禁止 `adb shell input` 注入（无法用 adb 驱动 UI）；无线调试下 `connectedAndroidTest` 常因 ddmlib 失败，改用手动 `adb install` + `am instrument`。
