package com.example.autotapper.engine

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
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 策略决定 → 引擎流转决定的翻译。
 *
 * 这段翻译是"插件能支配全部步骤"的唯一出口：翻错一个分支，插件要求暂停会被当成继续，
 * 或者整条规则停不下来。所以每个 PolicyDecision 取值都单独对一次。
 *
 * 全部用假插件 + 假控制通道，不碰 android.*（unit test 里那些是抛异常的桩）。
 */
class PluginPolicyAdapterTest {

    private val rule = Rule(
        id = "rule",
        name = "测试规则",
        steps = listOf(
            CoreStep(id = "s1", x = 11, y = 22, durationMs = 70L),
            CoreStep(id = "s2", x = 33, y = 44)
        )
    )

    private val coreStep: Step = rule.steps[0]

    private val pluginStep: Step = PluginStep(
        id = "s3",
        pluginId = "pi",
        typeId = "pi.do",
        config = JSONObject().put("k", 1)
    )

    /** 按剧本回答并记录被问了什么的假策略插件。 */
    private class FakePolicy(private val answer: (PolicyCall) -> PolicyDecision) : StepPolicyPlugin {
        override val id: String = "fake.flow"
        val calls = ArrayList<PolicyCall>()
        override fun decide(call: PolicyCall): PolicyDecision {
            calls.add(call)
            return answer(call)
        }
    }

    /** 记录引擎控制通道收到的请求。 */
    private class RecordingControl : PolicyControl {
        var stopReason: String? = null
        var pauseReason: String? = null
        var gotoIndex: Int = -1
        var gotoReason: String? = null
        override fun requestStop(reason: String) { stopReason = reason }
        override fun requestPause(reason: String) { pauseReason = reason }
        override fun requestGoto(index: Int, reason: String) {
            gotoIndex = index
            gotoReason = reason
        }
    }

    /** 空实现的间隔策略，只记录被调用过几次。 */
    private class RecordingInterval : StepPolicy {
        val visited = ArrayList<Int>()
        override suspend fun beforeStep(rule: Rule, step: Step, index: Int) {
            visited.add(index)
        }
    }

    private fun adapterOf(
        plugin: StepPolicyPlugin?,
        control: PolicyControl? = null,
        interval: StepPolicy = RecordingInterval()
    ): PluginPolicyAdapter = PluginPolicyAdapter(
        pluginProvider = { plugin },
        appContext = null,
        interval = interval,
        control = control
    )

    private suspend fun decided(
        decision: PolicyDecision,
        result: StepResult,
        step: Step = coreStep,
        index: Int = 0
    ): FlowDecision = adapterOf(FakePolicy { decision }).onResult(rule, step, index, result)

    // -------------------------------------------------------------------
    // AFTER：PolicyDecision → FlowDecision
    // -------------------------------------------------------------------

    @Test(timeout = 5_000L)
    fun proceedMapsToNext() = runBlocking {
        assertSame(FlowDecision.Next, decided(PolicyDecision.Proceed, StepResult.Success()))
    }

    @Test(timeout = 5_000L)
    fun stopMapsToStop() = runBlocking {
        assertSame(FlowDecision.Stop, decided(PolicyDecision.Stop, StepResult.Success()))
    }

    @Test(timeout = 5_000L)
    fun pauseMapsToPause() = runBlocking {
        assertSame(FlowDecision.Pause, decided(PolicyDecision.Pause, StepResult.Success()))
    }

    @Test(timeout = 5_000L)
    fun gotoCarriesIndex() = runBlocking {
        assertEquals(FlowDecision.Goto(2), decided(PolicyDecision.Goto(2), StepResult.Success()))
        // 0 也是合法目标（回到第一步），不能被当成"没给"
        assertEquals(FlowDecision.Goto(0), decided(PolicyDecision.Goto(0), StepResult.Success()))
    }

    @Test(timeout = 5_000L)
    fun afterStepDelayRepeatTimeoutAreHarmless() = runBlocking {
        assertSame(FlowDecision.Next, decided(PolicyDecision.Delay(0L), StepResult.Success()))
        // 负数被收敛到 0，不会真等
        assertSame(FlowDecision.Next, decided(PolicyDecision.Delay(-5_000L), StepResult.Success()))
        // AFTER 阶段要求重复已经来不及，只能忽略
        assertSame(FlowDecision.Next, decided(PolicyDecision.Repeat(3), StepResult.Success()))
        // 步骤已跑完，超时没意义
        assertSame(FlowDecision.Next, decided(PolicyDecision.Timeout(1_000L), StepResult.Success()))
    }

