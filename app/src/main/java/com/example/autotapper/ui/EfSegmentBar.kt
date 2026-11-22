package com.example.autotapper.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.autotapper.Ef
import com.example.autotapper.R
import kotlin.math.roundToInt

/**
 * 分段进度条：24 格 + 右侧百分比读数。
 *
 * 填满的格子用 ef_ink，未填的用 ef_chip，格子之间留 1dp 间隙；
 * 右侧读数用 Ef.field（宽体等宽数字），百分比是算出来的真实值。
 */
class EfSegmentBar @JvmOverloads constructor(
    context: Context,
    cellCount: Int = 24,
    showReadout: Boolean = true
) : LinearLayout(context) {

    private val count: Int = cellCount.coerceAtLeast(1)
    private val cells = mutableListOf<View>()
    private val readoutView: TextView = Ef.field(context, "0%")
    private var progress = 0f

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL

        val track = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val cellHeight = Ef.dp(context, 10)
        val gap = Ef.dp(context, 1)
        for (index in 0 until count) {
            val cell = View(context).apply {
                setBackgroundColor(Ef.color(context, R.color.ef_chip))
                layoutParams = LayoutParams(0, cellHeight, 1f).apply {
                    if (index < count - 1) {
                        marginEnd = gap
                    }
                }
            }
            track.addView(cell)
            cells.add(cell)
        }
        addView(track)

        readoutView.apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setTextColor(Ef.color(context, R.color.ef_ink))
            textSize = 13f
            isSingleLine = true
            includeFontPadding = false
            minWidth = Ef.dp(context, 40)
            layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ef.dp(context, 10)
            }
        }
        addView(readoutView)
        readoutView.visibility = if (showReadout) VISIBLE else GONE
        applyProgress()
    }

    /** 0..1 的完成度；越界自动收进区间。 */
    fun setProgress(fraction: Float) {
        progress = fraction.coerceIn(0f, 1f)
        applyProgress()
    }

    fun getProgress(): Float = progress

    private fun applyProgress() {
        val filled = (progress * count).roundToInt()
        cells.forEachIndexed { index, cell ->
            cell.setBackgroundColor(
                Ef.color(context, if (index < filled) R.color.ef_ink else R.color.ef_chip)
            )
        }
        readoutView.text = (progress * 100f).roundToInt().toString() + "%"
    }
}
