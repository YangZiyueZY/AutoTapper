package com.example.autotapper

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.autotapper.plugin.Capability
import com.example.autotapper.runtime.InstallResult
import com.example.autotapper.runtime.InstalledRecord
import com.example.autotapper.runtime.PluginManager
import com.example.autotapper.runtime.PluginManifest
import com.example.autotapper.runtime.PluginRegistry
import com.example.autotapper.ui.EfFieldRow
import com.example.autotapper.ui.EfHatch
import com.example.autotapper.ui.EfPageWidth
import com.example.autotapper.ui.EfNotice
import com.example.autotapper.ui.EfSegmented
import com.example.autotapper.ui.PluginPanelAdapter
import com.example.autotapper.ui.PluginRow
import com.example.autotapper.ui.PluginRowState
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 插件面板（M2）：列表 + 分段筛选 + 详情（同 Activity 内的第二屏）+ 仓库/本地文件安装。
 *
 * 三条数据原则：
 * 1. 数据只从 [PluginManager] 取，界面不自己读 installed.json / index.json；
 * 2. [PluginManager] 的动作全部异步回调且回调在主线程，这里不自己起线程，也不切线程；
 * 3. 回调回来时界面可能已经销毁（旋转、返回），每次都先看 isFinishing / isDestroyed。
 *
 * 监听器在 onResume 注册、onPause 注销，不留界面引用给单例。
 */
class PluginPanelActivity : AppCompatActivity() {

    private lateinit var pm: PluginManager

    private lateinit var tvFields: TextView
    private lateinit var btnRefresh: TextView
    private lateinit var listScreen: LinearLayout
    private lateinit var detailScroll: ScrollView
    private lateinit var detailContainer: LinearLayout
    private lateinit var noticeHolder: LinearLayout
    private lateinit var hatchHolder: FrameLayout
    private lateinit var rvPlugins: RecyclerView

    private lateinit var segFilter: EfSegmented
    private lateinit var noticeView: EfNotice
    private lateinit var hatchView: EfHatch
    private lateinit var adapter: PluginPanelAdapter

    /** 当前筛选段：0 已安装 / 1 可获取 / 2 需更新。 */
    private var filterIndex = FILTER_INSTALLED

    /** 详情正在看哪个插件；null 表示停在列表屏。 */
    private var detailId: String? = null

    /** 详情页里的一行状态字（下载进度、上一动作的结果）。 */
    private var actionHint: String = ""

    /** 进度回调要更新的那一行；renderDetail 重建界面时指针跟着换。 */
    private var detailHintView: TextView? = null

    /** 有动作在跑（安装/卸载/启停）时挡住重复点击。 */
    private var actionRunning = false

    private val listener = object : PluginManager.Listener {
        override fun onPluginsChanged() {
            if (isFinishing || isDestroyed) {
                return
            }
            render()
        }
    }

