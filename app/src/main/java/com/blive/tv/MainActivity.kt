package com.blive.tv

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewParent
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.blive.tv.ui.login.LoginEvent
import com.blive.tv.ui.login.LoginRepository
import com.blive.tv.ui.login.LoginScreenState
import com.blive.tv.ui.login.LoginViewModel
import com.blive.tv.ui.login.LoginViewModelFactory
import com.blive.tv.ui.login.QrCodeBitmapFactory
import com.blive.tv.ui.main.LiveListState
import com.blive.tv.ui.main.LiveRoom
import com.blive.tv.ui.main.LiveRoomAdapter
import com.blive.tv.ui.main.MainFocusNavigator
import com.blive.tv.ui.main.MainScreenState
import com.blive.tv.ui.main.MainTabType
import com.blive.tv.ui.main.MainRepository
import com.blive.tv.ui.main.MainUiRenderer
import com.blive.tv.ui.main.MainViewModel
import com.blive.tv.ui.main.MainViewModelFactory
import com.blive.tv.ui.main.MainViewRefs
import com.blive.tv.ui.main.UserProfile
import com.blive.tv.ui.play.LivePlayActivity
import com.blive.tv.ui.settings.SettingsDialogFragment
import com.blive.tv.utils.ToastHelper
import com.blive.tv.utils.TokenManager
import com.bumptech.glide.Glide
import com.google.zxing.WriterException
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private data class GridRestoreState(
        val position: Int = 0,
        val roomId: Long? = null
    )

    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(MainRepository())
    }

    private val loginViewModel: LoginViewModel by viewModels {
        LoginViewModelFactory(LoginRepository(applicationContext))
    }

    private lateinit var viewRefs: MainViewRefs
    private lateinit var uiRenderer: MainUiRenderer
    private lateinit var focusNavigator: MainFocusNavigator
    private lateinit var liveRoomAdapter: LiveRoomAdapter
    
    // User Profile
    private lateinit var userNicknameView: TextView
    private lateinit var userAvatarView: ImageView
    private lateinit var userBadgeContainer: LinearLayout
    private lateinit var userVipTagView: TextView
    private lateinit var userLevelTagView: TextView
    private lateinit var btnSettings: FrameLayout
    private lateinit var btnLogout: FrameLayout

    // Login UI
    private lateinit var qrCodeImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var countdownText: TextView
    private val qrCodeFactory = QrCodeBitmapFactory(220)
    private var lastQrCodeUrl: String? = null

    private var lastClickedRoomId: Long = -1L
    private var lastRenderedRoomKey: String = ""
    private var lastAdapterDataKey: String = ""
    private var currentLiveListState: LiveListState = LiveListState.Loading
    private var currentTab: MainTabType = MainTabType.Login
    private val tabGridRestoreStates = mutableMapOf(
        MainTabType.Recommend to GridRestoreState(),
        MainTabType.Following to GridRestoreState()
    )
    private var pendingGridRestoreTab: MainTabType? = null
    private var lastPlayEntryTab: MainTabType? = null
    private var lastPlayEntryRoomId: Long = -1L
    private var pendingRestoreFromPlay: Boolean = false
    private var pendingRestoreFollowingRoom: Boolean = false
    private var pendingRestoreRecommendTab: Boolean = false
    private var lastBackPressedAt = 0L
    private val backPressExitWindowMs = 3000L
    private val livePlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingRestoreFromPlay = true
            restoreFocusAfterPlayReturn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        initGrid()
        initControllers()
        setupListeners()
        observeViewModel()
        refreshByLoginState()
    }

    override fun onResume() {
        super.onResume()
        refreshByLoginState(fromResume = true)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && TokenManager.isLoggedIn(this)) {
            viewModel.refreshCurrentTab(force = true)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val now = System.currentTimeMillis()
            if (now - lastBackPressedAt <= backPressExitWindowMs) {
                finishAffinity()
                return true
            }
            lastBackPressedAt = now
            ToastHelper.showTextToast(this, "再按一次返回键退出应用")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun initViews() {
        val tabLogin = findViewById<View>(R.id.tab_login)
        val tabMine = findViewById<View>(R.id.tab_mine)
        val tabRecommend = findViewById<View>(R.id.tab_recommend)
        val tabFollowing = findViewById<View>(R.id.tab_following)
        val tabPartition = findViewById<View>(R.id.tab_partition)
        val tabFocusSlider = findViewById<View>(R.id.tab_focus_slider)

        val loginContainer = findViewById<View>(R.id.login_container)
        val mineContainer = findViewById<View>(R.id.mine_container)
        val gridContainer = findViewById<View>(R.id.grid_container)

        val gridTitle = findViewById<TextView>(R.id.grid_title)
        val gridView = findViewById<androidx.leanback.widget.VerticalGridView>(R.id.main_grid)
        val loadingContainer = findViewById<View>(R.id.live_list_loading_container)
        val emptyContainer = findViewById<View>(R.id.live_list_empty_container)
        val errorContainer = findViewById<View>(R.id.live_list_error_container)
        val emptyRefreshButton = findViewById<android.widget.ImageButton>(R.id.live_list_empty_refresh_button)
        val errorRefreshButton = findViewById<android.widget.ImageButton>(R.id.live_list_error_refresh_button)
        
        userNicknameView = findViewById(R.id.user_nickname)
        userAvatarView = findViewById(R.id.user_avatar)
        userBadgeContainer = findViewById(R.id.user_badge_container)
        userVipTagView = findViewById(R.id.user_vip_tag)
        userLevelTagView = findViewById(R.id.user_level_tag)
        btnSettings = findViewById(R.id.btn_settings)
        btnLogout = findViewById(R.id.btn_logout)

        qrCodeImage = findViewById(R.id.qr_code_image)
        statusText = findViewById(R.id.status_text)
        countdownText = findViewById(R.id.countdown_text)

        viewRefs = MainViewRefs(
            tabLogin = tabLogin,
            tabMine = tabMine,
            tabRecommend = tabRecommend,
            tabFollowing = tabFollowing,
            tabPartition = tabPartition,
            tabFocusSlider = tabFocusSlider,
            loginContainer = loginContainer,
            mineContainer = mineContainer,
            gridContainer = gridContainer,
            gridTitle = gridTitle,
            gridView = gridView,
            loadingContainer = loadingContainer,
            emptyContainer = emptyContainer,
            errorContainer = errorContainer,
            emptyRefreshButton = emptyRefreshButton,
            errorRefreshButton = errorRefreshButton
        )
    }

    private fun initGrid() {
        val gridColumnCount = resolveGridColumnCount()
        liveRoomAdapter = LiveRoomAdapter(
            columnCount = gridColumnCount,
            onFirstRowUp = {
                Unit
            },
            onRoomClicked = { roomId ->
                lastClickedRoomId = roomId
                lastPlayEntryTab = currentTab
                lastPlayEntryRoomId = roomId
                val clickedPosition = liveRoomAdapter.getPositionByRoomId(roomId)
                if (clickedPosition >= 0) {
                    updateGridRestoreState(currentTab, clickedPosition, roomId)
                }
                val intent = Intent(this, LivePlayActivity::class.java)
                intent.putExtra("room_id", roomId)
                livePlayLauncher.launch(intent)
            },
            onNavigateToTab = { focusCurrentTab() },
            onRoomFocused = { position, roomId ->
                updateGridRestoreState(currentTab, position, roomId)
            }
        )
        viewRefs.gridView.adapter = liveRoomAdapter
        viewRefs.gridView.itemAnimator = null
        viewRefs.gridView.setNumColumns(gridColumnCount)
        viewRefs.gridView.overScrollMode = View.OVER_SCROLL_NEVER
        viewRefs.gridView.setScrollEnabled(true)
        viewRefs.gridView.isFocusable = true
        viewRefs.gridView.isFocusableInTouchMode = true
    }

    private fun resolveGridColumnCount(): Int {
        val smallestScreenWidthDp = resources.configuration.smallestScreenWidthDp
        return when {
            smallestScreenWidthDp >= 960 -> 5
            smallestScreenWidthDp >= 720 -> 4
            else -> 3
        }
    }

    private fun initControllers() {
        uiRenderer = MainUiRenderer(viewRefs)
        focusNavigator = MainFocusNavigator(
            gridView = viewRefs.gridView,
            emptyRefreshButton = viewRefs.emptyRefreshButton,
            errorRefreshButton = viewRefs.errorRefreshButton,
            btnSettings = btnSettings,
            btnLogout = btnLogout
        )
    }

    private fun setupListeners() {
        viewRefs.emptyRefreshButton.setOnClickListener {
            viewModel.refreshCurrentTab()
        }
        viewRefs.errorRefreshButton.setOnClickListener {
            viewModel.refreshCurrentTab()
        }
        val contentBackToTabHandler = View.OnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                focusCurrentTab()
                true
            } else {
                false
            }
        }
        viewRefs.emptyRefreshButton.setOnKeyListener(contentBackToTabHandler)
        viewRefs.errorRefreshButton.setOnKeyListener(contentBackToTabHandler)
        
        val tabFocusHandler = View.OnFocusChangeListener { v, hasFocus ->
            val iconView = (v as? android.view.ViewGroup)?.getChildAt(0)
            val textView = (v as? android.view.ViewGroup)?.getChildAt(1)

            if (hasFocus) {
                // 1. Icon/Text 切换动画: 从中间慢慢变大/缩小
                iconView?.animate()?.scaleX(0f)?.scaleY(0f)?.alpha(0f)?.setDuration(200)?.withEndAction {
                    iconView.visibility = View.INVISIBLE
                }?.start()
                
                textView?.visibility = View.VISIBLE
                textView?.scaleX = 0f
                textView?.scaleY = 0f
                textView?.alpha = 0f
                textView?.animate()?.scaleX(1f)?.scaleY(1f)?.alpha(1f)?.setDuration(200)?.start()

                // 2. 焦点滑块动画，使用 post 确保 View 已经完成布局和测量
                v.post {
                    animateTabSlider(v)
                }

                val viewName = runCatching { resources.getResourceEntryName(v.id) }.getOrDefault(v.id.toString())
                Log.d("MainFocus", "Tab获得焦点 viewId=${v.id} name=$viewName")
                val targetTab = when (v.id) {
                    R.id.tab_login -> MainTabType.Login
                    R.id.tab_mine -> MainTabType.Mine
                    R.id.tab_recommend -> MainTabType.Recommend
                    R.id.tab_following -> MainTabType.Following
                    R.id.tab_partition -> MainTabType.Partition
                    else -> currentTab
                }
                if (targetTab != currentTab) {
                    pendingGridRestoreTab = targetTab.takeIf { isLiveListTab(it) }
                    viewModel.switchTab(targetTab)
                }
            } else {
                 // 失去焦点时恢复 Icon 隐藏 Text
                 iconView?.visibility = View.VISIBLE
                 iconView?.scaleX = 0f
                 iconView?.scaleY = 0f
                 iconView?.alpha = 0f
                 iconView?.animate()?.scaleX(1f)?.scaleY(1f)?.alpha(1f)?.setDuration(200)?.start()
                 
                 textView?.animate()?.scaleX(0f)?.scaleY(0f)?.alpha(0f)?.setDuration(200)?.withEndAction {
                     textView.visibility = View.INVISIBLE
                 }?.start()

                // 检查是否所有 Tab 都失去了焦点，如果是则隐藏滑块
                v.postDelayed({
                    val currentFocus = currentFocus
                    val tabs = listOf(viewRefs.tabLogin, viewRefs.tabMine, viewRefs.tabRecommend, viewRefs.tabFollowing, viewRefs.tabPartition)
                    if (currentFocus !in tabs) {
                        hideTabSlider()
                    }
                }, 50)
            }
        }
        viewRefs.tabLogin.onFocusChangeListener = tabFocusHandler
        viewRefs.tabMine.onFocusChangeListener = tabFocusHandler
        viewRefs.tabRecommend.onFocusChangeListener = tabFocusHandler
        viewRefs.tabFollowing.onFocusChangeListener = tabFocusHandler
        viewRefs.tabPartition.onFocusChangeListener = tabFocusHandler

        val tabKeyHandler = View.OnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@OnKeyListener false
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    focusNavigator.focusContent(
                        state = currentLiveListState,
                        tab = currentTab,
                        gridItemCount = liveRoomAdapter.itemCount,
                        targetPosition = resolveRestorePosition(currentTab, liveRoomAdapter.getCurrentData()) ?: 0
                    )
                    true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> moveTabFocus(v.id, moveDown = true)
                KeyEvent.KEYCODE_DPAD_UP -> moveTabFocus(v.id, moveDown = false)
                else -> false
            }
        }
        viewRefs.tabLogin.setOnKeyListener(tabKeyHandler)
        viewRefs.tabMine.setOnKeyListener(tabKeyHandler)
        viewRefs.tabRecommend.setOnKeyListener(tabKeyHandler)
        viewRefs.tabFollowing.setOnKeyListener(tabKeyHandler)
        viewRefs.tabPartition.setOnKeyListener(tabKeyHandler)

        btnSettings.setOnClickListener {
            if (!supportFragmentManager.isStateSaved && !isFinishing && !isDestroyed) {
                SettingsDialogFragment().show(supportFragmentManager, "settings")
            }
        }
        btnSettings.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                viewRefs.tabMine.requestFocus()
                true
            } else {
                false
            }
        }
        btnLogout.setOnClickListener {
            TokenManager.clearToken(this)
            refreshByLoginState()
            ToastHelper.showTextToast(this, "退出登录成功")
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.screenState.collect { state ->
                        val previousTab = currentTab
                        currentTab = state.selectedTab
                        val activeRooms = if (state.selectedTab == MainTabType.Following) {
                            state.followingRooms
                        } else {
                            state.recommendRooms
                        }
                        val activeListState = if (state.selectedTab == MainTabType.Following) {
                            state.followingListState
                        } else {
                            state.recommendListState
                        }
                        currentLiveListState = activeListState

                        uiRenderer.renderLoginState(state.isLoggedIn)
                        uiRenderer.renderTabState(state.selectedTab)
                        bindUserProfile(state.userProfile)
                        
                        if (state.selectedTab == MainTabType.Recommend || state.selectedTab == MainTabType.Following) {
                            if (previousTab != state.selectedTab && activeListState == LiveListState.Content) {
                                prepositionGrid(state.selectedTab, activeRooms)
                            }
                            liveRoomAdapter.updateData(activeRooms)
                            uiRenderer.renderLiveListState(activeListState)
                            if (activeListState == LiveListState.Content) {
                                val roomKey = buildRoomKey(activeRooms)
                                val shouldRestoreGrid = previousTab != state.selectedTab ||
                                    roomKey != lastRenderedRoomKey ||
                                    pendingGridRestoreTab == state.selectedTab ||
                                    pendingRestoreFollowingRoom
                                if (shouldRestoreGrid) {
                                    restoreGridFocus(state.selectedTab, activeRooms)
                                }
                                lastRenderedRoomKey = roomKey
                            } else {
                                lastRenderedRoomKey = ""
                            }
                        }
                        handlePendingPlayRestore(state)

                        if (state.selectedTab == MainTabType.Login && previousTab != MainTabType.Login) {
                            loginViewModel.startLoginFlow()
                        } else if (state.selectedTab != MainTabType.Login && previousTab == MainTabType.Login) {
                            loginViewModel.stopLoginFlow()
                        }

                        // Set focus to the current tab if we just switched state
                        if (previousTab != currentTab) {
                            focusCurrentTab()
                        }
                    }
                }
                launch {
                    viewModel.toastEvent.collect { message ->
                        ToastHelper.showTextToast(this@MainActivity, message)
                    }
                }
                launch {
                    loginViewModel.screenState.collect { state ->
                        renderLoginStateUI(state)
                    }
                }
                launch {
                    loginViewModel.eventFlow.collect { event ->
                        when (event) {
                            is LoginEvent.ShowToast -> ToastHelper.showTextToast(this@MainActivity, event.message)
                            is LoginEvent.NavigateToMain -> refreshByLoginState()
                        }
                    }
                }
            }
        }
    }

    private fun renderLoginStateUI(state: LoginScreenState) {
        statusText.text = state.statusText
        countdownText.text = state.countdownText
        val qrUrl = state.qrCodeUrl
        if (qrUrl != null && qrUrl != lastQrCodeUrl) {
            try {
                qrCodeImage.setImageBitmap(qrCodeFactory.create(qrUrl))
                lastQrCodeUrl = qrUrl
            } catch (e: WriterException) {
                Log.e("MainActivity", "生成二维码失败", e)
                ToastHelper.showTextToast(this, "生成二维码失败")
            }
        }
    }

    private fun bindUserProfile(profile: UserProfile?) {
        if (profile == null) {
            userNicknameView.text = ""
            userAvatarView.setImageResource(android.R.drawable.ic_media_play)
            userBadgeContainer.visibility = View.GONE
            return
        }
        userNicknameView.text = profile.nickname
        val hasVip = !profile.vipLabel.isNullOrEmpty()
        val hasLevel = profile.level > 0
        if (hasVip || hasLevel) {
            userBadgeContainer.visibility = View.VISIBLE
            if (hasVip) {
                userVipTagView.visibility = View.VISIBLE
                userVipTagView.text = profile.vipLabel
            } else {
                userVipTagView.visibility = View.GONE
            }
            if (hasLevel) {
                userLevelTagView.visibility = View.VISIBLE
                userLevelTagView.text = "LV${profile.level}"
            } else {
                userLevelTagView.visibility = View.GONE
            }
        } else {
            userBadgeContainer.visibility = View.GONE
        }
        if (profile.avatarUrl.isNotEmpty()) {
            Glide.with(this)
                .load(profile.avatarUrl)
                .placeholder(android.R.drawable.ic_media_play)
                .error(android.R.drawable.ic_media_play)
                .circleCrop()
                .into(userAvatarView)
        } else {
            userAvatarView.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun restoreGridFocus(tab: MainTabType, liveRooms: List<LiveRoom>) {
        if (liveRooms.isEmpty()) {
            return
        }
        val targetPosition = resolveRestorePosition(tab, liveRooms)
        if (targetPosition == null) {
            if (tab == MainTabType.Following && pendingRestoreFollowingRoom) {
                pendingRestoreFollowingRoom = false
                viewRefs.tabFollowing.post { viewRefs.tabFollowing.requestFocus() }
            }
            return
        }
        val targetRoomId = liveRooms.getOrNull(targetPosition)?.roomId
        if (targetRoomId != null) {
            updateGridRestoreState(tab, targetPosition, targetRoomId)
        }
        viewRefs.gridView.post {
            viewRefs.gridView.setSelectedPosition(targetPosition)
            if (pendingGridRestoreTab == tab) {
                pendingGridRestoreTab = null
            }
            if (tab == MainTabType.Following && pendingRestoreFollowingRoom) {
                pendingRestoreFollowingRoom = false
            }
            if (shouldRequestGridFocus() || (tab == MainTabType.Following && lastPlayEntryRoomId == targetRoomId)) {
                viewRefs.gridView.post {
                    viewRefs.gridView.findViewHolderForAdapterPosition(targetPosition)?.itemView?.requestFocus()
                }
            }
        }
    }

    private fun moveTabFocus(currentTabId: Int, moveDown: Boolean): Boolean {
        val visibleTabs = buildList {
            if (viewRefs.tabLogin.visibility == View.VISIBLE) {
                add(viewRefs.tabLogin)
            }
            if (viewRefs.tabFollowing.visibility == View.VISIBLE) {
                add(viewRefs.tabFollowing)
            }
            if (viewRefs.tabRecommend.visibility == View.VISIBLE) {
                add(viewRefs.tabRecommend)
            }
            if (viewRefs.tabPartition.visibility == View.VISIBLE) {
                add(viewRefs.tabPartition)
            }
            if (viewRefs.tabMine.visibility == View.VISIBLE) {
                add(viewRefs.tabMine)
            }
        }
        if (visibleTabs.size <= 1) {
            return true
        }
        val currentIndex = visibleTabs.indexOfFirst { it.id == currentTabId }
        if (currentIndex < 0) {
            return false
        }
        val targetIndex = if (moveDown) {
            (currentIndex + 1).coerceAtMost(visibleTabs.lastIndex)
        } else {
            (currentIndex - 1).coerceAtLeast(0)
        }
        visibleTabs[targetIndex].requestFocus()
        return true
    }

    private fun shouldRequestGridFocus(): Boolean {
        val focusedView = currentFocus ?: return false
        if (focusedView == viewRefs.gridView) {
            return true
        }
        if (focusedView == viewRefs.emptyRefreshButton || focusedView == viewRefs.errorRefreshButton) {
            return true
        }
        var parent: ViewParent? = focusedView.parent
        while (parent != null) {
            if (parent == viewRefs.gridView) {
                return true
            }
            parent = parent.parent
        }
        return false
    }

    private fun isLiveListTab(tab: MainTabType): Boolean {
        return tab == MainTabType.Recommend || tab == MainTabType.Following
    }

    private fun buildRoomKey(liveRooms: List<LiveRoom>): String {
        return liveRooms.joinToString(separator = ",") { it.roomId.toString() }
    }

    private fun updateGridRestoreState(tab: MainTabType, position: Int, roomId: Long?) {
        if (!isLiveListTab(tab) || position == RecyclerView.NO_POSITION || position < 0) {
            return
        }
        tabGridRestoreStates[tab] = GridRestoreState(
            position = position,
            roomId = roomId
        )
    }

    private fun resolveRestorePosition(tab: MainTabType, liveRooms: List<LiveRoom>): Int? {
        if (liveRooms.isEmpty()) {
            return null
        }
        if (tab == MainTabType.Following && pendingRestoreFollowingRoom) {
            val playRestoreIndex = liveRooms.indexOfFirst { it.roomId == lastPlayEntryRoomId }
            if (playRestoreIndex >= 0) {
                return playRestoreIndex
            }
            return null
        }
        val restoreState = tabGridRestoreStates[tab] ?: GridRestoreState()
        restoreState.roomId?.let { roomId ->
            val matchedIndex = liveRooms.indexOfFirst { it.roomId == roomId }
            if (matchedIndex >= 0) {
                return matchedIndex
            }
        }
        return restoreState.position.coerceIn(0, liveRooms.lastIndex)
    }

    private fun prepositionGrid(tab: MainTabType, liveRooms: List<LiveRoom>) {
        val targetPosition = resolveRestorePosition(tab, liveRooms) ?: 0
        viewRefs.gridView.scrollToPosition(targetPosition)
    }

    private fun focusCurrentTab() {
        when (currentTab) {
            MainTabType.Login -> viewRefs.tabLogin.requestFocus()
            MainTabType.Mine -> viewRefs.tabMine.requestFocus()
            MainTabType.Recommend -> viewRefs.tabRecommend.requestFocus()
            MainTabType.Following -> viewRefs.tabFollowing.requestFocus()
            MainTabType.Partition -> viewRefs.tabPartition.requestFocus()
        }
    }

    private fun handlePendingPlayRestore(state: MainScreenState) {
        if (pendingRestoreRecommendTab && state.selectedTab == MainTabType.Recommend) {
            pendingRestoreRecommendTab = false
            viewRefs.tabRecommend.post { viewRefs.tabRecommend.requestFocus() }
        }
        if (pendingRestoreFollowingRoom && state.selectedTab == MainTabType.Following) {
            when (state.followingListState) {
                LiveListState.Content -> Unit
                LiveListState.Empty, LiveListState.Error -> {
                    pendingRestoreFollowingRoom = false
                    viewRefs.tabFollowing.post { viewRefs.tabFollowing.requestFocus() }
                }
                LiveListState.Loading -> Unit
            }
        }
    }

    private fun restoreFocusAfterPlayReturn() {
        if (!pendingRestoreFromPlay) {
            return
        }
        pendingRestoreFromPlay = false
        when (lastPlayEntryTab) {
            MainTabType.Following -> {
                pendingRestoreFollowingRoom = true
                pendingRestoreRecommendTab = false
                viewModel.switchTab(MainTabType.Following)
                viewModel.refreshFollowingRooms(force = true)
            }
            MainTabType.Recommend -> {
                pendingRestoreRecommendTab = true
                pendingRestoreFollowingRoom = false
                viewModel.switchTab(MainTabType.Recommend)
            }
            else -> Unit
        }
    }

    private fun animateTabSlider(targetView: View) {
        val slider = viewRefs.tabFocusSlider
        val container = findViewById<View>(R.id.main_tab_container)
        
        // 目标位置相对于滑块父容器 (FrameLayout)
        val targetY = container.top + targetView.top
        val targetX = container.left + targetView.left
        val targetWidth = targetView.width
        val targetHeight = targetView.height

        slider.animate().cancel()
        slider.alpha = 1f

        if (slider.visibility != View.VISIBLE) {
            slider.visibility = View.VISIBLE
            // Ensure layout params are updated before translation
            slider.layoutParams = (slider.layoutParams as FrameLayout.LayoutParams).apply {
                width = targetWidth
                height = targetHeight
            }
            slider.requestLayout()
            
            // Wait for layout update before setting translation to avoid incorrect positions
            slider.post {
                val updatedTargetY = container.top + targetView.top
                val updatedTargetX = container.left + targetView.left
                slider.translationX = updatedTargetX.toFloat()
                slider.translationY = updatedTargetY.toFloat()
            }
        } else {
            // 平滑移动到目标位置
            slider.animate()
                .translationY(targetY.toFloat())
                .translationX(targetX.toFloat())
                .setDuration(250)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
            
            // 如果宽度不一致（理论上一致），动画调整宽度
            if (slider.width != targetWidth && targetWidth > 0) {
                val animator = ValueAnimator.ofInt(slider.width, targetWidth)
                animator.addUpdateListener { 
                    slider.layoutParams.width = it.animatedValue as Int
                    slider.requestLayout()
                }
                animator.duration = 250
                animator.start()
            }
        }
    }

    private fun hideTabSlider() {
        viewRefs.tabFocusSlider.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { viewRefs.tabFocusSlider.visibility = View.INVISIBLE }
            .start()
    }

    private fun refreshByLoginState(fromResume: Boolean = false) {
        val isLoggedIn = TokenManager.isLoggedIn(this)
        viewModel.syncLoginState(isLoggedIn)
        if (isLoggedIn) {
            if (fromResume && currentTab != MainTabType.Login) {
                viewModel.refreshAfterResume()
            } else {
                viewModel.refreshAfterLogin()
            }
        } else {
            loginViewModel.startLoginFlow()
        }
    }
}
