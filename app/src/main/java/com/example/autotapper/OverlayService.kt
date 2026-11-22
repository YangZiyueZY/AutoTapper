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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
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
import androidx.core.content.ContextCompat
import com.example.autotapper.engine.RegionStore
import com.example.autotapper.model.Region
import com.example.autotapper.model.TapCoord
import com.example.autotapper.plugin.RecordedGesture
import com.example.autotapper.plugin.host.RecordedGestureJson
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 悬浮控制台：一条贴边窄竖条 + 收起后的小标签 + 可展开的读数面板。
 * 宽高、边距与点位徽标直径全部读 dimens_console（values / values-sw600dp / values-w840dp）：
 * 手机档与改动前的 46 / 40×54 / 96 / 6 / 24dp 完全一致，平板与大屏按屏幕尺寸类等比放大。
 *
 * 三扇窗口都在服务启动时各建一次，收起 / 展开只改透明度、位移和触摸开关，
 * 快速连点不会多出窗口（这是 v1.2 那个「展开多个控制器」问题的修法，必须保留）。
 *
 * 占用原则：默认只占屏幕边缘一个窄条宽（手机 46dp，平板更大）；读数面板按「详情」才出现，且不可触摸、可透视，
 * 不挡下面的应用；停下 4 秒后自动变淡，碰一下恢复。
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var console: ConsoleRail? = null
    private var railView: LinearLayout? = null
    private var tabView: FrameLayout? = null
    private var infoView: LinearLayout? = null

    private var pickerRoot: View? = null
    private var pickerCounterView: TextView? = null

    // 画区域模式：和打点遮罩分开建窗，互不抢占监听器；同一时刻只服务一个请求
    private var regionRoot: View? = null
    private var regionBox: View? = null
    private var regionSizeView: TextView? = null
    private var regionStartX = 0
    private var regionStartY = 0
    private var regionDragging = false
    private var regionRequestId: String? = null
    private var regionLabel: String = ""

    // 录制模式：状态全在服务里（插件那边只是在等一个广播，随时可能取消），
    // 窗口和已录手势都不挂在请求方身上，服务被系统重拉也不会把半截状态留在插件里
    private var recordRoot: View? = null
    private var recordStatusView: TextView? = null
    private var recordRequestId: String? = null
    private val recordedGestures = mutableListOf<RecordedGesture>()
    private var recordEndAt = 0L
    private var recordDownX = 0f
    private var recordDownY = 0f
    private var recordDownAt = 0L
    private var recordTracking = false

    /** 到时长上限自动收摊，别让遮层一直占着触摸（用户还以为在录）。 */
    private val recordDeadlineRunnable = Runnable {
        if (recordRoot != null) {
            LogBus.info("录制到达时长上限，自动结束")
            finishRecording(sendResult = true)
        }
    }

    private val pointMarkers = mutableListOf<TextView>()
    private val markerBlinkers = mutableMapOf<View, Runnable>()

    private var expanded = false
    private var infoVisible = false
    private var dockedRight = true
    private var dimmed = false

    private var isClicking = false
    private var currentPointIndex = -1
    private var sessionStartCount = 0

    /** 竖条顶端的屏幕 Y 坐标（px）。 */
    private var anchorY = -1
    private var controllerAlpha = 1f
    private var notifiedClicking: Boolean? = null
    private var nightMode = false

    private var dragDownRawX = 0f
    private var dragDownRawY = 0f
    private var dragStartY = 0
    private var dragActive = false

    private val touchSlop: Int
        get() = ViewConfiguration.get(this).scaledTouchSlop

    private val dimRunnable = Runnable {
        dimmed = true
        applyAlpha(animate = true)
    }

    private val dragTouchListener = object : View.OnTouchListener {
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    wake()
                    dragDownRawX = event.rawX
                    dragDownRawY = event.rawY
                    dragStartY = anchorY.coerceAtLeast(0)
                    dragActive = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - dragDownRawY).toInt()
                    val dx = (event.rawX - dragDownRawX).toInt()
                    if (!dragActive && (abs(dy) > touchSlop || abs(dx) > touchSlop)) {
                        dragActive = true
                    }
                    if (dragActive) {
                        anchorY = (dragStartY + dy).coerceIn(0, maxAnchorY())
                        positionAll()
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragActive) {
                        view.performClick()
                    } else {
                        // 松手后贴回最近的屏幕侧边
                        val nextRight = event.rawX >= screenWidth / 2f
                        if (nextRight != dockedRight) {
                            dockedRight = nextRight
                            AutoTapperConfig.saveConsoleDock(this@OverlayService, dockedRight)
                        }
                        positionAll()
                        renderConsole()
                    }
                    wake()
                    return true
                }
            }
            return false
        }
    }

    private val consoleActions = object : ConsoleActions {
        override fun onPrimaryToggle() {
            wake()
            toggleClicking()
        }

        override fun onPick() {
            wake()
            showPickerOverlay()
        }

        override fun onDeleteLast() {
            wake()
            removeLastPoint()
        }

        override fun onToggleInfo() {
            wake()
            setInfoVisible(!infoVisible, animate = true)
        }

        override fun onCollapse() {
            wake()
            setExpanded(false)
        }

        override fun onClose() {
            closeController()
        }
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AutoTapperConfig.ACTION_CLICK_STATE_CHANGED -> {
                    isClicking = intent.getBooleanExtra(AutoTapperConfig.EXTRA_IS_CLICKING, false)
                    currentPointIndex =
                        intent.getIntExtra(AutoTapperConfig.EXTRA_CURRENT_POINT_INDEX, -1)
                    renderConsole()
                    refreshMarkers()
                    refreshNotification()
                    if (isClicking) {
                        // 点击循环每个间隔就发一次状态广播：这里不能重置闲置计时，
                        // 否则「点击中保持 85%」永远走不到，长按一路全亮。
                        applyAlpha(animate = true)
                    } else {
                        wake()
                    }
                }

                AutoTapperConfig.ACTION_POINT_SELECTED -> {
                    renderConsole()
                    refreshMarkers()
                }

                AutoTapperConfig.ACTION_CONTROLLER_ALPHA_CHANGED -> refreshControllerAlpha()
                AutoTapperConfig.ACTION_CANCEL_REGION_PICK -> {
                    if (intent.getStringExtra(AutoTapperConfig.EXTRA_PICK_REQUEST_ID) == regionRequestId) {
                        cancelRegionPick(sendResult = false)
                    }
                }

                AutoTapperConfig.ACTION_STOP_RECORDING -> {
                    // 只认自己那一单：并发请求可能互相踩，requestId 对不上就别乱关别人的遮层
                    val recordId = intent.getStringExtra(AutoTapperConfig.EXTRA_RECORD_REQUEST_ID)
                    if (recordRoot != null && recordId == recordRequestId) {
                        cancelRecording()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        controllerAlpha = AutoTapperConfig.getControllerAlpha(this)
        dockedRight = AutoTapperConfig.getConsoleDockedRight(this)
        nightMode = isNightMode()
        registerServiceReceiver()
        startAsForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!PermissionUtils.isOverlayPermissionGranted(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            // 关闭时先不要再建一遍窗口，否则会闪一帧
            AutoTapperConfig.ACTION_STOP_CONTROLLER -> {
                closeController()
                return START_NOT_STICKY
            }

            AutoTapperConfig.ACTION_START_PICKING -> {
                ensureViews()
                setExpanded(true, animate = false)
                showPickerOverlay()
            }

            AutoTapperConfig.ACTION_START_REGION_PICK -> {
                ensureViews()
                showRegionPick(
                    intent.getStringExtra(AutoTapperConfig.EXTRA_PICK_REQUEST_ID).orEmpty(),
                    intent.getStringExtra(AutoTapperConfig.EXTRA_REGION_LABEL).orEmpty()
                )
            }

            AutoTapperConfig.ACTION_START_RECORDING -> {
                ensureViews()
                startRecording(
                    intent.getStringExtra(AutoTapperConfig.EXTRA_RECORD_REQUEST_ID).orEmpty(),
                    intent.getLongExtra(AutoTapperConfig.EXTRA_RECORD_MAX_MS, DEFAULT_RECORD_MAX_MS)
                )
            }

            // 请求方已经放弃（插件协程被取消 / 超时）：只收窗口，不再回传结果，那边没人接了
            AutoTapperConfig.ACTION_STOP_RECORDING -> {
                cancelRecording()
                return START_STICKY
            }

            AutoTapperConfig.ACTION_SHOW_CONTROLLER -> {
                ensureViews()
                setExpanded(true, animate = false)
                if (intent.getBooleanExtra(AutoTapperConfig.EXTRA_START_PICKING, false)) {
                    showPickerOverlay()
                }
            }

            null -> {
                ensureViews()
                setExpanded(false, animate = false)
            }

            else -> ensureViews()
        }

        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转 / 分屏后可用高度变了：先把竖直位置夹回范围再重排，否则竖条会停在屏幕外
        if (railView != null) {
            anchorY = anchorY.coerceIn(0, maxAnchorY())
            positionAll()
        }
        val night = (newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (night == nightMode || railView == null) {
            return
        }
        nightMode = night
        rebuildConsole()
    }

    private fun isNightMode(): Boolean {
        val mode = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    override fun onDestroy() {
        stopClickingIfNeeded()
        dismissPickerOverlay()
        cancelRegionPick(sendResult = false)
        cancelRecording()
        hideMarkers()
        handler.removeCallbacksAndMessages(null)
        railView?.let { removeViewSafely(it) }
        tabView?.let { removeViewSafely(it) }
        infoView?.let { removeViewSafely(it) }
        railView = null
        tabView = null
        infoView = null
        console = null
        runCatching { unregisterReceiver(serviceReceiver) }
        super.onDestroy()
    }

    // ------------------------------------------------------------ 视图

    /** 只在这里建窗口，且只建一次。 */
    private fun ensureViews() {
        if (railView != null) {
            return
        }

        val created = ConsoleRail(this, consoleActions)
        console = created

        railView = created.railView.also {
            windowManager.addView(it, floatParams(railWidth(), WindowManager.LayoutParams.WRAP_CONTENT))
            it.alpha = 0f
            it.setOnTouchListener(dragTouchListener)
            setTouchable(it, false)
        }
        tabView = created.tabView.also {
            windowManager.addView(
                it,
                floatParams(tabWidth(), tabHeight())
            )
            it.alpha = 0f
            it.setOnTouchListener(dragTouchListener)
            it.setOnClickListener { wake(); setExpanded(true) }
            setTouchable(it, false)
        }
        infoView = created.infoView.also {
            windowManager.addView(
                it,
                floatParams(infoWidth(), WindowManager.LayoutParams.WRAP_CONTENT)
            )
            it.alpha = 0f
            // 只读面板：不接收触摸，点它就等于点下面的应用
            setTouchable(it, false)
        }

        if (anchorY < 0) {
            anchorY = ((screenHeight - Ef.dp(this, 300)) / 2).coerceAtLeast(0)
        }

        positionAll()
        renderConsole()
        applyAlpha(animate = false)
        setExpanded(expanded, animate = false)
        scheduleDim()
    }

    /** 深浅色切换后重建：颜色在创建时解析，需要重新生成一次视图。 */
    /** 旋转后重建控制器时，画区域窗口也要跟着重画（现有请求继续保留）。 */
    private fun rebuildRegionPickIfNeeded(wasActive: Boolean, requestId: String?, label: String) {
        if (wasActive) {
            showRegionPick(requestId.orEmpty(), label)
        }
    }

    private fun rebuildConsole() {
        val wasExpanded = expanded
        val wasInfo = infoVisible
        val hadPicker = pickerRoot != null
        val hadRegionPick = regionRoot != null
        val regionPickRequestId = regionRequestId
        val regionPickLabel = regionLabel
        val startCount = sessionStartCount
        val hadRecording = recordRoot != null

        hideMarkers()
        if (hadPicker) {
            dismissPickerOverlay()
        }
        if (hadRegionPick) {
            dismissRegionOverlay()
        }
        if (hadRecording) {
            // 重建后控制器窗口会被重新加到最上层，遮层就压不住了；已录内容也不能悄悄丢，
            // 所以这里提前结束录制，把录到的手势照常回传给请求方
            LogBus.info("窗口重建（旋转或深浅色切换），录制提前结束")
            finishRecording(sendResult = true)
        }
        val removed = listOfNotNull(railView, tabView, infoView).map { removeViewSafely(it) }
        railView = null
        tabView = null
        infoView = null
        console = null
        expanded = false
        infoVisible = false

        if (removed.any { !it }) {
            // 有窗口没摘干净：继续重建只会留下抓不住的孤儿窗口，直接结束服务让下次干净重来
            stopSelf()
            return
        }

        ensureViews()
        setInfoVisible(wasInfo, animate = false)
        setExpanded(wasExpanded, animate = false)
        if (hadPicker) {
            showPickerOverlay()
            // showPickerOverlay 会把基准重置成当前点位数量，这里再还原重建前的基准，
            // 否则「取消取点」回滚不掉重建之前那次会话新增的点位
            sessionStartCount = startCount
        }
        rebuildRegionPickIfNeeded(hadRegionPick, regionPickRequestId, regionPickLabel)
    }

    private fun renderConsole() {
        val points = AutoTapperConfig.loadPoints(this)
        val interval = AutoTapperConfig.getIntervalMs(this)
        val randomExtra = AutoTapperConfig.getRandomExtraMs(this)
        console?.render(
            ConsoleState(
                running = isClicking,
                pointCount = points.size,
                intervalMin = interval,
                intervalMax = interval + randomExtra,
                tapDuration = AutoTapperConfig.getTapDurationMs(this),
                repeatCount = AutoTapperConfig.getRepeatCount(this),
                infoVisible = infoVisible,
                dockedRight = dockedRight
            )
        )
    }

    // ------------------------------------------------------------ 收起 / 展开 / 详情

    private fun setExpanded(expand: Boolean, animate: Boolean = true) {
        val rail = railView ?: return
        val tab = tabView ?: return
        val info = infoView ?: return

        val alreadyThere = expand == expanded && ((expand && rail.alpha > 0f) || (!expand && tab.alpha > 0f))
        expanded = expand
        if (!expand) {
            infoVisible = false
        }

        rail.animate().cancel()
        tab.animate().cancel()
        info.animate().cancel()

        setTouchable(rail, expand)
        setTouchable(tab, !expand)

        if (alreadyThere) {
            applyAlpha(animate = false)
            renderConsole()
            positionAll()
            return
        }

        positionAll()

        if (!animate) {
            applyAlpha(animate = false)
        } else {
            val entering = if (expand) rail else tab
            val leaving = if (expand) tab else rail
            entering.alpha = 0f
            entering.translationX = (if (dockedRight) 1 else -1) * Ef.dp(this, 10).toFloat()
            entering.animate()
                .alpha(targetAlpha())
                .translationX(0f)
                .setDuration(ENTER_MS)
                .setInterpolator(SNAP)
                .start()
            leaving.animate()
                .alpha(0f)
                .translationX((if (dockedRight) 1 else -1) * Ef.dp(this, 8).toFloat())
                .setDuration(EXIT_MS)
                .setInterpolator(MECH)
                .start()

            // 读数面板不参与展开/收起的主过渡，但它必须跟着一起消失，否则收起后还会留在屏幕上
            info.animate().cancel()
            val infoTarget = if (expand && infoVisible) targetAlpha() else 0f
            if (Math.abs(info.alpha - infoTarget) > 0.01f) {
                info.animate()
                    .alpha(infoTarget)
                    .setDuration(if (infoTarget > 0f) ENTER_MS else EXIT_MS)
                    .setInterpolator(if (infoTarget > 0f) SNAP else MECH)
                    .start()
            } else {
                info.alpha = infoTarget
            }
            if (infoTarget == 0f) {
                info.translationX = (if (dockedRight) 1 else -1) * Ef.dp(this, 8).toFloat()
            } else {
                info.translationX = 0f
            }
        }

        renderConsole()
        refreshMarkers()
    }

    private fun setInfoVisible(visible: Boolean, animate: Boolean = true) {
        val info = infoView ?: return
        infoVisible = visible
        info.animate().cancel()
        if (!animate) {
            applyAlpha(animate = false)
        } else {
            info.animate()
                .alpha(if (visible && expanded) targetAlpha() else 0f)
                .translationX(if (visible) 0f else (if (dockedRight) 1 else -1) * Ef.dp(this, 8).toFloat())
                .setDuration(if (visible) ENTER_MS else EXIT_MS)
                .setInterpolator(if (visible) SNAP else MECH)
                .start()
        }
        renderConsole()
    }

    private fun scheduleDim() {
        handler.removeCallbacks(dimRunnable)
        handler.postDelayed(dimRunnable, DIM_DELAY_MS)
    }

    private fun wake() {
        if (dimmed) {
            dimmed = false
            applyAlpha(animate = true)
        }
        scheduleDim()
    }

    private fun targetAlpha(): Float = when {
        !dimmed -> controllerAlpha
        isClicking -> controllerAlpha * 0.85f
        // 用户可以把透明度调到 30%，再乘 0.45 就成了 13%（几乎看不见），这里保底 32%
        else -> (controllerAlpha * 0.45f).coerceAtLeast(DIM_ALPHA_FLOOR)
    }

    /**
     * 同一时刻只允许一个控制台窗口可见：展开时收起着的小标签必须明确归零，
     * 否则两个窗口会停在同一个像素位置上重叠（曾出现"点位数+箭头"糊成一团的现象）。
     */
    private fun applyAlpha(animate: Boolean) {
        val alpha = targetAlpha()
        val active = if (expanded) railView else tabView
        val inactive = if (expanded) tabView else railView
        val info = infoView?.takeIf { expanded && infoVisible }

        fun apply(view: View?, target: Float) {
            view ?: return
            if (!animate) {
                view.animate().cancel()
                view.alpha = target
                view.translationX = 0f
            } else if (Math.abs(view.alpha - target) > 0.01f) {
                view.animate().alpha(target).setDuration(ALPHA_MS).start()
            } else {
                view.alpha = target
            }
        }

        apply(active, alpha)
        apply(inactive, 0f)
        apply(infoView, if (info != null) alpha else 0f)
    }

    private fun refreshControllerAlpha() {
        controllerAlpha = AutoTapperConfig.getControllerAlpha(this)
        applyAlpha(animate = true)
    }

    // ------------------------------------------------------------ 位置与触摸

    private fun positionAll() {
        val rail = railView ?: return
        val tab = tabView ?: return
        val info = infoView ?: return
        val margin = consoleMargin()

        val y = anchorY.coerceIn(0, maxAnchorY())
        anchorY = y

        val railX = if (dockedRight) screenWidth - railWidth() - margin else margin
        val tabX = if (dockedRight) screenWidth - tabWidth() - margin else margin
        // 读数板贴在窄条内侧，与窄条之间留一条同样的 margin（平板档一起放大，比例不变）
        val infoX = if (dockedRight) {
            railX - infoWidth() - margin
        } else {
            railX + railWidth() + margin
        }

        moveTo(rail, railX, y)
        moveTo(tab, tabX, y)
        moveTo(info, infoX, y)
    }

    private fun maxAnchorY(): Int {
        val height = maxOf(
            railView?.height ?: 0,
            tabView?.height ?: 0,
            Ef.dp(this, 300)
        )
        return (screenHeight - height - consoleMargin()).coerceAtLeast(0)
    }

    private fun moveTo(view: View, x: Int, y: Int) {
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = x
        params.y = y
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

    private fun floatParams(width: Int, height: Int) =
        WindowManager.LayoutParams(
            width,
            height,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 坐标是按「距左边缘」算的，RTL 语言下 START 会翻成右侧，这里用绝对 LEFT
            gravity = Gravity.TOP or Gravity.LEFT
        }

    // 尺寸统一读 dimens_console：手机 46/40/96dp，平板 54/46/116dp，大屏 58/50/124dp
    private fun railWidth() = ConsoleRail.railWidth(this)

    private fun tabWidth() = ConsoleRail.tabWidth(this)

    private fun tabHeight() = ConsoleRail.tabHeight(this)

    private fun infoWidth() = ConsoleRail.infoWidth(this)

    private fun consoleMargin() = resources.getDimensionPixelSize(R.dimen.console_margin)

    private fun markerSize() = resources.getDimensionPixelSize(R.dimen.console_marker_size)

    // ------------------------------------------------------------ 取点

    private fun showPickerOverlay() {
        if (pickerRoot != null) {
            return
        }

        // 权限可能在服务运行期间被撤销，先复查再建窗
        if (!PermissionUtils.isOverlayPermissionGranted(this)) {
            toast(R.string.overlay_permission_required)
            return
        }

        // Service 上下文没有 MaterialComponents 主题，膨胀前包一层主题
        val themedContext = android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_AutoTapper_Floating)
        val view = LayoutInflater.from(themedContext).inflate(R.layout.overlay_layout, null)

        // 先挂窗口、成功了再记引用：addView 抛异常时不能让 pickerRoot 停在非 null，
        // 否则以后每次取点都会在入口直接 return，控制器还一直停在隐藏状态
        if (!runCatching { windowManager.addView(view, fullscreenParams()) }.isSuccess) {
            toast(R.string.overlay_permission_required)
            return
        }

        sessionStartCount = AutoTapperConfig.loadPoints(this).size
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

        // 取点时控制台会被遮罩挡住，先淡出；取完再回来
        railView?.animate()?.cancel()
        railView?.alpha = 0f
        railView?.let { setTouchable(it, false) }
        // 徽标要压在遮罩之上才看得清，重新挂一次窗口
        hideMarkers()
        refreshMarkers()
    }

    private fun dismissPickerOverlay() {
        pickerRoot?.let { removeViewSafely(it) }
        pickerRoot = null
        pickerCounterView = null
        if (expanded) {
            applyAlpha(animate = true)
            railView?.let { setTouchable(it, true) }
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
        renderConsole()
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
        renderConsole()
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
        renderConsole()
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

    // ------------------------------------------------------------ 点位徽标

    private fun refreshMarkers() {
        val points = AutoTapperConfig.loadPoints(this)
        // 展开时始终显示；收起时只在点击进行中显示，避免挡住被操作区域
        if ((!expanded && !isClicking) || points.isEmpty()) {
            hideMarkers()
            return
        }

        while (pointMarkers.size < points.size) {
            val marker = createPointMarker()
            // 权限被撤销时 addView 会抛异常，别让它把服务炸掉，也不要留下半套徽标
            if (!runCatching { windowManager.addView(marker, markerParams(0, 0)) }.isSuccess) {
                hideMarkers()
                stopSelf()
                return
            }
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
            // 徽标直径随屏幕档位变大，序号跟着同比例放大，避免「大圆小字」
            val markerTextSp = if (index >= 99) 7f else if (index >= 9) 8.5f else 10f
            marker.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_SP,
                ConsoleRail.scaleSp(this, markerTextSp)
            )
            runCatching { windowManager.updateViewLayout(marker, markerParams(point.x, point.y)) }

            val active = isClicking && index == currentPointIndex
            val wasActive = marker.tag as? Boolean ?: false
            if (wasActive != active) {
                marker.tag = active
                marker.background = markerBackground(active)
                // 两种状态都是墨字：底色从纸白换成信号黄，字色保持不变
                marker.setTextColor(Ef.color(this, R.color.ef_ink))
            }
            updateMarkerBlink(marker, active)
        }
    }

    /** 点位徽标：细环 + 序号，未命中时半透明，当前点填成强调色。 */
    private fun createPointMarker() = TextView(this).apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
        isSingleLine = true
        textSize = ConsoleRail.scaleSp(this@OverlayService, 10f)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(Ef.color(this@OverlayService, R.color.ef_ink))
        background = markerBackground(active = false)
        // 窗口本身会被系统按 FLAG_NOT_TOUCHABLE 压到 0.8，视图层不再叠加衰减，保证序号可读
        alpha = 1f
    }

    private fun markerBackground(active: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(
            Ef.color(
                this@OverlayService,
                if (active) R.color.ef_signal else R.color.ef_panel
            )
        )
        setStroke(
            Ef.dp(this@OverlayService, if (active) 2 else 1),
            Ef.color(this@OverlayService, R.color.ef_ink)
        )
    }

    /** 当前点位：两档硬闪，只做透明度，不缩放、不发光。 */
    private fun updateMarkerBlink(marker: View, active: Boolean) {
        val running = markerBlinkers[marker]
        if (!active) {
            if (running != null) {
                handler.removeCallbacks(running)
                markerBlinkers.remove(marker)
            }
            marker.alpha = 1f
            return
        }
        if (running != null) {
            return
        }
        val blink = object : Runnable {
            private var on = true
            override fun run() {
                on = !on
                marker.alpha = if (on) 1f else 0.6f
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

    private fun markerParams(x: Int, y: Int): WindowManager.LayoutParams {
        val size = markerSize()
        return WindowManager.LayoutParams(
            size,
            size,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
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
            gravity = Gravity.TOP or Gravity.LEFT
        }

    /** 画区域窗口要收 BACK 按键，所以不能带 FLAG_NOT_FOCUSABLE；只在画区域期间短暂聚焦。 */
    private fun regionFullscreenParams() =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
        }

    // ------------------------------------------------------------ 杂项

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------ 画区域（插件 RegionApi.pick）

    /**
     * 悬浮层进入矩形框选：按下起点、拖出矩形、松手保存并广播结果。
     * 点「取消」或按 BACK 放弃；结果通过广播回传给请求方。
     */
    private fun showRegionPick(requestId: String, label: String) {
        if (regionRoot != null) {
            cancelRegionPick(sendResult = true)
        }
        if (!PermissionUtils.isOverlayPermissionGranted(this)) {
            toast(R.string.region_pick_permission_required)
            return
        }

        val themedContext = android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_AutoTapper_Floating)
        val root = FrameLayout(themedContext)
        root.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        val scrim = View(themedContext).apply {
            setBackgroundColor(Ef.color(this@OverlayService, R.color.ef_scrim))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(scrim)

        val box = View(themedContext).apply {
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(Ef.dp(this@OverlayService, 2), Ef.color(this@OverlayService, R.color.ef_signal))
            }
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(0, 0)
        }
        root.addView(box)

        val sizeView = TextView(themedContext).apply {
            text = getString(R.string.region_pick_size, 0, 0)
            setTextColor(Ef.color(this@OverlayService, R.color.ef_ink))
            textSize = 13f
            setBackgroundColor(Ef.color(this@OverlayService, R.color.ef_panel))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
        }
        root.addView(sizeView)

        val card = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.ef_panel_board)
            isClickable = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = Ef.dp(this@OverlayService, 64) }
        }
        card.addView(TextView(themedContext).apply {
            text = getString(R.string.region_pick_title)
            setTextColor(Ef.color(this@OverlayService, R.color.ef_ink))
            textSize = 18f
        })
        card.addView(TextView(themedContext).apply {
            text = getString(R.string.region_pick_hint)
            setTextColor(Ef.color(this@OverlayService, R.color.ef_ink))
            textSize = 13f
            maxWidth = Ef.dp(this@OverlayService, 248)
        })
        card.addView(TextView(themedContext).apply {
            text = if (label.isBlank()) getString(R.string.region_pick_title) else label
            setTextColor(Ef.color(this@OverlayService, R.color.ef_ink))
            textSize = 13f
            maxWidth = Ef.dp(this@OverlayService, 248)
        })
        val cancelButton = TextView(themedContext).apply {
            text = getString(R.string.cancel_capture)
            setTextColor(Ef.color(this@OverlayService, R.color.ef_ink))
            textSize = 15f
            setBackgroundResource(R.drawable.ef_panel_board)
            setPadding(Ef.dp(this@OverlayService, 14), Ef.dp(this@OverlayService, 8),
                Ef.dp(this@OverlayService, 14), Ef.dp(this@OverlayService, 8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Ef.dp(this@OverlayService, 12) }
            setOnClickListener { cancelRegionPick(sendResult = true) }
        }
        card.addView(cancelButton)
        root.addView(card)

        if (!runCatching { windowManager.addView(root, regionFullscreenParams()) }.isSuccess) {
            toast(R.string.region_pick_permission_required)
            return
        }

        regionRoot = root
        regionBox = box
        regionSizeView = sizeView
        regionRequestId = requestId
        regionLabel = label
        regionDragging = false
        scrim.setOnTouchListener { _, event -> handleRegionTouch(event) }
        root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK) {
                cancelRegionPick(sendResult = true)
                true
            } else {
                false
            }
        }
        runCatching { root.requestFocus() }

        railView?.animate()?.cancel()
        railView?.alpha = 0f
        railView?.let { setTouchable(it, false) }
        hideMarkers()
    }

    private fun handleRegionTouch(event: MotionEvent): Boolean {
        val x = event.rawX.toInt()
        val y = event.rawY.toInt()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                regionStartX = x
                regionStartY = y
                regionDragging = true
                regionBox?.visibility = View.VISIBLE
                regionSizeView?.visibility = View.VISIBLE
                updateRegionPreview(0, 0)
            }
            MotionEvent.ACTION_MOVE -> {
                if (regionDragging) {
                    updateRegionPreview(abs(x - regionStartX), abs(y - regionStartY))
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!regionDragging) return true
                regionDragging = false
                val left = min(regionStartX, x)
                val top = min(regionStartY, y)
                val width = abs(x - regionStartX)
                val height = abs(y - regionStartY)
                if (width < 2 || height < 2) {
                    cancelRegionPick(sendResult = true)
                    return true
                }
                completeRegionPick(left, top, width, height)
            }
            MotionEvent.ACTION_CANCEL -> {
                if (regionDragging) {
                    regionDragging = false
                    cancelRegionPick(sendResult = true)
                }
            }
        }
        return true
    }

    private fun updateRegionPreview(width: Int, height: Int) {
        val box = regionBox ?: return
        val lw = box.layoutParams as FrameLayout.LayoutParams
        lw.width = max(width, 2)
        lw.height = max(height, 2)
        lw.leftMargin = min(regionStartX, regionStartX + width)
        lw.topMargin = min(regionStartY, regionStartY + height)
        runCatching { box.layoutParams = lw }
        regionSizeView?.text = getString(R.string.region_pick_size, width, height)
        val sw = regionSizeView?.layoutParams as? FrameLayout.LayoutParams
        if (sw != null) {
            sw.leftMargin = min(regionStartX, regionStartX + width)
                .coerceIn(0, (screenWidth - Ef.dp(this, 160)).coerceAtLeast(0))
            val top = min(regionStartY, regionStartY + height) - Ef.dp(this, 32)
            sw.topMargin = top.coerceIn(0, (screenHeight - Ef.dp(this, 48)).coerceAtLeast(0))
            runCatching { regionSizeView?.layoutParams = sw }
        }
    }

    private fun completeRegionPick(x: Int, y: Int, width: Int, height: Int) {
        val currentId = regionRequestId ?: return
        val id = if (currentId.isBlank()) UUID.randomUUID().toString() else currentId
        val newRegion = Region(
            id = id,
            label = regionLabel.ifBlank { RegionStore.labelFor(RegionStore.load(this).size) },
            x = x,
            y = y,
            w = width,
            h = height
        )
        runCatching { RegionStore.save(this, RegionStore.load(this) + newRegion) }
            .onFailure { LogBus.warn("画区域结果落盘失败：" + it) }
        dismissRegionOverlay()
        runCatching {
            sendBroadcast(
                Intent(AutoTapperConfig.ACTION_REGION_PICKED)
                    .setPackage(packageName)
                    .putExtra(AutoTapperConfig.EXTRA_PICK_REQUEST_ID, id)
                    .putExtra(AutoTapperConfig.EXTRA_REGION_LABEL, newRegion.label)
                    .putExtra(AutoTapperConfig.EXTRA_REGION_X, x)
                    .putExtra(AutoTapperConfig.EXTRA_REGION_Y, y)
                    .putExtra(AutoTapperConfig.EXTRA_REGION_W, width)
                    .putExtra(AutoTapperConfig.EXTRA_REGION_H, height)
            )
        }
        Toast.makeText(this, getString(R.string.region_pick_saved_toast, newRegion.label), Toast.LENGTH_SHORT).show()
    }

    private fun cancelRegionPick(sendResult: Boolean) {
        if (regionRoot == null) return
        val id = regionRequestId
        dismissRegionOverlay()
        if (sendResult && id != null) {
            runCatching {
                sendBroadcast(
                    Intent(AutoTapperConfig.ACTION_CANCEL_REGION_PICK)
                        .setPackage(packageName)
                        .putExtra(AutoTapperConfig.EXTRA_PICK_REQUEST_ID, id)
                )
            }
            Toast.makeText(this, R.string.region_pick_cancelled_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dismissRegionOverlay() {
        regionRoot?.let { runCatching { windowManager.removeView(it) } }
        regionRoot = null
        regionBox = null
        regionSizeView = null
        regionRequestId = null
        regionLabel = ""
        regionDragging = false
        if (expanded) {
            applyAlpha(animate = true)
            railView?.let { setTouchable(it, true) }
            refreshMarkers()
        }
    }


    // ------------------------------------------------------------ 录制（插件 RecordApi.record）

    /**
     * 进入录制模式：一块全屏透明遮层 + 顶部一条墨底小条（「录制中 · 已 N 条」+「结束」）。
     *
     * 诚实边界：遮层必须拿到触摸才能记坐标，所以录制期间下层应用收不到这一次点击——
     * 录到的是"你点的位置"，不是"应用对这次点击的反应"。插件那边也据此写日志。
     */
    private fun startRecording(requestId: String, maxDurationMs: Long) {
        if (recordRoot != null) {
            // 同一时刻只服务一个录制请求：上一个请求方已经拿到结果或者已经超时放过手了
            finishRecording(sendResult = true)
        }
        if (!PermissionUtils.isOverlayPermissionGranted(this)) {
            toast(R.string.overlay_permission_required)
            // 别让插件干等到超时：立刻回一张空表，插件会提示"没有录到任何操作"
            sendRecorded(requestId, emptyList())
            return
        }

        recordRequestId = requestId
        recordedGestures.clear()
        recordDownX = 0f
        recordDownY = 0f
        recordTracking = false
        // 上限夹一刀：插件可能传 0 或离谱的大数，遮层不能因此赖着不走
        val duration = maxDurationMs.coerceIn(MIN_RECORD_MS, MAX_RECORD_MS)
        recordEndAt = SystemClock.uptimeMillis() + duration

        if (!attachRecordingWindow()) {
            recordRequestId = null
            toast(R.string.overlay_permission_required)
            sendRecorded(requestId, emptyList())
            return
        }
        scheduleRecordDeadline()

        // 遮层要独占触摸，控制器整个先隐身（窄条、收起标签、读数板都是）；
        // 录完 / 取消后由 restoreConsoleAfterRecording 按当前收起/展开状态还原
        railView?.animate()?.cancel()
        tabView?.animate()?.cancel()
        infoView?.animate()?.cancel()
        railView?.alpha = 0f
        tabView?.alpha = 0f
        infoView?.alpha = 0f
        railView?.let { setTouchable(it, false) }
        tabView?.let { setTouchable(it, false) }
        hideMarkers()

        LogBus.info("开始录制操作，最长 " + (duration / 1000L) + " 秒")
        Toast.makeText(this, "录制中：点屏幕记位置，点顶部「结束」交回", Toast.LENGTH_SHORT).show()
    }

    /** 建录制窗口。返回 false 表示加窗失败（权限被撤销等），调用方要回一张空表。 */
    private fun attachRecordingWindow(): Boolean {
        if (recordRoot != null) {
            return true
        }

        val themedContext = android.view.ContextThemeWrapper(this, R.style.ThemeOverlay_AutoTapper_Floating)
        val root = FrameLayout(themedContext)
        root.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        // 遮层本身全透明：用户得看清下面在点什么；只有顶部那条小条是不透明的墨色
        root.setBackgroundColor(Color.TRANSPARENT)

        val bar = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ef.color(this@OverlayService, R.color.ef_ink))
            setPadding(
                Ef.dp(this@OverlayService, 12),
                Ef.dp(this@OverlayService, 8),
                Ef.dp(this@OverlayService, 8),
                Ef.dp(this@OverlayService, 8)
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = Ef.dp(this@OverlayService, 64)
            }
            // 小条要自己吃掉落在它身上的触摸，否则点「结束」会被当成一次手势记下来
            setOnTouchListener { _, _ -> true }
        }

        val status = TextView(themedContext).apply {
            text = recordStatusText()
            setTextColor(Ef.color(this@OverlayService, R.color.ef_panel))
            textSize = 13f
            includeFontPadding = false
        }
        bar.addView(status)

        val stopView = TextView(themedContext).apply {
            text = "结束"
            setTextColor(Ef.color(this@OverlayService, R.color.ef_ink))
            textSize = 13f
            setBackgroundColor(Ef.color(this@OverlayService, R.color.ef_signal))
            setPadding(
                Ef.dp(this@OverlayService, 12),
                Ef.dp(this@OverlayService, 6),
                Ef.dp(this@OverlayService, 12),
                Ef.dp(this@OverlayService, 6)
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = Ef.dp(this@OverlayService, 12) }
            setOnClickListener { finishRecording(sendResult = true) }
        }
        bar.addView(stopView)
        root.addView(bar)
        root.setOnTouchListener { _, event -> handleRecordTouch(event) }

        if (!runCatching { windowManager.addView(root, fullscreenParams()) }.isSuccess) {
            return false
        }
        recordRoot = root
        recordStatusView = status
        return true
    }

    private fun scheduleRecordDeadline() {
        handler.removeCallbacks(recordDeadlineRunnable)
        val remaining = (recordEndAt - SystemClock.uptimeMillis()).coerceAtLeast(1L)
        handler.postDelayed(recordDeadlineRunnable, remaining)
    }

    private fun handleRecordTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                recordDownX = event.rawX
                recordDownY = event.rawY
                recordDownAt = event.downTime
                recordTracking = true
            }

            MotionEvent.ACTION_UP -> {
                if (recordTracking) {
                    recordTracking = false
                    appendRecordedGesture(
                        event.rawX,
                        event.rawY,
                        (event.eventTime - recordDownAt).coerceAtLeast(0L)
                    )
                }
            }

            MotionEvent.ACTION_CANCEL -> recordTracking = false
        }
        // 全程吃掉：录制期间这一下不给下层应用，避免"录到了但应用也响应了"这种双份动作
        return true
    }

    /** 按时间差与位移判定：按下到抬起 < 300 ms 且移动 < 24 dp 算点击，其余算滑动。 */
    private fun appendRecordedGesture(upX: Float, upY: Float, durationMs: Long) {
        val slop = Ef.dp(this, RECORD_TOUCH_SLOP_DP)
        val dx = abs(upX - recordDownX)
        val dy = abs(upY - recordDownY)
        val from = TapCoord(recordDownX.toInt(), recordDownY.toInt())
        val gesture = if (durationMs < RECORD_TAP_MAX_MS && dx < slop && dy < slop) {
            RecordedGesture(
                kind = RecordedGesture.Kind.TAP,
                from = from,
                to = from,
                durationMs = durationMs,
                holdMs = durationMs
            )
        } else {
            RecordedGesture(
                kind = RecordedGesture.Kind.SWIPE,
                from = from,
                to = TapCoord(upX.toInt(), upY.toInt()),
                durationMs = durationMs,
                holdMs = 0L
            )
        }
        recordedGestures.add(gesture)
        recordStatusView?.text = recordStatusText()

        val isSwipe = gesture.kind == RecordedGesture.Kind.SWIPE
        LogBus.info(
            "录到第 " + recordedGestures.size + " 条：" + (if (isSwipe) "滑动 " else "点击 ") +
                from.x + "," + from.y +
                (if (isSwipe) " → " + gesture.to.x + "," + gesture.to.y else "") +
                "（" + durationMs + " ms）"
        )
    }

    private fun recordStatusText(): String = "录制中 · 已 " + recordedGestures.size + " 条"

    /**
     * 收掉录制窗口。sendResult = true 时把已录内容广播回给请求方（用户点「结束」/ 到时长上限）；
     * false 用于请求方自己取消（插件协程被取消、服务即将销毁），此时那边没人接这个广播。
     */
    private fun finishRecording(sendResult: Boolean) {
        if (recordRoot == null) {
            return
        }
        val requestId = recordRequestId.orEmpty()
        val gestures = recordedGestures.toList()
        detachRecordingWindow()
        recordRequestId = null
        recordedGestures.clear()
        if (sendResult) {
            sendRecorded(requestId, gestures)
            Toast.makeText(this, "录制结束，共 " + gestures.size + " 条", Toast.LENGTH_SHORT).show()
            LogBus.info("录制结束，共 " + gestures.size + " 条手势")
        }
        restoreConsoleAfterRecording()
    }

    private fun cancelRecording() {
        if (recordRoot == null) {
            return
        }
        LogBus.info("录制被取消，已录到的 " + recordedGestures.size + " 条不回传")
        finishRecording(sendResult = false)
    }

    private fun detachRecordingWindow() {
        handler.removeCallbacks(recordDeadlineRunnable)
        recordRoot?.let { removeViewSafely(it) }
        recordRoot = null
        recordStatusView = null
        recordTracking = false
    }

    /** 录制期间把控制器整个藏起来了，这里按当前收起/展开状态还原，别让它一直隐身。 */
    private fun restoreConsoleAfterRecording() {
        if (railView == null || tabView == null) {
            return
        }
        applyAlpha(animate = true)
        // 触摸开关跟 setExpanded 保持一致：展开时窄条可点、收起时小标签可点
        railView?.let { setTouchable(it, expanded) }
        tabView?.let { setTouchable(it, !expanded) }
        refreshMarkers()
    }

    private fun sendRecorded(requestId: String, gestures: List<RecordedGesture>) {
        runCatching {
            sendBroadcast(
                Intent(AutoTapperConfig.ACTION_RECORDED)
                    .setPackage(packageName)
                    .putExtra(AutoTapperConfig.EXTRA_RECORD_REQUEST_ID, requestId)
                    .putExtra(
                        AutoTapperConfig.EXTRA_RECORDED_JSON,
                        RecordedGestureJson.encode(gestures)
                    )
            )
        }.onFailure { LogBus.warn("回传录制结果失败：" + it.javaClass.simpleName) }
    }

    /** @return 窗口确实被摘掉了（已经是 null 视为成功），失败时调用方要处理，否则会留下孤儿窗口。 */
    private fun removeViewSafely(view: View?): Boolean {
        view ?: return true
        return runCatching { windowManager.removeView(view) }.isSuccess
    }

    private fun overlayWindowType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private val screenWidth: Int
        get() = resources.displayMetrics.widthPixels

    private val screenHeight: Int
        get() = resources.displayMetrics.heightPixels

    private fun registerServiceReceiver() {
        val filter = IntentFilter().apply {
            addAction(AutoTapperConfig.ACTION_CLICK_STATE_CHANGED)
            addAction(AutoTapperConfig.ACTION_POINT_SELECTED)
            addAction(AutoTapperConfig.ACTION_CONTROLLER_ALPHA_CHANGED)
            addAction(AutoTapperConfig.ACTION_CANCEL_REGION_PICK)
            addAction(AutoTapperConfig.ACTION_STOP_RECORDING)
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

    /** 通知内容只跟「是否正在点击」有关；点击中每个间隔都会走一次状态广播，没必要重复刷新。 */
    private fun refreshNotification(force: Boolean = false) {
        if (!force && notifiedClicking == isClicking) {
            return
        }
        notifiedClicking = isClicking
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

        private const val ENTER_MS = 260L
        private const val EXIT_MS = 180L
        private const val ALPHA_MS = 220L
        private const val DIM_DELAY_MS = 4_000L
        private const val DIM_ALPHA_FLOOR = 0.32f

        private val SNAP = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        private val MECH = PathInterpolator(0.7f, 0f, 0.2f, 1f)

        private const val MARKER_WINDOW_ALPHA = 0.95f
        private const val BLINK_INTERVAL_MS = 620L

        private const val DEFAULT_RECORD_MAX_MS = 120_000L
        private const val MIN_RECORD_MS = 1_000L
        private const val MAX_RECORD_MS = 300_000L

        /** 按下到抬起短于这个时长、且位移小于 24dp，算一次点击而不是滑动。 */
        private const val RECORD_TAP_MAX_MS = 300L
        private const val RECORD_TOUCH_SLOP_DP = 24
    }
}