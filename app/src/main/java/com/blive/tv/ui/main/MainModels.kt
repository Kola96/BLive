package com.blive.tv.ui.main

import com.blive.tv.data.model.AreaLevel1

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

data class GridRestoreState(
    val position: Int = 0,
    val roomId: Long? = null
)

enum class PartitionFocusLayer {
    Level1,
    Level2,
    Grid
}

enum class MineActionTarget {
    Settings,
    Logout
}

sealed interface FocusRegion {
    data class Tab(val tab: MainTabType) : FocusRegion
    data class Grid(val tab: MainTabType, val position: Int = 0, val roomId: Long? = null) : FocusRegion
    data class PartitionLevel1(val index: Int = 0) : FocusRegion
    data class PartitionLevel2(val index: Int = 0) : FocusRegion
    object SearchInput : FocusRegion
    data class MineAction(val action: MineActionTarget) : FocusRegion
}

sealed interface FocusTarget {
    object None : FocusTarget
    data class Tab(val tab: MainTabType) : FocusTarget
    data class Content(val tab: MainTabType) : FocusTarget
    data class Grid(val tab: MainTabType, val position: Int = 0, val roomId: Long? = null) : FocusTarget
    data class PartitionLevel1(val index: Int = 0) : FocusTarget
    data class PartitionLevel2(val index: Int = 0) : FocusTarget
    object SearchInput : FocusTarget
    data class MineAction(val action: MineActionTarget) : FocusTarget
}

data class FocusIntent(
    val target: FocusTarget = FocusTarget.None,
    val token: Long = 0L
)

data class PendingContentFocusRequest(
    val tab: MainTabType? = null,
    val target: FocusTarget = FocusTarget.None,
    val token: Long = 0L,
    val autoFocusWhenReady: Boolean = false
)

data class TabUiState(
    val highlightedTab: MainTabType = MainTabType.Login
)

data class PartitionFocusState(
    val level1Index: Int = 0,
    val level2Index: Int = 0,
    val layer: PartitionFocusLayer = PartitionFocusLayer.Level1,
    val gridRestoreState: GridRestoreState = GridRestoreState()
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
    Partition,
    Search
}

data class MainScreenState(
    val isLoggedIn: Boolean = false,
    val userProfile: UserProfile? = null,
    val selectedTab: MainTabType = MainTabType.Login,
    val tabUiState: TabUiState = TabUiState(),
    val focusRegion: FocusRegion = FocusRegion.Tab(MainTabType.Login),
    val focusIntent: FocusIntent = FocusIntent(),
    val pendingContentFocusRequest: PendingContentFocusRequest = PendingContentFocusRequest(),
    val followingRooms: List<LiveRoom> = emptyList(),
    val recommendRooms: List<LiveRoom> = emptyList(),
    val partitionAreas: List<AreaLevel1> = emptyList(),
    val selectedLevel1AreaId: Int = 0,
    val selectedLevel2AreaId: Int = 0,
    val partitionRooms: List<LiveRoom> = emptyList(),
    val searchRooms: List<LiveRoom> = emptyList(),
    val gridRestoreStates: Map<MainTabType, GridRestoreState> = mapOf(
        MainTabType.Recommend to GridRestoreState(),
        MainTabType.Following to GridRestoreState(),
        MainTabType.Partition to GridRestoreState(),
        MainTabType.Search to GridRestoreState()
    ),
    val partitionFocusState: PartitionFocusState = PartitionFocusState(),
    val followingListState: LiveListState = LiveListState.Loading,
    val recommendListState: LiveListState = LiveListState.Loading,
    val partitionListState: LiveListState = LiveListState.Loading,
    val searchListState: LiveListState = LiveListState.Loading,
    val isLoadingFollowing: Boolean = false,
    val isLoadingRecommend: Boolean = false,
    val isLoadingPartitionAreas: Boolean = false,
    val isLoadingPartitionRooms: Boolean = false,
    val isLoadingSearch: Boolean = false,
    val isShowingSearchResult: Boolean = false,
    val searchKeyword: String = "",
    val searchHistory: List<String> = emptyList()
)
