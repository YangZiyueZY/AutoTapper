package com.example.autotapper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import com.example.autotapper.Ef
import com.example.autotapper.R

/**
 * 斜线纹空状态容器：45° 墨色细线（ef_hatch，墨色 22%），线宽 1.5dp、沿 x 每 9.2dp 一条，
 * 中间放一句说明文字。用在“这里还没有内容”的位置。
 */
class EfHatch @JvmOverloads constructor(
    context: Context,
    message: String = "",
    minHeightDp: Int = 96
) : FrameLayout(context) {

    private val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Ef.color(context, R.color.ef_hatch)
        strokeWidth = Ef.dp(context, 1.5f).toFloat()
    }
    private val stepPx = Ef.dp(context, 9.2f).toFloat()

    private val messageView: TextView = TextView(context).apply {
        this.text = message
        textSize = 13f
        setTextColor(Ef.color(context, R.color.ef_ink2))
        gravity = Gravity.CENTER
        includeFontPadding = false
        layoutParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
    }

    init {
        // FrameLayout 在没有背景时会被打上 WILL_NOT_DRAW 标记，onDraw 根本不会被调用，
        // 于是斜线纹一直画不出来（空状态看起来就是一片纯纸）。必须显式关掉这个优化。
        setWillNotDraw(false)
        minimumHeight = Ef.dp(context, minHeightDp)
        setPadding(Ef.dp(context, 20), Ef.dp(context, 20), Ef.dp(context, 20), Ef.dp(context, 20))
        addView(messageView)
    }

    fun setMessage(text: String) {
        messageView.text = text
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) {
            return
        }
        // 45°：从底部 y = h 起，每 stepPx 一条，向左延伸到顶边之外
        var x = -h
        while (x < w) {
            canvas.drawLine(x, h, x + h, 0f, hatchPaint)
            x += stepPx
        }
    }
}
