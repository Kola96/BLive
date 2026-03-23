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
    @SerializedName("mid") val mid: Long,
    @SerializedName("vipStatus") val vipStatus: Int = 0,
    @SerializedName("vipType") val vipType: Int = 0,
    @SerializedName("level_info") val levelInfo: LevelInfoData? = null,
    @SerializedName("wbi_img") val wbiImg: WbiImgData? = null
)

data class LevelInfoData(
    @SerializedName("current_level") val currentLevel: Int = 0
)

data class WbiImgData(
    @SerializedName("img_url") val imgUrl: String = "",
    @SerializedName("sub_url") val subUrl: String = ""
)
