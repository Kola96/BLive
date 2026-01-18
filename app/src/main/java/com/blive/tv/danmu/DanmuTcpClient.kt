package com.blive.tv.danmu

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64
import java.util.Date
import kotlin.random.Random

/**
 * B站弹幕TCP客户端
 */
class DanmuTcpClient(
    private val roomId: Long,
    private val onDanmuReceived: (List<DanmuMessage>) -> Unit,
    private val onConnectionStatusChanged: (Boolean) -> Unit,
    private val onLog: ((String) -> Unit)? = null
) {
    private val TAG = "DanmuTcpClient"
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val danmuParser = DanmuParser()
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var isConnected = false
    private var buvid: String? = null
    private var token: String? = null
    private var serverHost: String = "broadcastlv.chat.bilibili.com"
    private var serverPort: Int = 2243
    private var heartbeatJob: Job? = null
    private var receiveJob: Job? = null

    private fun log(message: String) {
        Log.d(TAG, message)
        onLog?.invoke(message)
    }

    private fun logError(message: String, e: Throwable? = null) {
        if (e != null) {
            Log.e(TAG, message, e)
            onLog?.invoke("Error: $message - ${e.message}")
        } else {
            Log.e(TAG, message)
            onLog?.invoke("Error: $message")
        }
    }

    companion object {
    }

    /**
     * 启动弹幕客户端
     */
    fun start() {
        log("start()方法被调用，开始启动弹幕客户端")
        
        // 直接在IO线程中执行，不使用CoroutineScope，避免协程相关问题
        Thread {
            try {
                log("启动线程执行弹幕客户端连接流程")
                
                // 获取BUVid
                log("准备获取BUVid")
                if (!getBuvid()) {
                    logError("获取BUVid失败")
                    onConnectionStatusChanged(false)
                    return@Thread
                }

                // 获取房间信息和Token
                log("准备获取房间信息和Token")
                if (!getRoomInfo()) {
                    logError("获取房间信息失败")
                    onConnectionStatusChanged(false)
                    return@Thread
                }

                // 连接弹幕服务器
                log("准备连接弹幕服务器")
                if (!connect()) {
                    logError("连接弹幕服务器失败")
                    onConnectionStatusChanged(false)
                    return@Thread
                }

                // 发送认证包
                log("准备发送认证包")
                sendAuthPacket()

                // 启动心跳
                log("准备启动心跳")
                startHeartbeat()

                // 开始接收消息
                log("准备开始接收消息")
                startReceiveLoop()

                onConnectionStatusChanged(true)
                isConnected = true
            } catch (e: Exception) {
                logError("启动弹幕客户端失败", e)
                onConnectionStatusChanged(false)
                isConnected = false
            }
        }.start()
    }

    /**
     * 停止弹幕客户端
     */
    fun stop() {
        scope.cancel()
        heartbeatJob?.cancel()
        receiveJob?.cancel()
        disconnect()
        onConnectionStatusChanged(false)
        isConnected = false
    }

    /**
     * 获取BUVid
     */
    private fun getBuvid(): Boolean {
        Log.d(TAG, "Step 1: 获取BUVid")
        try {
            val url = "https://api.bilibili.com/x/frontend/finger/spi"
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "zh-CN,zh;q=0.9",
                "Origin" to "https://live.bilibili.com",
                "Referer" to "https://live.bilibili.com/$roomId"
            )

            val response = HttpRequest.get(url, headers)
            val responseJson = org.json.JSONObject(response)
            if (responseJson.getInt("code") == 0) {
                buvid = responseJson.getJSONObject("data").getString("b_3")
                Log.d(TAG, "获取到BUVid: $buvid")
                return true
            } else {
                Log.e(TAG, "获取BUVid失败: ${responseJson.getString("message")}")
                // 失败时生成随机BUVid
                buvid = "XY${(1..18).map { ('0'..'f').random() }.joinToString("")}infoc"
                Log.d(TAG, "生成随机BUVid: $buvid")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取BUVid异常", e)
            // 异常时生成随机BUVid
            buvid = "XY0123456789abcdefinfoc"
            Log.d(TAG, "使用默认BUVid: $buvid")
            return true
        }
    }

    /**
     * 获取房间信息和Token
     */
    private fun getRoomInfo(): Boolean {
        Log.d(TAG, "Step 2: 获取房间 $roomId 的信息和Token")
        try {
            // 获取WBI密钥
            val (imgKey, subKey) = getWbiKeys()
            Log.d(TAG, "获取到wbi keys: img_key=$imgKey, sub_key=$subKey")

            // 构建请求参数
            val params = mutableMapOf(
                "id" to roomId.toString(),
                "type" to "0",
                "web_location" to "444.8"
            )

            // 生成WBI签名
            val signedParams = encWbi(params, imgKey, subKey)
            Log.d(TAG, "生成签名参数: $signedParams")

            // 构建请求URL
            val url = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo"
            val queryString = signedParams.entries.joinToString("&") { "${it.key}=${it.value}" }
            val fullUrl = "$url?$queryString"
            Log.d(TAG, "完整请求URL: $fullUrl")

            // 构建请求头
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "zh-CN,zh;q=0.9",
                "Origin" to "https://live.bilibili.com",
                "Referer" to "https://live.bilibili.com/$roomId",
                "Cookie" to "LIVE_BUVID=$buvid"
            )
            Log.d(TAG, "请求头: $headers")

            Log.d(TAG, "调用API: $fullUrl")
            val response = HttpRequest.get(fullUrl, headers)
            Log.d(TAG, "API响应: $response")
            val responseJson = org.json.JSONObject(response)
            Log.d(TAG, "响应code: ${responseJson.getInt("code")}, message: ${responseJson.optString("message", "无消息")}")

            if (responseJson.getInt("code") == 0) {
                val data = responseJson.getJSONObject("data")
                token = data.getString("token")
                Log.d(TAG, "获取到Token: $token")

                // 获取服务器信息
                val hostList = data.getJSONArray("host_list")
                Log.d(TAG, "服务器列表数量: ${hostList.length()}")
                if (hostList.length() > 0) {
                    val hostInfo = hostList.getJSONObject(0)
                    serverHost = hostInfo.getString("host")
                    serverPort = hostInfo.getInt("port")
                    Log.d(TAG, "获取到服务器信息: host=$serverHost, port=$serverPort")
                }

                return true
            } else {
                Log.e(TAG, "获取房间信息失败: code=${responseJson.getInt("code")}, message=${responseJson.optString("message", "无消息")}")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取房间信息异常", e)
            return false
        }
    }

    /**
     * 获取WBI密钥
     */
    private fun getWbiKeys(): Pair<String, String> {
        try {
            val url = "https://api.bilibili.com/x/web-interface/nav"
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to "zh-CN,zh;q=0.9",
                "Referer" to "https://www.bilibili.com/"
            )

            Log.d(TAG, "开始获取WBI密钥，URL: $url")
            // Log.d(TAG, "WBI密钥请求头: $headers")
            val response = HttpRequest.get(url, headers)
            Log.d(TAG, "WBI密钥API响应完整内容: $response")
            
            val responseJson = org.json.JSONObject(response)
            // Log.d(TAG, "WBI密钥响应code: ${responseJson.getInt("code")}, message: ${responseJson.optString("message", "无消息")}")
            
            // if (responseJson.getInt("code") != 0) {
            //     Log.e(TAG, "获取WBI密钥失败: ${responseJson.optString("message", "无消息")}")
            //     return Pair("", "")
            // }
            
            val data = responseJson.getJSONObject("data")
            val wbiImg = data.getJSONObject("wbi_img")
            val imgUrl = wbiImg.getString("img_url")
            val subUrl = wbiImg.getString("sub_url")
            Log.d(TAG, "获取到WBI图片URL: img_url=$imgUrl, sub_url=$subUrl")

            val imgKey = imgUrl.substringAfterLast("/").substringBefore(".")
            val subKey = subUrl.substringAfterLast("/").substringBefore(".")
            Log.d(TAG, "解析得到WBI密钥: imgKey=$imgKey, subKey=$subKey")

            return Pair(imgKey, subKey)
        } catch (e: Exception) {
            Log.e(TAG, "获取WBI密钥异常，异常类型: ${e.javaClass.simpleName}", e)
            // 使用默认值，避免崩溃
            return Pair("", "")
        }
    }

    /**
     * 生成WBI签名
     */
    private fun encWbi(params: MutableMap<String, String>, imgKey: String, subKey: String): Map<String, String> {
        Log.d(TAG, "开始生成WBI签名")
        Log.d(TAG, "原始参数: $params")
        Log.d(TAG, "WBI密钥: imgKey=$imgKey, subKey=$subKey")
        
        // 添加时间戳
        val wts = System.currentTimeMillis() / 1000
        params["wts"] = wts.toString()
        Log.d(TAG, "添加时间戳wts: $wts")

        // 按照key排序
        val sortedParams = params.toSortedMap()
        Log.d(TAG, "排序后的参数: $sortedParams")

        // 过滤value中的特殊字符
        val filteredParams = sortedParams.mapValues { (_, v) ->
            v.filter { it !in "!'()*" }
        }
        Log.d(TAG, "过滤特殊字符后的参数: $filteredParams")

        // 构建查询字符串，使用URL编码
        val queryString = filteredParams.entries.joinToString("&") { (key, value) ->
            val encodedKey = java.net.URLEncoder.encode(key, "UTF-8")
            val encodedValue = java.net.URLEncoder.encode(value, "UTF-8")
            "$encodedKey=$encodedValue"
        }
        Log.d(TAG, "最终查询字符串: $queryString")

        // 生成签名
        val origStr = imgKey + subKey
        Log.d(TAG, "生成mixinKey的原始字符串: $origStr")
        val mixinKey = getMixinKey(origStr)
        Log.d(TAG, "生成的mixinKey: $mixinKey")
        
        val signInput = queryString + mixinKey
        Log.d(TAG, "生成WBI签名的输入: $signInput")
        val wbiSign = md5(signInput)
        Log.d(TAG, "生成的WBI签名(w_rid): $wbiSign")

        // 添加签名到参数
        val result = filteredParams.toMutableMap()
        result["w_rid"] = wbiSign
        Log.d(TAG, "最终签名参数: $result")

        return result
    }

    /**
     * 对imgKey和subKey进行字符顺序打乱编码
     */
    private fun getMixinKey(orig: String): String {
        Log.d(TAG, "进入getMixinKey，原始字符串: '$orig'，长度: ${orig.length}")
        
        val mixinKeyEncTab = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
        )

        // 处理orig为空字符串的情况
        if (orig.isEmpty()) {
            Log.d(TAG, "orig字符串为空，直接返回空字符串")
            return ""
        }

        val mappedChars = mixinKeyEncTab.take(32).mapIndexedNotNull { tabIndex, index ->
            val char = if (index < orig.length) {
                orig[index]
            } else {
                null
            }
            Log.d(TAG, "mixinKeyEncTab[$tabIndex] = $index, 对应orig字符: $char")
            char
        }
        
        val result = mappedChars.joinToString("")
        Log.d(TAG, "生成的mixinKey: '$result'，长度: ${result.length}")
        return result
    }

    /**
     * MD5加密
     */
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 连接弹幕服务器
     */
    private fun connect(): Boolean {
        log("Step 3: 连接弹幕服务器 $serverHost:$serverPort")
        try {
            socket = Socket()
            // 连接超时由connect方法的参数控制
            socket?.connect(InetSocketAddress(serverHost, serverPort), 5000)
            // 连接成功后，设置读取超时为60秒（心跳间隔是30秒，给足余量，防止没有弹幕时误判断开）
            socket?.soTimeout = 60000
            inputStream = socket?.getInputStream()
            outputStream = socket?.getOutputStream()
            log("连接弹幕服务器成功")
            return true
        } catch (e: SocketTimeoutException) {
            logError("连接弹幕服务器超时", e)
            disconnect()
            return false
        } catch (e: Exception) {
            logError("连接弹幕服务器异常", e)
            disconnect()
            return false
        }
    }

    /**
     * 断开连接
     */
    private fun disconnect() {
        try {
            heartbeatJob?.cancel()
            receiveJob?.cancel()
            inputStream?.close()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "断开连接异常", e)
        } finally {
            inputStream = null
            outputStream = null
            socket = null
            isConnected = false
        }
    }

    /**
     * 发送认证包
     */
    private fun sendAuthPacket() {
        Log.e(TAG, "Step 4: 发送认证包")
        try {
            // 使用正确的协议版本1，认证请求中的protover字段才是3
            val authJson = buildAuthJson()
            Log.e(TAG, "认证包JSON: $authJson")
            // 根据Python代码和日志，认证包的action应该是7
            sendPacket(7, authJson.toByteArray(), 1)
            Log.e(TAG, "认证包发送成功")
        } catch (e: Exception) {
            Log.e(TAG, "发送认证包异常", e)
        }
    }

    /**
     * 构建认证JSON
     */
    private fun buildAuthJson(): String {
        // 按照字母顺序排序，与Python代码保持一致
        return "{\"buvid\":\"$buvid\",\"key\":\"$token\",\"platform\":\"danmuji\",\"protover\":3,\"roomid\":$roomId,\"type\":2,\"uid\":0}"
    }

    /**
     * 发送数据包
     */
    private fun sendPacket(action: Int, body: ByteArray, version: Short = 1) {
        val packetLength = 16 + body.size
        val buffer = ByteBuffer.allocate(packetLength).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(packetLength)
            .putShort(16)
            .putShort(version)
            .putInt(action)
            .putInt(1)
            .put(body)
        outputStream?.write(buffer.array())
        outputStream?.flush()
    }

    /**
     * 开始心跳
     */
    private fun startHeartbeat() {
        log("Step 5: 开始心跳")
        heartbeatJob = scope.launch {
            while (isActive) {
                try {
                    // 心跳包操作码应该是2，内容通常为"[object Object]"
                    sendPacket(2, "[object Object]".toByteArray())
                    log("发送心跳包")
                    delay(30000) // 30秒发送一次心跳
                } catch (e: Exception) {
                    logError("发送心跳包异常", e)
                    break
                }
            }
        }
    }

    /**
     * 开始接收消息循环
     */
    private fun startReceiveLoop() {
        log("Step 6: 开始接收消息")
        receiveJob = scope.launch {
            try {
                log("接收消息循环开始运行")
                while (isActive) {
                    log("准备读取数据包")
                    // 读取数据包，readPacket()现在会抛出异常而不是返回null
                    val packet = readPacket()
                    log("成功读取到一个数据包，长度: ${packet.size} 字节")
                    // 处理数据包
                    handlePacket(packet)
                }
            } catch (e: SocketTimeoutException) {
                logError("接收消息超时，连接可能已关闭", e)
                onConnectionStatusChanged(false)
                disconnect()
            } catch (e: Exception) {
                logError("接收消息异常", e)
                onConnectionStatusChanged(false)
                disconnect()
            }
        }
    }

    /**
     * 读取数据包
     */
    private fun readPacket(): ByteArray {
        val input = inputStream ?: run {
            Log.e(TAG, "inputStream为null，无法读取数据包")
            throw IllegalStateException("inputStream is null")
        }
        val header = ByteArray(16)
        
        Log.d(TAG, "开始读取数据包包头，期望读取16字节")
        // 读取包头
        val headerRead = input.read(header)
        Log.d(TAG, "实际读取到的包头字节数: $headerRead")
        if (headerRead == -1) {
            Log.e(TAG, "读取包头失败，连接已关闭")
            throw SocketTimeoutException("Connection closed by server")
        }
        if (headerRead != 16) {
            Log.e(TAG, "读取包头失败，实际读取了$headerRead 字节，期望16字节")
            throw IllegalStateException("Failed to read header")
        }

        // 解析包头
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val packetLength = buffer.getInt()
        val headerLength = buffer.getShort().toInt()
        val version = buffer.getShort()
        val action = buffer.getInt()
        val param = buffer.getInt()
        val bodyLength = packetLength - 16
        
        Log.d(TAG, "解析包头成功: packetLength=$packetLength, headerLength=$headerLength, version=$version, action=$action, param=$param, bodyLength=$bodyLength")

        // 读取包体
        val body = ByteArray(bodyLength)
        var totalRead = 0
        Log.d(TAG, "开始读取包体，期望读取$bodyLength 字节")
        while (totalRead < bodyLength) {
            val read = input.read(body, totalRead, bodyLength - totalRead)
            if (read == -1) {
                Log.e(TAG, "读取包体失败，连接已关闭，当前已读取$totalRead/$bodyLength 字节")
                throw SocketTimeoutException("Connection closed by server")
            }
            totalRead += read
            Log.d(TAG, "已读取$totalRead/$bodyLength 字节")
        }
        Log.d(TAG, "包体读取完成，成功读取$totalRead 字节")

        // 合并包头和包体
        val packet = ByteArray(packetLength)
        System.arraycopy(header, 0, packet, 0, 16)
        System.arraycopy(body, 0, packet, 16, bodyLength)
        
        Log.d(TAG, "数据包组装完成，准备返回")
        return packet
    }

    /**
     * 处理数据包
     */
    private fun handlePacket(packet: ByteArray) {
        try {
            Log.d(TAG, "开始处理数据包，准备调用DanmuParser解析")
            val messages = danmuParser.parseBinaryData(packet)
            Log.d(TAG, "DanmuParser解析完成，返回 ${messages.size} 条弹幕消息")
            if (messages.isNotEmpty()) {
                Log.d(TAG, "解析到 ${messages.size} 条弹幕消息，准备调用onDanmuReceived回调")
                onDanmuReceived(messages)
                Log.d(TAG, "onDanmuReceived回调调用完成")
            } else {
                Log.d(TAG, "未解析到弹幕消息，messages列表为空")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理数据包异常，异常类型: ${e.javaClass.simpleName}", e)
            Log.e(TAG, "异常详情: ${e.message}")
            val stackTrace = e.stackTrace.joinToString("\n")
            Log.e(TAG, "异常堆栈: $stackTrace")
        }
    }

    /**
     * 获取连接状态
     */
    fun isConnected(): Boolean {
        return isConnected
    }

    /**
     * HTTP请求工具类
     */
    private object HttpRequest {
        fun get(url: String, headers: Map<String, String>): String {
            val TAG = "DanmuHttpRequest"
            Log.d(TAG, "发起GET请求: $url")
            Log.d(TAG, "请求头: $headers")
            
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            try {
                // 添加请求头
                headers.forEach { (key, value) ->
                    connection.setRequestProperty(key, value)
                    // Log.d(TAG, "添加请求头: $key = $value")
                }

                // 发送请求
                connection.connect()
                val responseCode = connection.responseCode
                // Log.d(TAG, "HTTP响应码: $responseCode")
                
                // 获取响应头
                val responseHeaders = connection.headerFields
                // Log.d(TAG, "响应头: $responseHeaders")

                if (responseCode != 200) {
                    // 读取错误响应
                    val errorStream = connection.errorStream
                    val errorContent = errorStream?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: ""
                    connection.disconnect()
                    Log.e(TAG, "HTTP请求失败，响应码: $responseCode，错误内容: $errorContent")
                    throw Exception("HTTP请求失败，响应码: $responseCode，错误内容: $errorContent")
                }

                // 读取响应
                val inputStream = connection.inputStream
                val outputStream = ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                var length: Int
                while (inputStream.read(buffer).also { length = it } != -1) {
                    outputStream.write(buffer, 0, length)
                }
                inputStream.close()
                connection.disconnect()

                val responseContent = outputStream.toString(Charsets.UTF_8.name())
                Log.d(TAG, "HTTP响应内容: $responseContent")
                return responseContent
            } catch (e: Exception) {
                Log.e(TAG, "HTTP请求异常，异常类型: ${e.javaClass.simpleName}", e)
                connection.disconnect()
                throw e
            }
        }
    }
}
