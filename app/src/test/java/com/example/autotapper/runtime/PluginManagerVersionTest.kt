package com.example.autotapper.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件版本比较（PluginManager.updates() 靠它判断"有没有新版本"）。
 *
 * 比错了的后果是双向的：判小了用户永远看不到更新，判大了每次同步都提示装同一个版本。
 */
class PluginManagerVersionTest {

    private fun compare(left: String, right: String): Int =
        PluginManager.compareVersions(left, right)

    @Test(timeout = 5_000L)
    fun patchSegmentIsNewer() {
        assertEquals(-1, compare("1.0.0", "1.0.1"))
        assertEquals(1, compare("1.0.1", "1.0.0"))
    }

    @Test(timeout = 5_000L)
    fun segmentsCompareAsNumbersNotText() {
        // 按字符串比会把 "1.10" 判成小于 "1.9"，于是用户永远收不到这次更新
        assertEquals(1, compare("1.10", "1.9"))
        assertEquals(-1, compare("1.9", "1.10"))
        assertEquals(1, compare("1.0.10", "1.0.9"))
    }

    @Test(timeout = 5_000L)
    fun missingSegmentsCountAsZero() {
        assertEquals(0, compare("2", "2.0.0"))
        assertEquals(0, compare("2.0.0", "2"))
        assertEquals(0, compare("1.2", "1.2.0"))
        assertEquals(-1, compare("2.0.0", "2.0.1"))
        assertEquals(1, compare("2.0.1", "2"))
    }

    @Test(timeout = 5_000L)
    fun equalVersionsCompareToZero() {
        assertEquals(0, compare("1.4.0", "1.4.0"))
        assertEquals(0, compare("0", "0.0.0"))
    }

    @Test(timeout = 5_000L)
    fun nonNumericSegmentComparesAsText() {
        // "1.0-beta" 这种：非数字段退化到字符串比较，不能抛异常
        assertTrue(compare("1.0-beta", "1.0-alpha") > 0)
        assertTrue(compare("1.0-alpha", "1.0-beta") < 0)
        // 大小写不敏感，避免 "1.0-BETA" 被当成另一个版本
        assertEquals(0, compare("1.0-BETA", "1.0-beta"))
    }

    @Test(timeout = 5_000L)
    fun surroundingWhitespaceIsIgnored() {
        assertEquals(0, compare(" 1.2.3 ", "1.2.3"))
        assertEquals(1, compare(" 1.2.4 ", "1.2.3"))
    }
}
