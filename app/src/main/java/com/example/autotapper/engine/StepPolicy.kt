package com.example.autotapper.engine

import com.example.autotapper.model.Rule
import com.example.autotapper.model.Step
import com.example.autotapper.plugin.StepResult

/**
 * 步骤执行策略。默认实现就是"按顺序、每步之间等一会儿"。
 * flow-control 插件装上以后会注册自己的策略，于是延迟、超时、随机次数、
 * 成功/失败分支这些"支配全部步骤"的能力也不需要写死在引擎里。
 */
interface StepPolicy {

    /** 这一轮这个步骤要执行几次（随机次数由策略决定）。 */
    fun repeatCount(rule: Rule, step: Step): Int = 1

    /** 单步超时；0 表示不限时。 */
    fun timeoutMs(rule: Rule, step: Step): Long = 0L

    /** 执行前的等待（延迟 / 随机时间）。 */
    suspend fun beforeStep(rule: Rule, step: Step, index: Int) = Unit

    /** 拿到结果后决定下一步去哪。 */
    suspend fun onResult(
        rule: Rule,
        step: Step,
        index: Int,
        result: StepResult
    ): FlowDecision = FlowDecision.Next
}

sealed interface FlowDecision {
    object Next : FlowDecision
    data class Goto(val index: Int) : FlowDecision
    object Stop : FlowDecision
    object Pause : FlowDecision
}

/**
 * 默认策略：步骤之间等待 rule.intervalMs + 0..rule.randomExtraMs。
 * 与升级前的行为一致（那时的"间隔 + 随机附加"就是这个意思）。
 */
class IntervalStepPolicy(
    private val random: kotlin.random.Random = kotlin.random.Random.Default
) : StepPolicy {

    override suspend fun beforeStep(rule: Rule, step: Step, index: Int) {
        if (index == 0) return
        val wait = rule.intervalMs + if (rule.randomExtraMs > 0) {
            random.nextLong(rule.randomExtraMs + 1)
        } else {
            0L
        }
        if (wait > 0) kotlinx.coroutines.delay(wait)
    }
}
