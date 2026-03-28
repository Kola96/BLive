package com.blive.tv.ui.play

import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView

class PlaySettingsPanelController(
    private val settingsPanel: View,
    private val playerView: View,
    private val playSettingsRecyclerView: RecyclerView,
    private val playSettingsAdapter: PlaySettingsCategoryAdapter,
    private val qualityCategoryId: String,
    private val logTag: String
) {
    var isVisible: Boolean = false
        private set

    fun show() {
        if (isVisible) {
            return
        }
        isVisible = true
        settingsPanel.visibility = View.VISIBLE
        val animatedView = getAnimatedContentView()
        animatedView.animate().cancel()
        animatedView.alpha = 0f
        animatedView.translationY = -40f * settingsPanel.resources.displayMetrics.density
        animatedView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        Log.d(logTag, "显示设置面板")
        requestFocusToQualityOption("显示设置面板")
    }

    fun hide(onHide: () -> Unit) {
        if (!isVisible) {
            return
        }
        isVisible = false
        getAnimatedContentView().animate().cancel()
        settingsPanel.visibility = View.GONE
        onHide()
        settingsPanel.clearFocus()
        playerView.requestFocus()
        Log.d(logTag, "隐藏设置面板")
    }

    fun toggle(onHide: () -> Unit) {
        if (isVisible) {
            hide(onHide)
        } else {
            show()
        }
    }

    fun recoverFocusIfNeeded(keyCode: Int, currentFocusView: View?): Boolean {
        if (!isVisible) {
            return false
        }
        val isNavigationKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if (!isNavigationKey || isFocusOnValidItem(currentFocusView)) {
            return false
        }
        Log.d(logTag, "设置面板可见但焦点无效，重置焦点到画质选项")
        requestFocusToQualityOption("resetFocusToQualityOption")
        return true
    }

    private fun isFocusOnValidItem(currentFocusView: View?): Boolean {
        if (currentFocusView == null) {
            return false
        }
        val isOnCategoryItem = currentFocusView.parent is RecyclerView ||
            (currentFocusView.parent as? View)?.parent is RecyclerView
        val isOnOptionItem = currentFocusView.parent is RecyclerView ||
            (currentFocusView.parent as? View)?.parent is RecyclerView
        val isOnRecyclerView = currentFocusView is RecyclerView
        Log.d(
            logTag,
            "isFocusOnValidItem - isOnCategoryItem: $isOnCategoryItem, isOnOptionItem: $isOnOptionItem, isOnRecyclerView: $isOnRecyclerView"
        )
        return isOnCategoryItem || isOnOptionItem || isOnRecyclerView
    }

    private fun requestFocusToQualityOption(prefix: String) {
        settingsPanel.post {
            val categories = playSettingsAdapter.getCategories()
            val position = categories.indexOfFirst { it.id == qualityCategoryId }
            if (position >= 0) {
                val viewHolder = playSettingsRecyclerView.findViewHolderForAdapterPosition(position)
                if (viewHolder != null) {
                    viewHolder.itemView.requestFocus()
                    Log.d(logTag, "$prefix - 请求焦点到画质选项成功")
                } else {
                    playSettingsRecyclerView.requestFocus()
                    Log.d(logTag, "$prefix - 请求焦点到playSettingsRecyclerView")
                }
            } else {
                playSettingsRecyclerView.requestFocus()
                Log.d(logTag, "$prefix - 未找到画质选项，请求焦点到playSettingsRecyclerView")
            }
        }
    }

    private fun getAnimatedContentView(): View {
        return (settingsPanel as? ViewGroup)?.getChildAt(0) ?: settingsPanel
    }
}
