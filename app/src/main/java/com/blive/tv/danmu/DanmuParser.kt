package com.blive.tv.danmu

import android.util.Log
import com.google.gson.JsonParser
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 弹幕解析器，用于解析B站WebSocket返回的二进制数据
 */
class DanmuParser {
    private val TAG = "DanmuParser"
    private val jsonParser = JsonParser()

    /**
     * 解析WebSocket接收到的二进制数据
     */
    fun parseBinaryData(data: ByteArray): List<DanmuMessage> {
        val messages = mutableListOf<DanmuMessage>()
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        while (buffer.hasRemaining()) {
            // 解析包头
            val packetLen = buffer.getInt()
            val headerLen = buffer.getShort().toInt()
            val ver = buffer.getShort().toInt()
            val op = buffer.getInt()
            val seq = buffer.getInt()

            Log.d(TAG, "Packet header: packetLen=$packetLen, headerLen=$headerLen, ver=$ver, op=$op, seq=$seq")

            // 解析正文
            val bodyLen = packetLen - headerLen
            val body = ByteArray(bodyLen)
            buffer.get(body)

            // 根据操作类型处理
            when (op) {
                3 -> {
                    // 心跳回复
                    handleHeartbeat(body, messages)
                }
                5 -> {
                    // 弹幕消息，根据版本处理不同压缩类型
                    Log.d(TAG, "Business message with version $ver, body size: ${body.size} bytes")
                    when (ver) {
                        0, 1 -> {
                            // 原始JSON或未压缩消息，直接处理
                            handleBusinessMessage(body, messages)
                        }
                        2 -> {
                            // Deflate压缩
                            Log.d(TAG, "Processing Deflate compressed message")
                            try {
                                val decompressed = decompressZlib(body)
                                Log.d(TAG, "Decompressed to ${decompressed.size} bytes")
                                messages.addAll(parseBinaryData(decompressed))
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to decompress Deflate data", e)
                            }
                        }
                        3 -> {
                            // Brotli压缩
                            Log.d(TAG, "Processing Brotli compressed message")
                            try {
                                val decompressed = decompressBrotli(body)
                                Log.d(TAG, "Decompressed to ${decompressed.size} bytes")
                                messages.addAll(parseBinaryData(decompressed))
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to decompress Brotli data", e)
                            }
                        }
                        else -> {
                            Log.d(TAG, "Unknown protocol version for business message: $ver")
                        }
                    }
                }
                8 -> {
                    // 认证成功
                    handleAuthSuccess(body, messages)
                }
                else -> {
                    Log.d(TAG, "Unknown op type: $op")
                }
            }
        }

        return messages
    }

