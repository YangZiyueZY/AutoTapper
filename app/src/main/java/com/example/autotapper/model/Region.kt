package com.example.autotapper.model

import org.json.JSONObject
import kotlin.random.Random

/** 屏幕绝对坐标上的一个点（px）。 */
data class TapCoord(val x: Int, val y: Int)

/**
 * 可点击区域。一个孤立的点用 w = h = 1 表示，所以旧版本的点位可以直接迁移过来。
 * 区域内的点击位置在 [randomPoint] 里均匀随机。
 */
data class Region(
    val id: String,
    val label: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
) {

    val right: Int get() = x + w
    val bottom: Int get() = y + h
    val centerX: Int get() = x + w / 2
    val centerY: Int get() = y + h / 2
    val center: TapCoord get() = TapCoord(centerX, centerY)

    /** 区域内均匀随机取一点。宽高为 1 时就是那个点本身。 */
    fun randomPoint(random: Random = Random.Default): TapCoord {
        val px = if (w <= 1) x else random.nextInt(x, x + w)
        val py = if (h <= 1) y else random.nextInt(y, y + h)
        return TapCoord(px, py)
    }

    fun contains(px: Int, py: Int): Boolean = px in x until right && py in y until bottom

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("label", label)
        .put("x", x)
        .put("y", y)
        .put("w", w)
        .put("h", h)

    companion object {
        fun point(id: String, label: String, x: Int, y: Int): Region = Region(id, label, x, y, 1, 1)

        fun fromJson(obj: JSONObject): Region? {
            if (!obj.has("x") || !obj.has("y")) return null
            val x = obj.optInt("x", -1)
            val y = obj.optInt("y", -1)
            if (x < 0 || y < 0) return null
            return Region(
                id = obj.optString("id", ""),
                label = obj.optString("label", ""),
                x = x,
                y = y,
                w = obj.optInt("w", 1).coerceAtLeast(1),
                h = obj.optInt("h", 1).coerceAtLeast(1)
            )
        }
    }
}