    /** 本地安装：系统文件选择器选 .jar。回调在主线程。 */
    private val openJar = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            handlePickedFile(uri)
        }
    }

    // -----------------------------------------------------------------------
    // 生命周期
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_plugin_panel)
        // 平板：列表与详情一起收窄，避免一行只写一个插件名
        EfPageWidth.cap(findViewById(R.id.list_screen), findViewById(R.id.detail_scroll))

        pm = PluginManager.get(this)

        tvFields = findViewById(R.id.tv_fields)
        btnRefresh = findViewById(R.id.btn_refresh)
        listScreen = findViewById(R.id.list_screen)
        detailScroll = findViewById(R.id.detail_scroll)
        detailContainer = findViewById(R.id.detail_container)
        noticeHolder = findViewById(R.id.notice_holder)
        hatchHolder = findViewById(R.id.hatch_holder)
        rvPlugins = findViewById(R.id.rv_plugins)

        setupRefreshButton()
        setupFilter()
        setupNotice()
        setupEmptyState()
        setupList()

        // 详情是第一屏的第二屏：返回键先回列表，再退出面板
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (detailId != null) {
                    showList()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        render()
    }

    override fun onResume() {
        super.onResume()
        pm.addListener(listener)
        render()
    }

    override fun onPause() {
        pm.removeListener(listener)
        super.onPause()
    }

    override fun onDestroy() {
        // 双保险：单例不该留着已经死亡的界面
        runCatching { pm.removeListener(listener) }
        detailHintView = null
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // 界面搭建
    // -----------------------------------------------------------------------

    /** 刷新：墨底切角块，按下擦黄；同步中置灰，避免连点。 */
    private fun setupRefreshButton() {
        val rest = Ef.cutBackground(this, R.color.ef_ink, R.color.ef_ink2)
        val pressed = Ef.cutBackground(this, R.color.ef_signal, null)
        btnRefresh.background = rest
        btnRefresh.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    btnRefresh.background = pressed
                    btnRefresh.setTextColor(Ef.color(this@PluginPanelActivity, R.color.ef_on_signal))
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    btnRefresh.background = rest
                    btnRefresh.setTextColor(
                        Ef.color(this@PluginPanelActivity, R.color.ef_command_bar_text)
                    )
                }
            }
            // 不吃事件：点击仍然交给 OnClickListener
            false
        }
        btnRefresh.setOnClickListener { startSync() }
    }

    private fun setupFilter() {
        segFilter = EfSegmented(this)
        segFilter.onSelected = { index ->
            filterIndex = index
            // 计数与空状态文案都跟筛选段有关，整屏重算一遍，不留下过期的数字
            render()
        }
        val holder = findViewById<LinearLayout>(R.id.seg_holder)
        holder.addView(
            segFilter,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun setupNotice() {
        noticeView = EfNotice(this)
        noticeHolder.addView(
            noticeView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun setupEmptyState() {
        hatchView = EfHatch(this, getString(R.string.plugin_panel_empty))
        hatchHolder.addView(
            hatchView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun setupList() {
        adapter = PluginPanelAdapter(this) { row -> showDetail(row.id) }
        rvPlugins.layoutManager = LinearLayoutManager(this)
        rvPlugins.adapter = adapter
    }

    // -----------------------------------------------------------------------
    // 渲染
    // -----------------------------------------------------------------------

    /** 整屏刷新：字段行、分段计数、提示条、列表，以及（停在详情时）详情页。 */
    private fun render() {
        if (isFinishing || isDestroyed) {
            return
        }
        runCatching {
            renderFields()
            renderFilterLabels()
            renderNotice()
            renderRows()
            if (detailId != null) {
                renderDetail()
            }
        }.onFailure { LogBus.error("刷新插件面板失败：" + describe(it)) }
    }

    /** 字段行：owner/repo │ branch │ 上次同步 14:32 │ N 个插件，全部取真实状态。 */
    private fun renderFields() {
        val repo = pm.repo
        val syncedText = if (pm.lastSyncAt > 0L) {
            getString(R.string.plugin_synced_at) + " " + formatClock(pm.lastSyncAt)
        } else {
            getString(R.string.pp_synced_never)
        }
        val countText = getString(R.string.plugin_count_format, pm.installed().size)
        // 四段在一行放不下（1080dp 手机上会折成"1 个 / 插件"两半），所以按语义分两行：
        // 第一行是"插件从哪来"，第二行是"同步到什么时候、有多少个"。
        tvFields.text = repo.owner + "/" + repo.repo + " │ " + repo.branch + "\n" +
            syncedText + " │ " + countText

        btnRefresh.isEnabled = !pm.isSyncing
        btnRefresh.alpha = if (pm.isSyncing) 0.55f else 1f
    }

    /** 分段标签上的计数必须是真数：每次刷新都重算，切筛选段也跟着重算。 */
    private fun renderFilterLabels() {
        val installedCount = pm.installed().size
        val availableCount = pm.available().size
        val updateCount = pm.updates().size
        segFilter.setOptions(
            listOf(
                filterLabel(R.string.plugin_filter_installed, installedCount),
                filterLabel(R.string.plugin_filter_available, availableCount),
                filterLabel(R.string.plugin_filter_updates, updateCount)
            )
        )
        segFilter.setSelected(filterIndex)
    }

    private fun filterLabel(nameRes: Int, count: Int): String =
        getString(R.string.pp_filter_count, getString(nameRes), count)

    /**
     * 提示条：未联网 / 仓库 404 / 还没有索引都用白底黄旗提示，动作是「改用本地文件安装」。
     * 这里永远不用红色——红色只给删除。
     */
    private fun renderNotice() {
        val reason = pm.lastSyncError
        when {
            !pm.isReady -> showNotice(getString(R.string.pp_preparing), null)
            reason != null -> showNotice(reason, getString(R.string.pp_notice_offline_action))
            pm.index() == null ->
                showNotice(getString(R.string.pp_notice_index_missing), getString(R.string.pp_notice_offline_action))

            else -> noticeHolder.visibility = View.GONE
        }
    }

    private fun showNotice(text: String, actionText: String?) {
        noticeHolder.visibility = View.VISIBLE
        noticeView.setText(text)
        if (actionText == null) {
            noticeView.setAction(null, null)
        } else {
            noticeView.setAction(actionText) { startLocalInstall() }
        }
    }

    /** 列表与空状态二选一，不会同时出现。 */
    private fun renderRows() {
        val rows = buildRows()
        adapter.submit(rows)
        val empty = rows.isEmpty()
        rvPlugins.visibility = if (empty) View.GONE else View.VISIBLE
        hatchHolder.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            hatchView.setMessage(emptyMessage())
        }
    }

    private fun emptyMessage(): String = when {
        // 冷启动还在读盘：这时候列表空不是「没有插件」，别写错话
        !pm.isReady -> getString(R.string.pp_preparing)
        filterIndex == FILTER_AVAILABLE -> getString(R.string.pp_empty_available)
        filterIndex == FILTER_UPDATES -> getString(R.string.pp_empty_updates)
        else -> getString(R.string.plugin_panel_empty)
    }

    private fun buildRows(): List<PluginRow> {
        val updating = pm.updates().map { it.id }.toSet()
        return when (filterIndex) {
            FILTER_AVAILABLE ->
                pm.available().mapIndexed { index, manifest -> availableRow(index, manifest) }

            FILTER_UPDATES ->
                pm.updates().mapIndexed { index, manifest -> updateRow(index, manifest) }

            else -> pm.installed().mapIndexed { index, record -> installedRow(index, record, updating) }
        }
    }

    /**
     * 已装的一行。状态三态优先级：装载失败 / 与宿主不兼容 → 黄块（需要你看一眼）；
     * 否则按下启停状态给实线框或虚线框；有新版也落黄块（提示去更新）。
     */
    private fun installedRow(index: Int, record: InstalledRecord, updating: Set<String>): PluginRow {
        val manifest = pm.manifestOf(record.id)
        val loadError = pm.loadError(record.id)
        val incompatible = runCatching { manifest?.incompatibleReason() }.getOrNull()
        val state: PluginRowState
        val stateText: String
        when {
            loadError != null -> {
                state = PluginRowState.ATTENTION
                stateText = getString(R.string.pp_state_check)
            }

            incompatible != null -> {
                state = PluginRowState.ATTENTION
                stateText = getString(R.string.pp_state_newer_host)
            }

            !record.enabled -> {
                state = PluginRowState.OFF
                stateText = getString(R.string.pp_state_off)
            }

            updating.contains(record.id) -> {
                state = PluginRowState.ATTENTION
                stateText = getString(R.string.pp_state_update)
            }

            else -> {
                state = PluginRowState.READY
                stateText = getString(R.string.pp_state_on)
            }
        }
        return PluginRow(
            number = index + 1,
            id = record.id,
            name = displayName(record.id, manifest),
            version = displayVersion(record.version, manifest),
            capabilities = capabilityLabels(manifest),
            state = state,
            stateText = stateText
        )
    }

    /** 仓库里有、本地没有的一行：黄块写「可安装」，不兼容的写「需更新主程序」。 */
    private fun availableRow(index: Int, manifest: PluginManifest): PluginRow {
        val incompatible = runCatching { manifest.incompatibleReason() }.getOrNull()
        return PluginRow(
            number = index + 1,
            id = manifest.id,
            name = displayName(manifest.id, manifest),
            version = displayVersion(manifest.version, manifest),
            capabilities = capabilityLabels(manifest),
            state = PluginRowState.ATTENTION,
            stateText = if (incompatible != null) {
                getString(R.string.pp_state_newer_host)
            } else {
                getString(R.string.pp_state_installable)
            }
        )
    }

    /** 有新版的一行。 */
    private fun updateRow(index: Int, manifest: PluginManifest): PluginRow {
        val incompatible = runCatching { manifest.incompatibleReason() }.getOrNull()
        return availableRow(index, manifest).copy(
            stateText = if (incompatible != null) {
                getString(R.string.pp_state_newer_host)
            } else {
                getString(R.string.pp_state_update)
            }
        )
    }

    private fun displayName(id: String, manifest: PluginManifest?): String =
        manifest?.name?.takeIf { it.isNotBlank() } ?: id

    private fun displayVersion(recordVersion: String, manifest: PluginManifest?): String =
        manifest?.version?.takeIf { it.isNotBlank() } ?: recordVersion

    /**
     * 能力标签：顺序固定，和仓库里的声明顺序无关，读起来才稳定。
     * 拿不到清单（快照丢了、索引还没同步）时返回一个「未知」标签——
     * 这种情况不能显示「无额外能力」，那是在替插件说谎。
     */
    private fun capabilityLabels(manifest: PluginManifest?): List<String> {
        if (manifest == null) {
            return listOf(getString(R.string.pp_size_unknown))
        }
        return CAPABILITY_ORDER.filter { manifest.requires.contains(it) }.map { capabilityLabel(it) }
    }

    private fun capabilityLabel(capability: Capability): String = getString(
        when (capability) {
            Capability.SCREENSHOT -> R.string.pp_cap_screenshot
            Capability.NETWORK -> R.string.pp_cap_network
            Capability.ACCESSIBILITY -> R.string.pp_cap_accessibility
            Capability.SHIZUKU -> R.string.pp_cap_shizuku
            Capability.OVERLAY -> R.string.pp_cap_overlay
        }
    )

    // -----------------------------------------------------------------------
    // 详情屏
    // -----------------------------------------------------------------------

    private fun showDetail(id: String) {
        detailId = id
        actionHint = ""
        detailHintView = null
        listScreen.visibility = View.GONE
        detailScroll.visibility = View.VISIBLE
        detailScroll.scrollTo(0, 0)
        renderDetail()
    }

    private fun showList() {
        detailId = null
        actionHint = ""
        detailHintView = null
        detailScroll.visibility = View.GONE
        listScreen.visibility = View.VISIBLE
        render()
    }

    private fun renderDetail() {
        val id = detailId ?: return
        val record = pm.installed().firstOrNull { it.id == id }
        val manifest = pm.manifestOf(id)
        if (record == null && manifest == null) {
            // 插件在别处被卸载了：退回列表，别留一屏空数据
            showList()
            return
        }
        val update = pm.updates().firstOrNull { it.id == id }
        val name = displayName(id, manifest)
        val version = displayVersion(record?.version.orEmpty(), manifest)
        val enabled = record?.enabled == true
        val verified = record?.verified == true

        detailContainer.removeAllViews()
        detailHintView = null

        addBackRow()
        addEyebrow(getString(R.string.pp_eyebrow_plugin))
        addHeading(name, 22f)
        addFieldLine("v" + version)

        // 说明
        val description = manifest?.description.orEmpty()
        if (description.isNotBlank()) {
            addEyebrow(getString(R.string.pp_eyebrow_about))
            addSubHeading(getString(R.string.pp_about_label))
            addBody(description)
        }

        // 能力声明：标签 + 权限原话（这句话一个字都不许改）
        addEyebrow(getString(R.string.pp_eyebrow_capability))
        addSubHeading(getString(R.string.pp_capability_label))
        addChips(capabilityLabels(manifest))
        addNotice(getString(R.string.plugin_install_confirm_body))

        // 来源与校验 / 体积 / API 版本 / 状态
        addEyebrow(getString(R.string.pp_eyebrow_source))
        addSubHeading(getString(R.string.pp_source_label))
        addFieldLine(
            when {
                record == null -> getString(R.string.pp_source_repo_pending)
                verified -> getString(R.string.plugin_verified)
                else -> getString(R.string.plugin_unverified)
            }
        )
        addFieldRow(getString(R.string.pp_size_label), formatSize(manifest?.sizeBytes ?: -1L))
        addFieldRow(
            getString(R.string.pp_api_label),
            manifest?.let { getString(R.string.plugin_api_format, it.apiVersion) }
                ?: getString(R.string.pp_size_unknown)
        )
        addFieldRow(
            getString(R.string.pp_state_label),
            when {
                record == null -> getString(R.string.pp_state_not_installed)
                enabled -> getString(R.string.pp_state_on)
                else -> getString(R.string.pp_state_off)
            }
        )
        record?.let { addFieldRow(getString(R.string.pp_installed_at), formatStamp(it.installedAt)) }

        // 装载失败 / 与宿主不兼容：白底黄旗，写清楚原因
        pm.loadError(id)?.let { reason ->
            addNotice(getString(R.string.pp_load_error_label) + "：" + reason)
        }
        runCatching { manifest?.incompatibleReason() }.getOrNull()?.let { reason ->
            addNotice(reason)
        }

        // 操作
        addEyebrow(getString(R.string.pp_eyebrow_actions))
        renderActions(id, name, manifest, update, record != null)

        if (actionHint.isNotBlank()) {
            val hint = Ef.field(this, actionHint)
            hint.setTextColor(Ef.color(this, R.color.ef_ink))
            detailContainer.addView(hint, matchWrap(Ef.dp(this, 10)))
            detailHintView = hint
        }
    }

    private fun renderActions(
        id: String,
        name: String,
        manifest: PluginManifest?,
        update: PluginManifest?,
        installed: Boolean
    ) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL

        if (installed) {
            val enabled = pm.installed().firstOrNull { it.id == id }?.enabled == true
            row.addView(
                actionButton(
                    getString(
                        if (enabled) R.string.plugin_action_disable else R.string.plugin_action_enable
                    ),
                    primary = true
                ) { toggleEnabled(id, enabled) },
                actionParams(0)
            )
            if (update != null) {
                row.addView(
                    actionButton(getString(R.string.plugin_action_update), primary = false) {
                        confirmInstall(update)
                    },
                    actionParams(8)
                )
            }
        } else if (manifest != null) {
            row.addView(
                actionButton(getString(R.string.plugin_action_install), primary = true) {
                    confirmInstall(manifest)
                },
                actionParams(0)
            )
        }

        if (row.childCount > 0) {
            detailContainer.addView(row, matchWrap(Ef.dp(this, 10)))
        }

        if (installed) {
            // 预览设置页：宿主用插件声明的 ScreenSpec 现画表单，用户装之前就能看清"这个插件能配什么"。
            // 找不到可用的步骤类型时就整块不出现，不给一个点开是白屏的按钮。
            val stepTypes = runCatching { PluginRegistry.stepTypesOf(id) }.getOrDefault(emptyList())
            if (stepTypes.isNotEmpty()) {
                val preview = actionButton(getString(R.string.pp_preview_spec), primary = false) {
                    openSpecPreview(stepTypes.first().typeId)
                }
                detailContainer.addView(preview, matchWrap(Ef.dp(this, 10)))
            }
        }

        if (installed) {
            // 红色只给删除；点了还要在对话框里再确认一次
            val remove = actionButton(
                getString(R.string.plugin_action_uninstall),
                primary = false,
                danger = true
            ) { confirmUninstall(id, name) }
            detailContainer.addView(remove, matchWrap(Ef.dp(this, 10)))
        }
    }

    /** 打开步骤编辑器预览插件的设置页；插件没装载（例如刚被停用）时给一句人话提示。 */
    private fun openSpecPreview(typeId: String) {
        runCatching {
            startActivity(
                Intent(this, PluginStepEditorActivity::class.java)
                    .putExtra(PluginStepEditorActivity.EXTRA_SPEC_CLASS, typeId)
            )
        }.onFailure {
            Toast.makeText(this, R.string.pp_preview_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun actionButton(
        label: String,
        primary: Boolean,
        danger: Boolean = false,
        onClick: () -> Unit
    ): EfBlock {
        val button = EfBlock(this, label, primary = primary, danger = danger, textSizeSp = 13f)
        button.minimumWidth = Ef.dp(this, 104)
        button.minimumHeight = Ef.dp(this, 48)
        // 切角那一侧多留一点内边距，字不压进切角
        button.setPadding(
            Ef.dp(this, 12),
            Ef.dp(this, 8),
            Ef.dp(this, 20),
            Ef.dp(this, 8)
        )
        button.setOnClickListener { onClick() }
        return button
    }

    private fun actionParams(marginStartDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = Ef.dp(this@PluginPanelActivity, marginStartDp) }

    private fun addBackRow() {
        val back = TextView(this)
        back.text = getString(R.string.pp_back_to_list)
        back.textSize = 13f
        back.includeFontPadding = false
        back.gravity = Gravity.CENTER
        back.isClickable = true
        back.isFocusable = true
        back.isSingleLine = true
        back.minimumHeight = Ef.dp(this, 48)
        back.setTextColor(Ef.color(this, R.color.ef_ink))
        back.background = Ef.cutBackground(this, R.color.ef_chip, null, cutDp = 6f)
        back.setPadding(
            Ef.dp(this, 12),
            Ef.dp(this, 8),
            Ef.dp(this, 20),
            Ef.dp(this, 8)
        )
        back.setOnClickListener { showList() }
        detailContainer.addView(back, actionParams(0))
    }

    /** 段落眉标：// XXX，大写拉丁字；下面跟一行中文粗体标题。 */
    private fun addEyebrow(text: String) {
        val view = Ef.eyebrow(this, text)
        detailContainer.addView(view, matchWrap(Ef.dp(this, 22)))
    }

    private fun addSubHeading(text: String) {
        val view = Ef.heading(this, text, 16f)
        detailContainer.addView(view, matchWrap(Ef.dp(this, 4)))
    }

    private fun addHeading(text: String, sizeSp: Float) {
        val view = Ef.heading(this, text, sizeSp)
        detailContainer.addView(view, matchWrap(Ef.dp(this, 6)))
    }

    private fun addFieldLine(text: String) {
        val view = Ef.field(this, text)
        view.setTextColor(Ef.color(this, R.color.ef_ink))
        detailContainer.addView(view, matchWrap(Ef.dp(this, 6)))
    }

    private fun addBody(text: String) {
        val view = TextView(this)
        view.text = text
        view.textSize = 13f
        view.typeface = Typeface.DEFAULT
        view.setTextColor(Ef.color(this, R.color.ef_ink2))
        view.setLineSpacing(Ef.dp(this, 4).toFloat(), 1f)
        detailContainer.addView(view, matchWrap(Ef.dp(this, 6)))
    }

    private fun addFieldRow(label: String, value: String) {
        val row = EfFieldRow(this, label, value)
        detailContainer.addView(row, matchWrap(Ef.dp(this, 6)))
    }

    /** 提示：白底细框 + 黄旗，永远不用红色。 */
    private fun addNotice(text: String) {
        val notice = EfNotice(this, text)
        detailContainer.addView(notice, matchWrap(Ef.dp(this, 10)))
    }

    /** 能力标签：浅灰切角小块，窄屏横向可滑，不会被裁掉。 */
    private fun addChips(labels: List<String>) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        if (labels.isEmpty()) {
            row.addView(chipView(getString(R.string.pp_cap_none)))
        } else {
            labels.forEach { row.addView(chipView(it)) }
        }
        val scroll = HorizontalScrollView(this)
        scroll.isHorizontalScrollBarEnabled = false
        scroll.addView(
            row,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        detailContainer.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ef.dp(this@PluginPanelActivity, 8) }
        )
    }

    private fun chipView(text: String): TextView {
        val chip = TextView(this)
        chip.text = text
        chip.textSize = 11f
        chip.includeFontPadding = false
        chip.isSingleLine = true
        chip.setTextColor(Ef.color(this, R.color.ef_ink2))
        chip.background = Ef.cutBackground(this, R.color.ef_chip, null, cutDp = 5f)
        chip.setPadding(
            Ef.dp(this, 7),
            Ef.dp(this, 3),
            Ef.dp(this, 10),
            Ef.dp(this, 5)
        )
        chip.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = Ef.dp(this@PluginPanelActivity, 6) }
        return chip
    }

    private fun matchWrap(topMarginPx: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = topMarginPx }

    // -----------------------------------------------------------------------
    // 动作
    // -----------------------------------------------------------------------

    private fun startSync() {
        if (pm.isSyncing) {
            return
        }
        pm.refreshIndex { outcome ->
            if (isFinishing || isDestroyed) {
                return@refreshIndex
            }
            when (outcome) {
                is PluginManager.SyncOutcome.Ok ->
                    toast(getString(R.string.pp_sync_done, outcome.count))

                is PluginManager.SyncOutcome.Failed ->
                    toast(getString(R.string.pp_sync_failed, outcome.reason))
            }
        }
    }

    private fun toggleEnabled(id: String, currentlyEnabled: Boolean) {
        if (actionRunning) {
            return
        }
        actionRunning = true
        val target = !currentlyEnabled
        pm.setEnabled(id, target) { ok ->
            actionRunning = false
            if (isFinishing || isDestroyed) {
                return@setEnabled
            }
            if (!ok) {
                toast(
                    getString(
                        if (target) R.string.pp_enable_failed else R.string.pp_disable_failed
                    )
                )
            }
            renderDetail()
        }
    }

    private fun confirmUninstall(id: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pp_uninstall_title, name))
            .setMessage(R.string.pp_uninstall_body)
            .setPositiveButton(R.string.plugin_action_uninstall) { _, _ -> uninstallPlugin(id) }
            .setNegativeButton(R.string.pp_cancel, null)
            .show()
    }

    private fun uninstallPlugin(id: String) {
        if (actionRunning) {
            return
        }
        actionRunning = true
        pm.uninstall(id) { ok ->
            actionRunning = false
            if (isFinishing || isDestroyed) {
                return@uninstall
            }
            toast(
                getString(if (ok) R.string.pp_uninstall_done else R.string.pp_uninstall_failed)
            )
            if (ok) {
                showList()
            } else {
                renderDetail()
            }
        }
    }

    private fun confirmInstall(manifest: PluginManifest) {
        if (actionRunning) {
            return
        }
        val incompatible = runCatching { manifest.incompatibleReason() }.getOrNull()
        if (incompatible != null) {
            // 装到一半才失败不如现在就说清楚
            toast(incompatible)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.plugin_install_confirm_title)
            .setMessage(R.string.plugin_install_confirm_body)
            .setPositiveButton(R.string.pp_confirm_install) { _, _ -> startInstall(manifest) }
            .setNegativeButton(R.string.pp_cancel, null)
            .show()
    }

    private fun startInstall(manifest: PluginManifest) {
        if (actionRunning) {
            return
        }
        actionRunning = true
        actionHint = getString(R.string.pp_install_progress_unknown)
        renderDetail()
        pm.install(
            manifest,
            onProgress = { done, total ->
                if (isFinishing || isDestroyed) {
                    return@install
                }
                val text = if (total > 0L && done >= 0L && done <= total) {
                    getString(R.string.pp_install_progress, (done * 100L / total).toInt())
                } else {
                    getString(R.string.pp_install_progress_unknown)
                }
                actionHint = text
                // 只改这一行：进度回调可能几十次，整屏重建太浪费
                detailHintView?.text = text
            },
            onDone = { result ->
                actionRunning = false
                if (isFinishing || isDestroyed) {
                    return@install
                }
                actionHint = ""
                handleInstallResult(result, manifest.id)
            }
        )
    }

    private fun handleInstallResult(result: InstallResult, fallbackId: String?) {
        when (result) {
            is InstallResult.Success -> {
                toast(getString(R.string.pp_install_success))
                // 插件 id 就是文件所在目录名（filesDir/plugins/<id>/<ver>.jar）
                val id = fallbackId ?: runCatching { result.path.parentFile?.name }.getOrNull()
                if (!id.isNullOrBlank()) {
                    showDetail(id)
                } else {
                    showList()
                }
            }

            is InstallResult.DigestMismatch -> toast(
                getString(R.string.pp_install_failed, getString(R.string.pp_digest_mismatch))
            )

            is InstallResult.SignatureInvalid -> toast(
                getString(R.string.pp_install_failed, result.reason)
            )

            is InstallResult.NetworkError -> toast(
                getString(R.string.pp_install_failed, result.message)
            )

            is InstallResult.IoError -> toast(
                getString(R.string.pp_install_failed, result.message)
            )
        }
    }

    // -----------------------------------------------------------------------
    // 本地文件安装
    // -----------------------------------------------------------------------

    private fun startLocalInstall() {
        if (!pm.allowUnsigned) {
            AlertDialog.Builder(this)
                .setTitle(R.string.pp_unsigned_denied_title)
                .setMessage(R.string.pp_unsigned_disabled)
                .setPositiveButton(R.string.pp_ok, null)
                .show()
            return
        }
        runCatching { openJar.launch(arrayOf("*/*")) }
            .onFailure {
                LogBus.warn("打开文件选择器失败：" + describe(it))
                toast(getString(R.string.pp_pick_copy_failed))
            }
    }

    private fun handlePickedFile(uri: Uri) {
        if (isFinishing || isDestroyed || actionRunning) {
            return
        }
        val pickedName = queryDisplayName(uri).ifBlank { uri.lastPathSegment.orEmpty() }
        if (!pickedName.endsWith(".jar", ignoreCase = true)) {
            toast(getString(R.string.pp_pick_not_jar))
            return
        }
        val copied = copyToCache(uri)
        if (copied == null) {
            toast(getString(R.string.pp_pick_copy_failed))
            return
        }
        actionRunning = true
        actionHint = getString(R.string.pp_install_progress_unknown)
        renderDetail()
        pm.installFromFile(copied) { result ->
            actionRunning = false
            runCatching { copied.delete() }
            if (isFinishing || isDestroyed) {
                return@installFromFile
            }
            actionHint = ""
            handleInstallResult(result, null)
        }
    }

    /** 把选中的 jar 复制到 cacheDir（PluginManager 只接受真实文件，不接受 content:// ）。 */
    private fun copyToCache(uri: Uri): File? {
        val target = File(cacheDir, "plugin-pick-" + System.currentTimeMillis() + ".jar")
        return try {
            val input = contentResolver.openInputStream(uri)
            if (input == null) {
                return null
            }
            input.use { source ->
                target.outputStream().use { sink -> source.copyTo(sink) }
            }
            if (target.isFile && target.length() > 0L) {
                target
            } else {
                runCatching { target.delete() }
                null
            }
        } catch (t: Throwable) {
            LogBus.warn("复制选中的插件包失败：" + describe(t))
            runCatching { target.delete() }
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && cursor.columnCount > 0) cursor.getString(0) else null
                }
        }.getOrNull().orEmpty()
    }

    // -----------------------------------------------------------------------
    // 工具
    // -----------------------------------------------------------------------

    private fun formatClock(at: Long): String = runCatching {
        Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(CLOCK_FORMAT)
    }.getOrDefault("--:--")

    private fun formatStamp(at: Long): String {
        if (at <= 0L) {
            return getString(R.string.pp_size_unknown)
        }
        return runCatching {
            Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(STAMP_FORMAT)
        }.getOrDefault(getString(R.string.pp_size_unknown))
    }

    /** 体积：负数或 0（仓库没给）就如实写「未知」，不编一个数出来。 */
    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) {
            return getString(R.string.pp_size_unknown)
        }
        if (bytes < 1024L) {
            return bytes.toString() + " B"
        }
        val kb = bytes / 1024.0
        if (kb < 1024.0) {
            return oneDecimal(kb) + " KB"
        }
        return oneDecimal(kb / 1024.0) + " MB"
    }

    private fun oneDecimal(value: Double): String {
        val tenths = Math.round(value * 10.0)
        val whole = tenths / 10L
        val rest = tenths % 10L
        return if (rest == 0L) whole.toString() else whole.toString() + "." + rest.toString()
    }

    private fun toast(text: String) {
        if (isFinishing || isDestroyed) {
            return
        }
        runCatching { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    private fun describe(t: Throwable): String = t.message ?: t.javaClass.simpleName

    companion object {

        private const val FILTER_INSTALLED = 0
        private const val FILTER_AVAILABLE = 1
        private const val FILTER_UPDATES = 2

        private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /** 能力标签的固定顺序：与仓库声明的先后无关，界面读起来才稳定。 */
        private val CAPABILITY_ORDER = listOf(
            Capability.SCREENSHOT,
            Capability.NETWORK,
            Capability.ACCESSIBILITY,
            Capability.SHIZUKU,
            Capability.OVERLAY
        )
    }
}
