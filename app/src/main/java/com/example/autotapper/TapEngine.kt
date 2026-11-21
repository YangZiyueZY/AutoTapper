package com.example.autotapper

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlin.random.Random

/**
 * 一次点击的执行者。两种实现：
 * - [AccessibilityTapExecutor]：无障碍服务的 dispatchGesture；
 * - [ShizukuTapExecutor]：Shizuku 提权执行 input 命令。
 */
interface TapExecutor {
    /** 在 (x, y) 按下并保持 [durationMs] 后抬起；onResult(true) 表示点击已经执行。 */
    fun tap(x: Int, y: Int, durationMs: Long, onResult: (Boolean) -> Unit)

    /** 停止后取消尚未完成的点击。 */
    fun cancel()
}

/**
 * 多点轮流点击的循环本身。点位的顺序、间隔、轮数、状态广播都集中在这里，
 * 与「用哪种方式真正点下去」解耦，避免两套循环各写一遍。
 */
class TapEngine(
    private val context: Context,
    private val executor: TapExecutor
) {
    private val handler = Handler(Looper.getMainLooper())

    var isClicking = false
        private set

    /** 循环停止（用户停止、跑完轮数或出错）时回调，用于让承载它的服务收尾。 */
    var onStopped: (() -> Unit)? = null

    private var pointIndex = 0
    private var roundsCompleted = 0

    private var points: List<TapPoint> = emptyList()
    private var intervalMs = AutoTapperConfig.DEFAULT_INTERVAL_MS
    private var randomExtraMs = AutoTapperConfig.DEFAULT_RANDOM_EXTRA_MS
    private var repeatCount = AutoTapperConfig.DEFAULT_REPEAT_COUNT
    private var tapDurationMs = AutoTapperConfig.DEFAULT_TAP_DURATION_MS

    fun start(): Boolean {
        // 双击 GO 或状态广播乱序时不要跑出两个循环
        if (isClicking) {
            return true
        }

        points = AutoTapperConfig.loadPoints(context)
        intervalMs = AutoTapperConfig.getIntervalMs(context)
        randomExtraMs = AutoTapperConfig.getRandomExtraMs(context)
        repeatCount = AutoTapperConfig.getRepeatCount(context)
        tapDurationMs = AutoTapperConfig.getTapDurationMs(context)

        if (points.isEmpty()) {
            toast(R.string.point_required)
            return false
        }

        pointIndex = 0
        roundsCompleted = 0
        isClicking = true
        handler.removeCallbacksAndMessages(null)
        broadcastState(true, 0)
        performClick()
        return true
    }

    fun stop(showToast: Boolean = false, messageRes: Int? = null) {
        val wasClicking = isClicking
        isClicking = false
        handler.removeCallbacksAndMessages(null)
        executor.cancel()
        broadcastState(false, -1)
        onStopped?.invoke()
        if (showToast && (wasClicking || messageRes != null)) {
            toast(messageRes ?: R.string.click_stopped)
        }
    }

    fun release() {
        stop()
        handler.removeCallbacksAndMessages(null)
    }

    private fun performClick() {
        if (!isClicking) {
            return
        }

        if (points.isEmpty()) {
            stop(showToast = true, messageRes = R.string.point_required)
            return
        }

        if (repeatCount > 0 && roundsCompleted >= repeatCount) {
            val completed = roundsCompleted
            isClicking = false
            handler.removeCallbacksAndMessages(null)
            broadcastState(false, -1)
            onStopped?.invoke()
            toast(context.getString(R.string.click_completed, completed))
            return
        }

        val index = pointIndex
        val point = points[index]
        broadcastState(true, index)

        executor.tap(point.x, point.y, tapDurationMs) { success ->
            if (!isClicking) {
                return@tap
            }
            if (!success) {
                stop(showToast = true, messageRes = R.string.click_dispatch_failed)
                return@tap
            }

            pointIndex++
            if (pointIndex >= points.size) {
                pointIndex = 0
                roundsCompleted++
            }

            val randomDelay = if (randomExtraMs > 0) Random.nextLong(randomExtraMs + 1) else 0L
            handler.postDelayed({ performClick() }, intervalMs + randomDelay)
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(context, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun broadcastState(running: Boolean, index: Int) {
        context.sendBroadcast(
            Intent(AutoTapperConfig.ACTION_CLICK_STATE_CHANGED)
                .setPackage(context.packageName)
                .putExtra(AutoTapperConfig.EXTRA_IS_CLICKING, running)
                .putExtra(AutoTapperConfig.EXTRA_CURRENT_POINT_INDEX, index)
        )
    }
}
