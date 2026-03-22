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
                isLoggedIn = false,
                liveListState = LiveListState.Loading
            )
            return
        }
        _screenState.update {
            it.copy(isLoggedIn = true)
        }
    }

    fun refreshAfterLogin() {
        _screenState.update {
            it.copy(
                isLoggedIn = true,
                liveListState = LiveListState.Loading
            )
        }
        loadUserProfile()
        refreshLiveRooms()
    }

    fun refreshLiveRooms(force: Boolean = false) {
        val state = _screenState.value
        if (!state.isLoggedIn) {
            return
        }
        if (state.isLoadingLiveList && !force) {
            emitToast("正在刷新直播间列表...")
            return
        }
        _screenState.update {
            it.copy(
                isLoadingLiveList = true,
                liveListState = LiveListState.Loading
            )
        }
        viewModelScope.launch {
            val result = repository.fetchLiveRooms()
            result.onSuccess { rooms ->
                _screenState.update {
                    it.copy(
                        liveRooms = rooms,
                        liveListState = if (rooms.isEmpty()) LiveListState.Empty else LiveListState.Content,
                        isLoadingLiveList = false
                    )
                }
            }.onFailure { error ->
                _screenState.update {
                    it.copy(
                        liveRooms = emptyList(),
                        liveListState = LiveListState.Error,
                        isLoadingLiveList = false
                    )
                }
                emitToast("网络连接错误：${error.message}")
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
