package com.example.autotapper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.slider.Slider
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var tvVersion: TextView
    private lateinit var tvHeroFields: TextView

    private lateinit var tvOverlayState: TextView
    private lateinit var tvAccessibilityState: TextView
    private lateinit var tvShizukuState: TextView

    private lateinit var btnModeAuto: TextView
    private lateinit var btnModeShizuku: TextView
    private lateinit var tvTapModeHint: TextView

    private lateinit var rvPoints: RecyclerView
    private lateinit var tvPointsEmpty: TextView
    private lateinit var tvPointsCount: TextView
    private lateinit var tvConfigSummary: TextView

    private lateinit var etIntervalMs: EditText
    private lateinit var etRandomExtraMs: EditText
    private lateinit var etRepeatCount: EditText
    private lateinit var etTapDurationMs: EditText
    private lateinit var sliderControllerAlpha: Slider
    private lateinit var tvControllerAlphaValue: TextView

    private var pointReceiverRegistered = false
    private var isCheckingUpdate = false
    private var points: List<TapPoint> = emptyList()
    private lateinit var pointsAdapter: PointListAdapter

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_CODE_SHIZUKU) {
            if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                setTapMode(AutoTapperConfig.TAP_MODE_SHIZUKU)
                Toast.makeText(this, R.string.shizuku_permission_granted, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.shizuku_permission_missing, Toast.LENGTH_SHORT).show()
            }
            updateStatus()
        }
    }

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { updateStatus() }
    }

    private val shizukuDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread { updateStatus() }
    }

    private val pointReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AutoTapperConfig.ACTION_POINT_SELECTED -> loadAndShowPoints()
                AutoTapperConfig.ACTION_CONTROLLER_ALPHA_CHANGED -> refreshControllerAlpha()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 断点在资源里（sw600dp / w840dp）已经决定了尺寸与字号，结构切换（单列 / 两栏）在代码里做。
        // 这里不声明 android:configChanges：旋转 / 分屏改窗口时系统会重建 Activity，onCreate 会再走一遍，
        // 比“自己重写 onConfigurationChanged”少一份需要维护的状态（也更不用碰 manifest）。
        applyResponsiveLayout()

        tvVersion = findViewById(R.id.tv_version)
        tvHeroFields = findViewById(R.id.tv_hero_fields)

        tvOverlayState = findViewById(R.id.tv_overlay_state)
        tvAccessibilityState = findViewById(R.id.tv_accessibility_state)
        tvShizukuState = findViewById(R.id.tv_shizuku_state)

        btnModeAuto = findViewById(R.id.btn_mode_auto)
        btnModeShizuku = findViewById(R.id.btn_mode_shizuku)
        tvTapModeHint = findViewById(R.id.tv_tap_mode_hint)

        rvPoints = findViewById(R.id.rv_points)
        tvPointsEmpty = findViewById(R.id.tv_points_empty)
        tvPointsCount = findViewById(R.id.tv_points_count)
        tvConfigSummary = findViewById(R.id.tv_config_summary)

        etIntervalMs = findViewById(R.id.et_interval_ms)
        etRandomExtraMs = findViewById(R.id.et_random_extra_ms)
        etRepeatCount = findViewById(R.id.et_repeat_count)
        etTapDurationMs = findViewById(R.id.et_tap_duration_ms)
        sliderControllerAlpha = findViewById(R.id.slider_controller_alpha)
        tvControllerAlphaValue = findViewById(R.id.tv_controller_alpha_value)

        pointsAdapter = PointListAdapter { index -> deletePoint(index) }
        rvPoints.layoutManager = LinearLayoutManager(this)
        rvPoints.adapter = pointsAdapter
        rvPoints.itemAnimator = DefaultItemAnimator()

        findViewById<View>(R.id.btn_grant_overlay).setOnClickListener {
            PermissionUtils.requestOverlayPermission(this)
        }
        findViewById<View>(R.id.btn_grant_accessibility).setOnClickListener {
            PermissionUtils.requestAccessibilityPermission(this)
        }
        findViewById<View>(R.id.btn_grant_shizuku).setOnClickListener {
            requestShizukuPermission()
        }
        findViewById<View>(R.id.btn_open_controller).setOnClickListener {
            openFloatingController()
        }
        findViewById<View>(R.id.btn_check_update).setOnClickListener {
            startUpdateCheck(silent = false)
        }

        btnModeAuto.setOnClickListener {
            setTapMode(AutoTapperConfig.TAP_MODE_ACCESSIBILITY)
        }
        btnModeShizuku.setOnClickListener {
            requestShizukuPermission()
        }

        sliderControllerAlpha.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                AutoTapperConfig.saveControllerAlpha(this, value)
                tvControllerAlphaValue.text = getString(R.string.controller_alpha_value, value * 100)
                sendBroadcast(
                    Intent(AutoTapperConfig.ACTION_CONTROLLER_ALPHA_CHANGED)
                        .setPackage(packageName)
                )
            }
        }

        tvVersion.text = getString(R.string.version_label, BuildConfig.VERSION_NAME)

        // 主界面没有单独的「保存」按钮，输入合法即落盘，开始点击时由服务读取
        etIntervalMs.doAfterTextChanged {
            it?.toString()?.toLongOrNull()?.let { value -> AutoTapperConfig.saveIntervalMs(this, value) }
            updateConfigSummary()
        }
        etRandomExtraMs.doAfterTextChanged {
            it?.toString()?.toLongOrNull()?.let { value ->
                AutoTapperConfig.saveRandomExtraMs(this, value)
            }
            updateConfigSummary()
        }
        etRepeatCount.doAfterTextChanged {
            it?.toString()?.toIntOrNull()?.let { value ->
                AutoTapperConfig.saveRepeatCount(this, value)
            }
            updateConfigSummary()
        }
        etTapDurationMs.doAfterTextChanged {
            it?.toString()?.toLongOrNull()?.let { value ->
                AutoTapperConfig.saveTapDurationMs(this, value)
            }
            updateConfigSummary()
        }

        loadSavedConfig()
        updateStatus()
        startUpdateCheck(silent = true)
    }

    override fun onStart() {
        super.onStart()
        registerPointReceiver()
        ShizukuManager.addPermissionResultListener(shizukuPermissionListener)
        ShizukuManager.addBinderListener(shizukuBinderListener)
        runCatching { Shizuku.addBinderDeadListener(shizukuDeadListener) }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    override fun onStop() {
        super.onStop()
        unregisterPointReceiver()
        ShizukuManager.removePermissionResultListener(shizukuPermissionListener)
        ShizukuManager.removeBinderListener(shizukuBinderListener)
        runCatching { Shizuku.removeBinderDeadListener(shizukuDeadListener) }
    }

    private fun loadSavedConfig() {
        etIntervalMs.setText(AutoTapperConfig.getIntervalMs(this).toString())
        etRandomExtraMs.setText(AutoTapperConfig.getRandomExtraMs(this).toString())
        etRepeatCount.setText(AutoTapperConfig.getRepeatCount(this).toString())
        etTapDurationMs.setText(AutoTapperConfig.getTapDurationMs(this).toString())

        val alpha = AutoTapperConfig.getControllerAlpha(this)
        sliderControllerAlpha.value = alpha
        tvControllerAlphaValue.text = getString(R.string.controller_alpha_value, alpha * 100)
    }

    private fun refreshControllerAlpha() {
        val alpha = AutoTapperConfig.getControllerAlpha(this)
        sliderControllerAlpha.value = alpha
        tvControllerAlphaValue.text = getString(R.string.controller_alpha_value, alpha * 100)
    }

    private fun updateStatus() {
        val overlayGranted = PermissionUtils.isOverlayPermissionGranted(this)
        val accessibilityGranted = PermissionUtils.isAccessibilityServiceEnabled(
            this,
            AutoClickService::class.java
        )
        val shizukuState = ShizukuManager.state(this)

        // 行内已经有权限名，状态块里只写状态本身
        bindStateBlock(
            tvOverlayState,
            getString(
                if (overlayGranted) R.string.permission_granted else R.string.permission_missing
            ),
            overlayGranted
        )
        bindStateBlock(
            tvAccessibilityState,
            getString(
                if (accessibilityGranted) R.string.permission_granted
                else R.string.permission_missing
            ),
            accessibilityGranted
        )

        val shizukuText = when (shizukuState) {
            ShizukuManager.State.READY -> getString(R.string.shizuku_permission_granted)
            ShizukuManager.State.NO_PERMISSION -> getString(R.string.shizuku_permission_missing)
            ShizukuManager.State.NOT_RUNNING -> getString(R.string.shizuku_state_not_running)
            ShizukuManager.State.NOT_INSTALLED -> getString(R.string.shizuku_state_not_installed)
        }
        bindStateBlock(
            tvShizukuState,
            shizukuText,
            shizukuState == ShizukuManager.State.READY
        )

        val grantedCount = listOf(overlayGranted, accessibilityGranted, shizukuState == ShizukuManager.State.READY)
            .count { it }
        tvHeroFields.text = getString(
            R.string.hero_fields_template,
            BuildConfig.VERSION_NAME,
            points.size,
            grantedCount
        )

        loadAndShowPoints()
        refreshTapMode()
    }

    private fun bindStateBlock(view: TextView, text: String, ok: Boolean) {
        view.text = text
        view.background = if (ok) {
            Ef.rectBackground(this, R.color.ef_panel, R.color.ef_ink)
        } else {
            Ef.rectBackground(this, R.color.ef_signal)
        }
        view.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.ef_ink else R.color.ef_on_signal)
        )
    }

    private fun refreshTapMode() {
        val mode = AutoTapperConfig.getTapMode(this)
        val shizuku = mode == AutoTapperConfig.TAP_MODE_SHIZUKU
        btnModeAuto.isSelected = !shizuku
        btnModeShizuku.isSelected = shizuku
        tvTapModeHint.setText(
            if (shizuku) R.string.tap_mode_shizuku_hint else R.string.tap_mode_auto_hint
        )
    }

    private fun setTapMode(mode: String) {
        val previous = AutoTapperConfig.getTapMode(this)
        AutoTapperConfig.saveTapMode(this, mode)
        if (previous != mode) {
            TapScheduler.stop(this)
        }
        refreshTapMode()
        Toast.makeText(
            this,
            if (mode == AutoTapperConfig.TAP_MODE_SHIZUKU) R.string.tap_mode_switched_shizuku
            else R.string.tap_mode_switched_auto,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun requestShizukuPermission() {
        when (ShizukuManager.state(this)) {
            ShizukuManager.State.READY -> {
                setTapMode(AutoTapperConfig.TAP_MODE_SHIZUKU)
            }

            ShizukuManager.State.NO_PERMISSION -> {
                ShizukuManager.requestPermission(REQUEST_CODE_SHIZUKU)
            }

            ShizukuManager.State.NOT_RUNNING -> {
                Toast.makeText(this, R.string.shizuku_not_running, Toast.LENGTH_SHORT).show()
                openShizukuApp()
            }

            ShizukuManager.State.NOT_INSTALLED -> {
                Toast.makeText(this, R.string.shizuku_unavailable, Toast.LENGTH_SHORT).show()
                openShizukuDownloadPage()
            }
        }
    }

    private fun openShizukuDownloadPage() {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))
            )
        }
    }

    private fun openShizukuApp() {
        val intent = packageManager.getLaunchIntentForPackage(ShizukuManager.SHIZUKU_PACKAGE)
        if (intent != null) {
            runCatching { startActivity(intent) }
        }
        updateStatus()
    }

    private fun loadAndShowPoints() {
        points = AutoTapperConfig.loadPoints(this)
        pointsAdapter.submitPoints(points)
        val empty = points.isEmpty()
        tvPointsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        rvPoints.visibility = if (empty) View.GONE else View.VISIBLE
        tvPointsCount.text = getString(R.string.points_count_badge, points.size)
        updateConfigSummary()
    }

    private fun deletePoint(index: Int) {
        val current = AutoTapperConfig.loadPoints(this)
        if (index !in current.indices) {
            return
        }
        AutoTapperConfig.removePointAt(this, index)
        // 通知悬浮控制器同步点位数与徽标（控制器删点也会发这条广播）
        sendBroadcast(
            Intent(AutoTapperConfig.ACTION_POINT_SELECTED).setPackage(packageName)
        )
        points = AutoTapperConfig.loadPoints(this)
        pointsAdapter.removeItem(index)
        tvPointsEmpty.visibility = if (points.isEmpty()) View.VISIBLE else View.GONE
        rvPoints.visibility = if (points.isEmpty()) View.GONE else View.VISIBLE
        tvPointsCount.text = getString(R.string.points_count_badge, points.size)
        updateConfigSummary()
        tvHeroFields.text = getString(
            R.string.hero_fields_template,
            BuildConfig.VERSION_NAME,
            points.size,
            listOf(
                PermissionUtils.isOverlayPermissionGranted(this),
                PermissionUtils.isAccessibilityServiceEnabled(this, AutoClickService::class.java),
                ShizukuManager.state(this) == ShizukuManager.State.READY
            ).count { it }
        )
        Toast.makeText(this, getString(R.string.point_deleted_toast, index + 1), Toast.LENGTH_SHORT)
            .show()
    }

    private fun openFloatingController() {
        if (!PermissionUtils.isOverlayPermissionGranted(this)) {
            Toast.makeText(this, R.string.overlay_permission_required, Toast.LENGTH_SHORT).show()
            PermissionUtils.requestOverlayPermission(this)
            return
        }

        val hasPoints = AutoTapperConfig.hasPoints(this)
        val intent = Intent(this, OverlayService::class.java).apply {
            action = AutoTapperConfig.ACTION_SHOW_CONTROLLER
            putExtra(AutoTapperConfig.EXTRA_START_PICKING, !hasPoints)
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(
            this,
            if (hasPoints) R.string.overlay_started else R.string.overlay_started_pick,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun startUpdateCheck(silent: Boolean) {
        if (isCheckingUpdate) {
            return
        }
        isCheckingUpdate = true
        if (!silent) {
            Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        }
        UpdateChecker.checkForUpdate { info ->
            runOnUiThread {
                isCheckingUpdate = false
                handleUpdateResult(info, silent)
            }
        }
    }

    private fun handleUpdateResult(info: UpdateChecker.UpdateInfo?, silent: Boolean) {
        if (info == null) {
            if (!silent) {
                Toast.makeText(this, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (!UpdateChecker.isNewer(info.latestVersion, BuildConfig.VERSION_NAME)) {
            if (!silent) {
                Toast.makeText(this, R.string.update_latest, Toast.LENGTH_SHORT).show()
            }
            return
        }

        val prefs = AutoTapperConfig.prefs(this)
        if (prefs.getString(AutoTapperConfig.KEY_UPDATE_IGNORED_VERSION, null) ==
            info.latestVersion
        ) {
            return
        }

        val alreadyNotified = prefs.getString(AutoTapperConfig.KEY_UPDATE_NOTIFIED_VERSION, null) ==
            info.latestVersion
        if (silent && alreadyNotified) {
            return
        }

        prefs.edit().putString(AutoTapperConfig.KEY_UPDATE_NOTIFIED_VERSION, info.latestVersion)
            .apply()
        UpdateNotifier.notify(this, info)
        if (!isFinishing && !isDestroyed) {
            showUpdateDialog(info)
        }
    }

    private fun showUpdateDialog(info: UpdateChecker.UpdateInfo) {
        val message = buildString {
            append(
                getString(
                    R.string.update_dialog_message,
                    BuildConfig.VERSION_NAME,
                    info.latestVersion
                )
            )
            val notes = info.notes?.lineSequence()
                ?.filter { it.isNotBlank() }
                ?.joinToString("\n")
                ?.take(400)
            if (!notes.isNullOrBlank()) {
                append("\n\n").append(getString(R.string.update_dialog_notes_title))
                    .append("\n").append(notes)
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.update_dialog_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_download) { _, _ ->
                openUrl(info.downloadUrl)
            }
            .setNeutralButton(R.string.update_ignore) { _, _ ->
                AutoTapperConfig.prefs(this).edit()
                    .putString(AutoTapperConfig.KEY_UPDATE_IGNORED_VERSION, info.latestVersion)
                    .apply()
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun openUrl(url: String) {
        if (!UpdateChecker.isSafeServerUrl(url)) {
            Toast.makeText(this, R.string.update_open_failed, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.update_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateConfigSummary() {
        val intervalInput = etIntervalMs.text.toString().toLongOrNull()
        val randomExtraInput = etRandomExtraMs.text.toString().toLongOrNull()
        val repeatCountInput = etRepeatCount.text.toString().toIntOrNull()
        val tapDurationInput = etTapDurationMs.text.toString().toLongOrNull()

        if (intervalInput == null || randomExtraInput == null ||
            repeatCountInput == null || tapDurationInput == null
        ) {
            tvConfigSummary.text = getString(R.string.config_summary_invalid)
            return
        }

        val intervalMs = intervalInput.coerceAtLeast(AutoTapperConfig.MIN_INTERVAL_MS)
        val randomExtraMs = randomExtraInput.coerceAtLeast(0L)
        val repeatCount = repeatCountInput.coerceAtLeast(0)
        val tapDurationMs = tapDurationInput.coerceIn(
            AutoTapperConfig.MIN_TAP_DURATION_MS,
            AutoTapperConfig.MAX_TAP_DURATION_MS
        )

        val repeatSummary = if (repeatCount == 0) {
            getString(R.string.config_summary_repeat_infinite)
        } else {
            getString(R.string.config_summary_repeat_finite, repeatCount)
        }

        val summaryText = getString(
            R.string.config_summary_template,
            points.size,
            intervalMs,
            intervalMs + randomExtraMs,
            tapDurationMs,
            repeatSummary
        )

        val notes = buildList {
            if (intervalInput < AutoTapperConfig.MIN_INTERVAL_MS) {
                add(getString(R.string.interval_adjusted_note))
            }
            if (tapDurationInput < AutoTapperConfig.MIN_TAP_DURATION_MS ||
                tapDurationInput > AutoTapperConfig.MAX_TAP_DURATION_MS
            ) {
                add(getString(R.string.tap_duration_adjusted_note))
            }
        }

        tvConfigSummary.text = if (notes.isEmpty()) {
            summaryText
        } else {
            summaryText + "\n" + notes.joinToString("\n")
        }
    }

    private fun registerPointReceiver() {
        if (pointReceiverRegistered) {
            return
        }

        val filter = IntentFilter().apply {
            addAction(AutoTapperConfig.ACTION_POINT_SELECTED)
            addAction(AutoTapperConfig.ACTION_CONTROLLER_ALPHA_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pointReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(pointReceiver, filter)
        }
        pointReceiverRegistered = true
    }

    private fun unregisterPointReceiver() {
        if (!pointReceiverRegistered) {
            return
        }

        runCatching { unregisterReceiver(pointReceiver) }
        pointReceiverRegistered = false
    }

    /**
     * 响应式布局（断点用 Android 官方窗口尺寸：600dp / 840dp，尺寸与字号由资源限定符给出）：
     *  - 可用宽度 < 840dp → 单列，渲染顺序 = 左栏全部 + 右栏全部，与改造前的单列顺序逐段一致：
     *      手机档（sw < 600dp）content_max_width = 10000dp，等于不限制宽度，满宽单列；
     *      平板竖屏档（sw >= 600dp）content_max_width = 640dp，单列加宽并居中。
     *  - 可用宽度 >= 840dp → 左右两栏并排，各占一半（平板横屏、大屏）：
     *      左栏 = 说明 hero + 权限状态 + 点击方式；右栏 = 点击点位 + 点击配置 + 当前配置 + 使用说明。
     * 只在数值真的发生变化时才改 layoutParams，避免配置变化时无谓地触发 requestLayout。
     */
    private fun applyResponsiveLayout() {
        val contentRoot = findViewById<LinearLayout>(R.id.content_root) ?: return
        val contentSplit = findViewById<LinearLayout>(R.id.content_split) ?: return
        val contentLeft = findViewById<LinearLayout>(R.id.content_left) ?: return
        val contentRight = findViewById<LinearLayout>(R.id.content_right) ?: return
        val pointsTopRule = findViewById<View>(R.id.rule_points_top)

        val widthDp = resources.configuration.screenWidthDp
        val twoColumn = widthDp >= WINDOW_WIDTH_TWO_COLUMN_DP

        val targetOrientation =
            if (twoColumn) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (contentSplit.orientation != targetOrientation) {
            contentSplit.orientation = targetOrientation
        }

        // 两栏时左右各占一半（width=0 + weight=1）；单列时回到 match_parent + weight=0
        val columnWeight = if (twoColumn) 1f else 0f
        applyColumnParams(contentLeft, columnWeight)
        applyColumnParams(contentRight, columnWeight)

        // 两栏间距只加在右栏的 paddingStart 上；单列时归零（改造前两栏容器都没有内边距）
        val columnGapPx = if (twoColumn) resources.getDimensionPixelSize(R.dimen.column_gap) else 0
        if (contentRight.paddingStart != columnGapPx) {
            contentRight.setPaddingRelative(
                columnGapPx,
                contentRight.paddingTop,
                contentRight.paddingEnd,
                contentRight.paddingBottom
            )
        }

        // 右栏第一段的 1px 细线：单列时它是“点击方式 / 点击点位”之间的分区线，必须保留；
        // 两栏时它成了右栏第一个元素（会把右栏整体压低一个 section_gap），置 GONE。
        if (pointsTopRule != null) {
            val ruleVisibility = if (twoColumn) View.GONE else View.VISIBLE
            if (pointsTopRule.visibility != ruleVisibility) {
                pointsTopRule.visibility = ruleVisibility
            }
        }

        // 内容最大宽度：可用宽 = 窗口宽 - 左右留白；上限比可用宽更窄时才收窄，否则保持 match_parent
        val gutterPx = resources.getDimensionPixelSize(R.dimen.page_gutter)
        val contentMaxPx = resources.getDimensionPixelSize(R.dimen.content_max_width)
        val density = resources.displayMetrics.density
        val availablePx = (widthDp * density).toInt() - 2 * gutterPx
        val targetRootWidth = if (contentMaxPx in 1 until availablePx) {
            contentMaxPx
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        val rootParams = contentRoot.layoutParams
        if (rootParams.width != targetRootWidth) {
            rootParams.width = targetRootWidth
            contentRoot.layoutParams = rootParams
        }
    }

    /** 两栏 / 单列的列参数；weight > 0 表示两栏并排（width=0 + weight），否则单列满宽。 */
    private fun applyColumnParams(column: LinearLayout, weight: Float) {
        val params = column.layoutParams as LinearLayout.LayoutParams
        val targetWidth =
            if (weight > 0f) 0 else LinearLayout.LayoutParams.MATCH_PARENT
        if (params.width == targetWidth && params.weight == weight) {
            return
        }
        params.width = targetWidth
        params.weight = weight
        column.layoutParams = params
    }

    companion object {
        private const val REQUEST_CODE_SHIZUKU = 4001

        /** Android 官方窗口宽度断点：>= 840dp 时主界面切成左右两栏。 */
        private const val WINDOW_WIDTH_TWO_COLUMN_DP = 840
    }
}
