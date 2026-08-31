package com.blive.tv.ui.play

import com.blive.tv.data.model.RoomPlayInfoData

data class StreamCapability(
    val qn: Int,
    val codecName: String,
    val cdnHost: String,
    val protocolName: String,
    val formatName: String,
    val url: String
)

data class CapabilityGraph(
    val capabilities: List<StreamCapability>,
    val qualityCandidates: Set<Int>
)

data class SelectionRequest(
    val targetQn: Int,
    val preferredCodec: String,
    val currentCdnHost: String
)

data class ResolvedSelection(
    val requestedQn: Int,
    val resolvedQn: Int,
    val resolvedCodec: String,
    val resolvedCdnHost: String,
    val url: String
)

data class PanelOptions(
    val qualityOptions: List<QualityOption>,
    val codecOptions: List<CodecOption>,
    val cdnOptions: List<CdnOption>
)

data class PlayOptionParseResult(
    val selectedQn: Int,
    val selectedCdnHost: String,
    val qualityOptions: List<QualityOption>,
    val cdnOptions: List<CdnOption>,
    val codecOptions: List<CodecOption>
)

class PlayStreamResolver {
    private val preferredProtocolList = listOf("http_stream", "http_hls")
    private val preferredFormatList = listOf("flv", "ts", "fmp4")

    fun buildCapabilityGraph(data: RoomPlayInfoData): CapabilityGraph {
        val capabilities = mutableListOf<StreamCapability>()
        val qualityCandidates = mutableSetOf<Int>()
        for (stream in data.playurlInfo.playurl.stream) {
            for (format in stream.format) {
                for (codec in format.codec) {
                    qualityCandidates.add(codec.currentQn)
                    qualityCandidates.addAll(codec.acceptQn)
                    for (urlInfo in codec.urlInfo) {
                        val cdnName = extractCdnName(urlInfo.host)
                        val url = "${urlInfo.host.trim()}${codec.baseUrl}${urlInfo.extra}"
                        capabilities.add(
                            StreamCapability(
                                qn = codec.currentQn,
                                codecName = codec.codecName,
                                cdnHost = cdnName,
                                protocolName = stream.protocolName,
                                formatName = format.formatName,
                                url = url
                            )
                        )
                    }
                }
            }
        }
        return CapabilityGraph(
            capabilities = capabilities,
            qualityCandidates = qualityCandidates
        )
    }

    fun resolveSelection(
        graph: CapabilityGraph,
        request: SelectionRequest
    ): ResolvedSelection? {
        val qualityCapabilities = graph.capabilities.filter { it.qn == request.targetQn }
        if (qualityCapabilities.isEmpty()) {
            return null
        }

        val codecOrder = buildCodecPriority(
            availableCodec = qualityCapabilities.map { it.codecName }.toSet(),
            preferredCodec = request.preferredCodec
        )
        val cdnOrder = buildCdnPriority(
            availableCdn = qualityCapabilities.map { it.cdnHost }.toSet(),
            preferredCdn = request.currentCdnHost
        )

        for (codec in codecOrder) {
            val codecCapabilities = qualityCapabilities.filter { it.codecName == codec }
            for (cdn in cdnOrder) {
                val candidates = codecCapabilities.filter { it.cdnHost == cdn }
                val resolved = pickBestCandidate(candidates)
                if (resolved != null) {
                    return ResolvedSelection(
                        requestedQn = request.targetQn,
                        resolvedQn = resolved.qn,
                        resolvedCodec = resolved.codecName,
                        resolvedCdnHost = resolved.cdnHost,
                        url = resolved.url
                    )
                }
            }
        }
        return null
    }

    fun buildPanelOptions(
        graph: CapabilityGraph,
        selectedQn: Int,
        selectedCodec: String,
        selectedCdnHost: String
    ): PanelOptions {
        val qualitySet = graph.qualityCandidates
        val qualityOptions = qualitySet.map { qn ->
            QualityOption(qn, qualityDisplayName(qn), isSelected = qn == selectedQn)
        }.sortedByDescending { it.qn }

        val qnCapabilities = graph.capabilities.filter { it.qn == selectedQn }
        val codecSet = if (qnCapabilities.isNotEmpty()) {
            qnCapabilities.map { it.codecName }.toSet()
        } else {
            graph.capabilities.map { it.codecName }.toSet()
        }
        // 线路选项严格跟随当前清晰度，仅展示该清晰度可用线路
        val cdnSet = qnCapabilities.map { it.cdnHost }.toSet()

        val codecOptions = buildCodecPriority(codecSet, selectedCodec).map { codecName ->
            CodecOption(codecName, codecDisplayName(codecName), isSelected = codecName == selectedCodec)
        }
        val cdnOptions = buildCdnPriority(cdnSet, selectedCdnHost).map { cdnName ->
            CdnOption(cdnName, cdnName, isSelected = cdnName == selectedCdnHost)
        }

        return PanelOptions(
            qualityOptions = qualityOptions,
            codecOptions = codecOptions,
            cdnOptions = cdnOptions
        )
    }

    fun parseOptions(
        data: RoomPlayInfoData,
        preferredQn: Int,
        currentSelectedCdnHost: String,
        selectedCodec: String
    ): PlayOptionParseResult {
        val graph = buildCapabilityGraph(data)
        val qualitySet = graph.qualityCandidates
        val bestQn = chooseBestQuality(qualitySet, preferredQn)
        val panelOptions = buildPanelOptions(graph, bestQn, selectedCodec, currentSelectedCdnHost)
        val selectedCdnHost = if (currentSelectedCdnHost.isEmpty() && panelOptions.cdnOptions.isNotEmpty()) {
            panelOptions.cdnOptions.first().host
        } else {
            currentSelectedCdnHost
        }

        return PlayOptionParseResult(
            selectedQn = bestQn,
            selectedCdnHost = selectedCdnHost,
            qualityOptions = panelOptions.qualityOptions,
            cdnOptions = panelOptions.cdnOptions,
            codecOptions = panelOptions.codecOptions
        )
    }

