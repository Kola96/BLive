# 弹幕渲染重写（单 View 统一绘制）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `SimpleDanmuView` 从「每条弹幕一个 TextView + ObjectAnimator」重写为「单 View 每帧统一 Canvas 绘制」，核心逻辑抽取为可 JVM 单测的 `DanmuEngine`。

**Architecture:** `DanmuEngine`（纯 Kotlin，注入时钟与文本测量函数）负责轨道分配、位置/进度计算、生命周期；`SimpleDanmuView`（薄壳，继承 `View`）负责帧循环调度与绘制。对外 API 不变。

**Tech Stack:** Kotlin、Android View/Canvas、JUnit4（`app/src/test`）。

## Global Constraints

- 设计依据：`docs/superpowers/specs/2026-09-08-danmu-rendering-rewrite-design.md`
- 对外 API 必须保持不变：`addDanmu(DanmuItem)`、`clear()`、`isDanmuEnabled`、`danmuSizeScale`、`danmuAlpha`、`danmuSpeedScale`、`danmuAreaRatio`
- `DanmuEngine` 不得 import 任何 `android.*` 类（保证 JVM 可测）
- 行为对齐：轨道 4–30 条按字号自适应；轨道满丢弃；基础时长 8000ms；关闭弹幕立即清屏；黑色描边宽度 = 1dp × 字号缩放；所有设置对在屏弹幕立即生效（速度变化重新锚定，不瞬移）
- 布局文件 `app/src/main/res/layout/activity_live_play.xml` 引用类名 `com.blive.tv.danmu.SimpleDanmuView`，类名与包名不得改变
- 本项目约定：不执行任何 git 提交/推送，除非用户明确要求

---

### Task 1: `DanmuEngine` 与单元测试（TDD）

**Files:**
- Create: `app/src/main/java/com/blive/tv/danmu/DanmuEngine.kt`
- Test: `app/src/test/java/com/blive/tv/danmu/DanmuEngineTest.kt`

**Interfaces:**
- Consumes: 无（独立组件）
- Produces（Task 2 依赖）:
  - `class DanmuEngine(clock: () -> Long, measurer: (String, Float) -> Float)`
  - `var viewWidthPx: Int`、`var viewHeightPx: Int`、`var textSizePx: Float`、`var speedScale: Float`、`var areaRatio: Float`、`var spacingPx: Float`
  - `val items: List<DanmuEngine.RenderItem>`、`val trackHeightPx: Float`、`val trackCount: Int`
  - `class RenderItem { val text: String; val colorRgb: Int; val trackIndex: Int; var anchorTimeMs: Long; var anchorProgress: Float; var widthPx: Float }`
  - `fun add(text: String, colorRgb: Int): Boolean`
  - `fun progress(item: RenderItem, now: Long = clock()): Float`
  - `fun x(item: RenderItem, now: Long = clock()): Float`
  - `fun prune(now: Long = clock())`、`fun clear()`、`fun reanchorAll(now: Long = clock())`、`fun rebuildLayout()`

- [ ] **Step 1: 写失败测试**

创建 `app/src/test/java/com/blive/tv/danmu/DanmuEngineTest.kt`：

```kotlin
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
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blive.tv.danmu.DanmuEngineTest"`
Expected: 编译失败，`Unresolved reference: DanmuEngine`

- [ ] **Step 3: 实现 DanmuEngine**

创建 `app/src/main/java/com/blive/tv/danmu/DanmuEngine.kt`：

