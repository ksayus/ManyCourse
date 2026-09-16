package com.tof.manycourse.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.TextView
import com.tof.manycourse.R
import com.tof.manycourse.data.School

/**
 * 登录页「选择学校」下拉的适配器（两行式：校名 + 教务系统域名）。
 *
 * 这里刻意关掉了 AutoCompleteTextView 的两个默认行为，两个都是"下拉只剩一条"的经典坑：
 *
 *  1. **过滤**：AutoCompleteTextView 每次 setText 都会拿当前文本去 filter，
 *     于是选中「广州软件学院」后再点开，列表里就只剩这一所。
 *     这里用一个不做任何过滤的 [Filter] 顶掉默认实现 —— 下拉永远展示**全部**学校。
 *  2. **显示文本**：选中项写回输入框时，默认走 `Filter.convertResultToString()` →
 *     `item.toString()`。学校是个 data class，直接 toString 会写出
 *     `School(id=gzus, name=..., ...)` 这种东西。这里覆写成校名。
 */
class SchoolDropdownAdapter(
    context: Context,
    private val items: List<School>,
) : ArrayAdapter<School>(context, R.layout.item_school_dropdown, items) {

    private val passThroughFilter = object : Filter() {

        override fun performFiltering(constraint: CharSequence?): FilterResults =
            FilterResults().apply {
                values = items
                count = items.size
            }

        /** 什么都不做：上面的 values 只是回给框架，适配器自身的清单保持完整 */
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) = Unit

        /** 选中后写进输入框的文字 = 校名 */
        override fun convertResultToString(resultValue: Any?): CharSequence =
            (resultValue as? School)?.name.orEmpty()
    }

    override fun getFilter(): Filter = passThroughFilter

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_school_dropdown, parent, false)
        val school = items[position]
        view.findViewById<TextView>(R.id.school_item_name).text = school.name
        view.findViewById<TextView>(R.id.school_item_host).text = school.host
        return view
    }
}
