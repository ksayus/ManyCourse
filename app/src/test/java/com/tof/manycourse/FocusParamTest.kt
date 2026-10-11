package com.tof.manycourse

import com.tof.manycourse.notify.FocusParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 焦点通知载荷（`miui.focus.param`）的单测。
 *
 * 这段 JSON 是**拼字符串**拼出来的，所以它只有两种失败方式，而两种都不会报错：
 *  - 少一个字段 / 名字写错 → 系统认不出来，焦点通知**静默不生效**（用户以为功能没做）；
 *  - 值没转义 → 生成的是一段坏 JSON，同样静默失败，而且**只在特定课程名上出现**
 *    （课程名里带个英文双引号就中招），极难查。
 *
 * 所以这里既钉住整体结构，也逐条钉住转义。
 */
class FocusParamTest {

    /** 整体结构一次性钉死：字段名、顺序、嵌套层级 —— 改任何一个都会让这条测试红 */
    @Test
    fun `载荷结构与官方字段一致`() {
        assertEquals(
            """{"param_v2":{"protocol":1,"business":"manycourse_class_reminder",""" +
                """"enableFloat":true,"updatable":true,"timeout":30,""" +
                """"baseInfo":{"type":1,"title":"还有 15 分钟上课","content":"高等数学 · A-101"}}}""",
            FocusParam.json("还有 15 分钟上课", "高等数学 · A-101"),
        )
    }

    /** extras 里那个键是小米定的字面量，不能改 */
    @Test
    fun `extras 键名固定`() {
        assertEquals("miui.focus.param", FocusParam.EXTRA_KEY)
    }

    /** 返回的确实是一段"以 param_v2 开头、以大括号闭合"的 JSON —— 防手滑漏括号 */
    @Test
    fun `首尾括号配平`() {
        val json = FocusParam.json("t", "c")
        assertTrue(json.startsWith("""{"param_v2":{"""))
        assertTrue(json.endsWith("}}}"))
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
    }

    @Test
    fun `超时时间可配_且不会小于一分钟`() {
        assertTrue(FocusParam.json("t", "c", timeoutMinutes = 5).contains(""""timeout":5,"""))
        // 0 / 负数会让系统认为"立刻消失"，夹到 1
        assertTrue(FocusParam.json("t", "c", timeoutMinutes = 0).contains(""""timeout":1,"""))
        assertTrue(FocusParam.json("t", "c", timeoutMinutes = -3).contains(""""timeout":1,"""))
    }

    // ══════════════════ 转义 ══════════════════

    @Test
    fun `双引号被转义`() {
        assertEquals("""他说\"上课\"""", FocusParam.escape("""他说"上课""""))
    }

    @Test
    fun `反斜杠被转义`() {
        assertEquals("""a\\b""", FocusParam.escape("""a\b"""))
    }

    @Test
    fun `换行与制表被转义`() {
        assertEquals("""a\nb""", FocusParam.escape("a\nb"))
        assertEquals("""a\rb""", FocusParam.escape("a\rb"))
        assertEquals("""a\tb""", FocusParam.escape("a\tb"))
    }

    /** 控制字符（从 HTML 带过来的脏数据里很常见）必须转成 \uXXXX，不能裸着进 JSON */
    @Test
    fun `控制字符被转成 u 形式`() {
        assertEquals("""\u0001""", FocusParam.escape("\u0001"))
        assertEquals("""\u0000""", FocusParam.escape("\u0000"))
    }

    /** 中文与 emoji **不转义**：JSON 允许直接放 UTF-8，转成 \uXXXX 只会白白变长 */
    @Test
    fun `中文与 emoji 原样保留`() {
        assertEquals("高等数学", FocusParam.escape("高等数学"))
        assertEquals("体育🏀", FocusParam.escape("体育🏀"))
    }

    /** 端到端：带引号的课程名拼进去之后，仍是**合法**的转义序列 */
    @Test
    fun `带引号的标题不会撑破 JSON`() {
        val json = FocusParam.json("""听说"要"上课""", """A-101 "东"楼""")
        assertTrue(json.contains(""""title":"听说\"要\"上课""""))
        assertTrue(json.contains(""""content":"A-101 \"东\"楼""""))
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
    }
}
