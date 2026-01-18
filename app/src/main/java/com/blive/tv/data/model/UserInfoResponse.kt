package com.blive.tv.data.model

import com.google.gson.annotations.SerializedName

// 用户信息响应
data class UserInfoResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String,
    @SerializedName("ttl") val ttl: Int,
    @SerializedName("data") val data: UserInfoData?
)

// 用户信息数据
data class UserInfoData(
    @SerializedName("isLogin") val isLogin: Boolean,
    @SerializedName("face") val face: String,
    @SerializedName("uname") val uname: String,
    @SerializedName("mid") val mid: Long
)