```kotlin
package com.blive.tv.danmu

/**
 * 弹幕引擎：轨道分配、位置与生命周期计算。
 * 纯 Kotlin 实现（无 Android 依赖），时钟与文本测量由外部注入，便于 JVM 单元测试。
 *
 * 运动模型：每条弹幕记录锚点（anchorTimeMs, anchorProgress），
 * 当前进度 = anchorProgress + (now - anchorTimeMs) / durationMs；
 * 变速时先 reanchorAll 再改 speedScale，位置保持连续。
 */
class DanmuEngine(
    private val clock: () -> Long,
    private val measurer: (text: String, textSizePx: Float) -> Float
) {
    /** 在屏弹幕的渲染状态（调用方只读，引擎内部更新） */
    class RenderItem internal constructor(
        val text: String,
        val colorRgb: Int,
        val trackIndex: Int,
        var anchorTimeMs: Long,
        var anchorProgress: Float,
        var widthPx: Float
    )

    var viewWidthPx: Int = 0
    var viewHeightPx: Int = 0
    var textSizePx: Float = 0f

    var speedScale: Float = 1f
        set(value) { field = value.coerceIn(MIN_SPEED_SCALE, MAX_SPEED_SCALE) }

    var areaRatio: Float = 1f
        set(value) { field = value.coerceIn(MIN_AREA_RATIO, 1f) }

    /** 轨道复用判定所需的尾部间距（px） */
    var spacingPx: Float = 0f

    private val _items = mutableListOf<RenderItem>()

    /** 在屏弹幕（只读） */
    val items: List<RenderItem> get() = _items

    /** 每条轨道的最新占用者；轨道是否可复用由其进度判定 */
    private var trackOwners: Array<RenderItem?> = emptyArray()

    var trackCount: Int = DEFAULT_TRACK_COUNT
        private set

    /** 轨道高 = 字号 × 1.6（与原实现一致） */
    val trackHeightPx: Float get() = (textSizePx * TRACK_HEIGHT_RATIO).coerceAtLeast(1f)

    private val durationMs: Float get() = BASE_DURATION_MS / speedScale

    /** 添加弹幕；无空闲轨道时丢弃并返回 false（与原实现一致） */
    fun add(text: String, colorRgb: Int): Boolean {
        if (viewWidthPx <= 0 || textSizePx <= 0f) return false
        val now = clock()
        val width = measurer(text, textSizePx)
        val track = findFreeTrack(now) ?: return false
        val item = RenderItem(text, colorRgb, track, now, 0f, width)
        trackOwners[track] = item
        _items.add(item)
        return true
    }

    fun progress(item: RenderItem, now: Long = clock()): Float =
        item.anchorProgress + (now - item.anchorTimeMs) / durationMs

    /** 弹幕头部 x 坐标：从屏幕右缘外飞到完全飞出左缘 */
    fun x(item: RenderItem, now: Long = clock()): Float =
        viewWidthPx - progress(item, now) * (viewWidthPx + item.widthPx)

    /** 剔除已完全飞出屏幕的弹幕 */
    fun prune(now: Long = clock()) {
        _items.removeAll { progress(it, now) >= 1f }
    }

    fun clear() {
        _items.clear()
        trackOwners.fill(null)
    }

    /** 变速前调用：把在屏弹幕按当前进度重新锚定，保证位置连续不瞬移 */
    fun reanchorAll(now: Long = clock()) {
        for (item in _items) {
            item.anchorProgress = progress(item, now)
            item.anchorTimeMs = now
        }
    }

    /** 字号/显示区域/容器尺寸变化后调用：重建轨道并刷新文本宽度缓存 */
    fun rebuildLayout() {
        val newCount = computeTrackCount()
        if (newCount != trackCount || trackOwners.size != newCount) {
            val old = trackOwners
            trackOwners = arrayOfNulls(newCount)
            for (i in 0 until minOf(old.size, newCount)) {
                trackOwners[i] = old[i]
            }
            trackCount = newCount
        }
        if (textSizePx > 0f) {
            for (item in _items) {
                item.widthPx = measurer(item.text, textSizePx)
            }
        }
    }

    private fun findFreeTrack(now: Long): Int? {
        if (trackOwners.isEmpty()) rebuildLayout()
        for (i in trackOwners.indices) {
            val owner = trackOwners[i] ?: return i
            if (progress(owner, now) >= freeProgress(owner)) return i
        }
        return null
    }

    /** 弹幕尾部完全进入屏幕（含尾间距）时的进度，达到后轨道可复用 */
    private fun freeProgress(item: RenderItem): Float =
        (item.widthPx + spacingPx) / (viewWidthPx + item.widthPx)

    private fun computeTrackCount(): Int {
        if (viewHeightPx <= 0 || textSizePx <= 0f) return DEFAULT_TRACK_COUNT
        val effectiveHeight = (viewHeightPx * areaRatio).toInt().coerceAtLeast(1)
        val trackHeight = trackHeightPx.toInt().coerceAtLeast(1)
        return (effectiveHeight / trackHeight).coerceIn(MIN_TRACK_COUNT, MAX_TRACK_COUNT)
    }

    companion object {
        const val BASE_DURATION_MS = 8000f
        private const val DEFAULT_TRACK_COUNT = 10
        private const val MIN_TRACK_COUNT = 4
        private const val MAX_TRACK_COUNT = 30
        private const val TRACK_HEIGHT_RATIO = 1.6f
        private const val MIN_AREA_RATIO = 0.1f
        private const val MIN_SPEED_SCALE = 0.1f
        private const val MAX_SPEED_SCALE = 10f
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :app:testDebugUnitTest --tests "com.blive.tv.danmu.DanmuEngineTest"`
Expected: 8 个用例全部 PASS

