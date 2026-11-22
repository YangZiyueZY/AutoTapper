package com.example.autotapper.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 一条规则 = 一串步骤 + 循环设置。
 * 旧版本里只有「点位 + 间隔 + 轮数」三个参数，它们现在落到 loop 上，语义保持一致。
 */
data class Rule(
    val id: String,
    val name: String,
    val steps: List<Step> = emptyList(),
    val loopCount: Int = 0,          // 0 = 无限循环
    val intervalMs: Long = 100L,     // 每步之间的间隔
    val randomExtraMs: Long = 80L,   // 间隔上再随机加 0..randomExtraMs
    val updatedAt: Long = 0L
) {

    val isInfinite: Boolean get() = loopCount <= 0

    fun withSteps(newSteps: List<Step>): Rule = copy(steps = newSteps, updatedAt = System.currentTimeMillis())

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("loopCount", loopCount)
        .put("intervalMs", intervalMs)
        .put("randomExtraMs", randomExtraMs)
        .put("updatedAt", updatedAt)
        .put("steps", JSONArray().apply { steps.forEach { put(StepJson.encode(it)) } })

    companion object {
        fun empty(name: String = "新规则"): Rule =
            Rule(id = UUID.randomUUID().toString(), name = name, updatedAt = System.currentTimeMillis())

        fun fromJson(obj: JSONObject): Rule {
            val steps = StepJson.decodeList(obj.optJSONArray("steps"))
            return Rule(
                id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = obj.optString("name", "未命名规则"),
                steps = steps,
                loopCount = obj.optInt("loopCount", 0),
                intervalMs = obj.optLong("intervalMs", 100L).coerceAtLeast(0L),
                randomExtraMs = obj.optLong("randomExtraMs", 0L).coerceAtLeast(0L),
                updatedAt = obj.optLong("updatedAt", 0L)
            )
        }
    }
}
