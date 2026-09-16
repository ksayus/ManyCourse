# ManyCourse UI 使用文档（面向后端开发）

> 本文档描述当前 App 各界面展示的数据、用户操作入口、以及前端已有的数据层接口。
> 开发后端时，只需对照第 2 章的数据模型和第 5 章的对接点，将本地仓库替换为网络实现即可，UI 层无需改动。

---

## 1. 界面总览

| 页面 | 入口 | 源文件 | 职责 |
|---|---|---|---|
| 登录页 | 应用启动页 `MainActivity` | `res/layout/activity_login.xml` + `MainActivity.kt` | 账号密码校验，成功后跳转主界面 |
| 课表页 | 底部导航「课表」 | `ui/ScheduleScreen.kt`（由 `ClassScheduleFragment` 承载） | 按星期查看课程、高亮当前课程、添加/删除课程 |
| 日历页 | 底部导航「日历」 | `ui/CalendarScreen.kt`（由 `CalenderFragment` 承载） | 月历浏览、每日课程圆点、当日课程列表 |
| 我的页 | 底部导航「我的」 | `ui/ProfileScreen.kt`（由 `SelfFragment` 承载） | 个人信息展示、统计、昵称设置、通知设置 |
| 添加课程浮层 | 课表页「添加课程」按钮 / 日历页「+ 添加」 | `ui/AddCourseScreen.kt` | 表单录入新课程 |

页面结构：`ManyCourseMain`（Activity）→ 底部导航 + `fragment_container` → 三个 Fragment 各持有一个 ComposeView。

---

## 2. 数据模型

### 2.1 Course（课程）

定义：`app/src/main/java/com/tof/manycourse/data/Course.kt`

| 字段 | 类型 | 必填 | 说明 | 约束 |
|---|---|---|---|---|
| `id` | Long | 是 | 主键，本地自增 | 后端可用服务端 id 替换 |
| `name` | String | 是 | 课程名称 | 非空，前端已做校验（空则禁止保存） |
| `teacher` | String | 否 | 任课教师 | 可为空字符串 |
| `room` | String | 否 | 上课地点 | 为空时前端默认填「地点待定」 |
| `weekday` | Int | 是 | 星期几上课 | **1=周一 … 7=周日**（对应 `java.time.DayOfWeek.value`） |
| `startPeriod` | Int | 是 | 开始节次 | 1~10 |
| `periodCount` | Int | 是 | 连续节数 | 1~4，且 `startPeriod + periodCount - 1 ≤ 10`（前端已保证） |

**派生字段（前端实时计算，后端无需存储）：**

- `startTime: String` — 开始时间，由 `startPeriod` 查表得出（如第 1 节 → "8:00"）
- `periodLabel: String` — 展示文本，如 "第1-2节"

### 2.2 节次-时间对照表

定义：`CourseRepository.periodTimes`（`data/Course.kt`）

| 节次 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| 时间 | 8:00 | 8:55 | 10:10 | 11:05 | 14:00 | 14:55 | 16:10 | 17:05 | 19:00 | 19:55 |

> 后端如返回自定义时间，可扩展 Course 增加 `startTime`/`endTime` 字段，UI 只读展示。

### 2.3 Profile（个人信息）

定义：`ProfileRepository`（`data/Course.kt` 末尾）

| 字段 | 类型 | 当前默认值 | 说明 |
|---|---|---|---|
| `nickname` | String | "张同学" | 显示昵称，「我的」页可编辑；头像取首字符 |
| `major` | String | "计算机科学 · 2022级" | 专业/年级描述，暂不可编辑 |

### 2.4 建议的后端课程 JSON

```json
{
  "id": 1024,
  "name": "高等数学",
  "teacher": "王建国",
  "room": "教学楼 A-101",
  "weekday": 1,
  "startPeriod": 1,
  "periodCount": 2
}
```

如需支持单双周、周次范围，建议扩展字段：`weeks: [1,2,3,...]` 或 `weekType: "ALL" | "ODD" | "EVEN"`，UI 层后续再加筛选展示。

---

## 3. 页面行为与数据流向

### 3.1 登录页

- 输入用户名/密码 → 点击「登录」
- 当前为**本地写死校验**：`admin / 2481`（`MainActivity.kt` 中 `debugAccount` / `debugPassword`）
- 校验失败提示依次：请输入用户名 → 请输入密码 → 用户名或密码错误 → 密码错误
- 成功：`startActivity(Intent(this, ManyCourseMain::class.java))` 并 `finish()`

**后端对接点**：将 `loginBtn.setOnClickListener` 中的 if 链替换为登录接口调用，成功后再跳转。

### 3.2 课表页

- 顶部 7 个星期 Chip（周一~周日），默认选中**今天**
- 列表展示**本周**所选星期几的课程，按节次升序
- 星期标签下方一行**教学周标签**（`第3周 09-14 ~ 09-20`，来自教务系统；没有数据时不显示）
- **高亮逻辑**：仅当选中今天时，当前时间之后的第一门课卡片加粗强调条（8dp）+ 更高不透明度
- 「添加课程」按钮 → 打开添加浮层（星期自动预选为当前选中的星期）
- **长按课程卡片** → 弹出删除确认框 → 确认后删除
  （**只有本地课能删**：教务系统按周给的课下次查还会回来，给它删除入口只会骗人）