    @Test(timeout = 5_000L)
    fun stepOwnGotoPassesThroughWhenPluginProceeds() = runBlocking {
        // 插件步骤自带跳转（StepResult.Goto），策略没意见时照它执行
        assertEquals(FlowDecision.Goto(1), decided(PolicyDecision.Proceed, StepResult.Goto(1)))
        assertSame(FlowDecision.Next, decided(PolicyDecision.Proceed, StepResult.Fail("没点到")))
        assertSame(FlowDecision.Next, decided(PolicyDecision.Proceed, StepResult.Stop))
    }

    // -------------------------------------------------------------------
    // 插件缺失 / 插件抛异常
    // -------------------------------------------------------------------

    @Test(timeout = 5_000L)
    fun brokenPluginFallsBackToNext() = runBlocking {
        val exploding = adapterOf(object : StepPolicyPlugin {
            override val id: String = "boom"
            override fun decide(call: PolicyCall): PolicyDecision =
                throw IllegalStateException("插件炸了")
        })
        assertSame(FlowDecision.Next, exploding.onResult(rule, coreStep, 0, StepResult.Success()))
        assertEquals("插件炸了也不能影响重复次数", 1, exploding.repeatCount(rule, coreStep))
    }

    @Test(timeout = 5_000L)
    fun providerFailureFallsBackToDefaults() = runBlocking {
        val adapter = PluginPolicyAdapter(
            pluginProvider = { throw IllegalStateException("读注册表失败") },
            appContext = null,
            interval = RecordingInterval(),
            control = null
        )
        assertSame(FlowDecision.Next, adapter.onResult(rule, coreStep, 0, StepResult.Success()))
        assertEquals(1, adapter.repeatCount(rule, coreStep))
        assertEquals(0L, adapter.timeoutMs(rule, coreStep))
    }

    @Test(timeout = 5_000L)
    fun withoutPluginBehaviourStaysAsBeforeUpgrade() = runBlocking {
        val interval = RecordingInterval()
        val adapter = adapterOf(null, control = null, interval = interval)

        adapter.beforeStep(rule, coreStep, 0)
        adapter.beforeStep(rule, coreStep, 1)

        // 间隔策略永远先跑（没装插件时它就是全部行为）
        assertEquals(listOf(0, 1), interval.visited)
        assertEquals(1, adapter.repeatCount(rule, coreStep))
        assertEquals(0L, adapter.timeoutMs(rule, coreStep))
        assertSame(FlowDecision.Next, adapter.onResult(rule, coreStep, 0, StepResult.Success()))
    }

    // -------------------------------------------------------------------
    // 传给插件的快照（PolicyCall）
    // -------------------------------------------------------------------

    @Test(timeout = 5_000L)
    fun policyCallCarriesSnapshot() = runBlocking {
        val plugin = FakePolicy { PolicyDecision.Proceed }
        val adapter = adapterOf(plugin)

        adapter.beforeStep(rule, coreStep, 1)
        val before = plugin.calls.last()

        assertEquals(PolicyPhase.BEFORE, before.phase)
        assertEquals(1, before.stepIndex)
        assertEquals(2, before.stepCount)
        assertEquals(CoreStep.TYPE, before.typeId)
        assertEquals(1, before.attempt)
        assertNull("BEFORE 阶段还没有结果", before.result)
        assertEquals(11, before.config.optInt("x"))
        assertEquals(22, before.config.optInt("y"))
        assertEquals(70L, before.config.optLong("durationMs"))

        adapter.onResult(rule, coreStep, 1, StepResult.Fail("没点到"))
        val after = plugin.calls.last()

        assertEquals(PolicyPhase.AFTER, after.phase)
        assertEquals(PolicyResult.FAIL, after.result)
        assertTrue("AFTER 阶段必须带耗时字段（允许 0）", after.elapsedMs >= 0L)
    }

