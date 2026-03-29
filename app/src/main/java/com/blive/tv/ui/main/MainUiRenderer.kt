package com.blive.tv.ui.main

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.leanback.widget.VerticalGridView
import com.blive.tv.R

data class MainViewRefs(
    val tabLogin: View,
    val tabMine: View,
    val tabRecommend: View,
    val tabFollowing: View,
    val tabPartition: View,
    val tabSearch: View,
    val tabFocusSlider: View,

    val loginContainer: View,
    val mineContainer: View,
    val gridContainer: View,
    val areaSelectorsContainer: View,
    val searchInputContainer: View,

    val gridTitle: TextView,
    val gridView: VerticalGridView,
    val loadingContainer: View,
    val emptyContainer: View,
    val errorContainer: View,
    val emptyRefreshButton: ImageButton,
    val errorRefreshButton: ImageButton
)

class MainUiRenderer(private val views: MainViewRefs) {
    fun renderLoginState(isLoggedIn: Boolean) {
        if (isLoggedIn) {
            views.tabLogin.visibility = View.GONE
            views.tabMine.visibility = View.VISIBLE
            views.tabRecommend.visibility = View.VISIBLE
            views.tabFollowing.visibility = View.VISIBLE
            views.tabPartition.visibility = View.VISIBLE
            views.tabSearch.visibility = View.VISIBLE
        } else {
            views.tabLogin.visibility = View.VISIBLE
            views.tabMine.visibility = View.GONE
            views.tabRecommend.visibility = View.GONE
            views.tabFollowing.visibility = View.GONE
            views.tabPartition.visibility = View.GONE
            views.tabSearch.visibility = View.GONE
        }
    }

    fun renderLiveListState(state: LiveListState) {
        when (state) {
            LiveListState.Loading -> {
                views.gridView.visibility = View.GONE
                views.loadingContainer.visibility = View.VISIBLE
                views.emptyContainer.visibility = View.GONE
                views.errorContainer.visibility = View.GONE
            }
            LiveListState.Content -> {
                views.gridView.visibility = View.VISIBLE
                views.loadingContainer.visibility = View.GONE
                views.emptyContainer.visibility = View.GONE
                views.errorContainer.visibility = View.GONE
            }
            LiveListState.Empty -> {
                views.gridView.visibility = View.GONE
                views.loadingContainer.visibility = View.GONE
                views.emptyContainer.visibility = View.VISIBLE
                views.errorContainer.visibility = View.GONE
            }
            LiveListState.Error -> {
                views.gridView.visibility = View.GONE
                views.loadingContainer.visibility = View.GONE
                views.emptyContainer.visibility = View.GONE
                views.errorContainer.visibility = View.VISIBLE
            }
        }
    }

    fun renderTabState(selectedTab: MainTabType, isShowingSearchResult: Boolean = false, searchKeyword: String = "") {
        // Reset all
        views.tabLogin.isSelected = false
        views.tabMine.isSelected = false
        views.tabRecommend.isSelected = false
        views.tabFollowing.isSelected = false
        views.tabPartition.isSelected = false
        views.tabSearch.isSelected = false

        val showLogin = selectedTab == MainTabType.Login
        val showMine = selectedTab == MainTabType.Mine
        val showGrid = selectedTab == MainTabType.Recommend || 
            selectedTab == MainTabType.Following || 
            selectedTab == MainTabType.Partition || 
            (selectedTab == MainTabType.Search && isShowingSearchResult)
        val showAreaSelectors = selectedTab == MainTabType.Partition
        val showSearchInput = selectedTab == MainTabType.Search && !isShowingSearchResult

        if (views.loginContainer.visibility != (if (showLogin) View.VISIBLE else View.GONE)) {
            views.loginContainer.visibility = if (showLogin) View.VISIBLE else View.GONE
        }
        if (views.mineContainer.visibility != (if (showMine) View.VISIBLE else View.GONE)) {
            views.mineContainer.visibility = if (showMine) View.VISIBLE else View.GONE
        }
        if (views.gridContainer.visibility != (if (showGrid) View.VISIBLE else View.GONE)) {
            views.gridContainer.visibility = if (showGrid) View.VISIBLE else View.GONE
        }
        if (views.areaSelectorsContainer.visibility != (if (showAreaSelectors) View.VISIBLE else View.GONE)) {
            views.areaSelectorsContainer.visibility = if (showAreaSelectors) View.VISIBLE else View.GONE
        }
        if (views.searchInputContainer.visibility != (if (showSearchInput) View.VISIBLE else View.GONE)) {
            views.searchInputContainer.visibility = if (showSearchInput) View.VISIBLE else View.GONE
        }

        val showGridTitle = selectedTab != MainTabType.Partition
        if (views.gridTitle.visibility != (if (showGridTitle) View.VISIBLE else View.GONE)) {
            views.gridTitle.visibility = if (showGridTitle) View.VISIBLE else View.GONE
        }

        when (selectedTab) {
            MainTabType.Login -> {
                views.tabLogin.isSelected = true
            }
            MainTabType.Mine -> {
                views.tabMine.isSelected = true
            }
            MainTabType.Recommend -> {
                views.tabRecommend.isSelected = true
                views.gridTitle.text = "为您推荐"
            }
            MainTabType.Following -> {
                views.tabFollowing.isSelected = true
                views.gridTitle.text = "我的关注"
            }
            MainTabType.Partition -> {
                views.tabPartition.isSelected = true
            }
            MainTabType.Search -> {
                views.tabSearch.isSelected = true
                if (isShowingSearchResult) {
                    views.gridTitle.text = "“${searchKeyword}”的搜索结果"
                }
            }
        }
    }
}
