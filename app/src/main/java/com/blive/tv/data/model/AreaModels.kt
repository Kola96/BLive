package com.blive.tv.data.model

import com.google.gson.annotations.SerializedName

data class WebAreaListResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String,
    @SerializedName("data") val data: WebAreaListData? = null
)

data class WebAreaListData(
    @SerializedName("data") val data: List<AreaLevel1> = emptyList()
)

data class AreaLevel1(
    @SerializedName("id") val id: Int,
    @SerializedName("name") val name: String,
    @SerializedName("list") val list: List<AreaLevel2> = emptyList()
)

data class AreaLevel2(
    @SerializedName("id") val id: String,
    @SerializedName("parent_id") val parentId: String,
    @SerializedName("name") val name: String,
    @SerializedName("pic") val pic: String = ""
)
