package com.example.autotapper.plugin.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.autotapper.AutoTapperConfig
import com.example.autotapper.LogBus
import com.example.autotapper.OverlayService
import com.example.autotapper.PermissionUtils
import com.example.autotapper.model.TapCoord
import com.example.autotapper.plugin.RecordApi
import com.example.autotapper.plugin.RecordedGesture
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject

/**
 * 录制能力的宿主实现：把插件的"帮我录一段"转成悬浮层的录制模式，结果用广播回传。
 *
 * 为什么不让插件自己碰悬浮窗：插件编译期只有纯 JVM 的 SDK，碰不到 WindowManager；
 * 而且录制窗口必须在服务里活着，跟着进程走，不能挂在插件的执行协程上。
 * 这条链路和画区域（[PluginRegionAdapter.pick]）完全同构：请求方注册接收器 → 拉起服务
 * → 服务回传 → settle 时注销接收器，只多了一个"录到几条手势"的 JSON。
 */
class RecorderBridge(private val context: Context) : RecordApi {

    /** 没有悬浮窗权限时连遮层都建不出来，直接说 false，别让插件以为能录。 */
    override fun isSupported(): Boolean =
        runCatching { PermissionUtils.isOverlayPermissionGranted(context) }.getOrDefault(false)

    override suspend fun record(maxDurationMs: Long): List<RecordedGesture> =
        suspendCancellableCoroutine { cont ->
            if (!isSupported()) {
                runCatching {
                    Toast.makeText(context, "需要悬浮窗权限才能录制", Toast.LENGTH_SHORT).show()
                }
                LogBus.warn("插件请求录制失败：没有悬浮窗权限")
                cont.resume(emptyList())
                return@suspendCancellableCoroutine
            }
            val session = RecordSession(context, UUID.randomUUID().toString(), maxDurationMs, cont)
            // 协程被取消（规则被停、步骤超时）时也要把遮层关掉，否则会留下一个抓不住的孤儿窗口
            cont.invokeOnCancellation { session.abort() }
            session.start()
        }
}

/**
 * 一次"请用户录一段操作"的等待会话。
 *
 * 和 RegionPickSession 一样单独成类的原因：BroadcastReceiver 是对象表达式，
 * 里面引用不到后面才声明的局部函数；而 settle / cleanup 要同时被 receiver、
 * 超时回调、取消回调三处调用，放进类里状态才收得拢。
 */
private class RecordSession(
    private val context: Context,
    private val requestId: String,
    private val maxDurationMs: Long,
    private val cont: CancellableContinuation<List<RecordedGesture>>
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var settled = false
    private var registered = false
    private var timeout: Runnable? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.getStringExtra(AutoTapperConfig.EXTRA_RECORD_REQUEST_ID) != requestId) return
            if (intent.action != AutoTapperConfig.ACTION_RECORDED) return
            settle(RecordedGestureJson.decode(intent.getStringExtra(AutoTapperConfig.EXTRA_RECORDED_JSON)))
        }
    }

    fun start() {
        val filter = IntentFilter().apply { addAction(AutoTapperConfig.ACTION_RECORDED) }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            true
        }.getOrElse {
            LogBus.warn("注册录制结果接收器失败：" + it.javaClass.simpleName)
            false
        }
        if (!ok) {
            settle(emptyList())
            return
        }
        registered = true

        // 超时给得比录制上限宽一截：遮层到点会自己回传，这里只兜"悬浮窗被系统收走"这类事故
        val runnable = Runnable {
            if (!settled) {
                LogBus.warn("插件请求录制超时（" + waitMs() + " ms），按空结果处理")
                settle(emptyList())
                sendStop()
            }
        }
        timeout = runnable
        mainHandler.postDelayed(runnable, waitMs())

        val started = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, OverlayService::class.java)
                    .setAction(AutoTapperConfig.ACTION_START_RECORDING)
                    .putExtra(AutoTapperConfig.EXTRA_RECORD_REQUEST_ID, requestId)
                    .putExtra(AutoTapperConfig.EXTRA_RECORD_MAX_MS, maxDurationMs)
            )
            true
        }.getOrElse {
            LogBus.warn("拉起录制遮层失败：" + it.javaClass.simpleName)
            false
        }
        if (!started) settle(emptyList())
    }

    /** 协程取消时调用：不 resume（协程已经结束），只清资源并让服务收掉遮层。 */
    fun abort() {
        if (!settled) {
            settled = true
            cleanup()
        }
        sendStop()
    }

    private fun settle(gestures: List<RecordedGesture>) {
        if (settled) return
        settled = true
        cleanup()
        if (cont.isActive) cont.resume(gestures)
    }

    private fun cleanup() {
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
        timeout?.let { runCatching { mainHandler.removeCallbacks(it) } }
        timeout = null
    }

    private fun sendStop() {
        runCatching {
            context.sendBroadcast(
                Intent(AutoTapperConfig.ACTION_STOP_RECORDING)
                    .setPackage(context.packageName)
                    .putExtra(AutoTapperConfig.EXTRA_RECORD_REQUEST_ID, requestId)
            )
        }
    }

    private fun waitMs(): Long = maxDurationMs.coerceIn(1_000L, MAX_WAIT_MS) + WAIT_SLACK_MS

    private companion object {
        const val MAX_WAIT_MS = 10L * 60L * 1000L
        const val WAIT_SLACK_MS = 6_000L
    }
}

