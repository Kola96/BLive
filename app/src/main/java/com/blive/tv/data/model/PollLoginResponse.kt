package com.blive.tv.data.model

import com.google.gson.annotations.SerializedName

// 登录状态轮询响应
data class PollLoginResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String,
    @SerializedName("ttl") val ttl: Int,
    @SerializedName("data") val data: PollLoginData?
)

// 登录状态轮询响应数据
data class PollLoginData(
    @SerializedName("is_new") val isNew: Boolean,
    @SerializedName("mid") val mid: Long,
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("expires_in") val expiresIn: Int,
    @SerializedName("token_info") val tokenInfo: TokenInfo,
    @SerializedName("cookie_info") val cookieInfo: CookieInfo,
    @SerializedName("sso") val sso: List<String>
)

// Token信息
data class TokenInfo(
    @SerializedName("mid") val mid: Long,
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("expires_in") val expiresIn: Int
)

// Cookie信息
data class CookieInfo(
    @SerializedName("cookies") val cookies: List<Cookie>,
    @SerializedName("domains") val domains: List<String>
)

// Cookie
data class Cookie(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: String,
    @SerializedName("http_only") val httpOnly: Int,
    @SerializedName("expires") val expires: Long,
    @SerializedName("secure") val secure: Int
)
