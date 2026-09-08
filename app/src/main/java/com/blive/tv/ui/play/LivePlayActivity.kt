package com.blive.tv.ui.play

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.blive.tv.R
import com.blive.tv.danmu.DanmuItem
import com.blive.tv.danmu.SimpleDanmuView
import com.blive.tv.utils.ToastHelper
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 直播播放页
 *
 * 职责收敛为：View 绑定、遥控器按键分发、订阅 [LivePlayViewModel] 状态渲染。
 * 播放器生命周期见 [PlayerManager]，网络/刷新/弹幕/关注逻辑见 [LivePlayViewModel]。
 * 设置面板为底部抽屉（分类 chip 行 + 选项 pill 行），见 [PlaySettingsPanelController]。
 */
class LivePlayActivity : AppCompatActivity() {

    private val viewModel: LivePlayViewModel by viewModels()
    private lateinit var playerManager: PlayerManager

    private lateinit var playerView: com.google.android.exoplayer2.ui.PlayerView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var settingsPanel: View
    private lateinit var simpleDanmuView: SimpleDanmuView
    private lateinit var roomInfoOverlay: View
    private lateinit var roomInfoController: RoomInfoOverlayController
    private lateinit var settingsPanelController: PlaySettingsPanelController

    private var lastBackPressedAt: Long = 0L

    // 长按关注相关（动画为纯 UI 逻辑）
    private var isCenterKeyDown: Boolean = false
    private var isFollowLoading: Boolean = false
    private val followActionRunnable = Runnable {
        if (isCenterKeyDown && roomInfoController.isVisible) {
            completeFollowAction()
        }
    }

    companion object {
        const val EXTRA_ANCHOR_MID = "anchor_mid"
        const val EXTRA_ANCHOR_NAME = "anchor_name"
        const val EXTRA_ROOM_TITLE = "room_title"
        private const val BACK_PRESS_EXIT_WINDOW_MS = 3000L
        private const val LONG_PRESS_THRESHOLD_MS = 2000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 直播播放页保持屏幕常亮，防止观看期间系统无操作超时自动休眠（在极米等投影仪上尤为明显）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_live_play)

        val currentRoomId = intent.getLongExtra("room_id", -1L)
        if (currentRoomId == -1L) {
            showError("直播间ID无效")
            finish()
            return
        }

        initViews()
        setupPlayer()
        setupControllers()
        observeViewModel()

