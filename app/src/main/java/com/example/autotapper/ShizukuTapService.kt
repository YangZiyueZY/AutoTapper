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
import com.example.autotapper.engine.ActiveExecutor
import com.example.autotapper.engine.PluginStepBridgeImpl
import com.example.autotapper.engine.RuleStore
import com.example.autotapper.engine.ShizukuAutomationExecutor
import com.example.autotapper.engine.StepEngine
import com.example.autotapper.model.Rule
import com.example.autotapper.runtime.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.Executors

/**
 * Shizuku 模式的点击服务：由悬浮控制器或主界面启动，用 Shizuku 提权执行注入。
 * 与无障碍服务互不干扰：只有当前点击方式是 Shizuku 时才真正开始点击。
 * 点击循环交给 [StepEngine]；前台服务只负责"跑起来时活着"，引擎回到 IDLE 就收尾
 * （等价于旧 TapEngine 的 onStopped）。
 */
class ShizukuTapService : Service() {

    /** 引擎的协程上下文：循环与注入回调都在主线程。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var executor: ShizukuAutomationExecutor? = null
    private var engine: StepEngine? = null
    private var receiverRegistered = false
    private var finishing = false
    private var destroyed = false

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            runCatching { handleControl(action) }
                .onFailure {
                    LogBus.error("控制指令处理失败：" + action + " " + it.javaClass.simpleName)
                }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 前台服务一起来就把注入通道挂上：插件步骤靠 ActiveExecutor 找当前通道
        runCatching { ensureSession() }
            .onFailure { LogBus.error("Shizuku 注入通道初始化失败：" + it.javaClass.simpleName) }
        startAsForegroundService()
        registerControlReceiver()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            when (intent?.action) {
                AutoTapperConfig.ACTION_SHIZUKU_TAP -> startClicking()
                AutoTapperConfig.ACTION_STOP_CONTROLLER -> {
                    engine?.let { it.stop() }
                    finishSession()
                }

                else -> Unit
            }
        }.onFailure {
            LogBus.error("Shizuku 服务指令处理失败：" + it.javaClass.simpleName + " " + (it.message ?: ""))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        releaseSession()
        // 结束 shell 身份的 UserService 进程，避免它一直挂在后台
        ShizukuManager.unbind()
        worker.shutdownNow()
        scope.cancel()
        unregisterControlReceiver()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 会话（注入器 + 引擎）

    /**
     * 注入通道与引擎各建一次。引擎跑完 / 出错 / 被停止都会回到 IDLE，
     * 那就是旧 TapEngine 的 onStopped：退出前台并结束本次会话。
     */
    private fun ensureSession(): StepEngine {
        engine?.let { return it }

        val shizuku = ShizukuAutomationExecutor(this)
        val created = StepEngine(
            this,
            shizuku,
            PluginStepBridgeImpl(
                this,
                { ActiveExecutor.current },
                { pluginId, reason -> onPluginCrashSuspect(pluginId, reason) }
            ),
            scope
        )
        created.onStateChanged = { state ->
            if (state == StepEngine.State.IDLE) {
                finishSession()
            }
        }

        executor = shizuku
        engine = created
        ActiveExecutor.set(shizuku)
        return created
    }

    /** 停引擎 → 摘掉全局通道，成对收尾；重复调用安全。 */
    private fun releaseSession() {
        runCatching {
            val stopping = engine
            engine = null
            if (stopping != null) {
                // 收尾阶段不要再回调 finishSession
                stopping.onStateChanged = null
                stopping.stop()
            }

            val current = executor
            executor = null
            if (current != null) {
                ActiveExecutor.clear(current)
            }
        }.onFailure { LogBus.error("释放 Shizuku 注入通道失败：" + it.javaClass.simpleName) }
    }

    private fun onPluginCrashSuspect(pluginId: String, reason: String) {
        runCatching { PluginManager.get(this).disableAfterCrashes(pluginId, reason) }
            .onFailure { LogBus.error("自动停用插件失败：" + pluginId) }
    }

