package com.blive.tv.ui.play

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.animation.ValueAnimator

/**
 * 自定义关注按钮视图
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

    // 动画进度 0-1
    private var animationProgress: Float = 0f

    // 是否正在动画
    private var isAnimating: Boolean = false

    // 动画类型
    private enum class AnimationType { FILL, CLEAR }
    private var currentAnimationType: AnimationType = AnimationType.FILL

    private val animator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f)

    // 圆角
    private val cornerRadius = 48f * resources.displayMetrics.density

    // 画笔
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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
        animator.addUpdateListener { animation ->
            animationProgress = animation.animatedValue as Float
            invalidate()
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                isAnimating = false
                onAnimationEnd?.invoke(isFollowing)
            }
        })
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 固定尺寸
        val desiredWidth = (120 * resources.displayMetrics.density).toInt()
        val desiredHeight = (48 * resources.displayMetrics.density).toInt()

        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)

        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        if (w <= 0 || h <= 0) return

        rect.set(0f, 0f, w, h)

        if (isAnimating) {
            // 动画中
            drawAnimatingState(canvas, w, h)
        } else {
            // 静态
            drawStaticState(canvas, w, h)
        }
    }

    private fun drawStaticState(canvas: Canvas, w: Float, h: Float) {
        if (isFollowing) {
            // 已关注：实心粉色
            solidPaint.color = Color.parseColor("#FF4081")
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, solidPaint)
            // 画心形和文字（简化）
        } else {
            // 未关注：透明背景 + 粉色边框
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
        }
    }

    private fun drawAnimatingState(canvas: Canvas, w: Float, h: Float) {
        // 底层：边框（未关注状态的样子）
        borderPaint.alpha = 255
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

        // 渐变填充层
        if (animationProgress > 0) {
            val shader = if (currentAnimationType == AnimationType.FILL) {
                // 关注模式：透明 -> 粉色（从左向右填充）
                LinearGradient(0f, 0f, w, 0f,
                    intArrayOf(Color.parseColor("#FF4081"), Color.parseColor("#FF4081")),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP)
            } else {
                // 取关模式：粉色 -> 透明（从左向右褪去）
                LinearGradient(0f, 0f, w, 0f,
                    intArrayOf(Color.parseColor("#FF4081"), Color.parseColor("#FF4081")),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP)
            }
            solidPaint.shader = shader

            // clipRect 从 0 扩展到 w，决定渐变层的可见范围
            canvas.save()
            val clipRight = w * animationProgress
            canvas.clipRect(0f, 0f, clipRight, h)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, solidPaint)
            canvas.restore()
        }
    }

    /**
     * 开始关注/取关动画
     * @param follow true=关注，false=取关
     */
    fun animateToState(follow: Boolean, duration: Long = 2000L) {
        if (follow == isFollowing && !isAnimating) {
            // 已经是目标状态
            return
        }

        currentAnimationType = if (follow) AnimationType.FILL else AnimationType.CLEAR
        isAnimating = true
        animationProgress = 0f

        animator.cancel()
        animator.duration = duration
        animator.start()

        // 更新状态
        isFollowing = follow
    }

    /**
     * 设置状态（无动画）
     */
    fun setFollowingState(following: Boolean) {
        if (isAnimating) {
            animator.cancel()
            isAnimating = false
        }
        isFollowing = following
        animationProgress = 1f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }
}
