package com.blive.tv.ui.main

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.leanback.widget.VerticalGridView
import com.blive.tv.R

data class MainViewRefs(
    val userInfoContainer: LinearLayout,
    val loginButtonContainer: LinearLayout,
    val gridView: VerticalGridView,
    val loadingContainer: LinearLayout,
    val emptyContainer: LinearLayout,
    val errorContainer: LinearLayout,
    val userAvatarContainer: FrameLayout,
    val emptyRefreshButton: ImageButton,
    val errorRefreshButton: ImageButton
)

class MainUiRenderer(private val views: MainViewRefs) {
    fun renderLoginState(isLoggedIn: Boolean) {
        if (isLoggedIn) {
            views.userInfoContainer.visibility = View.VISIBLE
            views.loginButtonContainer.visibility = View.GONE
        } else {
            views.userInfoContainer.visibility = View.GONE
            views.loginButtonContainer.visibility = View.VISIBLE
            views.gridView.visibility = View.GONE
            views.loadingContainer.visibility = View.GONE
            views.emptyContainer.visibility = View.GONE
            views.errorContainer.visibility = View.GONE
            views.userAvatarContainer.nextFocusDownId = R.id.main_grid
        }
    }

    fun renderLiveListState(state: LiveListState) {
        when (state) {
            LiveListState.Loading -> {
                views.gridView.visibility = View.GONE
                views.loadingContainer.visibility = View.VISIBLE
                views.emptyContainer.visibility = View.GONE
                views.errorContainer.visibility = View.GONE
                views.userAvatarContainer.nextFocusDownId = R.id.main_grid
            }

            LiveListState.Content -> {
                views.gridView.visibility = View.VISIBLE
                views.loadingContainer.visibility = View.GONE
                views.emptyContainer.visibility = View.GONE
                views.errorContainer.visibility = View.GONE
                views.userAvatarContainer.nextFocusDownId = R.id.main_grid
            }

            LiveListState.Empty -> {
                views.gridView.visibility = View.GONE
                views.loadingContainer.visibility = View.GONE
                views.emptyContainer.visibility = View.VISIBLE
                views.errorContainer.visibility = View.GONE
                views.userAvatarContainer.nextFocusDownId = R.id.live_list_empty_refresh_button
                views.emptyContainer.postDelayed({
                    views.emptyRefreshButton.requestFocus()
                }, 100)
            }

            LiveListState.Error -> {
                views.gridView.visibility = View.GONE
                views.loadingContainer.visibility = View.GONE
                views.emptyContainer.visibility = View.GONE
                views.errorContainer.visibility = View.VISIBLE
                views.userAvatarContainer.nextFocusDownId = R.id.live_list_error_refresh_button
                views.errorContainer.postDelayed({
                    views.errorRefreshButton.requestFocus()
                }, 100)
            }
        }
    }
}
