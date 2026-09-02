package com.blive.tv.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WbiSigner 测试向量来自 bilibili 公开文档的算法描述，
 * 用独立参考实现（Python）交叉验证。
 */
class WbiSignerTest {

    @Test
    fun `buildMixinKey matches known vector`() {
        val imgKey = "7cd084941338484aae1ad9425b84077c"
        val subKey = "4932caff0ff746eab6f01bf08b70ac45"
        assertEquals(
            "ea1db124af3c7062474693fa704f4ff8",
            WbiSigner.buildMixinKey(imgKey, subKey)
        )
    }

    @Test
    fun `sign matches reference implementation`() {
        val imgKey = "7cd084941338484aae1ad9425b84077c"
        val subKey = "4932caff0ff746eab6f01bf08b70ac45"
        val params = mapOf(
            "bar" to "514",
            "baz" to "1919810",
            "foo" to "114"
        )
        val (wRid, wts) = WbiSigner.sign(params, imgKey, subKey, timestampSeconds = 1684746387L)
        assertEquals("1684746387", wts)
        // 与参考实现（Python hashlib.md5）交叉验证的结果
        assertEquals("cf8c26a9acd82f9e961cf282adb501a2", wRid)
    }

    @Test
    fun `sign filters special characters from values`() {
        val imgKey = "7cd084941338484aae1ad9425b84077c"
        val subKey = "4932caff0ff746eab6f01bf08b70ac45"
        // "!'()*" 会被过滤，因此 "a'b!c(d)e*f" 与 "abcdef" 签名应一致
        val withSpecial = WbiSigner.sign(
            mapOf("foo" to "a'b!c(d)e*f"), imgKey, subKey, timestampSeconds = 1684746387L
        )
        val withoutSpecial = WbiSigner.sign(
            mapOf("foo" to "abcdef"), imgKey, subKey, timestampSeconds = 1684746387L
        )
        assertEquals(withoutSpecial.first, withSpecial.first)
    }

    @Test
    fun `sign is deterministic for same input`() {
        val imgKey = "7cd084941338484aae1ad9425b84077c"
        val subKey = "4932caff0ff746eab6f01bf08b70ac45"
        val params = mapOf("id" to "123456", "type" to "0", "web_location" to "444.8")
        val first = WbiSigner.sign(params, imgKey, subKey, timestampSeconds = 1700000000L)
        val second = WbiSigner.sign(params, imgKey, subKey, timestampSeconds = 1700000000L)
        assertEquals(first, second)
    }

    @Test
    fun `buildMixinKey handles short input`() {
        // 密钥不足 64 字符时不应越界，按混淆表顺序挑出存在的字符
        assertEquals("cdfabe", WbiSigner.buildMixinKey("abc", "def"))
    }
}
