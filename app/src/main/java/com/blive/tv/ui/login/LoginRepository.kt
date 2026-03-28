package com.blive.tv.ui.login

import android.content.Context
import com.blive.tv.data.model.AuthCookie
import com.blive.tv.data.model.PollLoginData
import com.blive.tv.data.model.PollLoginResponse
import com.blive.tv.data.model.UserToken
import com.blive.tv.network.RetrofitClient
import com.blive.tv.network.SignGenerator
import com.blive.tv.utils.TokenManager
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class LoginRepository(private val context: Context) {
    suspend fun requestAuthCode(): Result<AuthTicket> = withContext(Dispatchers.IO) {
        runCatching {
            val response = awaitCall(RetrofitClient.passportApiService.getAuthCode(buildAuthCodeParams()))
            if (!response.isSuccessful) {
                throw IOException("网络请求失败")
            }
            val body = response.body() ?: throw IOException("响应为空")
            if (body.code != 0 || body.data == null) {
                throw IOException(body.message.ifEmpty { "获取二维码失败" })
            }
            AuthTicket(
                authCode = body.data.authCode,
                qrCodeUrl = body.data.url
            )
        }
    }

    suspend fun pollLoginStatus(authCode: String): Result<PollLoginResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val response = awaitCall(
                RetrofitClient.passportApiService.pollLoginStatus(buildPollParams(authCode))
            )
            if (!response.isSuccessful) {
                throw IOException("网络请求失败")
            }
            response.body() ?: throw IOException("响应为空")
        }
    }

    fun saveToken(data: PollLoginData) {
        val cookieDomain = resolveCookieDomain(data.cookieInfo.domains)
        val authCookies = data.cookieInfo.cookies.map { cookie ->
            AuthCookie(
                name = cookie.name,
                value = cookie.value,
                domain = cookieDomain,
                path = "/",
                hostOnly = false,
                persistent = cookie.expires > 0,
                httpOnly = cookie.httpOnly == 1,
                secure = cookie.secure == 1,
                expiresAt = normalizeEpoch(cookie.expires)
            )
        }
        val sessData = authCookies.firstOrNull { it.name.equals("SESSDATA", ignoreCase = true) }?.value
        val userToken = UserToken(
            accessToken = data.accessToken,
            refreshToken = data.refreshToken,
            expiresIn = data.expiresIn.toLong(),
            mid = data.mid,
            expireTime = System.currentTimeMillis() + (data.expiresIn * 1000L),
            sessData = sessData,
            cookies = authCookies
        )
        TokenManager.saveToken(context, userToken)
    }

    private fun normalizeEpoch(value: Long): Long? {
        if (value <= 0L) return null
        return if (value < 1_000_000_000_000L) value * 1000L else value
    }

    private fun resolveCookieDomain(domains: List<String>): String {
        val raw = domains.firstOrNull { it.isNotBlank() } ?: "bilibili.com"
        val withoutScheme = raw.substringAfter("://", raw)
        val host = withoutScheme.substringBefore("/").trim()
        return host.trimStart('.').ifBlank { "bilibili.com" }
    }

    private fun buildAuthCodeParams(): MutableMap<String, String> {
        val timestamp = SignGenerator.generateTimestamp()
        val params = mapOf(
            "appkey" to SignGenerator.getAppKey(),
            "local_id" to "0",
            "ts" to timestamp.toString()
        )
        val sign = SignGenerator.generateSign(params)
        return params.toMutableMap().apply {
            put("sign", sign)
        }
    }

    private fun buildPollParams(authCode: String): MutableMap<String, String> {
        val timestamp = SignGenerator.generateTimestamp()
        val params = mapOf(
            "appkey" to SignGenerator.getAppKey(),
            "auth_code" to authCode,
            "local_id" to "0",
            "ts" to timestamp.toString()
        )
        val sign = SignGenerator.generateSign(params)
        return params.toMutableMap().apply {
            put("sign", sign)
        }
    }

    private suspend fun <T> awaitCall(call: Call<T>): Response<T> = suspendCoroutine { continuation ->
        call.enqueue(object : Callback<T> {
            override fun onResponse(call: Call<T>, response: Response<T>) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call<T>, t: Throwable) {
                continuation.resumeWithException(t)
            }
        })
    }
}

data class AuthTicket(
    val authCode: String,
    val qrCodeUrl: String
)
