package com.blive.tv.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRepositoryTest {

    private fun info(
        version: String,
        source: UpdateSource = UpdateSource.GITHUB
    ) = UpdateInfo(
        versionName = version,
        changelog = "",
        apkUrl = "https://example.com/app.apk",
        apkSize = 100L,
        publishedAt = "",
        source = source
    )

    private val githubFail = UpdateRepository.UpdateCheckException("GitHub 更新检查失败：超时")
    private val giteeFail = UpdateRepository.UpdateCheckException("Gitee 更新检查失败：403")

    // ---------------- selectAutoInfo（AUTO 双源选择，纯逻辑） ----------------

    @Test
    fun `both succeed picks newer version`() {
        val github = info("1.0.3", UpdateSource.GITHUB)
        val gitee = info("1.0.2", UpdateSource.GITEE)
        // Gitee 版本旧 → 用 GitHub
        assertEquals(github, UpdateRepository.selectAutoInfo(Result.success(github), Result.success(gitee)))

        val giteeNewer = info("1.0.4", UpdateSource.GITEE)
        // Gitee 版本新 → 用 Gitee
        assertEquals(
            giteeNewer,
            UpdateRepository.selectAutoInfo(Result.success(github), Result.success(giteeNewer))
        )
    }

    @Test
    fun `both succeed same version prefers github`() {
        // 版本一致 → isNewer(gitee, github)=false → 返回 GitHub
        val github = info("1.0.2", UpdateSource.GITHUB)
        val gitee = info("1.0.2", UpdateSource.GITEE)
        assertEquals(github, UpdateRepository.selectAutoInfo(Result.success(github), Result.success(gitee)))
    }

    @Test
    fun `github only success uses github`() {
        val github = info("1.0.2", UpdateSource.GITHUB)
        assertEquals(
            github,
            UpdateRepository.selectAutoInfo(Result.success(github), Result.failure(giteeFail))
        )
    }

    @Test
    fun `gitee only success uses gitee`() {
        val gitee = info("1.0.2", UpdateSource.GITEE)
        assertEquals(
            gitee,
            UpdateRepository.selectAutoInfo(Result.failure(githubFail), Result.success(gitee))
        )
    }

    @Test
    fun `both fail throws aggregated error`() {
        val e = runCatching {
            UpdateRepository.selectAutoInfo(Result.failure(githubFail), Result.failure(giteeFail))
        }.exceptionOrNull()
        assertTrue(e is UpdateRepository.UpdateCheckException)
        assertTrue(
            (e as UpdateRepository.UpdateCheckException).message!!.let {
                it.contains("GitHub 更新检查失败：超时") && it.contains("Gitee 更新检查失败：403")
            }
        )
    }

    @Test
    fun `newer gitee version beats github on version not speed`() {
        // 即使 GitHub 先返回（参数在前），版本号新的一方胜出
        val github = info("1.0.2", UpdateSource.GITHUB)
        val gitee = info("2.0.0", UpdateSource.GITEE)
        assertEquals(
            gitee,
            UpdateRepository.selectAutoInfo(Result.success(github), Result.success(gitee))
        )
    }
}
