package com.example.autotapper.ui

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.example.autotapper.Ef
import com.example.autotapper.R

/**
 * 区间输入：两个数字输入框用斜线 "/" 分隔，表示 "最小值 / 最大值"。
 *
 * 输入框是直角板色块 + 1dp 细线；单位是可选的右侧小字。
 */
class EfRangeInput @JvmOverloads constructor(
    context: Context,
    minValue: Long = 0L,
    maxValue: Long = 1000L
) : LinearLayout(context) {

    private var lastMin: Long = minValue
    private var lastMax: Long = maxValue

    private val minEdit: EditText = buildEdit(minValue.toString())
    private val maxEdit: EditText = buildEdit(maxValue.toString())
    private val slashView: TextView = TextView(context).apply {
        text = "/"
        textSize = 15f
        setTextColor(Ef.color(context, R.color.ef_ink2))
        gravity = Gravity.CENTER
        includeFontPadding = false
        Ef.applyWide(this, weight = 400, width = 112)
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = Ef.dp(context, 8)
            marginEnd = Ef.dp(context, 8)
        }
    }
    private val unitView: TextView = TextView(context).apply {
        textSize = 13f
        setTextColor(Ef.color(context, R.color.ef_ink2))
        includeFontPadding = false
        visibility = GONE
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = Ef.dp(context, 8)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(minEdit)
        addView(slashView)
        addView(maxEdit)
        addView(unitView)
    }

    private fun buildEdit(initial: String): EditText = EditText(context).apply {
        setText(initial)
        inputType = InputType.TYPE_CLASS_NUMBER
        textSize = 14f
        setTextColor(Ef.color(context, R.color.ef_ink))
        gravity = Gravity.CENTER
        isSingleLine = true
        maxLines = 1
        includeFontPadding = false
        typeface = Typeface.DEFAULT_BOLD
        background = Ef.rectBackground(context, R.color.ef_panel, R.color.ef_rule)
        setPadding(Ef.dp(context, 10), Ef.dp(context, 10), Ef.dp(context, 10), Ef.dp(context, 10))
        minimumHeight = Ef.dp(context, 48)
        layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    /** 读回区间；输入不合法时回退到上一次的有效值，min > max 时交换。 */
    fun getRange(): Pair<Long, Long> {
        var min = parse(minEdit.text?.toString()) ?: lastMin
        var max = parse(maxEdit.text?.toString()) ?: lastMax
        if (min > max) {
            val swap = min
            min = max
            max = swap
        }
        lastMin = min
        lastMax = max
        return min to max
    }

    /** 回填区间（不触发任何回调）。 */
    fun setRange(min: Long, max: Long) {
        lastMin = min
        lastMax = max
        minEdit.setText(min.toString())
        maxEdit.setText(max.toString())
    }

    /** 设置单位小字；传 null 隐藏。 */
    fun setUnit(unit: String?) {
        if (unit.isNullOrEmpty()) {
            unitView.visibility = GONE
        } else {
            unitView.text = unit
            unitView.visibility = VISIBLE
        }
    }

    private fun parse(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) {
            return null
        }
        val value = text.toLongOrNull() ?: return null
        return value.coerceAtLeast(0L)
    }
}
