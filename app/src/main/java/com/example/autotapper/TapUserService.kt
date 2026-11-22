package com.example.autotapper

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.MotionEvent.PointerCoords
import android.view.MotionEvent.PointerProperties

/**
 * 运行在 Shizuku 提供的独立进程里，身份是 shell(uid 2000) 或 root(uid 0)。
 *
 * 这不是一个普通的应用进程：getContentResolver / registerReceiver 等不可用，
 * 但 non-SDK API 限制在这里不生效，所以可以直接注入 MotionEvent / KeyEvent。
 * 注入不可用时回退到 input 命令。
 */
class TapUserService : ITapService.Stub() {

    override fun destroy() {
        Log.i(TAG, "destroy: pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()}")
        System.exit(0)
    }

    override fun tap(x: Int, y: Int, durationMs: Int): Boolean = try {
        injectTap(x, y, durationMs)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "inject tap failed, fallback to input command", t)
        shell("input swipe $x $y $x $y ${durationMs.coerceAtLeast(1)}")
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean = try {
        injectSwipe(x1, y1, x2, y2, durationMs)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "inject swipe failed, fallback to input command", t)
        shell("input swipe $x1 $y1 $x2 $y2 ${durationMs.coerceAtLeast(1)}")
    }

    override fun multiTouch(xs: IntArray?, ys: IntArray?, holdMs: Int): Boolean {
        if (xs == null || ys == null || xs.size != ys.size) return false
        val count = minOf(xs.size, MAX_POINTERS)
        if (count <= 0) return false
        return try {
            injectMultiTouch(xs.copyOf(count), ys.copyOf(count), holdMs)
            true
        } catch (t: Throwable) {
            // input 命令不支持多指，这里只能如实返回失败
            Log.w(TAG, "inject multi touch failed", t)
            false
        }
    }

    override fun keyEvent(keyCode: Int): Boolean = try {
        injectKey(keyCode)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "inject key failed, fallback to input command", t)
        shell("input keyevent $keyCode")
    }

    override fun inputText(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        // 中文等非 ASCII 字符只能用 adb 的 input text，需要把空格转义成 %s
        val escaped = text.replace(" ", "%s").replace("'", "")
        if (shell("input text '$escaped'")) return true
        return injectTextFallback(text)
    }

    // ------------------------------------------------------------ 注入实现

    private fun inputManagerInject(): java.lang.reflect.Method {
        val managerClass = Class.forName("android.hardware.input.InputManager")
        val manager = managerClass.getMethod("getInstance").invoke(null)
        val method = managerClass.getMethod(
            "injectInputEvent",
            android.view.InputEvent::class.java,
            Int::class.javaPrimitiveType
        )
        return method.apply { isAccessible = true }
    }

    private fun inject(event: MotionEvent) {
        try {
            inject.invoke(null, event, INJECT_MODE_ASYNC)
        } finally {
            event.recycle()
        }
    }

    private val inject: java.lang.reflect.Method by lazy { inputManagerInject() }

    private fun newMotion(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float
    ): MotionEvent = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
        source = InputDevice.SOURCE_TOUCHSCREEN
    }

    private fun injectTap(x: Int, y: Int, durationMs: Int) {
        val downTime = SystemClock.uptimeMillis()
        inject(newMotion(downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat()))
        if (durationMs > 0) SystemClock.sleep(durationMs.toLong())
        inject(newMotion(downTime, downTime + durationMs, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat()))
    }

    private fun injectSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        val total = durationMs.coerceAtLeast(1)
        val steps = SWIPE_STEPS
        val downTime = SystemClock.uptimeMillis()
        inject(newMotion(downTime, downTime, MotionEvent.ACTION_DOWN, x1.toFloat(), y1.toFloat()))
        for (i in 1..steps) {
            val fraction = i.toFloat() / steps
            val x = x1 + (x2 - x1) * fraction
            val y = y1 + (y2 - y1) * fraction
            val at = downTime + total.toLong() * i / steps
            inject(newMotion(downTime, at, MotionEvent.ACTION_MOVE, x, y))
            SystemClock.sleep((total.toLong() / steps).coerceAtLeast(1L))
        }
        inject(newMotion(downTime, downTime + total, MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat()))
    }

    /**
     * 多指：ACTION_DOWN(第 1 指) → ACTION_POINTER_DOWN(逐指) → 保持 → ACTION_POINTER_UP → ACTION_UP。
     * 这样系统收到的是真实的"多指同时按住"，而不是几次快速单点。
     */
    private fun injectMultiTouch(xs: IntArray, ys: IntArray, holdMs: Int) {
        val count = xs.size
        val props = Array(count) { PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }
        val coords = Array(count) { PointerCoords().apply { x = xs[it].toFloat(); y = ys[it].toFloat(); pressure = 1f } }
        val downTime = SystemClock.uptimeMillis()

        fun event(action: Int): MotionEvent = MotionEvent.obtain(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            count,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )

        inject(event(MotionEvent.ACTION_DOWN or (0 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)))
        for (i in 1 until count) {
            inject(event(MotionEvent.ACTION_POINTER_DOWN or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)))
        }
        SystemClock.sleep(holdMs.toLong().coerceAtLeast(1L))
        for (i in count - 1 downTo 1) {
            inject(event(MotionEvent.ACTION_POINTER_UP or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)))
        }
        inject(event(MotionEvent.ACTION_UP))
    }

    private fun injectKey(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0).apply {
            source = InputDevice.SOURCE_KEYBOARD
        }
        val up = KeyEvent(now, now + 10, KeyEvent.ACTION_UP, keyCode, 0).apply {
            source = InputDevice.SOURCE_KEYBOARD
        }
        inject.invoke(null, down, INJECT_MODE_ASYNC)
        inject.invoke(null, up, INJECT_MODE_ASYNC)
    }

    /** 注入方式输入中文：拆成一个个 KEYCODE 不现实，这里如实返回失败，由上层退到 input text。 */
    private fun injectTextFallback(text: String): Boolean {
        Log.w(TAG, "inputText: 注入方式不支持直接输入文本，已尝试 input text")
        return false
    }

    /** 回退方案：input 命令。 */
    private fun shell(command: String): Boolean = try {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        try {
            // 必须把输出读完，否则管道写满会让子进程卡住
            process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor() == 0
        } finally {
            process.destroy()
        }
    } catch (t: Throwable) {
        Log.w(TAG, "input command failed: $command", t)
        false
    }

    private companion object {
        const val TAG = "TapUserService"

        /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC */
        const val INJECT_MODE_ASYNC = 2

        /** 系统手势最多 10 指 */
        const val MAX_POINTERS = 10

        /** 滑动插值步数：步数越多越平滑，但每步都要 sleep 一次 */
        const val SWIPE_STEPS = 12
    }
}