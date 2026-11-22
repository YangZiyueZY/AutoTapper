package com.example.autotapper.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Step 的编解码。手写而不是上 kotlinx.serialization：工程里已经在用 org.json，
 * 这里保持零新依赖，也方便把旧版本的配置一次性迁移过来。
 */
object StepJson {

    fun encode(step: Step): JSONObject = when (step) {
        is CoreStep -> JSONObject()
            .put("type", CoreStep.TYPE)
            .put("id", step.id)
            .put("enabled", step.enabled)
            .put("note", step.note ?: "")
            .put("x", step.x)
            .put("y", step.y)
            .put("durationMs", step.durationMs)

        is PluginStep -> JSONObject()
            .put("type", PluginStep.TYPE)
            .put("id", step.id)
            .put("enabled", step.enabled)
            .put("note", step.note ?: "")
            .put("pluginId", step.pluginId)
            .put("typeId", step.typeId)
            .put("config", step.config)
    }

    fun decodeList(array: JSONArray?): List<Step> {
        if (array == null) return emptyList()
        val out = ArrayList<Step>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            decode(obj)?.let { out.add(it) }
        }
        return out
    }

    fun decode(obj: JSONObject?): Step? {
        if (obj == null) return null
        val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
        val enabled = obj.optBoolean("enabled", true)
        val note = obj.optString("note").ifBlank { null }
        return when (obj.optString("type")) {
            CoreStep.TYPE -> CoreStep(
                id = id,
                enabled = enabled,
                note = note,
                x = obj.optInt("x", 0),
                y = obj.optInt("y", 0),
                durationMs = obj.optLong("durationMs", CoreStep.DEFAULT_DURATION_MS)
            )

            PluginStep.TYPE -> {
                val pluginId = obj.optString("pluginId")
                val typeId = obj.optString("typeId")
                if (pluginId.isBlank() || typeId.isBlank()) {
                    null
                } else {
                    PluginStep(
                        id = id,
                        enabled = enabled,
                        note = note,
                        pluginId = pluginId,
                        typeId = typeId,
                        config = obj.optJSONObject("config") ?: JSONObject()
                    )
                }
            }

            else -> null
        }
    }

    /**
     * 旧版本配置迁移：以前只有一串点位 + 间隔 + 随机附加 + 轮数。
     * 迁移成一个 CoreStep 列表，参数原样搬到 loop 上，行为与升级前一致。
     */
    fun migrateLegacyPoints(
        pointsJson: String?,
        intervalMs: Long,
        randomExtraMs: Long,
        repeatCount: Int
    ): Rule? {
        val points = parseLegacyPoints(pointsJson) ?: return null
        if (points.isEmpty()) return null
        val steps = points.map { (x, y) ->
            CoreStep(id = UUID.randomUUID().toString(), x = x, y = y)
        }
        return Rule(
            id = UUID.randomUUID().toString(),
            name = "基础点击",
            steps = steps,
            loopCount = repeatCount,
            intervalMs = intervalMs,
            randomExtraMs = randomExtraMs,
            updatedAt = System.currentTimeMillis()
        )
    }

    /** 兼容旧单点字段：pointsJson 为空时再看 tap_x / tap_y。 */
    private fun parseLegacyPoints(raw: String?): List<Pair<Int, Int>>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val x = obj.optInt("x", -1)
                    val y = obj.optInt("y", -1)
                    if (x >= 0 && y >= 0) add(x to y)
                }
            }
        }.getOrNull()
    }
}
