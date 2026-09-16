package com.tof.manycourse.gr_api

/**
 * 极简 HTML 取值工具。
 *
 * 为什么不用 Jsoup：教务系统的页面是"能跑就行"的老 WebForms/MVC 产物，
 * 我们要取的只是**几个固定 id 的隐藏字段、一段 span 文本、一张表格的单元格**。
 * 为此引一个 HTML 解析库（还要处理它自己的依赖）不划算；
 * 这里的实现都只依赖"属性顺序可能变、大小写可能变、引号可能是单引号"这几条现实。
 *
 * 约定：所有函数都**不抛异常**，取不到就返回 null / 空串 —— 教务系统改版时
 * 应该表现为"某个字段没同步上"，而不是整个登录流程崩掉。
 */

private val ATTR_REGEX = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

/**
 * **没有值**的属性名（HTML 里的布尔属性）。
 *
 * 为什么必须认：`<option value='202601' selected>` —— 广东工业大学课表页的学期下拉框
 * **就是这么写的**（`selected` 后面没有任何 `=`）。只认 `name="value"` 的话，
 * `selected` 会被整个丢掉，于是 [htmlSelectedOption] 永远返回 null ——
 * 表现成"读不到当前学年学期"，而且**不报任何错**。
 */
private val BARE_ATTR_REGEX = Regex("""(?:^|\s)([A-Za-z_:][-A-Za-z0-9_:.]*)(?=[\s>/]|$)""")

/** 解析一个标签里的全部属性（`<input name="a" value='b' required>` → `{name=a, value=b, required=}`） */
private fun parseAttributes(tag: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val remaining = StringBuilder(tag)
    ATTR_REGEX.findAll(tag).forEach { m ->
        // 双引号捕获组是 2，单引号是 3
        out[m.groupValues[1].lowercase()] = if (m.groupValues[2].isNotEmpty() || m.groupValues[3].isEmpty()) {
            m.groupValues[2]
        } else {
            m.groupValues[3]
        }
        // 把已识别的 `name="value"` 抹成空格，剩下的才是布尔属性 ——
        // 不抹的话，`value="selected"` 里的 "selected" 会被当成一个属性名
        for (i in m.range) remaining.setCharAt(i, ' ')
    }
    BARE_ATTR_REGEX.findAll(remaining.toString()).forEach { m ->
        out.putIfAbsent(m.groupValues[1].lowercase(), "")
    }
    return out
}

/** HTML 实体解码。教务系统里最常见的是 `&nbsp;`，其次是 `&amp;` / `&#39;` */
internal fun String.unescapeHtml(): String {
    if (!contains('&')) return this
    return replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace(Regex("""&#(\d+);""")) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }
}

/** 去掉全部标签，并把实体解码、空白压平 —— 用于"只要那句人话"的场景 */
internal fun String.stripTags(): String =
    replace(Regex("""<(script|style)\b[\s\S]*?</\1>""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""<[^>]*>"""), " ")
        .unescapeHtml()
        .replace(Regex("""\s+"""), " ")
        .trim()

/**
 * 取控件（`<input>`）的值，按 `name` 或 `id` 匹配，**与属性书写顺序无关**。
 *
 * 用于 WebForms 的 `__VIEWSTATE` / `__VIEWSTATEGENERATOR` / `__VIEWSTATEENCRYPTED`
 * 这类必须原样回传的隐藏字段。
 */
internal fun String.htmlInputValue(nameOrId: String): String? {
    Regex("""<input\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(this).forEach { m ->
        val attrs = parseAttributes(m.value)
        if (attrs["name"] == nameOrId || attrs["id"] == nameOrId) return attrs["value"] ?: ""
    }
    return null
}

/**
 * 取某个 id 元素的**纯文本**，如 `<span id="LabXm">李同学</span>` → `李同学`。
 *
 * 只匹配"最近的同名闭合标签"，对嵌套同标签的页面（教务系统里少见）不保证完全正确 ——
 * 我们要取的 `LabXxx` 都是叶子节点，够用。
 */
internal fun String.htmlElementText(id: String): String? {
    val escaped = Regex.escape(id)
    val m = Regex(
        """<(\w+)\b[^>]*\bid\s*=\s*["']$escaped["'][^>]*>([\s\S]*?)</\1>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).find(this) ?: return null
    return m.groupValues[2].stripTags()
}

/** `<select>` 里的所有选项：`value to 显示文本` */
internal fun String.htmlSelectOptions(selectId: String): List<Pair<String, String>> {
    val escaped = Regex.escape(selectId)
    val select = Regex(
        """<select\b[^>]*\bid\s*=\s*["']$escaped["'][^>]*>([\s\S]*?)</select>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).find(this) ?: return emptyList()
    return Regex("""<option\b([^>]*)>([\s\S]*?)</option>""", RegexOption.IGNORE_CASE)
        .findAll(select.groupValues[1])
        .map { m ->
            parseAttributes(m.groupValues[1])["value"].orEmpty() to m.groupValues[2].stripTags()
        }
        .toList()
}

/**
 * `<select>` 上带 `selected` 的那个 option 的 value。
 *
 * 金城学院的课表查询页靠它给出**当前学年学期**（如 `2026-2027,1`）、
 * 广东工业大学的课表页靠它给出 `xnxqdm`（如 `202601`）——
 * 直接用页面给的默认值，比在客户端猜"现在是第几学期"可靠得多。
 *
 * `selected` 的两种写法都认：`selected="selected"` 和**光秃秃的 `selected`**
 * （广工那页就是后者，见 [BARE_ATTR_REGEX]）。
 */
internal fun String.htmlSelectedOption(selectId: String): String? {
    val escaped = Regex.escape(selectId)
    val select = Regex(
        """<select\b[^>]*\bid\s*=\s*["']$escaped["'][^>]*>([\s\S]*?)</select>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).find(this) ?: return null
    val selected = Regex("""<option\b([^>]*)>([\s\S]*?)</option>""", RegexOption.IGNORE_CASE)
        .findAll(select.groupValues[1])
        .firstOrNull { "selected" in parseAttributes(it.groupValues[1]) }
        ?: return null
    return parseAttributes(selected.groupValues[1])["value"]
}

/** 取整张表格的 HTML（含标签），找不到返回 null */
internal fun String.htmlTable(tableId: String): String? {
    val escaped = Regex.escape(tableId)
    return Regex(
        """<table\b[^>]*\bid\s*=\s*["']$escaped["'][^>]*>[\s\S]*?</table>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    ).find(this)?.value
}

/** 表格里的所有行（每行是"单元格 HTML 列表"，已去掉 td/th 外层标签） */
internal fun String.htmlTableRows(): List<List<String>> =
    Regex("""<tr\b[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE).findAll(this).map { row ->
        Regex("""<t[dh]\b[^>]*>([\s\S]*?)</t[dh]>""", RegexOption.IGNORE_CASE)
            .findAll(row.groupValues[1])
            .map { it.groupValues[1] }
            .toList()
    }.toList()

/**
 * 把单元格按 `<br>` 拆成多行 —— 这是金城学院课表单元格的实际分隔方式：
 *
 * ```html
 * 英语听说（一）&nbsp;&nbsp;A1N403&nbsp;&nbsp;禄口<br>孔雁&nbsp;&nbsp;02220101&nbsp;&nbsp;3-5,8-20周
 * ```
 */
internal fun String.htmlLines(): List<String> =
    Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE).split(this)

/** 按连续的 `&nbsp;`（或普通空格）切分字段，丢掉空串 */
internal fun String.nbspFields(): List<String> =
    unescapeHtml()
        .split(Regex("""\s{2,}"""))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
