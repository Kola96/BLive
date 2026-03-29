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
    fun focusContent(
        state: LiveListState,
        tab: MainTabType,
        gridItemCount: Int,
        targetPosition: Int,
        isShowingSearchResult: Boolean = false
    ): Boolean {
        return when (tab) {
            MainTabType.Mine -> {
                btnSettings.requestFocus()
            }
            MainTabType.Recommend, MainTabType.Following -> {
                when (state) {
                    LiveListState.Content -> focusGridItem(gridItemCount, targetPosition)
                    LiveListState.Empty -> emptyRefreshButton.requestFocus()
                    LiveListState.Error -> errorRefreshButton.requestFocus()
                    LiveListState.Loading -> false
                }
            }
            MainTabType.Partition -> {
                if (level1AreaGrid.visibility == View.VISIBLE) {
                    level1AreaGrid.requestFocus()
                } else {
                    false
                }
            }
            MainTabType.Search -> {
                if (isShowingSearchResult) {
                    when (state) {
                        LiveListState.Empty -> emptyRefreshButton.requestFocus()
                        LiveListState.Error -> errorRefreshButton.requestFocus()
                        LiveListState.Loading -> false
                        else -> focusGridItem(gridItemCount, targetPosition)
                    }
                } else {
                    searchEditText.requestFocus()
                }
            }
            else -> false
        }
    }

    private fun focusGridItem(gridItemCount: Int, targetPosition: Int): Boolean {
        if (gridView.visibility != View.VISIBLE || gridItemCount <= 0) {
            return false
        }
        val safePosition = targetPosition.coerceIn(0, gridItemCount - 1)
        gridView.scrollToPosition(safePosition)
        val holder = gridView.findViewHolderForAdapterPosition(safePosition)
        return when {
            holder?.itemView?.requestFocus() == true -> true
            gridView.getChildAt(0)?.requestFocus() == true -> true
            else -> false
        }
    }
}
