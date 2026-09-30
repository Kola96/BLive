package com.blive.tv.ui.play

import com.blive.tv.data.model.AudioCodecs
import com.blive.tv.data.model.Codec
import com.blive.tv.data.model.Format
import com.blive.tv.data.model.PlayUrl
import com.blive.tv.data.model.PlayUrlInfo
import com.blive.tv.data.model.RoomPlayInfoData
import com.blive.tv.data.model.Stream
import com.blive.tv.data.model.UrlInfo
import com.blive.tv.data.model.VideoCodecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayStreamResolverTest {

    private val resolver = PlayStreamResolver()

    private fun codec(
        name: String,
        qn: Int,
        acceptQn: List<Int> = listOf(qn),
        baseUrl: String = "/live_123.flv",
        hosts: List<String> = listOf("https://cn-hb-cu-01-01.bilivideo.com")
    ) = Codec(
        codecName = name,
        currentQn = qn,
        acceptQn = acceptQn,
        baseUrl = baseUrl,
        urlInfo = hosts.map { UrlInfo(host = it, extra = "?token=abc", streamTtl = 3600) },
        hdrQn = null,
        dolbyType = 0,
        attrName = "",
        hdrType = 0,
        drm = false,
        drmKeySystems = null,
        videoCodecs = VideoCodecs(name),
        audioCodecs = AudioCodecs("aac")
    )

    private fun data(
        streams: List<Stream>
    ) = RoomPlayInfoData(
        roomId = 123,
        shortId = 123,
        uid = 456L,
        isHidden = false,
        isLocked = false,
        isPortrait = false,
        liveStatus = 1,
        hiddenTill = 0,
        lockTill = 0,
        encrypted = false,
        pwdVerified = false,
        liveTime = 0,
        roomShield = 0,
        allSpecialTypes = emptyList(),
        playurlInfo = PlayUrlInfo(confJson = "{}", playurl = PlayUrl(cid = 123, gQnDesc = emptyList(), stream = streams))
    )

    /**
     * 贴近真实响应的数据：
     * - http_stream/flv：avc + hevc（hevc+flv 为增强RTMP封装，ExoPlayer 不支持）
     * - http_hls/ts + http_hls/fmp4：avc + hevc
     */
    private fun standardData(): RoomPlayInfoData {
        val httpStream = Stream(
            protocolName = "http_stream",
            format = listOf(
                Format(
                    formatName = "flv",
                    codec = listOf(
                        codec("avc", 10000, listOf(10000, 400, 80), "/avc_10000.flv"),
                        codec("hevc", 10000, listOf(10000, 400), "/hevc_10000.flv"),
                        codec("avc", 80, listOf(80), "/avc_80.flv")
                    )
                )
            )
        )
        val httpHls = Stream(
            protocolName = "http_hls",
            format = listOf(
                Format(
                    formatName = "ts",
                    codec = listOf(
                        codec("avc", 10000, listOf(10000, 400), "/avc_10000.m3u8"),
                        codec("hevc", 10000, listOf(10000, 400), "/hevc_10000.m3u8")
                    )
                ),
                Format(
                    formatName = "fmp4",
                    codec = listOf(
                        codec("avc", 10000, listOf(10000), "/avc_fmp4/index.m3u8"),
                        codec("hevc", 10000, listOf(10000), "/hevc_fmp4/index.m3u8")
                    )
                )
            )
        )
        return data(listOf(httpStream, httpHls))
    }

    // ---------------- buildCapabilityGraph ----------------

    @Test
    fun `capability graph collects playable capabilities and quality candidates`() {
        val graph = resolver.buildCapabilityGraph(standardData())
        // flv: avc10000 + avc80（hevc+flv 被过滤）, ts: avc + hevc, fmp4: avc + hevc
        assertEquals(6, graph.capabilities.size)
        assertEquals(setOf(10000, 400, 80), graph.qualityCandidates)
    }

    @Test
    fun `hevc flv capabilities are excluded as unplayable`() {
        val graph = resolver.buildCapabilityGraph(standardData())
        assertTrue(graph.capabilities.none { it.codecName == "hevc" && it.formatName == "flv" })
        assertTrue(graph.capabilities.any { it.codecName == "hevc" && it.formatName == "ts" })
    }

    @Test
    fun `capability url is host plus baseUrl plus extra`() {
        val graph = resolver.buildCapabilityGraph(standardData())
        val avc = graph.capabilities.first { it.codecName == "avc" && it.formatName == "flv" && it.qn == 10000 }
        assertEquals("https://cn-hb-cu-01-01.bilivideo.com/avc_10000.flv?token=abc", avc.url)
        assertEquals("cn-hb-cu-01-01", avc.cdnHost)
    }

    // ---------------- resolveSelection ----------------

    @Test
    fun `resolve hevc preference picks hls stream instead of flv`() {
        val graph = resolver.buildCapabilityGraph(standardData())
        val resolved = resolver.resolveSelection(graph, SelectionRequest(targetQn = 10000, preferredCodec = "hevc", currentCdnHost = ""))
        assertEquals("hevc", resolved?.resolvedCodec)
        // ExoPlayer 不支持 hevc+flv，必须落到 HLS
        assertTrue(resolved?.url.orEmpty().contains("hevc_10000.m3u8"))
        assertFalse(resolved?.url.orEmpty().contains(".flv"))
    }

    @Test
    fun `avc has priority when no preference given`() {
        val graph = resolver.buildCapabilityGraph(standardData())
        val resolved = resolver.resolveSelection(graph, SelectionRequest(targetQn = 10000, preferredCodec = "", currentCdnHost = ""))
        assertEquals("avc", resolved?.resolvedCodec)
        assertTrue(resolved?.url.orEmpty().endsWith("/avc_10000.flv?token=abc"))
    }

    @Test
    fun `unavailable qn returns null`() {
        val graph = resolver.buildCapabilityGraph(standardData())
        val resolved = resolver.resolveSelection(graph, SelectionRequest(targetQn = 250, preferredCodec = "avc", currentCdnHost = ""))
        assertNull(resolved)
    }

    @Test
    fun `unknown preferred cdn falls back to available`() {
        val graph = resolver.buildCapabilityGraph(standardData())
        val resolved = resolver.resolveSelection(graph, SelectionRequest(targetQn = 10000, preferredCodec = "avc", currentCdnHost = "not-exist-cdn"))
        assertEquals("cn-hb-cu-01-01", resolved?.resolvedCdnHost)
    }

    // ---------------- buildPanelOptions ----------------

    @Test
    fun `quality options sorted descending with selection marked`() {
        val graph = resolver.buildCapabilityGraph(standardData())
        val panel = resolver.buildPanelOptions(graph, selectedQn = 400, selectedCodec = "avc", selectedCdnHost = "")
        assertEquals(listOf(10000, 400, 80), panel.qualityOptions.map { it.qn })
        assertEquals("原画", panel.qualityOptions[0].name)
        assertEquals("蓝光", panel.qualityOptions[1].name)
        assertTrue(panel.qualityOptions[1].isSelected)
    }

    @Test
    fun `cdn options follow selected qn availability`() {
        val graph = resolver.buildCapabilityGraph(standardData())
        val panel = resolver.buildPanelOptions(graph, selectedQn = 80, selectedCodec = "avc", selectedCdnHost = "")
        // 80 清晰度只有 avc，编码选项应只含 avc
        assertEquals(listOf("avc"), panel.codecOptions.map { it.codecName })
        assertEquals(1, panel.cdnOptions.size)
    }

    // ---------------- buildAllUrls / findStreamUrl ----------------

    @Test
    fun `buildAllUrls puts flv before hls and contains no hevc flv`() {
        val urls = resolver.buildAllUrls(standardData())
        assertTrue(urls.isNotEmpty())
        // hevc+flv 不可播放，不应出现在 fallback 列表
        assertTrue(urls.none { it.contains("hevc") && it.contains(".flv") })
        // flv 排在 hls 前
        val lastFlv = urls.indexOfLast { it.contains(".flv") }
        val firstM3u8 = urls.indexOfFirst { it.contains(".m3u8") }
        assertTrue("flv 应排在 hls 前", lastFlv != -1 && firstM3u8 != -1 && lastFlv < firstM3u8)
    }

    @Test
    fun `findStreamUrl matches acceptQn and cdn`() {
        val data = standardData()
        val url = resolver.findStreamUrl(data, "http_stream", "flv", "avc", 400, "cn-hb-cu-01-01")
        assertTrue(url.contains("/avc_10000.flv"))
        // 不匹配的 CDN 返回空
        val noMatch = resolver.findStreamUrl(data, "http_stream", "flv", "avc", 400, "other-cdn")
        assertTrue(noMatch.isEmpty())
        // 不在 acceptQn 中的清晰度返回空
        val noQn = resolver.findStreamUrl(data, "http_stream", "flv", "avc", 250, "")
        assertTrue(noQn.isEmpty())
        // hevc+flv 不可播放，即使精确匹配也返回空
        val hevcFlv = resolver.findStreamUrl(data, "http_stream", "flv", "hevc", 10000, "")
        assertTrue(hevcFlv.isEmpty())
    }

    @Test
    fun `empty stream yields empty graph and null selection`() {
        val emptyData = data(emptyList())
        val graph = resolver.buildCapabilityGraph(emptyData)
        assertTrue(graph.capabilities.isEmpty())
        assertNull(resolver.resolveSelection(graph, SelectionRequest(10000, "avc", "")))
        assertTrue(resolver.buildAllUrls(emptyData).isEmpty())
    }
}
