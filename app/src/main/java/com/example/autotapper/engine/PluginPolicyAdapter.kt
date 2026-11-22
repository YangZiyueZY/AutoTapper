package com.example.autotapper.engine

import android.content.Context
import com.example.autotapper.LogBus
import com.example.autotapper.model.CoreStep
import com.example.autotapper.model.PluginStep
import com.example.autotapper.model.Rule
import com.example.autotapper.model.Step
import com.example.autotapper.plugin.PolicyCall
import com.example.autotapper.plugin.PolicyDecision
import com.example.autotapper.plugin.PolicyPhase
import com.example.autotapper.plugin.PolicyResult
import com.example.autotapper.plugin.StepPolicyPlugin
import com.example.autotapper.plugin.StepResult
import com.example.autotapper.plugin.host.CapabilityHolder
import com.example.autotapper.runtime.PluginHostContext
import com.example.autotapper.runtime.PluginRegistry
import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 策略把 BEFORE 阶段拿到的"非等待类"决定交给引擎的通道。
 *
 * 为什么需要它：[StepPolicy.beforeStep] 没有返回值，而 [PolicyDecision] 里
 * 停止 / 暂停 / 跳转必须由引擎的循环来执行（它才知道当前在第几步）。
 * 所以这几条决定不能只靠返回值翻译，得回灌给引擎。插件永远看不到这个接口。
 */
interface PolicyControl {

    /** 让引擎在本次步骤执行前退出。与 stop() 的区别：不取消协程，由引擎循环自己收尾。 */
    fun requestStop(reason: String)

    /** 暂停，等用户点继续（引擎在 beforeStep 之后本来就会等这个门）。 */
    fun requestPause(reason: String)

    /** 跳过当前步骤，直接执行第 index 步（0 基）。越界由引擎负责校验并记日志。 */
    fun requestGoto(index: Int, reason: String)
}

/**
 * 把 [StepPolicy] 的四个钩子翻译成插件看得到的 [PolicyCall] / [PolicyDecision]。
 *
 * 三条设计约束（都是"不能让升级改变老行为"的推论）：
 * 1. **没装策略插件时必须与升级前完全一致**：内部始终先跑一份 [IntervalStepPolicy]
 *    （步骤之间的 intervalMs + 0..randomExtraMs），插件不在时就到此为止，
 *    超时 0、重复 1、下一步永远是 Next —— 与 [IntervalStepPolicy] 单独生效一模一样。
 * 2. **插件只做决定，不碰引擎**：超时和重复次数先记在本步的状态里，
 *    等引擎随后自己来问 [timeoutMs] / [repeatCount] 时再交出去（引擎的顺序是
 *    beforeStep → repeatCount → 执行（其间读 timeoutMs）→ onResult，所以"先记一下"来得及）。
 * 3. **插件抛异常不能拖垮规则**：decide() 全程 try/catch(Throwable)，失败就按默认值继续，
 *    只在日志里留一行。
 *
 * 线程：所有钩子都由引擎的协程调用；这里除了 delay() 之外不阻塞，
 * 访问插件与注册表的那几次调用本身也很轻（读内存 + 遍历已装载插件列表）。
 */
