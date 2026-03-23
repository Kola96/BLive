package com.blive.tv.network

import com.blive.tv.utils.AppRuntime
import com.blive.tv.utils.TokenManager
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val PASSPORT_BASE_URL = "https://passport.bilibili.com/"
    private const val API_BASE_URL = "https://api.bilibili.com/"
    private const val LIVE_API_BASE_URL = "https://api.live.bilibili.com/"
    private const val LIVE_WEB_BASE_URL = "https://live.bilibili.com/"
    private const val WEB_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36 Edg/145.0.0.0"

    private val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor()
        logging.setLevel(HttpLoggingInterceptor.Level.BODY)

        OkHttpClient.Builder()
            .cookieJar(PersistentCookieJar())
            .addInterceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder()
                if (shouldUseWebHeaders(original)) {
                    requestBuilder
                        .header("User-Agent", WEB_USER_AGENT)
                        .header("Referer", "https://live.bilibili.com/")
                        .header("Origin", "https://live.bilibili.com")
                        .header("Accept", "*/*")
                }
                if (shouldAttachSessData(original)) {
                    val sessData = TokenManager.getSessData(AppRuntime.appContext).orEmpty()
                    if (sessData.isNotEmpty() && original.header("Cookie").isNullOrEmpty()) {
                        requestBuilder.header("Cookie", "SESSDATA=$sessData")
                    }
                }
                chain.proceed(requestBuilder.build())
            }
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    
    private val gson by lazy {
        GsonBuilder()
            .setLenient()
            .create()
    }

    private val passportRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(PASSPORT_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    private val apiRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    private val liveApiRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(LIVE_API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    private val liveWebRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(LIVE_WEB_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }
    val passportApiService: ApiService by lazy {
        passportRetrofit.create(ApiService::class.java)
    }

    val apiService: ApiService by lazy {
        apiRetrofit.create(ApiService::class.java)
    }

    val liveApiService: ApiService by lazy {
        liveApiRetrofit.create(ApiService::class.java)
    }

    val liveWebApiService: ApiService by lazy {
        liveWebRetrofit.create(ApiService::class.java)
    }

    private fun shouldUseWebHeaders(request: Request): Boolean {
        val host = request.url.host
        val path = request.url.encodedPath
        if (host == "live.bilibili.com" && path == "/all") return true
        return host == "api.live.bilibili.com" && path == "/xlive/web-interface/v1/second/getUserRecommend"
    }

    private fun shouldAttachSessData(request: Request): Boolean {
        val host = request.url.host
        return host == "api.bilibili.com" || host == "api.live.bilibili.com" || host == "live.bilibili.com"
    }
}
