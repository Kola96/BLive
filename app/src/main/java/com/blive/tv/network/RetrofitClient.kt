package com.blive.tv.network

import com.blive.tv.utils.TokenManager
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    // 基础URL配置
    private const val PASSPORT_BASE_URL = "https://passport.bilibili.com"
    private const val API_BASE_URL = "https://api.bilibili.com"
    private const val LIVE_API_BASE_URL = "https://api.live.bilibili.com"
    
    // 创建OkHttpClient
    private val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor()
        logging.setLevel(HttpLoggingInterceptor.Level.BODY)
        
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    
    // 创建Gson实例
    private val gson by lazy {
        GsonBuilder()
            .setLenient()
            .create()
    }
    
    // Passport服务Retrofit实例（用于登录相关API）
    private val passportRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(PASSPORT_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }
    
    // API服务Retrofit实例（用于通用API）
    private val apiRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }
    
    // 直播API服务Retrofit实例（用于获取直播列表等API）
    private val liveApiRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(LIVE_API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }
    
    // Passport API服务（登录相关）
    val passportApiService: ApiService by lazy {
        passportRetrofit.create(ApiService::class.java)
    }
    
    // 主API服务（通用API）
    val apiService: ApiService by lazy {
        apiRetrofit.create(ApiService::class.java)
    }
    
    // 直播API服务（用于直播列表等）
    val liveApiService: ApiService by lazy {
        liveApiRetrofit.create(ApiService::class.java)
    }
}
