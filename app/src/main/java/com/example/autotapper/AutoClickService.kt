package com.example.autotapper

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.example.autotapper.engine.AccessibilityAutomationExecutor
import com.example.autotapper.engine.ActiveExecutor
import com.example.autotapper.engine.PluginStepBridgeImpl
import com.example.autotapper.engine.RuleStore
import com.example.autotapper.engine.StepEngine
import com.example.autotapper.plugin.host.CapabilityHolder
import com.example.autotapper.plugin.host.PluginNodeAdapter
import com.example.autotapper.runtime.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 无障碍通道的常驻服务：只负责把 [AccessibilityAutomationExecutor] 这个注入通道挂到当前进程上。
 * 点击循环本身交给 [StepEngine]（规则 + 步骤），本类不再自己数点位、不再排队点击，
 * 于是「暂停 / 继续 / 跳步」这些能力与 Shizuku 通道天然一致。
 */
class AutoClickService : AccessibilityService() {

    /** 引擎的协程上下文：引擎在主线程跑循环，注入回调也在主线程回来。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var executor: AccessibilityAutomationExecutor? = null
    private var engine: StepEngine? = null
    private var receiverRegistered = false

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            // 广播来自界面 / 悬浮控制器，任何异常都不能把常驻的无障碍服务带崩
            runCatching { handleControl(action) }
                .onFailure {
                    LogBus.error("控制指令处理失败：" + action + " " + it.javaClass.simpleName)
                }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 进程一起来就就位：插件步骤要通过 ActiveExecutor 拿当前活跃通道
        runCatching { ensureSession() }
            .onFailure { LogBus.error("无障碍注入通道初始化失败：" + it.javaClass.simpleName) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 系统可能重复回调（用户关掉再打开无障碍），ensureSession 内部幂等
        runCatching { ensureSession() }
            .onFailure { LogBus.error("无障碍注入通道初始化失败：" + it.javaClass.simpleName) }
        registerControlReceiver()
        attachNodeTree()
        // 连上时把真实状态播一次：服务可能是在界面已经打开后才被重新连上的
        broadcastClickState(engine?.state == StepEngine.State.RUNNING)
        Toast.makeText(this, R.string.accessibility_ready, Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        engine?.let { runCatching { it.stop() } }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        releaseSession()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        releaseSession()
        scope.cancel()
        unregisterControlReceiver()
        super.onDestroy()
    }

    // ------------------------------------------------------------ 会话（注入器 + 引擎）

    /**
     * 注入通道与引擎各建一次。以 engine 是否为空作为唯一判据：
     * onServiceConnected 被重复调用时若再建一套，就会出现两条循环同时点。
     */
    private fun ensureSession(): StepEngine {
        engine?.let { return it }

        val accessibility = AccessibilityAutomationExecutor(this)
        val created = StepEngine(
            this,
            accessibility,
            PluginStepBridgeImpl(
                this,
                { ActiveExecutor.current },
                { pluginId, reason -> onPluginCrashSuspect(pluginId, reason) }
            ),
            scope
        )

        executor = accessibility
        engine = created
        ActiveExecutor.set(accessibility)
        return created
    }

    /** 停引擎 → 摘掉全局通道，成对收尾；重复调用安全。 */
    private fun releaseSession() {
        runCatching {
            val stopping = engine
            engine = null
            stopping?.let { it.stop() }

            val current = executor
            executor = null
            if (current != null) {
                // 先告诉注入器"已经断开"，避免收尾瞬间还在往系统递手势
                current.onServiceDisconnected()
                ActiveExecutor.clear(current)
            }
        }.onFailure { LogBus.error("释放无障碍注入通道失败：" + it.javaClass.simpleName) }

        // 服务断开后节点树就不可读了，必须立刻退回空实现，
        // 否则插件会在下一次查找时摸到一棵已经死掉的树
        CapabilityHolder.resetNodes()
    }

    /**
     * 把"读当前窗口节点树"的能力交给插件（view-id 这类识 ID 插件靠它）。
     *
     * 为什么用 provider 而不是把 this 交给适配器：服务被系统断开后适配器还活着，
     * 直接持有 this 会让它悄悄指向一个已经失效的服务；provider 每次现取 rootInActiveWindow，
     * 服务活着就有一棵可读的树，断开时宿主 resetNodes() 换成空实现。
     */
    private fun attachNodeTree() {
        runCatching {
            CapabilityHolder.setNodes(PluginNodeAdapter { rootInActiveWindow })
        }.onFailure {
            // 接入失败只是"识 ID 类插件不可用"，不影响点击主流程，所以只记一条黄旗
            LogBus.warn("插件节点树接入失败：" + it.javaClass.simpleName)
        }
    }

    private fun onPluginCrashSuspect(pluginId: String, reason: String) {
        runCatching { PluginManager.get(this).disableAfterCrashes(pluginId, reason) }
            .onFailure { LogBus.error("自动停用插件失败：" + pluginId) }
    }

    // ------------------------------------------------------------ 控制指令

    private fun handleControl(action: String) {
        when (action) {
            AutoTapperConfig.ACTION_START_CLICKING -> startClicking()
            AutoTapperConfig.ACTION_STOP_CLICKING -> stopClicking()
            AutoTapperConfig.ACTION_PAUSE_CLICKING -> pauseClicking()
            AutoTapperConfig.ACTION_RESUME_CLICKING -> resumeClicking()
        }
    }

    private fun startClicking() {
        // 切到 Shizuku 模式后，无障碍服务即使还连着也不应该再点
        if (AutoTapperConfig.getTapMode(this) != AutoTapperConfig.TAP_MODE_ACCESSIBILITY) {
            return
        }

        val eng = ensureSession()
        when (eng.state) {
            // 连点两下"开始"不要叠出第二条循环
            StepEngine.State.RUNNING -> return
            StepEngine.State.PAUSED -> {
                eng.resume()
                Toast.makeText(this, R.string.click_resumed, Toast.LENGTH_SHORT).show()
                return
            }

            StepEngine.State.IDLE -> Unit
        }

        val rule = RuleStore.currentRule(this)
        if (rule == null) {
            Toast.makeText(this, R.string.rule_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (rule.steps.isEmpty()) {
            Toast.makeText(this, R.string.rule_steps_required, Toast.LENGTH_SHORT).show()
            return
        }

        eng.start(rule)
        Toast.makeText(this, R.string.click_started, Toast.LENGTH_SHORT).show()
    }

    private fun stopClicking() {
        val eng = engine ?: return
        // 引擎自己会把 IDLE 状态广播出去，界面据此收起"运行中"
        if (eng.state == StepEngine.State.IDLE) {
            return
        }
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

    // ------------------------------------------------------------ 广播

    private fun broadcastClickState(isRunning: Boolean) {
        runCatching {
            sendBroadcast(
                Intent(AutoTapperConfig.ACTION_CLICK_STATE_CHANGED)
                    .setPackage(packageName)
                    .putExtra(AutoTapperConfig.EXTRA_IS_CLICKING, isRunning)
                    .putExtra(AutoTapperConfig.EXTRA_CURRENT_POINT_INDEX, -1)
            )
        }
    }

    private fun registerControlReceiver() {
        if (receiverRegistered) {
            return
        }

        val filter = IntentFilter().apply {
            addAction(AutoTapperConfig.ACTION_START_CLICKING)
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
}