    fun buildPreferredUrl(
        data: RoomPlayInfoData,
        selectedCodec: String,
        selectedQn: Int,
        selectedCdnHost: String
    ): String {
        for (protocol in preferredProtocolList) {
            for (format in preferredFormatList) {
                val url = findStreamUrl(data, protocol, format, selectedCodec, selectedQn, selectedCdnHost)
                if (url.isNotEmpty()) {
                    return url
                }
            }
        }
        return ""
    }

    fun buildAllUrls(data: RoomPlayInfoData): List<String> {
        val urlList = mutableListOf<String>()
        val preferredQnList = listOf(10000, 400, 250, 150, 80)
        val preferredCodecList = listOf("avc", "hevc")

        for (protocol in preferredProtocolList) {
            for (format in preferredFormatList) {
                for (codec in preferredCodecList) {
                    for (qn in preferredQnList) {
                        urlList.addAll(findStreamUrls(data, protocol, format, codec, qn))
                    }
                }
            }
        }
        return urlList
    }

    fun findStreamUrl(
        data: RoomPlayInfoData,
        targetProtocol: String,
        targetFormat: String,
        targetCodec: String,
        targetQn: Int,
        targetCdn: String
    ): String {
        for (stream in data.playurlInfo.playurl.stream) {
            if (stream.protocolName == targetProtocol) {
                for (format in stream.format) {
                    if (format.formatName == targetFormat) {
                        for (codec in format.codec) {
                            if (codec.codecName == targetCodec && codec.acceptQn.contains(targetQn)) {
                                for (urlInfo in codec.urlInfo) {
                                    val cdnName = urlInfo.host.substringAfter("://").substringBefore(".")
                                    if (targetCdn.isEmpty() || cdnName == targetCdn) {
                                        return "${urlInfo.host.trim()}${codec.baseUrl}${urlInfo.extra}"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return ""
    }

    private fun findStreamUrls(
        data: RoomPlayInfoData,
        targetProtocol: String,
        targetFormat: String,
        targetCodec: String,
        targetQn: Int
    ): List<String> {
        val urls = mutableListOf<String>()
        for (stream in data.playurlInfo.playurl.stream) {
            if (stream.protocolName == targetProtocol) {
                for (format in stream.format) {
                    if (format.formatName == targetFormat) {
                        for (codec in format.codec) {
                            if (codec.codecName == targetCodec && codec.acceptQn.contains(targetQn)) {
                                for (urlInfo in codec.urlInfo) {
                                    urls.add("${urlInfo.host.trim()}${codec.baseUrl}${urlInfo.extra}")
                                }
                            }
                        }
                    }
                }
            }
        }
        return urls
    }

    private fun chooseBestQuality(qualitySet: Set<Int>, preferredQn: Int): Int {
        if (qualitySet.isEmpty()) {
            return preferredQn
        }
        if (qualitySet.contains(preferredQn)) {
            return preferredQn
        }
        val maxQn = qualitySet.maxOrNull() ?: preferredQn
        val minQn = qualitySet.minOrNull() ?: preferredQn
        if (maxQn < preferredQn) {
            return maxQn
        }
        if (minQn > preferredQn) {
            return minQn
        }
        return qualitySet.filter { it < preferredQn }.maxOrNull() ?: maxQn
    }

    private fun pickBestCandidate(candidates: List<StreamCapability>): StreamCapability? {
        if (candidates.isEmpty()) {
            return null
        }
        for (protocol in preferredProtocolList) {
            for (format in preferredFormatList) {
                val matched = candidates.firstOrNull {
                    it.protocolName == protocol && it.formatName == format
                }
                if (matched != null) {
                    return matched
                }
            }
        }
        return candidates.firstOrNull()
    }

    private fun buildCodecPriority(availableCodec: Set<String>, preferredCodec: String): List<String> {
        if (availableCodec.isEmpty()) {
            return emptyList()
        }
        val result = mutableListOf<String>()
        if (availableCodec.contains("avc")) {
            result.add("avc")
        }
        if (preferredCodec.isNotEmpty() && availableCodec.contains(preferredCodec) && !result.contains(preferredCodec)) {
            result.add(preferredCodec)
        }
        result.addAll(availableCodec.filter { !result.contains(it) }.sorted())
        return result
    }

    private fun buildCdnPriority(availableCdn: Set<String>, preferredCdn: String): List<String> {
        if (availableCdn.isEmpty()) {
            return emptyList()
        }
        val result = mutableListOf<String>()
        if (preferredCdn.isNotEmpty() && availableCdn.contains(preferredCdn)) {
            result.add(preferredCdn)
        }
        result.addAll(availableCdn.filter { it !in result }.sorted())
        return result
    }

    private fun extractCdnName(host: String): String {
        return host.substringAfter("://").substringBefore(".")
    }

    private fun qualityDisplayName(qn: Int): String {
        return when (qn) {
            10000 -> "原画"
            400 -> "蓝光"
            250 -> "超清"
            150 -> "高清"
            80 -> "流畅"
            else -> "未知($qn)"
        }
    }

    private fun codecDisplayName(codecName: String): String {
        return when (codecName) {
            "avc" -> "H.264 (AVC)"
            "hevc" -> "H.265 (HEVC)"
            else -> codecName.uppercase()
        }
    }
}
