package com.example.autotapper

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * 点击的启动/停止入口：按当前点击方式选择无障碍广播或 Shizuku 前台服务，
 * 悬浮控制器与主界面都走这里，避免两处各写一遍判断。
 */
object TapScheduler {

    fun isReady(context: Context): Boolean = when (AutoTapperConfig.getTapMode(context)) {
        AutoTapperConfig.TAP_MODE_SHIZUKU -> ShizukuManager.hasPermission()
        else -> PermissionUtils.isAccessibilityServiceEnabled(context, AutoClickService::class.java)
    }

    /** 返回是否已经发出开始指令；失败时会提示原因。 */
    fun start(context: Context): Boolean {
        if (!AutoTapperConfig.hasPoints(context)) {
            Toast.makeText(context, R.string.point_required, Toast.LENGTH_SHORT).show()
            return false
        }

        return when (AutoTapperConfig.getTapMode(context)) {
            AutoTapperConfig.TAP_MODE_SHIZUKU -> {
                when (ShizukuManager.state(context)) {
                    ShizukuManager.State.READY -> {
                        val intent = Intent(context, ShizukuTapService::class.java)
                            .setAction(AutoTapperConfig.ACTION_SHIZUKU_TAP)
                        runCatching {
                            ContextCompat.startForegroundService(context, intent)
                        }.isSuccess
                    }

                    ShizukuManager.State.NO_PERMISSION -> {
                        Toast.makeText(
                            context,
                            R.string.shizuku_permission_required,
                            Toast.LENGTH_SHORT
                        ).show()
                        false
                    }

                    else -> {
                        Toast.makeText(context, R.string.shizuku_unavailable, Toast.LENGTH_SHORT)
                            .show()
                        false
                    }
                }
            }

            else -> {
                if (!PermissionUtils.isAccessibilityServiceEnabled(
                        context,
                        AutoClickService::class.java
                    )
                ) {
                    Toast.makeText(
                        context,
                        R.string.accessibility_permission_required,
                        Toast.LENGTH_SHORT
                    ).show()
                    return false
                }
                context.sendBroadcast(
                    Intent(AutoTapperConfig.ACTION_START_CLICKING).setPackage(context.packageName)
                )
                true
            }
        }
    }

    fun stop(context: Context) {
        context.sendBroadcast(
            Intent(AutoTapperConfig.ACTION_STOP_CLICKING).setPackage(context.packageName)
        )
    }
}
