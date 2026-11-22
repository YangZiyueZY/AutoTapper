package com.example.autotapper.plugin.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.autotapper.AutoTapperConfig
import com.example.autotapper.LogBus
import com.example.autotapper.OverlayService
import com.example.autotapper.PermissionUtils
import com.example.autotapper.R
import com.example.autotapper.engine.AutomationExecutor
import com.example.autotapper.engine.RegionStore
import com.example.autotapper.engine.TimeService
import com.example.autotapper.model.Region
import com.example.autotapper.model.TapCoord
import com.example.autotapper.plugin.CaptureApi
import com.example.autotapper.plugin.ClockApi
import com.example.autotapper.plugin.ExecutorApi
import com.example.autotapper.plugin.GlobalAction
import com.example.autotapper.plugin.KeyCode
import com.example.autotapper.plugin.LogApi
import com.example.autotapper.plugin.OcrApi
import com.example.autotapper.plugin.OcrResult
import com.example.autotapper.plugin.RegionApi
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID

/**
 * 把主程序的注入器转接成插件看到的 [ExecutorApi]。
 * 插件不知道自己跑在无障碍还是 Shizuku 上，也就不会因为换通道而行为不同。
 */
class PluginExecutorAdapter(private val host: AutomationExecutor) : ExecutorApi {

    override fun tap(x: Int, y: Int, durationMs: Long, onResult: (Boolean) -> Unit) =
        host.tap(x, y, durationMs, onResult)

    override fun swipe(from: TapCoord, to: TapCoord, durationMs: Long, onResult: (Boolean) -> Unit) =
        host.swipe(from, to, durationMs, onResult)

    override fun multiTouch(points: List<TapCoord>, holdMs: Long, onResult: (Boolean) -> Unit) =
        host.multiTouch(points, holdMs, onResult)

    override fun globalAction(action: GlobalAction, onResult: (Boolean) -> Unit) {
        val mapped = when (action) {
            GlobalAction.BACK -> AutomationExecutor.GlobalAction.BACK
            GlobalAction.HOME -> AutomationExecutor.GlobalAction.HOME
        }
        host.globalAction(mapped, onResult)
    }

    override fun keyEvent(key: KeyCode, onResult: (Boolean) -> Unit) {
        val mapped = when (key) {
            KeyCode.MENU -> AutomationExecutor.KeyCode.MENU
            KeyCode.ENTER -> AutomationExecutor.KeyCode.ENTER
            KeyCode.DEL -> AutomationExecutor.KeyCode.DEL
        }
        host.keyEvent(mapped, onResult)
    }

    override fun inputText(text: String, onResult: (Boolean) -> Unit) = host.inputText(text, onResult)

    override fun maxTapsPerSecond(): Int = host.maxTapsPerSecond()
}

/** 插件写日志时带上插件名，日志页一眼能看出是谁写的。 */
class PluginLogAdapter(private val pluginName: String) : LogApi {

    override fun info(message: String) = LogBus.info("[$pluginName] $message")
    override fun warn(message: String) = LogBus.warn("[$pluginName] $message")
    override fun error(message: String) = LogBus.error("[$pluginName] $message")
}

class PluginClockAdapter(private val context: Context) : ClockApi {

    override fun nowMs(): Long = TimeService.nowMs(context)

    override fun offsetMs(): Long = TimeService.offsetMs(context)

    override fun isBeijingTime(): Boolean = true
}

/** 区域读写直接落到主程序的 RegionStore；画区域走悬浮层，结果用广播回传。 */
class PluginRegionAdapter(private val context: Context) : RegionApi {

    override fun load(): List<Region> = RegionStore.load(context)

    override fun save(regions: List<Region>) = RegionStore.save(context, regions)

