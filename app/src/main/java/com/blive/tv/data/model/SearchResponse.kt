package com.blive.tv.data.model

import com.google.gson.annotations.SerializedName

data class SearchLiveResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String,
    @SerializedName("data") val data: SearchData?
)

data class SearchData(
    @SerializedName("result") val result: SearchResult?
)

data class SearchResult(
    @SerializedName("live_room") val liveRoom: List<SearchLiveRoomItem>?
)

data class SearchLiveRoomItem(
    @SerializedName("roomid") val roomId: Long,
    @SerializedName("title") val title: String,
    @SerializedName("uname") val uname: String,
    @SerializedName("cover") val cover: String,
    @SerializedName("user_cover") val userCover: String?,
    @SerializedName("uface") val uface: String,
    @SerializedName("online") val online: Long,
    @SerializedName("cate_name") val cateName: String,
    @SerializedName("watched_show") val watchedShow: WatchedShowData?
) {
    fun toLiveRoomModel(): com.blive.tv.ui.main.LiveRoom {
        val rawCover = if (!userCover.isNullOrEmpty()) userCover else cover
        val resolvedCover = when {
            rawCover.startsWith("//") -> "http:$rawCover"
            rawCover.startsWith("https://") -> rawCover.replace("https://", "http://")
            else -> rawCover
        }
        val resolvedFace = when {
            uface.startsWith("//") -> "http:$uface"
            uface.startsWith("https://") -> uface.replace("https://", "http://")
            else -> uface
        }
        val htmlRemovedTitle = title.replace(Regex("<[^>]*>"), "")
        val htmlRemovedUname = uname.replace(Regex("<[^>]*>"), "")
        return com.blive.tv.ui.main.LiveRoom(
            roomId = roomId,
            coverUrl = resolvedCover,
            anchorName = htmlRemovedUname,
            anchorAvatar = resolvedFace,
            roomTitle = htmlRemovedTitle,
            areaName = cateName,
            viewerCount = online,
            viewerCountText = watchedShow?.textSmall?.ifBlank { null }
        )
    }
}
