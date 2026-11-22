package com.example.autotapper.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.autotapper.Ef
import com.example.autotapper.R

/**
 * 分段选择：一组选项里单选一段。
 *
 * 选中段墨底反白（ef_ink 底 + ef_paper 字），未选中是安静的 chip 底 + 墨字；
 * 切角表示可点，圆角一律 0，每段高度不小于 48dp。
 */
class EfSegmented @JvmOverloads constructor(
    context: Context,
    options: List<String> = emptyList(),
    var onSelected: ((Int) -> Unit)? = null
) : LinearLayout(context) {

    private val cells = mutableListOf<TextView>()
    private var labels: List<String> = emptyList()
    private var selectedIndex = 0

    /**
     * 是否让各段等分父宽度。默认 true（一行铺满）；
     * 段数多、文案长（例如日志页的「全部 / INFO / WARN / ERROR / RESULT」）时设成 false，
     * 每段按文字自适应宽度，外层套 HorizontalScrollView 就能横滑，不会把最后一段切掉。
     */
    private var fillWidth: Boolean = true

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setOptions(options)
    }

    /** 换一组选项；选中位置尽量保持，越界时收回到最后一段。 */
    fun setOptions(list: List<String>) {
        labels = list
        removeAllViews()
        cells.clear()
        if (list.isEmpty()) {
            selectedIndex = 0
            return
        }
        val gap = Ef.dp(context, 2)
        list.forEachIndexed { index, label ->
            val cell = TextView(context).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                isSingleLine = true
                includeFontPadding = false
                contentDescription = label
                isClickable = true
                isFocusable = true
                minHeight = Ef.dp(context, 48)
                setPadding(Ef.dp(context, 6), Ef.dp(context, 12), Ef.dp(context, 6), Ef.dp(context, 12))
                Ef.applyWide(this, weight = 600, width = 118)
                setOnClickListener { select(index, notify = true) }
                layoutParams = if (fillWidth) {
                    LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        if (index > 0) marginStart = gap
                    }
                } else {
                    LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        if (index > 0) marginStart = gap
                    }
                }
            }
            addView(cell)
            cells.add(cell)
        }
        selectedIndex = selectedIndex.coerceIn(0, list.size - 1)
        applySelection()
    }

    /** 切换"等分宽度 / 按文字自适应"。改完会重建选项，选中位置保持不变。 */
    fun setFillWidth(fill: Boolean) {
        if (fillWidth == fill) return
        fillWidth = fill
        setOptions(labels)
    }

    /** 当前选中段的下标；没有选项时返回 -1。 */
    fun getSelectedIndex(): Int = if (cells.isEmpty()) -1 else selectedIndex

    /** 当前选中段的文案；没有选项时返回空串。 */
    fun getSelectedOption(): String = labels.getOrElse(selectedIndex) { "" }

    /**
     * 设置选中段。notify = true 时回调 onSelected，默认不回调（供界面回填使用）。
     */
    @JvmOverloads
    fun setSelected(index: Int, notify: Boolean = false) {
        select(index, notify)
    }

    private fun select(index: Int, notify: Boolean) {
        if (cells.isEmpty()) {
            selectedIndex = 0
            return
        }
        selectedIndex = index.coerceIn(0, cells.size - 1)
        applySelection()
        if (notify) {
            onSelected?.invoke(selectedIndex)
        }
    }

    private fun applySelection() {
        cells.forEachIndexed { index, cell ->
            val active = index == selectedIndex
            cell.background = Ef.cutBackground(
                context,
                if (active) R.color.ef_ink else R.color.ef_chip,
                null,
                cutDp = Ef.CUT_DP
            )
            cell.setTextColor(Ef.color(context, if (active) R.color.ef_paper else R.color.ef_ink))
            cell.isSelected = active
        }
    }
}