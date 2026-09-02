package com.blive.tv.ui.play

import android.graphics.Rect
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
import androidx.recyclerview.widget.LinearLayoutManager
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
 */
class LivePlayActivity : AppCompatActivity() {

    private val viewModel: LivePlayViewModel by viewModels()
    private lateinit var playerManager: PlayerManager

    private lateinit var playerView: com.google.android.exoplayer2.ui.PlayerView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var settingsPanel: View
    private lateinit var playSettingsRecyclerView: RecyclerView
    private lateinit var danmuSettingsRecyclerView: RecyclerView
    private lateinit var simpleDanmuView: SimpleDanmuView
    private lateinit var roomInfoOverlay: View
    private lateinit var roomInfoController: RoomInfoOverlayController

    private lateinit var playSettingsAdapter: PlaySettingsCategoryAdapter
    private lateinit var danmuSettingsAdapter: PlaySettingsCategoryAdapter
    private lateinit var settingsPanelController: PlaySettingsPanelController

    /** 设置面板展开状态（纯 UI 状态，跟随面板生命周期） */
    private var currentExpandedCategory: String? = null

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
        private const val CATEGORY_QUALITY = "quality"
        private const val CATEGORY_CDN = "cdn"
        private const val CATEGORY_CODEC = "codec"
        private const val CATEGORY_DANMU_ENABLE = "danmu_enable"
        private const val CATEGORY_DANMU_OPACITY = "danmu_opacity"
        private const val CATEGORY_DANMU_SIZE = "danmu_size"
        private const val BACK_PRESS_EXIT_WINDOW_MS = 3000L
        private const val LONG_PRESS_THRESHOLD_MS = 2000L

