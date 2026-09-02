package com.blive.tv.danmu

import android.util.Log
import com.blive.tv.network.RetrofitClient
import com.blive.tv.network.WbiKeyParser
import com.blive.tv.network.WbiSigner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * B站弹幕TCP客户端（纯协程实现）
 *
 * - [start] 启动会话协程，内部带指数退避自动重连；重复调用安全（幂等）。
 * - [stop] 取消当前会话并关闭 socket，scope 保活，可随时再次 [start]。
 * - 网络预备（BUVid / WBI 签名 / 弹幕服务器信息）统一走 RetrofitClient(OkHttp)，
 *   WBI 签名复用 [WbiSigner]，不再手写 HttpURLConnection。
 * - 敏感信息（token、WBI key、响应体）不输出日志。
 */
class DanmuTcpClient(
    private val roomId: Long,
    private val onDanmuReceived: (List<DanmuMessage>) -> Unit,
    private val onConnectionStatusChanged: (Boolean) -> Unit,
    private val onLog: ((String) -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val danmuParser = DanmuParser()

    private var sessionJob: Job? = null

    /** 当前会话使用的 socket，关闭它可打断阻塞中的 read。仅通过 [closeSocket] 清理。 */
    private val socketLock = Any()
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    /** 心跳与认证包都可能写 outputStream，写操作需串行化避免帧交错 */
    private val writeLock = Any()

    @Volatile
    private var connected: Boolean = false

    // 会话级凭据，每次重连刷新
    private var buvid: String? = null
    private var token: String? = null

    private fun log(message: String) {
        Log.d(TAG, message)
        onLog?.invoke(message)
    }

    private fun logWarn(message: String) {
        Log.w(TAG, message)
        onLog?.invoke("Warn: $message")
    }

    /**
     * 启动弹幕客户端。已运行中重复调用为 no-op；[stop] 后可再次调用。
     */
    fun start() {
        if (sessionJob?.isActive == true) {
            log("弹幕客户端已在运行，忽略重复 start()")
            return
        }
        log("启动弹幕客户端，roomId=$roomId")
        sessionJob = scope.launch { runSessionLoop() }
    }

    /**
     * 停止弹幕客户端。只取消会话协程并关闭 socket，scope 保活可重启。
     */
    fun stop() {
        sessionJob?.cancel()
        sessionJob = null
        closeSocket()
        setConnected(false)
    }

    fun isConnected(): Boolean = connected

    private fun setConnected(value: Boolean) {
        if (connected != value) {
            connected = value
            onConnectionStatusChanged(value)
        }
    }

    // ---------------- 会话与重连 ----------------

    private suspend fun runSessionLoop() {
        var backoffMs = RECONNECT_INITIAL_BACKOFF_MS
        while (currentCoroutineContext().isActive) {
            try {
                prepareCredentials()
                serveConnection()
                // 连接正常结束（对端关闭）也视为掉线，进入重连
                logWarn("弹幕连接已断开")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarn("弹幕会话异常: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                setConnected(false)
                closeSocket()
            }

            if (!currentCoroutineContext().isActive) break
            log("弹幕 ${backoffMs / 1000}s 后重连")
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_BACKOFF_MS)
        }
    }

    /**
     * 建立一次完整连接：认证 → 心跳 + 接收循环，直至任一方失败。
     */
    private suspend fun serveConnection() {
        connectSocket()
        sendAuthPacket()
        setConnected(true)
        log("弹幕连接建立成功")

        // 心跳失败时关闭 socket 打断接收循环，由接收循环抛出异常统一进入重连
        // 注意：心跳协程以当前会话协程为父级，stop() 取消会话时会被一并取消
        val heartbeatJob = CoroutineScope(currentCoroutineContext()).launch {
            try {
                heartbeatLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logWarn("心跳异常，关闭连接以触发重连: ${e.message}")
                closeSocket()
            }
        }
        try {
            receiveLoop()
        } finally {
            heartbeatJob.cancel()
        }
    }

    // ---------------- 连接预备 ----------------

    private fun prepareCredentials() {
        buvid = fetchBuvid()

        val navResponse = RetrofitClient.apiService.getNavInfo().execute()
        val navBody = navResponse.body()
        if (!navResponse.isSuccessful || navBody == null || navBody.code != 0 || navBody.data == null) {
            throw IllegalStateException("获取WBI密钥失败: http=${navResponse.code()}")
        }
        val imgKey = WbiKeyParser.parseFromUrl(navBody.data.wbiImg?.imgUrl.orEmpty())
        val subKey = WbiKeyParser.parseFromUrl(navBody.data.wbiImg?.subUrl.orEmpty())
        if (imgKey.isEmpty() || subKey.isEmpty()) {
            throw IllegalStateException("解析WBI密钥失败")
        }

        val unsignedParams = mutableMapOf(
            "id" to roomId.toString(),
            "type" to "0",
            "web_location" to "444.8"
        )
        val (wRid, wts) = WbiSigner.sign(unsignedParams, imgKey, subKey)
        val requestParams = unsignedParams.toMutableMap()
        requestParams["w_rid"] = wRid
        requestParams["wts"] = wts

        val danmuResponse = RetrofitClient.liveApiService.getDanmuInfoSigned(requestParams).execute()
        val danmuBody = danmuResponse.body()
        if (!danmuResponse.isSuccessful || danmuBody == null || danmuBody.code != 0) {
            throw IllegalStateException("获取弹幕服务器信息失败: http=${danmuResponse.code()}")
        }
        val data = danmuBody.data ?: throw IllegalStateException("弹幕服务器信息为空")
        token = data.token ?: throw IllegalStateException("弹幕token为空")
        val hostInfo = data.hostList?.firstOrNull()
        val host = hostInfo?.host
        if (!host.isNullOrEmpty()) {
            serverHost = host
            serverPort = hostInfo.port
        }
    }

    private var serverHost: String = DEFAULT_SERVER_HOST
    private var serverPort: Int = DEFAULT_SERVER_PORT

    private fun fetchBuvid(): String {
        return try {
            val response = RetrofitClient.apiService.getFingerSpiRaw().execute()
            val body = response.body()?.string().orEmpty()
            val b3 = runCatching {
                org.json.JSONObject(body).getJSONObject("data").getString("b_3")
            }.getOrNull()
            if (!b3.isNullOrEmpty()) b3 else randomBuvid()
        } catch (e: Exception) {
            logWarn("获取BUVid失败，使用随机值: ${e.message}")
            randomBuvid()
        }
    }

    private fun randomBuvid(): String {
        val randomPart = (1..18).joinToString("") { ('0'..'f').random().toString() }
        return "XY${randomPart}infoc"
    }

    // ---------------- Socket 与协议 ----------------

    private fun connectSocket() {
        val newSocket = Socket()
        newSocket.connect(InetSocketAddress(serverHost, serverPort), CONNECT_TIMEOUT_MS)
        // 心跳间隔30s，读超时给足余量，防止没有弹幕时误判掉线
        newSocket.soTimeout = READ_TIMEOUT_MS
        synchronized(socketLock) {
            socket = newSocket
            inputStream = newSocket.getInputStream()
            outputStream = newSocket.getOutputStream()
        }
    }

    private fun closeSocket() {
        synchronized(socketLock) {
            try {
                inputStream?.close()
            } catch (_: Exception) {
            }
            try {
                outputStream?.close()
            } catch (_: Exception) {
            }
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            inputStream = null
            outputStream = null
            socket = null
        }
    }

    private fun buildAuthJson(): String {
        // 字段按字母序排列，与官方协议示例保持一致
        return "{\"buvid\":\"$buvid\",\"key\":\"$token\",\"platform\":\"danmuji\",\"protover\":3,\"roomid\":$roomId,\"type\":2,\"uid\":0}"
    }

    private fun sendAuthPacket() {
        synchronized(writeLock) {
            writePacket(OP_AUTH, buildAuthJson().toByteArray(Charsets.UTF_8), PROTOCOL_VERSION_PLAIN)
        }
    }

    private suspend fun heartbeatLoop() {
        while (currentCoroutineContext().isActive) {
            synchronized(writeLock) {
                writePacket(OP_HEARTBEAT, HEARTBEAT_BODY, PROTOCOL_VERSION_PLAIN)
            }
            delay(HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun writePacket(operation: Int, body: ByteArray, version: Short) {
        val packetLength = HEADER_SIZE + body.size
        val buffer = ByteBuffer.allocate(packetLength).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(packetLength)
            .putShort(HEADER_SIZE.toShort())
            .putShort(version)
            .putInt(operation)
            .putInt(SEQUENCE)
            .put(body)
        val out = outputStream ?: throw IllegalStateException("outputStream is null")
        out.write(buffer.array())
        out.flush()
    }

    private suspend fun receiveLoop() {
        while (currentCoroutineContext().isActive) {
            val packet = readPacket()
            val messages = runCatching { danmuParser.parseBinaryData(packet) }
                .onFailure { logWarn("解析弹幕包失败: ${it.message}") }
                .getOrDefault(emptyList())
            if (messages.isNotEmpty()) {
                onDanmuReceived(messages)
            }
        }
    }

    private fun readPacket(): ByteArray {
        val input = inputStream ?: throw IllegalStateException("inputStream is null")
        val header = ByteArray(HEADER_SIZE)
        readFully(input, header, 0, HEADER_SIZE)

        val headerBuffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val packetLength = headerBuffer.getInt()
        headerBuffer.getShort() // headerLength，恒为16
        headerBuffer.getShort() // version
        headerBuffer.getInt()   // operation
        headerBuffer.getInt()   // sequence
        val bodyLength = packetLength - HEADER_SIZE
        require(bodyLength >= 0) { "非法包长度: $packetLength" }

        val body = ByteArray(bodyLength)
        readFully(input, body, 0, bodyLength)

        val packet = ByteArray(packetLength)
        System.arraycopy(header, 0, packet, 0, HEADER_SIZE)
        System.arraycopy(body, 0, packet, HEADER_SIZE, bodyLength)
        return packet
    }

    /**
     * InputStream.read 不保证填满缓冲区，TCP 分包时必须循环读取，
     * 否则会把正常的分包误判为连接异常。
     */
    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var totalRead = 0
        while (totalRead < length) {
            val read = input.read(buffer, offset + totalRead, length - totalRead)
            if (read == -1) throw EOFException("连接已被服务器关闭")
            totalRead += read
        }
    }

    companion object {
        private const val TAG = "DanmuTcpClient"

        // 弹幕协议常量
        private const val HEADER_SIZE = 16
        private const val SEQUENCE = 1
        private const val OP_HEARTBEAT = 2
        private const val OP_AUTH = 7
        private const val PROTOCOL_VERSION_PLAIN: Short = 1
        private val HEARTBEAT_BODY = "[object Object]".toByteArray(Charsets.UTF_8)

        private const val DEFAULT_SERVER_HOST = "broadcastlv.chat.bilibili.com"
        private const val DEFAULT_SERVER_PORT = 2243

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val HEARTBEAT_INTERVAL_MS = 30_000L

        private const val RECONNECT_INITIAL_BACKOFF_MS = 1_000L
        private const val RECONNECT_MAX_BACKOFF_MS = 30_000L
    }
}
