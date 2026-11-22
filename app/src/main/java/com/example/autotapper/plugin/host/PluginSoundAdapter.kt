package com.example.autotapper.plugin.host

import android.content.Context
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.autotapper.LogBus
import com.example.autotapper.plugin.HostSoundApi

/**
 * 响铃 / 震动的宿主实现。
 *
 * 几个必须处理好的边界：
 * - 铃声要能被停掉（用户点「停止」时不该还在响），所以持有当前 Ringtone 并在超时/停止时 stop；
 * - 没有振动能力（模拟器常见）时静默跳过，不要抛；
 * - 所有系统调用都可能抛 SecurityException，一律吃掉并记日志。
 */
class PluginSoundAdapter(private val context: Context) : HostSoundApi {

    private val appContext: Context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var current: Ringtone? = null

    private val stopRunnable = Runnable { stop() }

    override fun ring(durationMs: Int) {
        val duration = durationMs.coerceIn(100, 60_000)
        runCatching {
            stop()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: return
            val tone = RingtoneManager.getRingtone(appContext, uri) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                tone.isLooping = false
            }
            current = tone
            tone.play()
            main.removeCallbacks(stopRunnable)
            main.postDelayed(stopRunnable, duration.toLong())
        }.onFailure {
            LogBus.warn("响铃失败（可能被静音或没有默认铃声）：" + it.javaClass.simpleName)
        }
    }

    override fun vibrate(durationMs: Long) {
        val duration = durationMs.coerceIn(10L, 10_000L)
        runCatching {
            val vibrator = vibrator() ?: return
            if (!vibrator.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        }.onFailure {
            LogBus.warn("震动失败：" + it.javaClass.simpleName)
        }
    }

    override fun stop() {
        runCatching {
            main.removeCallbacks(stopRunnable)
            val tone = current
            current = null
            if (tone != null && tone.isPlaying) tone.stop()
        }
    }

    private fun vibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