        val alreadyInitialized = viewModel.isInitialized
        viewModel.initialize(
            roomId = currentRoomId,
            anchorMid = intent.getLongExtra(EXTRA_ANCHOR_MID, 0L),
            anchorName = intent.getStringExtra(EXTRA_ANCHOR_NAME) ?: "",
            roomTitle = intent.getStringExtra(EXTRA_ROOM_TITLE) ?: ""
        )
        if (alreadyInitialized) {
            // 配置变更导致 Activity 重建：ViewModel 仍在，恢复播放
            viewModel.requestReplay()
        }
    }

    private fun initViews() {
        playerView = findViewById(R.id.player_view)
        loadingProgress = findViewById(R.id.loading_progress)
        errorText = findViewById(R.id.error_text)
        settingsPanel = findViewById(R.id.settings_panel)
        simpleDanmuView = findViewById(R.id.simple_danmu_view)
        roomInfoOverlay = findViewById(R.id.room_info_overlay)
    }

    private fun setupPlayer() {
        playerManager = PlayerManager(this, object : PlayerManager.Events {
            override fun onFirstFrame() = viewModel.onPlaybackReady()
            override fun onReady() = viewModel.onPlaybackReady()
            override fun onSwitchFailed() = viewModel.onSwitchFailed()
            override fun onFatalError(message: String) = viewModel.onFatalPlaybackError(message)
        })
        playerManager.attach(playerView)
    }

    private fun setupControllers() {
        settingsPanelController = PlaySettingsPanelController(
            settingsPanel = settingsPanel,
            categoryRecyclerView = findViewById(R.id.settings_category_recycler),
            optionRecyclerView = findViewById(R.id.settings_option_recycler),
            onToggleDanmu = {
                viewModel.setDanmuEnabled(!viewModel.uiState.value.danmuEnabled)
            },
            onOptionSelected = { categoryId, optionId ->
                onSettingOptionSelected(categoryId, optionId)
            }
        )
        roomInfoController = RoomInfoOverlayController(
            roomInfoOverlay = roomInfoOverlay,
            playerView = playerView,
            logTag = "LivePlayActivity"
        )
    }

    private fun onSettingOptionSelected(categoryId: String, optionId: String) {
        when (categoryId) {
            PlaySettingIds.QUALITY -> viewModel.selectQuality(optionId.toInt())
            PlaySettingIds.CDN -> viewModel.selectCdn(optionId)
            PlaySettingIds.CODEC -> viewModel.selectCodec(optionId)
            PlaySettingIds.DANMU_SWITCH -> viewModel.setDanmuEnabled(optionId == "1")
            PlaySettingIds.DANMU_SPEED -> viewModel.setDanmuSpeed(optionId.toFloat())
            PlaySettingIds.DANMU_OPACITY -> viewModel.setDanmuOpacity(optionId.toFloat())
            PlaySettingIds.DANMU_SIZE -> viewModel.setDanmuSize(optionId.toFloat())
            PlaySettingIds.DANMU_AREA -> viewModel.setDanmuArea(optionId.toFloat())
        }
    }

    // ---------------- 状态订阅 ----------------

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 加载/错误态
                launch {
                    viewModel.uiState
                        .map { it.isLoading to it.errorMessage }
                        .distinctUntilChanged()
                        .collect { (isLoading, errorMessage) ->
                            loadingProgress.visibility = if (isLoading) View.VISIBLE else View.GONE
                            if (errorMessage != null) {
                                errorText.visibility = View.VISIBLE
                                errorText.text = errorMessage
                            } else {
                                errorText.visibility = View.GONE
                            }
                        }
                }
                // 设置面板数据（chip 值与选项选中态随状态刷新）
                launch {
                    viewModel.uiState.collect { state ->
                        settingsPanelController.render(state)
                    }
                }
                // 弹幕 View 属性
                launch {
                    viewModel.uiState
                        .map { DanmuProps(it.danmuEnabled, it.danmuOpacity, it.danmuSize, it.danmuSpeed, it.danmuArea) }
                        .distinctUntilChanged()
                        .collect { props ->
                            simpleDanmuView.isDanmuEnabled = props.enabled
                            simpleDanmuView.danmuAlpha = props.opacity
                            simpleDanmuView.danmuSizeScale = props.size
                            simpleDanmuView.danmuSpeedScale = props.speed
                            simpleDanmuView.danmuAreaRatio = props.area
                        }
                }
                // 关注状态
                launch {
                    viewModel.uiState
                        .map { it.isFollowing }
                        .distinctUntilChanged()
                        .collect { following ->
                            if (roomInfoController.isVisible) {
                                updateFollowButtonUI(following)
                            }
                        }
                }
                // 一次性事件
                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is LivePlayViewModel.PlayEvent.PlaySingle ->
                                playerManager.play(event.url, event.fallbackUrls)
                            is LivePlayViewModel.PlayEvent.PlayDual ->
                                playerManager.playDual(event.targetUrl, event.fastUrl, event.fallbackUrls)
                            is LivePlayViewModel.PlayEvent.SwitchStream ->
                                playerManager.seamlessSwitch(event.url)
                            is LivePlayViewModel.PlayEvent.Toast ->
                                ToastHelper.showTextToast(this@LivePlayActivity, event.message)
                            is LivePlayViewModel.PlayEvent.FollowSettled ->
                                onFollowSettled(event.success)
                        }
                    }
                }
                // 弹幕消息
                launch {
                    viewModel.danmuMessages.collect { messages ->
                        renderDanmuMessages(messages)
                    }
                }
            }
        }
    }

    private data class DanmuProps(
        val enabled: Boolean,
        val opacity: Float,
        val size: Float,
        val speed: Float,
        val area: Float
    )

    // ---------------- 弹幕渲染 ----------------

    private fun renderDanmuMessages(messages: List<com.blive.tv.danmu.DanmuMessage>) {
        if (!viewModel.uiState.value.danmuEnabled) return
        for (message in messages) {
            if (message is com.blive.tv.danmu.DanmuMessage.Danmu) {
                simpleDanmuView.addDanmu(
                    DanmuItem(
                        id = System.currentTimeMillis(),
                        text = message.content,
                        color = message.color,
                        speed = viewModel.uiState.value.danmuSpeed,
                        type = when (message.mode) {
                            4 -> DanmuItem.TYPE_TOP
                            5 -> DanmuItem.TYPE_BOTTOM
                            else -> DanmuItem.TYPE_SCROLL
                        }
                    )
                )
            }
        }
    }

    // ---------------- 按键处理 ----------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                settingsPanelController.toggle()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (!settingsPanelController.isVisible && !roomInfoController.isVisible) {
                    showRoomInfoOverlay()
                    return true
                }
                if (roomInfoController.isVisible) {
                    // 重置Overlay隐藏计时器，防止长按过程中闪退
                    roomInfoController.resetAutoDismissTimer()
                    // 开始长按检测
                    if (!isCenterKeyDown && !isFollowLoading) {
                        isCenterKeyDown = true
                        startFollowLoadingAnimation()
                        roomInfoOverlay.removeCallbacks(followActionRunnable)
                        roomInfoOverlay.postDelayed(followActionRunnable, LONG_PRESS_THRESHOLD_MS)
                    }
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_BACK -> {
                if (roomInfoController.isVisible) {
                    roomInfoController.hide()
                    return true
                }
                if (settingsPanelController.isVisible) {
                    settingsPanelController.hide()
                    return true
                }
                val now = System.currentTimeMillis()
                if (now - lastBackPressedAt <= BACK_PRESS_EXIT_WINDOW_MS) {
                    setResult(RESULT_OK)
                    finish()
                    return true
                }
                lastBackPressedAt = now
                ToastHelper.showTextToast(this, "再按一次返回键退出直播间")
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (roomInfoController.isVisible) {
                    // Overlay显示时，DOWN键消费掉，不传递给设置面板
                    return true
                }
                if (!settingsPanelController.isVisible) {
                    settingsPanelController.show()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (roomInfoController.isVisible) {
                    roomInfoController.hide()
                    return true
                }
                if (!settingsPanelController.isVisible) {
                    showRoomInfoOverlay()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (roomInfoController.isVisible) {
                    // Overlay显示时，LEFT/RIGHT键消费掉
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                if (isFollowLoading) {
                    // 用户提前松手，取消动画与长按任务
                    isCenterKeyDown = false
                    roomInfoOverlay.removeCallbacks(followActionRunnable)
                    hideFollowLoading()
                    roomInfoController.resetAutoDismissTimer()
                    return true
                }
                isCenterKeyDown = false
                roomInfoOverlay.removeCallbacks(followActionRunnable)
                if (roomInfoController.isVisible) {
                    roomInfoController.resetAutoDismissTimer()
                    return true
                }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    // ---------------- 房间信息 Overlay 与关注 ----------------

    private fun showRoomInfoOverlay() {
        val state = viewModel.uiState.value
        roomInfoOverlay.findViewById<TextView>(R.id.room_title)?.text = state.roomTitle
        roomInfoOverlay.findViewById<TextView>(R.id.anchor_name)?.text = state.anchorName
        roomInfoOverlay.findViewById<TextView>(R.id.hint_text)?.visibility = View.VISIBLE

        if (state.anchorMid > 0) {
            viewModel.refreshFollowStatus()
        } else {
            updateFollowButtonUI(false)
            hideFollowLoading()
        }
        roomInfoController.show()
    }

    /** 长按完成，执行关注/取关（动画已在长按开始时启动） */
    private fun completeFollowAction() {
        isCenterKeyDown = false
        viewModel.toggleFollow()
    }

    /** 关注操作结束：收尾动画并刷新按钮 */
    private fun onFollowSettled(success: Boolean) {
        isFollowLoading = false
        val following = viewModel.uiState.value.isFollowing
        val animationView = roomInfoOverlay.findViewById<FollowButtonView>(R.id.follow_animation_view)
        animationView?.setFollowingState(following)
        animationView?.visibility = View.GONE
        updateFollowButtonUI(following)
    }

    private fun updateFollowButtonUI(following: Boolean) {
        val followContent = roomInfoOverlay.findViewById<View>(R.id.follow_content)
        val followText = roomInfoOverlay.findViewById<TextView>(R.id.follow_text)
        val followIcon = roomInfoOverlay.findViewById<android.widget.ImageView>(R.id.follow_icon)
        val hintText = roomInfoOverlay.findViewById<TextView>(R.id.hint_text)

        if (following) {
            followContent?.setBackgroundResource(R.drawable.follow_solid_background)
            followText?.text = "已关注"
            followIcon?.setImageResource(R.drawable.ic_heart_filled)
            hintText?.text = "长按确认键取关"
        } else {
            followContent?.setBackgroundResource(R.drawable.follow_border_background)
            followText?.text = "关注"
            followIcon?.setImageResource(R.drawable.ic_lucide_heart)
            hintText?.text = "长按确认键关注"
        }
    }

    private fun hideFollowLoading() {
        isFollowLoading = false
        val animationView = roomInfoOverlay.findViewById<FollowButtonView>(R.id.follow_animation_view)
        animationView?.setFollowingState(viewModel.uiState.value.isFollowing)
        animationView?.visibility = View.GONE
        updateFollowButtonUI(viewModel.uiState.value.isFollowing)
    }

    private fun startFollowLoadingAnimation() {
        isFollowLoading = true
        val animationView = roomInfoOverlay.findViewById<FollowButtonView>(R.id.follow_animation_view)
        val contentView = roomInfoOverlay.findViewById<View>(R.id.follow_content)
        // 动画期间清除内容背景，以露出底部的动画视图
        contentView?.setBackgroundResource(0)
        animationView?.visibility = View.VISIBLE
        // 关注时填充，取关时褪去
        animationView?.animateToState(!viewModel.uiState.value.isFollowing, LONG_PRESS_THRESHOLD_MS)
    }

    // ---------------- 错误与生命周期 ----------------

    private fun showError(message: String) {
        loadingProgress.visibility = View.GONE
        errorText.visibility = View.VISIBLE
        errorText.text = message
        ToastHelper.showTextToast(this, message)
    }

    override fun onPause() {
        super.onPause()
        playerManager.pause()
    }

    override fun onResume() {
        super.onResume()
        playerManager.resume()
    }

    override fun onDestroy() {
        super.onDestroy()
        roomInfoOverlay.removeCallbacks(followActionRunnable)
        playerManager.release()
        if (::simpleDanmuView.isInitialized) {
            simpleDanmuView.clear()
        }
    }
}
