package com.blive.tv.ui.main

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

class MainViewModel(
    private val repository: MainRepository
) : ViewModel() {
    private val _screenState = MutableStateFlow(MainScreenState())
    val screenState: StateFlow<MainScreenState> = _screenState.asStateFlow()

    private val _toastEvent = MutableSharedFlow<String>()
    val toastEvent: SharedFlow<String> = _toastEvent.asSharedFlow()

    fun syncLoginState(isLoggedIn: Boolean) {
        if (!isLoggedIn) {
            _screenState.value = MainScreenState(
                isLoggedIn = false
            )
            return
        }
        _screenState.update {
            it.copy(
                isLoggedIn = true,
                selectedTab = MainTabType.Following
            )
        }
    }

    fun refreshAfterLogin() {
        _screenState.update {
            it.copy(
                isLoggedIn = true,
                selectedTab = MainTabType.Following,
                followingListState = LiveListState.Loading
            )
        }
        loadUserProfile()
        refreshFollowingRooms()
    }

    fun switchTab(tab: MainTabType) {
        val state = _screenState.value
        if (!state.isLoggedIn || state.selectedTab == tab) {
            return
        }
        _screenState.update { it.copy(selectedTab = tab) }
        if (tab == MainTabType.Recommend) {
            if (state.recommendRooms.isEmpty()) {
                val cachedRooms = repository.getCachedRecommendRooms()
                if (cachedRooms.isNotEmpty()) {
                    _screenState.update {
                        it.copy(
                            recommendRooms = cachedRooms,
                            recommendListState = LiveListState.Content
                        )
                    }
                }
            }
            refreshRecommendRooms(preferCache = true)
        } else if (_screenState.value.followingRooms.isEmpty()) {
            refreshFollowingRooms()
        }
    }

    fun refreshCurrentTab(force: Boolean = false) {
        when (_screenState.value.selectedTab) {
            MainTabType.Following -> refreshFollowingRooms(force)
            MainTabType.Recommend -> refreshRecommendRooms(force = force, preferCache = false)
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

    fun refreshRecommendRooms(
        force: Boolean = false,
        preferCache: Boolean = true
    ) {
        val state = _screenState.value
        if (!state.isLoggedIn) {
            return
        }
        if (state.isLoadingRecommend && !force) {
            emitToast("正在刷新推荐直播间...")
            return
        }
        if (preferCache && state.recommendRooms.isNotEmpty()) {
            _screenState.update {
                it.copy(
                    recommendListState = LiveListState.Content
                )
            }
        } else {
            _screenState.update {
                it.copy(recommendListState = LiveListState.Loading)
            }
        }
        _screenState.update {
            it.copy(isLoadingRecommend = true)
        }
        viewModelScope.launch {
            val result = repository.fetchRecommendRooms()
            result.onSuccess { rooms ->
                _screenState.update {
                    it.copy(
                        recommendRooms = rooms,
                        recommendListState = if (rooms.isEmpty()) LiveListState.Empty else LiveListState.Content,
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
