package com.blive.tv.ui.play

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blive.tv.danmu.DanmuMessage
import com.blive.tv.danmu.DanmuTcpClient
import com.blive.tv.data.model.RoomPlayInfoResponse
import com.blive.tv.network.RetrofitClient
import com.blive.tv.utils.AppRuntime
import com.blive.tv.utils.TokenManager
import com.blive.tv.utils.UserPreferencesManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 直播播放页 ViewModel
 *
 * 职责：播放信息请求、清晰度/线路/编码选择、90 分钟定时刷新、
 * 弹幕连接与消息流、关注操作、弹幕偏好持久化。
 * 播放器实例生命周期由 [PlayerManager] 负责，Activity 只做 View 绑定与按键分发。
 */
class LivePlayViewModel : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val errorMessage: String? = null,
        val qualityOptions: List<QualityOption> = emptyList(),
        val cdnOptions: List<CdnOption> = emptyList(),
        val codecOptions: List<CodecOption> = emptyList(),
        val selectedQn: Int = 10000,
        val selectedCdnHost: String = "",
        val selectedCodec: String = "avc",
        val danmuEnabled: Boolean = true,
        val danmuSpeed: Float = 1.0f,
        val danmuOpacity: Float = 1.0f,
        val danmuSize: Float = 1.0f,
        val anchorMid: Long = 0L,
        val anchorName: String = "",
        val roomTitle: String = "",
        val isFollowing: Boolean = false
    )

    /** 一次性事件：驱动播放器 / Toast / 关注动画收尾 */
    sealed interface PlayEvent {
        data class PlaySingle(val url: String, val fallbackUrls: List<String>) : PlayEvent
        data class PlayDual(val targetUrl: String, val fastUrl: String, val fallbackUrls: List<String>) : PlayEvent
        data class SwitchStream(val url: String) : PlayEvent
        data class Toast(val message: String) : PlayEvent
        /** 关注操作结束（成功或失败），Activity 收尾 loading 动画 */
        data class FollowSettled(val success: Boolean) : PlayEvent
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<PlayEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PlayEvent> = _events.asSharedFlow()

    /** 弹幕消息流。缓冲满时丢弃最旧，避免阻塞弹幕接收协程 */
    private val _danmuMessages = MutableSharedFlow<List<DanmuMessage>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val danmuMessages: SharedFlow<List<DanmuMessage>> = _danmuMessages.asSharedFlow()

    private val streamResolver = PlayStreamResolver()
    private var response: RoomPlayInfoResponse? = null
    private var capabilityGraph: CapabilityGraph? = null
    private var fallbackUrls: List<String> = emptyList()

    private var roomId: Long = -1L
    private var initialized = false

    /** 最近一次播放指令，用于 Activity 重建后恢复播放 */
    private var lastPlayCommand: PlayEvent? = null

    private var refreshJob: Job? = null
    private var danmuTcpClient: DanmuTcpClient? = null

    private val appContext get() = AppRuntime.appContext

    val isInitialized: Boolean get() = initialized

    // ---------------- 初始化 ----------------

    fun initialize(roomId: Long, anchorMid: Long, anchorName: String, roomTitle: String) {
        if (initialized) return
        initialized = true
        this.roomId = roomId

        // 从偏好恢复选择与弹幕设置
        _uiState.update {
            it.copy(
                selectedQn = UserPreferencesManager.getQualityQn(appContext),
                danmuEnabled = UserPreferencesManager.isDanmakuEnabled(appContext),
                danmuSize = UserPreferencesManager.getDanmakuSizeScale(appContext),
                danmuOpacity = UserPreferencesManager.getDanmakuAlpha(appContext),
                anchorMid = anchorMid,
                anchorName = anchorName,
                roomTitle = roomTitle
            )
        }

        startDanmuClient()
        fetchPlayInfo()
    }

    // ---------------- 播放信息 ----------------

    private fun fetchPlayInfo() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val playInfo = requestPlayInfo(roomId, _uiState.value.selectedQn)
                if (playInfo.code != 0) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "获取播放信息失败：${playInfo.message}") }
                    return@launch
                }
                val data = playInfo.data
                if (data == null) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "无法获取播放地址") }
                    return@launch
                }
                response = playInfo
                // intent 未带 anchorMid 时，从播放信息回填
                if (_uiState.value.anchorMid == 0L) {
                    _uiState.update { it.copy(anchorMid = data.uid) }
                }
                applyPlayInfo(playInfo)

                val targetUrl = resolveCurrentUrl()
                if (targetUrl.isEmpty()) {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "无法获取播放地址") }
                    return@launch
                }
                fallbackUrls = streamResolver.buildAllUrls(data)

                val minQn = _uiState.value.qualityOptions.minByOrNull { it.qn }?.qn
                val fastUrl = if (minQn != null && minQn != _uiState.value.selectedQn) {
                    streamResolver.findStreamUrl(data, "http_stream", "flv", _uiState.value.selectedCodec, minQn, _uiState.value.selectedCdnHost)
                        .ifEmpty {
                            streamResolver.findStreamUrl(data, "http_stream", "ts", _uiState.value.selectedCodec, minQn, _uiState.value.selectedCdnHost)
                        }
                } else {
                    targetUrl
                }

                if (fastUrl.isNotEmpty() && fastUrl != targetUrl) {
                    emitPlayCommand(PlayEvent.PlayDual(targetUrl, fastUrl, fallbackUrls))
                } else {
                    emitPlayCommand(PlayEvent.PlaySingle(targetUrl, fallbackUrls))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "获取播放信息失败", e)
                _uiState.update { it.copy(isLoading = false, errorMessage = "网络连接错误：${e.message}") }
            }
        }
    }

    /** 解析播放信息并更新选项状态，同时校正当前选择 */
    private fun applyPlayInfo(playInfo: RoomPlayInfoResponse) {
        val data = playInfo.data ?: return
        val graph = streamResolver.buildCapabilityGraph(data)
        capabilityGraph = graph

        _uiState.update { state ->
            val resolved = streamResolver.resolveSelection(
                graph = graph,
                request = SelectionRequest(
                    targetQn = state.selectedQn,
                    preferredCodec = state.selectedCodec,
                    currentCdnHost = state.selectedCdnHost
                )
            )
            val newQn = resolved?.resolvedQn ?: state.selectedQn
            val newCodec = resolved?.resolvedCodec ?: state.selectedCodec
            val newCdn = resolved?.resolvedCdnHost ?: state.selectedCdnHost
            val panelOptions = streamResolver.buildPanelOptions(graph, newQn, newCodec, newCdn)
            state.copy(
                selectedQn = newQn,
                selectedCodec = newCodec,
                selectedCdnHost = newCdn,
                qualityOptions = panelOptions.qualityOptions,
                cdnOptions = panelOptions.cdnOptions,
                codecOptions = panelOptions.codecOptions
            )
        }
    }

    private fun resolveCurrentUrl(): String {
        val data = response?.data ?: return ""
        val graph = capabilityGraph ?: streamResolver.buildCapabilityGraph(data).also { capabilityGraph = it }
        val resolved = streamResolver.resolveSelection(
            graph = graph,
            request = _uiState.value.let {
                SelectionRequest(it.selectedQn, it.selectedCodec, it.selectedCdnHost)
            }
        ) ?: return ""
        _uiState.update {
            it.copy(
                selectedQn = resolved.resolvedQn,
                selectedCodec = resolved.resolvedCodec,
                selectedCdnHost = resolved.resolvedCdnHost
            )
        }
        return resolved.url
    }

    /** 记录并发送播放指令，供 Activity 重建后通过 [requestReplay] 恢复 */
    private fun emitPlayCommand(command: PlayEvent) {
        lastPlayCommand = command
        _events.tryEmit(command)
    }

    /**
     * Activity 重建（配置变更）后调用：重发最近一次播放指令。
     * 新的 PlayerManager 会从头加载当前流，ViewModel 侧的弹幕连接与刷新计时不受影响。
     */
    fun requestReplay() {
        lastPlayCommand?.let { _events.tryEmit(it) }
    }

    // ---------------- 播放事件回馈 ----------------

    /** 首帧已上屏 / 每次流就绪：重置 90 分钟刷新计时 */
    fun onPlaybackReady() {
        _uiState.update { it.copy(isLoading = false, errorMessage = null) }
        scheduleRefreshLoop(REFRESH_INTERVAL_MS)
    }

    /** 无缝切换失败（新流预加载出错）：5 分钟后重试刷新 */
    fun onSwitchFailed() {
        scheduleRefreshLoop(REFRESH_RETRY_INTERVAL_MS)
    }

    fun onFatalPlaybackError(message: String) {
        _uiState.update { it.copy(isLoading = false, errorMessage = message) }
    }

    private fun scheduleRefreshLoop(initialDelayMs: Long) {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            var delayMs = initialDelayMs
            while (isActive) {
                delay(delayMs)
                delayMs = if (refreshStreamOnce()) REFRESH_INTERVAL_MS else REFRESH_RETRY_INTERVAL_MS
            }
        }
    }

    /** 刷新直播流并发起无缝切换。成功返回 true */
    private suspend fun refreshStreamOnce(): Boolean {
        if (roomId <= 0) return false
        return try {
            val playInfo = requestPlayInfo(roomId, _uiState.value.selectedQn)
            if (playInfo.code != 0) return false
            response = playInfo
            applyPlayInfo(playInfo)
            val url = resolveCurrentUrl()
            if (url.isEmpty()) return false
            _events.emit(PlayEvent.SwitchStream(url))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "定时刷新失败", e)
            false
        }
    }

    // ---------------- 用户选择 ----------------

    fun selectQuality(qn: Int) {
        if (qn == _uiState.value.selectedQn && response != null) return
        _uiState.update { it.copy(selectedQn = qn) }
        UserPreferencesManager.setQualityQn(appContext, qn)
        viewModelScope.launch {
            try {
                val playInfo = requestPlayInfo(roomId, qn)
                if (playInfo.code != 0) {
                    _events.emit(PlayEvent.Toast("获取画质信息失败"))
                    return@launch
                }
                response = playInfo
                applyPlayInfo(playInfo)
                if (playInfo.data == null) {
                    _events.emit(PlayEvent.Toast("当前画质无可用流"))
                    return@launch
                }
                playCurrentSelection()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "切换画质失败", e)
                _events.emit(PlayEvent.Toast("网络错误，切换失败"))
            }
        }
    }

    fun selectCdn(host: String) {
        _uiState.update { it.copy(selectedCdnHost = host) }
        playCurrentSelection()
    }

    fun selectCodec(codec: String) {
        _uiState.update { it.copy(selectedCodec = codec) }
        playCurrentSelection()
    }

    /** 根据当前选择重建 URL 并无缝切换 */
    private fun playCurrentSelection() {
        val url = resolveCurrentUrl()
        if (url.isNotEmpty()) {
            _events.tryEmit(PlayEvent.SwitchStream(url))
        } else {
            _events.tryEmit(PlayEvent.Toast("当前画质无可用流"))
            response?.let { applyPlayInfo(it) }
        }
    }

    // ---------------- 弹幕设置（修改即持久化，与全局设置页保持一致） ----------------

    fun setDanmuEnabled(enable: Boolean) {
        _uiState.update { it.copy(danmuEnabled = enable) }
        UserPreferencesManager.setDanmakuEnabled(appContext, enable)
    }

    fun setDanmuSpeed(speed: Float) {
        _uiState.update { it.copy(danmuSpeed = speed) }
    }

    fun setDanmuOpacity(opacity: Float) {
        _uiState.update { it.copy(danmuOpacity = opacity) }
        UserPreferencesManager.setDanmakuAlpha(appContext, opacity)
    }

    fun setDanmuSize(size: Float) {
        _uiState.update { it.copy(danmuSize = size) }
        UserPreferencesManager.setDanmakuSizeScale(appContext, size)
    }

    // ---------------- 弹幕连接 ----------------

    private fun startDanmuClient() {
        danmuTcpClient = DanmuTcpClient(
            roomId = roomId,
            onDanmuReceived = { messages -> _danmuMessages.tryEmit(messages) },
            onConnectionStatusChanged = { connected ->
                Log.d(TAG, "弹幕连接状态: $connected")
            }
        )
        danmuTcpClient?.start()
    }

    // ---------------- 关注 ----------------

    fun refreshFollowStatus() {
        val mid = _uiState.value.anchorMid
        if (mid <= 0) return
        viewModelScope.launch {
            try {
                val resp = awaitCall(RetrofitClient.apiService.getRelationStatus(mid))
                if (resp.isSuccessful && resp.body()?.code == 0) {
                    val attribute = resp.body()?.data?.attribute ?: 0
                    _uiState.update { it.copy(isFollowing = attribute == 2 || attribute == 6) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "获取关注状态失败", e)
            }
        }
    }

    fun toggleFollow() {
        val state = _uiState.value
        if (!TokenManager.isLoggedIn(appContext)) {
            _events.tryEmit(PlayEvent.Toast("请先登录"))
            _events.tryEmit(PlayEvent.FollowSettled(false))
            return
        }
        if (state.anchorMid <= 0) {
            _events.tryEmit(PlayEvent.Toast("无法获取主播信息"))
            _events.tryEmit(PlayEvent.FollowSettled(false))
            return
        }
        val csrf = TokenManager.getCsrfToken(appContext)
        if (csrf.isNullOrEmpty()) {
            _events.tryEmit(PlayEvent.Toast("无法获取登录凭证"))
            _events.tryEmit(PlayEvent.FollowSettled(false))
            return
        }

        val follow = !state.isFollowing
        viewModelScope.launch {
            val params = mapOf(
                "fid" to state.anchorMid.toString(),
                "act" to if (follow) "1" else "2",
                "csrf" to csrf,
                "re_src" to "14"
            )
            try {
                val resp = awaitCall(RetrofitClient.apiService.modifyRelation(params))
                if (resp.isSuccessful && resp.body()?.code == 0) {
                    _uiState.update { it.copy(isFollowing = follow) }
                    _events.emit(PlayEvent.Toast(if (follow) "关注成功" else "取消关注成功"))
                    _events.emit(PlayEvent.FollowSettled(true))
                } else {
                    _events.emit(PlayEvent.Toast(resp.body()?.message ?: "操作失败"))
                    _events.emit(PlayEvent.FollowSettled(false))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "关注操作失败", e)
                _events.emit(PlayEvent.Toast("网络错误"))
                _events.emit(PlayEvent.FollowSettled(false))
            }
        }
    }

    // ---------------- 生命周期 ----------------

    override fun onCleared() {
        refreshJob?.cancel()
        danmuTcpClient?.stop()
        danmuTcpClient = null
    }

    // ---------------- 网络工具 ----------------

    private suspend fun requestPlayInfo(roomId: Long, qn: Int): RoomPlayInfoResponse =
        withContext(Dispatchers.IO) {
            val resp = awaitCall(RetrofitClient.liveApiService.getRoomPlayInfo(roomId, qn = qn))
            if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code()}")
            resp.body() ?: throw java.io.IOException("播放信息响应为空")
        }

    private suspend fun <T> awaitCall(call: Call<T>): Response<T> =
        suspendCancellableCoroutine { continuation ->
            call.enqueue(object : Callback<T> {
                override fun onResponse(call: Call<T>, response: Response<T>) {
                    continuation.resume(response)
                }

                override fun onFailure(call: Call<T>, t: Throwable) {
                    continuation.resumeWithException(t)
                }
            })
        }

    companion object {
        private const val TAG = "LivePlayViewModel"
        /** 直播流 90 分钟过期，定时刷新 */
        private const val REFRESH_INTERVAL_MS = 90 * 60 * 1000L
        /** 刷新失败后 5 分钟重试 */
        private const val REFRESH_RETRY_INTERVAL_MS = 5 * 60 * 1000L
    }
}
