package com.tof.manycourse

import com.tof.manycourse.gr_api.htmlInputValue
import com.tof.manycourse.gr_api.htmlSelectOptions
import com.tof.manycourse.gr_api.htmlSelectedOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `Html.kt` 里那几个取值工具的回归测试（JVM）。
 *
 * 这些函数被三家学校的解析器共用，坏掉的后果是"某所学校的登录突然全都登不上"
 * 或者"读不到当前学期"，而且**都不抛异常**（约定就是取不到返回 null），
 * 所以只能靠这里钉住。
 *
 * 本文件里的页面片段全部来自实测：
 *  - 广东工业大学课表页的学期下拉框：`<option value='202601' selected>`（**布尔属性没有值**）；
 *  - 广软/金城的页面：`selected="selected"`（另一种写法，也要继续认）。
 */
class HtmlTest {

    @Test
    fun readsInputValueByIdOrNameRegardlessOfAttributeOrder() {
        val html = """<input type="hidden" value="a-b-c" id="execution" name="execution">"""
        assertEquals("a-b-c", html.htmlInputValue("execution"))
        // 只按 id 匹配（广工的盐那个 input 没有 name）
        assertEquals("salt", """<input type="hidden" id="pwdEncryptSalt" value="salt" />""".htmlInputValue("pwdEncryptSalt"))
        // 属性顺序换了也要认
        assertEquals("x", """<input value='x' name='lt'>""".htmlInputValue("lt"))
        // 不存在就是 null（调用方据此判断"页面改版了"）
        assertNull("""<input name="lt" value="">""".htmlInputValue("nothing"))
    }

    @Test
    fun understandsValuelessBooleanAttributes() {
        // ★ 广工课表页就是这么写的：`selected` 后面没有 `=`
        // 不认布尔属性 → htmlSelectedOption 永远返回 null → "读不到当前学年学期"，而且不报错
        val page = """
            <select id='xnxqdm' name='xnxqdm' class='ntssselect' >
              <option value='202602' >2027春季</option><option value='202601' selected>2026秋季</option>
            </select>
        """.trimIndent()
        assertEquals("202601", page.htmlSelectedOption("xnxqdm"))
        assertEquals(
            listOf("202602" to "2027春季", "202601" to "2026秋季"),
            page.htmlSelectOptions("xnxqdm"),
        )
    }

    @Test
    fun stillUnderstandsSelectedEqualsSelected() {
        // 广软/金城那两套页面的写法：不能因为支持布尔属性就把这个弄坏
        val page = """<select id="xnm"><option value="2025">旧</option><option value="2026" selected="selected">新</option></select>"""
        assertEquals("2026", page.htmlSelectedOption("xnm"))
    }

    @Test
    fun aValueThatLooksLikeAnAttributeNameIsNotMistakenForOne() {
        // `value="selected"` 里的 selected 是**值**，不是属性名；
        // 先把 name="value" 抹掉再找布尔属性，就是为了防这个
        val page = """<select id="zs"><option value="selected">第1周</option><option value="3" selected>第3周</option></select>"""
        assertEquals("3", page.htmlSelectedOption("zs"))
    }

    @Test
    fun noSelectedOptionMeansNull() {
        // 服务端没标 selected 时不要瞎猜第一个 —— 上层会报错让用户知道页面变了
        assertNull("""<select id="xnxqdm"><option value="202601">2026秋季</option></select>""".htmlSelectedOption("xnxqdm"))
        assertNull("""<div>没有 select</div>""".htmlSelectedOption("xnxqdm"))
    }
}
