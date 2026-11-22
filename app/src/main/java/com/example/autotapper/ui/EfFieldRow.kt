package com.example.autotapper.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.autotapper.Ef
import com.example.autotapper.R

/**
 * 一行字段：左侧标签、右侧读数，下面一条 1dp 细线。
 *
 * 标签与读数都用 Ef.field（13sp、宽体等宽数字）；读数取真实数据，不写死。
 */
class EfFieldRow @JvmOverloads constructor(
    context: Context,
    label: String = "",
    value: String = ""
) : LinearLayout(context) {

    private val labelView: TextView = Ef.field(context, label).apply {
        isSingleLine = true
        includeFontPadding = false
        layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private val valueView: TextView = Ef.field(context, value).apply {
        gravity = Gravity.END
        isSingleLine = true
        includeFontPadding = false
        setTextColor(Ef.color(context, R.color.ef_ink))
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    init {
        orientation = VERTICAL

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ef.dp(context, 44)
        }
        row.addView(labelView)
        row.addView(valueView)
        addView(row)

        val rule = View(context).apply {
            setBackgroundColor(Ef.color(context, R.color.ef_rule))
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ef.dp(context, 1)).apply {
                topMargin = Ef.dp(context, 8)
            }
        }
        addView(rule)
    }

    /** 更新读数。 */
    fun setValue(text: String) {
        valueView.text = text
    }

    fun setLabel(text: String) {
        labelView.text = text
    }
}
