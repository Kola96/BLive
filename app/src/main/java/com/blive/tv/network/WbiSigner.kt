package com.blive.tv.network

import java.net.URLEncoder
import java.security.MessageDigest

object WbiSigner {
    private val mixinKeyEncTab = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52
    )

    fun buildMixinKey(imgKey: String, subKey: String): String {
        val origin = imgKey + subKey
        val mixed = StringBuilder()
        for (index in mixinKeyEncTab) {
            if (index < origin.length) {
                mixed.append(origin[index])
            }
        }
        return mixed.toString().take(32)
    }

    fun sign(
        params: Map<String, String>,
        imgKey: String,
        subKey: String,
        timestampSeconds: Long = System.currentTimeMillis() / 1000
    ): Pair<String, String> {
        val filtered = params
            .mapValues { (_, value) -> value.filter { it !in "!'()*" } }
            .toMutableMap()
        val wts = timestampSeconds.toString()
        filtered["wts"] = wts
        val query = filtered.toSortedMap().entries.joinToString("&") {
            val encodedValue = URLEncoder.encode(it.value, "UTF-8").replace("+", "%20")
            "${it.key}=${encodedValue}"
        }
        val signInput = query + buildMixinKey(imgKey, subKey)
        val wRid = md5(signInput)
        return Pair(wRid, wts)
    }

    private fun md5(raw: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
