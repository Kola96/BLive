package com.blive.tv.ui.login

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.blive.tv.MainActivity
import com.blive.tv.R
import com.blive.tv.utils.ToastHelper
import com.google.zxing.WriterException
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private val tag = "LoginActivity"
    private lateinit var qrCodeImage: android.widget.ImageView
    private lateinit var statusText: TextView
    private lateinit var countdownText: TextView
    private lateinit var cancelButton: androidx.cardview.widget.CardView
    private val qrCodeFactory = QrCodeBitmapFactory(300)
    private var lastQrCodeUrl: String? = null

    private val viewModel: LoginViewModel by viewModels {
        LoginViewModelFactory(LoginRepository(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        initUI()
        observeViewModel()
        viewModel.startLoginFlow()
        cancelButton.setOnClickListener {
            finish()
        }
    }

    private fun initUI() {
        qrCodeImage = findViewById(R.id.qr_code_image)
        statusText = findViewById(R.id.status_text)
        countdownText = findViewById(R.id.countdown_text)
        cancelButton = findViewById(R.id.cancel_button)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.screenState.collect { state ->
                        renderState(state)
                    }
                }
                launch {
                    viewModel.eventFlow.collect { event ->
                        handleEvent(event)
                    }
                }
            }
        }
    }

    private fun renderState(state: LoginScreenState) {
        statusText.text = state.statusText
        countdownText.text = state.countdownText
        val qrUrl = state.qrCodeUrl
        if (qrUrl != null && qrUrl != lastQrCodeUrl) {
            generateAndDisplayQRCode(qrUrl)
        }
    }

    private fun handleEvent(event: LoginEvent) {
        when (event) {
            is LoginEvent.ShowToast -> {
                ToastHelper.showTextToast(this, event.message)
            }

            is LoginEvent.NavigateToMain -> {
                navigateToMain()
            }
        }
    }

    private fun generateAndDisplayQRCode(url: String) {
        try {
            qrCodeImage.setImageBitmap(qrCodeFactory.create(url))
            lastQrCodeUrl = url
        } catch (e: WriterException) {
            Log.e(tag, "生成二维码失败", e)
            ToastHelper.showTextToast(this, "生成二维码失败")
        }
    }

    private fun navigateToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopLoginFlow()
    }
}
