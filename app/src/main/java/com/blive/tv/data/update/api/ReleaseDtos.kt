package com.blive.tv.data.update.api

import com.blive.tv.data.update.UpdateInfo
import com.blive.tv.data.update.UpdateSource
import com.google.gson.annotations.SerializedName

/**
 * GitHub Releases API 响应
 * https://api.github.com/repos/{owner}/{repo}/releases/latest
 */
data class GithubReleaseDto(
    @SerializedName("tag_name") val tagName: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("body") val body: String?,
    @SerializedName("draft") val draft: Boolean?,
    @SerializedName("prerelease") val prerelease: Boolean?,
    @SerializedName("published_at") val publishedAt: String?,
    @SerializedName("assets") val assets: List<AssetDto>?
) {
    data class AssetDto(
        @SerializedName("name") val name: String?,
        @SerializedName("size") val size: Long?,
        @SerializedName("browser_download_url") val browserDownloadUrl: String?
    )

    fun toUpdateInfo(): UpdateInfo? {
        if (draft == true || prerelease == true) return null
        val rawTag = tagName?.trim().orEmpty()
        if (rawTag.isEmpty()) return null
        val version = rawTag.removePrefix("v").removePrefix("V")
        val apkAsset = assets.orEmpty().firstOrNull {
            it.name?.endsWith(".apk", ignoreCase = true) == true &&
                !it.browserDownloadUrl.isNullOrBlank()
        } ?: return null
        return UpdateInfo(
            versionName = version,
            changelog = body.orEmpty(),
            apkUrl = apkAsset.browserDownloadUrl!!,
            apkSize = apkAsset.size,
            publishedAt = publishedAt.orEmpty(),
            source = UpdateSource.GITHUB
        )
    }
}

/**
 * Gitee Releases API 响应
 * https://gitee.com/api/v5/repos/{owner}/{repo}/releases/latest
 *
 * 注意：Gitee 的 latest 端点不会自动过滤 prerelease，需要客户端判断。
 * Gitee 的 assets 字段结构跟 GitHub 几乎一致。
 */
data class GiteeReleaseDto(
    @SerializedName("tag_name") val tagName: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("body") val body: String?,
    @SerializedName("prerelease") val prerelease: Boolean?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("assets") val assets: List<AssetDto>?
) {
    data class AssetDto(
        @SerializedName("name") val name: String?,
        // Gitee 部分版本可能不返回 size
        @SerializedName("size") val size: Long?,
        @SerializedName("browser_download_url") val browserDownloadUrl: String?
    )

    fun toUpdateInfo(): UpdateInfo? {
        if (prerelease == true) return null
        val rawTag = tagName?.trim().orEmpty()
        if (rawTag.isEmpty()) return null
        val version = rawTag.removePrefix("v").removePrefix("V")
        val apkAsset = assets.orEmpty().firstOrNull {
            it.name?.endsWith(".apk", ignoreCase = true) == true &&
                !it.browserDownloadUrl.isNullOrBlank()
        } ?: return null
        return UpdateInfo(
            versionName = version,
            changelog = body.orEmpty(),
            apkUrl = apkAsset.browserDownloadUrl!!,
            apkSize = apkAsset.size,
            publishedAt = createdAt.orEmpty(),
            source = UpdateSource.GITEE
        )
    }
}
