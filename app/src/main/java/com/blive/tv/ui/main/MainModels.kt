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

data class MainScreenState(
    val isLoggedIn: Boolean = false,
    val userProfile: UserProfile? = null,
    val liveRooms: List<LiveRoom> = emptyList(),
    val liveListState: LiveListState = LiveListState.Loading,
    val isLoadingLiveList: Boolean = false
)
