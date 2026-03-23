package com.blive.tv.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.blive.tv.data.model.AuthCookie
import com.blive.tv.data.model.AuthCookieJar
import com.blive.tv.data.model.UserToken
import com.google.gson.Gson
import java.util.Locale

object TokenManager {
    private const val PREF_NAME = "tv_login_token"
    private const val KEY_USER_TOKEN = "user_token"
    private const val KEY_IS_LOGGED_IN = "is_logged_in"

    // 初始化EncryptedSharedPreferences
    private fun getEncryptedPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun getPreferences(context: Context): SharedPreferences {
        val appContext = context.applicationContext
        return runCatching {
            getEncryptedPreferences(appContext)
        }.getOrElse {
            appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    // 保存登录凭证
    fun saveToken(context: Context, token: UserToken) {
        val prefs = getPreferences(context)
        val gson = Gson()
        val tokenJson = gson.toJson(token)
        prefs.edit()
            .putString(KEY_USER_TOKEN, tokenJson)
            .putBoolean(KEY_IS_LOGGED_IN, true)
            .apply()
    }

    // 获取登录凭证
    fun getToken(context: Context): UserToken? {
        val prefs = getPreferences(context)
        val tokenJson = prefs.getString(KEY_USER_TOKEN, null)
        if (tokenJson.isNullOrEmpty()) return null
        return runCatching {
            val token = Gson().fromJson(tokenJson, UserToken::class.java)
            if (token == null) {
                null
            } else {
                val safeCookies = (token.cookies as? List<AuthCookie>).orEmpty()
                token.copy(cookies = safeCookies)
            }
        }.getOrNull()
    }

    fun getCookieJar(context: Context): AuthCookieJar? {
        val token = getToken(context) ?: return null
        val tokenCookies = (token.cookies as? List<AuthCookie>).orEmpty()
        val cookies = if (tokenCookies.isNotEmpty()) {
            tokenCookies
        } else {
            val sessData = token.sessData ?: return null
            listOf(
                AuthCookie(
                    name = "SESSDATA",
                    value = sessData,
                    domain = "bilibili.com",
                    path = "/"
                )
            )
        }
        return AuthCookieJar(cookies)
    }

    fun updateCookies(context: Context, updatedCookies: List<AuthCookie>) {
        if (updatedCookies.isEmpty()) return
        val token = getToken(context) ?: return
        val existingCookies = getCookieJar(context)?.cookies.orEmpty()
        val mergedCookies = mergeCookies(existingCookies, updatedCookies)
        val sessData = mergedCookies.firstOrNull { it.name.equals("SESSDATA", ignoreCase = true) }?.value
        saveToken(
            context,
            token.copy(
                sessData = sessData,
                cookies = mergedCookies
            )
        )
    }

    // 检查是否已登录
    fun isLoggedIn(context: Context): Boolean {
        val prefs = getPreferences(context)
        return runCatching {
            prefs.getBoolean(KEY_IS_LOGGED_IN, false) && getToken(context) != null
        }.getOrDefault(false)
    }

    // 清除登录凭证
    fun clearToken(context: Context) {
        val prefs = getPreferences(context)
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
        return getCookieJar(context)?.find("SESSDATA")?.value
    }

    private fun mergeCookies(existing: List<AuthCookie>, updates: List<AuthCookie>): List<AuthCookie> {
        val now = System.currentTimeMillis()
        val cookieMap = linkedMapOf<String, AuthCookie>()
        for (cookie in existing) {
            if (!shouldRemoveCookie(cookie, now)) {
                cookieMap[cookie.uniqueKey()] = cookie
            }
        }
        for (cookie in updates) {
            val key = cookie.uniqueKey()
            if (shouldRemoveCookie(cookie, now)) {
                cookieMap.remove(key)
            } else {
                cookieMap[key] = cookie
            }
        }
        return cookieMap.values.toList()
    }

    private fun shouldRemoveCookie(cookie: AuthCookie, now: Long): Boolean {
        if (cookie.value.isEmpty()) return true
        val expiresAt = cookie.expiresAt
        return expiresAt != null && expiresAt <= now
    }

    private fun AuthCookie.uniqueKey(): String {
        return listOf(
            name.lowercase(Locale.ROOT),
            domain.lowercase(Locale.ROOT),
            path
        ).joinToString("|")
    }
}
