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
        _screenState.update { it.copy(selectedTab = tab) }
        if (tab == MainTabType.Recommend || tab == MainTabType.Following) {
            refreshCurrentTab(force = true)
        }
    }

    fun refreshCurrentTab(force: Boolean = false) {
        when (_screenState.value.selectedTab) {
            MainTabType.Following -> refreshFollowingRooms(force)
            MainTabType.Recommend -> refreshRecommendRooms(force)
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
