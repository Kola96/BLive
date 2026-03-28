package com.blive.tv.ui.main

import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.leanback.widget.VerticalGridView
import androidx.leanback.widget.HorizontalGridView

class MainFocusNavigator(
    private val gridView: VerticalGridView,
    private val level1AreaGrid: HorizontalGridView,
    private val level2AreaGrid: HorizontalGridView,
    private val emptyRefreshButton: ImageButton,
    private val errorRefreshButton: ImageButton,
    private val loadingContainer: View,
    private val btnSettings: FrameLayout,
    private val btnLogout: FrameLayout,
    private val searchEditText: EditText
) {
    fun focusContent(state: LiveListState, tab: MainTabType, gridItemCount: Int, targetPosition: Int, isShowingSearchResult: Boolean = false) {
        when (tab) {
            MainTabType.Mine -> {
                btnSettings.requestFocus()
            }
            MainTabType.Recommend, MainTabType.Following -> {
                when (state) {
                    LiveListState.Content -> focusGridItem(gridItemCount, targetPosition)
                    LiveListState.Empty -> emptyRefreshButton.requestFocus()
                    LiveListState.Error -> errorRefreshButton.requestFocus()
                    LiveListState.Loading -> loadingContainer.requestFocus()
                }
            }
            MainTabType.Partition -> {
                if (level1AreaGrid.visibility == View.VISIBLE) {
                    level1AreaGrid.requestFocus()
                }
            }
            MainTabType.Search -> {
                if (isShowingSearchResult) {
                    when (state) {
                        LiveListState.Empty -> emptyRefreshButton.requestFocus()
                        LiveListState.Error -> errorRefreshButton.requestFocus()
                        LiveListState.Loading -> loadingContainer.requestFocus()
                        else -> focusGridItem(gridItemCount, targetPosition)
                    }
                } else {
                    searchEditText.requestFocus()
                }
            }
            else -> Unit
        }
    }

    private fun focusGridItem(gridItemCount: Int, targetPosition: Int) {
        if (gridView.visibility != View.VISIBLE || gridItemCount <= 0) {
            return
        }
        val safePosition = targetPosition.coerceIn(0, gridItemCount - 1)
        gridView.scrollToPosition(safePosition)
        gridView.post {
            val holder = gridView.findViewHolderForAdapterPosition(safePosition)
            if (holder != null) {
                holder.itemView.requestFocus()
            } else {
                gridView.getChildAt(0)?.requestFocus()
            }
        }
    }
}