> ⚠️ 数据来源与日历页**完全共用**：`data/CourseEntries.kt` 的 `coursesOfDate(date)`。
> 课表页把"星期几"换算成本周的日期（`dateOfCurrentWeek(weekday)`）再调它，
> 日历页直接传日期。两页各自实现一遍的话，迟早出现"课表页有课、日历页没课"。
> 真机测试 `ScheduleCalendarParityTest` 守着这条。

### 3.3 日历页

- 月历支持左右翻月；今天有蓝色描边；选中的日期实心蓝底
- 每个日期下方渲染**课程圆点**（最多 4 个）：按该日期的星期几匹配周课表，圆点颜色 = 课程名哈希取 5 色之一
- 底部汇总条显示「X月X日 共 N 门课程」，「+ 添加」打开添加浮层（星期预选为该日期的星期）
- 下方「当日课程」列表；三种空状态要分清：
  「当日无课」（有数据、那天确实没课）/「该日期暂无课表数据」（这一天没有任何可用数据）

#### 数据来源（**与课表页共用同一个函数**）

| | 来源 | 什么时候用 |
|---|---|---|
| **教学周数据** | `data/WeekScheduleStore.kt` ← 教务系统的**周次课表**接口 | 学校支持按周查（广软）时**优先**用它；翻到哪个月就补拉哪几周 |
| **本地课表兜底** | `CourseRepository.coursesOn(date)` | **只在「本周」**（含今天的那一个周一到周日） |

取值规则全部收在 `data/CourseEntries.kt` 的 `coursesOfDate(date)` 里，课表页与日历页都调它：

```
这一天所在的教学周有"按周"数据
      → 教务系统给的课 + 用户自己加的课
没有按周数据，但这一天在本周
      → 本地课表（整学期的课按星期几循环）
没有按周数据，而且不在本周
      → null（留空）
```

为什么必须优先教学周数据：本地课表只知道"周一 3-4 节有军事理论，周次 2-4"，
按星期几循环渲染的话**第 20 周的周一也会画上它**。按周拉回来的数据天然准确
（服务端只返回那一周真的会上课的课程），并且顺带给出"第 3 周 = 09-14 ~ 09-20"
这种真实日期区间 —— 就是教务系统「周次课表」页里那个周次下拉框的内容。

##### 为什么兜底只给「本周」

广软拿得到每一周的课；**金城学院拿不到**（它只有"按班级查一张整表"，给不出"某一周有哪些课"）。
如果对它也用本地课表硬兜底，等于**把本周的课循环画满整个学期** —— 看着像真的，其实是编的。

所以规则统一成：**没有教学周数据的日期就不显示课程**，只有含今天的那一周例外。
用户翻到别的月份看到的是一张"干净"的日历，而不是一张编出来的。
判定见 `data/SchoolWeek.kt` 的 `naturalWeekOf(date)`。

界面上有两处体现：

- 月份标题下方多一行**「第 N 周 09-14 ~ 09-20」**（读服务端 `#zs` 的选中项，不是在客户端推开学日期）；
  没有教学周数据时这一行不显示；
- 卡片下方一行**来源提示**：周次课表（与「课表」页同一份数据）/ 正在读取 / 拉失败（附重试）/
  「本校教务系统不支持按周查课表，日历只显示本周」/「登录已过期，日历只显示本周」。

> 注意：`coursesOfDate(date)` 返回 **null 与空表含义不同** ——
> null = 这一天没有可用数据（显示"该日期暂无课表数据"），空表 = 有数据、那天确实没课。
> 混用会把"没有数据"显示成"今天没课"。

### 3.4 我的页

- **个人信息卡片**（独立）：圆形头像（昵称首字符，蓝字白底）+ 昵称 + 专业
- **统计卡片**：本周课程 = 课程总数；今日课程 = 今天星期几匹配的课程数（均实时计算）
- **个人设置卡片**：昵称编辑（空校验"请输入昵称"），保存/重置按钮
- **通知设置卡片**：静态展示「课前 15 分钟推送提醒 · 已开启」（尚无实际推送逻辑）

### 3.5 添加课程浮层

- 字段：课程名称（必填）、任课教师（选填）、上课地点（选填）
- 选择器：星期（1~7）、开始节次（1~10）、连续节数（1~4，自动收缩不越界）
- 底部实时预览文案：`周X · 第N-M节 · H:MM 上课`
- 「保存课程」→ 写入仓库并关闭浮层；点遮罩或右上角 × 取消

### 3.6 周次：课表页 vs 日历页

课表页（`ScheduleScreen`）仍然按**每周循环**渲染整个学期的课，
课程卡片上的「3-5,8-20周」只作展示 —— 这是刻意的取舍：课表页看的是"典型一周"。
要精确到"某年某月某日有没有课"请看日历页（走教学周数据，见 §3.3）。

