package com.blive.tv.network

object WbiKeyParser {
    fun parseFromUrl(url: String): String {
        if (url.isEmpty()) {
            return ""
        }
        return url.substringAfterLast("/").substringBefore(".")
    }
}
