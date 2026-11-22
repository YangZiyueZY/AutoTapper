package com.example.autotapper.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.example.autotapper.LogBus
import com.example.autotapper.ShizukuManager
import com.example.autotapper.model.TapCoord
import java.util.concurrent.Executors

/**
 * Shizuku 通道：所有注入都在 shell/root 身份的 UserService 里完成。
 * binder 调用是同步的，所以统一丢到单线程 executor 上跑，再把结果 post 回主线程。
 */
class ShizukuAutomationExecutor(private val context: Context) : AutomationExecutor {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "shizuku-inject").apply { isDaemon = true }
    }
    private val handler = Handler(Looper.getMainLooper())

    override fun isReady(): Boolean = ShizukuManager.isServiceConnected()

    override fun maxTapsPerSecond(): Int = MAX_TAPS_PER_SECOND

    override fun tap(x: Int, y: Int, durationMs: Long, onResult: (Boolean) -> Unit) {
        runAsync(onResult) { ShizukuManager.tap(x, y, durationMs) }
    }

    override fun swipe(from: TapCoord, to: TapCoord, durationMs: Long, onResult: (Boolean) -> Unit) {
        runAsync(onResult) {
            ShizukuManager.swipe(from.x, from.y, to.x, to.y, durationMs)
        }
    }

    override fun multiTouch(points: List<TapCoord>, holdMs: Long, onResult: (Boolean) -> Unit) {
        if (points.isEmpty()) {
            onResult(false)
            return
        }
        runAsync(onResult) {
            ShizukuManager.multiTouch(
                points.map { it.x }.toIntArray(),
                points.map { it.y }.toIntArray(),
                holdMs
            )
        }
    }

    override fun globalAction(action: AutomationExecutor.GlobalAction, onResult: (Boolean) -> Unit) {
        val keyCode = when (action) {
            AutomationExecutor.GlobalAction.BACK -> 4
            AutomationExecutor.GlobalAction.HOME -> 3
            AutomationExecutor.GlobalAction.RECENTS -> 187
        }
        runAsync(onResult) { ShizukuManager.keyEvent(keyCode) }
    }

    override fun keyEvent(key: AutomationExecutor.KeyCode, onResult: (Boolean) -> Unit) {
        val keyCode = when (key) {
            AutomationExecutor.KeyCode.MENU -> 82
            AutomationExecutor.KeyCode.ENTER -> 66
            AutomationExecutor.KeyCode.DEL -> 67
        }
        runAsync(onResult) { ShizukuManager.keyEvent(keyCode) }
    }

    override fun inputText(text: String, onResult: (Boolean) -> Unit) {
        runAsync(onResult) { ShizukuManager.inputText(text) }
    }

    override fun cancel() = Unit

    private fun runAsync(onResult: (Boolean) -> Unit, block: () -> Boolean) {
        worker.execute {
            val result = runCatching(block).onFailure {
                LogBus.warn("Shizuku 注入异常：${it.javaClass.simpleName} ${it.message ?: ""}")
            }.getOrDefault(false)
            handler.post { onResult(result) }
        }
    }

    private companion object {
        /** UserService 直接注入 MotionEvent，比 input 命令快得多 */
        const val MAX_TAPS_PER_SECOND = 60
    }
}