class PluginPolicyAdapter(
    private val pluginProvider: () -> StepPolicyPlugin? = { PolicyPluginHost.first()?.second },
    private val appContext: Context? = null,
    private val interval: StepPolicy = IntervalStepPolicy(),
    private val control: PolicyControl? = null
) : StepPolicy {

    /** 本步这一次访问的临时状态，按 step.id 存（同一条规则里 id 唯一）。 */
    private class Visit {
        var timeoutMs: Long = 0L
        var repeat: Int = 1
        var startedAt: Long = 0L
    }

    private val lock = Any()

    private val visits = HashMap<String, Visit>()

    /** 上一个已经 attach 过的策略插件实例：换插件（重装/重启用）时会重新 attach。 */
    private var attachedPlugin: StepPolicyPlugin? = null

    // -----------------------------------------------------------------------
    // StepPolicy
    // -----------------------------------------------------------------------

    override fun repeatCount(rule: Rule, step: Step): Int = synchronized(lock) {
        // 引擎紧接着执行，所以这个时刻最接近"本步开始跑"，AFTER 的 elapsedMs 由它算
        val visit = visitOfLocked(step)
        visit.startedAt = System.currentTimeMillis()
        visit.repeat.coerceIn(1, MAX_REPEAT)
    }

    override fun timeoutMs(rule: Rule, step: Step): Long =
        synchronized(lock) { visitOfLocked(step).timeoutMs.coerceIn(0L, MAX_TIMEOUT_MS) }

    override suspend fun beforeStep(rule: Rule, step: Step, index: Int) {
        // 1) 先补上"间隔 + 随机附加"。没装插件时这就是全部行为。
        interval.beforeStep(rule, step, index)

        val plugin = currentPlugin() ?: return

        // 2) 每次访问都从默认值重来：插件这次没提超时/重复，就是"不限时、执行一次"，
        //    不能沿用上一次访问的残留值。
        val visit = synchronized(lock) {
            val fresh = Visit()
            fresh.startedAt = System.currentTimeMillis()
            if (visits.size > VISIT_CACHE_LIMIT) visits.clear()
            visits[step.id] = fresh
            fresh
        }

        val call = PolicyCall(
            stepIndex = index,
            stepCount = rule.steps.size,
            typeId = typeIdOf(step),
            note = step.note,
            config = configOf(step),
            phase = PolicyPhase.BEFORE,
            // 引擎把"重复几次"整体交给策略，这一轮的第几次它自己也不知道，按契约固定传 1
            attempt = 1
        )

        when (val decision = askPlugin(plugin, call, index)) {
            null, PolicyDecision.Proceed -> Unit

            is PolicyDecision.Delay -> {
                val ms = decision.ms.coerceIn(0L, MAX_WAIT_MS)
                if (ms > 0L) delay(ms)
            }

            is PolicyDecision.Timeout -> synchronized(lock) {
                visit.timeoutMs = decision.ms.coerceIn(0L, MAX_TIMEOUT_MS)
            }

            is PolicyDecision.Repeat -> synchronized(lock) {
                visit.repeat = decision.times.coerceIn(1, MAX_REPEAT)
            }

            PolicyDecision.Stop -> {
                val channel = control
                if (channel == null) {
                    LogBus.warn("策略在步骤 " + (index + 1) + " 执行前要求停止，但引擎没有提供控制通道，已忽略")
                } else {
                    LogBus.warn("策略在步骤 " + (index + 1) + " 执行前要求停止")
                    channel.requestStop("策略要求停止")
                }
            }

            PolicyDecision.Pause -> {
                val channel = control
                if (channel == null) {
                    LogBus.warn("策略在步骤 " + (index + 1) + " 执行前要求暂停，但引擎没有提供控制通道，已忽略")
                } else {
                    LogBus.info("策略在步骤 " + (index + 1) + " 执行前要求暂停")
                    channel.requestPause("策略要求暂停")
                }
            }

            is PolicyDecision.Goto -> {
                val channel = control
                if (channel == null) {
                    LogBus.warn(
                        "策略在步骤 " + (index + 1) + " 执行前要求跳转到步骤 " + (decision.stepIndex + 1) +
                            "，但引擎没有提供控制通道，已忽略"
                    )
                } else {
                    channel.requestGoto(decision.stepIndex, "策略要求跳转")
                }
            }
        }
    }

    override suspend fun onResult(
        rule: Rule,
        step: Step,
        index: Int,
        result: StepResult
    ): FlowDecision {
        val plugin = currentPlugin() ?: return FlowDecision.Next

        val (repeat, startedAt) = synchronized(lock) {
            val visit = visitOfLocked(step)
            visit.repeat.coerceAtLeast(1) to visit.startedAt
        }
        val elapsed = if (startedAt > 0L) {
            (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        } else {
            0L
        }

        val call = PolicyCall(
            stepIndex = index,
            stepCount = rule.steps.size,
            typeId = typeIdOf(step),
            note = step.note,
            config = configOf(step),
            phase = PolicyPhase.AFTER,
            // 引擎只在全部重复执行完之后才问一次 AFTER，所以这里报的是"这次访问总共跑了几次"
            attempt = repeat,
            result = policyResultOf(result),
            elapsedMs = elapsed
        )

        return when (val decision = askPlugin(plugin, call, index)) {
            null, PolicyDecision.Proceed -> stepOwnDecision(result)

            is PolicyDecision.Goto -> FlowDecision.Goto(decision.stepIndex)

            PolicyDecision.Stop -> FlowDecision.Stop

            PolicyDecision.Pause -> FlowDecision.Pause

            is PolicyDecision.Delay -> {
                // AFTER 只做"下一步之前再等一下"
                val ms = decision.ms.coerceIn(0L, MAX_WAIT_MS)
                if (ms > 0L) delay(ms)
                FlowDecision.Next
            }

            is PolicyDecision.Repeat -> {
                LogBus.warn(
                    "策略在步骤 " + (index + 1) + " 执行后要求再重复 " + decision.times +
                        " 次，已经跑完的步骤无法回头补跑，本次忽略（重复请在执行前给出）"
                )
                FlowDecision.Next
            }

            is PolicyDecision.Timeout -> {
                // 步骤已经跑完，超时没有意义；如果插件是"顺手报一下配置"，忽略即可
                FlowDecision.Next
            }
        }
    }

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    /**
     * 策略返回 Proceed（或抛异常）时，看步骤自己有没有表达意图。
     * 步骤返回 [StepResult.Goto] 是插件步骤自带的跳转（flow-control.goto），
     * 策略没意见就照它执行；没装策略插件时这里不会被调用，老行为保持不变。
     */
    private fun stepOwnDecision(result: StepResult): FlowDecision =
        if (result is StepResult.Goto) FlowDecision.Goto(result.stepIndex) else FlowDecision.Next

    /** 取当前策略插件；换了实例就顺手 attach 一次（宿主装载插件后必须给它 PluginContext）。 */
    private fun currentPlugin(): StepPolicyPlugin? {
        val plugin = try {
            pluginProvider()
        } catch (t: Throwable) {
            LogBus.warn("读取策略插件失败，本步按默认策略执行：" + describe(t))
            return null
        } ?: return null

        if (plugin !== attachedPlugin) {
            attachedPlugin = plugin
            val ctx = appContext
            if (ctx == null) {
                LogBus.warn("策略插件没有绑定进程上下文，读不到配置，将一直按默认值决策")
            } else {
                PolicyPluginHost.attach(ctx, plugin)
            }
        }
        return plugin
    }

    /** 问一次插件。插件抛异常、返回非法值都在这里兜住，绝不让规则崩掉。 */
    private fun askPlugin(plugin: StepPolicyPlugin, call: PolicyCall, index: Int): PolicyDecision? =
        try {
            plugin.decide(call)
        } catch (t: Throwable) {
            LogBus.warn("策略插件在步骤 " + (index + 1) + " 的 " + call.phase + " 决策抛异常，按默认处理：" + describe(t))
            null
        }

    private fun visitOfLocked(step: Step): Visit {
        // 引擎是长生命周期的（服务在就一直在），换规则以后旧步骤的状态留着没用，攒多了就清一次
        if (visits.size > VISIT_CACHE_LIMIT) visits.clear()
        return visits.getOrPut(step.id) { Visit() }
    }

    private fun typeIdOf(step: Step): String = when (step) {
        is CoreStep -> CoreStep.TYPE
        is PluginStep -> step.typeId
    }

    /**
     * 给插件看一份配置副本：规则里的 JSONObject 是宿主自己的状态，
     * 插件即使改了也不该影响下一次执行。
     */
    private fun configOf(step: Step): JSONObject = when (step) {
        is CoreStep -> JSONObject()
            .put("x", step.x)
            .put("y", step.y)
            .put("durationMs", step.durationMs)

        is PluginStep -> runCatching { JSONObject(step.config.toString()) }.getOrElse { step.config }
    }

    private fun policyResultOf(result: StepResult): PolicyResult = when (result) {
        is StepResult.Success -> PolicyResult.SUCCESS
        is StepResult.Fail -> PolicyResult.FAIL
        is StepResult.Stop -> PolicyResult.STOP
        is StepResult.Pause -> PolicyResult.PAUSE
        is StepResult.Goto -> PolicyResult.GOTO
    }

    private companion object {
        /** 策略给的等待上限。再长就不像"节奏"而像卡死了。 */
        const val MAX_WAIT_MS = 600_000L

        /** 单步超时上限，防止插件写一个天文数字把引擎等到天荒地老。 */
        const val MAX_TIMEOUT_MS = 3_600_000L

        /** 单步重复上限（引擎自己还会再 coerce 到 1..10000）。 */
        const val MAX_REPEAT = 10_000

        /** 每步状态缓存的容量上限，超过就整体清空（同一条规则的步数远小于它）。 */
        const val VISIT_CACHE_LIMIT = 2_000
    }
}

/**
 * 策略插件在宿主里的落地方式：找到它、给它 attach。
 *
 * 为什么 attach 放在这里而不是 PluginManager：注册表只是"登记"，真正需要上下文的时机是
 * "引擎要开始问它了"。放在这里的好处是插件被重装/重新启用后，下一次决策前一定会重新 attach，
 * 不需要谁去通知。同一个插件实例只会 attach 一次。
 */
object PolicyPluginHost {

    /** 已经 attach 过的插件实例。用弱引用，插件被卸载后不会拖住它的类加载器。 */
    private val attached: MutableSet<StepPolicyPlugin> =
        Collections.newSetFromMap(WeakHashMap<StepPolicyPlugin, Boolean>())

    /**
     * 注册表里第一个策略插件及其归属 pluginId。
     *
     * 返回 pluginId 是必须的：插件自己的 prefs 是按 **AutoTapperPlugin.id** 隔离的
     * （PluginStepBridgeImpl 就是这么建的），策略这边必须用同一个 id 才能读到
     * 「流程设置」步骤写进去的配置。
     */
    fun first(): Pair<String, StepPolicyPlugin>? {
        val found = runCatching {
            PluginRegistry.extensions().firstOrNull { it.second is StepPolicyPlugin }
        }.getOrElse { t ->
            LogBus.warn("读取插件扩展列表失败，本次按默认策略执行：" + describe(t))
            return null
        } ?: return null

        val plugin = found.second as? StepPolicyPlugin ?: return null
        return found.first to plugin
    }

    /**
     * 给插件一个 PluginContext（读配置、写日志就靠它）。重复调用返回 false。
     *
     * 不给注入器（executorProvider 恒为 null）：策略只该做决定，
     * 给它注入能力反而绕过了引擎的步骤顺序。
     */
    fun attach(appContext: Context, plugin: StepPolicyPlugin): Boolean {
        val alreadyKnown = synchronized(attached) { attached.contains(plugin) }
        if (alreadyKnown) return false

        val pluginId = pluginIdOf(plugin)
        val pluginName = pluginNameOf(pluginId)

        return try {
            plugin.attach(
                PluginHostContext(
                    appContext = appContext,
                    pluginId = pluginId,
                    pluginName = pluginName,
                    executorProvider = { null },
                    capture = CapabilityHolder.capture,
                    ocr = CapabilityHolder.ocr
                )
            )
            synchronized(attached) { attached.add(plugin) }
            LogBus.info("策略插件「" + pluginName + "」已接入流程控制")
            true
        } catch (t: Throwable) {
            LogBus.warn("策略插件「" + pluginName + "」接入失败，本步按默认策略执行：" + describe(t))
            false
        }
    }

    private fun pluginIdOf(plugin: StepPolicyPlugin): String {
        val fromRegistry = runCatching { first() }.getOrNull()?.takeIf { it.second === plugin }?.first
        if (!fromRegistry.isNullOrBlank()) return fromRegistry
        return runCatching { plugin.id }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
    }

    private fun pluginNameOf(pluginId: String): String =
        runCatching { PluginRegistry.plugin(pluginId)?.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: pluginId
}

/** 异常转一句人话，带上最内层原因（插件里的反射/类加载错误常常套好几层）。 */
private fun describe(t: Throwable): String {
    var root: Throwable = t
    var guard = 0
    while (root.cause != null && root.cause !== root && guard < 8) {
        root = root.cause!!
        guard++
    }
    val head = t.javaClass.simpleName + (t.message?.let { ": " + it } ?: "")
    if (root === t) return head
    val tail = root.javaClass.simpleName + (root.message?.let { ": " + it } ?: "")
    return head + "（根本原因 " + tail + "）"
}
