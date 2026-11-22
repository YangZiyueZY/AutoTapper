package com.example.autotapper.engine

import android.content.Context
import android.content.Intent
import com.example.autotapper.AutoTapperConfig
import com.example.autotapper.LogBus
import com.example.autotapper.model.CoreStep
import com.example.autotapper.model.PluginStep
import com.example.autotapper.model.Rule
import com.example.autotapper.model.Step
import com.example.autotapper.plugin.StepResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * 步骤执行引擎：按规则顺序跑步骤，把真正的注入交给 [AutomationExecutor]，
 * 把"下一步去哪"交给 [StepPolicy]（默认顺序，装了 flow-control 插件就带分支）。
 *
 * 引擎自己只管四件事：顺序、暂停/继续/停止、超时、以及把过程写进日志。
 */
class StepEngine(
    private val context: Context,
    private val executor: AutomationExecutor,
    private val bridge: PluginStepBridge,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default
) {

    enum class State { IDLE, RUNNING, PAUSED }

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var currentStepIndex: Int = -1
        private set

    /** 策略在 BEFORE 阶段要求"直接去第 N 步"时先存这里，由 [runLoop] 统一执行。 */
    @Volatile
    private var pendingGoto: Int = -1

    /**
     * 策略回灌通道：BEFORE 阶段的决定拿不到返回值，所以停止 / 暂停 / 跳转由策略适配器调这里，
     * 引擎在 beforeStep 之后自己执行。
     *
     * 注意这里**不**取消协程：stop() 会 cancel job，那会让 runLoop 抛 CancellationException，
     * 于是日志里会变成"引擎异常结束"。所以策略要求停止时只置旗标，让循环自己正常收尾。
     */
    private val policyControl: PolicyControl = object : PolicyControl {
        override fun requestStop(reason: String) {
            stopRequested = true
        }

        override fun requestPause(reason: String) {
            pause()
        }

        override fun requestGoto(index: Int, reason: String) {
            pendingGoto = index
        }
    }

    /**
     * 默认策略：装了策略插件就叠加插件决定，没装就退化成纯间隔等待（与升级前完全一致）。
     * 外部整体替换 policy 仍然允许（预览/测试用）。
     */
    var policy: StepPolicy = PluginPolicyAdapter(
        pluginProvider = { PolicyPluginHost.first()?.second },
        appContext = context,
        interval = IntervalStepPolicy(random),
        control = policyControl
    )

    /** 引擎自己停下来（跑完轮数 / 出错 / 用户停止）时回调。 */
    var onStateChanged: ((State) -> Unit)? = null

    private var job: Job? = null
    private var pauseGate: CompletableDeferred<Unit>? = null
    private var stopRequested = false

    fun start(rule: Rule) {
        if (state != State.IDLE) {
            LogBus.warn("引擎已在运行，忽略重复启动")
            return
        }
        if (rule.steps.isEmpty()) {
            LogBus.warn("规则里没有步骤，启动被忽略")
            return
        }
        stopRequested = false
        currentStepIndex = -1
        setState(State.RUNNING)
        job = scope.launch {
            try {
                runLoop(rule)
            } catch (t: Throwable) {
                LogBus.error("引擎异常结束：${t.javaClass.simpleName} ${t.message ?: ""}")
            } finally {
                currentStepIndex = -1
                executor.cancel()
                setState(State.IDLE)
            }
        }
    }

    fun pause() {
        if (state != State.RUNNING) return
        pauseGate = CompletableDeferred()
        setState(State.PAUSED)
        LogBus.info("已暂停")
    }

    fun resume() {
        if (state != State.PAUSED) return
        val gate = pauseGate
        pauseGate = null
        setState(State.RUNNING)
        LogBus.info("已继续")
        gate?.complete(Unit)
    }

    fun stop() {
        stopRequested = true
        pauseGate?.complete(Unit)
        pauseGate = null
        executor.cancel()
        job?.cancel()
        job = null
        if (state != State.IDLE) {
            setState(State.IDLE)
            LogBus.info("已停止")
        }
    }

    private suspend fun runLoop(rule: Rule) {
        val total = rule.steps.size
        var round = 0
        while (!stopRequested && (rule.isInfinite || round < rule.loopCount)) {
            var index = 0
            while (index < total && !stopRequested) {
                val step = rule.steps[index]
                currentStepIndex = index
                if (!step.enabled) {
                    index++
                    continue
                }

                policy.beforeStep(rule, step, index)
                awaitIfPaused()
                if (stopRequested) return

                // 策略可以在 BEFORE 阶段要求"这步别跑，直接去别处"（见 policyControl）。
                // 放在这里执行而不是让适配器改 currentStepIndex：步骤游标只有引擎的循环能改。
                val jump = takePendingGoto()
                if (jump >= 0) {
                    if (jump in 0 until total && jump != index) {
                        LogBus.info("跳转到步骤 " + (jump + 1))
                        index = jump
                        continue
                    }
                    LogBus.warn("策略要求跳转到步骤 " + (jump + 1) + " 无效（越界或就是当前这一步），按当前步骤继续")
                }

                val times = policy.repeatCount(rule, step).coerceIn(1, 10_000)
                var result: StepResult = StepResult.Success()
                var i = 0
                while (i < times && !stopRequested) {
                    result = executeOnce(rule, step, index)
                    i++
                }

                if (result === StepResult.Stop) {
                    LogBus.result("步骤 ${index + 1} 要求停止")
                    return
                }
                if (result === StepResult.Pause) {
                    pause()
                }

                when (val decision = policy.onResult(rule, step, index, result)) {
                    FlowDecision.Next -> index++
                    is FlowDecision.Goto -> {
                        val target = decision.index
                        if (target in 0 until total) {
                            LogBus.info("跳转到步骤 ${target + 1}")
                            index = target
                        } else {
                            LogBus.warn("跳转目标 ${target + 1} 超出步骤范围，按下一步处理")
                            index++
                        }
                    }

                    FlowDecision.Stop -> {
                        LogBus.result("流程要求停止")
                        return
                    }

                    FlowDecision.Pause -> pause()
                }

                awaitIfPaused()
            }
            round++
            if (!rule.isInfinite) {
                LogBus.result("第 $round / ${rule.loopCount} 轮完成")
            }
        }
    }

    /** 取走策略回灌的跳转目标并清空；没有要求时返回 -1。 */
    private fun takePendingGoto(): Int {
        val pending = pendingGoto
        if (pending >= 0) pendingGoto = -1
        return pending
    }

    private suspend fun awaitIfPaused() {
        val gate = pauseGate
        if (gate != null) {
            gate.await()
        }
    }

    private suspend fun executeOnce(rule: Rule, step: Step, index: Int): StepResult {
        val label = stepLabel(step)
        val started = System.currentTimeMillis()
        val timeout = policy.timeoutMs(rule, step)

        val body: suspend () -> StepResult = {
            when (step) {
                is CoreStep -> dispatchTap(step)
                is PluginStep -> bridge.execute(step)
                    ?: StepResult.Fail("插件未安装：${bridge.pluginName(step.pluginId)}")
            }
        }

        val result = if (timeout > 0) {
            withTimeoutOrNull(timeout) { body() } ?: StepResult.Fail("超时（${timeout} ms）")
        } else {
            body()
        }

        val elapsed = System.currentTimeMillis() - started
        when (result) {
            is StepResult.Success -> LogBus.result("步骤 ${index + 1} 成功，用时 $elapsed ms", index, label)
            is StepResult.Fail -> LogBus.warn("步骤 ${index + 1} 失败：${result.reason}（用时 $elapsed ms）", index, label)
            is StepResult.Stop -> LogBus.info("步骤 ${index + 1} 返回停止", index, label)
            is StepResult.Pause -> LogBus.info("步骤 ${index + 1} 返回暂停", index, label)
            is StepResult.Goto -> LogBus.info("步骤 ${index + 1} 要求跳转到 ${result.stepIndex + 1}", index, label)
        }
        return result
    }

    private suspend fun dispatchTap(step: CoreStep): StepResult {
        val ok = awaitCallback { cb -> executor.tap(step.x, step.y, step.durationMs, cb) }
        return if (ok) StepResult.Success() else StepResult.Fail("点击下发失败")
    }

    private suspend fun awaitCallback(block: ((Boolean) -> Unit) -> Unit): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        block { success -> if (!deferred.isCompleted) deferred.complete(success) }
        // 注入回调没回来也不能把引擎挂死
        return withTimeoutOrNull(CALLBACK_TIMEOUT_MS) { deferred.await() } ?: false
    }

    private fun stepLabel(step: Step): String = when (step) {
        is CoreStep -> "点击(${step.x},${step.y})"
        is PluginStep -> bridge.summarize(step) ?: "${step.pluginId}/${step.typeId}"
    }

    private fun setState(next: State) {
        state = next
        onStateChanged?.invoke(next)
        runCatching {
            context.sendBroadcast(
                Intent(AutoTapperConfig.ACTION_CLICK_STATE_CHANGED)
                    .setPackage(context.packageName)
                    .putExtra(AutoTapperConfig.EXTRA_IS_CLICKING, next == State.RUNNING)
                    .putExtra(AutoTapperConfig.EXTRA_CURRENT_POINT_INDEX, currentStepIndex)
            )
        }
    }

    companion object {
        private const val CALLBACK_TIMEOUT_MS = 5_000L
    }
}