如需在课表页也支持单双周/周次范围，建议扩展字段：`weeks: [1,2,3,...]` 或
`weekType: "ALL" | "ODD" | "EVEN"`，UI 层后续再加筛选展示。

---

## 4. 数据层 API（UI 只依赖这些）

`CourseRepository`（`data/Course.kt`）——所有页面通过它读写，后端实现保持签名不变即可无缝替换：

| 方法/属性 | 签名 | 用途 |
|---|---|---|
| `courses` | `SnapshotStateList<Course>` | 全部课程（可观察，变更自动刷新 UI） |
| `add(...)` | `(name, teacher, room, weekday, startPeriod, periodCount) -> Course` | 新增课程 |
| `remove(id)` | `(Long) -> Unit` | 删除课程 |
| `coursesOn(weekday)` | `(Int) -> List<Course>` | 按星期查询（已按节次排序） |
| `coursesOn(date)` | `(LocalDate) -> List<Course>` | 按日期查询（日历页的**兜底**数据源） |
| `colorOf(course)` / `colorOfName(name)` | `(Course) / (String) -> Color` | 课程配色（前端逻辑，后端无需关心） |
| `periodTime(period)` | `(Int) -> String` | 节次 → 时间 |
| `weekCourseCount` | `Int` | 本周课程总数 |
| `todayCourseCount` | `Int` | 今日课程数 |

`WeekScheduleStore`（`data/WeekScheduleStore.kt`）——日历页的**优先**数据源（按教学周）：

| 方法/属性 | 签名 | 用途 |
|---|---|---|
| `state` | `State`（Idle/Loading/Ready/Failed/Expired） | 教学周列表的同步状态 |
| `weeks` | `SnapshotStateList<SchoolWeek>` | 教学周列表（第几周 + 起止日期） |
| `sync(schoolId, account, force)` | `(...) -> Unit` | 拉教学周列表（`CourseSync` 里自动调） |
| `ensureRange(from, to)` | `(LocalDate, LocalDate) -> Unit` | 补齐可见范围内的周（翻月时调） |
| `coursesOn(date)` | `(LocalDate) -> List<SchoolCourse>?` | **null = 这一周没有教学周数据**（不能兜底就留空），空表 = 确实没课 |
| `weekOf(date)` | `(LocalDate) -> SchoolWeek?` | 该日期属于第几教学周 |
| `clearOnLogout()` | `() -> Unit` | 退出登录时清空 |

`naturalWeekOf(date)`（`data/SchoolWeek.kt`）—— 没有教学周数据时的退路，
含该日期的那个**自然周**（周一~周日）。**只用于"本周兜底"这一处**，
不要拿它代替 `SchoolWeek` 判断"今天第几周"（它不知道寒暑假和调休）。

`ProfileRepository`：`nickname`、`major`（均为 `MutableState<String>`）。

---

## 5. 后端接入指南

1. **登录**：替换 `MainActivity` 中写死的账号密码校验为 `POST /login`，建议返回 token 并持久化。
2. **课程列表**：进入主界面时 `GET /courses` 拉取当前用户课程，写入 `CourseRepository.courses`（`courses.clear()` 后逐条 `add`）。
3. **新增**：`AddCourseScreen` 保存时已调用 `CourseRepository.add` → 在实现里先 `POST /courses` 成功后再加入本地列表（失败提示用户）。
4. **删除**：`ScheduleScreen` 长按删除已调用 `CourseRepository.remove(id)` → 在实现里先 `DELETE /courses/{id}`。
5. **个人信息**：登录后拉取用户资料写入 `ProfileRepository`；「我的」页保存昵称时 `PUT /profile`。
6. **多用户隔离**：`id` 建议改用服务端 id；如需要可加 `userId` 字段。

**注意**：
- 当前数据存内存，**杀进程后重置为示例数据**。如需本地缓存，可先用 SharedPreferences/Room 兜底，后端就绪后做双向同步。
- 所有 UI 刷新依赖 Compose 可观察状态，异步写回数据后**无需手动通知界面**。
- 项目已自带 `okhttp 5.5.0` + `logging-interceptor` 依赖（`app/build.gradle.kts`），可直接使用。

---

## 6. 设计规范速查（保持风格统一用）

来源：`campus-schedule/` 设计系统。新增 UI 时请遵守：

- **主色**：`#2563EB`（campus-blue-600），仅用于主要操作/选中态
- **圆角**：控件 12dp，卡片 12~16dp，浮层 24dp，胶囊 999dp（Chip/头像）
- **高度**：按钮 44dp，输入框 48dp，底部导航 64dp，页头 56dp
- **间距**：4dp 基准，页面边距 16dp，卡片内边距 16dp
- **液态玻璃**：内容卡片统一用 `Modifier.liquidGlass(hazeState)`（实时模糊，Haze 库）；每个 Fragment 一个 `HazeState`，背景必须是 `LiquidGlassBackground()` 且包在 `hazeSource` 里
- **图标**：用 `AppIcons`（24dp 描边风格），不要再用 `@android:drawable/*`（低清位图）
- **文案**：中文优先、2~4 字动作词（"保存课程""添加课程"）、不用 emoji 和感叹号
