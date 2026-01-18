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
            val info = jsonObject.get("info").asJsonArray
            
            // info[0] 包含弹幕基本信息，如颜色、模式、字体大小等
            val baseInfo = info[0].asJsonArray
            val color = baseInfo[3].asInt
            val mode = baseInfo[1].asInt
            val fontSize = baseInfo[2].asInt
            
            // info[1] 是弹幕内容
            val content = info[1].asString
            
            // info[2] 包含用户信息
            val userInfo = info[2].asJsonArray
            val username = userInfo[1].asString
            val userId = userInfo[0].asLong
            
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
            val data = jsonObject.get("data").asJsonObject
            val userId = data.get("uid").asLong
            val username = data.get("uname").asString
            val giftName = data.get("giftName").asString
            val giftCount = data.get("num").asInt
            val giftPrice = data.get("price").asInt
            
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
            val data = jsonObject.get("data").asJsonObject
            val userId = data.get("uid").asLong
            val username = data.get("uname").asString
            val isVip = data.get("is_vip").asBoolean
            
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