---

### Task 2: 重写 `SimpleDanmuView` 为单 View 统一绘制

**Files:**
- Modify: `app/src/main/java/com/blive/tv/danmu/SimpleDanmuView.kt`（整体替换）

**Interfaces:**
- Consumes: Task 1 的 `DanmuEngine` 全部接口
- Produces: 对外 API 不变（`LivePlayActivity` 与布局 XML 零改动）：
  `addDanmu(DanmuItem)`、`clear()`、`isDanmuEnabled`、`danmuSizeScale`、`danmuAlpha`、`danmuSpeedScale`、`danmuAreaRatio`

- [ ] **Step 1: 整体替换 SimpleDanmuView.kt**

```kotlin
package com.blive.tv.danmu

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View

/**
 * 弹幕组件（单 View 统一绘制版）
 * 每帧一次 onDraw 绘制全部弹幕，替代原先"每条弹幕一个 TextView + ObjectAnimator"的实现，
 * 消除子 View 增删/测量/布局与多动画回调开销，降低弱设备上的 UI 线程与合成负载。
 * 运动与轨道逻辑在 [DanmuEngine]，本类只负责帧调度与绘制。
 */
class SimpleDanmuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val engine = DanmuEngine(
        clock = { System.currentTimeMillis() },
        measurer = { text, sizePx ->
            textPaint.textSize = sizePx
            textPaint.measureText(text)
        }
    )

    private var frameRunning = false

    var isDanmuEnabled = true
        set(value) {
            field = value
            if (!value) {
                engine.clear() // 关闭时清空当前弹幕（与原实现一致）
                invalidate()
            }
        }

    var danmuSizeScale = 1f // 字号缩放比例
        set(value) {
            field = value
            engine.textSizePx = scaledTextSizePx()
            engine.rebuildLayout()
            invalidate()
        }

    var danmuAlpha = 1f // 透明度（作用于填充色 alpha，绘制时生效）
        set(value) {
            field = value
            invalidate()
        }

    var danmuSpeedScale = 1f // 速度缩放比例（数值越大越快）
        set(value) {
            engine.reanchorAll() // 先按旧速度锚定当前位置，再切换速度，避免瞬移
            field = value
            engine.speedScale = value
            invalidate()
        }

    /** 弹幕显示区域占屏比例：1.0=全屏，0.5=上半屏。轨道只布局在顶部区域内。 */
    var danmuAreaRatio = 1f
        set(value) {
            field = value
            engine.areaRatio = value
            engine.rebuildLayout()
            invalidate()
        }

    init {
        engine.spacingPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics
        )
        engine.textSizePx = scaledTextSizePx()
    }

    fun addDanmu(item: DanmuItem) {
        if (!isDanmuEnabled) return
        post {
            val rgb = item.color and 0x00FFFFFF
            if (engine.add(item.text, rgb)) {
                ensureFrameLoop()
            }
        }
    }

    fun clear() {
        engine.clear()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        engine.viewWidthPx = w
        engine.viewHeightPx = h
        engine.rebuildLayout()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isDanmuEnabled || engine.items.isEmpty()) return

        val now = System.currentTimeMillis()
        textPaint.textSize = engine.textSizePx
        val trackHeight = engine.trackHeightPx
        val fm = textPaint.fontMetrics
        val alpha = (danmuAlpha * 255).toInt().coerceIn(0, 255)

        // 第一遍：统一描边（黑色不透明，与原实现一致）
        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 1f * danmuSizeScale, resources.displayMetrics
        )
        textPaint.color = Color.BLACK
        textPaint.alpha = 255
        for (item in engine.items) {
            canvas.drawText(item.text, engine.x(item, now), baselineY(item, trackHeight, fm), textPaint)
        }

        // 第二遍：统一填充（用户透明度作用于填充色）
        textPaint.style = Paint.Style.FILL
        textPaint.strokeWidth = 0f
        for (item in engine.items) {
            textPaint.color = item.colorRgb
            textPaint.alpha = alpha
            canvas.drawText(item.text, engine.x(item, now), baselineY(item, trackHeight, fm), textPaint)
        }
    }

    /** 文本基线 y：在所属轨道内垂直居中 */
    private fun baselineY(
        item: DanmuEngine.RenderItem,
        trackHeight: Float,
        fm: Paint.FontMetrics
    ): Float {
        val textHeight = fm.descent - fm.ascent
        val top = item.trackIndex * trackHeight + (trackHeight - textHeight) / 2f
        return top - fm.ascent
    }

    private val frameCallback = object : Runnable {
        override fun run() {
            if (!isDanmuEnabled) {
                frameRunning = false
                return
            }
            engine.prune()
            if (engine.items.isEmpty()) {
                frameRunning = false
                invalidate() // 最后一帧清屏
                return
            }
            invalidate()
            postOnAnimation(this)
        }
    }

    private fun ensureFrameLoop() {
        if (!frameRunning) {
            frameRunning = true
            postOnAnimation(frameCallback)
        }
    }

    private fun scaledTextSizePx(): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SP * danmuSizeScale, resources.displayMetrics
    )

    companion object {
        private const val BASE_TEXT_SP = 20f
    }
}
```

