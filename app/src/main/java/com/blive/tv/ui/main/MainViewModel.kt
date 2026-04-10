package com.blive.tv.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.blive.tv.data.model.AreaLevel1
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.content.Context
import com.blive.tv.utils.AppRuntime

class MainViewModel(
    private val repository: MainRepository
) : ViewModel() {
    private val _screenState = MutableStateFlow(MainScreenState())
    val screenState: StateFlow<MainScreenState> = _screenState.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    private var recommendCache: List<LiveRoom> = emptyList()
    private var recommendCacheTime: Long = 0L
    private val cacheValidDurationMs = 10 * 60 * 1000L

    private data class PartitionCacheKey(val level1Id: Int, val level2Id: Int)
    private data class PartitionCacheEntry(val rooms: List<LiveRoom>, val timestamp: Long)
    private val partitionCaches = mutableMapOf<PartitionCacheKey, PartitionCacheEntry>()

    private var nextFocusToken = 1L

    private val PREF_SEARCH_HISTORY = "search_history_pref"
    private val KEY_SEARCH_HISTORY = "history_list"

    init {
        loadSearchHistory()
    }

    private fun isRecommendCacheValid(): Boolean {
        if (recommendCache.isEmpty()) return false
        return System.currentTimeMillis() - recommendCacheTime < cacheValidDurationMs
    }

    private fun isPartitionCacheValid(level1Id: Int, level2Id: Int): Boolean {
        val entry = partitionCaches[PartitionCacheKey(level1Id, level2Id)] ?: return false
        return System.currentTimeMillis() - entry.timestamp < cacheValidDurationMs
    }

    fun syncLoginState(isLoggedIn: Boolean) {
        if (!isLoggedIn) {
            _screenState.value = MainScreenState(
                isLoggedIn = false,
                selectedTab = MainTabType.Login,
                tabUiState = TabUiState(MainTabType.Login),
                focusRegion = FocusRegion.Tab(MainTabType.Login),
                focusIntent = createFocusIntent(FocusTarget.Tab(MainTabType.Login))
            )
            return
        }
        _screenState.update {
            it.copy(
                isLoggedIn = true,
                selectedTab = if (it.selectedTab == MainTabType.Login) MainTabType.Following else it.selectedTab,
                tabUiState = it.tabUiState.copy(
                    highlightedTab = if (it.tabUiState.highlightedTab == MainTabType.Login) {
                        MainTabType.Following
                    } else {
                        it.tabUiState.highlightedTab
                    }
                )
            )
        }
    }

    fun refreshAfterLogin() {
        _screenState.update {
            it.copy(
                isLoggedIn = true,
                selectedTab = MainTabType.Following,
                tabUiState = TabUiState(MainTabType.Following),
                focusRegion = FocusRegion.Tab(MainTabType.Following),
                focusIntent = createFocusIntent(FocusTarget.Tab(MainTabType.Following)),
                followingListState = LiveListState.Loading
            )
        }
        loadUserProfile()
        refreshFollowingRooms(force = true)
    }

    fun refreshAfterResume() {
        if (!_screenState.value.isLoggedIn) {
            return
        }
        loadUserProfile()
        refreshCurrentTab()
    }

    fun onTabFocused(tab: MainTabType) {
        val state = _screenState.value
        if (!state.isLoggedIn && tab != MainTabType.Login) {
            return
        }
        val tabChanged = state.selectedTab != tab
        commitSelectedTab(tab)
        if (tabChanged) {
            refreshTabDataIfNeeded(tab)
        }
    }

    fun requestTabFocus(tab: MainTabType = _screenState.value.selectedTab) {
        _screenState.update {
            it.copy(
                tabUiState = it.tabUiState.copy(highlightedTab = tab),
                focusIntent = createFocusIntent(FocusTarget.Tab(tab)),
                pendingContentFocusRequest = PendingContentFocusRequest()
            )
        }
    }

    fun requestContentFocus(tab: MainTabType) {
        val state = _screenState.value
        if (!state.isLoggedIn && tab != MainTabType.Login) {
            return
        }
        val tabChanged = state.selectedTab != tab
        if (tabChanged) {
            commitSelectedTab(tab)
            refreshTabDataIfNeeded(tab)
        }
        if (tab == MainTabType.Login) {
            requestTabFocus(MainTabType.Login)
            return
        }
        enqueuePendingContentFocus(tab, resolveDefaultContentTarget(_screenState.value, tab))
    }

    fun onNavigateBackToTab() {
        requestTabFocus(_screenState.value.selectedTab)
    }

    fun refreshCurrentTab(force: Boolean = false) {
        when (_screenState.value.selectedTab) {
            MainTabType.Following -> refreshFollowingRooms(force)
            MainTabType.Recommend -> refreshRecommendRooms(force)
            MainTabType.Partition -> refreshPartitionRooms(force)
            MainTabType.Search -> {
                if (_screenState.value.isShowingSearchResult && _screenState.value.searchKeyword.isNotEmpty()) {
                    // 仅在强制刷新或结果为空时重新搜索，防止 onResume 触发刷新导致焦点丢失
                    if (force || _screenState.value.searchRooms.isEmpty()) {
                        performSearch(_screenState.value.searchKeyword)
                    }
                }
            }
            else -> Unit
        }
    }

    fun refreshLiveRooms(force: Boolean = false) {
        refreshCurrentTab(force)
    }

    fun refreshFollowingRooms(force: Boolean = false) {
        val state = _screenState.value
        if (!state.isLoggedIn) {
            return
        }
        if (state.isLoadingFollowing && !force) {
            emitToast("正在刷新关注直播间...")
            return
        }
        _screenState.update {
            it.copy(
                isLoadingFollowing = true,
                followingListState = LiveListState.Loading
            )
        }
        viewModelScope.launch {
            val result = repository.fetchLiveRooms()
            result.onSuccess { rooms ->
                _screenState.update {
                    it.copy(
                        followingRooms = rooms,
                        followingListState = if (rooms.isEmpty()) LiveListState.Empty else LiveListState.Content,
                        isLoadingFollowing = false
                    )
                }
            }.onFailure { error ->
                _screenState.update {
                    it.copy(
                        followingRooms = emptyList(),
                        followingListState = LiveListState.Error,
                        isLoadingFollowing = false
                    )
                }
                emitToast("网络连接错误：${error.message}")
            }
        }
    }

    fun refreshRecommendRooms(force: Boolean = false) {
        val state = _screenState.value
        if (!state.isLoggedIn) {
            return
        }
        if (state.isLoadingRecommend && !force) {
            emitToast("正在刷新推荐直播间...")
            return
        }
        if (!force && isRecommendCacheValid()) {
            _screenState.update {
                it.copy(
                    recommendRooms = recommendCache,
                    recommendListState = if (recommendCache.isEmpty()) LiveListState.Empty else LiveListState.Content,
                    isLoadingRecommend = false
                )
            }
            return
        }
        _screenState.update {
            it.copy(
                recommendListState = LiveListState.Loading,
                isLoadingRecommend = true
            )
        }
        viewModelScope.launch {
            val result = repository.fetchRecommendRooms()
            result.onSuccess { rooms ->
                val normalizedRooms = rooms
                    .filter { it.roomId > 0L }
                    .distinctBy { it.roomId }
                recommendCache = normalizedRooms
                recommendCacheTime = System.currentTimeMillis()
                _screenState.update {
                    it.copy(
                        recommendRooms = normalizedRooms,
                        recommendListState = if (normalizedRooms.isEmpty()) LiveListState.Empty else LiveListState.Content,
                        isLoadingRecommend = false
                    )
                }
            }.onFailure { error ->
                _screenState.update {
                    it.copy(
                        recommendRooms = emptyList(),
                        recommendListState = LiveListState.Error,
                        isLoadingRecommend = false
                    )
                }
                emitToast("加载推荐直播间失败：${error.message}")
            }
        }
    }

    private fun refreshPartitionAreas() {
        val state = _screenState.value
        if (!state.isLoggedIn || state.isLoadingPartitionAreas) return
        
        _screenState.update { it.copy(isLoadingPartitionAreas = true, partitionListState = LiveListState.Loading) }
        viewModelScope.launch {
            val result = repository.fetchWebAreaList()
            result.onSuccess { areas ->
                val initialLevel1Id = if (areas.isNotEmpty()) areas[0].id else 0
                _screenState.update {
                    it.copy(
                        partitionAreas = areas,
                        selectedLevel1AreaId = initialLevel1Id,
                        selectedLevel2AreaId = 0,
                        partitionFocusState = it.partitionFocusState.copy(
                            level1Index = resolveLevel1Index(areas, initialLevel1Id),
                            level2Index = 0,
                            layer = PartitionFocusLayer.Level1
                        ),
                        isLoadingPartitionAreas = false
                    )
                }
                refreshPartitionRooms(force = false)
            }.onFailure { error ->
                _screenState.update {
                    it.copy(
                        partitionListState = LiveListState.Error,
                        isLoadingPartitionAreas = false
                    )
                }
                emitToast("分区列表加载失败：${error.message}")
            }
        }
    }

    fun refreshPartitionRooms(force: Boolean = false) {
        val state = _screenState.value
        if (!state.isLoggedIn) return
        if (state.isLoadingPartitionRooms && !force) {
            emitToast("正在刷新分区直播间...")
            return
        }
        
        if (!force) {
            val cacheKey = PartitionCacheKey(state.selectedLevel1AreaId, state.selectedLevel2AreaId)
            val entry = partitionCaches[cacheKey]
            if (entry != null && System.currentTimeMillis() - entry.timestamp < cacheValidDurationMs) {
                _screenState.update {
                    it.copy(
                        partitionRooms = entry.rooms,
                        partitionListState = if (entry.rooms.isEmpty()) LiveListState.Empty else LiveListState.Content,
                        isLoadingPartitionRooms = false
                    )
                }
                return
            }
        }
        
        _screenState.update {
            it.copy(
                isLoadingPartitionRooms = true,
                partitionListState = LiveListState.Loading
            )
        }
        viewModelScope.launch {
            val result = repository.fetchAreaRooms(state.selectedLevel1AreaId, state.selectedLevel2AreaId, 1)
            result.onSuccess { rooms ->
                val cacheKey = PartitionCacheKey(state.selectedLevel1AreaId, state.selectedLevel2AreaId)
                partitionCaches[cacheKey] = PartitionCacheEntry(rooms, System.currentTimeMillis())
                
                _screenState.update {
                    it.copy(
                        partitionRooms = rooms,
                        partitionListState = if (rooms.isEmpty()) LiveListState.Empty else LiveListState.Content,
                        isLoadingPartitionRooms = false
                    )
                }
            }.onFailure { error ->
                _screenState.update {
                    it.copy(
                        partitionRooms = emptyList(),
                        partitionListState = LiveListState.Error,
                        isLoadingPartitionRooms = false
                    )
                }
                emitToast("分区直播间加载失败：${error.message}")
            }
        }
    }

    fun selectLevel1Area(areaId: Int) {
        val state = _screenState.value
        if (state.selectedLevel1AreaId == areaId) return
        val level1Index = resolveLevel1Index(state.partitionAreas, areaId)
        _screenState.update {
            it.copy(
                selectedLevel1AreaId = areaId,
                selectedLevel2AreaId = 0,
                partitionFocusState = it.partitionFocusState.copy(
                    level1Index = level1Index,
                    level2Index = 0,
                    layer = PartitionFocusLayer.Level1
                ),
                focusRegion = FocusRegion.PartitionLevel1(level1Index),
                focusIntent = createFocusIntent(FocusTarget.PartitionLevel1(level1Index))
            )
        }
        refreshPartitionRooms(force = false)
    }

    fun selectLevel2Area(areaId: Int) {
        val state = _screenState.value
        if (state.selectedLevel2AreaId == areaId) return
        val level2Index = resolveLevel2Index(state.partitionAreas, state.selectedLevel1AreaId, areaId)
        _screenState.update {
            it.copy(
                selectedLevel2AreaId = areaId,
                partitionFocusState = it.partitionFocusState.copy(
                    level2Index = level2Index,
                    layer = PartitionFocusLayer.Level2
                ),
                focusRegion = FocusRegion.PartitionLevel2(level2Index),
                focusIntent = createFocusIntent(FocusTarget.PartitionLevel2(level2Index))
            )
        }
        refreshPartitionRooms(force = false)
    }

    fun onPartitionLevel1Focused(index: Int) {
        _screenState.update {
            it.copy(
                partitionFocusState = it.partitionFocusState.copy(level1Index = index, layer = PartitionFocusLayer.Level1),
                focusRegion = FocusRegion.PartitionLevel1(index)
            )
        }
    }

    fun onPartitionLevel2Focused(index: Int) {
        _screenState.update {
            it.copy(
                partitionFocusState = it.partitionFocusState.copy(level2Index = index, layer = PartitionFocusLayer.Level2),
                focusRegion = FocusRegion.PartitionLevel2(index)
            )
        }
    }

    fun onGridFocused(tab: MainTabType, position: Int, roomId: Long) {
        _screenState.update {
            val updatedRestoreStates = it.gridRestoreStates.toMutableMap().apply {
                this[tab] = GridRestoreState(position, roomId)
            }
            val updatedPartitionFocusState = if (tab == MainTabType.Partition) {
                it.partitionFocusState.copy(
                    layer = PartitionFocusLayer.Grid,
                    gridRestoreState = GridRestoreState(position, roomId)
                )
            } else {
                it.partitionFocusState
            }
            it.copy(
                gridRestoreStates = updatedRestoreStates,
                partitionFocusState = updatedPartitionFocusState,
                focusRegion = FocusRegion.Grid(tab, position, roomId)
            )
        }
    }

    fun onPartitionGridBackRequested() {
        val state = _screenState.value
        val target = if (hasVisibleLevel2(state)) {
            FocusTarget.PartitionLevel2(state.partitionFocusState.level2Index)
        } else {
            FocusTarget.PartitionLevel1(state.partitionFocusState.level1Index)
        }
        _screenState.update {
            it.copy(
                focusIntent = createFocusIntent(target),
                partitionFocusState = it.partitionFocusState.copy(
                    layer = if (hasVisibleLevel2(it)) PartitionFocusLayer.Level2 else PartitionFocusLayer.Level1
                )
            )
        }
    }

    fun onSearchInputFocused() {
        _screenState.update {
            it.copy(focusRegion = FocusRegion.SearchInput)
        }
    }

    fun onMineActionFocused(action: MineActionTarget) {
        _screenState.update {
            it.copy(focusRegion = FocusRegion.MineAction(action))
        }
    }

    fun consumeFocusIntent(token: Long) {
        val current = _screenState.value.focusIntent
        if (current.token != token) {
            return
        }
        _screenState.update {
            it.copy(focusIntent = FocusIntent())
        }
    }

    fun consumePendingContentFocus(token: Long) {
        val current = _screenState.value.pendingContentFocusRequest
        if (current.token != token) {
            return
        }
        _screenState.update {
            it.copy(pendingContentFocusRequest = PendingContentFocusRequest())
        }
    }

    private fun loadSearchHistory() {
        val prefs = AppRuntime.appContext.getSharedPreferences(PREF_SEARCH_HISTORY, Context.MODE_PRIVATE)
        val historyString = prefs.getString(KEY_SEARCH_HISTORY, "") ?: ""
        val historyList = if (historyString.isEmpty()) emptyList() else historyString.split("|||")
        _screenState.update { it.copy(searchHistory = historyList) }
    }

    private fun saveSearchKeyword(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        val currentHistory = _screenState.value.searchHistory.toMutableList()
        currentHistory.remove(trimmed)
        currentHistory.add(0, trimmed)
        val newHistory = currentHistory.take(10)
        
        val prefs = AppRuntime.appContext.getSharedPreferences(PREF_SEARCH_HISTORY, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SEARCH_HISTORY, newHistory.joinToString("|||")).apply()
        _screenState.update { it.copy(searchHistory = newHistory) }
    }

    fun clearSearchHistory() {
        val prefs = AppRuntime.appContext.getSharedPreferences(PREF_SEARCH_HISTORY, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_SEARCH_HISTORY).apply()
        _screenState.update { it.copy(searchHistory = emptyList()) }
    }

    fun performSearch(keyword: String) {
        val state = _screenState.value
        if (!state.isLoggedIn) return
        
        val trimmedKeyword = keyword.trim()
        if (trimmedKeyword.isEmpty()) {
            emitToast("搜索词不能为空")
            return
        }

        saveSearchKeyword(trimmedKeyword)

        _screenState.update {
            it.copy(
                selectedTab = MainTabType.Search,
                tabUiState = it.tabUiState.copy(highlightedTab = MainTabType.Search),
                isShowingSearchResult = true,
                searchKeyword = trimmedKeyword,
                isLoadingSearch = true,
                searchListState = LiveListState.Loading,
                pendingContentFocusRequest = createPendingContentFocusRequest(
                    MainTabType.Search,
                    FocusTarget.Content(MainTabType.Search)
                )
            )
        }

        viewModelScope.launch {
            val result = repository.fetchSearchLiveRooms(trimmedKeyword, 1)
            result.onSuccess { rooms ->
                _screenState.update {
                    it.copy(
                        searchRooms = rooms,
                        searchListState = if (rooms.isEmpty()) LiveListState.Empty else LiveListState.Content,
                        isLoadingSearch = false,
                        pendingContentFocusRequest = createPendingContentFocusRequest(
                            MainTabType.Search,
                            if (rooms.isEmpty()) {
                                FocusTarget.Content(MainTabType.Search)
                            } else {
                                FocusTarget.Grid(
                                    tab = MainTabType.Search,
                                    roomId = it.gridRestoreStates[MainTabType.Search]?.roomId
                                )
                            }
                        )
                    )
                }
            }.onFailure { error ->
                _screenState.update {
                    it.copy(
                        searchRooms = emptyList(),
                        searchListState = LiveListState.Error,
                        isLoadingSearch = false,
                        pendingContentFocusRequest = createPendingContentFocusRequest(
                            MainTabType.Search,
                            FocusTarget.Content(MainTabType.Search)
                        )
                    )
                }
                emitToast("搜索失败：${error.message}")
            }
        }
    }

    fun exitSearchResult() {
        _screenState.update {
            it.copy(
                isShowingSearchResult = false,
                searchRooms = emptyList(),
                searchKeyword = "",
                focusIntent = createFocusIntent(FocusTarget.SearchInput),
                pendingContentFocusRequest = PendingContentFocusRequest()
            )
        }
    }

    fun restoreAfterPlayReturn(originTab: MainTabType?, roomId: Long) {
        when (originTab) {
            MainTabType.Following -> {
                commitSelectedTab(MainTabType.Following)
                enqueuePendingContentFocus(
                    MainTabType.Following,
                    FocusTarget.Grid(MainTabType.Following, roomId = roomId)
                )
                refreshFollowingRooms(force = true)
            }
            MainTabType.Recommend -> {
                commitSelectedTab(MainTabType.Recommend)
                enqueuePendingContentFocus(
                    MainTabType.Recommend,
                    FocusTarget.Grid(MainTabType.Recommend, roomId = roomId)
                )
            }
            MainTabType.Search -> {
                commitSelectedTab(MainTabType.Search)
                enqueuePendingContentFocus(
                    MainTabType.Search,
                    FocusTarget.Grid(MainTabType.Search, roomId = roomId)
                )
            }
            MainTabType.Partition -> {
                commitSelectedTab(MainTabType.Partition)
                enqueuePendingContentFocus(
                    MainTabType.Partition,
                    FocusTarget.Grid(MainTabType.Partition, roomId = roomId)
                )
                if (_screenState.value.partitionAreas.isEmpty()) {
                    refreshPartitionAreas()
                }
            }
            else -> Unit
        }
    }

    fun loadUserProfile() {
        if (!_screenState.value.isLoggedIn) {
            return
        }
        viewModelScope.launch {
            val result = repository.fetchUserProfile()
            result.onSuccess { profile ->
                _screenState.update {
                    it.copy(userProfile = profile)
                }
            }
        }
    }

    private fun emitToast(message: String) {
        viewModelScope.launch {
            _toastEvent.emit(message)
        }
    }

    private fun commitSelectedTab(tab: MainTabType) {
        val state = _screenState.value
        val shouldClearPendingContentFocus = state.selectedTab != tab
        val shouldResetSearchState = state.selectedTab == MainTabType.Search &&
            state.isShowingSearchResult &&
            tab != MainTabType.Search
        _screenState.update {
            var nextState = if (shouldResetSearchState) {
                it.copy(
                    selectedTab = tab,
                    isShowingSearchResult = false,
                    searchRooms = emptyList(),
                    searchKeyword = "",
                    pendingContentFocusRequest = PendingContentFocusRequest()
                )
            } else {
                it.copy(
                    selectedTab = tab,
                    pendingContentFocusRequest = if (shouldClearPendingContentFocus) {
                        PendingContentFocusRequest()
                    } else {
                        it.pendingContentFocusRequest
                    }
                )
            }
            nextState = nextState.copy(
                tabUiState = nextState.tabUiState.copy(highlightedTab = tab),
                focusRegion = FocusRegion.Tab(tab)
            )
            nextState
        }
    }

    private fun refreshTabDataIfNeeded(tab: MainTabType) {
        when (tab) {
            MainTabType.Recommend -> refreshRecommendRooms(force = false)
            MainTabType.Following -> refreshFollowingRooms(force = false)
            MainTabType.Partition -> {
                if (_screenState.value.partitionAreas.isEmpty()) {
                    refreshPartitionAreas()
                } else {
                    refreshPartitionRooms(force = false)
                }
            }
            MainTabType.Search, MainTabType.Mine, MainTabType.Login -> Unit
        }
    }

    private fun createFocusIntent(target: FocusTarget): FocusIntent {
        return FocusIntent(
            target = target,
            token = nextFocusToken++
        )
    }

    private fun createPendingContentFocusRequest(
        tab: MainTabType,
        target: FocusTarget
    ): PendingContentFocusRequest {
        return PendingContentFocusRequest(
            tab = tab,
            target = target,
            token = nextFocusToken++,
            autoFocusWhenReady = true
        )
    }

    private fun enqueuePendingContentFocus(tab: MainTabType, target: FocusTarget) {
        _screenState.update {
            it.copy(
                pendingContentFocusRequest = createPendingContentFocusRequest(tab, target)
            )
        }
    }

    private fun resolveDefaultContentTarget(
        state: MainScreenState,
        tab: MainTabType
    ): FocusTarget {
        return when (tab) {
            MainTabType.Login -> FocusTarget.Tab(MainTabType.Login)
            MainTabType.Mine -> FocusTarget.MineAction(MineActionTarget.Settings)
            MainTabType.Partition -> FocusTarget.Content(MainTabType.Partition)
            MainTabType.Search -> if (state.isShowingSearchResult) {
                FocusTarget.Content(MainTabType.Search)
            } else {
                FocusTarget.SearchInput
            }
            MainTabType.Recommend, MainTabType.Following -> FocusTarget.Content(tab)
        }
    }

    private fun hasVisibleLevel2(state: MainScreenState): Boolean {
        val level1 = state.partitionAreas.find { it.id == state.selectedLevel1AreaId } ?: return false
        return level1.id != 0 && level1.list.isNotEmpty()
    }

    private fun resolveLevel1Index(areas: List<AreaLevel1>, areaId: Int): Int {
        val index = areas.indexOfFirst { it.id == areaId }
        return if (index >= 0) index else 0
    }

    private fun resolveLevel2Index(areas: List<AreaLevel1>, level1AreaId: Int, areaId: Int): Int {
        val level1 = areas.find { it.id == level1AreaId } ?: return 0
        val index = level1.list.indexOfFirst { it.id.toIntOrNull() == areaId }
        return if (index >= 0) index else 0
    }
}

class MainViewModelFactory(
    private val repository: MainRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
