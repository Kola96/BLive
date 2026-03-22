package com.blive.tv.ui.main

import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.leanback.widget.VerticalGridView

class MainFocusNavigator(
    private val userAvatarContainer: FrameLayout,
    private val nicknameView: TextView,
    private val gridView: VerticalGridView,
    private val emptyRefreshButton: ImageButton,
    private val errorRefreshButton: ImageButton
) {
    fun handleAvatarKey(
        keyCode: Int,
        state: LiveListState,
        gridItemCount: Int
    ): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                when (state) {
                    LiveListState.Content -> focusGridFirstItem(gridItemCount)
                    LiveListState.Empty -> emptyRefreshButton.requestFocus()
                    LiveListState.Error -> errorRefreshButton.requestFocus()
                    LiveListState.Loading -> Unit
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (nicknameView.isFocusable) {
                    nicknameView.requestFocus()
                }
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> true
            else -> false
        }
    }

    fun focusAvatar() {
        userAvatarContainer.requestFocus()
    }

    private fun focusGridFirstItem(gridItemCount: Int) {
        if (gridView.visibility != View.VISIBLE || gridItemCount <= 0) {
            return
        }
        gridView.scrollToPosition(0)
        gridView.post {
            val holder = gridView.findViewHolderForAdapterPosition(0)
            if (holder != null) {
                holder.itemView.requestFocus()
            } else {
                gridView.getChildAt(0)?.requestFocus()
            }
        }
    }
}
