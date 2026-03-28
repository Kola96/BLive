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
    @SerializedName("live_status") val liveStatus: Int = 0,
    @SerializedName("online") val online: Long = 0L,
    @SerializedName("watched_show") val watchedShow: WatchedShowData? = null
)

data class WatchedShowData(
    @SerializedName("text_small") val textSmall: String = ""
)

fun RecommendRoomItem.toLiveRoom(): LiveRoom {
    val rawCover = when {
        userCover.isNotEmpty() -> userCover
        systemCover.isNotEmpty() -> systemCover
        cover.isNotEmpty() -> cover
        else -> ""
    }
    val resolvedCover = when {
        rawCover.startsWith("//") -> "http:$rawCover"
        rawCover.startsWith("https://") -> rawCover.replace("https://", "http://")
        else -> rawCover
    }
    val resolvedFace = when {
        face.startsWith("//") -> "http:$face"
        face.startsWith("https://") -> face.replace("https://", "http://")
        else -> face
    }
    val area = if (areaV2Name.isNotEmpty()) areaV2Name else areaName
    return LiveRoom(
        roomId = roomId,
        coverUrl = resolvedCover,
        anchorName = uname,
        anchorAvatar = resolvedFace,
        roomTitle = title,
        areaName = area,
        viewerCount = online,
        viewerCountText = watchedShow?.textSmall?.takeIf { it.isNotBlank() }
    )
}
