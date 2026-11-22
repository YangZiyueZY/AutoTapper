package com.example.autotapper.ui

import android.content.Context
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

/** 记录行的状态三态：实线框 = 启用、虚线框 = 停用、黄块 = 需要你看一眼。 */
enum class PluginRowState { READY, OFF, ATTENTION }

/**
 * 列表一行需要的全部数据。
 *
 * 能力标签在这里已经是"读得懂的字"（Activity 负责把 Capability 翻成中文），
 * 适配器只管画，不认插件契约里的枚举——以后加能力只要改 Activity 一处。
 * [number] 是真实序号（从 1 开始），界面上的编号不是装饰。
 */
data class PluginRow(
    val number: Int,
    val id: String,
    val name: String,
    val version: String,
    val capabilities: List<String>,
    val state: PluginRowState,
    val stateText: String
)

/**
 * 插件记录行：编号 │ 名称 + 版本 │ 能力标签 │ 状态三态，行与行之间一条 1px 细线。
 *
 * 只用 Ef* 基元与 ef_* 颜色：编号是墨底白字 + 右下切角（EfShapeDrawable），
 * 能力标签是浅灰切角小块，状态用 [EfStateBlock] 的三态。
 * 行整体可点，按下落一档浅灰填充（没有涟漪），松手就走 [onRowClick]。
 */
class PluginPanelAdapter(
    private val context: Context,
    private val onRowClick: (PluginRow) -> Unit
) : RecyclerView.Adapter<PluginPanelAdapter.RowHolder>() {

    private val rows = ArrayList<PluginRow>()

    /** 整表替换。插件数量是个位数到几十，全量重绑比 DiffUtil 更简单，也不会漏刷状态。 */
    fun submit(items: List<PluginRow>) {
        rows.clear()
        rows.addAll(items)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
        val root = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
        }
        return RowHolder(root, onRowClick)
    }

    override fun onBindViewHolder(holder: RowHolder, position: Int) {
        holder.bind(rows[position])
    }

    /** 一行：内容是"名称 + 版本 + 能力标签"，右边是状态块。 */
    class RowHolder(
        private val shell: LinearLayout,
        private val onRowClick: (PluginRow) -> Unit
    ) : RecyclerView.ViewHolder(shell) {

        private val context: Context = shell.context
        private val contentRow = LinearLayout(context)
        private val indexBadge = TextView(context)
        private val nameView = TextView(context)
        private val versionView: TextView = Ef.field(context, "")
        private val chipsScroll = HorizontalScrollView(context)
        private val chipsRow = LinearLayout(context)
        private val stateBlock = EfStateBlock(context)

        /** 当前绑定的行；点击时用它，避免用可能已经错位的 adapterPosition。 */
        private var bound: PluginRow? = null

        init {
            shell.addView(
                contentRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            // 编号：墨底白字，右下切角
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

            contentRow.orientation = LinearLayout.HORIZONTAL
            contentRow.gravity = Gravity.CENTER_VERTICAL
            contentRow.setPadding(0, Ef.dp(context, 12), 0, Ef.dp(context, 12))
            contentRow.addView(
                indexBadge,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            // 中间：名称（粗体）+ 版本（等宽字段）+ 能力标签（横向可滑，窄屏不会被切掉）
            nameView.textSize = 15f
            nameView.typeface = Typeface.DEFAULT_BOLD
            nameView.includeFontPadding = false
            nameView.isSingleLine = true
            nameView.ellipsize = TextUtils.TruncateAt.END
            nameView.setTextColor(Ef.color(context, R.color.ef_ink))

            chipsRow.orientation = LinearLayout.HORIZONTAL
            chipsScroll.isHorizontalScrollBarEnabled = false
            chipsScroll.addView(
                chipsRow,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

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
                versionView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Ef.dp(context, 2) }
            )
            middle.addView(
                chipsScroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Ef.dp(context, 6) }
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

            // 分区细线：列表靠细线分隔，不用带边框的卡片
            shell.addView(
                View(context).apply {
                    setBackgroundColor(Ef.color(context, R.color.ef_rule))
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Ef.dp(context, 1)
                )
            )

            shell.setOnClickListener { bound?.let(onRowClick) }
            shell.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> shell.background =
                        Ef.rectBackground(context, R.color.ef_chip)

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        shell.background = null
                }
                // 不吃事件：点击仍然交给上面的 OnClickListener
                false
            }
        }

        fun bind(row: PluginRow) {
            bound = row
            // 复用的视图可能停在「按下」的底色上，绑定前先复位
            shell.background = null
            indexBadge.text = context.getString(R.string.pp_row_index, row.number)
            nameView.text = row.name
            versionView.text = "v" + row.version

            chipsRow.removeAllViews()
            if (row.capabilities.isEmpty()) {
                chipsRow.addView(chipView(context.getString(R.string.pp_cap_none)))
            } else {
                row.capabilities.forEach { label -> chipsRow.addView(chipView(label)) }
            }

            when (row.state) {
                PluginRowState.READY -> stateBlock.setKind(EfStateBlock.Kind.SOLID)
                PluginRowState.OFF -> stateBlock.setKind(EfStateBlock.Kind.DASHED)
                PluginRowState.ATTENTION -> stateBlock.setKind(EfStateBlock.Kind.FLAG)
            }
            stateBlock.setText(row.stateText)

            shell.contentDescription = row.name + " " + row.version + " " + row.stateText
        }

        /** 能力标签：浅灰切角小块，右侧多留一点给切角，字不压角。 */
        private fun chipView(text: String): TextView = TextView(context).apply {
            this.text = text
            textSize = 11f
            includeFontPadding = false
            isSingleLine = true
            setTextColor(Ef.color(context, R.color.ef_ink2))
            background = Ef.cutBackground(context, R.color.ef_chip, null, cutDp = 5f)
            setPadding(
                Ef.dp(context, 7),
                Ef.dp(context, 3),
                Ef.dp(context, 10),
                Ef.dp(context, 5)
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = Ef.dp(context, 6) }
        }
    }
}
