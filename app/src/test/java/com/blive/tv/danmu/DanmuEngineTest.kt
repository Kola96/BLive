package com.blive.tv.danmu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DanmuEngine 轨道分配 / 位置计算 / 生命周期测试。
 * 假时钟 + 确定性测量函数（宽度 = 字符数 × 字号），与 Android 无关。
 */
class DanmuEngineTest {

    private var now = 0L

    /** 默认：viewWidth=1000, viewHeight=100, textSize=50 → 轨道高 80 → 100/80=1 → 钳制为 4 条轨道 */
    private fun newEngine(
        viewWidth: Int = 1000,
        viewHeight: Int = 100,
        textSize: Float = 50f,
        spacing: Float = 0f
    ): DanmuEngine {
        val engine = DanmuEngine(clock = { now }, measurer = { text, size -> text.length * size })
        engine.viewWidthPx = viewWidth
        engine.viewHeightPx = viewHeight
        engine.textSizePx = textSize
        engine.spacingPx = spacing
        engine.rebuildLayout()
        return engine
    }

    @Test
    fun `first danmu takes track 0, second takes track 1`() {
        val engine = newEngine()
        assertTrue(engine.add("aa", 0xFFFFFF))
        assertTrue(engine.add("bb", 0xFFFFFF))
        assertEquals(0, engine.items[0].trackIndex)
        assertEquals(1, engine.items[1].trackIndex)
    }

    @Test
    fun `danmu is dropped when all tracks are occupied`() {
        val engine = newEngine()
        repeat(4) { assertTrue(engine.add("t$it", 0xFFFFFF)) }
        assertFalse(engine.add("overflow", 0xFFFFFF))
        assertEquals(4, engine.items.size)
    }

    @Test
    fun `track is reused after owner fully enters screen`() {
        val engine = newEngine()
        // "ab" 宽 100，viewWidth 1000 → freeProgress = 100/1100 ≈ 0.0909 → 8000ms × 0.0909 ≈ 727ms 后轨道空闲
        assertTrue(engine.add("ab", 0xFFFFFF))
        now = 800
        assertTrue(engine.add("cd", 0xFFFFFF))
        assertEquals(0, engine.items[1].trackIndex)
    }

    @Test
    fun `x position interpolates from right edge to off-screen left`() {
        val engine = newEngine()
        engine.add("ab", 0xFFFFFF) // 宽 100
        val item = engine.items[0]
        assertEquals(1000f, engine.x(item, 0L), 0.001f)
        assertEquals(450f, engine.x(item, 4000L), 0.001f)  // 1000 - 0.5×(1000+100)
        assertEquals(-100f, engine.x(item, 8000L), 0.001f) // 完全飞出
    }

    @Test
    fun `prune removes items that fully left screen`() {
        val engine = newEngine()
        engine.add("ab", 0xFFFFFF)
        now = 8000
        engine.prune()
        assertTrue(engine.items.isEmpty())
    }

    @Test
    fun `speed change re-anchors items without position jump`() {
        val engine = newEngine()
        engine.add("ab", 0xFFFFFF) // 宽 100
        val item = engine.items[0]
        now = 2000
        val xBefore = engine.x(item, now) // progress 0.25 → x = 1000-0.25×1100 = 725
        assertEquals(725f, xBefore, 0.001f)

        engine.reanchorAll(now)
        engine.speedScale = 2f // duration 8000 → 4000

        // 变速瞬间位置不变
        assertEquals(xBefore, engine.x(item, now), 0.001f)
        // 之后按新速度运动：now=3000 → progress = 0.25 + 1000/4000 = 0.5 → x = 450
        assertEquals(450f, engine.x(item, 3000L), 0.001f)
    }

    @Test
    fun `area ratio change rebuilds track count`() {
        val engine = newEngine(viewHeight = 1000, textSize = 50f) // 1000/80 = 12 条轨道
        assertEquals(12, engine.trackCount)
        engine.areaRatio = 0.5f
        engine.rebuildLayout()
        assertEquals(6, engine.trackCount)
    }

    @Test
    fun `clear empties items and frees tracks`() {
        val engine = newEngine()
        engine.add("ab", 0xFFFFFF)
        engine.clear()
        assertTrue(engine.items.isEmpty())
        assertTrue(engine.add("cd", 0xFFFFFF))
        assertEquals(0, engine.items[0].trackIndex)
    }
}
