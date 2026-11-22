package com.example.autotapper.model

import kotlin.random.Random
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 区域的随机取点与几何计算。
 *
 * 区域取点越界会让插件的点击落到别的控件上，所以这里成批抽样并逐点校验。
 */
class RegionTest {

    private val samples = 500

    @Test(timeout = 5_000L)
    fun randomPointStaysInsideRect() {
        val region = Region(id = "r1", label = "区域一", x = 100, y = 200, w = 40, h = 30)
        val random = Random(1234)

        repeat(samples) {
            val point = region.randomPoint(random)
            assertTrue("点 " + point + " 不在区域 " + region + " 内", region.contains(point.x, point.y))
            assertTrue("x 越界：" + point.x, point.x >= 100 && point.x < 140)
            assertTrue("y 越界：" + point.y, point.y >= 200 && point.y < 230)
        }
    }

    @Test(timeout = 5_000L)
    fun unitRegionAlwaysHitsTheOnlyPoint() {
        // 旧版本的点位就是用 w = h = 1 表示的，取点必须恒定落在那一个像素上
        val region = Region.point("r2", "点位", 321, 654)
        assertEquals(1, region.w)
        assertEquals(1, region.h)

        repeat(100) { assertEquals(TapCoord(321, 654), region.randomPoint()) }
        assertTrue(region.contains(321, 654))
        assertFalse(region.contains(322, 654))
    }

    @Test(timeout = 5_000L)
    fun degenerateSizeDoesNotThrow() {
        // 宽高为 0（用户拖出一个零面积区域）或负数（脏数据）都不该抛异常
        val zero = Region("r3", "", 10, 20, 0, 0)
        assertEquals(TapCoord(10, 20), zero.randomPoint())

        val negative = Region("r4", "", 10, 20, -5, -3)
        assertEquals(TapCoord(10, 20), negative.randomPoint())
    }

    @Test(timeout = 5_000L)
    fun containsExcludesFarEdges() {
        val region = Region("r5", "", 0, 0, 10, 10)
        assertTrue(region.contains(0, 0))
        assertTrue(region.contains(9, 9))
        // 右/下边界是开区间：否则相邻两块区域会在边界上重复命中
        assertFalse(region.contains(10, 5))
        assertFalse(region.contains(5, 10))
        assertFalse(region.contains(-1, 0))
    }

    @Test(timeout = 5_000L)
    fun geometryAndJson() {
        val region = Region("r6", "右下", 10, 20, 30, 40)

        assertEquals(40, region.right)
        assertEquals(60, region.bottom)
        assertEquals(25, region.centerX)
        assertEquals(40, region.centerY)
        assertEquals(TapCoord(25, 40), region.center)

        val back = Region.fromJson(region.toJson())
        assertEquals(region, back)
    }

    @Test(timeout = 5_000L)
    fun jsonToleratesDirtyData() {
        // 缺坐标：这条区域不可用，返回 null 让调用方跳过
        assertNull(Region.fromJson(JSONObject().put("id", "x")))
        // 负坐标：屏幕外，同样丢弃
        assertNull(Region.fromJson(JSONObject().put("x", -3).put("y", 5)))

        val fixed = Region.fromJson(JSONObject().put("x", 1).put("y", 2).put("w", 0).put("h", 0))
        assertNotNull(fixed)
        assertEquals(1, fixed!!.w)
        assertEquals(1, fixed.h)
    }
}
