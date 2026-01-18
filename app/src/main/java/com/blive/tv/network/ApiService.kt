package com.blive.tv.network

import com.blive.tv.data.model.*
import retrofit2.Call
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface ApiService {
    // 申请TV端登录二维码
    @FormUrlEncoded
    @POST("/x/passport-tv-login/qrcode/auth_code")
    fun getAuthCode(@FieldMap params: Map<String, String>): Call<AuthCodeResponse>

    // 轮询TV端登录状态
    @FormUrlEncoded
    @POST("/x/passport-tv-login/qrcode/poll")
    fun pollLoginStatus(@FieldMap params: Map<String, String>): Call<PollLoginResponse>

    // 获取用户关注的直播间列表
    @GET("/xlive/web-ucenter/user/following")
    fun getLiveUsers(
        @Header("Cookie") cookie: String,
        @retrofit2.http.Query("page") page: Int,
        @retrofit2.http.Query("page_size") pageSize: Int,
        @retrofit2.http.Query("ignoreRecord") ignoreRecord: Int = 1,
        @retrofit2.http.Query("hit_ab") hitAb: Boolean = true
    ): Call<LiveUsersResponse>

    // 获取用户信息
    @GET("/x/web-interface/nav")
    fun getNavInfo(
        @Header("Cookie") cookie: String
    ): Call<UserInfoResponse>

    // 获取直播间播放信息
    @GET("/xlive/web-room/v2/index/getRoomPlayInfo")
    fun getRoomPlayInfo(
        @Header("Cookie") cookie: String,
        @Query("room_id") roomId: Long,
        @Query("protocol") protocol: String = "0,1",
        @Query("format") format: String = "0,1,2",
        @Query("codec") codec: String = "0,1",
        @Query("qn") qn: Int = 10000
    ): Call<RoomPlayInfoResponse>
    
    // 获取弹幕服务器信息
    @GET("/room/v1/Danmu/getConf")
    fun getDanmuInfo(
        @Header("Cookie") cookie: String,
        @Query("room_id") roomId: Long,
        @Query("type") type: Int = 0
    ): Call<com.blive.tv.data.model.DanmuInfoResponse>
}
