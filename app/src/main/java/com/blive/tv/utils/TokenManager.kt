package com.blive.tv.utils

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.blive.tv.data.model.UserToken
import com.google.gson.Gson
import java.util.concurrent.TimeUnit

object TokenManager {
    private const val PREF_NAME = "tv_login_token"
    private const val KEY_USER_TOKEN = "user_token"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"

    // 初始化EncryptedSharedPreferences
    private fun getEncryptedPreferences(context: Context): EncryptedSharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }

    // 保存登录凭证
    fun saveToken(context: Context, token: UserToken) {
        val prefs = getEncryptedPreferences(context)
        val gson = Gson()
        val tokenJson = gson.toJson(token)
        prefs.edit()
            .putString(KEY_USER_TOKEN, tokenJson)
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
    }

    // 获取登录凭证
    fun getToken(context: Context): UserToken? {
        val prefs = getEncryptedPreferences(context)
        val tokenJson = prefs.getString(KEY_USER_TOKEN, null)
        return if (tokenJson != null) {
            val gson = Gson()
            gson.fromJson(tokenJson, UserToken::class.java)
        } else {
            null
        }
    }

    // 检查是否已登录
    fun isLoggedIn(context: Context): Boolean {
        val prefs = getEncryptedPreferences(context)
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false) && getToken(context) != null
    }

    // 清除登录凭证
    fun clearToken(context: Context) {
        val prefs = getEncryptedPreferences(context)
        prefs.edit()
            .remove(KEY_USER_TOKEN)
            .putBoolean(KEY_IS_LOGGED_IN, false)
            .apply()
    }

    // 检查token是否过期
    fun isTokenExpired(context: Context): Boolean {
        val token = getToken(context)
        return token?.let {
            val currentTime = System.currentTimeMillis()
            currentTime > it.expireTime
        } ?: true
    }

    // 获取access_token
    fun getAccessToken(context: Context): String? {
        return getToken(context)?.accessToken
    }

    // 获取refresh_token
    fun getRefreshToken(context: Context): String? {
        return getToken(context)?.refreshToken
    }

    // 获取用户mid
    fun getUserId(context: Context): Long {
        return getToken(context)?.mid ?: 0
    }

    // 获取SESSDATA
    fun getSessData(context: Context): String? {
        return getToken(context)?.sessData
    }
}
