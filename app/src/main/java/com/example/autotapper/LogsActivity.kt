package com.example.autotapper

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.autotapper.ui.EfHatch
import com.example.autotapper.ui.EfPageWidth
import com.example.autotapper.ui.EfNotice
import com.example.autotapper.ui.EfSegmented
import java.io.File

/**
 * 日志页：// LOGS —— 实时日志 + 等级筛选 + 关键字过滤 + 自动滚动 + 导出 + 清空。
 *
 * 数据只有一个来源：LogBus 的内存环形缓冲（2000 条）。
 * - 进页面用 snapshot() 取全量（ newest-first），反转成时间顺序后放进 [allEntries]；
 * - 之后靠 addListener 实时追加：匹配当前筛选就直接 append 到尾部并 notifyItemInserted，
 *   绝不每条日志都重建整表；超过 2000 条才整表截断一次。
 * - 监听器 onResume 注册 / onPause 注销 / onDestroy 再兜一次，不留界面引用给单例。
 */
class LogsActivity : AppCompatActivity() {

    private lateinit var btnExport: TextView
    private lateinit var btnClear: TextView
    private lateinit var etSearch: EditText
    private lateinit var segFilter: EfSegmented
    private lateinit var rvLogs: RecyclerView
    private lateinit var pausedHolder: LinearLayout
    private lateinit var hatchHolder: FrameLayout
    private lateinit var adapter: LogAdapter
    private lateinit var pausedNotice: EfNotice
    private lateinit var hatch: EfHatch

    /** 原始缓冲：按时间升序，最多 [MAX_ENTRIES] 条。筛选/搜索时才派生出 [visible]。 */
    private val allEntries = ArrayList<LogBus.Entry>(MAX_ENTRIES)

    /** 当前展示的（已筛选）条目；adapter 只认这一份。 */
    private val visible = ArrayList<LogBus.Entry>()

    /** 当前等级筛选下标，见 FILTER_* 常量。 */
    private var filterIndex = FILTER_ALL

    /** 当前关键字（已经小写化），空串表示不过滤。 */
    private var keyword = ""

    /** 用户上滑超过 2 屏后置 true：暂停自动滚动，顶部给可点提示。 */
    private var autoScrollPaused = false

    /** 距上次「往下滚到底」累计的上滑距离（像素），超过 2 屏就暂停。 */
    private var upScrollPx = 0

    /** 搜索防抖：连续输入只保留最后一次。 */
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    /** 关键字变化时自增，避免旧的防抖回调写进新状态。 */
    private var filterGeneration = 0

    private val logListener: (LogBus.Entry) -> Unit = { entry -> onNewEntry(entry) }