/**
 * 录制结果的线格式。编码在悬浮层那边、解码在这里，两边共用同一份 key，
 * 免得改了字段名只改一头，录到的东西静默变成空表。
 *
 * JSON：[{"kind":"tap","fromX":1,"fromY":2,"toX":1,"toY":2,"durationMs":60,"holdMs":60}, ...]
 */
internal object RecordedGestureJson {

    private const val KIND_TAP = "tap"
    private const val KIND_SWIPE = "swipe"

    private const val KEY_KIND = "kind"
    private const val KEY_FROM_X = "fromX"
    private const val KEY_FROM_Y = "fromY"
    private const val KEY_TO_X = "toX"
    private const val KEY_TO_Y = "toY"
    private const val KEY_DURATION_MS = "durationMs"
    private const val KEY_HOLD_MS = "holdMs"

    fun encode(gestures: List<RecordedGesture>): String = runCatching {
        val array = JSONArray()
        gestures.forEach { gesture ->
            array.put(
                JSONObject()
                    .put(KEY_KIND, if (gesture.kind == RecordedGesture.Kind.SWIPE) KIND_SWIPE else KIND_TAP)
                    .put(KEY_FROM_X, gesture.from.x)
                    .put(KEY_FROM_Y, gesture.from.y)
                    .put(KEY_TO_X, gesture.to.x)
                    .put(KEY_TO_Y, gesture.to.y)
                    .put(KEY_DURATION_MS, gesture.durationMs)
                    .put(KEY_HOLD_MS, gesture.holdMs)
            )
        }
        array.toString()
    }.getOrElse {
        LogBus.warn("录制结果编码失败：" + it.javaClass.simpleName)
        "[]"
    }

    fun decode(raw: String?): List<RecordedGesture> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val kind = if (obj.optString(KEY_KIND) == KIND_SWIPE) {
                        RecordedGesture.Kind.SWIPE
                    } else {
                        RecordedGesture.Kind.TAP
                    }
                    val from = TapCoord(obj.optInt(KEY_FROM_X, 0), obj.optInt(KEY_FROM_Y, 0))
                    val to = TapCoord(obj.optInt(KEY_TO_X, from.x), obj.optInt(KEY_TO_Y, from.y))
                    add(
                        RecordedGesture(
                            kind = kind,
                            from = from,
                            to = to,
                            durationMs = obj.optLong(KEY_DURATION_MS, 0L),
                            holdMs = obj.optLong(KEY_HOLD_MS, 0L)
                        )
                    )
                }
            }
        }.getOrElse {
            LogBus.warn("录制结果解析失败，按空表处理：" + it.javaClass.simpleName)
            emptyList()
        }
    }
}

/**
 * 录制能力没有接上时的空实现：isSupported 为 false、录制返回空表。
 * 插件据此提示"需要悬浮窗权限"，而不是拿到一个永远不回调的挂起。
 */
class UnavailableRecordApi : RecordApi {
    override fun isSupported(): Boolean = false
    override suspend fun record(maxDurationMs: Long): List<RecordedGesture> = emptyList()
}
