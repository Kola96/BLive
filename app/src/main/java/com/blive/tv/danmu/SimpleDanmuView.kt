package com.blive.tv.danmu

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatTextView

/**
 * 简易弹幕组件
 * 基于 ViewGroup + TextView + ObjectAnimator 实现
 */
class SimpleDanmuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var currentTrackCount = DEFAULT_TRACK_COUNT
    private var tracks = LongArray(DEFAULT_TRACK_COUNT) { 0L }

    /** 弹幕 View 回收池：弹幕是高频创建/销毁对象，回收复用避免 GC 抖动 */
    private val textViewPool = ArrayDeque<StrokedTextView>()
    
    // 配置参数
    var isDanmuEnabled = true
        set(value) {
            field = value
            visibility = if (value) View.VISIBLE else View.INVISIBLE
            if (!value) {
                clearAllDanmuViews() // 关闭时清空当前弹幕
            }
        }
    
    var danmuSizeScale = 1.0f // 字号缩放比例
        set(value) {
            field = value
            // 更新现有弹幕字号
            for (i in 0 until childCount) {
                val view = getChildAt(i)
                if (view is TextView) {
                    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f * value)
                }
            }
            ensureTracksReady(forceRebuild = true)
        }
    var danmuAlpha = 1.0f     // 透明度
        set(value) {
            field = value
            // 用户要求：调节后的弹幕应用这个透明度即可，已经生成的弹幕不用管了
        }
        
    var danmuSpeedScale = 1.0f // 速度缩放比例 (数值越大越快)

    companion object {
        private const val DEFAULT_TRACK_COUNT = 10
        private const val MIN_TRACK_COUNT = 4
        private const val MAX_TRACK_COUNT = 30
        private const val BASE_TEXT_SP = 20f
        private const val TRACK_HEIGHT_RATIO = 1.6f
        private const val MAX_POOL_SIZE = 20
    }

    init {
        // 确保容器不拦截点击事件，让下层视频可以响应点击
        isClickable = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false
    }

    /**
     * 添加一条弹幕
     */
    fun addDanmu(item: DanmuItem) {
        if (!isDanmuEnabled) return

        // 在主线程执行
        post {
            createAndShowDanmu(item)
        }
    }

    private fun createAndShowDanmu(item: DanmuItem) {
        ensureTracksReady()

        // 寻找可用轨道
        val trackIndex = findAvailableTrack()
        if (trackIndex == -1) {
            // 没有可用轨道，暂时丢弃（或者可以加入等待队列，这里简单处理直接丢弃）
            return
        }

        // 从回收池复用或创建弹幕 View
        val textView = obtainTextView().apply {
            text = item.text
            // 解析颜色，通过Alpha通道控制透明度
            val alpha = (danmuAlpha * 255).toInt().coerceIn(0, 255)
            val rgb = item.color.toLong() and 0x00FFFFFFL
            val textColor = (alpha.toLong() shl 24 or rgb).toInt()
            setTextColor(textColor)

            setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE_TEXT_SP * danmuSizeScale) // 基础字号 20sp
        }

        // 测量 View 大小
        val spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        textView.measure(spec, spec)
        val viewWidth = textView.measuredWidth
        val viewHeight = textView.measuredHeight

        // 计算轨道高度
        val trackHeight = (height / currentTrackCount).coerceAtLeast(1)
        val topMargin = trackIndex * trackHeight + (trackHeight - viewHeight) / 2

        // 设置初始位置（屏幕右侧外）
        textView.x = width.toFloat()
        textView.y = topMargin.toFloat()

        addView(textView)

        // 计算动画时长 (基础时长 8000ms)
        val duration = (8000 / danmuSpeedScale).toLong()

        // 计算弹幕完全进入屏幕所需时间 (plus spacing)
        val spacing = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics)
        val timeToEnter = (duration * (viewWidth + spacing) / (width + viewWidth)).toLong()
        tracks[trackIndex] = System.currentTimeMillis() + timeToEnter
        
        // 创建平移动画
        val animator = ObjectAnimator.ofFloat(textView, "translationX", width.toFloat(), -viewWidth.toFloat())
        animator.duration = duration
        animator.interpolator = LinearInterpolator()
        
        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                recycleTextView(textView)
            }

            override fun onAnimationCancel(animation: Animator) {
                recycleTextView(textView)
            }
        })

        // 保存动画引用以便后续变速
        textView.tag = animator

        animator.start()
    }

    private fun obtainTextView(): StrokedTextView {
        val pooled = textViewPool.removeFirstOrNull()
        if (pooled != null) return pooled
        return StrokedTextView(context).apply {
            maxLines = 1
            includeFontPadding = false
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun recycleTextView(textView: StrokedTextView) {
        // cancel() 会连续触发 onAnimationCancel/onAnimationEnd，用 tag 防重复回收
        val animator = textView.tag as? ObjectAnimator ?: return
        animator.removeAllListeners()
        textView.tag = null
        removeView(textView)
        if (textViewPool.size < MAX_POOL_SIZE) {
            textViewPool.addLast(textView)
        }
    }

    /** 清空屏幕上的弹幕（动画中的先取消，统一回收到池） */
    private fun clearAllDanmuViews() {
        for (i in childCount - 1 downTo 0) {
            val view = getChildAt(i) as? StrokedTextView ?: continue
            (view.tag as? ObjectAnimator)?.cancel()
        }
        removeAllViews()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) {
            ensureTracksReady(forceRebuild = true)
        }
    }

    private fun ensureTracksReady(forceRebuild: Boolean = false) {
        val newTrackCount = calculateTrackCount(height)
        if (forceRebuild || newTrackCount != currentTrackCount || tracks.size != newTrackCount) {
            rebuildTracks(newTrackCount)
        }
    }

    private fun calculateTrackCount(viewHeight: Int): Int {
        if (viewHeight <= 0) return DEFAULT_TRACK_COUNT
        val textSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            BASE_TEXT_SP * danmuSizeScale,
            resources.displayMetrics
        )
        val targetTrackHeight = (textSizePx * TRACK_HEIGHT_RATIO).toInt().coerceAtLeast(1)
        val estimatedTrackCount = (viewHeight / targetTrackHeight).coerceAtLeast(1)
        return estimatedTrackCount.coerceIn(MIN_TRACK_COUNT, MAX_TRACK_COUNT)
    }

    private fun rebuildTracks(newTrackCount: Int) {
        val normalizedTrackCount = newTrackCount.coerceIn(MIN_TRACK_COUNT, MAX_TRACK_COUNT)
        val oldTracks = tracks
        val newTracks = LongArray(normalizedTrackCount) { 0L }
        val copyCount = minOf(oldTracks.size, newTracks.size)
        for (i in 0 until copyCount) {
            newTracks[i] = oldTracks[i]
        }
        tracks = newTracks
        currentTrackCount = normalizedTrackCount
    }

    private fun findAvailableTrack(): Int {
        val now = System.currentTimeMillis()
        for (i in tracks.indices) {
            if (now >= tracks[i]) {
                return i
            }
        }
        return -1 // 没有空闲轨道
    }
    
    /**
     * 清空所有弹幕
     */
    fun clear() {
        clearAllDanmuViews()
        for (i in tracks.indices) {
            tracks[i] = 0L
        }
    }

    inner class StrokedTextView(context: Context) : AppCompatTextView(context) {
        private var isDrawing = false

        override fun invalidate() {
            if (isDrawing) return
            super.invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (isDrawing) {
                super.onDraw(canvas)
                return
            }
            
            val originalColors = textColors
            
            isDrawing = true
            
            // 绘制描边
            paint.style = Paint.Style.STROKE
            // 描边宽度跟随字号缩放
            paint.strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f * danmuSizeScale, resources.displayMetrics)
            setTextColor(Color.BLACK)
            super.onDraw(canvas)
            
            // 绘制填充
            paint.style = Paint.Style.FILL
            setTextColor(originalColors)
            super.onDraw(canvas)
            
            isDrawing = false
        }
    }
}
