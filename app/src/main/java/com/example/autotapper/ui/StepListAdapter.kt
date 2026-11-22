package com.example.autotapper.ui

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.autotapper.Ef
import com.example.autotapper.R

/** 步骤行的状态：启用（实线框）/ 停用（虚线框）/ 缺插件（黄块，要你看一眼）。 */
enum class StepRowState { ON, OFF, MISSING }

/**
 * 步骤列表一行需要的全部数据。
 *
 * [typeText] / [summary] 已经是人话：CoreStep 的"点击 120,340 按住 50 ms"、
 * 插件步骤的"长按 42 ms × 3 次"都由 Activity 问过插件再传进来，适配器不碰插件代码。
 */
data class StepRow(
    val id: String,
    val number: Int,
    val typeText: String,
    val summary: String,
    val state: StepRowState,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean
)

/**
 * 步骤行：编号（墨底白字切角）│ 类型 + 摘要 │ 状态块；下面一排动作：启用·停用 / 上移 / 下移 / 试跑 / 删除。
 *
 * 红色只给删除。所有动作按钮 48dp 触控区；上移/下移到头时置灰且不可点。
 * 动作排放在横向滚动容器里：窄屏上宁可左右滑，也不能把「删除」挤出屏幕。
 *
 * 适配器不持有 Activity：视图用 parent.context 建，回调由 Activity 传进来（同生命周期）。
 */
