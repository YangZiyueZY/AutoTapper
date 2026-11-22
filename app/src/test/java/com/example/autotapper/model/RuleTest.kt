package com.example.autotapper.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则的不可变变换与 JSON 往返。
 *
 * withSteps 是 UI 改步骤列表时唯一的入口，它必须"换掉步骤、别动身份"，
 * 否则每改一次就变成另一条规则，用户的规则列表会越改越多。
 */
class RuleTest {

    @Test(timeout = 5_000L)
    fun withStepsKeepsIdentityAndBumpsUpdatedAt() {
        val rule = Rule(
            id = "rule-1",
            name = "押镖",
            steps = emptyList(),
            loopCount = 3,
            intervalMs = 250L,
            randomExtraMs = 60L,
            updatedAt = 0L
        )
        val newSteps = listOf<Step>(CoreStep(id = "a", x = 1, y = 1))

        val updated = rule.withSteps(newSteps)

        assertEquals(rule.id, updated.id)
        assertEquals(rule.name, updated.name)
        assertEquals(rule.loopCount, updated.loopCount)
        assertEquals(rule.intervalMs, updated.intervalMs)
        assertEquals(rule.randomExtraMs, updated.randomExtraMs)
        assertEquals(newSteps, updated.steps)
        assertTrue("updatedAt 应该被刷新，用来判断「更新了哪一条」", updated.updatedAt > rule.updatedAt)
        assertTrue("原对象不能被改（data class copy 语义）", rule.steps.isEmpty())
    }

    @Test(timeout = 5_000L)
    fun jsonRoundTrip() {
        val rule = Rule(
            id = "rule-2",
            name = "打坐",
            steps = listOf(
                CoreStep(id = "a", x = 1, y = 2, durationMs = 80L),
                PluginStep(
                    id = "b",
                    pluginId = "pi",
                    typeId = "pi.do",
                    config = JSONObject().put("k", "v")
                )
            ),
            loopCount = 2,
            intervalMs = 120L,
            randomExtraMs = 30L,
            updatedAt = 1690000000000L
        )

        val back = Rule.fromJson(rule.toJson())

        assertEquals(rule.id, back.id)
        assertEquals(rule.name, back.name)
        assertEquals(rule.loopCount, back.loopCount)
        assertEquals(rule.intervalMs, back.intervalMs)
        assertEquals(rule.randomExtraMs, back.randomExtraMs)
        assertEquals(rule.updatedAt, back.updatedAt)
        assertEquals(2, back.steps.size)
        assertEquals(rule.steps[0], back.steps[0])

        val pluginStep = back.steps[1] as PluginStep
        assertEquals("pi", pluginStep.pluginId)
        assertEquals("pi.do", pluginStep.typeId)
        assertEquals("v", pluginStep.config.optString("k"))
    }

    @Test(timeout = 5_000L)
    fun fromJsonToleratesDirtyData() {
        val fallback = Rule.fromJson(JSONObject())

        assertTrue("缺 id 时必须补一个", fallback.id.isNotBlank())
        assertEquals("未命名规则", fallback.name)
        assertEquals(100L, fallback.intervalMs)
        assertEquals(0L, fallback.randomExtraMs)
        assertEquals(0, fallback.loopCount)
        assertTrue(fallback.isInfinite)
        assertTrue(fallback.steps.isEmpty())

        // 负数间隔/随机附加：收敛到 0，绝不让引擎算出负的等待
        val dirty = Rule.fromJson(
            JSONObject()
                .put("id", "r")
                .put("name", "脏数据")
                .put("loopCount", -2)
                .put("intervalMs", -50L)
                .put("randomExtraMs", -1L)
        )
        assertEquals(0L, dirty.intervalMs)
        assertEquals(0L, dirty.randomExtraMs)
        assertTrue(dirty.isInfinite)
    }

    @Test(timeout = 5_000L)
    fun isInfiniteAndEmptyRule() {
        assertTrue(Rule(id = "r", name = "n", loopCount = 0).isInfinite)
        assertTrue(Rule(id = "r", name = "n", loopCount = -1).isInfinite)
        assertFalse(Rule(id = "r", name = "n", loopCount = 4).isInfinite)

        val empty = Rule.empty()
        assertNotNull(empty.id)
        assertTrue(empty.id.isNotBlank())
        assertTrue(empty.steps.isEmpty())
        assertEquals("新规则", empty.name)
    }
}