    // -----------------------------------------------------------------------
    // 生命周期
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)
        // 平板：日志列收窄到 720dp，长行更好读
        EfPageWidth.cap(findViewById(R.id.logs_content))

        btnExport = findViewById(R.id.btn_export)
        btnClear = findViewById(R.id.btn_clear)
        etSearch = findViewById(R.id.et_search)
        rvLogs = findViewById(R.id.rv_logs)
        pausedHolder = findViewById(R.id.paused_holder)
        hatchHolder = findViewById(R.id.hatch_holder)

        setupCommandButtons()
        setupFilter()
        setupSearch()
        setupPausedNotice()
        setupEmptyState()
        setupList()

        loadSnapshot()
        rebuildVisible()
    }

    override fun onResume() {
        super.onResume()
        runCatching { LogBus.addListener(logListener) }
        // onResume 时列表可能已经被 onPause 期间的日志追加过，重新取一次快照最稳
        loadSnapshot()
        rebuildVisible()
    }

    override fun onPause() {
        runCatching { LogBus.removeListener(logListener) }
        searchRunnable?.let { runCatching { searchHandler.removeCallbacks(it) } }
        super.onPause()
    }

    override fun onDestroy() {
        // 双保险：LogBus 是单例，绝不留着已经死亡的界面引用
        runCatching { LogBus.removeListener(logListener) }
        searchRunnable?.let { runCatching { searchHandler.removeCallbacks(it) } }
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // 界面搭建
    // -----------------------------------------------------------------------

    /** 命令栏按钮：墨底切角块，按下擦黄。 */
    private fun setupCommandButtons() {
        styleCommandButton(btnExport)
        btnExport.setOnClickListener { exportCurrent() }
        styleCommandButton(btnClear)
        btnClear.setOnClickListener { clearAll() }
    }

    private fun styleCommandButton(button: TextView) {
        val rest = Ef.cutBackground(this, R.color.ef_ink, R.color.ef_ink2)
        val pressed = Ef.cutBackground(this, R.color.ef_signal, null)
        button.background = rest
        // 静止态的字色必须在这里一起设：布局里默认是 ef_ink，墨底上等于隐形文字
        button.setTextColor(Ef.color(this, R.color.ef_command_bar_text))
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    button.background = pressed
                    button.setTextColor(Ef.color(this@LogsActivity, R.color.ef_on_signal))
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    button.background = rest
                    button.setTextColor(Ef.color(this@LogsActivity, R.color.ef_command_bar_text))
                }
            }
            false
        }
    }

    private fun setupFilter() {
        segFilter = EfSegmented(this)
        // 五段文案加起来比一屏宽：按文字自适应 + 外层横滑，别等分（等分会把 RESULT 切掉）
        segFilter.setFillWidth(false)
        segFilter.onSelected = { index ->
            filterIndex = index
            rebuildVisible()
        }
        val holder = findViewById<LinearLayout>(R.id.seg_holder)
        // 五个分段放不下一行：宽度用 WRAP_CONTENT，让外层 HorizontalScrollView 去滚，
        // 用 MATCH_PARENT 会被压扁并把最后一个分段切掉
        holder.addView(
            segFilter,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        updateFilterLabels()
    }

    /** 计数取真数：每次筛选标签都重算，形如「WARN 12」。 */
    private fun updateFilterLabels() {
        val counts = IntArray(LEVELS.size)
        synchronized(allEntries) {
            allEntries.forEach { entry ->
                val i = LEVELS.indexOf(entry.level)
                if (i >= 0) counts[i]++
            }
        }
        val labels = ArrayList<String>(LEVELS.size + 1)
        labels.add(getString(R.string.logs_filter_count, getString(R.string.logs_filter_all), allEntries.size))
        for (i in LEVELS.indices) {
            labels.add(getString(R.string.logs_filter_count, getString(FILTER_LABELS[i]), counts[i]))
        }
        segFilter.setOptions(labels)
        segFilter.setSelected(filterIndex)
    }

    /** 关键字过滤：300ms 防抖，用 Handler.postDelayed，不引第三方。 */
    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val next = s?.toString()?.trim()?.lowercase() ?: ""
                val generation = ++filterGeneration
                searchRunnable?.let { runCatching { searchHandler.removeCallbacks(it) } }
                val runnable = Runnable {
                    if (isFinishing || isDestroyed || generation != filterGeneration) {
                        return@Runnable
                    }
                    keyword = next
                    rebuildVisible()
                }
                searchRunnable = runnable
                searchHandler.postDelayed(runnable, SEARCH_DEBOUNCE_MS)
            }
        })
    }

    private fun setupPausedNotice() {
        pausedNotice = EfNotice(
            this,
            getString(R.string.logs_paused_hint),
            getString(R.string.logs_paused_action)
        ) { resumeAutoScroll() }
        pausedHolder.addView(
            pausedNotice,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        pausedHolder.visibility = View.GONE
    }

    private fun setupEmptyState() {
        hatch = EfHatch(this, getString(R.string.logs_empty))
        hatchHolder.addView(
            hatch,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun setupList() {
        adapter = LogAdapter()
        val layoutManager = LinearLayoutManager(this)
        rvLogs.layoutManager = layoutManager
        rvLogs.adapter = adapter
        rvLogs.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (isFinishing || isDestroyed) {
                    return
                }
                if (dy > 0) {
                    // 向下滚：清掉累计上滑距离；真正到底时才解除暂停
                    upScrollPx = 0
                    // RecyclerView 才有 canScrollVertically(direction)；LinearLayoutManager 没有
                    if (autoScrollPaused && !recyclerView.canScrollVertically(1)) {
                        setAutoScrollPaused(false)
                    }
                } else if (dy < 0 && !autoScrollPaused) {
                    upScrollPx += -dy
                    val twoScreens = rvLogs.height * 2
                    if (twoScreens > 0 && upScrollPx >= twoScreens) {
                        setAutoScrollPaused(true)
                    }
                }
            }
        })
    }

    // -----------------------------------------------------------------------
    // 数据
    // -----------------------------------------------------------------------

    /** snapshot() 是 newest-first，反转成时间升序后放进 [allEntries]。 */
    private fun loadSnapshot() {
        runCatching {
            val snapshot = LogBus.snapshot(limit = MAX_ENTRIES)
            synchronized(allEntries) {
                allEntries.clear()
                for (i in snapshot.indices.reversed()) {
                    allEntries.add(snapshot[i])
                }
            }
        }.onFailure { LogBus.warn("读取日志快照失败：" + describe(it)) }
    }

    /** 全量重建可见列表：只在筛选切换 / 搜索变化 / 清空 / 截断时调用。 */
    private fun rebuildVisible() {
        if (isFinishing || isDestroyed) {
            return
        }
        runCatching {
            synchronized(allEntries) {
                visible.clear()
                allEntries.forEach { if (matches(it)) visible.add(it) }
            }
            adapter.submit(visible)
            updateFilterLabels()
            updateEmptyState()
            if (!autoScrollPaused) {
                scrollToBottom()
            }
        }.onFailure { LogBus.warn("刷新日志列表失败：" + describe(it)) }
    }

    /** 是否匹配当前等级 + 关键字。调用方持有 allEntries 的锁（或对象只在主线程读）。 */
    private fun matches(entry: LogBus.Entry): Boolean {
        val levelOk = filterIndex == FILTER_ALL || LEVELS[filterIndex - 1] == entry.level
        if (!levelOk) {
            return false
        }
        if (keyword.isEmpty()) {
            return true
        }
        // 正文 / 步骤标签一起参与匹配，等级名也允许搜（比如输入 error）
        return entry.message.lowercase().contains(keyword) ||
            entry.stepLabel.lowercase().contains(keyword) ||
            entry.level.name.lowercase().contains(keyword)
    }

    /** 实时追加：只插一条，不整表重建。超过 2000 条才做一次截断。 */
    private fun onNewEntry(entry: LogBus.Entry) {
        if (isFinishing || isDestroyed) {
            return
        }
        runCatching {
            val hit = matches(entry)
            val needTrim: Boolean
            synchronized(allEntries) {
                allEntries.add(entry)
                needTrim = allEntries.size > MAX_ENTRIES
                if (needTrim) {
                    // 环形缓冲只会把最旧的挤掉；这里同步截断到上限，保持和 LogBus 一致
                    while (allEntries.size > MAX_ENTRIES) {
                        allEntries.removeAt(0)
                    }
                }
            }
            updateFilterLabels()
            if (needTrim) {
                // 截断改变了头部索引，走一次整表重建（极少发生）
                rebuildVisible()
                return
            }
            if (hit) {
                visible.add(entry)
                adapter.append(entry)
                updateEmptyState()
                if (!autoScrollPaused) {
                    scrollToBottom()
                }
            }
        }.onFailure { LogBus.warn("追加日志行失败：" + describe(it)) }
    }

    private fun updateEmptyState() {
        val empty = visible.isEmpty()
        rvLogs.visibility = if (empty) View.GONE else View.VISIBLE
        hatchHolder.visibility = if (empty) View.VISIBLE else View.GONE
    }

    private fun scrollToBottom() {
        val count = adapter.itemCountNow()
        if (count > 0) {
            rvLogs.scrollToPosition(count - 1)
        }
    }

    private fun setAutoScrollPaused(paused: Boolean) {
        if (autoScrollPaused == paused) {
            return
        }
        autoScrollPaused = paused
        pausedHolder.visibility = if (paused) View.VISIBLE else View.GONE
        if (paused) {
            // 导出结果可能覆盖过提示条；重新暂停时把「继续」动作放回去
            pausedNotice.setText(getString(R.string.logs_paused_hint))
            pausedNotice.setAction(getString(R.string.logs_paused_action)) { resumeAutoScroll() }
        }
        if (!paused) {
            upScrollPx = 0
            scrollToBottom()
        }
    }

    private fun resumeAutoScroll() {
        setAutoScrollPaused(false)
    }

    // -----------------------------------------------------------------------
    // 动作
    // -----------------------------------------------------------------------

    /** 清空：先清 LogBus（包括落盘缓冲），再清界面。 */
    private fun clearAll() {
        runCatching { LogBus.clear() }
            .onSuccess {
                synchronized(allEntries) { allEntries.clear() }
                rebuildVisible()
            }
            .onFailure { toast(getString(R.string.logs_clear_failed, describe(it))) }
    }

    /**
     * 导出当前筛选结果：filesDir/log/export-<时间戳>.log。
     * 写成功后用 EfNotice 显示完整路径 + 行数（替换「已暂停」提示的位置，避免两处提示打架）。
     */
    private fun exportCurrent() {
        if (isFinishing || isDestroyed || visible.isEmpty()) {
            return
        }
        val lines = synchronized(visible) { visible.toList() }
        val stamp = System.currentTimeMillis()
        val dir = runCatching { LogBus.logDir(this) }.getOrNull()
        if (dir == null) {
            toast(getString(R.string.logs_export_failed, getString(R.string.logs_empty)))
            return
        }
        val file = File(dir, "export-" + stamp + ".log")
        val written = runCatching {
            file.writeText(buildExportText(lines), Charsets.UTF_8)
            lines.size
        }
        written.onSuccess { count ->
            if (isFinishing || isDestroyed) {
                return@onSuccess
            }
            showExportNotice(getString(R.string.logs_export_done, count, file.absolutePath))
        }.onFailure { t ->
            LogBus.warn("导出日志失败：" + describe(t))
            toast(getString(R.string.logs_export_failed, describe(t)))
        }
    }

    /** 导出文本：一行一条，时间和正文都在， stepLabel 有就带上。 */
    private fun buildExportText(entries: List<LogBus.Entry>): String {
        val sb = StringBuilder()
        entries.forEach { entry ->
            sb.append(LogBus.formatTime(entry.at))
            sb.append(' ')
            sb.append('[').append(entry.level.name).append(']')
            sb.append(' ')
            if (entry.stepIndex >= 0) {
                sb.append('#').append(entry.stepIndex).append(' ')
            }
            if (entry.stepLabel.isNotBlank()) {
                sb.append(entry.stepLabel).append(' ')
            }
            sb.append(entry.message)
            sb.append('\n')
        }
        return sb.toString()
    }

    /** 导出结果用 EfNotice 常驻显示完整路径，不用一闪而过的 Toast。 */
    private fun showExportNotice(text: String) {
        pausedNotice.setText(text)
        pausedNotice.setAction(null, null)
        pausedHolder.visibility = View.VISIBLE
        // 下一次自动滚动暂停/继续时提示会被覆盖，这是可接受的（导出结果已经看过）
    }

    private fun toast(text: String) {
        if (isFinishing || isDestroyed) {
            return
        }
        runCatching { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    private fun describe(t: Throwable): String = t.message ?: t.javaClass.simpleName

    // -----------------------------------------------------------------------
    // 行视图：代码构建，RecyclerView 复用
    // -----------------------------------------------------------------------

    private inner class LogAdapter : RecyclerView.Adapter<LogAdapter.RowHolder>() {

        override fun getItemCount(): Int = visible.size

        /** 单条追加：绝不用 notifyDataSetChanged。 */
        fun append(entry: LogBus.Entry) {
            notifyItemInserted(visible.size - 1)
        }

        fun itemCountNow(): Int = visible.size

        /** 整表替换：只在筛选 / 搜索 / 清空 / 截断时调用。 */
        fun submit(list: List<LogBus.Entry>) {
            // list 就是外部的 visible 同一个引用；这里只通知刷新，不再复制一份
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(0, Ef.dp(context, 5f), 0, Ef.dp(context, 5f))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val time = TextView(row.context).apply {
                textSize = 11f
                includeFontPadding = false
                isSingleLine = true
                setTextColor(Ef.color(context, R.color.ef_ink2))
                fontFeatureSettings = "tnum"
            }
            val level = TextView(row.context).apply {
                textSize = 11f
                includeFontPadding = false
                isSingleLine = true
                gravity = Gravity.CENTER
                setPadding(
                    Ef.dp(context, 6),
                    Ef.dp(context, 2),
                    Ef.dp(context, 8),
                    Ef.dp(context, 4)
                )
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = Ef.dp(context, 8) }
            }
            val step = TextView(row.context).apply {
                textSize = 11f
                includeFontPadding = false
                isSingleLine = true
                setTextColor(Ef.color(context, R.color.ef_ink2))
                fontFeatureSettings = "tnum"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = Ef.dp(context, 8) }
            }
            val message = TextView(row.context).apply {
                textSize = 13f
                includeFontPadding = false
                setTextColor(Ef.color(context, R.color.ef_ink))
                // 长文本自然换行，不横向滚动
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply { marginStart = Ef.dp(context, 8) }
            }
            row.addView(time)
            row.addView(level)
            row.addView(step)
            row.addView(message)
            return RowHolder(row, time, level, step, message)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            holder.bind(visible[position])
        }

        inner class RowHolder(
            itemView: View,
            private val time: TextView,
            private val levelView: TextView,
            private val step: TextView,
            private val message: TextView
        ) : RecyclerView.ViewHolder(itemView) {

            fun bind(entry: LogBus.Entry) {
                time.text = LogBus.formatTime(entry.at)
                levelView.text = entry.level.name
                // 标签：浅灰切角小块；ERROR 红字，WARN 墨字，RESULT 黄块
                val bgRes: Int
                val fgRes: Int
                when (entry.level) {
                    LogBus.Level.RESULT -> {
                        bgRes = R.color.ef_signal
                        fgRes = R.color.ef_on_signal
                    }
                    LogBus.Level.ERROR -> {
                        bgRes = R.color.ef_chip
                        fgRes = R.color.ef_danger
                    }
                    else -> {
                        bgRes = R.color.ef_chip
                        fgRes = R.color.ef_ink
                    }
                }
                levelView.background = Ef.cutBackground(itemView.context, bgRes, null, cutDp = 5f)
                levelView.setTextColor(Ef.color(itemView.context, fgRes))

                if (entry.stepIndex >= 0) {
                    step.visibility = View.VISIBLE
                    step.text = "#" + entry.stepIndex
                } else {
                    step.visibility = View.GONE
                }
                message.text = entry.message
            }
        }
    }

    companion object {
        private const val MAX_ENTRIES = 2000
        private const val SEARCH_DEBOUNCE_MS = 300L

        private const val FILTER_ALL = 0
        private const val FILTER_INFO = 1
        private const val FILTER_WARN = 2
        private const val FILTER_ERROR = 3
        private const val FILTER_RESULT = 4

        /** 下标 0 = INFO … 3 = RESULT，和 FILTER_* 对齐（FILTER_ALL 不在里面）。 */
        private val LEVELS = listOf(
            LogBus.Level.INFO,
            LogBus.Level.WARN,
            LogBus.Level.ERROR,
            LogBus.Level.RESULT
        )
        private val FILTER_LABELS = intArrayOf(
            R.string.logs_filter_info,
            R.string.logs_filter_warn,
            R.string.logs_filter_error,
            R.string.logs_filter_result
        )
    }
}