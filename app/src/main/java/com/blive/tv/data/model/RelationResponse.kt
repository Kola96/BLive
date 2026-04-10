package com.blive.tv.data.model

import com.google.gson.annotations.SerializedName

data class RelationResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String,
    @SerializedName("ttl") val ttl: Int,
    @SerializedName("data") val data: RelationData?
)

data class RelationData(
    @SerializedName("attribute") val attribute: Int  // 0=未关注, 2=已关注, 6=互粉
)

data class RelationModifyResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String,
    @SerializedName("ttl") val ttl: Int
)
