package com.blive.tv.ui.login

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.blive.tv.MainActivity
import com.blive.tv.R
import com.blive.tv.data.model.AuthCodeResponse
import com.blive.tv.data.model.PollLoginResponse
import com.blive.tv.data.model.UserToken
import com.blive.tv.network.RetrofitClient
import com.blive.tv.network.SignGenerator
import com.blive.tv.utils.TokenManager
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.WriterException
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.*
import java.util.*

class LoginActivity : AppCompatActivity() {
    private val TAG = "LoginActivity"
    private val QR_CODE_SIZE = 300
    private val COUNTDOWN_SECONDS = 180L
    private val POLL_INTERVAL = 3000L

    private lateinit var qrCodeImage: android.widget.ImageView
    private lateinit var statusText: TextView
    private lateinit var countdownText: TextView
    private lateinit var cancelButton: androidx.cardview.widget.CardView

    private var authCode: String? = null
    private var countDownTimer: CountDownTimer? = null
    private var pollJob: Job? = null
    private var qrCodeUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // 初始化UI组件
        initUI()

        // 申请二维码
        requestAuthCode()

        // 设置取消按钮点击事件
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

    // 申请二维码
    private fun requestAuthCode() {
        statusText.text = "正在生成二维码..."

        // 生成请求参数
        val timestamp = SignGenerator.generateTimestamp()
        val appkey = SignGenerator.getAppKey()
        val params = mapOf(
            "appkey" to appkey,
            "local_id" to "0",
            "ts" to timestamp.toString()
        )
        val sign = SignGenerator.generateSign(params)

        val requestParams = params.toMutableMap()
        requestParams["sign"] = sign

        // 发送请求
        RetrofitClient.passportApiService.getAuthCode(requestParams).enqueue(
            object : retrofit2.Callback<AuthCodeResponse> {
                override fun onResponse(call: retrofit2.Call<AuthCodeResponse>, response: retrofit2.Response<AuthCodeResponse>) {
                    if (response.isSuccessful) {
                        val authCodeResponse = response.body()
                        if (authCodeResponse != null && authCodeResponse.code == 0) {
                            authCode = authCodeResponse.data?.authCode
                            qrCodeUrl = authCodeResponse.data?.url
                            if (authCode != null && qrCodeUrl != null) {
                                // 生成并显示二维码
                                generateAndDisplayQRCode(qrCodeUrl!!)
                                // 启动倒计时
                                startCountdown()
                                // 启动登录状态轮询
                                startPollingLoginStatus()
                            } else {
                                statusText.text = "获取二维码失败"
                                Toast.makeText(this@LoginActivity, "获取二维码失败", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            statusText.text = "获取二维码失败：${authCodeResponse?.message}"
                            Toast.makeText(this@LoginActivity, "获取二维码失败：${authCodeResponse?.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        statusText.text = "网络请求失败"
                        Toast.makeText(this@LoginActivity, "网络请求失败", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(call: retrofit2.Call<AuthCodeResponse>, t: Throwable) {
                    statusText.text = "网络连接错误：${t.message}"
                    Toast.makeText(this@LoginActivity, "网络连接错误：${t.message}", Toast.LENGTH_SHORT).show()
                    Log.e(TAG, "请求二维码失败", t)
                }
            }
        )
    }

    // 生成并显示二维码
    private fun generateAndDisplayQRCode(url: String) {
        try {
            val bitmap = generateQRCode(url)
            qrCodeImage.setImageBitmap(bitmap)
            statusText.text = "请使用B站APP扫描下方二维码登录"
        } catch (e: WriterException) {
            Log.e(TAG, "生成二维码失败", e)
            statusText.text = "生成二维码失败"
            Toast.makeText(this, "生成二维码失败", Toast.LENGTH_SHORT).show()
        }
    }

    // 生成二维码图片
    @Throws(WriterException::class)
    private fun generateQRCode(url: String): Bitmap {
        val bitMatrix: BitMatrix = MultiFormatWriter().encode(
            url,
            BarcodeFormat.QR_CODE,
            QR_CODE_SIZE,
            QR_CODE_SIZE,
            null
        )
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (bitMatrix.get(x, y)) {
                    android.graphics.Color.BLACK
                } else {
                    android.graphics.Color.WHITE
                }
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    // 启动倒计时
    private fun startCountdown() {
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(COUNTDOWN_SECONDS * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = millisUntilFinished / 1000
                countdownText.text = "${secondsLeft}秒后自动刷新"
            }

            override fun onFinish() {
                // 倒计时结束，重新申请二维码
                requestAuthCode()
            }
        }.start()
    }

    // 启动登录状态轮询
    private fun startPollingLoginStatus() {
        pollJob?.cancel()
        pollJob = GlobalScope.launch(Dispatchers.Main) {
            while (isActive && authCode != null) {
                delay(POLL_INTERVAL)
                pollLoginStatus()
            }
        }
    }

    // 轮询登录状态
    private fun pollLoginStatus() {
        if (authCode == null) return

        // 生成请求参数
        val timestamp = SignGenerator.generateTimestamp()
        val appkey = SignGenerator.getAppKey()
        val params = mapOf(
            "appkey" to appkey,
            "auth_code" to authCode!!,
            "local_id" to "0",
            "ts" to timestamp.toString()
        )
        val sign = SignGenerator.generateSign(params)

        val requestParams = params.toMutableMap()
        requestParams["sign"] = sign

        RetrofitClient.passportApiService.pollLoginStatus(requestParams).enqueue(
            object : retrofit2.Callback<PollLoginResponse> {
                override fun onResponse(call: retrofit2.Call<PollLoginResponse>, response: retrofit2.Response<PollLoginResponse>) {
                    if (response.isSuccessful) {
                        val pollResponse = response.body()
                        if (pollResponse != null) {
                            when (pollResponse.code) {
                                0 -> {
                                    // 登录成功
                                    handleLoginSuccess(pollResponse)
                                }
                                86038 -> {
                                    // 二维码已失效，重新申请
                                    statusText.text = "二维码已失效，正在重新生成..."
                                    requestAuthCode()
                                }
                                86039 -> {
                                    // 二维码尚未确认，继续轮询
                                    statusText.text = "请使用B站APP扫描二维码登录"
                                }
                                86090 -> {
                                    // 二维码已扫码未确认
                                    statusText.text = "请在手机上确认登录"
                                }
                                else -> {
                                    // 其他错误
                                    statusText.text = "登录失败：${pollResponse.message}"
                                    Toast.makeText(this@LoginActivity, "登录失败：${pollResponse.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } else {
                        Log.e(TAG, "轮询登录状态失败：${response.code()}")
                    }
                }

                override fun onFailure(call: retrofit2.Call<PollLoginResponse>, t: Throwable) {
                    Log.e(TAG, "轮询登录状态网络错误", t)
                }
            }
        )
    }

    // 处理登录成功
    private fun handleLoginSuccess(pollResponse: PollLoginResponse) {
        val data = pollResponse.data
        if (data != null) {
            // 从cookie列表中提取SESSDATA
            var sessData: String? = null
            data.cookieInfo.cookies.forEach { cookie ->
                if (cookie.name == "SESSDATA") {
                    sessData = cookie.value
                }
            }

            // 保存登录凭证
            val userToken = UserToken(
                accessToken = data.accessToken,
                refreshToken = data.refreshToken,
                expiresIn = data.expiresIn.toLong(),
                mid = data.mid,
                expireTime = System.currentTimeMillis() + (data.expiresIn * 1000L),
                sessData = sessData
            )
            TokenManager.saveToken(this, userToken)

            // 显示登录成功
            statusText.text = "登录成功！"
            Toast.makeText(this, "登录成功！", Toast.LENGTH_SHORT).show()

            // 取消计时器和轮询
            countDownTimer?.cancel()
            pollJob?.cancel()

            // 返回主界面
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 清理资源
        countDownTimer?.cancel()
        pollJob?.cancel()
    }
}
