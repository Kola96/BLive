package com.blive.tv.data.model

import com.blive.tv.ui.main.LiveRoom
import com.google.gson.annotations.SerializedName

data class LiveRecommendResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String,
    @SerializedName("ttl") val ttl: Int = 0,
    @SerializedName("data") val data: LiveRecommendData? = null
)

data class LiveRecommendData(
    @SerializedName("list") val list: List<RecommendRoomItem> = emptyList()
)

data class RecommendRoomItem(
    @SerializedName("roomid") val roomId: Long = 0L,
    @SerializedName("uid") val uid: Long = 0L,
    @SerializedName("uname") val uname: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("face") val face: String = "",
    @SerializedName("user_cover") val userCover: String = "",
    @SerializedName("system_cover") val systemCover: String = "",
    @SerializedName("cover") val cover: String = "",
    @SerializedName("area_name") val areaName: String = "",
    @SerializedName("area_v2_name") val areaV2Name: String = "",
    @SerializedName("live_status") val liveStatus: Int = 0
)

fun RecommendRoomItem.toLiveRoom(): LiveRoom {
    val coverUrl = when {
        userCover.isNotEmpty() -> userCover
        systemCover.isNotEmpty() -> systemCover
        cover.isNotEmpty() -> cover
        else -> ""
    }
    val area = if (areaV2Name.isNotEmpty()) areaV2Name else areaName
    return LiveRoom(
        roomId = roomId,
        coverUrl = coverUrl,
        anchorName = uname,
        anchorAvatar = face,
        roomTitle = title,
        areaName = area
    )
}
