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
