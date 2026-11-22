package com.example.autotapper

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.autotapper.capture.ScreenCaptureController
import com.example.autotapper.engine.TimeService
import com.example.autotapper.ocr.OcrSettings
import com.example.autotapper.runtime.Mirror
import com.example.autotapper.runtime.PluginHttp
import com.example.autotapper.runtime.PluginManager
import com.example.autotapper.runtime.RepoSource
import com.example.autotapper.ui.EfFieldRow
import com.example.autotapper.ui.EfSegmented
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 设置页（// SETTINGS）：把散落在主界面各段、以及此前没有入口的开关与配置集中到一处。
 *
 * 六个分节：注入通道 / 屏幕截图 / 识字服务 / 插件仓库 / 时间校准 / 运行日志。
 * 三条做法：
 * 1. **没有「保存」总按钮**：开关与输入框改动立刻写进各自的落盘位置（AutoTapperConfig / OcrSettings /
 *    PluginManager），按钮只负责动作（拉授权、清日志、校准）。写盘失败不崩界面，只 Toast + LogBus；
 * 2. **数据源不重复实现**：截图问 [ScreenCaptureController]，识字问 [OcrSettings]，
 *    插件仓库与未签名开关问 [PluginManager]，时间问 [TimeService]，日志问 [LogBus]；
 * 3. **授权页/系统设置回来要刷新**：onResume 整屏重画一次，状态永远是真值而不是上次渲染时的旧值。
 *
 * 视觉只用 Ef 基元与 ef_* 颜色：切角块表示可点击，黄只做填充，红色只给「清空」这种破坏性动作。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var pm: PluginManager
    private lateinit var container: LinearLayout

    /** 校准要发网络请求，用界面自己的 scope：onDestroy 里取消，回调不会落到已经没了的界面上。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 当前正在往哪个分节里加字段；null 表示不在任何分节里（版本行）。 */
    private var currentSection: LinearLayout? = null

    // 整屏重建时这些指针跟着换；局部刷新（打字、按开关）只改这几行，不重建整页
    private var tapStatusRow: EfFieldRow? = null
    private var captureStatusRow: EfFieldRow? = null
    private var ocrStatusRow: EfFieldRow? = null
    private var repoLabelRow: EfFieldRow? = null
    private var mirrorLabelRow: EfFieldRow? = null
    private var timingOffsetRow: EfFieldRow? = null
    private var timingCalibratedRow: EfFieldRow? = null
    private var logsCountRow: EfFieldRow? = null

    /** 仓库格式错误时的红字提示；格式正确时隐藏。 */
    private var repoErrorView: TextView? = null

    /** 选「自定义」时才出现的模板输入区（保存/预览按钮、预览结果、红字都在里面）。 */
    private var mirrorCustomBox: LinearLayout? = null

    /** 预览拼出来的 index.json 完整地址。 */
    private var mirrorPreviewView: TextView? = null

    /** 镜像模板校验失败的红字。 */
    private var mirrorErrorView: TextView? = null

    /** onCreate 里已经画过一次，第一次 onResume 不必重复画。 */
    private var firstResume = true

    /** 校准请求在跑：连点按钮只打一次网络。 */
    private var calibrating = false

    // -----------------------------------------------------------------------
    // 生命周期
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // get 等于 init（幂等）：Application 里已经初始化过，这里拿到的是同一个单例
        pm = PluginManager.get(this)
        container = findViewById(R.id.settings_container)

        render()
    }

    override fun onResume() {
        super.onResume()
        // 从截图授权页、无障碍设置、Shizuku 回来时状态全变了，整屏重画一遍最省心
        if (firstResume) {
            firstResume = false
        } else {
            render()
        }
    }

    override fun onDestroy() {
        runCatching { scope.cancel() }
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // 整屏渲染
    // -----------------------------------------------------------------------

    private fun render() {
        if (isFinishing || isDestroyed) {
            return
        }
        runCatching {
            currentSection = null
            tapStatusRow = null
            captureStatusRow = null
            ocrStatusRow = null
            repoLabelRow = null
            mirrorLabelRow = null
            timingOffsetRow = null
            timingCalibratedRow = null
            logsCountRow = null
            repoErrorView = null
            mirrorCustomBox = null
            mirrorPreviewView = null
            mirrorErrorView = null

            container.removeAllViews()
            addInjectSection()
            addCaptureSection()
            addOcrSection()
            addPluginSection()
            addTimingSection()
            addLogSection()
            addVersionLine()
        }.onFailure {
            LogBus.error("设置页渲染失败：" + describe(it))
            toast(getString(R.string.set_render_failed))
        }
    }

    // -----------------------------------------------------------------------
    // 1. // INJECT 注入通道
    // -----------------------------------------------------------------------

    private fun addInjectSection() {
        val block = newSection(
            getString(R.string.set_eyebrow_inject),
            getString(R.string.set_section_inject)
        )

        val mode = currentTapMode()
        val segmented = EfSegmented(
            this,
            listOf(
                getString(R.string.set_inject_mode_accessibility),
                getString(R.string.set_inject_mode_shizuku)
            )
        )
        segmented.setSelected(if (mode == AutoTapperConfig.TAP_MODE_SHIZUKU) 1 else 0)
        segmented.onSelected = { index -> applyTapMode(index) }
        block.addView(segmented, matchWrap(Ef.dp(this, 10)))

        val statusRow = EfFieldRow(
            this,
            getString(R.string.set_inject_state_label),
            tapModeStatusText(mode)
        )
        block.addView(statusRow, matchWrap(Ef.dp(this, 10)))
        tapStatusRow = statusRow

        addBody(getString(R.string.set_inject_switch_hint))
    }

    private fun currentTapMode(): String = runCatching {
        AutoTapperConfig.getTapMode(this)
    }.getOrDefault(AutoTapperConfig.TAP_MODE_ACCESSIBILITY)

    /** 当前通道的人话状态。读系统设置与 Shizuku binder 都可能抛，统一兜住。 */
    private fun tapModeStatusText(mode: String): String = runCatching {
        if (mode == AutoTapperConfig.TAP_MODE_SHIZUKU) {
            when (ShizukuManager.state(this)) {
                ShizukuManager.State.READY -> getString(R.string.set_inject_shizuku_ready)
                ShizukuManager.State.NO_PERMISSION -> getString(R.string.set_inject_shizuku_no_permission)
                ShizukuManager.State.NOT_RUNNING -> getString(R.string.set_inject_shizuku_not_running)
                ShizukuManager.State.NOT_INSTALLED -> getString(R.string.set_inject_shizuku_not_installed)
                else -> getString(R.string.set_inject_state_unknown)
            }
        } else {
            val enabled = PermissionUtils.isAccessibilityServiceEnabled(
                this,
                AutoClickService::class.java
            )
            if (enabled) {
                getString(R.string.set_inject_accessibility_on)
            } else {
                getString(R.string.set_inject_accessibility_off)
            }
        }
    }.getOrDefault(getString(R.string.set_inject_state_unknown))

    private fun applyTapMode(index: Int) {
        val target = if (index == 1) {
            AutoTapperConfig.TAP_MODE_SHIZUKU
        } else {
            AutoTapperConfig.TAP_MODE_ACCESSIBILITY
        }
        if (target == currentTapMode()) {
            return
        }
        val saved = runCatching { AutoTapperConfig.saveTapMode(this, target) }
        if (saved.isFailure) {
            LogBus.error("保存注入通道失败：" + describe(saved.exceptionOrNull()))
            toast(getString(R.string.set_save_failed))
            return
        }
        LogBus.info(
            if (target == AutoTapperConfig.TAP_MODE_SHIZUKU) {
                "注入通道已切换为 Shizuku，下次开始才生效。"
            } else {
                "注入通道已切换为无障碍，下次开始才生效。"
            }
        )
        tapStatusRow?.setValue(tapModeStatusText(target))
        // 这句话是用户必须看到的：引擎在启动时才读通道，切完当前这一轮还是老的
        toast(getString(R.string.set_inject_switch_hint))
    }

    // -----------------------------------------------------------------------
    // 2. // CAPTURE 屏幕截图
    // -----------------------------------------------------------------------

    private fun addCaptureSection() {
        val block = newSection(
            getString(R.string.set_eyebrow_capture),
            getString(R.string.set_section_capture)
        )

        val statusRow = EfFieldRow(
            this,
            getString(R.string.set_capture_status_label),
            captureStatusText()
        )
        block.addView(statusRow, matchWrap(Ef.dp(this, 10)))
        captureStatusRow = statusRow

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            actionButton(getString(R.string.set_capture_grant), primary = true) { requestCapture() },
            buttonParams(0, weight = 1f)
        )
        row.addView(
            actionButton(getString(R.string.set_capture_stop), primary = false) { stopCapture() },
            buttonParams(8, weight = 1f)
        )
        block.addView(row, matchWrap(Ef.dp(this, 10)))

        addBody(getString(R.string.set_capture_note))
    }

    private fun captureStatusText(): String = runCatching {
        if (ScreenCaptureController.isRunning()) {
            getString(R.string.set_capture_running)
        } else {
            getString(R.string.set_capture_idle)
        }
    }.getOrDefault(getString(R.string.set_capture_idle))

    private fun refreshCaptureStatus() {
        captureStatusRow?.setValue(captureStatusText())
    }

    private fun requestCapture() {
        val result = runCatching { ScreenCaptureController.start(this) }
        if (result.isFailure) {
            LogBus.error("请求截图授权失败：" + describe(result.exceptionOrNull()))
            toast(getString(R.string.set_action_failed))
            return
        }
        // 服务起停是异步的：立刻读状态一定是旧值，稍等一下再刷
        container.postDelayed({ runCatching { refreshCaptureStatus() } }, DELAYED_REFRESH_MS)
    }

    private fun stopCapture() {
        val result = runCatching { ScreenCaptureController.stop(this) }
        if (result.isFailure) {
            LogBus.error("停止截图失败：" + describe(result.exceptionOrNull()))
            toast(getString(R.string.set_action_failed))
            return
        }
        container.postDelayed({ runCatching { refreshCaptureStatus() } }, DELAYED_REFRESH_MS)
    }

    // -----------------------------------------------------------------------
    // 3. // OCR 识字服务
    // -----------------------------------------------------------------------

    private fun addOcrSection() {
        val block = newSection(
            getString(R.string.set_eyebrow_ocr),
            getString(R.string.set_section_ocr)
        )

        // 访问令牌：密码样式 + 右侧「显示/隐藏」。每次改动立刻写盘，不设总保存按钮。
        block.addView(labelView(getString(R.string.set_ocr_token_label)))
        val tokenRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tokenEdit = inputEdit(
            runCatching { OcrSettings.token(this) }.getOrDefault(""),
            getString(R.string.set_ocr_token_hint)
        )
        tokenEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        tokenEdit.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )
        val revealView = textAction(getString(R.string.set_ocr_token_show))
        revealView.setOnClickListener {
            runCatching {
                val reveal = tokenEdit.inputType != REVEALED_INPUT_TYPE
                tokenEdit.inputType = if (reveal) {
                    REVEALED_INPUT_TYPE
                } else {
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                revealView.text = getString(
                    if (reveal) R.string.set_ocr_token_hide else R.string.set_ocr_token_show
                )
                revealView.contentDescription = revealView.text
            }.onFailure {
                LogBus.warn("切换令牌显示失败：" + describe(it))
            }
        }
        tokenRow.addView(tokenEdit)
        tokenRow.addView(revealView)
        block.addView(tokenRow, matchWrap(Ef.dp(this, 6)))
        tokenEdit.watchText { raw ->
            OcrSettings.saveToken(this, raw)
            refreshOcrStatus()
        }

        // 服务地址：留空就用 OcrSettings 里的官方默认值
        block.addView(labelView(getString(R.string.set_ocr_endpoint_label)))
        val endpointEdit = inputEdit(
            runCatching { OcrSettings.endpoint(this) }.getOrDefault(""),
            getString(R.string.set_ocr_endpoint_hint)
        )
        endpointEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        block.addView(endpointEdit, matchWrap(Ef.dp(this, 6)))
        endpointEdit.watchText { raw ->
            OcrSettings.saveEndpoint(this, raw)
            refreshOcrStatus()
        }

        addSwitchRow(
            getString(R.string.set_ocr_enable_label),
            runCatching { OcrSettings.enabled(this) }.getOrDefault(true)
        ) { enabled ->
            OcrSettings.saveEnabled(this, enabled)
            refreshOcrStatus()
        }

        val statusRow = EfFieldRow(
            this,
            getString(R.string.set_ocr_status_label),
            ocrStatusText()
        )
        block.addView(statusRow, matchWrap(Ef.dp(this, 10)))
        ocrStatusRow = statusRow

        addBody(getString(R.string.set_ocr_note))
    }

    /**
     * 识字状态一句话：没填令牌就是「未配置」，填了写尾号四位；
     * 停用或地址不是 https 时在后面追加一句，避免"看着已配置其实发不出去"。
     */
    private fun ocrStatusText(): String = runCatching {
        val token = OcrSettings.token(this)
        if (token.isEmpty()) {
            return@runCatching getString(R.string.set_ocr_status_none)
        }
        val parts = mutableListOf(
            getString(R.string.set_ocr_status_ready, token.takeLast(TOKEN_TAIL_LENGTH))
        )
        if (!OcrSettings.enabled(this)) {
            parts.add(getString(R.string.set_ocr_status_off_suffix))
        }
        if (!OcrSettings.isEndpointUsable(OcrSettings.endpoint(this))) {
            parts.add(getString(R.string.set_ocr_status_endpoint_suffix))
        }
        parts.joinToString(" · ")
    }.getOrDefault(getString(R.string.set_ocr_status_none))

    private fun refreshOcrStatus() {
        ocrStatusRow?.setValue(ocrStatusText())
    }

    // -----------------------------------------------------------------------
    // 4. // PLUGINS 插件仓库
    // -----------------------------------------------------------------------

    private fun addPluginSection() {
        val block = newSection(
            getString(R.string.set_eyebrow_plugins),
            getString(R.string.set_section_plugins)
        )

        val repoRow = EfFieldRow(
            this,
            getString(R.string.set_plugins_current_label),
            currentRepoLabel()
        )
        block.addView(repoRow, matchWrap(Ef.dp(this, 10)))
        repoLabelRow = repoRow

        block.addView(labelView(getString(R.string.set_plugins_repo_label)))
        val repoRowHolder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val repoEdit = inputEdit(
            currentRepoLabel(),
            getString(R.string.set_plugins_repo_hint)
        )
        repoEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        repoEdit.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )
        repoRowHolder.addView(repoEdit)
        repoRowHolder.addView(
            actionButton(getString(R.string.set_plugins_repo_save), primary = false) {
                saveRepo(repoEdit)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = Ef.dp(this@SettingsActivity, 8) }
        )
        block.addView(repoRowHolder, matchWrap(Ef.dp(this, 6)))

        val errorView = TextView(this).apply {
            textSize = 12f
            setTextColor(Ef.color(this@SettingsActivity, R.color.ef_danger))
            includeFontPadding = false
            visibility = View.GONE
        }
        block.addView(errorView, matchWrap(Ef.dp(this, 6)))
        repoErrorView = errorView

        // 仓库定好之后才是镜像：镜像只决定"从哪个主机取文件"，不改变仓库身份
        addMirrorRows(block)

        addSwitchRow(
            getString(R.string.set_plugins_unsigned_label),
            runCatching { pm.allowUnsigned }.getOrDefault(false)
        ) { allow ->
            pm.allowUnsigned = allow
        }
        addBody(getString(R.string.set_plugins_unsigned_note))
        addBody(getString(R.string.set_plugins_official_note, RepoSource.DEFAULT.label))
    }

    private fun currentRepoLabel(): String = runCatching { pm.repo.label }
        .getOrDefault(RepoSource.DEFAULT.label)

    private fun saveRepo(edit: EditText) {
        val raw = edit.text?.toString().orEmpty()
        val parsed = runCatching { RepoSource.parse(raw) }.getOrNull()
        if (parsed == null) {
            showRepoError(getString(R.string.set_plugins_repo_format_error))
            return
        }
        hideRepoError()
        if (parsed.label == currentRepoLabel()) {
            // 同一个仓库重复保存：既不用弹确认，也不用刷索引
            toast(getString(R.string.set_plugins_repo_saved, parsed.label))
            return
        }
        if (parsed.label == RepoSource.DEFAULT.label) {
            applyRepo(parsed)
            return
        }
        // 非官方仓库：先问一句再换，换仓库等于换一个信任来源
        AlertDialog.Builder(this)
            .setTitle(R.string.set_plugins_unofficial_title)
            .setMessage(R.string.set_plugins_unofficial_body)
            .setPositiveButton(R.string.set_plugins_unofficial_confirm) { _, _ -> applyRepo(parsed) }
            .setNegativeButton(R.string.set_common_cancel, null)
            .show()
    }

    private fun applyRepo(source: RepoSource) {
        val result = runCatching { pm.repo = source }
        if (result.isFailure) {
            LogBus.error("切换插件仓库失败：" + describe(result.exceptionOrNull()))
            toast(getString(R.string.set_save_failed))
            return
        }
        toast(getString(R.string.set_plugins_repo_saved, source.label))
        repoLabelRow?.setValue(source.label)
    }

    private fun showRepoError(text: String) {
        val view = repoErrorView ?: return
        view.text = text
        view.contentDescription = text
        view.visibility = View.VISIBLE
    }

    private fun hideRepoError() {
        val view = repoErrorView ?: return
        view.visibility = View.GONE
    }

    // -----------------------------------------------------------------------
    // 4.1 插件仓库镜像
    // -----------------------------------------------------------------------

    /**
     * 镜像三件套：当前镜像行 + 三档分段选择 + 自定义模板输入（选「自定义」才出现）。
     * 换镜像会写 prefs、清上次同步错误并立刻重新同步一次（都在 [PluginManager.mirror] 的 setter 里）。
     */
    private fun addMirrorRows(block: LinearLayout) {
        val current = currentMirror()
        val row = EfFieldRow(
            this,
            getString(R.string.set_plugins_mirror_label),
            mirrorDisplayText(current)
        )
        block.addView(row, matchWrap(Ef.dp(this, 10)))
        mirrorLabelRow = row

        val segmented = EfSegmented(
            this,
            listOf(
                getString(R.string.set_plugins_mirror_direct),
                getString(R.string.set_plugins_mirror_jsdelivr),
                getString(R.string.set_plugins_mirror_custom)
            )
        )
        segmented.setSelected(mirrorIndex(current))
        segmented.onSelected = { index -> onMirrorSelected(index) }
        block.addView(segmented, matchWrap(Ef.dp(this, 10)))

        val customBox = buildCustomMirrorBox(current)
        block.addView(customBox, matchWrap(Ef.dp(this, 10)))
        mirrorCustomBox = customBox

        addBody(getString(R.string.set_plugins_mirror_note))
    }

    /** 自定义模板输入区：输入框 + 保存/预览两个按钮 + 预览结果 + 红字。 */
    private fun buildCustomMirrorBox(current: Mirror): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (current is Mirror.Custom) View.VISIBLE else View.GONE
        }
        box.addView(labelView(getString(R.string.set_plugins_mirror_template_label)))

        val templateEdit = inputEdit(
            (current as? Mirror.Custom)?.template.orEmpty(),
            getString(R.string.set_plugins_mirror_template_hint)
        )
        templateEdit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        box.addView(templateEdit, matchWrap(0))

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttonRow.addView(
            actionButton(getString(R.string.set_plugins_mirror_save), primary = true) {
                saveCustomMirror(templateEdit)
            },
            buttonParams(0, weight = 1f)
        )
        buttonRow.addView(
            actionButton(getString(R.string.set_plugins_mirror_preview), primary = false) {
                previewCustomMirror(templateEdit)
            },
            buttonParams(8, weight = 1f)
        )
        box.addView(buttonRow, matchWrap(Ef.dp(this, 8)))

        val previewView = TextView(this).apply {
            textSize = 12f
            setTextColor(Ef.color(this@SettingsActivity, R.color.ef_ink2))
            includeFontPadding = false
            visibility = View.GONE
        }
        box.addView(previewView, matchWrap(Ef.dp(this, 6)))
        mirrorPreviewView = previewView

        val errorView = TextView(this).apply {
            textSize = 12f
            setTextColor(Ef.color(this@SettingsActivity, R.color.ef_danger))
            includeFontPadding = false
            visibility = View.GONE
        }
        box.addView(errorView, matchWrap(Ef.dp(this, 6)))
        mirrorErrorView = errorView
        return box
    }

    private fun currentMirror(): Mirror = runCatching { pm.mirror }.getOrDefault(Mirror.JsDelivr)

    private fun currentRepo(): RepoSource = runCatching { pm.repo }.getOrDefault(RepoSource.DEFAULT)

    private fun mirrorIndex(mirror: Mirror): Int = when (mirror) {
        Mirror.Direct -> MIRROR_INDEX_DIRECT
        Mirror.JsDelivr -> MIRROR_INDEX_JSDELIVR
        is Mirror.Custom -> MIRROR_INDEX_CUSTOM
    }

    /** 当前镜像给人看的写法；自定义额外把主机名带出来，用户才知道到底打到哪。 */
    private fun mirrorDisplayText(mirror: Mirror): String {
        if (mirror !is Mirror.Custom) return mirror.label
        val host = mirror.host
        return if (host.isNullOrBlank()) {
            mirror.label
        } else {
            getString(R.string.set_plugins_mirror_custom_detail, mirror.label, host)
        }
    }

    /** 分段选择的回调。官方/内置镜像点一下就生效；自定义要先填模板再保存。 */
    private fun onMirrorSelected(index: Int) {
        when (index) {
            MIRROR_INDEX_DIRECT -> applyMirror(Mirror.Direct)
            MIRROR_INDEX_JSDELIVR -> applyMirror(Mirror.JsDelivr)
            else -> {
                // 还没保存的模板不能拿去发请求：先只把输入区亮出来
                syncMirrorBoxVisibility(Mirror.Custom(""))
                hideMirrorError()
                toast(getString(R.string.set_plugins_mirror_custom_tip))
            }
        }
    }

    /**
     * 落一次镜像改动：
     * 1. 自定义镜像先校验模板，再把主机名登记进 [PluginHttp] 白名单（不登记的话同步会被拒）；
     * 2. 写进 [PluginManager.mirror]，setter 会清同步错误并自动重新同步一次。
     */
    private fun applyMirror(mirror: Mirror) {
        val reason = mirror.invalidReason()
        if (reason != null) {
            showMirrorError(reason)
            return
        }
        if (mirror is Mirror.Custom) {
            val host = mirror.host
            if (host.isNullOrBlank() || !PluginHttp.allowHost(host)) {
                showMirrorError(getString(R.string.set_plugins_mirror_host_error))
                return
            }
        }
        hideMirrorError()
        if (currentMirror().encode() == mirror.encode()) {
            // 点到的就是当前镜像：别假装切换了，也别白跑一次同步
            syncMirrorBoxVisibility(mirror)
            toast(getString(R.string.set_plugins_mirror_unchanged))
            return
        }
        val result = runCatching { pm.mirror = mirror }
        if (result.isFailure) {
            LogBus.error("切换插件镜像失败：" + describe(result.exceptionOrNull()))
            toast(getString(R.string.set_save_failed))
            return
        }
        mirrorLabelRow?.setValue(mirrorDisplayText(mirror))
        syncMirrorBoxVisibility(mirror)
        toast(getString(R.string.set_plugins_mirror_saved, mirrorDisplayText(mirror)))
    }

    private fun syncMirrorBoxVisibility(mirror: Mirror) {
        val custom = mirror is Mirror.Custom
        mirrorCustomBox?.visibility = if (custom) View.VISIBLE else View.GONE
        if (!custom) {
            mirrorPreviewView?.visibility = View.GONE
            hideMirrorError()
        }
    }

    /** 保存自定义模板：校验占位符与 https，通过后才登记主机并切换。 */
    private fun saveCustomMirror(edit: EditText) {
        val template = edit.text?.toString().orEmpty().trim()
        val mirror = Mirror.Custom(template)
        val reason = mirror.invalidReason()
        if (reason != null) {
            showMirrorError(reason)
            return
        }
        applyMirror(mirror)
    }

    /** 预览：把拼出来的 index.json 完整地址显示出来，不联网、不改设置。 */
    private fun previewCustomMirror(edit: EditText) {
        val template = edit.text?.toString().orEmpty().trim()
        val mirror = Mirror.Custom(template)
        val reason = mirror.invalidReason()
        if (reason != null) {
            mirrorPreviewView?.visibility = View.GONE
            showMirrorError(reason)
            return
        }
        val source = currentRepo()
        val base = RepoSource.previewMirror(template, source.owner, source.repo, source.branch)
        if (base == null) {
            mirrorPreviewView?.visibility = View.GONE
            showMirrorError(getString(R.string.set_plugins_mirror_host_error))
            return
        }
        hideMirrorError()
        val view = mirrorPreviewView ?: return
        view.text = getString(R.string.set_plugins_mirror_preview_value, base + "/index.json")
        view.visibility = View.VISIBLE
    }

    private fun showMirrorError(text: String) {
        val view = mirrorErrorView ?: return
        view.text = text
        view.contentDescription = text
        view.visibility = View.VISIBLE
        LogBus.warn("自定义镜像地址不可用：" + text)
    }

    private fun hideMirrorError() {
        val view = mirrorErrorView ?: return
        view.visibility = View.GONE
    }

    // -----------------------------------------------------------------------
    // 5. // TIMING 时间校准
    // -----------------------------------------------------------------------

    private fun addTimingSection() {
        val block = newSection(
            getString(R.string.set_eyebrow_timing),
            getString(R.string.set_section_timing)
        )

        val offsetRow = EfFieldRow(
            this,
            getString(R.string.set_timing_offset_label),
            offsetText()
        )
        block.addView(offsetRow, matchWrap(Ef.dp(this, 10)))
        timingOffsetRow = offsetRow

        val calibratedRow = EfFieldRow(
            this,
            getString(R.string.set_timing_calibrated_label),
            calibratedText()
        )
        block.addView(calibratedRow, matchWrap(Ef.dp(this, 6)))
        timingCalibratedRow = calibratedRow

        addBody(getString(R.string.set_timing_note))

        block.addView(
            actionButton(getString(R.string.set_timing_calibrate), primary = false) { calibrate() },
            matchWrap(Ef.dp(this, 10))
        )
    }

    private fun offsetText(): String {
        val offset = runCatching { TimeService.offsetMs(this) }.getOrDefault(0L)
        return getString(R.string.set_timing_offset_value, offset.toString())
    }

    private fun calibratedText(): String {
        val at = runCatching { TimeService.calibratedAt(this) }.getOrDefault(0L)
        if (at <= 0L) {
            return getString(R.string.set_timing_never)
        }
        return runCatching {
            STAMP_FORMAT.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()))
        }.getOrDefault(getString(R.string.set_timing_never))
    }

    private fun refreshTimingRows() {
        timingOffsetRow?.setValue(offsetText())
        timingCalibratedRow?.setValue(calibratedText())
    }

    /** 校准走 HTTP 的 Date 头，网络不通就失败；界面只把结果说成人话。 */
    private fun calibrate() {
        if (calibrating) {
            return
        }
        calibrating = true
        toast(getString(R.string.set_timing_calibrating))
        scope.launch {
            val result = runCatching { TimeService.calibrate(this@SettingsActivity) }.getOrNull()
            calibrating = false
            if (isFinishing || isDestroyed) {
                return@launch
            }
            val offset = result?.getOrNull()
            if (offset != null) {
                refreshTimingRows()
                toast(getString(R.string.set_timing_calibrated_ok, offset.toString()))
            } else {
                toast(getString(R.string.set_timing_calibrated_failed))
            }
        }
    }

    // -----------------------------------------------------------------------
    // 6. // LOGS 运行日志
    // -----------------------------------------------------------------------

    private fun addLogSection() {
        val block = newSection(
            getString(R.string.set_eyebrow_logs),
            getString(R.string.set_section_logs)
        )

        val countRow = EfFieldRow(
            this,
            getString(R.string.set_logs_count_label),
            logsCountText()
        )
        block.addView(countRow, matchWrap(Ef.dp(this, 10)))
        logsCountRow = countRow

        addBody(logDirPath())

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            actionButton(
                getString(R.string.set_logs_clear),
                primary = false,
                danger = true
            ) { clearLogs() },
            buttonParams(0, weight = 1f)
        )
        row.addView(
            actionButton(getString(R.string.set_logs_dir_action), primary = false) {
                showLogDirDialog()
            },
            buttonParams(8, weight = 1f)
        )
        block.addView(row, matchWrap(Ef.dp(this, 10)))
    }

    private fun logsCountText(): String = runCatching {
        // 内存缓冲上限 2000 条，取全量才有意义
        val count = LogBus.snapshot(limit = Int.MAX_VALUE).size
        getString(R.string.set_logs_count_value, count)
    }.getOrDefault(getString(R.string.set_logs_count_failed))

    private fun logDirPath(): String =
        runCatching { LogBus.logDir(this).absolutePath }.getOrDefault("")

    private fun clearLogs() {
        val result = runCatching { LogBus.clear() }
        if (result.isFailure) {
            LogBus.warn("清空内存日志失败：" + describe(result.exceptionOrNull()))
            toast(getString(R.string.set_logs_clear_failed))
            return
        }
        logsCountRow?.setValue(logsCountText())
        toast(getString(R.string.set_logs_clear_done))
    }

    private fun showLogDirDialog() {
        runCatching {
            AlertDialog.Builder(this)
                .setTitle(R.string.set_logs_dir_dialog_title)
                .setMessage(getString(R.string.set_logs_dir_dialog_body, logDirPath()))
                .setPositiveButton(R.string.set_common_ok, null)
                .show()
        }.onFailure {
            LogBus.warn("打不开日志目录说明：" + describe(it))
            toast(getString(R.string.set_action_failed))
        }
    }

    // -----------------------------------------------------------------------
    // 版本行
    // -----------------------------------------------------------------------

    private fun addVersionLine() {
        currentSection = null
        val line = Ef.field(
            this,
            getString(R.string.set_version_line, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        )
        line.setTextColor(Ef.color(this, R.color.ef_ink2))
        container.addView(line, matchWrap(Ef.dp(this, 24)))
    }

    // -----------------------------------------------------------------------
    // 分节与小部件
    // -----------------------------------------------------------------------

    /** 一个分节的头部：眉标（// XXX）+ 中文标题 + 细线；返回的容器接着装字段。 */
    private fun newSection(eyebrow: String, title: String): LinearLayout {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = if (container.childCount == 0) {
                    Ef.dp(this@SettingsActivity, 4)
                } else {
                    Ef.dp(this@SettingsActivity, 24)
                }
            }
        }
        block.addView(Ef.eyebrow(this, eyebrow))
        block.addView(Ef.heading(this, title, 17f), matchWrap(Ef.dp(this, 3)))
        val rule = View(this).apply {
            setBackgroundColor(Ef.color(this@SettingsActivity, R.color.ef_rule))
        }
        block.addView(
            rule,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Ef.dp(this, 1)
            ).apply { topMargin = Ef.dp(this@SettingsActivity, 8) }
        )
        container.addView(block)
        currentSection = block
        return block
    }

    /** 字段标签：13sp 墨字，底下留 7dp 给控件。 */
    private fun labelView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Ef.color(this@SettingsActivity, R.color.ef_ink))
        includeFontPadding = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = Ef.dp(this@SettingsActivity, 7) }
    }

    /** 说明文字：13sp 灰字，允许折行。 */
    private fun addBody(text: String) {
        val host = currentSection ?: container
        val view = TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT
            setTextColor(Ef.color(this@SettingsActivity, R.color.ef_ink2))
            setLineSpacing(Ef.dp(this@SettingsActivity, 4).toFloat(), 1f)
        }
        host.addView(view, matchWrap(Ef.dp(this, 8)))
    }

    /** 输入框：直角、板色填充、1px 细线，与插件编辑器里的输入框同一套做法。 */
    private fun inputEdit(initial: String, hint: String): EditText = EditText(this).apply {
        setText(initial)
        this.hint = hint
        textSize = 14f
        setTextColor(Ef.color(this@SettingsActivity, R.color.ef_ink))
        setHintTextColor(Ef.color(this@SettingsActivity, R.color.ef_ink2))
        background = Ef.rectBackground(this@SettingsActivity, R.color.ef_panel, R.color.ef_rule)
        setPadding(
            Ef.dp(this@SettingsActivity, 10),
            Ef.dp(this@SettingsActivity, 10),
            Ef.dp(this@SettingsActivity, 10),
            Ef.dp(this@SettingsActivity, 10)
        )
        minimumHeight = Ef.dp(this@SettingsActivity, 48)
        includeFontPadding = false
        maxLines = 1
        inputType = InputType.TYPE_CLASS_TEXT
    }

    /** 文字动作（显示/隐藏一类）：透明底、切角、触控区 48dp。 */
    private fun textAction(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        isSingleLine = true
        includeFontPadding = false
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minimumHeight = Ef.dp(this@SettingsActivity, 48)
        setTextColor(Ef.color(this@SettingsActivity, R.color.ef_ink))
        background = Ef.cutBackground(this@SettingsActivity, R.color.ef_chip, null, cutDp = 6f)
        setPadding(
            Ef.dp(this@SettingsActivity, 10),
            Ef.dp(this@SettingsActivity, 6),
            Ef.dp(this@SettingsActivity, 14),
            Ef.dp(this@SettingsActivity, 6)
        )
        Ef.applyWide(this, weight = 700, width = 118)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = Ef.dp(this@SettingsActivity, 8) }
    }

    /** 开关：墨底反白 = 开，白底细线 = 关（与插件编辑器里的开关一致）。 */
    private fun addSwitchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        val host = currentSection ?: container
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = matchWrap(Ef.dp(this@SettingsActivity, 16))
        }
        block.addView(labelView(label))

        val button = TextView(this).apply {
            textSize = 13f
            isSingleLine = true
            gravity = Gravity.CENTER
            includeFontPadding = false
            minimumHeight = Ef.dp(this@SettingsActivity, 48)
            setPadding(
                Ef.dp(this@SettingsActivity, 14),
                Ef.dp(this@SettingsActivity, 10),
                Ef.dp(this@SettingsActivity, 14),
                Ef.dp(this@SettingsActivity, 10)
            )
            isClickable = true
            isFocusable = true
            Ef.applyWide(this, weight = 600, width = 118)
        }
        var on = initial
        fun applyState() {
            button.background = if (on) {
                Ef.cutBackground(this, R.color.ef_ink, null)
            } else {
                Ef.cutBackground(this, R.color.ef_panel, R.color.ef_rule)
            }
            button.setTextColor(Ef.color(this, if (on) R.color.ef_paper else R.color.ef_ink))
            button.text = getString(if (on) R.string.set_switch_on else R.string.set_switch_off)
            button.contentDescription = button.text
        }
        applyState()
        button.setOnClickListener {
            val result = runCatching {
                on = !on
                applyState()
                onChange(on)
            }
            if (result.isFailure) {
                LogBus.error("切换「" + label + "」失败：" + describe(result.exceptionOrNull()))
                toast(getString(R.string.set_save_failed))
            }
        }
        block.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        host.addView(block)
    }

    /** 切角按钮：主要动作用墨底（primary），破坏性动作用红字（danger）。 */
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
        button.setOnClickListener {
            runCatching { onClick() }.onFailure {
                LogBus.error("设置页动作失败：" + describe(it))
                toast(getString(R.string.set_action_failed))
            }
        }
        return button
    }

    private fun buttonParams(marginStartDp: Int, weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
            marginStart = Ef.dp(this@SettingsActivity, marginStartDp)
        }

    private fun matchWrap(topMarginPx: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = topMarginPx }

    /**
     * 输入框改动立刻回调（落盘），用户每敲一个字就写一次 —— 设置项没有"保存"总按钮，
     * 中断了也不会丢。读输入框本身出错的概率极低，仍然兜一层。
     */
    private fun EditText.watchText(onChange: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) =
                Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                runCatching { onChange(s?.toString().orEmpty()) }
                    .onFailure { LogBus.warn("保存设置项失败：" + describe(it)) }
            }
        })
    }

    private fun toast(text: String) {
        if (isFinishing || isDestroyed) {
            return
        }
        runCatching { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    private fun describe(t: Throwable?): String {
        if (t == null) {
            return "未知原因"
        }
        return t.message ?: t.javaClass.simpleName
    }

    private companion object {

        /** 服务起停与授权回调都是异步的，刷新状态前等一小会儿。 */
        const val DELAYED_REFRESH_MS = 600L

        /** 状态行只显示令牌尾号，够核对是哪一把令牌，又不会把整串钥匙印在屏幕上。 */
        const val TOKEN_TAIL_LENGTH = 4

        /** 镜像分段选择的三段下标，与 addMirrorRows 里的选项顺序一一对应。 */
        const val MIRROR_INDEX_DIRECT = 0
        const val MIRROR_INDEX_JSDELIVR = 1
        const val MIRROR_INDEX_CUSTOM = 2

        val REVEALED_INPUT_TYPE: Int =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

        val STAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}