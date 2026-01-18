package com.blive.tv.data.model

import com.google.gson.annotations.SerializedName

// 用户登录凭证
data class UserToken(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("refresh_token") val refreshToken: String,
    @SerializedName("expires_in") val expiresIn: Long,
    @SerializedName("mid") val mid: Long,
    @SerializedName("expire_time") val expireTime: Long,
    @SerializedName("sess_data") val sessData: String? = null
)
