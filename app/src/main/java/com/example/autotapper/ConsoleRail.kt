package com.example.autotapper

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** 控制台要显示的一次性快照，避免视图自己去读配置。 */
data class ConsoleState(
    val running: Boolean,
    val pointCount: Int,
    val intervalMin: Long,
    val intervalMax: Long,
    val tapDuration: Long,
    val repeatCount: Int,
    val infoVisible: Boolean,
    val dockedRight: Boolean
)

interface ConsoleActions {
    fun onPrimaryToggle()
    fun onPick()
    fun onDeleteLast()
    fun onToggleInfo()
    fun onCollapse()
    fun onClose()
}

/**
 * 贴边悬浮控制台：一条窄竖条 + 一个收起后的小标签 + 一块读数面板。
 * 尺寸全部来自 dimens_console（values / values-sw600dp / values-w840dp：手机 46/40×54/96dp，平板与大屏等比放大）。
 *
 * 设计目标是最小占用、最大信息密度：
 * - 竖条只有一列，每格 = 图标 + 竖排两字标签（与参考录屏同一种「图标 + 竖排中文」的压缩方式）；
 * - 读数面板单独一扇窗口，按「详情」展开/收起，平时完全不占地方；
 * - 所有控件都是在服务启动时建好、之后只改状态，收起/展开不会新建窗口。
 */
