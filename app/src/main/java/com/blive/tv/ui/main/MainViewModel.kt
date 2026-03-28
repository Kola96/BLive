package com.blive.tv.ui.main

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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

    private val PREF_SEARCH_HISTORY = "search_history_pref"
    private val KEY_SEARCH_HISTORY = "history_list"

    init {
        loadSearchHistory()
    }

    private fun isRecommendCacheValid(): Boolean {
        if (recommendCache.isEmpty()) return false
        return System.currentTimeMillis() - recommendCacheTime < cacheValidDurationMs
    }

    fun syncLoginState(isLoggedIn: Boolean) {
        if (!isLoggedIn) {
            _screenState.value = MainScreenState(
                isLoggedIn = false,
                selectedTab = MainTabType.Login
            )
            return
        }
        _screenState.update {
            it.copy(
                isLoggedIn = true,
                selectedTab = if (it.selectedTab == MainTabType.Login) MainTabType.Recommend else it.selectedTab
            )
        }
    }

    fun refreshAfterLogin() {
        _screenState.update {
            it.copy(
                isLoggedIn = true,
                selectedTab = MainTabType.Recommend,
                recommendListState = LiveListState.Loading
            )
        }
        loadUserProfile()
        refreshRecommendRooms(force = true)
    }

    fun refreshAfterResume() {
        if (!_screenState.value.isLoggedIn) {
            return
        }
        loadUserProfile()
        refreshCurrentTab()
    }

    fun switchTab(tab: MainTabType) {
        val state = _screenState.value
        if (!state.isLoggedIn && tab != MainTabType.Login) {
            return
        }
        if (state.selectedTab == tab) {
            return
        }

        val shouldResetSearchState = state.selectedTab == MainTabType.Search &&
            state.isShowingSearchResult &&
            tab != MainTabType.Search

        _screenState.update {
            if (shouldResetSearchState) {
                it.copy(
                    selectedTab = tab,
                    isShowingSearchResult = false,
                    searchRooms = emptyList(),
                    searchKeyword = ""
                )
            } else {
                it.copy(selectedTab = tab)
            }
        }
        when (tab) {
            MainTabType.Recommend -> refreshRecommendRooms(force = false)
            MainTabType.Following -> refreshFollowingRooms(force = true)
            MainTabType.Partition -> {
                if (_screenState.value.partitionAreas.isEmpty()) {
                    refreshPartitionAreas()
                } else {
                    refreshPartitionRooms(force = false)
                }
            }
            MainTabType.Search -> {
                // Do nothing specific, just show search container
            }
            else -> Unit
        }
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
                _screenState.update {
                    it.copy(
                        partitionAreas = areas,
                        selectedLevel1AreaId = if (areas.isNotEmpty()) areas[0].id else 0,
                        selectedLevel2AreaId = 0,
                        isLoadingPartitionAreas = false
                    )
                }
                refreshPartitionRooms(force = true)
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
        if (state.isLoadingPartitionRooms && !force) return
        
        _screenState.update {
            it.copy(
                isLoadingPartitionRooms = true,
                partitionListState = LiveListState.Loading
            )
        }
        viewModelScope.launch {
            val result = repository.fetchAreaRooms(state.selectedLevel1AreaId, state.selectedLevel2AreaId, 1)
            result.onSuccess { rooms ->
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
        _screenState.update {
            it.copy(
                selectedLevel1AreaId = areaId,
                selectedLevel2AreaId = 0 // reset to "全部"
            )
        }
        refreshPartitionRooms(force = true)
    }

    fun selectLevel2Area(areaId: Int) {
        val state = _screenState.value
        if (state.selectedLevel2AreaId == areaId) return
        _screenState.update {
            it.copy(
                selectedLevel2AreaId = areaId
            )
        }
        refreshPartitionRooms(force = true)
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
                isShowingSearchResult = true,
                searchKeyword = trimmedKeyword,
                isLoadingSearch = true,
                searchListState = LiveListState.Loading
            )
        }

        viewModelScope.launch {
            val result = repository.fetchSearchLiveRooms(trimmedKeyword, 1)
            result.onSuccess { rooms ->
                _screenState.update {
                    it.copy(
                        searchRooms = rooms,
                        searchListState = if (rooms.isEmpty()) LiveListState.Empty else LiveListState.Content,
                        isLoadingSearch = false
                    )
                }
            }.onFailure { error ->
                _screenState.update {
                    it.copy(
                        searchRooms = emptyList(),
                        searchListState = LiveListState.Error,
                        isLoadingSearch = false
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
                searchKeyword = ""
            )
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