注意：`DanmuItem` 的 `speed`/`size`/`type` 字段原实现也未使用（原实现只用 `text` 与 `color`），保持一致，不在本次范围内处理。

- [ ] **Step 2: 编译验证**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 确认无其他引用残留**

Run: `grep -r "StrokedTextView\|ObjectAnimator" app/src/main/java/com/blive/tv/danmu/`
Expected: 无匹配（旧实现类已随文件替换删除）

---

### Task 3: 全量验证

**Files:** 无新增

- [ ] **Step 1: 全量单元测试**

Run: `./gradlew :app:testDebugUnitTest`
Expected: 全部 PASS（含既有 DanmuParserTest / WbiSignerTest / PlayStreamResolverTest 等，共 34 + 新增 8 个用例）

- [ ] **Step 2: Debug 包构建**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 真机验证清单（用户侧执行）**

- 弹幕正常滚动、颜色/描边观感与原版本一致
- 设置面板调节字号/透明度/速度/显示区域：在屏弹幕立即生效、速度切换无瞬移
- 关闭弹幕开关：立即清屏；重新打开：新弹幕正常
- 高密度弹幕直播间（如电台）在电视/老设备上对比流畅度

- [ ] **Step 4: 向用户提议提交（由用户确认后执行）**

建议提交信息：`perf: 弹幕渲染改为单 View 统一绘制，降低弱设备负载`
涉及文件：`DanmuEngine.kt`（新增）、`DanmuEngineTest.kt`（新增）、`SimpleDanmuView.kt`（重写）、设计/计划文档
