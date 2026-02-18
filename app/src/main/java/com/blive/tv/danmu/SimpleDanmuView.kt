package com.blive.tv.danmu

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import java.util.LinkedList
import java.util.Queue
import kotlin.random.Random

/**
 * 简易弹幕组件
 * 基于 ViewGroup + TextView + ObjectAnimator 实现
 */
class SimpleDanmuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val trackCount = 10 // 默认10个轨道
    private val tracks = LongArray(trackCount) { 0L } // 轨道可用时间戳，0表示空闲
    private val danmuQueue: Queue<DanmuItem> = LinkedList()
    
    // 配置参数
    var isDanmuEnabled = true
        set(value) {
            field = value
            visibility = if (value) View.VISIBLE else View.INVISIBLE
            if (!value) {
                removeAllViews() // 关闭时清空当前弹幕
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
        }
    var danmuAlpha = 1.0f     // 透明度
        set(value) {
            field = value
            // 用户要求：调节后的弹幕应用这个透明度即可，已经生成的弹幕不用管了
        }
        
    var danmuSpeedScale = 1.0f // 速度缩放比例 (数值越大越快)

    companion object {
        private const val TAG = "SimpleDanmuView"
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
        // 寻找可用轨道
        val trackIndex = findAvailableTrack()
        if (trackIndex == -1) {
            // 没有可用轨道，暂时丢弃（或者可以加入等待队列，这里简单处理直接丢弃）
            return
        }

        // 创建弹幕 View
        val textView = TextView(context).apply {
            text = item.text
            // 解析颜色，通过Alpha通道控制透明度
            val alpha = (danmuAlpha * 255).toInt().coerceIn(0, 255)
            val rgb = item.color.toLong() and 0x00FFFFFFL
            val textColor = (alpha.toLong() shl 24 or rgb).toInt()
            Log.d(TAG, "textColor: $textColor")
            setTextColor(textColor)
            
            // 移除阴影以提升显示效果
            // setShadowLayer(3f, 1f, 1f, shadowColor)
            // setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f * danmuSizeScale) // 基础字号 20sp
            // alpha = danmuAlpha // 不再使用 View.alpha 控制透明度
            maxLines = 1
            includeFontPadding = false
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 测量 View 大小
        val spec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        textView.measure(spec, spec)
        val viewWidth = textView.measuredWidth
        val viewHeight = textView.measuredHeight

        // 计算轨道高度
        val trackHeight = height / trackCount
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
                removeView(textView) // 动画结束移除 View
            }
        })
        
        // 保存动画引用以便后续变速
        textView.tag = animator

        animator.start()
    }

    private fun updateRunningDanmuSpeed() {
        for (i in 0 until childCount) {
            val view = getChildAt(i)
            val oldAnimator = view.tag as? ObjectAnimator ?: continue
            
            if (oldAnimator.isRunning) {
                val currentX = view.translationX
                val viewWidth = view.width
                val endX = -viewWidth.toFloat()
                
                // 取消旧动画
                oldAnimator.removeAllListeners()
                oldAnimator.cancel()
                
                // 计算剩余距离和新时长
                val totalDistance = width + viewWidth
                val remainingDistance = currentX - endX
                val newTotalDuration = (8000 / danmuSpeedScale).toLong()
                
                val newDuration = if (totalDistance > 0) {
                    (newTotalDuration * (remainingDistance / totalDistance.toFloat())).toLong()
                } else {
                    0L
                }
                
                if (newDuration > 0) {
                    val newAnimator = ObjectAnimator.ofFloat(view, "translationX", currentX, endX)
                    newAnimator.duration = newDuration
                    newAnimator.interpolator = LinearInterpolator()
                    newAnimator.addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            removeView(view)
                        }
                    })
                    newAnimator.start()
                    view.tag = newAnimator
                } else {
                    removeView(view)
                }
            }
        }
    }

    private fun findAvailableTrack(): Int {
        val now = System.currentTimeMillis()
        for (i in 0 until trackCount) {
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
        removeAllViews()
        for (i in tracks.indices) {
            tracks[i] = 0L
        }
    }
}
