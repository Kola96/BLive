package com.blive.tv.network

import java.security.MessageDigest

object SignGenerator {
    // 可用的appkey和appsec
    private const val APP_KEY = "4409e2ce8ffd12b8"
    private const val APP_SEC = "59b43e04ad6965f34319062b478f83dd"

    // 生成签名
    fun generateSign(params: Map<String, String>): String {
        // 1. 将参数按key字典序排序
        val sortedParams = params.toSortedMap()
        
        // 2. 拼接参数，格式：key1=value1&key2=value2
        val sb = StringBuilder()
        for ((key, value) in sortedParams) {
            if (sb.isNotEmpty()) {
                sb.append("&")
            }
            sb.append(key).append("=").append(value)
        }
        
        // 3. 拼接appsec
        sb.append(APP_SEC)
        
        // 4. 生成MD5哈希
        return md5(sb.toString())
    }

    // 生成当前时间戳
    fun generateTimestamp(): Long {
        return System.currentTimeMillis() / 1000
    }

    // 获取appkey
    fun getAppKey(): String {
        return APP_KEY
    }

    // MD5哈希函数
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val hashBytes = md.digest(input.toByteArray())
        return hashBytes.joinToString("") {
            String.format("%02x", it)
        }
    }
}
