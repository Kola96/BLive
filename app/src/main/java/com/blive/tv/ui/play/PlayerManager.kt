package com.blive.tv.ui.play

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.blive.tv.network.RetrofitClient
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource

/**
 * 直播播放器管理器
 *
 * 收敛原先散落在 LivePlayActivity 中的 4 个 ExoPlayer 实例
 * （主播放器 / 竞速双流 target+fast / 无缝切换 helper）的生命周期。
 *
 * 职责边界：
 * - 只负责"给 URL 就能播"：单流播放（带 fallback 列表）、双流竞速、无缝切换。
 * - 不负责网络请求与刷新调度（那是 ViewModel 的事），通过 [Events] 回调向外通报。
 *
 * 所有方法必须在主线程调用。
 */
class PlayerManager(
    private val context: Context,
    private val events: Events
) {
    interface Events {
        /** 首帧已上屏（竞速胜出或单流就绪），整个播放会话只触发一次 */
        fun onFirstFrame()

        /** 当前流就绪（用于隐藏 loading） */
        fun onReady()

        /** 无缝切换失败（新流预加载出错），由调用方决定何时重试 */
        fun onSwitchFailed()

        /** 所有可用 URL 均播放失败 */
        fun onFatalError(message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var playerView: PlayerView? = null

    /** 当前上屏的播放器 */
    private var activePlayer: ExoPlayer? = null

    /** 无缝切换预加载播放器 */
    private var switchPlayer: ExoPlayer? = null

    /** 首屏竞速：目标画质 / 最低画质播放器 */
    private var raceTargetPlayer: ExoPlayer? = null
    private var raceFastPlayer: ExoPlayer? = null

    private var hasFirstFrameShown = false

    /** fallback URL 列表与游标（由 ViewModel 在每次拿到播放信息时提供） */
    private var fallbackUrls: List<String> = emptyList()
    private var fallbackIndex: Int = 0

    /** 绑定渲染视图。Activity 重建后可再次调用，不中断播放。 */
    fun attach(view: PlayerView) {
        playerView = view
        view.player = activePlayer
    }

    /**
     * 单流播放。失败时按 [fallbackUrls] 顺序自动重试。
     */
    fun play(url: String, fallbackUrls: List<String>) {
        hasFirstFrameShown = false
        this.fallbackUrls = fallbackUrls
        fallbackIndex = fallbackUrls.indexOf(url).takeIf { it >= 0 } ?: 0
        Log.d(TAG, "单流播放，fallback 共 ${fallbackUrls.size} 个")
        startActivePlayer(url)
    }

    /**
     * 双流竞速：目标画质与最低画质同时静音加载，先就绪者先上屏；
     * 若低画质先上屏，目标画质就绪后无缝切换。
     */
    fun playDual(targetUrl: String, fastUrl: String, fallbackUrls: List<String>) {
        hasFirstFrameShown = false
        this.fallbackUrls = fallbackUrls
        fallbackIndex = 0
        if (targetUrl == fastUrl || fastUrl.isEmpty()) {
            play(targetUrl, fallbackUrls)
            return
        }
        Log.d(TAG, "双流竞速加载")
        loadRacePlayer(isTarget = true, url = targetUrl)
        loadRacePlayer(isTarget = false, url = fastUrl)
    }

    /**
     * 无缝切换：静音预加载新流，就绪后交换上屏，旧流延迟释放。
     * 失败回调 [Events.onSwitchFailed]，当前播放不受影响。
     */
    fun seamlessSwitch(url: String) {
        cancelRacePlayers()
        switchPlayer?.release()
        Log.d(TAG, "预加载切换流")
        switchPlayer = createPlayer(muted = true, url = url).also { newPlayer ->
            newPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        newPlayer.removeListener(this)
                        performSwitch(newPlayer)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "切换流预加载失败", error)
                    if (switchPlayer === newPlayer) {
                        switchPlayer = null
                    }
                    newPlayer.release()
                    events.onSwitchFailed()
                }
            })
        }
    }

    /** 取消进行中的竞速加载（用户手动切换时调用） */
    fun cancelRacePlayers() {
        raceTargetPlayer?.release()
        raceTargetPlayer = null
        raceFastPlayer?.release()
        raceFastPlayer = null
    }

    fun pause() {
        activePlayer?.pause()
    }

    fun resume() {
        activePlayer?.play()
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        cancelRacePlayers()
        switchPlayer?.release()
        switchPlayer = null
        activePlayer?.release()
        activePlayer = null
        playerView?.player = null
        playerView = null
    }

    // ---------------- 内部实现 ----------------

    private fun startActivePlayer(url: String) {
        val newPlayer = createPlayer(muted = false, url = url)
        activePlayer = newPlayer
        playerView?.player = newPlayer
        attachMainListener(newPlayer)
    }

    private fun loadRacePlayer(isTarget: Boolean, url: String) {
        val newPlayer = createPlayer(muted = true, url = url)
        if (isTarget) {
            raceTargetPlayer = newPlayer
        } else {
            raceFastPlayer = newPlayer
        }
        newPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    onRacePlayerReady(newPlayer, isTarget)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "竞速播放器(${if (isTarget) "target" else "fast"})出错", error)
                if (isTarget && raceTargetPlayer === newPlayer) raceTargetPlayer = null
                if (!isTarget && raceFastPlayer === newPlayer) raceFastPlayer = null
                newPlayer.release()
                // 对手也不存在且尚无画面，走 fallback
                if (raceTargetPlayer == null && raceFastPlayer == null && !hasFirstFrameShown) {
                    tryNextFallback(error)
                }
            }
        })
    }

    private fun onRacePlayerReady(candidate: ExoPlayer, isTarget: Boolean) {
        // 过期回调（已被取消/替换）
        val isStale = if (isTarget) raceTargetPlayer !== candidate else raceFastPlayer !== candidate
        if (isStale) {
            candidate.release()
            return
        }

        if (!hasFirstFrameShown) {
            // 竞速胜出，直接上屏
            hasFirstFrameShown = true
            if (isTarget) raceTargetPlayer = null else raceFastPlayer = null
            // 若低画质胜出，目标画质继续在后台加载，就绪后走切换分支
            if (!isTarget) {
                // fast 胜出
            } else {
                raceFastPlayer?.release()
                raceFastPlayer = null
            }
            promoteToActive(candidate)
            events.onFirstFrame()
        } else {
            if (isTarget) {
                // 低画质先上屏，目标画质后到：无缝切换
                raceTargetPlayer = null
                switchOnScreen(candidate)
            } else {
                // 目标画质已在播，丢弃低画质
                candidate.release()
                raceFastPlayer = null
            }
        }
    }

    /** 竞速胜者上屏 */
    private fun promoteToActive(winner: ExoPlayer) {
        winner.volume = 1.0f
        activePlayer = winner
        playerView?.player = winner
        attachMainListener(winner)
        events.onReady()
    }

    /** 无缝切换上屏（竞速目标后来居上 / 手动切换 / 定时刷新共用） */
    private fun switchOnScreen(newPlayer: ExoPlayer) {
        val oldPlayer = activePlayer
        // 先断开旧播放器与 Surface 的连接，避免释放时底层争抢缓冲区
        oldPlayer?.clearVideoSurface()

        newPlayer.volume = 1.0f
        activePlayer = newPlayer
        playerView?.player = newPlayer
        attachMainListener(newPlayer)

        // 延迟释放旧播放器，让硬件解码器优雅关闭
        mainHandler.postDelayed({
            oldPlayer?.stop()
            oldPlayer?.release()
        }, RELEASE_OLD_PLAYER_DELAY_MS)

        hasFirstFrameShown = true
        events.onReady()
    }

    private fun performSwitch(newPlayer: ExoPlayer) {
        if (switchPlayer !== newPlayer) {
            // 过期回调
            newPlayer.release()
            return
        }
        switchPlayer = null
        switchOnScreen(newPlayer)
    }

    private fun attachMainListener(target: ExoPlayer) {
        target.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> events.onReady()
                    Player.STATE_IDLE -> {
                        target.playerError?.let { onActivePlayerError(target, it) }
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onActivePlayerError(target, error)
            }
        })
    }

    /** 主播放器出错：仅处理当前在屏播放器，且每个播放器只消费一次错误 */
    private fun onActivePlayerError(failing: ExoPlayer, error: PlaybackException) {
        if (activePlayer !== failing) return
        activePlayer = null
        failing.release()
        playerView?.player = null
        Log.e(TAG, "播放失败: ${error.errorCodeName}", error)
        tryNextFallback(error)
    }

    private fun tryNextFallback(lastError: PlaybackException? = null) {
        fallbackIndex++
        if (fallbackIndex < fallbackUrls.size) {
            Log.w(TAG, "尝试下一个流 ($fallbackIndex/${fallbackUrls.size})")
            val nextUrl = fallbackUrls[fallbackIndex]
            mainHandler.postDelayed({
                if (activePlayer == null && !hasFirstFrameShown) {
                    startActivePlayer(nextUrl)
                }
            }, FALLBACK_RETRY_DELAY_MS)
        } else {
            Log.e(TAG, "所有流均播放失败")
            hasFirstFrameShown = false
            events.onFatalError(errorMessageOf(lastError))
        }
    }

    private fun errorMessageOf(error: PlaybackException?): String {
        return when (error?.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接超时"
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "无效的内容类型"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "HTTP状态错误，已尝试所有可用流"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件未找到"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码器初始化失败"
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "解码器查询失败"
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "网络错误，请检查网络连接"
            else -> "播放错误：${error?.message ?: "未知错误"}"
        }
    }

    private fun createPlayer(muted: Boolean, url: String): ExoPlayer {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(RetrofitClient.WEB_USER_AGENT)
            .setDefaultRequestProperties(
                mapOf(
                    "Referer" to "https://live.bilibili.com/",
                    "Origin" to "https://live.bilibili.com"
                )
            )
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .apply {
                if (muted) volume = 0f
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
    }

    companion object {
        private const val TAG = "PlayerManager"
        private const val RELEASE_OLD_PLAYER_DELAY_MS = 500L
        private const val FALLBACK_RETRY_DELAY_MS = 500L
    }
}