class StepListAdapter(
    private val onRowClick: (StepRow) -> Unit,
    private val onToggleEnabled: (StepRow) -> Unit,
    private val onMoveUp: (StepRow) -> Unit,
    private val onMoveDown: (StepRow) -> Unit,
    private val onRun: (StepRow) -> Unit,
    private val onDelete: (StepRow) -> Unit
) : RecyclerView.Adapter<StepListAdapter.RowHolder>() {

    private val rows = ArrayList<StepRow>()

    /** 整表替换：步骤增删与排序后全都重画一遍，编号才是真序号。 */
    fun submit(items: List<StepRow>) {
        rows.clear()
        rows.addAll(items)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val shell = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
        }
        return RowHolder(
            shell,
            onRowClick,
            onToggleEnabled,
            onMoveUp,
            onMoveDown,
            onRun,
            onDelete
        )
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        holder.bind(rows[position])
    }

    class RowHolder(
        private val shell: LinearLayout,
        private val onRowClick: (StepRow) -> Unit,
        private val onToggleEnabled: (StepRow) -> Unit,
        private val onMoveUp: (StepRow) -> Unit,
        private val onMoveDown: (StepRow) -> Unit,
        private val onRun: (StepRow) -> Unit,
        private val onDelete: (StepRow) -> Unit
    ) : RecyclerView.ViewHolder(shell) {

        private val context = shell.context
        private val contentRow = LinearLayout(context)
        private val indexBadge = TextView(context)
        private val typeView = TextView(context)
        private val summaryView: TextView = Ef.field(context, "")
        private val stateBlock = EfStateBlock(context)

        private val toggleView: TextView
        private val upView: TextView
        private val downView: TextView
        private val runView: TextView
        private val deleteView: TextView

        /** 当前绑定的行；动作按钮都通过它取 id，不用可能已经错位的 adapterPosition。 */
        private var bound: StepRow? = null

        init {
            contentRow.orientation = LinearLayout.HORIZONTAL
            contentRow.gravity = Gravity.CENTER_VERTICAL
            contentRow.setPadding(0, Ef.dp(context, 10), 0, Ef.dp(context, 6))

            // 编号：墨底白字，右下切角（与插件面板的编号同一种做法，但不是同一个资源键）
            indexBadge.textSize = 12f
            indexBadge.typeface = Typeface.DEFAULT_BOLD
            indexBadge.includeFontPadding = false
            indexBadge.gravity = Gravity.CENTER
            indexBadge.isSingleLine = true
            indexBadge.fontFeatureSettings = "tnum"
            indexBadge.setTextColor(Ef.color(context, R.color.ef_tag_fg))
            indexBadge.background = Ef.cutBackground(context, R.color.ef_tag_bg, null, cutDp = 6f)
            indexBadge.setPadding(
                Ef.dp(context, 7),
                Ef.dp(context, 4),
                Ef.dp(context, 10),
                Ef.dp(context, 6)
            )

            typeView.textSize = 14f
            typeView.typeface = Typeface.DEFAULT_BOLD
            typeView.includeFontPadding = false
            typeView.isSingleLine = true
            typeView.ellipsize = TextUtils.TruncateAt.END
            typeView.setTextColor(Ef.color(context, R.color.ef_ink))

            summaryView.textSize = 12f
            summaryView.maxLines = 2
            summaryView.ellipsize = TextUtils.TruncateAt.END

            val middle = LinearLayout(context)
            middle.orientation = LinearLayout.VERTICAL
            middle.addView(
                typeView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            middle.addView(
                summaryView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Ef.dp(context, 3) }
            )

            contentRow.addView(
                indexBadge,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            contentRow.addView(
                middle,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = Ef.dp(context, 12) }
            )
            contentRow.addView(
                stateBlock,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = Ef.dp(context, 10) }
            )

            // 动作排：启用·停用 / 上移 / 下移 / 试跑 / 删除
            toggleView = actionButton("")
            upView = actionButton(context.getString(R.string.rules_action_move_up))
            downView = actionButton(context.getString(R.string.rules_action_move_down))
            runView = actionButton(context.getString(R.string.rules_action_run))
            deleteView = actionButton(
                context.getString(R.string.rules_action_delete),
                danger = true
            )

            val actionsRow = LinearLayout(context)
            actionsRow.orientation = LinearLayout.HORIZONTAL
            actionsRow.gravity = Gravity.CENTER_VERTICAL
            actionsRow.addView(toggleView)
            actionsRow.addView(upView)
            actionsRow.addView(downView)
            actionsRow.addView(runView)
            actionsRow.addView(deleteView)

            val actionsScroll = HorizontalScrollView(context)
            actionsScroll.isHorizontalScrollBarEnabled = false
            actionsScroll.addView(
                actionsRow,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            toggleView.setOnClickListener { bound?.let(onToggleEnabled) }
            upView.setOnClickListener { bound?.let(onMoveUp) }
            downView.setOnClickListener { bound?.let(onMoveDown) }
            runView.setOnClickListener { bound?.let(onRun) }
            deleteView.setOnClickListener { bound?.let(onDelete) }

            shell.addView(
                contentRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            shell.addView(
                actionsScroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            // 分区细线
            shell.addView(
                View(context).apply { setBackgroundColor(Ef.color(context, R.color.ef_rule)) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Ef.dp(context, 1)
                ).apply { topMargin = Ef.dp(context, 8) }
            )

            // 点行（非动作按钮）进编辑
            shell.setOnClickListener { bound?.let(onRowClick) }
            shell.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        shell.background = Ef.rectBackground(context, R.color.ef_chip)

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        shell.background = null
                }
                false
            }
        }

        fun bind(row: StepRow) {
            bound = row
            shell.background = null
            indexBadge.text = context.getString(R.string.rules_row_index, row.number)
            typeView.text = row.typeText
            summaryView.text = row.summary

            when (row.state) {
                StepRowState.ON -> {
                    stateBlock.setKind(EfStateBlock.Kind.SOLID)
                    stateBlock.setText(context.getString(R.string.rules_state_on))
                }

                StepRowState.OFF -> {
                    stateBlock.setKind(EfStateBlock.Kind.DASHED)
                    stateBlock.setText(context.getString(R.string.rules_state_off))
                }

                StepRowState.MISSING -> {
                    stateBlock.setKind(EfStateBlock.Kind.FLAG)
                    stateBlock.setText(context.getString(R.string.rules_missing_plugin))
                }
            }

            // 按钮写"点了会发生什么"，状态由左边的状态块如实说明
            toggleView.text = context.getString(
                if (row.state == StepRowState.OFF) R.string.rules_action_enable
                else R.string.rules_action_disable
            )
            toggleView.contentDescription = toggleView.text

            setUpEnabled(upView, row.canMoveUp)
            setUpEnabled(downView, row.canMoveDown)

            shell.contentDescription = context.getString(R.string.rules_row_index, row.number) +
                " " + row.typeText + " " + row.summary
        }

        /** 到头的那一头置灰且不可点，避免点了没反应。 */
        private fun setUpEnabled(view: TextView, enabled: Boolean) {
            view.isEnabled = enabled
            view.isClickable = enabled
            view.alpha = if (enabled) 1f else 0.35f
        }

        /** 动作按钮：切角小块、48dp 触控区；红色只给删除。 */
        private fun actionButton(label: String, danger: Boolean = false): TextView =
            TextView(context).apply {
                text = label
                textSize = 12f
                isSingleLine = true
                includeFontPadding = false
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                minimumHeight = Ef.dp(context, 48)
                minimumWidth = Ef.dp(context, 48)
                setPadding(
                    Ef.dp(context, 10),
                    Ef.dp(context, 8),
                    Ef.dp(context, 14),
                    Ef.dp(context, 8)
                )
                background = Ef.cutBackground(context, R.color.ef_chip, null, cutDp = 6f)
                setTextColor(Ef.color(context, if (danger) R.color.ef_danger else R.color.ef_ink))
                contentDescription = label
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = Ef.dp(context, 6) }
                Ef.applyWide(this, weight = 600, width = 118)
            }
    }
}
