package com.blive.tv.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LoginViewModel(
    private val repository: LoginRepository
) : ViewModel() {
    companion object {
        private const val COUNTDOWN_SECONDS = 180L
        private const val POLL_INTERVAL_MS = 3000L
    }

    private val _screenState = MutableStateFlow(LoginScreenState())
    val screenState: StateFlow<LoginScreenState> = _screenState.asStateFlow()

    private val _eventFlow = MutableSharedFlow<LoginEvent>()
    val eventFlow: SharedFlow<LoginEvent> = _eventFlow.asSharedFlow()

    private var authCode: String? = null
    private var countdownJob: Job? = null
    private var pollingJob: Job? = null

    fun startLoginFlow() {
        requestAuthCodeAndStartFlow()
    }

    fun stopLoginFlow() {
        countdownJob?.cancel()
        pollingJob?.cancel()
    }

    private fun requestAuthCodeAndStartFlow() {
        stopLoginFlow()
        _screenState.update {
            it.copy(
                isLoading = true,
                statusText = "正在生成二维码...",
                countdownText = "",
                qrCodeUrl = null
            )
        }
        viewModelScope.launch {
            val result = repository.requestAuthCode()
            result.onSuccess { ticket ->
                authCode = ticket.authCode
                _screenState.update {
                    it.copy(
                        isLoading = false,
                        qrCodeUrl = ticket.qrCodeUrl,
                        statusText = "请使用B站APP扫描下方二维码登录"
                    )
                }
                startCountdown()
                startPolling()
            }.onFailure { error ->
                _screenState.update {
                    it.copy(
                        isLoading = false,
                        statusText = "获取二维码失败"
                    )
                }
                emitEvent(LoginEvent.ShowToast("获取二维码失败：${error.message}"))
            }
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (secondsLeft in COUNTDOWN_SECONDS downTo 1) {
                _screenState.update {
                    it.copy(countdownText = "${secondsLeft}秒后自动刷新")
                }
                delay(1000)
            }
            requestAuthCodeAndStartFlow()
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive && authCode != null) {
                delay(POLL_INTERVAL_MS)
                val currentAuthCode = authCode ?: break
                val result = repository.pollLoginStatus(currentAuthCode)
                result.onSuccess { response ->
                    handlePollResponse(response.code, response.message, response.data)
                }
            }
        }
    }

    private fun handlePollResponse(code: Int, message: String, data: com.blive.tv.data.model.PollLoginData?) {
        when (code) {
            0 -> {
                if (data != null) {
                    repository.saveToken(data)
                    _screenState.update {
                        it.copy(statusText = "登录成功！")
                    }
                    emitEvent(LoginEvent.ShowToast("登录成功！"))
                    emitEvent(LoginEvent.NavigateToMain)
                    stopLoginFlow()
                }
            }

            86038 -> {
                _screenState.update {
                    it.copy(statusText = "二维码已失效，正在重新生成...")
                }
                requestAuthCodeAndStartFlow()
            }

            86039 -> {
                _screenState.update {
                    it.copy(statusText = "请使用B站APP扫描二维码登录")
                }
            }

            86090 -> {
                _screenState.update {
                    it.copy(statusText = "请在手机上确认登录")
                }
            }

            else -> {
                _screenState.update {
                    it.copy(statusText = "登录失败：$message")
                }
                emitEvent(LoginEvent.ShowToast("登录失败：$message"))
            }
        }
    }

    private fun emitEvent(event: LoginEvent) {
        viewModelScope.launch {
            _eventFlow.emit(event)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopLoginFlow()
    }
}

class LoginViewModelFactory(
    private val repository: LoginRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LoginViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LoginViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