    /**
     * 解压缩zlib数据
     */
    private fun decompressZlib(data: ByteArray): ByteArray {
        val inflater = java.util.zip.Inflater()
        // 跳过zlib头(2字节)
        val dataToUse = if (data.size > 2) data.copyOfRange(2, data.size) else data
        inflater.setInput(dataToUse)
        val outputStream = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            outputStream.write(buffer, 0, count)
        }
        outputStream.close()
        inflater.end()
        return outputStream.toByteArray()
    }
    
    /**
     * 解压缩Brotli数据
     */
    private fun decompressBrotli(data: ByteArray): ByteArray {
        return try {
            // 使用Java的Brotli库解压缩
            val brotliInputStream = org.brotli.dec.BrotliInputStream(data.inputStream())
            brotliInputStream.use { input ->
                val outputStream = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.toByteArray()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decompress Brotli data", e)
            ByteArray(0)
        }
    }

    /**
     * 处理心跳包
     */
    private fun handleHeartbeat(body: ByteArray, messages: MutableList<DanmuMessage>) {
        val popularity = String(body, Charsets.UTF_8).toLongOrNull() ?: 0
        Log.d(TAG, "Heartbeat received, popularity: $popularity")
        // 心跳包不需要生成弹幕消息
    }

    /**
     * 处理业务消息（弹幕、礼物等）
     */
    private fun handleBusinessMessage(body: ByteArray, messages: MutableList<DanmuMessage>) {
        val jsonString = String(body, Charsets.UTF_8)
        Log.d(TAG, "Business message: $jsonString")

        // 处理多条JSON消息拼接的情况（以\x00分隔）
        val jsonParts = jsonString.split("\u0000")
        for (part in jsonParts) {
            if (part.isBlank()) continue
            try {
                val jsonElement = jsonParser.parse(part)
                if (jsonElement.isJsonObject) {
                    val jsonObject = jsonElement.asJsonObject
                    val cmd = jsonObject.get("cmd").asString
                    when (cmd) {
                        "DANMU_MSG" -> parseDanmuMessage(jsonObject, messages)
                        "SEND_GIFT" -> parseGiftMessage(jsonObject, messages)
                        "INTERACT_WORD" -> parseInteractMessage(jsonObject, messages)
                        else -> {
                            Log.d(TAG, "Unknown cmd: $cmd")
                            messages.add(DanmuMessage.Other(cmd, emptyMap()))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse business message: $part", e)
            }
        }
    }

    /**
     * 处理认证成功消息
     */
    private fun handleAuthSuccess(body: ByteArray, messages: MutableList<DanmuMessage>) {
        val jsonString = String(body, Charsets.UTF_8)
        Log.d(TAG, "Auth success: $jsonString")
        // 打印body的十六进制表示，以便查看详细内容
        Log.d(TAG, "Auth success body hex: ${body.joinToString(" ") { "%02X".format(it) }}")
        // 认证成功不需要生成弹幕消息
    }

    /**
     * 解析普通弹幕消息
     */
    private fun parseDanmuMessage(jsonObject: com.google.gson.JsonObject, messages: MutableList<DanmuMessage>) {
        try {
            val info = jsonObject.get("info")?.takeIf { !it.isJsonNull }?.asJsonArray ?: return
            
            // info[0] 包含弹幕基本信息，如颜色、模式、字体大小等
            val baseInfo = info.get(0)?.takeIf { !it.isJsonNull }?.asJsonArray
            val color = baseInfo?.optInt(3, 16777215) ?: 16777215
            val mode = baseInfo?.optInt(1, 1) ?: 1
            val fontSize = baseInfo?.optInt(2, 25) ?: 25
            
            // info[1] 是弹幕内容
            val content = info.optString(1, "")
            
            // info[2] 包含用户信息
            val userInfo = info.get(2)?.takeIf { !it.isJsonNull }?.asJsonArray
            val username = userInfo?.optString(1, "未知用户") ?: "未知用户"
            val userId = userInfo?.optLong(0, 0L) ?: 0L
            
            messages.add(
                DanmuMessage.Danmu(
                    userId = userId,
                    username = username,
                    content = content,
                    color = color,
                    mode = mode,
                    fontSize = fontSize
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse danmu message", e)
        }
    }

    /**
     * 解析礼物消息
     */
    private fun parseGiftMessage(jsonObject: com.google.gson.JsonObject, messages: MutableList<DanmuMessage>) {
        try {
            val data = jsonObject.get("data")?.takeIf { !it.isJsonNull }?.asJsonObject ?: return
            val userId = data.optLong("uid", 0L)
            val username = data.optString("uname", "未知用户")
            val giftName = data.optString("giftName", "未知礼物")
            val giftCount = data.optInt("num", 1)
            val giftPrice = data.optInt("price", 0)
            
            messages.add(
                DanmuMessage.Gift(
                    userId = userId,
                    username = username,
                    giftName = giftName,
                    giftCount = giftCount,
                    giftPrice = giftPrice
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse gift message", e)
        }
    }

    /**
     * 解析进入房间消息
     */
    private fun parseInteractMessage(jsonObject: com.google.gson.JsonObject, messages: MutableList<DanmuMessage>) {
        try {
            val data = jsonObject.get("data")?.takeIf { !it.isJsonNull }?.asJsonObject ?: return
            val userId = data.optLong("uid", 0L)
            val username = data.optString("uname", "未知用户")
            val isVip = data.optBoolean("is_vip", false)
            
            messages.add(
                DanmuMessage.EnterRoom(
                    userId = userId,
                    username = username,
                    isVip = isVip
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse interact message", e)
        }
    }
}

// ================= Extension Functions for Safe JSON Parsing =================

private fun com.google.gson.JsonObject.optString(memberName: String, fallback: String = ""): String {
    val element = get(memberName)
    return if (element != null && !element.isJsonNull) element.asString else fallback
}

private fun com.google.gson.JsonObject.optInt(memberName: String, fallback: Int = 0): Int {
    val element = get(memberName)
    return if (element != null && !element.isJsonNull) element.asInt else fallback
}

private fun com.google.gson.JsonObject.optLong(memberName: String, fallback: Long = 0L): Long {
    val element = get(memberName)
    return if (element != null && !element.isJsonNull) element.asLong else fallback
}

private fun com.google.gson.JsonObject.optBoolean(memberName: String, fallback: Boolean = false): Boolean {
    val element = get(memberName)
    return if (element != null && !element.isJsonNull) element.asBoolean else fallback
}

private fun com.google.gson.JsonArray.optString(index: Int, fallback: String = ""): String {
    if (index >= size()) return fallback
    val element = get(index)
    return if (element != null && !element.isJsonNull) element.asString else fallback
}

private fun com.google.gson.JsonArray.optInt(index: Int, fallback: Int = 0): Int {
    if (index >= size()) return fallback
    val element = get(index)
    return if (element != null && !element.isJsonNull) element.asInt else fallback
}

private fun com.google.gson.JsonArray.optLong(index: Int, fallback: Long = 0L): Long {
    if (index >= size()) return fallback
    val element = get(index)
    return if (element != null && !element.isJsonNull) element.asLong else fallback
}
