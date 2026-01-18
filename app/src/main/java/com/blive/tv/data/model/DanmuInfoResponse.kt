package com.blive.tv.data.model

import com.google.gson.annotations.SerializedName

/**
 * 弹幕信息响应
 */
class DanmuInfoResponse {
    @SerializedName("code")
    var code: Int = 0
    
    @SerializedName("msg")
    var msg: String? = null
    
    @SerializedName("message")
    var message: String? = null
    
    @SerializedName("data")
    var data: DanmuInfoData? = null
}

/**
 * 弹幕信息数据
 */
class DanmuInfoData {
    @SerializedName("refresh_row_factor")
    var refreshRowFactor: Double? = null
    
    @SerializedName("refresh_rate")
    var refreshRate: Int? = null
    
    @SerializedName("max_delay")
    var maxDelay: Int? = null
    
    @SerializedName("port")
    var port: Int = 0
    
    @SerializedName("host")
    var host: String? = null
    
    @SerializedName("host_server_list")
    var hostList: List<DanmuHostInfo>? = null
    
    @SerializedName("server_list")
    var serverList: List<DanmuServerInfo>? = null
    
    @SerializedName("token")
    var token: String? = null
}

/**
 * 弹幕服务器信息
 */
class DanmuHostInfo {
    @SerializedName("host")
    var host: String? = null
    
    @SerializedName("port")
    var port: Int = 0
    
    @SerializedName("wss_port")
    var wssPort: Int = 0
    
    @SerializedName("ws_port")
    var wsPort: Int = 0
}

/**
 * 弹幕服务器IP信息
 */
class DanmuServerInfo {
    @SerializedName("host")
    var host: String? = null
    
    @SerializedName("port")
    var port: Int = 0
}