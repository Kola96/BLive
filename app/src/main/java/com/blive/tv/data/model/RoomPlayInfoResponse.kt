package com.blive.tv.data.model

import com.google.gson.annotations.SerializedName

data class RoomPlayInfoResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String,
    @SerializedName("ttl") val ttl: Int,
    @SerializedName("data") val data: RoomPlayInfoData?
)

data class RoomPlayInfoData(
    @SerializedName("room_id") val roomId: Int,
    @SerializedName("short_id") val shortId: Int,
    @SerializedName("uid") val uid: Long,
    @SerializedName("is_hidden") val isHidden: Boolean,
    @SerializedName("is_locked") val isLocked: Boolean,
    @SerializedName("is_portrait") val isPortrait: Boolean,
    @SerializedName("live_status") val liveStatus: Int,
    @SerializedName("hidden_till") val hiddenTill: Long,
    @SerializedName("lock_till") val lockTill: Long,
    @SerializedName("encrypted") val encrypted: Boolean,
    @SerializedName("pwd_verified") val pwdVerified: Boolean,
    @SerializedName("live_time") val liveTime: Long,
    @SerializedName("room_shield") val roomShield: Int,
    @SerializedName("all_special_types") val allSpecialTypes: List<Int>,
    @SerializedName("playurl_info") val playurlInfo: PlayUrlInfo
)

data class PlayUrlInfo(
    @SerializedName("conf_json") val confJson: String,
    @SerializedName("playurl") val playurl: PlayUrl
)

data class PlayUrl(
    @SerializedName("cid") val cid: Int,
    @SerializedName("g_qn_desc") val gQnDesc: List<QnDesc>,
    @SerializedName("stream") val stream: List<Stream>
)

data class QnDesc(
    @SerializedName("qn") val qn: Int,
    @SerializedName("desc") val desc: String,
    @SerializedName("hdr_desc") val hdrDesc: String,
    @SerializedName("attr_desc") val attrDesc: String?,
    @SerializedName("hdr_type") val hdrType: Int,
    @SerializedName("media_base_desc") val mediaBaseDesc: MediaBaseDesc?
)

data class MediaBaseDesc(
    @SerializedName("detail_desc") val detailDesc: DetailDesc?,
    @SerializedName("brief_desc") val briefDesc: BriefDesc?
)

data class DetailDesc(
    @SerializedName("desc") val desc: String,
    @SerializedName("tag") val tag: List<String>
)

data class BriefDesc(
    @SerializedName("desc") val desc: String,
    @SerializedName("badge") val badge: String
)

data class Stream(
    @SerializedName("protocol_name") val protocolName: String,
    @SerializedName("format") val format: List<Format>
)

data class Format(
    @SerializedName("format_name") val formatName: String,
    @SerializedName("codec") val codec: List<Codec>
)

data class Codec(
    @SerializedName("codec_name") val codecName: String,
    @SerializedName("current_qn") val currentQn: Int,
    @SerializedName("accept_qn") val acceptQn: List<Int>,
    @SerializedName("base_url") val baseUrl: String,
    @SerializedName("url_info") val urlInfo: List<UrlInfo>,
    @SerializedName("hdr_qn") val hdrQn: Int?,
    @SerializedName("dolby_type") val dolbyType: Int,
    @SerializedName("attr_name") val attrName: String,
    @SerializedName("hdr_type") val hdrType: Int,
    @SerializedName("drm") val drm: Boolean,
    @SerializedName("drm_key_systems") val drmKeySystems: Any?,
    @SerializedName("video_codecs") val videoCodecs: VideoCodecs,
    @SerializedName("audio_codecs") val audioCodecs: AudioCodecs
)

data class UrlInfo(
    @SerializedName("host") val host: String,
    @SerializedName("extra") val extra: String,
    @SerializedName("stream_ttl") val streamTtl: Int
)

data class VideoCodecs(
    @SerializedName("base") val base: String
)

data class AudioCodecs(
    @SerializedName("base") val base: String
)
