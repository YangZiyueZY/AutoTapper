package com.example.autotapper

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.autotapper.engine.RuleStore

/**
 * 点击的启动/停止入口：按当前点击方式选择无障碍广播或 Shizuku 前台服务，
 * 悬浮控制器与主界面都走这里，避免两处各写一遍判断。
 *
 * 能不能开始，现在看的是「当前规则有没有步骤」（引擎按步骤跑），
 * 不再是旧的「有没有点位」。
 */
object TapScheduler {

    fun isReady(context: Context): Boolean = when (AutoTapperConfig.getTapMode(context)) {
        AutoTapperConfig.TAP_MODE_SHIZUKU -> ShizukuManager.hasPermission()
        else -> PermissionUtils.isAccessibilityServiceEnabled(context, AutoClickService::class.java)
    }

    /** 返回是否已经发出开始指令；失败时会提示原因。 */
    fun start(context: Context): Boolean {
        // 老版本只存了点位：先迁移成一条规则，否则升级后第一次点「开始」只会被告知"没有规则"。
        // ensureMigrated 只在一条规则都没有时才动手，重复调用安全。
        runCatching { RuleStore.ensureMigrated(context) }

        // 读规则会碰文件，但只有一次小 JSON，和原来读点位 prefs 的成本同级
        val rule = RuleStore.currentRule(context)
        if (rule == null) {
            Toast.makeText(context, R.string.rule_required, Toast.LENGTH_SHORT).show()
            return false
        }
        if (rule.steps.isEmpty()) {
            Toast.makeText(context, R.string.rule_steps_required, Toast.LENGTH_SHORT).show()
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

    /** 暂停：两条通道都是同一条广播，服务自己判断当前有没有在跑。 */
    fun pause(context: Context) {
        context.sendBroadcast(
            Intent(AutoTapperConfig.ACTION_PAUSE_CLICKING).setPackage(context.packageName)
        )
    }

    fun resume(context: Context) {
        context.sendBroadcast(
            Intent(AutoTapperConfig.ACTION_RESUME_CLICKING).setPackage(context.packageName)
        )
    }
}
