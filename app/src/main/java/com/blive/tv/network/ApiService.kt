package com.blive.tv.network

import com.blive.tv.data.model.AuthCodeResponse
import com.blive.tv.data.model.DanmuInfoResponse
import com.blive.tv.data.model.LiveRecommendResponse
import com.blive.tv.data.model.LiveUsersResponse
import com.blive.tv.data.model.PollLoginResponse
import com.blive.tv.data.model.RelationModifyResponse
import com.blive.tv.data.model.RelationResponse
import com.blive.tv.data.model.RoomBasicInfoResponse
import com.blive.tv.data.model.RoomPlayInfoResponse
import com.blive.tv.data.model.SearchLiveResponse
import com.blive.tv.data.model.UserInfoResponse
import com.blive.tv.data.model.WebAreaListResponse
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.QueryMap

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
        @retrofit2.http.Query("page") page: Int,
        @retrofit2.http.Query("page_size") pageSize: Int,
        @retrofit2.http.Query("ignoreRecord") ignoreRecord: Int = 1,
        @retrofit2.http.Query("hit_ab") hitAb: Boolean = true
    ): Call<LiveUsersResponse>

    // 获取用户信息
    @GET("/x/web-interface/nav")
    fun getNavInfo(): Call<UserInfoResponse>

    @GET("/x/frontend/finger/spi")
    fun getFingerSpiRaw(): Call<ResponseBody>

    @GET("/all")
    fun getLiveAllPageRaw(): Call<ResponseBody>

    @GET("/xlive/web-interface/v1/second/getUserRecommend")
    fun getUserRecommend(
        @QueryMap params: Map<String, String>
    ): Call<LiveRecommendResponse>

    @GET("/xlive/web-interface/v1/index/getWebAreaList")
    fun getWebAreaList(@Query("source_id") sourceId: Int = 1): Call<WebAreaListResponse>

    @GET("/xlive/web-interface/v1/second/getList")
    fun getAreaRoomList(
        @QueryMap params: Map<String, String>
    ): Call<LiveRecommendResponse>

    @GET("/room/v1/Room/get_info")
    fun getRoomBasicInfo(
        @Query("room_id") roomId: Long
    ): Call<RoomBasicInfoResponse>

    // 获取直播间播放信息
    @GET("/xlive/web-room/v2/index/getRoomPlayInfo")
    fun getRoomPlayInfo(
        @Query("room_id") roomId: Long,
        @Query("protocol") protocol: String = "0,1",
        @Query("format") format: String = "0,1,2",
        @Query("codec") codec: String = "0,1",
        @Query("qn") qn: Int = 10000
    ): Call<RoomPlayInfoResponse>
    
    // 获取弹幕服务器信息
    @GET("/room/v1/Danmu/getConf")
    fun getDanmuInfo(
        @Query("room_id") roomId: Long,
        @Query("type") type: Int = 0
    ): Call<com.blive.tv.data.model.DanmuInfoResponse>

    // 搜索直播间
    @GET("/x/web-interface/wbi/search/type")
    fun getLiveSearch(
        @QueryMap params: Map<String, String>
    ): Call<com.blive.tv.data.model.SearchLiveResponse>

    // 查询关注状态
    @GET("/x/relation")
    fun getRelationStatus(@Query("fid") fid: Long): Call<RelationResponse>

    // 关注/取关
    @FormUrlEncoded
    @POST("/x/relation/modify")
    fun modifyRelation(@FieldMap params: Map<String, String>): Call<RelationModifyResponse>
}
