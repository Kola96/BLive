package com.blive.tv.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    // ---------------- 基础比较 ----------------

    @Test
    fun `equal versions compare to zero`() {
        assertEquals(0, VersionComparator.compare("1.0.0", "1.0.0"))
        assertEquals(0, VersionComparator.compare("1.0", "1.0.0"))
        assertEquals(0, VersionComparator.compare("2", "2.0.0"))
    }

    @Test
    fun `v prefix is ignored`() {
        assertEquals(0, VersionComparator.compare("v1.0.2", "1.0.2"))
        assertEquals(0, VersionComparator.compare("V1.0.2", "1.0.2"))
        assertTrue(VersionComparator.isNewer("v1.1.0", "1.0.9"))
    }

    @Test
    fun `numeric segments compare numerically not lexically`() {
        // 关键回归：字符串比较会把 1.0.10 判为小于 1.0.9
        assertTrue(VersionComparator.isNewer("1.0.10", "1.0.9"))
        assertFalse(VersionComparator.isNewer("1.0.9", "1.0.10"))
        assertTrue(VersionComparator.isNewer("1.10.0", "1.9.0"))
    }

    @Test
    fun `shorter version is zero padded`() {
        // 1.0 与 1.0.0 相等
        assertEquals(0, VersionComparator.compare("1.0", "1.0.0"))
        // 1.0 vs 1.0.1：补零后 1.0.0 < 1.0.1
        assertTrue(VersionComparator.isNewer("1.0.1", "1.0"))
        assertFalse(VersionComparator.isNewer("1.0", "1.0.1"))
    }

    @Test
    fun `major minor patch each dominate lower levels`() {
        assertTrue(VersionComparator.isNewer("2.0.0", "1.99.99"))
        assertTrue(VersionComparator.isNewer("1.2.0", "1.1.99"))
        assertTrue(VersionComparator.isNewer("1.0.3", "1.0.2"))
        assertFalse(VersionComparator.isNewer("1.0.2", "1.0.2"))
    }

    // ---------------- 边界与容错 ----------------

    @Test
    fun `blank segments are filtered`() {
        // "1..0" split 出空段，normalize 过滤后与 "1.0" 等价
        assertEquals(0, VersionComparator.compare("1..0", "1.0"))
        assertEquals(0, VersionComparator.compare(" 1.0.1 ", "1.0.1"))
    }

    @Test
    fun `prerelease suffix never throws and degrades gracefully`() {
        // 非数字段按字面降级比较，不应抛异常
        VersionComparator.compare("1.0.2-beta", "1.0.2")
        VersionComparator.compare("1.0.2", "1.0.2-beta")
        VersionComparator.compare("1.0.2-beta", "1.0.3")
        // 既定行为：第三段 "2-beta" 与 "2" 字面比较，"2-beta" > "2" → beta 被判为更新
        // （语义上不理想，但这是实现的降级策略；此用例锁定现状防意外变更）
        assertTrue(VersionComparator.isNewer("1.0.2-beta", "1.0.2"))
    }

    @Test
    fun `single number versions compare correctly`() {
        assertTrue(VersionComparator.isNewer("2", "1"))
        assertFalse(VersionComparator.isNewer("1", "2"))
        assertEquals(0, VersionComparator.compare("1", "1.0"))
    }
}
