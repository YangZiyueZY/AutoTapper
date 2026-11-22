package com.example.autotapper.model

import kotlin.random.Random
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取值区间的抽样与 JSON 往返。
 *
 * 抽样只要有一次越界，用户的"随机间隔"就会变成负等待或超长卡死，
 * 所以这里用固定种子成批抽样，而不是只测一次。
 */
class RangeTest {

    /** 抽样次数：区间内每个整数被覆盖到的概率足够高，同时用例仍然是毫秒级。 */
    private val samples = 500

    @Test(timeout = 5_000L)
    fun minEqualsMaxIsFixedValue() {
        val range = Range(7L, 7L)
        assertTrue(range.isFixed)
        repeat(50) { assertEquals(7L, range.sample()) }
    }

    @Test(timeout = 5_000L)
    fun invalidRangeCollapsesToMin() {
        // min > max 是脏数据，按固定值处理，绝不让上层拿到越界值
        val range = Range(10L, 2L)
        assertFalse(range.isFixed)
        repeat(50) { assertEquals(10L, range.sample()) }
    }

    @Test(timeout = 5_000L)
    fun sampleAlwaysInsideRange() {
        val min = -3L
        val max = 5L
        val range = Range(min, max)
        val random = Random(20240101)

        repeat(samples) {
            val value = range.sample(random)
            assertTrue("抽样越界：" + value + " 不在 [" + min + ", " + max + "]", value in min..max)
        }
    }

    @Test(timeout = 5_000L)
    fun sampleCoversBothEnds() {
        // 固定种子 ⇒ 用例可复现；两端都能取到说明 nextLong 是闭区间写法
        val range = Range(0L, 3L)
        val random = Random(7)
        val seen = HashSet<Long>()

        repeat(samples) { seen.add(range.sample(random)) }

        assertEquals(setOf(0L, 1L, 2L, 3L), seen)
    }

    @Test(timeout = 5_000L)
    fun jsonRoundTripAndDirtyData() {
        val range = Range(60L, 120L)
        assertEquals(range, Range.fromJson(range.toJson()))

        assertEquals(Range.ZERO, Range.fromJson(null))
        // 只有 min：当成固定值
        assertEquals(Range(30L, 30L), Range.fromJson(JSONObject().put("min", 30)))
        // max < min：收敛成固定值，不产生越界区间
        assertEquals(Range(30L, 30L), Range.fromJson(JSONObject().put("min", 30).put("max", 5)))
        // 空对象：全用兜底值
        assertEquals(Range.ZERO, Range.fromJson(JSONObject()))
    }

    @Test(timeout = 5_000L)
    fun factoryAndCoerce() {
        assertEquals(Range(9L, 9L), Range.of(9))
        assertEquals(Range(9L, 9L), Range.of(9L))
        assertTrue(Range.of(9).isFixed)

        val coerced = Range(-40L, 15L).coerceAtLeast(0L)
        assertEquals(0L, coerced.min)
        assertEquals(15L, coerced.max)
        // 两端都被抬到地板以上，抬完可能变成固定值
        val bothBelow = Range(-40L, -10L).coerceAtLeast(0L)
        assertEquals(Range(0L, 0L), bothBelow)
        assertTrue(bothBelow.isFixed)
    }
}