class ConsoleRail(
    private val context: Context,
    private val actions: ConsoleActions
) {

    /**
     * 字号 / 图标 / 内边距的放大系数：手机档恒为 1.0（因此手机观感与改动前完全一致），
     * 平板 / 大屏按 console_cell_height 的比例等比放大，避免「大格子小字」。
     */
    private val scale: Float get() = visualScale(context)

    private fun dimen(resId: Int): Int = context.resources.getDimensionPixelSize(resId)

    /** 跟着格子一起放大的内边距 / 图标尺寸（dp）。 */
    private fun sdp(dp: Float): Int = Ef.dp(context, dp * scale)

    /** 跟着格子一起放大的字号（sp）。 */
    private fun ssp(sp: Float): Float = sp * scale

    /** 竖条内容：顶部读数格 + 6 个操作格。 */
    val railView: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Ef.rectBackground(context, R.color.ef_panel, R.color.ef_ink)
        setPadding(0, sdp(4f), 0, sdp(4f))
    }

    /** 收起后的贴边小标签。 */
    val tabView: FrameLayout = FrameLayout(context).apply {
        background = Ef.cutBackground(context, R.color.ef_panel, R.color.ef_ink, cutDp = 8f * scale)
        isClickable = true
    }

    /** 读数面板：状态 / 间隔 / 时长 / 轮数。 */
    val infoView: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Ef.rectBackground(context, R.color.ef_panel, R.color.ef_ink)
        setPadding(sdp(8f), sdp(6f), sdp(8f), sdp(8f))
    }

    /** 拖动只挂在读数格上，避免和操作格的点击打架。 */
    private val headerCell: LinearLayout
    private val countLabel: TextView
    private val stateDot: View

    private val primaryCell: RailCell
    private val pickCell: RailCell
    private val deleteCell: RailCell
    private val infoCell: RailCell
    private val collapseCell: RailCell
    private val closeCell: RailCell

    private val tabCount: TextView
    private val tabChevron: ImageView

    private val infoValues = mutableMapOf<Int, TextView>()

    init {
        // ---- 顶部读数格：点位数量 + 运行指示灯 ----
        headerCell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dimen(R.dimen.console_header_height)
            )
        }
        countLabel = TextView(context).apply {
            textSize = ssp(14f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Ef.color(context, R.color.ef_ink))
            Ef.applyWide(this, weight = 700, width = 112)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                fontFeatureSettings = "tnum"
            }
        }
        val unit = TextView(context).apply {
            text = context.getString(R.string.rail_unit_point)
            textSize = ssp(8f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Ef.color(context, R.color.ef_ink2))
        }
        stateDot = View(context).apply {
            // 待命灯用次级墨色，别用浅灰 ef_rule：纸白底上几乎看不见
            background = dotDrawable(R.color.ef_ink2)
        }
        headerCell.addView(countLabel)
        headerCell.addView(unit)
        headerCell.addView(
            stateDot,
            LinearLayout.LayoutParams(sdp(5f), sdp(5f)).apply {
                topMargin = sdp(3f)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )
        railView.addView(headerCell)
        railView.addView(hairline())

        // ---- 操作格 ----
        primaryCell = RailCell(context, R.drawable.ic_overlay_play)
        pickCell = RailCell(context, R.drawable.ic_overlay_add)
        deleteCell = RailCell(context, R.drawable.ic_overlay_delete)
        infoCell = RailCell(context, R.drawable.ic_rail_info)
        collapseCell = RailCell(context, R.drawable.ic_rail_collapse)
        closeCell = RailCell(context, R.drawable.ic_overlay_close)

        primaryCell.setOnClick { actions.onPrimaryToggle() }
        pickCell.setOnClick { actions.onPick() }
        deleteCell.setOnClick { actions.onDeleteLast() }
        infoCell.setOnClick { actions.onToggleInfo() }
        collapseCell.setOnClick { actions.onCollapse() }
        closeCell.setOnClick { actions.onClose() }

        listOf(primaryCell, pickCell, deleteCell, infoCell, collapseCell, closeCell).forEach {
            railView.addView(it.view)
        }

        // ---- 收起态小标签：点位数量 + 展开箭头 ----
        tabCount = TextView(context).apply {
            textSize = ssp(13f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Ef.color(context, R.color.ef_ink))
            Ef.applyWide(this, weight = 700, width = 112)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                fontFeatureSettings = "tnum"
            }
        }
        tabChevron = ImageView(context).apply {
            setImageResource(R.drawable.ic_rail_expand)
            setColorFilter(Ef.color(context, R.color.ef_ink2))
        }
        tabView.addView(
            tabCount,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = sdp(9f)
            }
        )
        tabView.addView(
            tabChevron,
            FrameLayout.LayoutParams(
                sdp(14f),
                sdp(14f)
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
                bottomMargin = sdp(9f)
            }
        )

        // ---- 读数面板 ----
        infoView.addView(
            TextView(context).apply {
                text = context.getString(R.string.rail_readout_title)
                textSize = ssp(8f)
                letterSpacing = 0.14f
                isAllCaps = true
                includeFontPadding = false
                setTextColor(Ef.color(context, R.color.ef_ink2))
                Ef.applyWide(this, weight = 600, width = 125)
                setPadding(0, 0, 0, sdp(6f))
            }
        )
        addReadout(R.string.rail_field_state)
        infoView.addView(hairline())
        addReadout(R.string.rail_field_interval)
        infoView.addView(hairline())
        addReadout(R.string.rail_field_duration)
        infoView.addView(hairline())
        addReadout(R.string.rail_field_repeat)
    }

    private fun dotDrawable(colorRes: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Ef.color(context, colorRes))
    }

    private fun hairline() = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            Ef.dp(context, 1)
        )
        setBackgroundColor(Ef.color(context, R.color.ef_rule))
    }

    private fun addReadout(labelRes: Int) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, sdp(5f), 0, sdp(5f))
        }
        val label = TextView(context).apply {
            text = context.getString(labelRes)
            textSize = ssp(9f)
            includeFontPadding = false
            setTextColor(Ef.color(context, R.color.ef_ink2))
        }
        val value = TextView(context).apply {
            // 读数板只有 96dp 宽，单行显示；间隔值（如 100–180 ms）不能被拆成两行
            textSize = ssp(10f)
            gravity = Gravity.END
            includeFontPadding = false
            isSingleLine = true
            setTextColor(Ef.color(context, R.color.ef_ink))
            Ef.applyWide(this, weight = 700, width = 100)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                fontFeatureSettings = "tnum"
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(label)
        row.addView(value)
        infoView.addView(row)
        infoValues[labelRes] = value
    }

    private var lastState: ConsoleState? = null

    /** 按服务状态刷新整套控制台；状态没变就直接返回（点击中每个间隔都会调用一次）。 */
    fun render(state: ConsoleState) {
        if (state == lastState) {
            return
        }
        lastState = state

        countLabel.text = state.pointCount.toString().padStart(2, '0')
        tabCount.text = state.pointCount.toString()

        stateDot.background = dotDrawable(if (state.running) R.color.ef_signal else R.color.ef_ink2)

        primaryCell.setLabel(
            context.getString(if (state.running) R.string.rail_label_stop else R.string.rail_label_start)
        )
        primaryCell.setIcon(if (state.running) R.drawable.ic_rail_stop else R.drawable.ic_overlay_play)
        primaryCell.setKind(if (state.running) RailCell.Kind.INK else RailCell.Kind.SIGNAL)
        primaryCell.setContentDescription(
            context.getString(
                if (state.running) R.string.overlay_stop_button_desc else R.string.overlay_play_button_desc
            )
        )

        pickCell.setLabel(context.getString(R.string.rail_label_pick))
        pickCell.setContentDescription(context.getString(R.string.rail_desc_pick))
        pickCell.setKind(RailCell.Kind.PLAIN)

        deleteCell.setLabel(context.getString(R.string.rail_label_delete))
        deleteCell.setContentDescription(context.getString(R.string.rail_desc_delete))
        deleteCell.setKind(RailCell.Kind.PLAIN)
        deleteCell.setDimmed(state.pointCount == 0)

        infoCell.setLabel(context.getString(R.string.rail_label_info))
        infoCell.setContentDescription(context.getString(R.string.rail_desc_info))
        infoCell.setKind(if (state.infoVisible) RailCell.Kind.CHIP else RailCell.Kind.PLAIN)

        collapseCell.setLabel(context.getString(R.string.rail_label_collapse))
        collapseCell.setContentDescription(context.getString(R.string.rail_desc_collapse))
        collapseCell.setKind(RailCell.Kind.PLAIN)

        closeCell.setLabel(context.getString(R.string.rail_label_close))
        closeCell.setContentDescription(context.getString(R.string.rail_desc_close))
        closeCell.setKind(RailCell.Kind.PLAIN)

        // 收起态箭头指向屏幕内侧
        tabChevron.scaleX = if (state.dockedRight) -1f else 1f
        tabView.contentDescription = context.getString(R.string.rail_desc_expand)

        infoValues[R.string.rail_field_state]?.text = context.getString(
            if (state.running) R.string.overlay_state_running else R.string.overlay_state_idle
        )
        infoValues[R.string.rail_field_interval]?.text =
            context.getString(R.string.overlay_interval_value, state.intervalMin, state.intervalMax)
        infoValues[R.string.rail_field_duration]?.text =
            context.getString(R.string.overlay_duration_value, state.tapDuration)
        infoValues[R.string.rail_field_repeat]?.text = if (state.repeatCount == 0) {
            context.getString(R.string.overlay_repeat_infinite)
        } else {
            context.getString(R.string.overlay_repeat_finite, state.repeatCount)
        }
    }

    /**
     * 一格：图标 + 竖排两字标签。可点，按下时整格换成浅色底，没有涟漪。
     */
    class RailCell(private val context: Context, iconRes: Int) {

        enum class Kind { PLAIN, CHIP, SIGNAL, INK }

        val view: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            setPadding(
                ConsoleRail.scaleDp(context, 4f),
                0,
                ConsoleRail.scaleDp(context, 2f),
                0
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                context.resources.getDimensionPixelSize(R.dimen.console_cell_height)
            )
        }

        private val icon: ImageView = ImageView(context).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(
                ConsoleRail.scaleDp(context, 20f),
                ConsoleRail.scaleDp(context, 20f)
            )
        }

        private val label: TextView = TextView(context).apply {
            textSize = ConsoleRail.scaleSp(context, 9f)
            gravity = Gravity.CENTER
            includeFontPadding = false
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(
                ConsoleRail.scaleDp(context, 12f),
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = ConsoleRail.scaleDp(context, 2f) }
        }

        private var kind = Kind.PLAIN
        private var dimmed = false
        private var pressed = false
        private var onClick: (() -> Unit)? = null

        init {
            view.addView(icon)
            view.addView(label)
            applyBackground()
            view.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pressed = true
                        applyBackground()
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        // 手指滑出这一格就不再算按下：关闭控制器 / 删点是破坏性操作，
                        // 不能让「按下去再滑出去松手」也执行
                        if (pressed && !isInside(v, event)) {
                            pressed = false
                            applyBackground()
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        val wasPressed = pressed && isInside(v, event)
                        pressed = false
                        applyBackground()
                        if (wasPressed) {
                            v.performClick()
                            onClick?.invoke()
                        }
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        pressed = false
                        applyBackground()
                        true
                    }

                    else -> true
                }
            }
        }

        private fun isInside(v: View, event: MotionEvent): Boolean {
            val x = event.x
            val y = event.y
            return x >= 0f && y >= 0f && x <= v.width && y <= v.height
        }

        fun setOnClick(block: () -> Unit) {
            onClick = block
        }

        fun setLabel(text: String) {
            label.text = text.chunked(1).joinToString("\n")
        }

        fun setIcon(resId: Int) {
            icon.setImageResource(resId)
        }

        fun setKind(value: Kind) {
            if (kind == value) {
                return
            }
            kind = value
            applyBackground()
        }

        fun setDimmed(value: Boolean) {
            if (dimmed == value) {
                return
            }
            dimmed = value
            applyBackground()
        }

        fun setContentDescription(text: String) {
            view.contentDescription = text
        }

        private fun applyBackground() {
            val fill = when {
                pressed -> R.color.ef_chip
                kind == Kind.SIGNAL -> R.color.ef_signal
                kind == Kind.INK -> R.color.ef_ink
                kind == Kind.CHIP -> R.color.ef_chip
                else -> 0
            }
            view.background = if (fill == 0) {
                null
            } else {
                Ef.cutBackground(context, fill, null, cutDp = 7f * ConsoleRail.visualScale(context))
            }

            val fg = when {
                kind == Kind.INK -> R.color.ef_panel
                kind == Kind.SIGNAL -> R.color.ef_on_signal
                else -> R.color.ef_ink
            }
            val color = Ef.color(context, fg)
            icon.setColorFilter(color)
            label.setTextColor(color)
            val alpha = if (dimmed) 0.45f else 1f
            icon.alpha = alpha
            label.alpha = alpha
        }

    }

    companion object {

        /**
         * 手机档基准格高：只用来求「视觉放大系数」，本身不参与布局。
         * 布局尺寸一律来自 R.dimen.console_*（见 res/values/dimens_console.xml）。
         */
        private const val BASE_CELL_HEIGHT_DP = 42f

        /** 窄条宽度（px）：手机 46dp / 平板 54dp / 大屏 58dp。 */
        fun railWidth(context: Context): Int =
            context.resources.getDimensionPixelSize(R.dimen.console_rail_width)

        /** 收起标签宽度（px）。 */
        fun tabWidth(context: Context): Int =
            context.resources.getDimensionPixelSize(R.dimen.console_tab_width)

        /** 收起标签高度（px）。 */
        fun tabHeight(context: Context): Int =
            context.resources.getDimensionPixelSize(R.dimen.console_tab_height)

        /** 读数板宽度（px）。 */
        fun infoWidth(context: Context): Int =
            context.resources.getDimensionPixelSize(R.dimen.console_info_width)

        /**
         * 视觉放大系数：手机档为 1.0（字号 / 图标 / 内边距与改动前逐像素一致），
         * 平板与大屏按 console_cell_height 与手机档基准格高的比值等比放大。
         */
        fun visualScale(context: Context): Float {
            val base = Ef.dp(context, BASE_CELL_HEIGHT_DP)
            if (base <= 0) {
                return 1f
            }
            val current = context.resources.getDimensionPixelSize(R.dimen.console_cell_height)
            return current.toFloat() / base
        }

        /** 按视觉系数放大的 dp。 */
        fun scaleDp(context: Context, dp: Float): Int = Ef.dp(context, dp * visualScale(context))

        /** 按视觉系数放大的字号。 */
        fun scaleSp(context: Context, sp: Float): Float = sp * visualScale(context)
    }
}
