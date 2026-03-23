package com.blive.tv.network

object WebIdExtractor {
    private val accessIdRegex = Regex("\"access_id\"\\s*:\\s*\"([^\"]+)\"")

    fun extract(rawHtml: String): String {
        val match = accessIdRegex.find(rawHtml) ?: return ""
        return match.groupValues.getOrElse(1) { "" }
    }
}
