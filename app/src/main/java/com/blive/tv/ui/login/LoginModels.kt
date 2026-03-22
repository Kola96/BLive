package com.blive.tv.ui.login

data class LoginScreenState(
    val isLoading: Boolean = false,
    val qrCodeUrl: String? = null,
    val statusText: String = "正在生成二维码...",
    val countdownText: String = ""
)

sealed class LoginEvent {
    data class ShowToast(val message: String) : LoginEvent()
    data object NavigateToMain : LoginEvent()
}
