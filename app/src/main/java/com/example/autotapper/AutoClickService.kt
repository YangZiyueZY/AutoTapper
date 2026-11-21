package com.example.autotapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * 无障碍模式：仍然用无障碍服务的手势接口执行点击。
 * 点击循环本身在 [TapEngine] 里，这里只负责把手势送出去。
 */
class AutoClickService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false
    private var engine: TapEngine? = null
    private var serviceConnected = false

    private val executor = object : AccessibilityTapExecutor {
        override fun tap(x: Int, y: Int, durationMs: Long, onResult: (Boolean) -> Unit) {
            tapWithGesture(x, y, durationMs, onResult)
        }

        override fun cancel() = Unit
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AutoTapperConfig.ACTION_START_CLICKING -> startClicking()
                AutoTapperConfig.ACTION_STOP_CLICKING -> engine?.stop(showToast = true)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceConnected = true
        ensureEngine()
        registerControlReceiver()
        broadcastClickState(false)
        Toast.makeText(this, R.string.accessibility_ready, Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        engine?.stop()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        serviceConnected = false
        engine?.release()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        serviceConnected = false
        engine?.release()
        engine = null
        unregisterControlReceiver()
        super.onDestroy()
    }

    private fun ensureEngine(): TapEngine = engine ?: TapEngine(this, executor).also { engine = it }

    private fun startClicking() {
        // 切到 Shizuku 模式后，无障碍服务即使还连着也不应该再点
        if (AutoTapperConfig.getTapMode(this) != AutoTapperConfig.TAP_MODE_ACCESSIBILITY) {
            return
        }
        if (ensureEngine().start()) {
            Toast.makeText(this, R.string.click_started, Toast.LENGTH_SHORT).show()
        }
    }

    private fun tapWithGesture(x: Int, y: Int, durationMs: Long, onResult: (Boolean) -> Unit) {
        if (!serviceConnected) {
            onResult(false)
            return
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    Path().apply { moveTo(x.toFloat(), y.toFloat()) },
                    0,
                    durationMs
                )
            )
            .build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    onResult(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    onResult(false)
                }
            },
            handler
        )

        if (!dispatched) {
            onResult(false)
        }
    }

    private fun broadcastClickState(isRunning: Boolean) {
        sendBroadcast(
            Intent(AutoTapperConfig.ACTION_CLICK_STATE_CHANGED)
                .setPackage(packageName)
                .putExtra(AutoTapperConfig.EXTRA_IS_CLICKING, isRunning)
                .putExtra(AutoTapperConfig.EXTRA_CURRENT_POINT_INDEX, -1)
        )
    }

    private fun registerControlReceiver() {
        if (receiverRegistered) {
            return
        }

        val filter = IntentFilter().apply {
            addAction(AutoTapperConfig.ACTION_START_CLICKING)
            addAction(AutoTapperConfig.ACTION_STOP_CLICKING)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(controlReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterControlReceiver() {
        if (!receiverRegistered) {
            return
        }

        runCatching { unregisterReceiver(controlReceiver) }
        receiverRegistered = false
    }
}

/** 无障碍手势执行器：把 TapEngine 的一次点击交给 dispatchGesture。 */
interface AccessibilityTapExecutor : TapExecutor
