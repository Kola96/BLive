package com.blive.tv.ui.main

data class LiveRoom(
    val roomId: Long,
    val coverUrl: String,
    val anchorName: String,
    val anchorAvatar: String,
    val roomTitle: String,
    val areaName: String,
    val viewerCount: Long = 0L,
    val viewerCountText: String? = null
)

data class UserProfile(
    val nickname: String,
    val avatarUrl: String,
    val vipLabel: String? = null,
    val level: Int = 0
)

enum class LiveListState {
    Loading,
    Content,
    Empty,
    Error
}

enum class MainTabType {
    Login,
    Mine,
    Recommend,
    Following,
    Partition
}

data class MainScreenState(
    val isLoggedIn: Boolean = false,
    val userProfile: UserProfile? = null,
    val selectedTab: MainTabType = MainTabType.Login,
    val followingRooms: List<LiveRoom> = emptyList(),
    val recommendRooms: List<LiveRoom> = emptyList(),
    val followingListState: LiveListState = LiveListState.Loading,
    val recommendListState: LiveListState = LiveListState.Loading,
    val isLoadingFollowing: Boolean = false,
    val isLoadingRecommend: Boolean = false
)