    override suspend fun pick(label: String): Region? = suspendCancellableCoroutine { cont ->
        if (!PermissionUtils.isOverlayPermissionGranted(context)) {
            Toast.makeText(context, R.string.region_pick_permission_required, Toast.LENGTH_SHORT).show()
            LogBus.warn("插件请求画区域（" + label + "）失败：没有悬浮窗权限")
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val session = RegionPickSession(context, UUID.randomUUID().toString(), cont)
        // 协程被取消（规则被停、步骤超时）时也要把悬浮层关掉，避免留下孤儿窗口
        cont.invokeOnCancellation { session.abort() }
        session.start(label)
    }
}

/**
 * 一次"请用户画个区域"的等待会话。
 *
 * 为什么单独写一个类而不是在 suspendCancellableCoroutine 里写局部函数：
 * BroadcastReceiver 是个对象表达式，里面引用不到后面才声明的局部函数（Kotlin 不允许），
 * 而 settle/cleanup 又要同时被 receiver、超时回调、取消回调三处调用。
 * 放进类里让状态与回调生命周期绑定在同一个对象上，读起来也更清楚。
 */
private class RegionPickSession(
    private val context: Context,
    private val requestId: String,
    private val cont: kotlinx.coroutines.CancellableContinuation<Region?>
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var settled = false
    private var registered = false
    private var timeout: Runnable? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.getStringExtra(AutoTapperConfig.EXTRA_PICK_REQUEST_ID) != requestId) return
            when (intent.action) {
                AutoTapperConfig.ACTION_REGION_PICKED -> {
                    val x = intent.getIntExtra(AutoTapperConfig.EXTRA_REGION_X, -1)
                    val y = intent.getIntExtra(AutoTapperConfig.EXTRA_REGION_Y, -1)
                    val w = intent.getIntExtra(AutoTapperConfig.EXTRA_REGION_W, -1)
                    val h = intent.getIntExtra(AutoTapperConfig.EXTRA_REGION_H, -1)
                    if (x < 0 || y < 0 || w < 1 || h < 1) {
                        LogBus.warn("画区域回传的坐标无效，按取消处理")
                        settle(null)
                    } else {
                        settle(
                            Region(
                                id = requestId,
                                label = intent.getStringExtra(AutoTapperConfig.EXTRA_REGION_LABEL).orEmpty(),
                                x = x,
                                y = y,
                                w = w,
                                h = h
                            )
                        )
                    }
                }

                AutoTapperConfig.ACTION_CANCEL_REGION_PICK -> settle(null)
            }
        }
    }

    fun start(label: String) {
        val filter = IntentFilter().apply {
            addAction(AutoTapperConfig.ACTION_REGION_PICKED)
            addAction(AutoTapperConfig.ACTION_CANCEL_REGION_PICK)
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            true
        }.getOrElse {
            LogBus.warn("注册画区域结果接收器失败：" + it.javaClass.simpleName)
            false
        }
        if (!ok) {
            settle(null)
            return
        }
        registered = true

        val runnable = Runnable {
            if (!settled) {
                LogBus.warn("插件请求画区域（" + label + "）超时，按取消处理")
                settle(null)
                sendCancel()
            }
        }
        timeout = runnable
        mainHandler.postDelayed(runnable, REGION_PICK_TIMEOUT_MS)

        val started = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java)
                    .setAction(AutoTapperConfig.ACTION_START_REGION_PICK)
                    .putExtra(AutoTapperConfig.EXTRA_PICK_REQUEST_ID, requestId)
                    .putExtra(AutoTapperConfig.EXTRA_REGION_LABEL, label)
            )
            true
        }.getOrElse {
            LogBus.warn("拉起画区域悬浮层失败：" + it.javaClass.simpleName)
            false
        }
        if (!started) settle(null)
    }

    /** 协程取消时调用：不 resume（协程已经结束），只清资源并关掉悬浮层。 */
    fun abort() {
        if (!settled) {
            settled = true
            cleanup()
        }
        sendCancel()
    }

    private fun settle(region: Region?) {
        if (settled) return
        settled = true
        cleanup()
        cont.resume(region)
    }

    private fun cleanup() {
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
        timeout?.let { runCatching { mainHandler.removeCallbacks(it) } }
        timeout = null
    }

    private fun sendCancel() {
        runCatching {
            context.sendBroadcast(
                Intent(AutoTapperConfig.ACTION_CANCEL_REGION_PICK)
                    .setPackage(context.packageName)
                    .putExtra(AutoTapperConfig.EXTRA_PICK_REQUEST_ID, requestId)
            )
        }
    }

    private companion object {
        const val REGION_PICK_TIMEOUT_MS = 120_000L
    }
}

/**
 * 截图能力要在拿到 MediaProjection 授权之后才有意义。
 * 未接入时 isReady() 一定是 false，插件据此走失败分支，而不是拿到一张黑图。
 */
class UnavailableCaptureApi : CaptureApi {
    override fun isReady(): Boolean = false
    override fun grabRegion(region: Region): Bitmap? = null
    override fun grabFullscreen(): Bitmap? = null
}

/**
 * 识字服务：token 配好之前 isConfigured() 为 false。
 * 实现见里程碑 M5 的 OcrService（云端 PaddleOCR-VL）。
 */
class UnconfiguredOcrApi : OcrApi {
    override fun isConfigured(): Boolean = false
    override suspend fun recognize(bitmap: Bitmap): OcrResult =
        OcrResult("", emptyList(), 0L, error = "识字服务未配置")
}