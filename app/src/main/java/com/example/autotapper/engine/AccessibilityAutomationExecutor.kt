package com.example.autotapper.engine

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import com.example.autotapper.model.TapCoord

/**
 * 无障碍通道：dispatchGesture 注入点/滑/多指，performGlobalAction 做返回与桌面。
 *
 * 系统限制（界面上要如实标注，不要假装能做到）：
 * - 手势的 duration 上限 60s 且必须 > 0；
 * - 连续 dispatchGesture 不能重叠，实际频率受系统手势队列限制；
 * - 没有"菜单键"的全局动作，只能用 [AutomationExecutor.KeyCode.MENU] 走 Shizuku。
 */
class AccessibilityAutomationExecutor(
    private val service: AccessibilityService
) : AutomationExecutor {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var connected = true

    override fun isReady(): Boolean = connected

    fun onServiceDisconnected() {
        connected = false
    }

    override fun maxTapsPerSecond(): Int = MAX_TAPS_PER_SECOND

    override fun tap(x: Int, y: Int, durationMs: Long, onResult: (Boolean) -> Unit) {
        val stroke = GestureDescription.StrokeDescription(
            Path().apply { moveTo(x.toFloat(), y.toFloat()) },
            0L,
            durationMs.coerceIn(1L, MAX_STROKE_MS)
        )
        dispatch(listOf(stroke), onResult)
    }

    override fun swipe(from: TapCoord, to: TapCoord, durationMs: Long, onResult: (Boolean) -> Unit) {
        val path = Path().apply {
            moveTo(from.x.toFloat(), from.y.toFloat())
            lineTo(to.x.toFloat(), to.y.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(1L, MAX_STROKE_MS))
        dispatch(listOf(stroke), onResult)
    }

    override fun multiTouch(points: List<TapCoord>, holdMs: Long, onResult: (Boolean) -> Unit) {
        if (points.isEmpty()) {
            onResult(false)
            return
        }
        val strokes = points.take(MAX_POINTERS).map { point ->
            GestureDescription.StrokeDescription(
                Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) },
                0L,
                holdMs.coerceIn(1L, MAX_STROKE_MS)
            )
        }
        dispatch(strokes, onResult)
    }

    override fun globalAction(action: AutomationExecutor.GlobalAction, onResult: (Boolean) -> Unit) {
        val globalAction = when (action) {
            AutomationExecutor.GlobalAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            AutomationExecutor.GlobalAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            AutomationExecutor.GlobalAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
        }
        onResult(runCatching { service.performGlobalAction(globalAction) }.getOrDefault(false))
    }

    /** 无障碍没有菜单键的全局动作，如实返回失败，由上层提示改用 Shizuku。 */
    override fun keyEvent(key: AutomationExecutor.KeyCode, onResult: (Boolean) -> Unit) {
        onResult(false)
    }

    override fun inputText(text: String, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            onResult(false)
            return
        }
        runCatching {
            val root: AccessibilityNodeInfo = service.rootInActiveWindow ?: return@runCatching false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@runCatching false
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.onSuccess { onResult(it) }.onFailure { onResult(false) }
    }

    override fun cancel() {
        handler.removeCallbacksAndMessages(null)
    }

    private fun dispatch(strokes: List<GestureDescription.StrokeDescription>, onResult: (Boolean) -> Unit) {
        if (!connected) {
            onResult(false)
            return
        }
        val builder = GestureDescription.Builder()
        strokes.forEach { builder.addStroke(it) }

        val dispatched = runCatching {
            service.dispatchGesture(
                builder.build(),
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        onResult(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        onResult(false)
                    }
                },
                handler
            )
        }.getOrDefault(false)

        if (!dispatched) {
            onResult(false)
        }
    }

    private companion object {
        const val MAX_STROKE_MS = 60_000L
        const val MAX_POINTERS = 10

        /** dispatchGesture 的实测经验上限：再高系统会丢弃或合并手势 */
        const val MAX_TAPS_PER_SECOND = 20
    }
}
