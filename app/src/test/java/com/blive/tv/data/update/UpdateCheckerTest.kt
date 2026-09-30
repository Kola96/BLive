package com.blive.tv.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private val interval = 24 * 60 * 60 * 1000L // 与实现中的 PROMPT_INTERVAL_MS 一致

    private fun remote(version: String) = UpdateInfo(
        versionName = version,
        changelog = "",
        apkUrl = "https://example.com/app.apk",
        apkSize = 100L,
        publishedAt = "",
        source = UpdateSource.GITEE
    )

    // ---------------- shouldAutoPrompt（纯逻辑重载） ----------------

    @Test
    fun `prompts when no history at all`() {
        assertTrue(
            UpdateChecker.shouldAutoPrompt(
                remoteVersion = "1.0.2",
                ignoredVersion = null,
                lastPromptedVersion = null,
                lastPromptTime = 0L,
                now = 1_000_000L
            )
        )
    }

    @Test
    fun `ignored version is never prompted`() {
        assertFalse(
            UpdateChecker.shouldAutoPrompt(
                remoteVersion = "1.0.2",
                ignoredVersion = "1.0.2",
                lastPromptedVersion = null,
                lastPromptTime = 0L,
                now = Long.MAX_VALUE / 2
            )
        )
    }

    @Test
    fun `same version within 24h is not prompted again`() {
        val promptTime = 1_000_000L
        assertFalse(
            UpdateChecker.shouldAutoPrompt(
                remoteVersion = "1.0.2",
                ignoredVersion = null,
                lastPromptedVersion = "1.0.2",
                lastPromptTime = promptTime,
                now = promptTime + interval - 1
            )
        )
        // 恰好 24h → 应再次提示
        assertTrue(
            UpdateChecker.shouldAutoPrompt(
                remoteVersion = "1.0.2",
                ignoredVersion = null,
                lastPromptedVersion = "1.0.2",
                lastPromptTime = promptTime,
                now = promptTime + interval
            )
        )
    }

    @Test
    fun `different version within 24h is still prompted`() {
        val promptTime = 1_000_000L
        assertTrue(
            UpdateChecker.shouldAutoPrompt(
                remoteVersion = "1.0.3",
                ignoredVersion = null,
                lastPromptedVersion = "1.0.2",
                lastPromptTime = promptTime,
                now = promptTime + 1_000L
            )
        )
    }

    @Test
    fun `ignore wins over prompt history`() {
        // 已忽略 1.0.3，即使从未提示过它也不再弹
        assertFalse(
            UpdateChecker.shouldAutoPrompt(
                remoteVersion = "1.0.3",
                ignoredVersion = "1.0.3",
                lastPromptedVersion = "1.0.2",
                lastPromptTime = 1_000_000L,
                now = 1_001_000L
            )
        )
    }

    @Test
    fun `clock skew protection - future prompt time still suppresses`() {
        // lastPromptTime 在未来（时钟回拨场景）：now - lastPromptTime 为负 < interval → 不弹
        assertFalse(
            UpdateChecker.shouldAutoPrompt(
                remoteVersion = "1.0.2",
                ignoredVersion = null,
                lastPromptedVersion = "1.0.2",
                lastPromptTime = 2_000_000L,
                now = 1_000_000L
            )
        )
    }

    // ---------------- source mode 序列化兼容 ----------------

    @Test
    fun `source mode round trip via valueOf`() {
        // UpdateChecker 持久化存的是 enum.name，读取用 valueOf + 兜底 AUTO。
        // 这里验证三个枚举值的 name 可稳定解析回自身（防止重命名导致存量配置失效）。
        for (mode in UpdateRepository.SourceMode.entries) {
            assertEquals(mode, UpdateRepository.SourceMode.valueOf(mode.name))
        }
    }
}
