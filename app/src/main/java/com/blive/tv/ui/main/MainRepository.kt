package com.blive.tv.ui.main

import android.content.Context
import com.blive.tv.data.model.LiveUserItem
import com.blive.tv.data.model.LiveUsersResponse
import com.blive.tv.network.RetrofitClient
import com.blive.tv.utils.TokenManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.coroutines.suspendCoroutine

class MainRepository(private val context: Context) {
    suspend fun fetchUserProfile(): Result<UserProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val cookie = buildCookie()
            val response = awaitCall(RetrofitClient.apiService.getNavInfo(cookie))
            if (!response.isSuccessful) {
                throw IOException("网络请求失败：${response.code()}")
            }
            val body = response.body() ?: throw IOException("用户信息为空")
            if (body.code != 0 || body.data == null || !body.data.isLogin) {
                throw IOException(body.message.ifEmpty { "获取用户信息失败" })
            }
            UserProfile(
                nickname = body.data.uname,
                avatarUrl = body.data.face
            )
        }
    }

    suspend fun fetchLiveRooms(): Result<List<LiveRoom>> = withContext(Dispatchers.IO) {
        runCatching {
            val cookie = buildCookie()
            val liveUsers = fetchAllLiveUsers(cookie)
            liveUsers.map { item ->
                LiveRoom(
                    roomId = item.roomId,
                    coverUrl = item.roomCover,
                    anchorName = item.uname,
                    anchorAvatar = item.face,
                    roomTitle = item.title,
                    areaName = item.areaNameV2
                )
            }
        }
    }

    private suspend fun fetchAllLiveUsers(cookie: String): List<LiveUserItem> {
        val accumulated = mutableListOf<LiveUserItem>()
        var page = 1
        var continueFetching = true
        while (continueFetching) {
            val response = awaitCall(RetrofitClient.liveApiService.getLiveUsers(cookie, page, 20))
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

    private fun buildCookie(): String {
        val sessData = TokenManager.getSessData(context)
            ?: throw IOException("未登录")
        return "SESSDATA=$sessData"
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
