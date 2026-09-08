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
