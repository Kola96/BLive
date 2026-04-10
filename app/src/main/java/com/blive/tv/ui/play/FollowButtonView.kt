package com.blive.tv.ui.play

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator

/**
 * 自定义关注按钮背景视图
 * 支持从左向右填充/褪去动画
 */
class FollowButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 是否已关注
    var isFollowing: Boolean = false
        private set

    // 动画进度控制
    private var leftProgress: Float = 0f
    private var rightProgress: Float = 0f

    private var startLeft: Float = 0f
    private var targetLeft: Float = 0f
    private var startRight: Float = 0f
    private var targetRight: Float = 0f

    private val animator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f)

    // 圆角 (24dp 适配 48dp 高度)
    private val cornerRadius = 24f * resources.displayMetrics.density

    // 画笔
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF4081")
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = Color.parseColor("#40FF4081")
    }

    private val rect = RectF()

    // 回调
    var onAnimationEnd: ((Boolean) -> Unit)? = null

    init {
        animator.interpolator = LinearInterpolator()
        animator.addUpdateListener { animation ->
            val fraction = animation.animatedFraction
            leftProgress = startLeft + (targetLeft - startLeft) * fraction
            rightProgress = startRight + (targetRight - startRight) * fraction
            invalidate()
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                // 如果是取关完成，重置进度为 0，以便下次关注能从左向右填充
                if (!isFollowing && leftProgress == rightProgress) {
                    leftProgress = 0f
                    rightProgress = 0f
                }
                onAnimationEnd?.invoke(isFollowing)
            }
        })
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        if (w <= 0 || h <= 0) return

        // 底层：边框（未关注状态的样子）
        // 边框描边是居中绘制的，为了和 XML 的 shape 完美对齐，需要向内偏移半个线宽
        val halfStroke = borderPaint.strokeWidth / 2f
        rect.set(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
        
        borderPaint.alpha = 255
        // 对于向内偏移的边框，圆角也需减去偏移量，以保持内外弧度一致
        val borderCorner = cornerRadius - halfStroke
        canvas.drawRoundRect(rect, borderCorner, borderCorner, borderPaint)

        // 填充层
        if (rightProgress > leftProgress) {
            canvas.save()
            val clipLeft = w * leftProgress
            val clipRight = w * rightProgress
            canvas.clipRect(clipLeft, 0f, clipRight, h)
            
            // 填充层应铺满整个控件，所以 rect 不偏移
            rect.set(0f, 0f, w, h)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, solidPaint)
            canvas.restore()
        }
    }

    /**
     * 开始关注/取关动画
     * @param follow true=关注，false=取关
     */
    fun animateToState(follow: Boolean, duration: Long = 2000L) {
        if (follow == isFollowing && leftProgress == targetLeft && rightProgress == targetRight) {
            return
        }

        // 如果当前是全空状态，确保从 0,0 开始，以保证自左向右填充
        if (leftProgress == rightProgress) {
            leftProgress = 0f
            rightProgress = 0f
        }

        startLeft = leftProgress
        startRight = rightProgress

        if (follow) {
            // 关注：向右填满
            targetLeft = 0f
            targetRight = 1f
        } else {
            // 取关：左侧向右追赶，直到褪去
            targetLeft = rightProgress
            targetRight = rightProgress
        }

        val distLeft = Math.abs(targetLeft - startLeft)
        val distRight = Math.abs(targetRight - startRight)
        val maxDist = Math.max(distLeft, distRight)

        animator.cancel()
        animator.duration = (duration * maxDist).toLong().coerceAtLeast(10L)
        animator.start()

        isFollowing = follow
    }

    /**
     * 设置状态（无动画）
     */
    fun setFollowingState(following: Boolean) {
        animator.cancel()
        isFollowing = following
        if (following) {
            leftProgress = 0f
            rightProgress = 1f
        } else {
            leftProgress = 0f
            rightProgress = 0f
        }
        targetLeft = leftProgress
        targetRight = rightProgress
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
