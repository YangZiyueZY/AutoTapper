package com.example.autotapper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.util.concurrent.Executors

/**
 * Shizuku 模式的点击服务：由悬浮控制器或主界面启动，用 Shizuku 提权执行 input 命令。
 * 与无障碍服务互不干扰：只有当前点击方式是 Shizuku 时才真正开始点击。
 */
class ShizukuTapService : Service() {

    private lateinit var engine: TapEngine
    private var receiverRegistered = false
    private var finishing = false

    private val executor = ShizukuTapExecutor()
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AutoTapperConfig.ACTION_STOP_CLICKING) {
                return
            }
            engine.stop(showToast = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        engine = TapEngine(this, executor).also { it.onStopped = { finishSession() } }
        startAsForegroundService()
        registerControlReceiver()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            AutoTapperConfig.ACTION_SHIZUKU_TAP -> startClicking()
            AutoTapperConfig.ACTION_STOP_CONTROLLER -> {
                engine.stop()
                finishSession()
            }

            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        engine.release()
        // 结束 shell 身份的 UserService 进程，避免它一直挂在后台
        ShizukuManager.unbind()
        worker.shutdownNow()
        unregisterControlReceiver()
        super.onDestroy()
    }

    private fun startClicking() {
        if (AutoTapperConfig.getTapMode(this) != AutoTapperConfig.TAP_MODE_SHIZUKU) {
            finishSession()
            return
        }

        when (ShizukuManager.state(this)) {
            ShizukuManager.State.READY -> Unit
            ShizukuManager.State.NOT_INSTALLED,
            ShizukuManager.State.NOT_RUNNING -> {
                Toast.makeText(this, R.string.shizuku_unavailable, Toast.LENGTH_SHORT).show()
                finishSession()
                return
            }

            ShizukuManager.State.NO_PERMISSION -> {
                Toast.makeText(this, R.string.shizuku_permission_required, Toast.LENGTH_SHORT).show()
                finishSession()
                return
            }
        }

        // UserService 的绑定回调 post 到主线程，只能在后台线程等待
        worker.execute {
            val ready = ShizukuManager.awaitReady(this)
            mainHandler.post {
                if (!ready) {
                    Toast.makeText(this, R.string.shizuku_tap_failed, Toast.LENGTH_SHORT).show()
                    finishSession()
                    return@post
                }
                if (!engine.start()) {
                    finishSession()
                } else {
                    Toast.makeText(this, R.string.click_started, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** 点击结束（停止、跑完或出错）后退出前台，恢复系统状态。 */
    private fun finishSession() {
        if (finishing) {
            return
        }
        finishing = true
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForegroundService() {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_running))
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.overlay_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun registerControlReceiver() {
        if (receiverRegistered) {
            return
        }
        val filter = IntentFilter().apply {
            addAction(AutoTapperConfig.ACTION_STOP_CLICKING)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(controlReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterControlReceiver() {
        if (!receiverRegistered) {
            return
        }
        runCatching { unregisterReceiver(controlReceiver) }
        receiverRegistered = false
    }

    /** 在后台线程执行 Shizuku 命令，回调回主线程，避免阻塞点击循环。 */
    private inner class ShizukuTapExecutor : TapExecutor {
        @Volatile
        private var cancelled = false

        override fun tap(x: Int, y: Int, durationMs: Long, onResult: (Boolean) -> Unit) {
            cancelled = false
            worker.execute {
                val success = if (cancelled) false else ShizukuManager.tap(x, y, durationMs)
                mainHandler.post { if (!cancelled) onResult(success) }
            }
        }

        override fun cancel() {
            cancelled = true
        }
    }

    companion object {
        private const val CHANNEL_ID = "autotapper_shizuku_tapping"
        private const val NOTIFICATION_ID = 1002
    }
}
