package com.blive.tv.ui.main

data class LiveRoom(
    val roomId: Long,
    val coverUrl: String,
    val anchorName: String,
    val anchorAvatar: String,
    val roomTitle: String,
    val areaName: String
)

data class UserProfile(
    val nickname: String,
    val avatarUrl: String
)

enum class LiveListState {
    Loading,
    Content,
    Empty,
    Error
}

enum class MainTabType {
    Following,
    Recommend
}

data class MainScreenState(
    val isLoggedIn: Boolean = false,
    val userProfile: UserProfile? = null,
    val selectedTab: MainTabType = MainTabType.Following,
    val followingRooms: List<LiveRoom> = emptyList(),
    val recommendRooms: List<LiveRoom> = emptyList(),
    val followingListState: LiveListState = LiveListState.Loading,
    val recommendListState: LiveListState = LiveListState.Loading,
    val isLoadingFollowing: Boolean = false,
    val isLoadingRecommend: Boolean = false
)
