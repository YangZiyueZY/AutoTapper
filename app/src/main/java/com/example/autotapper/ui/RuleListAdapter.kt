package com.example.autotapper.ui

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.autotapper.Ef
import com.example.autotapper.R

/**
 * 规则列表一行需要的全部数据。
 *
 * [meta] 已经是"读得懂的字"（「3 步 · 2 轮」），适配器不认 [com.example.autotapper.model.Rule]——
 * 以后轮数/步骤数的说法要改，只要改 Activity 一处。
 */
data class RuleRow(
    val id: String,
    val name: String,
    val meta: String,
    val isCurrent: Boolean
)

/**
 * 规则行：名称 + 「N 步 · M 轮」 │ 当前标记（黄块） │ 「…」操作入口，行与行之间一条 1px 细线。
 *
 * 只用 Ef* 基元与 ef_* 颜色：当前规则用 [EfStateBlock] 的黄块（黄只表示"当前/要看一眼"，
 * 不做文字色）；「…」是切角小块、48dp 触控区。
 * 行整体可点（进步骤列表），长按等同点「…」（两个入口都能打开操作菜单）。
 *
 * 适配器不持有 Activity：它只拿回调，视图一律用 parent.context 建；
 * 行里缓存当前绑定行，点击时不用可能错位的 adapterPosition。
 */
class RuleListAdapter(
    private val onRowClick: (RuleRow) -> Unit,
    private val onRowActions: (RuleRow) -> Unit
) : RecyclerView.Adapter<RuleListAdapter.RowHolder>() {

    private val rows = ArrayList<RuleRow>()

    /** 整表替换。规则数量是个位数到几十，全量重绑比 DiffUtil 简单，也不会漏刷当前标记。 */
    fun submit(items: List<RuleRow>) {
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
        return RowHolder(shell, onRowClick, onRowActions)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        holder.bind(rows[position])
    }

    class RowHolder(
        private val shell: LinearLayout,
        private val onRowClick: (RuleRow) -> Unit,
        private val onRowActions: (RuleRow) -> Unit
    ) : RecyclerView.ViewHolder(shell) {

        private val context = shell.context
        private val contentRow = LinearLayout(context)
        private val nameView = TextView(context)
        private val metaView: TextView = Ef.field(context, "")
        private val currentBlock = EfStateBlock(context)
        private val moreView = TextView(context)

        /** 当前绑定的行；点击时用它，避免用可能已经错位的 adapterPosition。 */
        private var bound: RuleRow? = null

        init {
            contentRow.orientation = LinearLayout.HORIZONTAL
            contentRow.gravity = Gravity.CENTER_VERTICAL
            contentRow.minimumHeight = Ef.dp(context, 64)
            contentRow.setPadding(0, Ef.dp(context, 12), 0, Ef.dp(context, 12))

            nameView.textSize = 15f
            nameView.typeface = Typeface.DEFAULT_BOLD
            nameView.includeFontPadding = false
            nameView.isSingleLine = true
            nameView.ellipsize = TextUtils.TruncateAt.END
            nameView.setTextColor(Ef.color(context, R.color.ef_ink))

            metaView.textSize = 12f
            metaView.isSingleLine = true

            val middle = LinearLayout(context)
            middle.orientation = LinearLayout.VERTICAL
            middle.addView(
                nameView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            middle.addView(
                metaView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Ef.dp(context, 2) }
            )

            contentRow.addView(
                middle,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )

            // 当前标记：黄块，只在是当前规则时出现
            currentBlock.visibility = View.GONE
            contentRow.addView(
                currentBlock,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = Ef.dp(context, 10) }
            )

            // 「…」：切角小块，触控区 48dp
            moreView.text = "…"
            moreView.textSize = 18f
            moreView.includeFontPadding = false
            moreView.gravity = Gravity.CENTER
            moreView.isSingleLine = true
            moreView.isClickable = true
            moreView.isFocusable = true
            moreView.minimumWidth = Ef.dp(context, 48)
            moreView.minimumHeight = Ef.dp(context, 48)
            moreView.setTextColor(Ef.color(context, R.color.ef_ink))
            moreView.background = Ef.cutBackground(context, R.color.ef_chip, null, cutDp = 6f)
            moreView.contentDescription = context.getString(R.string.rules_action_more)
            moreView.setOnClickListener { bound?.let(onRowActions) }
            contentRow.addView(
                moreView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = Ef.dp(context, 10) }
            )

            shell.addView(
                contentRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            // 分区细线：列表靠细线分隔，不用带边框的卡片
            shell.addView(
                View(context).apply { setBackgroundColor(Ef.color(context, R.color.ef_rule)) },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Ef.dp(context, 1)
                )
            )

            shell.setOnClickListener { bound?.let(onRowClick) }
            shell.setOnLongClickListener {
                bound?.let(onRowActions)
                true
            }
            shell.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        shell.background = Ef.rectBackground(context, R.color.ef_chip)

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        shell.background = null
                }
                // 不吃事件：点击仍然交给上面的 OnClickListener
                false
            }
        }

        fun bind(row: RuleRow) {
            bound = row
            // 复用的视图可能停在「按下」的底色上，绑定前先复位
            shell.background = null
            nameView.text = row.name
            metaView.text = row.meta
            currentBlock.setText(context.getString(R.string.rules_mark_current))
            currentBlock.setKind(EfStateBlock.Kind.FLAG)
            currentBlock.visibility = if (row.isCurrent) View.VISIBLE else View.GONE
            val currentText = if (row.isCurrent) {
                " " + context.getString(R.string.rules_mark_current)
            } else {
                ""
            }
            shell.contentDescription = row.name + " " + row.meta + currentText
        }
    }
}
