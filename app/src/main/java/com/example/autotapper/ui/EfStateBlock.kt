package com.example.autotapper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.view.View
import com.example.autotapper.Ef
import com.example.autotapper.R
import kotlin.math.ceil
import kotlin.math.max

/**
 * 三态状态块，文字 12sp、圆角 0：
 *  - SOLID：实线墨框 —— 这一项正在生效；
 *  - DASHED：虚线灰框 —— 在后台或已停用；
 *  - FLAG：黄块（ef_signal 填充）—— 要人看一眼。
 *
 * 直接用 Paint 画框（EfShapeDrawable 的描边本身不支持虚线），颜色仍然只取 ef_*。
 */
class EfStateBlock @JvmOverloads constructor(
    context: Context,
    kind: Kind = Kind.SOLID,
    text: String = ""
) : View(context) {

    enum class Kind { SOLID, DASHED, FLAG }

    private var stateKind: Kind = kind
    private var stateText: String = text

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val blockRect = RectF()

    private val strokeWidth = Ef.dp(context, 1.5f).toFloat()
    private val innerH = Ef.dp(context, 10)
    private val innerV = Ef.dp(context, 6)

    init {
        contentDescription = text
        applyKind()
    }

    fun setKind(kind: Kind) {
        if (stateKind == kind) {
            return
        }
        stateKind = kind
        applyKind()
        requestLayout()
        invalidate()
    }

    fun getKind(): Kind = stateKind

    fun setText(text: String) {
        if (stateText == text) {
            return
        }
        stateText = text
        contentDescription = text
        requestLayout()
        invalidate()
    }

    fun getText(): String = stateText

    private fun applyKind() {
        when (stateKind) {
            Kind.SOLID -> {
                fillPaint.color = 0
                strokePaint.color = Ef.color(context, R.color.ef_ink)
                strokePaint.pathEffect = null
                textPaint.color = Ef.color(context, R.color.ef_ink)
            }

            Kind.DASHED -> {
                fillPaint.color = 0
                strokePaint.color = Ef.color(context, R.color.ef_ink2)
                strokePaint.pathEffect = DashPathEffect(
                    floatArrayOf(Ef.dp(context, 4).toFloat(), Ef.dp(context, 3).toFloat()),
                    0f
                )
                textPaint.color = Ef.color(context, R.color.ef_ink2)
            }

            Kind.FLAG -> {
                fillPaint.color = Ef.color(context, R.color.ef_signal)
                strokePaint.color = 0
                strokePaint.pathEffect = null
                textPaint.color = Ef.color(context, R.color.ef_on_signal)
            }
        }
        strokePaint.strokeWidth = strokeWidth
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val metrics = textPaint.fontMetrics
        val textWidth = ceil(textPaint.measureText(stateText).toDouble()).toInt()
        val textHeight = ceil((metrics.descent - metrics.ascent).toDouble()).toInt()
        val desiredWidth = paddingLeft + innerH + textWidth + innerH + paddingRight
        val desiredHeight = max(
            minimumHeight,
            paddingTop + innerV + textHeight + innerV + paddingBottom
        )
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = if (stateKind == Kind.FLAG) 0f else strokeWidth / 2f
        blockRect.set(inset, inset, width - inset, height - inset)
        if (stateKind == Kind.FLAG) {
            canvas.drawRect(blockRect, fillPaint)
        } else {
            canvas.drawRect(blockRect, strokePaint)
        }

        val metrics = textPaint.fontMetrics
        val baseline = height / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(stateText, (paddingLeft + innerH).toFloat(), baseline, textPaint)
    }
}