    @Test(timeout = 5_000L)
    fun stepResultMapsToPolicyResult() = runBlocking {
        assertEquals(PolicyResult.SUCCESS, resultSeen(StepResult.Success()))
        assertEquals(PolicyResult.FAIL, resultSeen(StepResult.Fail("x")))
        assertEquals(PolicyResult.STOP, resultSeen(StepResult.Stop))
        assertEquals(PolicyResult.PAUSE, resultSeen(StepResult.Pause))
        assertEquals(PolicyResult.GOTO, resultSeen(StepResult.Goto(3)))
    }

    private suspend fun resultSeen(result: StepResult): PolicyResult? {
        val plugin = FakePolicy { PolicyDecision.Proceed }
        adapterOf(plugin).onResult(rule, coreStep, 0, result)
        return plugin.calls.last().result
    }

    @Test(timeout = 5_000L)
    fun pluginConfigIsACopy() = runBlocking {
        val plugin = FakePolicy { PolicyDecision.Proceed }
        adapterOf(plugin).beforeStep(rule, pluginStep, 2)

        val call = plugin.calls.last()
        assertEquals("pi.do", call.typeId)
        call.config.put("k", 999)
        // 插件改自己拿到的快照，不能改到规则里的配置（pluginStep 声明成 Step，取 config 要还原成 PluginStep）
        assertEquals(1, (pluginStep as PluginStep).config.optInt("k"))
    }

    // -------------------------------------------------------------------
    // BEFORE：重复次数 / 超时 / 控制通道
    // -------------------------------------------------------------------

    @Test(timeout = 5_000L)
    fun beforeStepRepeatAndTimeoutReachTheEngine() = runBlocking {
        var answer: PolicyDecision = PolicyDecision.Proceed
        val adapter = adapterOf(FakePolicy { answer })

        answer = PolicyDecision.Repeat(50_000)
        adapter.beforeStep(rule, coreStep, 0)
        assertEquals("重复次数必须夹到上限", 10_000, adapter.repeatCount(rule, coreStep))

        answer = PolicyDecision.Repeat(0)
        adapter.beforeStep(rule, coreStep, 0)
        assertEquals("重复次数最少 1 次", 1, adapter.repeatCount(rule, coreStep))

        answer = PolicyDecision.Timeout(10_000_000L)
        adapter.beforeStep(rule, coreStep, 0)
        assertEquals(3_600_000L, adapter.timeoutMs(rule, coreStep))

        answer = PolicyDecision.Timeout(-5L)
        adapter.beforeStep(rule, coreStep, 0)
        assertEquals(0L, adapter.timeoutMs(rule, coreStep))
    }

    @Test(timeout = 5_000L)
    fun visitStateResetsEveryVisit() = runBlocking {
        var answer: PolicyDecision = PolicyDecision.Proceed
        val adapter = adapterOf(FakePolicy { answer })

        answer = PolicyDecision.Repeat(5)
        adapter.beforeStep(rule, coreStep, 0)
        assertEquals(5, adapter.repeatCount(rule, coreStep))

        // 这次插件什么都没说 → 回到"执行一次"，不能沿用上一轮残留的 5 次
        answer = PolicyDecision.Proceed
        adapter.beforeStep(rule, coreStep, 0)
        assertEquals(1, adapter.repeatCount(rule, coreStep))
        assertEquals(0L, adapter.timeoutMs(rule, coreStep))
    }

    @Test(timeout = 5_000L)
    fun controlChannelReceivesStopPauseGoto() = runBlocking {
        val control = RecordingControl()
        var answer: PolicyDecision = PolicyDecision.Proceed
        val adapter = adapterOf(FakePolicy { answer }, control)

        answer = PolicyDecision.Stop
        adapter.beforeStep(rule, coreStep, 1)
        assertEquals("策略要求停止", control.stopReason)

        answer = PolicyDecision.Pause
        adapter.beforeStep(rule, coreStep, 1)
        assertEquals("策略要求暂停", control.pauseReason)

        answer = PolicyDecision.Goto(2)
        adapter.beforeStep(rule, coreStep, 1)
        assertEquals(2, control.gotoIndex)
        assertEquals("策略要求跳转", control.gotoReason)
    }

    @Test(timeout = 5_000L)
    fun controlChannelAbsentIsHarmless() = runBlocking {
        // 引擎没给控制通道时只该记一条日志，不能把异常抛到协程里
        val adapter = adapterOf(FakePolicy { PolicyDecision.Stop }, control = null)
        adapter.beforeStep(rule, coreStep, 0)
        assertEquals(1, adapter.repeatCount(rule, coreStep))
    }
}
