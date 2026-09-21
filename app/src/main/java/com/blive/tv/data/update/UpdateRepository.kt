package com.blive.tv.data.update

import android.util.Log
import com.blive.tv.BuildConfig
import com.blive.tv.data.update.api.GiteeReleaseApi
import com.blive.tv.data.update.api.GithubReleaseApi
import com.blive.tv.utils.VersionComparator
import com.google.gson.GsonBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 更新信息仓库。负责从 GitHub / Gitee Release API 拉取最新版本信息。
 *
 * 双源策略：
 * - GITHUB / GITEE：单源请求
 * - AUTO：并行请求两个源，谁先返回有效数据用谁；都失败则抛错
 */
object UpdateRepository {

    private const val TAG = "UpdateRepository"

    /** 用户选择的更新源模式 */
    enum class SourceMode { AUTO, GITHUB, GITEE }

    /** 更新检查失败异常 */
    class UpdateCheckException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private const val GITHUB_BASE_URL = "https://api.github.com/"
    private const val GITEE_BASE_URL = "https://gitee.com/"
    private const val USER_AGENT_PREFIX = "BLive-Android-TV/"

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val ua = USER_AGENT_PREFIX + BuildConfig.VERSION_NAME
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", ua)
                        .header("Accept", "application/vnd.github+json, application/json")
                        .build()
                )
            }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val gson by lazy { GsonBuilder().create() }

    private val githubApi: GithubReleaseApi by lazy {
        Retrofit.Builder()
            .baseUrl(GITHUB_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(GithubReleaseApi::class.java)
    }

    private val giteeApi: GiteeReleaseApi by lazy {
        Retrofit.Builder()
            .baseUrl(GITEE_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(GiteeReleaseApi::class.java)
    }

    /**
     * 检查更新。
     *
     * @param mode 更新源模式
     * @return 远端最新版本信息；若接口成功但解析失败 / 无 APK 资产，抛 [UpdateCheckException]
     */
    suspend fun fetchLatest(mode: SourceMode): UpdateInfo {
        Log.d(TAG, "fetchLatest mode=$mode, local version=${BuildConfig.VERSION_NAME}")
        val info = when (mode) {
            SourceMode.GITHUB -> fetchFromGithub()
            SourceMode.GITEE -> fetchFromGitee()
            SourceMode.AUTO -> fetchFromAuto()
        }
        Log.d(TAG, "fetchLatest success: remote=${info.versionName} from ${info.source}, url=${info.apkUrl}")
        return info
    }

    private suspend fun fetchFromGithub(): UpdateInfo {
        val dto = try {
            githubApi.getLatestRelease(UpdateSource.OWNER, UpdateSource.REPO)
        } catch (e: Exception) {
            Log.w(TAG, "GitHub API 请求失败", e)
            throw UpdateCheckException("GitHub 更新检查失败：${e.message}", e)
        }
        return dto.toUpdateInfo()
            ?: throw UpdateCheckException("GitHub 返回的 release 缺少 APK 资产或为预发布版本")
    }

    private suspend fun fetchFromGitee(): UpdateInfo {
        val dto = try {
            giteeApi.getLatestRelease(UpdateSource.OWNER, UpdateSource.REPO)
        } catch (e: Exception) {
            Log.w(TAG, "Gitee API 请求失败", e)
            throw UpdateCheckException("Gitee 更新检查失败：${e.message}", e)
        }
        return dto.toUpdateInfo()
            ?: throw UpdateCheckException("Gitee 返回的 release 缺少 APK 资产或为预发布版本")
    }

    /**
     * 并行竞速：两个源同时请求，谁先返回有效 UpdateInfo 用谁。
     * 两个都失败则聚合错误抛出。
     */
    private suspend fun fetchFromAuto(): UpdateInfo = supervisorScope {
        val githubDeferred = async {
            runCatching { fetchFromGithub() }
        }
        val giteeDeferred = async {
            runCatching { fetchFromGitee() }
        }

        // 等待两个都结束（无法中途取消另一个，因为我们需要收集失败原因）
        val githubResult = githubDeferred.await()
        val giteeResult = giteeDeferred.await()

        val githubInfo = githubResult.getOrNull()
        val giteeInfo = giteeResult.getOrNull()

        when {
            // 两个都成功 → 选版本号更新的那个（防止两边不同步）
            githubInfo != null && giteeInfo != null -> {
                if (VersionComparator.isNewer(giteeInfo.versionName, githubInfo.versionName)) {
                    giteeInfo
                } else {
                    githubInfo
                }
            }
            githubInfo != null -> githubInfo
            giteeInfo != null -> giteeInfo
            else -> {
                val githubErr = githubResult.exceptionOrNull()
                val giteeErr = giteeResult.exceptionOrNull()
                throw UpdateCheckException(
                    "两个更新源都失败：GitHub=${githubErr?.message ?: "未知"}；" +
                        "Gitee=${giteeErr?.message ?: "未知"}"
                )
            }
        }
    }
}
