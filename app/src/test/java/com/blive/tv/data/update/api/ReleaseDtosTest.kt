package com.blive.tv.data.update.api

import com.blive.tv.data.update.UpdateSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseDtosTest {

    private fun githubAsset(
        name: String = "BLive-release-v1.0.2.apk",
        size: Long = 4_500_000L,
        url: String = "https://github.com/Kola96/BLive/releases/download/v1.0.2/app-release.apk"
    ) = GithubReleaseDto.AssetDto(name = name, size = size, browserDownloadUrl = url)

    private fun githubRelease(
        tagName: String = "v1.0.2",
        body: String = "修复若干问题",
        draft: Boolean = false,
        prerelease: Boolean = false,
        assets: List<GithubReleaseDto.AssetDto> = listOf(githubAsset())
    ) = GithubReleaseDto(
        tagName = tagName,
        name = "v1.0.2",
        body = body,
        draft = draft,
        prerelease = prerelease,
        publishedAt = "2026-09-21T00:00:00Z",
        assets = assets
    )

    private fun giteeAsset(
        name: String = "BLive-release-v1.0.2.apk",
        size: Long? = 4_500_000L,
        url: String = "https://gitee.com/Kola96/BLive/releases/download/v1.0.2/app-release.apk"
    ) = GiteeReleaseDto.AssetDto(name = name, size = size, browserDownloadUrl = url)

    private fun giteeRelease(
        tagName: String = "v1.0.2",
        body: String = "修复若干问题",
        prerelease: Boolean = false,
        assets: List<GiteeReleaseDto.AssetDto> = listOf(giteeAsset())
    ) = GiteeReleaseDto(
        tagName = tagName,
        name = "v1.0.2",
        body = body,
        prerelease = prerelease,
        createdAt = "2026-09-21T00:00:00Z",
        assets = assets
    )

    // ---------------- 正常解析 ----------------

    @Test
    fun `github normal release parses to update info`() {
        val info = githubRelease().toUpdateInfo()!!
        assertEquals("1.0.2", info.versionName)
        assertEquals("修复若干问题", info.changelog)
        assertEquals("https://github.com/Kola96/BLive/releases/download/v1.0.2/app-release.apk", info.apkUrl)
        assertEquals(4_500_000L, info.apkSize)
        assertEquals(UpdateSource.GITHUB, info.source)
    }

    @Test
    fun `gitee normal release parses to update info`() {
        val info = giteeRelease().toUpdateInfo()!!
        assertEquals("1.0.2", info.versionName)
        assertEquals(UpdateSource.GITEE, info.source)
        assertEquals("2026-09-21T00:00:00Z", info.publishedAt)
    }

    @Test
    fun `uppercase V tag prefix is stripped`() {
        assertEquals("1.0.2", githubRelease(tagName = "V1.0.2").toUpdateInfo()!!.versionName)
        assertEquals("1.0.2", giteeRelease(tagName = "V1.0.2").toUpdateInfo()!!.versionName)
    }

    @Test
    fun `apk asset is matched case insensitively and skips non apk assets`() {
        val info = githubRelease(
            assets = listOf(
                githubAsset(name = "checksums.txt"),
                githubAsset(
                    name = "BLive-Update-v1.0.2.APK",
                    url = "https://github.com/Kola96/BLive/releases/download/v1.0.2/BLive-Update-v1.0.2.APK"
                )
            )
        ).toUpdateInfo()
        assertNotNull(info)
        assertEquals("BLive-Update-v1.0.2.APK", info!!.apkUrl.substringAfterLast('/'))
    }

    @Test
    fun `gitee asset with null size still parses`() {
        val info = giteeRelease(assets = listOf(giteeAsset(size = null))).toUpdateInfo()
        assertNotNull(info)
        assertEquals(null, info!!.apkSize)
    }

    // ---------------- 应返回 null 的场景 ----------------

    @Test
    fun `github draft release is rejected`() {
        assertNull(githubRelease(draft = true).toUpdateInfo())
    }

    @Test
    fun `github prerelease is rejected`() {
        assertNull(githubRelease(prerelease = true).toUpdateInfo())
    }

    @Test
    fun `gitee prerelease is rejected`() {
        assertNull(giteeRelease(prerelease = true).toUpdateInfo())
    }

    @Test
    fun `blank tag is rejected`() {
        assertNull(githubRelease(tagName = "  ").toUpdateInfo())
        assertNull(githubRelease(tagName = "").toUpdateInfo())
    }

    @Test
    fun `release without apk asset is rejected`() {
        assertNull(githubRelease(assets = emptyList()).toUpdateInfo())
        assertNull(
            githubRelease(assets = listOf(githubAsset(name = "notes.md"))).toUpdateInfo()
        )
    }

    @Test
    fun `apk asset without download url is skipped`() {
        val info = githubRelease(
            assets = listOf(
                githubAsset(url = ""),
                githubAsset(url = "   "),
                githubAsset(name = "BLive-v1.0.2.apk", url = "https://example.com/BLive-v1.0.2.apk")
            )
        ).toUpdateInfo()
        assertNotNull(info)
        assertEquals("https://example.com/BLive-v1.0.2.apk", info!!.apkUrl)
    }

    // ---------------- JSON 反序列化 ----------------

    @Test
    fun `github dto deserializes from real api shaped json`() {
        val json = """
        {
          "tag_name": "v1.0.2",
          "name": "v1.0.2",
          "body": "## 更新内容\n- 新增应用内更新",
          "draft": false,
          "prerelease": false,
          "published_at": "2026-09-21T03:00:00Z",
          "assets": [
            {
              "name": "BLive-release-v1.0.2.apk",
              "size": 4718592,
              "browser_download_url": "https://github.com/Kola96/BLive/releases/download/v1.0.2/BLive-release-v1.0.2.apk"
            }
          ]
        }
        """.trimIndent()
        val dto = GsonHolder.gson.fromJson(json, GithubReleaseDto::class.java)
        val info = dto.toUpdateInfo()!!
        assertEquals("1.0.2", info.versionName)
        assertEquals("## 更新内容\n- 新增应用内更新", info.changelog)
        assertEquals(4718592L, info.apkSize)
    }

    @Test
    fun `gitee dto deserializes from real api shaped json`() {
        val json = """
        {
          "tag_name": "v1.0.2",
          "name": "v1.0.2",
          "body": "修复若干问题",
          "prerelease": false,
          "created_at": "2026-09-21T03:00:00Z",
          "assets": [
            {
              "name": "BLive-release-v1.0.2.apk",
              "browser_download_url": "https://gitee.com/Kola96/BLive/releases/download/v1.0.2/BLive-release-v1.0.2.apk"
            }
          ]
        }
        """.trimIndent()
        val dto = GsonHolder.gson.fromJson(json, GiteeReleaseDto::class.java)
        val info = dto.toUpdateInfo()!!
        assertEquals("1.0.2", info.versionName)
        // Gitee 可选 size 字段缺失 → null
        assertEquals(null, info.apkSize)
    }

    private object GsonHolder {
        val gson: com.google.gson.Gson = com.google.gson.GsonBuilder().create()
    }
}
