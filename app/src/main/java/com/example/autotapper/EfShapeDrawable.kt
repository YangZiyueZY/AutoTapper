package com.example.autotapper

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * 终末地风味的块状背景：直角 + 可选切角，纯色填充 + 1px 描边。
 *
 * 不用 MaterialShapeDrawable：它在悬浮窗（无主题的 Service Context）里填充色不生效，
 * 面板会变成透明，只剩描边。这里直接画 Path，行为可预期。
 */
class EfShapeDrawable(
    private val fillColor: Int,
    private val strokeColor: Int = 0,
    private val strokeWidth: Float = 0f,
    private val cutPx: Float = 0f,
    private val corner: Corner = Corner.BOTTOM_END
) : Drawable() {

    enum class Corner { NONE, BOTTOM_END }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = fillColor
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = strokeColor
        this.strokeWidth = strokeWidth
    }

    private val path = Path()

    override fun draw(canvas: Canvas) {
        val bounds: Rect = bounds
        if (bounds.isEmpty) {
            return
        }
        buildPath(bounds)
        if (fillColor != 0) {
            canvas.drawPath(path, fillPaint)
        }
        if (strokeColor != 0 && strokeWidth > 0f) {
            canvas.drawPath(path, strokePaint)
        }
    }

    private fun buildPath(bounds: Rect) {
        path.reset()
        val left = bounds.left + strokeWidth / 2f
        val top = bounds.top + strokeWidth / 2f
        val right = bounds.right - strokeWidth / 2f
        val bottom = bounds.bottom - strokeWidth / 2f
        val cut = cutPx.coerceAtMost(minOf(right - left, bottom - top) / 2f)

        path.moveTo(left, top)
        path.lineTo(right, top)
        if (corner == Corner.BOTTOM_END && cut > 0f) {
            path.lineTo(right, bottom - cut)
            path.lineTo(right - cut, bottom)
        } else {
            path.lineTo(right, bottom)
        }
        path.lineTo(left, bottom)
        path.close()
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
