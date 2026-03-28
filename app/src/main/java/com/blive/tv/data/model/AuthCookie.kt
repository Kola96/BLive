package com.blive.tv.data.model

import com.google.gson.annotations.SerializedName

data class AuthCookie(
    @SerializedName("name") val name: String,
    @SerializedName("value") val value: String,
    @SerializedName("domain") val domain: String = "bilibili.com",
    @SerializedName("path") val path: String = "/",
    @SerializedName("host_only") val hostOnly: Boolean = false,
    @SerializedName("persistent") val persistent: Boolean = false,
    @SerializedName("http_only") val httpOnly: Boolean = false,
    @SerializedName("secure") val secure: Boolean = false,
    @SerializedName("expires_at") val expiresAt: Long? = null
)

data class AuthCookieJar(
    @SerializedName("cookies") val cookies: List<AuthCookie> = emptyList()
) {
    fun find(name: String): AuthCookie? = cookies.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
