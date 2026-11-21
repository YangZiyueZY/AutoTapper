package com.example.autotapper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlin.math.abs

/**
 * 悬浮控制器：一个常驻的收起态悬浮球 + 一个展开态控制面板。
 *
 * 关键约束（也是之前「快速收起/展开会多出几个控制器」的根因）：
 * 两个窗口在服务创建时各建一次，之后收起/展开只改 alpha、位移和触摸开关，
 * 绝不在这里 addView / removeView。旧实现每次切换都新建视图，并靠
 * ViewPropertyAnimator 的 withEndAction 去移除旧视图；动画被下一次切换取消时
 * 回调不会执行，旧视图就永远留在屏幕上，于是出现多个控制器。
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var bubbleView: FrameLayout? = null
    private var bubbleLabel: TextView? = null
    private var bubbleBar: View? = null

    private var panelView: LinearLayout? = null
    private var panelDragHandle: View? = null
    private var playButton: EfBlock? = null
    private var clearButton: EfBlock? = null
    private var stateValue: TextView? = null
    private var intervalValue: TextView? = null
    private var durationValue: TextView? = null
    private var repeatValue: TextView? = null
    private var pointsValue: TextView? = null

    private var pickerRoot: View? = null
    private var pickerCounterView: TextView? = null

    private val pointMarkers = mutableListOf<TextView>()
    private val markerBlinkers = mutableMapOf<View, Runnable>()

    private var isExpanded = false
    private var isClicking = false
    private var currentPointIndex = -1
    private var sessionStartCount = 0

    // 当前显示的那个悬浮组件左上角在屏幕上的位置
    private var anchorX = -1
    private var anchorY = -1

    private var controllerAlpha = 0.9f

    // 当前深/浅色模式，用于在系统切换主题时重建悬浮窗（窗口颜色在创建时解析）
    private var nightMode = false

    private var dragDownRawX = 0f
    private var dragDownRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragActive = false

    private val touchSlop: Int
        get() = ViewConfiguration.get(this).scaledTouchSlop

    private val dragTouchListener = object : View.OnTouchListener {
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragDownRawX = event.rawX
                    dragDownRawY = event.rawY
                    dragStartX = anchorX.coerceAtLeast(0)
                    dragStartY = anchorY.coerceAtLeast(0)
                    dragActive = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - dragDownRawX).toInt()
                    val dy = (event.rawY - dragDownRawY).toInt()
                    if (!dragActive && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragActive = true
                    }
                    if (dragActive) {
                        val maxX = (screenWidth - view.width).coerceAtLeast(0)
                        val maxY = (screenHeight - view.height).coerceAtLeast(0)
                        anchorX = (dragStartX + dx).coerceIn(0, maxX)
                        anchorY = (dragStartY + dy).coerceIn(0, maxY)
                        applyPosition(view)
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragActive) {
                        view.performClick()
                    }
                    return true
                }
            }
            return false
        }
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AutoTapperConfig.ACTION_CLICK_STATE_CHANGED -> {
                    isClicking = intent.getBooleanExtra(AutoTapperConfig.EXTRA_IS_CLICKING, false)
                    currentPointIndex =
                        intent.getIntExtra(AutoTapperConfig.EXTRA_CURRENT_POINT_INDEX, -1)
                    refreshControllerState()
                    refreshMarkers()
                    refreshNotification()
                }

                AutoTapperConfig.ACTION_POINT_SELECTED -> {
                    refreshControllerState()
                    refreshMarkers()
                }

                AutoTapperConfig.ACTION_CONTROLLER_ALPHA_CHANGED -> refreshControllerAlpha()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        controllerAlpha = AutoTapperConfig.getControllerAlpha(this)
        nightMode = isNightMode()
        registerServiceReceiver()
        startAsForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!PermissionUtils.isOverlayPermissionGranted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        ensureViews()

        when (intent?.action) {
            AutoTapperConfig.ACTION_STOP_CONTROLLER -> {
                closeController()
                return START_NOT_STICKY
            }

            AutoTapperConfig.ACTION_START_PICKING -> {
                setExpanded(true, animate = false)
                showPickerOverlay()
            }

            AutoTapperConfig.ACTION_SHOW_CONTROLLER -> {
                setExpanded(true, animate = false)
                if (intent.getBooleanExtra(AutoTapperConfig.EXTRA_START_PICKING, false)) {
                    showPickerOverlay()
                }
            }

            null -> setExpanded(false, animate = false)
        }

        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val night = (newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (night == nightMode || bubbleView == null) {
            return
        }
        nightMode = night
        rebuildController()
    }

    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** 深浅色切换后重建悬浮球与面板：颜色/背景在创建时解析，需要重新生成。 */
    private fun rebuildController() {
        val expanded = isExpanded
        val hadPicker = pickerRoot != null
        val startCount = sessionStartCount

        hideMarkers()
        if (hadPicker) {
            dismissPickerOverlay()
        }
        bubbleView?.let { removeViewSafely(it) }
        panelView?.let { removeViewSafely(it) }
        bubbleView = null
        bubbleLabel = null
        bubbleBar = null
        panelView = null
        playButton = null
        clearButton = null
        stateValue = null
        intervalValue = null
        durationValue = null
        repeatValue = null
        pointsValue = null
        isExpanded = false

        ensureViews()
        setExpanded(expanded, animate = false)
        if (hadPicker) {
            sessionStartCount = startCount
            showPickerOverlay()
        }
    }

    override fun onDestroy() {
        stopClickingIfNeeded()
        dismissPickerOverlay()
        hideMarkers()
        bubbleView?.let { removeViewSafely(it) }
        panelView?.let { removeViewSafely(it) }
        bubbleView = null
        panelView = null
        bubbleLabel = null
        bubbleBar = null
        panelDragHandle = null
        playButton = null
        clearButton = null
        stateValue = null
        intervalValue = null
        durationValue = null
        repeatValue = null
        pointsValue = null
        runCatching { unregisterReceiver(serviceReceiver) }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 视图搭建

    /** 只在这里建窗口，且只建一次。 */
    private fun ensureViews() {
        if (bubbleView == null) {
            bubbleView = buildBubble().also {
                windowManager.addView(it, floatParams(bubbleWidth(), bubbleHeight(), anchorX, anchorY))
            }
        }
        if (panelView == null) {
            panelView = buildPanel().also {
                windowManager.addView(
                    it,
                    floatParams(panelWidth(), WindowManager.LayoutParams.WRAP_CONTENT, anchorX, anchorY)
                )
                it.alpha = 0f
                setTouchable(it, false)
            }
        }

        if (anchorX < 0 || anchorY < 0) {
            anchorX = (screenWidth - bubbleWidth() - Ef.dp(this, 8)).coerceAtLeast(0)
            anchorY = ((screenHeight - bubbleHeight()) / 2).coerceAtLeast(0)
        }

        applyPosition(bubbleView!!)
        applyPosition(panelView!!)
        refreshControllerState()
        refreshControllerAlpha()
    }

    private fun buildBubble(): FrameLayout {
        val root = FrameLayout(this).apply {
            setPadding(0, 0, 0, 0)
            contentDescription = getString(R.string.overlay_expand_label)
            setOnClickListener { setExpanded(true) }
            setOnTouchListener(dragTouchListener)
        }

        val bar = View(this).apply {
            setBackgroundColor(Ef.color(this@OverlayService, R.color.ef_signal))
            visibility = View.GONE
        }
        root.addView(
            bar,
            FrameLayout.LayoutParams(Ef.dp(this, 3), FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START
            }
        )
        bubbleBar = bar

        val label = TextView(this).apply {
            text = getString(R.string.overlay_handle_idle)
            textSize = 14f
            letterSpacing = 0.14f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Ef.color(this@OverlayService, R.color.ef_on_signal))
            Ef.applyWide(this, weight = 800, width = 125)
        }
        bubbleLabel = label
        root.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        return root
    }

    private fun buildPanel(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ef.dp(this@OverlayService, 12), Ef.dp(this@OverlayService, 10),
                Ef.dp(this@OverlayService, 12), Ef.dp(this@OverlayService, 12))
            background = Ef.rectBackground(
                this@OverlayService,
                R.color.ef_panel,
                R.color.ef_ink
            )
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnTouchListener(dragTouchListener)
        }
        panelDragHandle = header

        // 收起：回到侧边悬浮球。可点的块切一个角，和 ADD / DEL / GO 保持同一种形状语言。
        val collapse = TextView(this).apply {
            text = getString(R.string.overlay_collapse_label)
            textSize = 12f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Ef.color(this@OverlayService, R.color.ef_ink))
            background = Ef.cutBackground(
                this@OverlayService,
                R.color.ef_chip,
                R.color.ef_rule,
                cutDp = 6f
            )
            setPadding(Ef.dp(this@OverlayService, 10), Ef.dp(this@OverlayService, 6),
                Ef.dp(this@OverlayService, 14), Ef.dp(this@OverlayService, 6))
            isClickable = true
            contentDescription = getString(R.string.overlay_collapse_label)
            setOnClickListener { setExpanded(false) }
        }
        header.addView(collapse)

        val eyebrow = Ef.eyebrow(this, getString(R.string.eyebrow_controller)).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = Ef.dp(this@OverlayService, 8)
            }
            textSize = 9f
            letterSpacing = 0.08f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(eyebrow)

        val title = Ef.heading(this, getString(R.string.overlay_controller_title), 16f).apply {
            setPadding(0, Ef.dp(this@OverlayService, 2), 0, Ef.dp(this@OverlayService, 8))
            setOnTouchListener(dragTouchListener)
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val add = EfBlock(this, getString(R.string.overlay_add_label)).apply {
            layoutParams = LinearLayout.LayoutParams(0, Ef.dp(this@OverlayService, 52), 1f)
            setOnClickListener { showPickerOverlay() }
        }
        val clear = EfBlock(this, getString(R.string.overlay_clear_label)).apply {
            layoutParams = LinearLayout.LayoutParams(0, Ef.dp(this@OverlayService, 52), 1f).apply {
                marginStart = Ef.dp(this@OverlayService, 6)
            }
            setOnClickListener { removeLastPoint() }
        }
        clearButton = clear

        val play = EfBlock(this, getString(R.string.overlay_play_label), primary = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, Ef.dp(this@OverlayService, 52), 1f).apply {
                marginStart = Ef.dp(this@OverlayService, 6)
            }
            setOnClickListener { toggleClicking() }
        }
        playButton = play

        buttons.addView(add)
        buttons.addView(clear)
        buttons.addView(play)

        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ef.dp(this@OverlayService, 12), 0, 0)
        }

        val ruleTop = Ef.matchSpacer(this)
        fields.addView(ruleTop)

        stateValue = addFieldRow(fields, getString(R.string.overlay_field_state))
        intervalValue = addFieldRow(fields, getString(R.string.overlay_field_interval))
        durationValue = addFieldRow(fields, getString(R.string.overlay_field_duration))
        repeatValue = addFieldRow(fields, getString(R.string.overlay_field_repeat))
        pointsValue = addFieldRow(fields, getString(R.string.overlay_field_points))

        // 关闭放在面板底部：收起后悬浮球正好落在面板右上角，那里不能放会「关掉控制器」的按钮
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ef.dp(this@OverlayService, 6), 0, 0)
        }
        val close = TextView(this).apply {
            text = getString(R.string.overlay_close_label)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Ef.color(this@OverlayService, R.color.ef_ink2))
            contentDescription = getString(R.string.overlay_close_button_desc)
            isClickable = true
            background = Ef.cutBackground(
                this@OverlayService,
                R.color.ef_chip,
                R.color.ef_rule,
                cutDp = 6f
            )
            setPadding(Ef.dp(this@OverlayService, 10), Ef.dp(this@OverlayService, 6),
                Ef.dp(this@OverlayService, 14), Ef.dp(this@OverlayService, 6))
            setOnClickListener { closeController() }
        }
        footer.addView(close)

        root.addView(header)
        root.addView(title)
        root.addView(buttons)
        root.addView(fields)
        root.addView(footer)
        return root
    }

    /** 一行「宽体小字标签 + 读数」，用细线分隔。 */
    private fun addFieldRow(parent: LinearLayout, labelText: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ef.dp(this@OverlayService, 7), 0, Ef.dp(this@OverlayService, 7))
        }

        val label = Ef.field(this, labelText).apply {
            letterSpacing = 0.14f
            isAllCaps = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val value = Ef.field(this, "—").apply {
            setTextColor(Ef.color(this@OverlayService, R.color.ef_ink))
            gravity = Gravity.END
            Ef.applyWide(this, weight = 700, width = 112)
        }

        row.addView(label)
        row.addView(value)
        parent.addView(row)
        parent.addView(Ef.matchSpacer(this))
        return value
    }

    // ---------------------------------------------------------------- 收起 / 展开

    /**
     * 切换到收起或展开态。两个窗口始终存在，这里只改可见性与触摸开关，
     * 重复调用（快速连点）只会重订动画目标，不会多出窗口。
     */
    private fun setExpanded(expand: Boolean, animate: Boolean = true) {
        val bubble = bubbleView ?: return
        val panel = panelView ?: return

        val alreadyThere = expand == isExpanded &&
            ((expand && panel.alpha > 0f) || (!expand && bubble.alpha > 0f))
        isExpanded = expand
        if (!expand) {
            dismissPickerOverlay()
        }
        if (alreadyThere) {
            // 已经在目标状态：只同步可见性，不重播入场，避免重复打开时的闪动
            setTouchable(panel, expand)
            setTouchable(bubble, !expand)
            panel.alpha = if (expand) controllerAlpha else 0f
            bubble.alpha = if (expand) 0f else controllerAlpha
            applyPosition(if (expand) panel else bubble)
            refreshControllerState()
            refreshMarkers()
            return
        }

        val entering = if (expand) panel else bubble
        val leaving = if (expand) bubble else panel

        // 先把进入方夹进屏幕，避免展开后跑到屏幕外
        val size = sizeOf(entering)
        if (!expand) {
            // 收起时贴回最近的屏幕侧边，悬浮球常驻侧边
            val margin = Ef.dp(this, 8)
            val centerX = anchorX + size.first / 2
            anchorX = if (centerX >= screenWidth / 2) {
                screenWidth - size.first - margin
            } else {
                margin
            }
        }
        anchorX = anchorX.coerceIn(0, (screenWidth - size.first).coerceAtLeast(0))
        anchorY = anchorY.coerceIn(0, (screenHeight - size.second).coerceAtLeast(0))
        applyPosition(entering)
        applyPosition(leaving)

        // 取消进行中的动画：ViewPropertyAnimator 被取消时不会执行 withEndAction，
        // 所以这里不依赖任何收尾回调来做清理，状态全部立即生效。
        entering.animate().cancel()
        leaving.animate().cancel()

        setTouchable(entering, true)
        setTouchable(leaving, false)

        if (!animate) {
            entering.alpha = controllerAlpha
            entering.translationX = 0f
            leaving.alpha = 0f
            leaving.translationX = 0f
        } else {
            leaving.animate()
                .alpha(0f)
                .translationX((-Ef.dp(this, 10)).toFloat())
                .setDuration(EXIT_MS)
                .setInterpolator(MECH)
                .start()

            entering.alpha = 0f
            entering.translationX = Ef.dp(this, 14).toFloat()
            entering.animate()
                .alpha(controllerAlpha)
                .translationX(0f)
                .setDuration(ENTER_MS)
                .setInterpolator(SNAP)
                .start()
        }

        refreshControllerState()
        refreshMarkers()
    }

    /** 悬浮球尺寸固定；面板宽度固定、高度要量一次才知道（窗口是 WRAP_CONTENT）。 */
    private fun sizeOf(view: View): Pair<Int, Int> {
        if (view !== panelView) {
            return Pair(bubbleWidth(), bubbleHeight())
        }
        view.measure(
            View.MeasureSpec.makeMeasureSpec(panelWidth(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return Pair(panelWidth(), view.measuredHeight.takeIf { it > 0 } ?: Ef.dp(this, 220))
    }

    private fun applyPosition(view: View) {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = anchorX.coerceAtLeast(0)
        params.y = anchorY.coerceAtLeast(0)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun setTouchable(view: View, touchable: Boolean) {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val current = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0
        if (current == !touchable) {
            return
        }
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    // ---------------------------------------------------------------- 状态刷新

    private fun refreshControllerState() {
        val hasPoint = AutoTapperConfig.hasPoints(this)

        bubbleView?.apply {
            background = Ef.cutBackground(
                this@OverlayService,
                if (isClicking) R.color.ef_ink else R.color.ef_signal,
                if (isClicking) R.color.ef_ink else R.color.ef_signal,
                cutDp = 12f,
                strokeWidthDp = 1f
            )
            contentDescription = getString(
                if (isExpanded) R.string.overlay_collapse_label else R.string.overlay_expand_label
            )
        }
        bubbleLabel?.apply {
            text = getString(
                if (isClicking) R.string.overlay_handle_running else R.string.overlay_handle_idle
            )
            setTextColor(
                Ef.color(
                    this@OverlayService,
                    if (isClicking) R.color.ef_panel else R.color.ef_on_signal
                )
            )
        }
        bubbleBar?.visibility = if (isClicking) View.VISIBLE else View.GONE

        playButton?.apply {
            setLabel(
                getString(
                    if (isClicking) R.string.overlay_stop_label else R.string.overlay_play_label
                )
            )
            alpha = 1f
        }
        clearButton?.alpha = if (hasPoint) 1f else 0.5f

        val intervalMs = AutoTapperConfig.getIntervalMs(this)
        val randomExtraMs = AutoTapperConfig.getRandomExtraMs(this)
        val repeatCount = AutoTapperConfig.getRepeatCount(this)
        val tapDurationMs = AutoTapperConfig.getTapDurationMs(this)
        val points = AutoTapperConfig.loadPoints(this)

        stateValue?.text = getString(
            if (isClicking) R.string.overlay_state_running else R.string.overlay_state_idle
        )
        intervalValue?.text = getString(R.string.overlay_interval_value, intervalMs, intervalMs + randomExtraMs)
        durationValue?.text = getString(R.string.overlay_duration_value, tapDurationMs)
        repeatValue?.text = if (repeatCount == 0) {
            getString(R.string.overlay_repeat_infinite)
        } else {
            getString(R.string.overlay_repeat_finite, repeatCount)
        }
        pointsValue?.text = if (points.isEmpty()) {
            getString(R.string.overlay_point_missing)
        } else {
            getString(R.string.overlay_points_value, points.size)
        }

        playButton?.contentDescription = getString(
            if (isClicking) R.string.overlay_stop_button_desc else R.string.overlay_play_button_desc
        )
    }

    private fun refreshControllerAlpha() {
        controllerAlpha = AutoTapperConfig.getControllerAlpha(this)
        val visible = if (isExpanded) panelView else bubbleView
        val hidden = if (isExpanded) bubbleView else panelView
        visible?.animate()?.cancel()
        hidden?.animate()?.cancel()
        visible?.alpha = controllerAlpha
        hidden?.alpha = 0f
        pickerRoot?.findViewById<View>(R.id.card_picker)?.alpha = controllerAlpha
    }

    // ---------------------------------------------------------------- 取点

    private fun showPickerOverlay() {
        if (pickerRoot != null) {
            return
        }

        sessionStartCount = AutoTapperConfig.loadPoints(this).size

        // Service 上下文没有 MaterialComponents 主题，膨胀前包一层主题
        val themedContext = android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_AutoTapper_Floating)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_layout, null)
        pickerRoot = view
        pickerCounterView = view.findViewById(R.id.tv_picker_counter)
        updatePickerCounter()

        view.findViewById<View>(R.id.capture_root).setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                addPickedPoint(event.rawX.toInt(), event.rawY.toInt())
            }
            true
        }
        view.findViewById<View>(R.id.btn_finish_capture).setOnClickListener { finishPicking() }
        view.findViewById<View>(R.id.btn_cancel_capture).setOnClickListener { cancelPicking() }
        view.findViewById<View>(R.id.card_picker)?.alpha = controllerAlpha

        windowManager.addView(view, fullscreenParams())
        // 取点时控制面板被遮罩挡住没有意义，先淡出；取完再回来
        panelView?.animate()?.cancel()
        panelView?.alpha = 0f
        panelView?.let { setTouchable(it, false) }
        // 徽标要压在遮罩之上才看得清，重新挂一次窗口
        hideMarkers()
        refreshMarkers()
    }

    private fun dismissPickerOverlay() {
        pickerRoot?.let { removeViewSafely(it) }
        pickerRoot = null
        pickerCounterView = null
        if (isExpanded) {
            panelView?.animate()?.cancel()
            panelView?.alpha = controllerAlpha
            panelView?.let { setTouchable(it, true) }
            refreshMarkers()
        }
    }

    private fun addPickedPoint(x: Int, y: Int) {
        AutoTapperConfig.addPoint(this, x, y)
        broadcastPointsChanged(x, y)
        updatePickerCounter()
        refreshMarkers()
        Toast.makeText(this, getString(R.string.point_added_toast, x, y), Toast.LENGTH_SHORT)
            .show()
    }

    private fun finishPicking() {
        dismissPickerOverlay()
        refreshControllerState()
        refreshMarkers()
        Toast.makeText(
            this,
            getString(R.string.picking_finished_toast, AutoTapperConfig.loadPoints(this).size),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun cancelPicking() {
        val all = AutoTapperConfig.loadPoints(this)
        if (all.size > sessionStartCount) {
            AutoTapperConfig.savePoints(this, all.take(sessionStartCount))
        }
        broadcastPointsChanged()
        dismissPickerOverlay()
        refreshControllerState()
        refreshMarkers()
        Toast.makeText(this, R.string.picking_cancelled_toast, Toast.LENGTH_SHORT).show()
    }

    private fun updatePickerCounter() {
        pickerCounterView?.text = getString(
            R.string.picker_counter_template,
            AutoTapperConfig.loadPoints(this).size
        )
    }

    private fun broadcastPointsChanged(x: Int = -1, y: Int = -1) {
        sendBroadcast(
            Intent(AutoTapperConfig.ACTION_POINT_SELECTED)
                .setPackage(packageName)
                .putExtra(AutoTapperConfig.EXTRA_TAP_X, x)
                .putExtra(AutoTapperConfig.EXTRA_TAP_Y, y)
        )
    }

    private fun removeLastPoint() {
        if (isClicking) {
            TapScheduler.stop(this)
        }

        if (AutoTapperConfig.loadPoints(this).isEmpty()) {
            Toast.makeText(this, R.string.no_point_to_delete_toast, Toast.LENGTH_SHORT).show()
            return
        }

        AutoTapperConfig.removeLastPoint(this)
        broadcastPointsChanged()
        refreshControllerState()
        refreshMarkers()
        Toast.makeText(this, R.string.point_removed_toast, Toast.LENGTH_SHORT).show()
    }

    private fun toggleClicking() {
        if (isClicking) {
            TapScheduler.stop(this)
            return
        }
        TapScheduler.start(this)
    }

    // ---------------------------------------------------------------- 点位徽标

    private fun refreshMarkers() {
        val points = AutoTapperConfig.loadPoints(this)
        if (!isExpanded || points.isEmpty()) {
            hideMarkers()
            return
        }

        while (pointMarkers.size < points.size) {
            val marker = createPointMarker()
            windowManager.addView(marker, markerParams(0, 0))
            pointMarkers.add(marker)
        }
        while (pointMarkers.size > points.size) {
            val removed = pointMarkers.removeAt(pointMarkers.size - 1)
            markerBlinkers.remove(removed)?.let { handler.removeCallbacks(it) }
            removed.animate().cancel()
            removeViewSafely(removed)
        }

        points.forEachIndexed { index, point ->
            val marker = pointMarkers[index]
            marker.text = (index + 1).toString()
            windowManager.updateViewLayout(marker, markerParams(point.x, point.y))

            val active = isClicking && index == currentPointIndex
            val wasActive = marker.tag as? Boolean ?: false
            if (wasActive != active) {
                marker.tag = active
                marker.background = markerBackground(active)
                marker.setTextColor(
                    Ef.color(this, if (active) R.color.ef_on_signal else R.color.ef_tag_fg)
                )
            }
            updateMarkerBlink(marker, active)
        }
    }

    /** 圆形序号徽标：墨底白字，当前点击的点变成黄底墨字。 */
    private fun createPointMarker() = TextView(this).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        isSingleLine = true
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Ef.color(this@OverlayService, R.color.ef_tag_fg))
        background = markerBackground(active = false)
        alpha = 0.9f
    }

    private fun markerBackground(active: Boolean) = Ef.cutBackground(
        this,
        if (active) R.color.ef_signal else R.color.ef_tag_bg,
        if (active) R.color.ef_ink else R.color.ef_panel,
        cutDp = 9f,
        strokeWidthDp = 2f
    )

    /**
     * 当前点位的定位提示：两档硬闪，不做缩放和发光。
     * 只在「正在点击的那一个点」上跑，停止即停。
     */
    private fun updateMarkerBlink(marker: View, active: Boolean) {
        val running = markerBlinkers[marker]
        if (!active) {
            if (running != null) {
                handler.removeCallbacks(running)
                markerBlinkers.remove(marker)
            }
            marker.alpha = 0.9f
            return
        }
        if (running != null) {
            return
        }
        val blink = object : Runnable {
            private var on = true
            override fun run() {
                on = !on
                marker.alpha = if (on) 1f else 0.55f
                handler.postDelayed(this, BLINK_INTERVAL_MS)
            }
        }
        markerBlinkers[marker] = blink
        marker.alpha = 1f
        handler.postDelayed(blink, BLINK_INTERVAL_MS)
    }

    private fun hideMarkers() {
        pointMarkers.forEach { marker ->
            markerBlinkers.remove(marker)?.let { handler.removeCallbacks(it) }
            removeViewSafely(marker)
        }
        pointMarkers.clear()
    }

    // ---------------------------------------------------------------- 窗口参数

    private fun floatParams(width: Int, height: Int, x: Int, y: Int) =
        WindowManager.LayoutParams(
            width,
            height,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.coerceAtLeast(0)
            this.y = y.coerceAtLeast(0)
        }

    private fun markerParams(x: Int, y: Int): WindowManager.LayoutParams {
        val size = Ef.dp(this, MARKER_SIZE_DP)
        return WindowManager.LayoutParams(
            size,
            size,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x - size / 2
            this.y = y - size / 2
            alpha = MARKER_WINDOW_ALPHA
        }
    }

    private fun fullscreenParams() =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    private fun bubbleWidth() = Ef.dp(this, BUBBLE_SIZE_DP)

    private fun bubbleHeight() = Ef.dp(this, BUBBLE_SIZE_DP)

    private fun panelWidth() = Ef.dp(this, PANEL_WIDTH_DP)

    private fun removeViewSafely(view: View?) {
        view ?: return
        runCatching { windowManager.removeView(view) }
    }

    private fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private val screenWidth: Int
        get() = resources.displayMetrics.widthPixels

    private val screenHeight: Int
        get() = resources.displayMetrics.heightPixels

    // ---------------------------------------------------------------- 通知与收尾

    private fun registerServiceReceiver() {
        val filter = IntentFilter().apply {
            addAction(AutoTapperConfig.ACTION_CLICK_STATE_CHANGED)
            addAction(AutoTapperConfig.ACTION_POINT_SELECTED)
            addAction(AutoTapperConfig.ACTION_CONTROLLER_ALPHA_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(serviceReceiver, filter)
        }
    }

    private fun startAsForegroundService() {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(
                if (isClicking) getString(R.string.overlay_notification_running)
                else getString(R.string.overlay_notification_idle)
            )
            .setOngoing(true)
            .setContentIntent(buildOpenAppPendingIntent())
            .addAction(
                0,
                getString(R.string.overlay_notification_stop),
                buildStopControllerPendingIntent()
            )
            .build()

    private fun refreshNotification() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(
            this,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildStopControllerPendingIntent(): PendingIntent {
        val intent = Intent(this, OverlayService::class.java)
            .setAction(AutoTapperConfig.ACTION_STOP_CONTROLLER)
        return PendingIntent.getService(
            this,
            101,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.overlay_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun closeController() {
        stopClickingIfNeeded()
        stopSelf()
    }

    private fun stopClickingIfNeeded() {
        if (isClicking) {
            TapScheduler.stop(this)
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "autotapper_overlay_controller"
        private const val NOTIFICATION_ID = 1001

        // 动效：出现 snap 320ms，退出 mech 200ms（约为进入的 2/3）
        private const val ENTER_MS = 320L
        private const val EXIT_MS = 200L
        private val SNAP = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        private val MECH = PathInterpolator(0.7f, 0f, 0.2f, 1f)

        private const val BUBBLE_SIZE_DP = 58
        private const val PANEL_WIDTH_DP = 196
        private const val MARKER_SIZE_DP = 34
        private const val MARKER_WINDOW_ALPHA = 0.95f
        private const val BLINK_INTERVAL_MS = 620L
    }
}
