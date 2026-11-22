package com.example.autotapper.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 步骤的编解码与旧配置迁移。
 *
 * 为什么值得单独测：规则 JSON 是用户数据的唯一载体，编码/解码里漏一个字段
 * 就会在升级后把用户排好的步骤弄丢或弄乱，而这件事在界面上完全看不出来。
 */
class StepJsonTest {

    @Test(timeout = 5_000L)
    fun coreStepRoundTrip() {
        val step = CoreStep(
            id = "s1",
            enabled = false,
            note = "第一下",
            x = 12,
            y = 34,
            durationMs = 120L
        )

        val json = StepJson.encode(step)

        assertEquals("core.tap", json.getString("type"))
        assertEquals("s1", json.getString("id"))
        assertEquals(false, json.getBoolean("enabled"))
        assertEquals("第一下", json.getString("note"))

        val back = StepJson.decode(json)

        assertTrue(back is CoreStep)
        // enabled = false 必须原样保留：用户手动停用的步骤不能在存盘后自己复活
        assertEquals(false, back!!.enabled)
        assertEquals(step, back)
    }

    @Test(timeout = 5_000L)
    fun coreStepNoteNullAndMissingFields() {
        // note = null 存成空串，读回来还是 null，不会变成一句空字符串显示在界面上
        val step = CoreStep(id = "s2", x = 0, y = 0)
        val back = StepJson.decode(StepJson.encode(step)) as CoreStep
        assertNull(back.note)
        assertTrue(back.enabled)

        // 最老的数据只有 x / y，其余字段都靠默认值兜住
        val legacy = JSONObject().put("type", "core.tap").put("x", 5).put("y", 6)
        val decoded = StepJson.decode(legacy) as CoreStep
        assertEquals(5, decoded.x)
        assertEquals(6, decoded.y)
        assertEquals(CoreStep.DEFAULT_DURATION_MS, decoded.durationMs)
        assertTrue(decoded.enabled)
        assertTrue("缺 id 时必须补一个，否则引擎按 id 记状态会撞车", decoded.id.isNotBlank())
    }

    @Test(timeout = 5_000L)
    fun pluginStepRoundTrip() {
        val config = JSONObject().put("times", 3).put("ms", 42L)
        val step = PluginStep(
            id = "p1",
            enabled = true,
            note = null,
            pluginId = "tap-styles",
            typeId = "tap-styles.longpress",
            config = config
        )

        val json = StepJson.encode(step)

        assertEquals("plugin", json.getString("type"))
        assertEquals("tap-styles", json.getString("pluginId"))
        assertEquals("tap-styles.longpress", json.getString("typeId"))

        val back = StepJson.decode(json) as PluginStep

        assertEquals(step.id, back.id)
        assertEquals(step.pluginId, back.pluginId)
        assertEquals(step.typeId, back.typeId)
        assertEquals(3, back.config.optInt("times"))
        assertEquals(42L, back.config.optLong("ms"))
        assertNull(back.note)
        assertEquals(step.summary(), back.summary())
    }

    @Test(timeout = 5_000L)
    fun unknownTypeDecodesToNull() {
        // 未来版本的步骤类型不该让整条规则解析失败，只丢这一步
        assertNull(StepJson.decode(JSONObject().put("type", "future.mystery")))
        assertNull(StepJson.decode(JSONObject()))
        assertNull(StepJson.decode(null))
    }

    @Test(timeout = 5_000L)
    fun pluginStepWithoutIdsDecodesToNull() {
        // 没有 pluginId / typeId 的插件步骤在宿主里既找不到类型也显示不了，只能丢
        assertNull(StepJson.decode(JSONObject().put("type", "plugin").put("typeId", "x.y")))
        assertNull(StepJson.decode(JSONObject().put("type", "plugin").put("pluginId", "x")))
        assertNull(
            StepJson.decode(
                JSONObject().put("type", "plugin").put("pluginId", "  ").put("typeId", "  ")
            )
        )
    }

    @Test(timeout = 5_000L)
    fun decodeListSkipsBadEntries() {
        assertTrue(StepJson.decodeList(null).isEmpty())

        val array = JSONArray()
        array.put(StepJson.encode(CoreStep(id = "a", x = 1, y = 2)))
        array.put("这不是对象")
        array.put(JSONObject().put("type", "unknown.step"))
        array.put(JSONObject().put("type", "plugin").put("pluginId", "").put("typeId", ""))
        array.put(StepJson.encode(PluginStep(id = "b", pluginId = "pi", typeId = "pi.do")))

        val decoded = StepJson.decodeList(array)

        // 三条坏条目被跳过，好的一条都不少
        assertEquals(2, decoded.size)
        assertEquals("a", decoded[0].id)
        assertEquals("b", decoded[1].id)
    }

    @Test(timeout = 5_000L)
    fun summariesAreReadable() {
        assertEquals("点击 12,34 按住 120 ms", CoreStep(id = "s", x = 12, y = 34, durationMs = 120L).summary())
        // 插件没给 __summary 时回退到 "插件 id / 类型 id"
        assertEquals("pi / pi.do", PluginStep(id = "p", pluginId = "pi", typeId = "pi.do").summary())
        assertEquals(
            "长按 42 ms × 3 次",
            PluginStep(
                id = "p",
                pluginId = "pi",
                typeId = "pi.do",
                config = JSONObject().put("__summary", "长按 42 ms × 3 次")
            ).summary()
        )
    }

    @Test(timeout = 5_000L)
    fun migrateLegacyNothingToMigrate() {
        // 没有旧点位就不迁移：否则每次启动都会多出一条空规则
        assertNull(StepJson.migrateLegacyPoints(null, 100L, 80L, 3))
        assertNull(StepJson.migrateLegacyPoints("", 100L, 80L, 3))
        assertNull(StepJson.migrateLegacyPoints("   ", 100L, 80L, 3))
        assertNull(StepJson.migrateLegacyPoints("[]", 100L, 80L, 3))
        assertNull(StepJson.migrateLegacyPoints("这不是 JSON", 100L, 80L, 3))
        // 坐标非法（负数）的点位同样视为空
        assertNull(StepJson.migrateLegacyPoints("[{\"x\":-1,\"y\":5}]", 100L, 80L, 3))
    }

    @Test(timeout = 5_000L)
    fun migrateLegacyTwoPointsKeepsLoopParams() {
        val raw = "[{\"x\":10,\"y\":20},{\"x\":30,\"y\":40}]"

        val rule = StepJson.migrateLegacyPoints(
            pointsJson = raw,
            intervalMs = 250L,
            randomExtraMs = 90L,
            repeatCount = 5
        )

        assertNotNull(rule)
        val migrated = rule!!
        assertEquals(2, migrated.steps.size)
        assertEquals("间隔/轮数必须原样搬到 loop 上，升级后手感不变", 5, migrated.loopCount)
        assertEquals(250L, migrated.intervalMs)
        assertEquals(90L, migrated.randomExtraMs)
        assertEquals("基础点击", migrated.name)
        assertTrue(migrated.id.isNotBlank())

        val first = migrated.steps[0] as CoreStep
        val second = migrated.steps[1] as CoreStep
        assertEquals(10, first.x)
        assertEquals(20, first.y)
        assertEquals(30, second.x)
        assertEquals(40, second.y)
        assertTrue("两个点的 step.id 必须不同", first.id != second.id)
    }
}
