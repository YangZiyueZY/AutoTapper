package com.example.autotapper

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent

/**
 * 运行在 Shizuku 提供的独立进程里，身份是 shell(uid 2000) 或 root(uid 0)。
 *
 * 这不是一个普通的应用进程：getContentResolver / registerReceiver 等不可用，
 * 但 non-SDK API 限制在这里不生效，所以可以直接注入 MotionEvent。
 * 注入不可用时回退到 input 命令。
 */
class TapUserService : ITapService.Stub() {

    override fun destroy() {
        Log.i(TAG, "destroy: pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()}")
        System.exit(0)
    }

    override fun tap(x: Int, y: Int, durationMs: Int): Boolean {
        return tapByInject(x, y, durationMs)
    }

    /**
     * 直接注入按下 / 抬起事件：耗时约 1ms，按住时长精确，
     * 适合 100ms 级别的连续点击。
     */
    private fun tapByInject(x: Int, y: Int, durationMs: Int): Boolean = try {
        val managerClass = Class.forName("android.hardware.input.InputManager")
        val manager = managerClass.getMethod("getInstance").invoke(null)
        val inject = managerClass.getMethod(
            "injectInputEvent",
            android.view.InputEvent::class.java,
            Int::class.javaPrimitiveType
        )

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0
        )
        val up = MotionEvent.obtain(
            downTime, downTime + durationMs, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0
        )
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        up.source = InputDevice.SOURCE_TOUCHSCREEN

        try {
            inject.invoke(manager, down, INJECT_MODE_ASYNC)
            if (durationMs > 0) {
                SystemClock.sleep(durationMs.toLong())
            }
            inject.invoke(manager, up, INJECT_MODE_ASYNC)
        } finally {
            down.recycle()
            up.recycle()
        }
        true
    } catch (t: Throwable) {
        Log.w(TAG, "inject failed, fallback to input command", t)
        tapByShell(x, y, durationMs)
    }

    /** 回退方案：input 命令。按住时长用相同起止点的 swipe 表达。 */
    private fun tapByShell(x: Int, y: Int, durationMs: Int): Boolean = try {
        val command = if (durationMs > 0) {
            "input swipe $x $y $x $y $durationMs"
        } else {
            "input tap $x $y"
        }
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
        Log.w(TAG, "input command failed", t)
        false
    }

    private companion object {
        const val TAG = "TapUserService"

        /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC */
        const val INJECT_MODE_ASYNC = 2
    }
}
