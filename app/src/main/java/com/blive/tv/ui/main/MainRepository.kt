package com.blive.tv.ui.main

import com.blive.tv.data.model.AuthCookie
import com.blive.tv.data.model.LiveUserItem
import com.blive.tv.data.model.LiveUsersResponse
import com.blive.tv.data.model.*
import com.blive.tv.data.model.toLiveRoom
import com.blive.tv.data.model.AreaLevel1
import com.blive.tv.data.model.AreaLevel2
import com.blive.tv.network.RetrofitClient
import com.blive.tv.network.WbiKeyParser
import com.blive.tv.network.WbiSigner
import com.blive.tv.network.WebIdExtractor
import com.blive.tv.utils.AppRuntime
import com.blive.tv.utils.TokenManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.coroutines.suspendCoroutine

class MainRepository {
    suspend fun fetchUserProfile(): Result<UserProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val response = awaitCall(RetrofitClient.apiService.getNavInfo())
            if (!response.isSuccessful) {
                throw IOException("网络请求失败：${response.code()}")
            }
            val body = response.body() ?: throw IOException("用户信息为空")
            if (body.code != 0 || body.data == null || !body.data.isLogin) {
                throw IOException(body.message.ifEmpty { "获取用户信息失败" })
            }
            UserProfile(
                nickname = body.data.uname,
                avatarUrl = fixProtocol(body.data.face),
                vipLabel = resolveVipLabel(body.data.vipStatus, body.data.vipType),
                level = body.data.levelInfo?.currentLevel ?: 0
            )
        }
    }

    private fun fixProtocol(url: String): String {
        return when {
            url.startsWith("//") -> "http:$url"
            url.startsWith("https://") -> url.replace("https://", "http://")
            else -> url
        }
    }

    suspend fun fetchLiveRooms(): Result<List<LiveRoom>> = withContext(Dispatchers.IO) {
        runCatching {
            val liveUsers = fetchAllLiveUsers()
            liveUsers.map { it.toLiveRoom() }
        }
    }

    suspend fun fetchWebAreaList(): Result<List<AreaLevel1>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = awaitCall(RetrofitClient.liveApiService.getWebAreaList())
            if (!response.isSuccessful) {
                throw IOException("获取分区列表失败：${response.code()}")
            }
            val body = response.body() ?: throw IOException("分区列表响应为空")
            if (body.code != 0 || body.data == null) {
                throw IOException(body.message.ifEmpty { "获取分区列表失败" })
            }
            val originalList = body.data!!.data
            
            // 为每个一级分区处理二级分区：只取前 10 个，并在前面插入“全部”(id=0)
            val processedList = originalList.map { level1 ->
                val allLevel2 = AreaLevel2(id = "0", parentId = level1.id.toString(), name = "全部")
                val limitedLevel2 = listOf(allLevel2) + level1.list.take(10)
                level1.copy(list = limitedLevel2)
            }
            
            processedList
        }
    }

    suspend fun fetchAreaRooms(parentAreaId: Int, areaId: Int, page: Int = 1): Result<List<LiveRoom>> = withContext(Dispatchers.IO) {
        if (parentAreaId == 0) {
            // "全部"一级分区，使用推荐列表代替
            return@withContext fetchRecommendRooms()
        }
        runCatching {
            ensureSpiCookies()
            val allPageResponse = awaitCall(RetrofitClient.liveWebApiService.getLiveAllPageRaw())
            if (!allPageResponse.isSuccessful) {
                throw IOException("获取w_webid失败：${allPageResponse.code()}")
            }
            val rawHtml = allPageResponse.body()?.string().orEmpty()
            val wWebId = WebIdExtractor.extract(rawHtml)
            if (wWebId.isEmpty()) {
                throw IOException("提取w_webid失败")
            }
            
            val navResponse = awaitCall(RetrofitClient.apiService.getNavInfo())
            if (!navResponse.isSuccessful) {
                throw IOException("获取WBI密钥失败：${navResponse.code()}")
            }
            val navBody = navResponse.body() ?: throw IOException("nav响应为空")
            if (navBody.code != 0 || navBody.data == null) {
                throw IOException(navBody.message.ifEmpty { "获取WBI密钥失败" })
            }
            val imgUrl = navBody.data.wbiImg?.imgUrl.orEmpty()
            val subUrl = navBody.data.wbiImg?.subUrl.orEmpty()
            val imgKey = WbiKeyParser.parseFromUrl(imgUrl)
            val subKey = WbiKeyParser.parseFromUrl(subUrl)
            if (imgKey.isEmpty() || subKey.isEmpty()) {
                throw IOException("解析WBI密钥失败")
            }

            val unsignedParams = mutableMapOf(
                "platform" to "web",
                "parent_area_id" to parentAreaId.toString(),
                "area_id" to areaId.toString(),
                "page" to page.toString(),
                "sort_type" to "",
                "w_webid" to wWebId
            )
            val (wRid, wts) = WbiSigner.sign(unsignedParams, imgKey, subKey)
            val requestParams = unsignedParams.toMutableMap()
            requestParams["w_rid"] = wRid
            requestParams["wts"] = wts
            
            val response = awaitCall(RetrofitClient.liveApiService.getAreaRoomList(requestParams))
            if (!response.isSuccessful) {
                throw IOException("获取分区直播间失败：${response.code()}")
            }
            val body = response.body() ?: throw IOException("分区直播间响应为空")
            if (body.code != 0) {
                throw IOException(body.message.ifEmpty { "获取分区直播间失败" })
            }
            body.data?.list.orEmpty().map { it.toLiveRoom() }
        }
    }

    suspend fun fetchRecommendRooms(): Result<List<LiveRoom>> = withContext(Dispatchers.IO) {
        runCatching {
            ensureSpiCookies()
            val allPageResponse = awaitCall(RetrofitClient.liveWebApiService.getLiveAllPageRaw())
            if (!allPageResponse.isSuccessful) {
                throw IOException("获取w_webid失败：${allPageResponse.code()}")
            }
            val rawHtml = allPageResponse.body()?.string().orEmpty()
            val wWebId = WebIdExtractor.extract(rawHtml)
            if (wWebId.isEmpty()) {
                throw IOException("提取w_webid失败")
            }
            val navResponse = awaitCall(RetrofitClient.apiService.getNavInfo())
            if (!navResponse.isSuccessful) {
                throw IOException("获取WBI密钥失败：${navResponse.code()}")
            }
            val navBody = navResponse.body() ?: throw IOException("nav响应为空")
            if (navBody.code != 0 || navBody.data == null) {
                throw IOException(navBody.message.ifEmpty { "获取WBI密钥失败" })
            }
            val imgUrl = navBody.data.wbiImg?.imgUrl.orEmpty()
            val subUrl = navBody.data.wbiImg?.subUrl.orEmpty()
            val imgKey = WbiKeyParser.parseFromUrl(imgUrl)
            val subKey = WbiKeyParser.parseFromUrl(subUrl)
            if (imgKey.isEmpty() || subKey.isEmpty()) {
                throw IOException("解析WBI密钥失败")
            }

            val (rooms1, rooms2) = coroutineScope {
                val deferred1 = async {
                    fetchSinglePageRecommend(1, 50, wWebId, imgKey, subKey)
                }
                val deferred2 = async {
                    fetchSinglePageRecommend(2, 50, wWebId, imgKey, subKey)
                }
                Pair(deferred1.await(), deferred2.await())
            }

            (rooms1 + rooms2).distinctBy { it.roomId }
        }
    }

    suspend fun fetchSearchLiveRooms(keyword: String, page: Int = 1): Result<List<LiveRoom>> = withContext(Dispatchers.IO) {
        runCatching {
            ensureSpiCookies()
            val navResponse = awaitCall(RetrofitClient.apiService.getNavInfo())
            if (!navResponse.isSuccessful) {
                throw IOException("获取WBI密钥失败：${navResponse.code()}")
            }
            val navBody = navResponse.body() ?: throw IOException("nav响应为空")
            if (navBody.code != 0 || navBody.data == null) {
                throw IOException(navBody.message.ifEmpty { "获取WBI密钥失败" })
            }
            val imgUrl = navBody.data.wbiImg?.imgUrl.orEmpty()
            val subUrl = navBody.data.wbiImg?.subUrl.orEmpty()
            val imgKey = WbiKeyParser.parseFromUrl(imgUrl)
            val subKey = WbiKeyParser.parseFromUrl(subUrl)
            if (imgKey.isEmpty() || subKey.isEmpty()) {
                throw IOException("解析WBI密钥失败")
            }

            val unsignedParams = mutableMapOf(
                "search_type" to "live",
                "keyword" to keyword,
                "page" to page.toString()
            )
            val (wRid, wts) = WbiSigner.sign(unsignedParams, imgKey, subKey)
            val requestParams = unsignedParams.toMutableMap()
            requestParams["w_rid"] = wRid
            requestParams["wts"] = wts

            val response = awaitCall(RetrofitClient.apiService.getLiveSearch(requestParams))
            if (!response.isSuccessful) {
                throw IOException("搜索请求失败：${response.code()}")
            }
            val body = response.body() ?: throw IOException("搜索结果为空")
            if (body.code != 0) {
                throw IOException(body.message.ifEmpty { "搜索失败" })
            }
            body.data?.result?.liveRoom?.filter { it.roomId > 0L }?.map { it.toLiveRoomModel() } ?: emptyList()
        }
    }

    private suspend fun fetchSinglePageRecommend(
        page: Int,
        pageSize: Int,
        wWebId: String,
        imgKey: String,
        subKey: String
    ): List<LiveRoom> {
        val unsignedParams = mutableMapOf(
            "page" to page.toString(),
            "page_size" to pageSize.toString(),
            "platform" to "web",
            "web_location" to "444.253",
            "w_webid" to wWebId
        )
        val (wRid, wts) = WbiSigner.sign(unsignedParams, imgKey, subKey)
        val requestParams = unsignedParams.toMutableMap()
        requestParams["w_rid"] = wRid
        requestParams["wts"] = wts
        val response = awaitCall(RetrofitClient.liveApiService.getUserRecommend(requestParams))
        if (!response.isSuccessful) return emptyList()
        val body = response.body() ?: return emptyList()
        if (body.code != 0) return emptyList()
        return body.data?.list.orEmpty().map { it.toLiveRoom() }
    }

    private fun resolveVipLabel(vipStatus: Int, vipType: Int): String? {
        if (vipStatus != 1) {
            return null
        }
        return when (vipType) {
            2 -> "年度大会员"
            1 -> "大会员"
            else -> null
        }
    }

    private suspend fun ensureSpiCookies() {
        val spiResponse = awaitCall(RetrofitClient.apiService.getFingerSpiRaw())
        if (!spiResponse.isSuccessful) return
        val spiBody = spiResponse.body()?.string().orEmpty()
        if (spiBody.isEmpty()) return
        val json = runCatching { JSONObject(spiBody) }.getOrNull() ?: return
        val data = json.optJSONObject("data") ?: return
        val buvid3 = data.optString("b_3", "")
        val buvid4 = data.optString("b_4", "")
        if (buvid3.isEmpty() && buvid4.isEmpty()) return
        val now = System.currentTimeMillis()
        val cookies = mutableListOf<AuthCookie>()
        if (buvid3.isNotEmpty()) {
            cookies.add(
                AuthCookie(
                    name = "buvid3",
                    value = buvid3,
                    domain = "bilibili.com",
                    path = "/",
                    hostOnly = false,
                    persistent = true,
                    expiresAt = now + 365L * 24 * 60 * 60 * 1000
                )
            )
        }
        if (buvid4.isNotEmpty()) {
            cookies.add(
                AuthCookie(
                    name = "buvid4",
                    value = buvid4,
                    domain = "bilibili.com",
                    path = "/",
                    hostOnly = false,
                    persistent = true,
                    expiresAt = now + 365L * 24 * 60 * 60 * 1000
                )
            )
        }
        if (cookies.isNotEmpty()) {
            TokenManager.updateCookies(AppRuntime.appContext, cookies)
        }
    }

    private suspend fun fetchAllLiveUsers(): List<LiveUserItem> {
        val accumulated = mutableListOf<LiveUserItem>()
        var page = 1
        var continueFetching = true
        while (continueFetching) {
            val response = awaitCall(RetrofitClient.liveApiService.getLiveUsers(page, 20))
            if (!response.isSuccessful) {
                throw IOException("网络请求失败：${response.code()}")
            }
            val body = response.body() ?: throw IOException("直播列表响应为空")
            if (body.code != 0) {
                throw IOException(body.message.ifEmpty { "获取直播列表失败" })
            }
            val pageResult = handlePageData(body, accumulated)
            continueFetching = pageResult.shouldContinue && page < pageResult.totalPage
            page += 1
        }
        return accumulated
    }

    private fun handlePageData(
        response: LiveUsersResponse,
        accumulated: MutableList<LiveUserItem>
    ): PageResult {
        val data = response.data ?: return PageResult(false, 0)
        val currentPageRooms = data.list
        if (currentPageRooms.isEmpty()) {
            return PageResult(false, data.totalPage)
        }
        var shouldContinueFetching = true
        for (room in currentPageRooms) {
            if (room.liveStatus == 1) {
                accumulated.add(room)
            } else {
                shouldContinueFetching = false
                break
            }
        }
        return PageResult(shouldContinueFetching, data.totalPage)
    }

    private suspend fun <T> awaitCall(call: Call<T>): Response<T> = suspendCoroutine { continuation ->
        call.enqueue(object : Callback<T> {
            override fun onResponse(call: Call<T>, response: Response<T>) {
                continuation.resume(response)
            }

            override fun onFailure(call: Call<T>, t: Throwable) {
                if (t is CancellationException) {
                    continuation.resumeWithException(t)
                    return
                }
                continuation.resumeWithException(t)
            }
        })
    }

    private data class PageResult(
        val shouldContinue: Boolean,
        val totalPage: Int
    )
}
