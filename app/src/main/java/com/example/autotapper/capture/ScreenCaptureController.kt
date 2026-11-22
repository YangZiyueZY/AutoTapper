package com.example.autotapper.capture

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.autotapper.LogBus

/**
 * 截图服务的唯一开关：设置页、插件面板、以后的快捷开关都从这里走，
 * 避免每个调用点各写一遍 Intent 拼装与状态判断。
 *
 * 授权链路：[start] → [CapturePermissionActivity] 拉起系统弹窗 → 用户同意 →
 * [startWithPermission] → [ScreenCaptureService] 起前台服务并接管投影。
 */
object ScreenCaptureController {

    /**
     * 请求开始截图。已经有权限并且服务在跑就直接返回 true；
     * 否则拉起授权页（用户会不会同意不在这句话的回答范围内，所以返回 true 只表示「请求已发出」）。
     */
    fun start(context: Context): Boolean {
        if (ScreenCaptureService.isRunning()) {
            LogBus.info("截图服务已经在运行，不重复申请授权。")
            return true
        }
        CapturePermissionActivity.request(context)
        return true
    }

    /** 停止截图：直接停服务，服务在 onDestroy 里释放投影、ImageReader 并把插件能力退回空实现。 */
    fun stop(context: Context) {
        val stopped = runCatching {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }.getOrDefault(false)
        if (stopped) {
            LogBus.info("截图服务已停止。")
        } else {
            LogBus.info("截图服务本来就没在运行。")
        }
    }

    /** 截图管线是否活着（授权还在、帧源还在）。 */
    fun isRunning(): Boolean = ScreenCaptureService.isRunning()

    /**
     * 带授权结果启动截图服务，只给 [CapturePermissionActivity] 用。
     * 走 startForegroundService：Android 8 起后台启动服务必须走这条，
     * 而服务自己在 onCreate 里立刻 startForeground，不会触发 ANR 超时。
     */
    internal fun startWithPermission(context: Context, resultCode: Int, data: Intent): Boolean {
        return runCatching {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .setAction(ScreenCaptureService.ACTION_START)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
            true
        }.onFailure {
            LogBus.error("启动截图服务失败：" + it.javaClass.simpleName + " " + (it.message ?: ""))
        }.getOrDefault(false)
    }
}
