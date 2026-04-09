package com.blive.tv.ui.play

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.animation.ValueAnimator

/**
 * 关注动画辅助类
 * 效果：粉色从左向右填充（关注）或从左向右褪去（取关）
 */
class FollowAnimationHelper(private val view: View) {

    private var animator: ValueAnimator? = null

    // 动画进度 0-1
    private var progress: Float = 0f

    // 是否为取消关注（褪去）模式
    private var isClearing: Boolean = false

    // 底部画笔（实心粉色）
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4081")
        style = Paint.Style.FILL
    }

    // 渐变画笔
    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val rect = RectF()

    // 按钮圆角
    private val cornerRadius = 48f

    fun startAnimation(clearing: Boolean, duration: Long, onEnd: () -> Unit) {
        animator?.cancel()

        isClearing = clearing
        progress = 0f

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            addUpdateListener {
                progress = it.animatedValue as Float
                view.invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd()
                }
            })
            start()
        }
    }

    fun cancel() {
        animator?.cancel()
        animator = null
    }

    fun draw(canvas: Canvas) {
        val w = view.width.toFloat()
        val h = view.height.toFloat()

        if (w <= 0 || h <= 0) return

        rect.set(0f, 0f, w, h)

        // 绘制底部实心层
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, solidPaint)

        // 绘制渐变层（clipRect 控制从左向右显示/隐藏）
        val shader = if (isClearing) {
            // 取关模式：渐变从左向右，透明 -> 粉色
            LinearGradient(0f, 0f, w, 0f,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#FF4081")),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP)
        } else {
            // 关注模式：渐变从左向右，粉色 -> 透明
            LinearGradient(0f, 0f, w, 0f,
                intArrayOf(Color.parseColor("#FF4081"), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP)
        }
        gradientPaint.shader = shader

        // clipRect 从 0 扩展到 w，决定渐变层的可见范围
        canvas.save()
        val clipRight = w * progress
        if (clipRight > 0) {
            canvas.clipRect(0f, 0f, clipRight, h)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, gradientPaint)
        }
        canvas.restore()
    }

    companion object {
        // 从右向左的 clip
        fun drawFill(canvas: Canvas, w: Float, h: Float, progress: Float, cornerRadius: Float) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF4081")
                style = Paint.Style.FILL
            }
            val rect = RectF(0f, 0f, w, h)
            paint.alpha = (255 * progress).toInt()
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        }
    }
}
