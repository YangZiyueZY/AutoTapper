package com.example.autotapper

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * 终末地风味的界面基元（颜色、切角块、宽体字、字段行）。
 * 规范见 https://github.com/hhikr/endfield-ui-skill：纸面 + 墨色，只有一种强调色 ef_signal，
 * 切角表示可点击，圆角一律 0，细线分区，不用阴影和涟漪。
 */
object Ef {

    /** 切块尺寸（dp） */
    const val CUT_DP = 8f

    fun color(context: Context, colorRes: Int): Int = ContextCompat.getColor(context, colorRes)

    fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun dp(context: Context, value: Int): Int = dp(context, value.toFloat())

    /** 可点击的块：切掉右下角。 */
    fun cutBackground(
        context: Context,
        fillRes: Int,
        strokeRes: Int? = null,
        cutDp: Float = CUT_DP,
        strokeWidthDp: Float = 1f
    ): EfShapeDrawable = EfShapeDrawable(
        fillColor = color(context, fillRes),
        strokeColor = strokeRes?.let { color(context, it) } ?: 0,
        strokeWidth = dp(context, strokeWidthDp).toFloat(),
        cutPx = dp(context, cutDp).toFloat(),
        corner = EfShapeDrawable.Corner.BOTTOM_END
    )

    /** 不可点击的容器：直角 + 1px 细线。 */
    fun rectBackground(
        context: Context,
        fillRes: Int,
        strokeRes: Int? = null,
        strokeWidthDp: Float = 1f
    ): EfShapeDrawable = EfShapeDrawable(
        fillColor = color(context, fillRes),
        strokeColor = strokeRes?.let { color(context, it) } ?: 0,
        strokeWidth = dp(context, strokeWidthDp).toFloat(),
        cutPx = 0f,
        corner = EfShapeDrawable.Corner.NONE
    )

    /** Saira 宽体拉丁字；中文自动回落到系统黑体。 */
    fun applyWide(textView: TextView, weight: Int = 600, width: Int = 125) {
        val typeface = ResourcesCompat.getFont(textView.context, R.font.saira) ?: return
        textView.typeface = typeface
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { textView.fontVariationSettings = "'wght' $weight, 'wdth' $width" }
        }
    }

    /** 11sp 大写字母间距眉标。 */
    fun eyebrow(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.14f
        setTextColor(color(context, R.color.ef_ink2))
        isAllCaps = true
        applyWide(this, weight = 600, width = 125)
    }

    /** 宽体字段（编号、间隔、读数）。 */
    fun field(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(color(context, R.color.ef_ink2))
        applyWide(this, weight = 400, width = 112)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            fontFeatureSettings = "tnum"
        }
    }

    /** 中文小标题（粗体黑体）。 */
    fun heading(context: Context, text: String, textSizeSp: Float = 16f): TextView =
        TextView(context).apply {
            this.text = text
            this.textSize = textSizeSp
            setTextColor(color(context, R.color.ef_ink))
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        }

    fun spacer(context: Context, widthDp: Int = 0, heightDp: Int = 0): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, widthDp), dp(context, heightDp))
        }

    fun matchSpacer(context: Context, heightDp: Int = 1): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, heightDp)
            )
            setBackgroundColor(color(context, R.color.ef_rule))
        }
}

/**
 * 终末地风味的按钮：切角块，按下时强调色从左擦入（主要按钮按下时墨色压暗），没有涟漪。
 */
class EfBlock @JvmOverloads constructor(
    context: Context,
    private val labelText: String = "",
    private val primary: Boolean = false,
    private val danger: Boolean = false,
    private val iconRes: Int = 0,
    textSizeSp: Float = 13f
) : LinearLayout(context) {

    private val labelView = TextView(context)
    private val iconView = ImageView(context)
    private var restBackground: EfShapeDrawable
    private var pressedBackground: EfShapeDrawable
    private var pressed = false
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = labelText
        setPadding(Ef.dp(context, 6), Ef.dp(context, 8), Ef.dp(context, 6), Ef.dp(context, 8))

        val baseFill = when {
            primary -> R.color.ef_signal
            else -> R.color.ef_chip
        }
        val pressedFill = when {
            primary -> R.color.ef_ink
            danger -> R.color.ef_danger
            else -> R.color.ef_signal
        }
        val baseStroke = when {
            primary -> null
            else -> R.color.ef_rule
        }
        restBackground = Ef.cutBackground(context, baseFill, baseStroke)
        pressedBackground = Ef.cutBackground(context, pressedFill, baseStroke)
        background = restBackground

        if (iconRes != 0) {
            iconView.setImageResource(iconRes)
            iconView.layoutParams = LayoutParams(Ef.dp(context, 18), Ef.dp(context, 18)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            addView(iconView)
            val gap = View(context).apply { layoutParams = LayoutParams(1, Ef.dp(context, 5)) }
            addView(gap)
        }

        labelView.text = labelText
        labelView.textSize = textSizeSp
        // 面板里按钮很窄：文案必须一行放得下（STOP 不能折成 STO/P）
        labelView.letterSpacing = 0.08f
        labelView.isSingleLine = true
        labelView.gravity = Gravity.CENTER
        labelView.includeFontPadding = false
        labelView.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
        Ef.applyWide(labelView, weight = 700, width = 125)
        addView(labelView)

        applyColors()
    }

    /** 同一个按钮切换文案（GO ↔ STOP）。 */
    fun setLabel(text: String) {
        labelView.text = text
        contentDescription = text
    }

    private fun applyColors() {
        val fg = when {
            pressed && primary -> R.color.ef_signal
            pressed && danger -> R.color.ef_panel
            pressed && !primary -> R.color.ef_on_signal
            primary -> R.color.ef_on_signal
            danger -> R.color.ef_danger
            else -> R.color.ef_ink
        }
        labelView.setTextColor(Ef.color(context, fg))
        if (iconRes != 0) {
            iconView.setColorFilter(Ef.color(context, fg))
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                setPressedState(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val outside = Math.abs(event.rawX - downX) > touchSlop * 3 ||
                    Math.abs(event.rawY - downY) > touchSlop * 3
                if (outside && pressed) {
                    setPressedState(false)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val inside = isInside(event.rawX, event.rawY)
                setPressedState(false)
                if (inside) {
                    performClick()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                setPressedState(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isInside(rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return rawX >= location[0] && rawX <= location[0] + width &&
            rawY >= location[1] && rawY <= location[1] + height
    }

    private fun setPressedState(value: Boolean) {
        if (pressed == value) {
            return
        }
        pressed = value
        background = if (value) pressedBackground else restBackground
        applyColors()
        if (value) {
            labelView.alpha = 0.92f
        } else {
            labelView.alpha = 1f
        }
    }
}
