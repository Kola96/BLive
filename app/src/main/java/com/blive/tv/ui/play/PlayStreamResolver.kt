package com.blive.tv.ui.play

import com.blive.tv.data.model.RoomPlayInfoData

data class PlayOptionParseResult(
    val selectedQn: Int,
    val selectedCdnHost: String,
    val qualityOptions: List<QualityOption>,
    val cdnOptions: List<CdnOption>,
    val codecOptions: List<CodecOption>
)

class PlayStreamResolver {
    fun parseOptions(
        data: RoomPlayInfoData,
        preferredQn: Int,
        currentSelectedCdnHost: String,
        selectedCodec: String
    ): PlayOptionParseResult {
        val qualitySet = mutableSetOf<Int>()
        val cdnSet = mutableSetOf<String>()
        val codecSet = mutableSetOf<String>()

        for (stream in data.playurlInfo.playurl.stream) {
            for (format in stream.format) {
                for (codec in format.codec) {
                    codecSet.add(codec.codecName)
                    qualitySet.addAll(codec.acceptQn)
                    for (urlInfo in codec.urlInfo) {
                        val cdnName = urlInfo.host.substringAfter("://").substringBefore(".")
                        cdnSet.add(cdnName)
                    }
                }
            }
        }

        val bestQn = chooseBestQuality(qualitySet, preferredQn)
        val qualityOptions = qualitySet.map { qn ->
            QualityOption(qn, qualityDisplayName(qn), isSelected = qn == bestQn)
        }.sortedByDescending { it.qn }
        val cdnOptions = cdnSet.map { cdnName ->
            CdnOption(cdnName, cdnName, isSelected = cdnName == currentSelectedCdnHost)
        }
        val codecOptions = codecSet.map { codecName ->
            CodecOption(codecName, codecDisplayName(codecName), isSelected = codecName == selectedCodec)
        }
        val selectedCdnHost = if (currentSelectedCdnHost.isEmpty() && cdnOptions.isNotEmpty()) {
            cdnOptions.first().host
        } else {
            currentSelectedCdnHost
        }

        return PlayOptionParseResult(
            selectedQn = bestQn,
            selectedCdnHost = selectedCdnHost,
            qualityOptions = qualityOptions,
            cdnOptions = cdnOptions,
            codecOptions = codecOptions
        )
    }

    fun buildPreferredUrl(
        data: RoomPlayInfoData,
        selectedCodec: String,
        selectedQn: Int,
        selectedCdnHost: String
    ): String {
        val preferredProtocolList = listOf("http_stream", "http_hls")
        val preferredFormatList = listOf("flv", "ts", "fmp4")
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
        val preferredProtocolList = listOf("http_stream", "http_hls")
        val preferredFormatList = listOf("flv", "ts", "fmp4")
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
