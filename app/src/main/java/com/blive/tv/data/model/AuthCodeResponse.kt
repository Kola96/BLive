package com.blive.tv.data.model

import com.google.gson.annotations.SerializedName

// 二维码申请响应
data class AuthCodeResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String,
    @SerializedName("ttl") val ttl: Int,
    @SerializedName("data") val data: AuthCodeData?
)

// 二维码申请响应数据
data class AuthCodeData(
    @SerializedName("url") val url: String,
    @SerializedName("auth_code") val authCode: String
)
