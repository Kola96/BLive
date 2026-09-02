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

    /** 标准数据：flv+ts 两种封装，avc+hevc 两种编码，原画/蓝光/流畅三档清晰度 */
    private fun standardData(): RoomPlayInfoData {
        val flvFormat = Format(
            formatName = "flv",
            codec = listOf(
                codec("avc", 10000, listOf(10000, 400, 80), "/avc_10000.flv"),
                codec("hevc", 10000, listOf(10000, 400), "/hevc_10000.flv"),
                codec("avc", 80, listOf(80), "/avc_80.flv")
            )
        )
        val tsFormat = Format(
            formatName = "ts",
            codec = listOf(
                codec("avc", 10000, listOf(10000, 400), "/avc_10000.ts"),
                codec("avc", 80, listOf(80), "/avc_80.ts")
            )
        )
        return data(listOf(Stream(protocolName = "http_stream", format = listOf(flvFormat, tsFormat))))
    }

    // ---------------- buildCapabilityGraph ----------------

    @Test
    fun `capability graph collects all capabilities and quality candidates`() {
        val graph = resolver.buildCapabilityGraph(standardData())
        assertEquals(5, graph.capabilities.size)
        assertEquals(setOf(10000, 400, 80), graph.qualityCandidates)
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
    fun `resolve exact qn with preferred codec`() {
        val graph = resolver.buildCapabilityGraph(standardData())
        val resolved = resolver.resolveSelection(graph, SelectionRequest(targetQn = 10000, preferredCodec = "hevc", currentCdnHost = ""))
        assertEquals(10000, resolved?.resolvedQn)
        assertEquals("hevc", resolved?.resolvedCodec)
        assertTrue(resolved?.url.orEmpty().contains("hevc_10000.flv"))
    }

    @Test
    fun `avc has priority when no preference given`() {
        val graph = resolver.buildCapabilityGraph(standardData())
        val resolved = resolver.resolveSelection(graph, SelectionRequest(targetQn = 10000, preferredCodec = "", currentCdnHost = ""))
        assertEquals("avc", resolved?.resolvedCodec)
        assertTrue(resolved?.url.orEmpty().endsWith("/avc_10000.flv?token=abc"))
    }

    @Test
    fun `flv format preferred over ts`() {
        val graph = resolver.buildCapabilityGraph(standardData())
        val resolved = resolver.resolveSelection(graph, SelectionRequest(targetQn = 80, preferredCodec = "avc", currentCdnHost = ""))
        assertTrue(resolved?.url.orEmpty().contains(".flv"))
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
    fun `buildAllUrls puts avc before hevc within same format, and flv before ts`() {
        val urls = resolver.buildAllUrls(standardData())
        assertTrue(urls.isNotEmpty())
        // 同一封装格式内，avc 排在 hevc 前
        val flvUrls = urls.filter { it.contains(".flv") }
        val firstHevcFlv = flvUrls.indexOfFirst { it.contains("hevc") }
        val lastAvcFlv = flvUrls.indexOfLast { it.contains("avc") }
        assertTrue("flv 内 avc 应排在 hevc 前", lastAvcFlv != -1 && firstHevcFlv != -1 && lastAvcFlv < firstHevcFlv)
        // flv 封装排在 ts 封装前
        val lastFlv = urls.indexOfLast { it.contains(".flv") }
        val firstTs = urls.indexOfFirst { it.contains(".ts") }
        assertTrue("flv 应排在 ts 前", lastFlv != -1 && firstTs != -1 && lastFlv < firstTs)
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
