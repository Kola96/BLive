package com.blive.tv.data.model

import com.google.gson.annotations.SerializedName

// 用户关注的直播间列表响应
data class LiveUsersResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String,
    @SerializedName("ttl") val ttl: Int,
    @SerializedName("data") val data: LiveUsersData?
)

// 直播间列表数据
data class LiveUsersData(
    @SerializedName("title") val title: String,
    @SerializedName("pageSize") val pageSize: Int,
    @SerializedName("totalPage") val totalPage: Int,
    @SerializedName("list") val list: List<LiveUserItem>
)

// 单个直播间用户项
data class LiveUserItem(
    @SerializedName("roomid") val roomId: Long,
    @SerializedName("uid") val uid: Long,
    @SerializedName("uname") val uname: String,
    @SerializedName("title") val title: String,
    @SerializedName("face") val face: String,
    @SerializedName("live_status") val liveStatus: Int,
    @SerializedName("room_cover") val roomCover: String,
    @SerializedName("area_name_v2") val areaNameV2: String,
    @SerializedName("online") val online: Long = 0L,
    @SerializedName("text_small") val textSmall: String = ""
) {
    fun toLiveRoom(): com.blive.tv.ui.main.LiveRoom {
        val resolvedCover = when {
            roomCover.startsWith("//") -> "http:$roomCover"
            roomCover.startsWith("https://") -> roomCover.replace("https://", "http://")
            else -> roomCover
        }
        val resolvedFace = when {
            face.startsWith("//") -> "http:$face"
            face.startsWith("https://") -> face.replace("https://", "http://")
            else -> face
        }
        return com.blive.tv.ui.main.LiveRoom(
            roomId = roomId,
            coverUrl = resolvedCover,
            anchorName = uname,
            anchorAvatar = resolvedFace,
            anchorMid = uid,
            roomTitle = title,
            areaName = areaNameV2,
            viewerCount = online,
            viewerCountText = textSmall.ifBlank { null }
        )
    }
}
