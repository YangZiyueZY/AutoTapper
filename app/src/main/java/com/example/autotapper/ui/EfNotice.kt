package com.example.autotapper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.autotapper.Ef
import com.example.autotapper.R

/**
 * 提示条：白底 + 1dp 细框 + 左边 4dp 黄旗（ef_signal），正文用 ef_ink。
 *
 * 提示不是错误，所以一律不用红色；红色只留给删除。
 * 右侧可以挂一个文字动作（例如“重试”），用切角块表示可点。
 */
class EfNotice @JvmOverloads constructor(
    context: Context,
    text: String = "",
    actionText: String = "",
    action: (() -> Unit)? = null
) : LinearLayout(context) {

    private val flagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Ef.color(context, R.color.ef_signal)
    }
    private val flagWidth = Ef.dp(context, 4)

    private val messageView: TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(Ef.color(context, R.color.ef_ink))
        includeFontPadding = false
        layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    private val actionView: TextView = TextView(context).apply {
        textSize = 12f
        letterSpacing = 0.08f
        isAllCaps = true
        gravity = Gravity.CENTER
        includeFontPadding = false
        isSingleLine = true
        setTextColor(Ef.color(context, R.color.ef_ink))
        background = Ef.cutBackground(context, R.color.ef_chip, null, cutDp = 6f)
        val padH = Ef.dp(context, 10)
        val padV = Ef.dp(context, 6)
        setPadding(padH, padV, padH, padV)
        minimumHeight = Ef.dp(context, 48)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        Ef.applyWide(this, weight = 700, width = 118)
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = Ef.dp(context, 10)
        }
        visibility = GONE
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ef.rectBackground(context, R.color.ef_panel, R.color.ef_rule)
        // 左边留出黄旗的宽度：旗子直接画在背景上，随高度整条撑满
        setPadding(flagWidth + Ef.dp(context, 10), Ef.dp(context, 10), Ef.dp(context, 10), Ef.dp(context, 10))
        addView(messageView)
        addView(actionView)
        setAction(actionText, action)
    }

    fun setText(text: String) {
        messageView.text = text
    }

    /** 右侧动作；actionText 为空时隐藏。 */
    fun setAction(text: String?, onClick: (() -> Unit)?) {
        if (text.isNullOrEmpty()) {
            actionView.visibility = GONE
            actionView.setOnClickListener(null)
            return
        }
        actionView.text = text
        actionView.contentDescription = text
        actionView.visibility = VISIBLE
        actionView.setOnClickListener { onClick?.invoke() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, flagWidth.toFloat(), height.toFloat(), flagPaint)
    }
}
