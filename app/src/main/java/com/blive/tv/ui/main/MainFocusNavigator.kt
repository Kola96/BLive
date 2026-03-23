package com.blive.tv.ui.main

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.leanback.widget.VerticalGridView

class MainFocusNavigator(
    private val gridView: VerticalGridView,
    private val emptyRefreshButton: ImageButton,
    private val errorRefreshButton: ImageButton,
    private val btnSettings: FrameLayout,
    private val btnLogout: FrameLayout
) {
    fun focusContent(state: LiveListState, tab: MainTabType, gridItemCount: Int) {
        when (tab) {
            MainTabType.Mine -> {
                btnSettings.requestFocus()
            }
            MainTabType.Recommend, MainTabType.Following -> {
                when (state) {
                    LiveListState.Content -> focusGridFirstItem(gridItemCount)
                    LiveListState.Empty -> emptyRefreshButton.requestFocus()
                    LiveListState.Error -> errorRefreshButton.requestFocus()
                    LiveListState.Loading -> Unit
                }
            }
            else -> Unit
        }
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
