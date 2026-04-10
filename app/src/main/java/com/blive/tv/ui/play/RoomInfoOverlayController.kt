package com.blive.tv.ui.play

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator

class RoomInfoOverlayController(
    private val roomInfoOverlay: View,
    private val playerView: View,
    private val logTag: String
) {
    var isVisible: Boolean = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable { hide() }
    private val autoDismissDelayMs = 15000L

    fun show() {
        if (isVisible) {
            return
        }
        isVisible = true
        roomInfoOverlay.visibility = View.VISIBLE
        val animatedView = getAnimatedContentView()
        animatedView.animate().cancel()
        animatedView.alpha = 0f
        animatedView.animate()
            .alpha(1f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        Log.d(logTag, "显示房间信息Overlay")
        scheduleAutoDismiss()
    }

    fun hide() {
        if (!isVisible) {
            return
        }
        isVisible = false
        cancelAutoDismiss()
        roomInfoOverlay.animate().cancel()
        roomInfoOverlay.visibility = View.GONE
        roomInfoOverlay.clearFocus()
        playerView.requestFocus()
        Log.d(logTag, "隐藏房间信息Overlay")
    }

    fun toggle() {
        if (isVisible) {
            hide()
        } else {
            show()
        }
    }

    private fun scheduleAutoDismiss() {
        cancelAutoDismiss()
        handler.postDelayed(autoDismissRunnable, autoDismissDelayMs)
        Log.d(logTag, "计划${autoDismissDelayMs}ms后自动隐藏Overlay")
    }

    private fun cancelAutoDismiss() {
        handler.removeCallbacks(autoDismissRunnable)
    }

    /**
     * 重置自动消失计时器（用户交互时可调用）
     */
    fun resetAutoDismissTimer() {
        if (isVisible) {
            scheduleAutoDismiss()
        }
    }

    private fun getAnimatedContentView(): View {
        return (roomInfoOverlay as? ViewGroup)?.getChildAt(0) ?: roomInfoOverlay
    }
}