        private val DANMU_OPACITY_OPTIONS = listOf(0.25f, 0.5f, 0.75f, 1.0f)
        private val DANMU_SIZE_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f)
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
        setupRecyclerViews()
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
        playSettingsRecyclerView = findViewById(R.id.play_settings_recycler_view)
        danmuSettingsRecyclerView = findViewById(R.id.danmu_settings_recycler_view)
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

    private fun setupRecyclerViews() {
        playSettingsRecyclerView.layoutManager = LinearLayoutManager(this)
        danmuSettingsRecyclerView.layoutManager = LinearLayoutManager(this)

        val spacingDecoration = object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(
                outRect: Rect,
                view: View,
                parent: RecyclerView,
                state: RecyclerView.State
            ) {
                val position = parent.getChildAdapterPosition(view)
                val adapter = parent.adapter as? PlaySettingsCategoryAdapter ?: return
                if (position != RecyclerView.NO_POSITION) {
                    val viewType = adapter.getItemViewType(position)
                    // 分类项之间增加间距（第一项除外），0 为 TYPE_CATEGORY
                    if (viewType == 0 && position > 0) {
                        outRect.top = (16 * view.context.resources.displayMetrics.density).toInt()
                    }
                }
            }
        }
        playSettingsRecyclerView.addItemDecoration(spacingDecoration)
        danmuSettingsRecyclerView.addItemDecoration(spacingDecoration)

        playSettingsAdapter = PlaySettingsCategoryAdapter(emptyList()) { item ->
            onSettingsItemClicked(item)
        }
        danmuSettingsAdapter = PlaySettingsCategoryAdapter(emptyList()) { item ->
            onSettingsItemClicked(item)
        }
        playSettingsRecyclerView.adapter = playSettingsAdapter
        danmuSettingsRecyclerView.adapter = danmuSettingsAdapter
    }

    private fun setupControllers() {
        settingsPanelController = PlaySettingsPanelController(
            settingsPanel = settingsPanel,
            playerView = playerView,
            playSettingsRecyclerView = playSettingsRecyclerView,
            playSettingsAdapter = playSettingsAdapter,
            qualityCategoryId = CATEGORY_QUALITY,
            logTag = "LivePlayActivity"
        )
        roomInfoController = RoomInfoOverlayController(
            roomInfoOverlay = roomInfoOverlay,
            playerView = playerView,
            logTag = "LivePlayActivity"
        )
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
                // 播放设置面板数据
                launch {
                    viewModel.uiState
                        .map { PanelInput(it.qualityOptions, it.cdnOptions, it.codecOptions, it.selectedQn, it.selectedCdnHost, it.selectedCodec) }
                        .distinctUntilChanged()
                        .collect { updatePlayCategories() }
                }
                // 弹幕设置与弹幕 View
                launch {
                    viewModel.uiState
                        .map { DanmuSettings(it.danmuEnabled, it.danmuOpacity, it.danmuSize, it.danmuSpeed) }
                        .distinctUntilChanged()
                        .collect { settings ->
                            simpleDanmuView.isDanmuEnabled = settings.enabled
                            simpleDanmuView.danmuAlpha = settings.opacity
                            simpleDanmuView.danmuSizeScale = settings.size
                            simpleDanmuView.danmuSpeedScale = settings.speed
                            updateDanmuCategories()
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

    private data class PanelInput(
        val qualityOptions: List<QualityOption>,
        val cdnOptions: List<CdnOption>,
        val codecOptions: List<CodecOption>,
        val selectedQn: Int,
        val selectedCdnHost: String,
        val selectedCodec: String
    )

    private data class DanmuSettings(
        val enabled: Boolean,
        val opacity: Float,
        val size: Float,
        val speed: Float
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

    // ---------------- 设置面板 ----------------

    private fun onSettingsItemClicked(item: SettingsItem) {
        when (item) {
            is PlaySettingsCategory -> onCategoryClicked(item)
            is PlaySettingsOption -> onOptionClicked(item)
        }
    }

    private fun onCategoryClicked(category: PlaySettingsCategory) {
        if (currentExpandedCategory == category.id) {
            collapseAllCategories(focusCategoryId = category.id)
        } else {
            currentExpandedCategory = category.id
            refreshPanels(focusTargetId = null, shouldFocusSelectedOption = true)
        }
    }

    private fun onOptionClicked(option: PlaySettingsOption) {
        when (option.categoryId) {
            CATEGORY_QUALITY -> viewModel.selectQuality(option.id.toInt())
            CATEGORY_CDN -> viewModel.selectCdn(option.id)
            CATEGORY_CODEC -> viewModel.selectCodec(option.id)
            CATEGORY_DANMU_ENABLE -> viewModel.setDanmuEnabled(option.id == "1")
            CATEGORY_DANMU_OPACITY -> viewModel.setDanmuOpacity(option.id.toFloat())
            CATEGORY_DANMU_SIZE -> viewModel.setDanmuSize(option.id.toFloat())
        }
        refreshPanels(focusTargetId = option.id)
    }

    private fun collapseAllCategories(focusCategoryId: String? = null) {
        currentExpandedCategory = null
        refreshPanels(focusTargetId = focusCategoryId)
    }

    private fun refreshPanels(focusTargetId: String? = null, shouldFocusSelectedOption: Boolean = false) {
        updatePlayCategories()
        updateDanmuCategories()
        if (focusTargetId != null || shouldFocusSelectedOption) {
            playSettingsRecyclerView.post {
                restoreFocus(playSettingsRecyclerView, playSettingsAdapter, focusTargetId, shouldFocusSelectedOption)
            }
            danmuSettingsRecyclerView.post {
                restoreFocus(danmuSettingsRecyclerView, danmuSettingsAdapter, focusTargetId, shouldFocusSelectedOption)
            }
        }
    }

    private fun updatePlayCategories() {
        val state = viewModel.uiState.value
        val categories = listOf(
            PlaySettingsCategory(
                id = CATEGORY_QUALITY,
                name = "画质",
                currentValue = state.qualityOptions.find { it.qn == state.selectedQn }?.name ?: "未知",
                isExpanded = currentExpandedCategory == CATEGORY_QUALITY
            ),
            PlaySettingsCategory(
                id = CATEGORY_CDN,
                name = "线路",
                currentValue = state.cdnOptions.find { it.host == state.selectedCdnHost }?.cdnName ?: "未知",
                isExpanded = currentExpandedCategory == CATEGORY_CDN
            ),
            PlaySettingsCategory(
                id = CATEGORY_CODEC,
                name = "编码",
                currentValue = state.codecOptions.find { it.codecName == state.selectedCodec }?.displayName ?: "未知",
                isExpanded = currentExpandedCategory == CATEGORY_CODEC
            )
        )

        val displayList = mutableListOf<SettingsItem>()
        for (category in categories) {
            displayList.add(category)
            if (category.isExpanded) {
                when (category.id) {
                    CATEGORY_QUALITY -> displayList.addAll(state.qualityOptions.map {
                        PlaySettingsOption(it.qn.toString(), it.name, it.qn == state.selectedQn, category.id)
                    })
                    CATEGORY_CDN -> displayList.addAll(state.cdnOptions.map {
                        PlaySettingsOption(it.host, it.cdnName, it.host == state.selectedCdnHost, category.id)
                    })
                    CATEGORY_CODEC -> displayList.addAll(state.codecOptions.map {
                        PlaySettingsOption(it.codecName, it.displayName, it.codecName == state.selectedCodec, category.id)
                    })
                }
            }
        }
        playSettingsAdapter.updateItems(displayList)
    }

    private fun updateDanmuCategories() {
        val state = viewModel.uiState.value
        // 吸附到最近的有效档位
        val snappedOpacity = DANMU_OPACITY_OPTIONS.minBy { kotlin.math.abs(it - state.danmuOpacity) }
        val snappedSize = DANMU_SIZE_OPTIONS.minBy { kotlin.math.abs(it - state.danmuSize) }

        val categories = listOf(
            PlaySettingsCategory(
                id = CATEGORY_DANMU_ENABLE,
                name = "开关",
                currentValue = if (state.danmuEnabled) "开启" else "关闭",
                isExpanded = currentExpandedCategory == CATEGORY_DANMU_ENABLE
            ),
            PlaySettingsCategory(
                id = CATEGORY_DANMU_OPACITY,
                name = "不透明度",
                currentValue = "${(snappedOpacity * 100).toInt()}%",
                isExpanded = currentExpandedCategory == CATEGORY_DANMU_OPACITY
            ),
            PlaySettingsCategory(
                id = CATEGORY_DANMU_SIZE,
                name = "大小",
                currentValue = "${(snappedSize * 100).toInt()}%",
                isExpanded = currentExpandedCategory == CATEGORY_DANMU_SIZE
            )
        )

        val displayList = mutableListOf<SettingsItem>()
        for (category in categories) {
            displayList.add(category)
            if (category.isExpanded) {
                when (category.id) {
                    CATEGORY_DANMU_ENABLE -> {
                        displayList.add(PlaySettingsOption("1", "开启", state.danmuEnabled, category.id))
                        displayList.add(PlaySettingsOption("0", "关闭", !state.danmuEnabled, category.id))
                    }
                    CATEGORY_DANMU_OPACITY -> displayList.addAll(DANMU_OPACITY_OPTIONS.map {
                        PlaySettingsOption(it.toString(), "${(it * 100).toInt()}%", it == snappedOpacity, category.id)
                    })
                    CATEGORY_DANMU_SIZE -> displayList.addAll(DANMU_SIZE_OPTIONS.map {
                        PlaySettingsOption(it.toString(), "${(it * 100).toInt()}%", it == snappedSize, category.id)
                    })
                }
            }
        }
        danmuSettingsAdapter.updateItems(displayList)
    }

    private fun restoreFocus(
        recyclerView: RecyclerView,
        adapter: PlaySettingsCategoryAdapter,
        targetId: String?,
        focusSelected: Boolean
    ) {
        val items = adapter.getItems()
        var position = -1

        if (targetId != null) {
            position = items.indexOfFirst { it.id == targetId }
        }

        if (position == -1 && focusSelected && currentExpandedCategory != null) {
            // 找到当前展开分类下选中的选项
            position = items.indexOfFirst {
                it is PlaySettingsOption && it.categoryId == currentExpandedCategory && it.isSelected
            }
            if (position == -1) {
                position = items.indexOfFirst {
                    it is PlaySettingsOption && it.categoryId == currentExpandedCategory
                }
            }
        }

        if (position != -1) {
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
            if (viewHolder != null) {
                viewHolder.itemView.requestFocus()
            } else {
                // ViewHolder 未创建（屏幕外），先滚动再聚焦
                recyclerView.scrollToPosition(position)
                recyclerView.post {
                    recyclerView.findViewHolderForAdapterPosition(position)
                        ?.itemView?.requestFocus()
                }
            }
        }
    }

    // ---------------- 按键处理 ----------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (settingsPanelController.recoverFocusIfNeeded(keyCode, currentFocus)) {
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                settingsPanelController.toggle {
                    collapseAllCategories()
                }
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
                    if (currentExpandedCategory != null) {
                        collapseAllCategories(focusCategoryId = currentExpandedCategory)
                        return true
                    }
                    settingsPanelController.hide {
                        collapseAllCategories()
                    }
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
