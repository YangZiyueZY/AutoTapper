package com.example.autotapper.model

import org.json.JSONObject
import kotlin.random.Random

/**
 * 一个取值区间。min == max 表示固定值，min < max 表示每次取随机值。
 * 整数字面量统一用 Long，避免毫秒/像素在 JSON 里来回转换时丢精度。
 */
data class Range(val min: Long, val max: Long) {

    val isFixed: Boolean get() = min == max

    fun coerceAtLeast(floor: Long): Range = Range(min.coerceAtLeast(floor), max.coerceAtLeast(floor))

    /** 取一次值；区间非法（min > max）时按单值处理，绝不让上层拿到越界结果。 */
    fun sample(random: Random = Random.Default): Long =
        when {
            max <= min -> min
            else -> random.nextLong(min, max + 1)
        }

    fun toJson(): JSONObject = JSONObject().put("min", min).put("max", max)

    companion object {
        val ZERO = Range(0, 0)

        fun of(value: Long): Range = Range(value, value)

        fun of(value: Int): Range = of(value.toLong())

        fun fromJson(obj: JSONObject?): Range {
            if (obj == null) return ZERO
            val min = obj.optLong("min", 0L)
            val max = obj.optLong("max", min)
            return if (max < min) Range(min, min) else Range(min, max)
        }
    }
}
