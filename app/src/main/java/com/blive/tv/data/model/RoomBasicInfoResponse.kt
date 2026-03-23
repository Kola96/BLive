package com.blive.tv.data.model

import com.google.gson.annotations.SerializedName

data class RoomBasicInfoResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String = "",
    @SerializedName("msg") val msg: String = "",
    @SerializedName("data") val data: RoomBasicInfoData? = null
)

data class RoomBasicInfoData(
    @SerializedName("online") val online: Long = 0L,
    @SerializedName("live_status") val liveStatus: Int = 0
)
