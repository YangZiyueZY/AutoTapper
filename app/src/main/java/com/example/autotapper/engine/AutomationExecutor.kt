package com.example.autotapper.engine

import com.example.autotapper.model.TapCoord

/**
 * 主程序侧的注入器接口。两种实现：
 * - 无障碍 dispatchGesture（默认，无需额外授权）；
 * - Shizuku（提权后频率更高、能做多指）。
 *
 * 插件看到的是 plugin.ExecutorApi，中间由 [PluginExecutorAdapter] 转接，
 * 这样插件不需要知道自己跑在哪条通道上。
 */
interface AutomationExecutor {

    /** 在下发的通道里按下并保持 [durationMs] 后抬起。 */
    fun tap(x: Int, y: Int, durationMs: Long, onResult: (Boolean) -> Unit)

    fun swipe(from: TapCoord, to: TapCoord, durationMs: Long, onResult: (Boolean) -> Unit)

    /** 多指同时按下，最多 10 个点。通道不支持时回调 false。 */
    fun multiTouch(points: List<TapCoord>, holdMs: Long, onResult: (Boolean) -> Unit)

    /** 返回键 / 桌面键。 */
    fun globalAction(action: GlobalAction, onResult: (Boolean) -> Unit)

    /** 菜单键等没有全局动作的按键。 */
    fun keyEvent(key: KeyCode, onResult: (Boolean) -> Unit)

    /** 往当前焦点输入框输入文本。 */
    fun inputText(text: String, onResult: (Boolean) -> Unit)

    /** 该通道实际可达的点击频率上限（次/秒），界面上按这个真话标注。 */
    fun maxTapsPerSecond(): Int

    /** 停止后取消尚未完成的注入。 */
    fun cancel()

    /** 通道是否就绪（无障碍已连上 / Shizuku 已授权）。 */
    fun isReady(): Boolean

    enum class GlobalAction { BACK, HOME, RECENTS }

    enum class KeyCode { MENU, ENTER, DEL }
}