    // ------------------------------------------------------------ 控制指令

    private fun handleControl(action: String) {
        when (action) {
            AutoTapperConfig.ACTION_STOP_CLICKING -> stopClicking()
            AutoTapperConfig.ACTION_PAUSE_CLICKING -> pauseClicking()
            AutoTapperConfig.ACTION_RESUME_CLICKING -> resumeClicking()
        }
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
                Toast.makeText(this, R.string.shizuku_permission_required, Toast.LENGTH_SHORT)
                    .show()
                finishSession()
                return
            }
        }

        val eng = ensureSession()
        when (eng.state) {
            StepEngine.State.RUNNING -> return
            StepEngine.State.PAUSED -> {
                eng.resume()
                Toast.makeText(this, R.string.click_resumed, Toast.LENGTH_SHORT).show()
                return
            }

            StepEngine.State.IDLE -> Unit
        }

        // UserService 的绑定回调 post 到主线程，只能在后台线程等待；
        // 规则要读文件，顺手一起在后台读，主线程只做启动与提示
        runCatching {
            worker.execute {
                val ready = ShizukuManager.awaitReady(this)
                val rule = if (ready) RuleStore.currentRule(this) else null
                mainHandler.post {
                    if (destroyed) {
                        return@post
                    }
                    runCatching { startEngineAfterReady(eng, ready, rule) }
                        .onFailure {
                            LogBus.error(
                                "Shizuku 启动失败：" + it.javaClass.simpleName + " " + (it.message ?: "")
                            )
                            finishSession()
                        }
                }
            }
        }.onFailure {
            LogBus.error("后台任务提交失败：" + it.javaClass.simpleName)
            finishSession()
        }
    }

    private fun startEngineAfterReady(
        eng: StepEngine,
        ready: Boolean,
        rule: Rule?
    ) {
        if (!ready) {
            Toast.makeText(this, R.string.shizuku_tap_failed, Toast.LENGTH_SHORT).show()
            finishSession()
            return
        }
        if (rule == null) {
            Toast.makeText(this, R.string.rule_required, Toast.LENGTH_SHORT).show()
            finishSession()
            return
        }
        if (rule.steps.isEmpty()) {
            Toast.makeText(this, R.string.rule_steps_required, Toast.LENGTH_SHORT).show()
            finishSession()
            return
        }
        eng.start(rule)
        Toast.makeText(this, R.string.click_started, Toast.LENGTH_SHORT).show()
    }

    private fun stopClicking() {
        val eng = engine ?: return
        if (eng.state == StepEngine.State.IDLE) {
            // 已经停了也可能还挂着前台通知，这里收尾
            finishSession()
            return
        }
        // 回到 IDLE 会走 onStateChanged → finishSession
        eng.stop()
        Toast.makeText(this, R.string.click_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun pauseClicking() {
        val eng = engine ?: return
        if (eng.state != StepEngine.State.RUNNING) {
            return
        }
        eng.pause()
        Toast.makeText(this, R.string.click_paused, Toast.LENGTH_SHORT).show()
    }

    private fun resumeClicking() {
        val eng = engine ?: return
        if (eng.state != StepEngine.State.PAUSED) {
            return
        }
        eng.resume()
        Toast.makeText(this, R.string.click_resumed, Toast.LENGTH_SHORT).show()
    }

    /** 点击结束（停止、跑完或出错）后退出前台，恢复系统状态。 */
    private fun finishSession() {
        if (finishing) {
            return
        }
        finishing = true
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            .onFailure { LogBus.warn("退出前台失败：" + it.javaClass.simpleName) }
        stopSelf()
    }

    // ------------------------------------------------------------ 前台服务与通知

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
            addAction(AutoTapperConfig.ACTION_PAUSE_CLICKING)
            addAction(AutoTapperConfig.ACTION_RESUME_CLICKING)
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

    companion object {
        private const val CHANNEL_ID = "autotapper_shizuku_tapping"
        private const val NOTIFICATION_ID = 1002
    }
}
