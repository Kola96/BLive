package com.blive.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewParent
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
    private var lastPlayEntryTab: MainTabType? = null
    private var lastPlayEntryRoomId: Long = -1L
    private var pendingRestoreFromPlay: Boolean = false
    private var pendingRestoreFollowingRoom: Boolean = false
    private var pendingRestoreRecommendTab: Boolean = false
    private val gridColumnCount = 5
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
        val tabLogin = findViewById<TextView>(R.id.tab_login)
        val tabMine = findViewById<TextView>(R.id.tab_mine)
        val tabRecommend = findViewById<TextView>(R.id.tab_recommend)
        val tabFollowing = findViewById<TextView>(R.id.tab_following)
        val tabPartition = findViewById<TextView>(R.id.tab_partition)

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
        liveRoomAdapter = LiveRoomAdapter(
            onFirstRowUp = {
                Unit
            },
            onRoomClicked = { roomId ->
                lastClickedRoomId = roomId
                lastPlayEntryTab = currentTab
                lastPlayEntryRoomId = roomId
                val intent = Intent(this, LivePlayActivity::class.java)
                intent.putExtra("room_id", roomId)
                livePlayLauncher.launch(intent)
            },
            onNavigateToTab = { focusCurrentTab() }
        )
        viewRefs.gridView.adapter = liveRoomAdapter
        viewRefs.gridView.itemAnimator = null
        viewRefs.gridView.setNumColumns(gridColumnCount)
        viewRefs.gridView.overScrollMode = View.OVER_SCROLL_NEVER
        viewRefs.gridView.setScrollEnabled(true)
        viewRefs.gridView.isFocusable = true
        viewRefs.gridView.isFocusableInTouchMode = true
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
            if (hasFocus) {
                val viewName = runCatching { resources.getResourceEntryName(v.id) }.getOrDefault(v.id.toString())
                Log.d("MainFocus", "Tab获得焦点 viewId=${v.id} name=$viewName")
                when (v.id) {
                    R.id.tab_login -> viewModel.switchTab(MainTabType.Login)
                    R.id.tab_mine -> viewModel.switchTab(MainTabType.Mine)
                    R.id.tab_recommend -> viewModel.switchTab(MainTabType.Recommend)
                    R.id.tab_following -> viewModel.switchTab(MainTabType.Following)
                    R.id.tab_partition -> viewModel.switchTab(MainTabType.Partition)
                }
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
                    focusNavigator.focusContent(currentLiveListState, currentTab, liveRoomAdapter.itemCount)
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
                            val previousRoomCount = liveRoomAdapter.itemCount
                            liveRoomAdapter.updateData(activeRooms)
                            uiRenderer.renderLiveListState(activeListState)
                            if (activeListState == LiveListState.Content) {
                                // 只有在首次加载（从空变有）或发生完全不同的全量刷新时才需要主动恢复焦点。
                                // 如果是分页追加（有旧数据且追加新数据），DiffUtil 会保持焦点，不需要 restoreGridFocus 强行抢占。
                                if (previousRoomCount == 0 || lastRenderedRoomKey.isEmpty()) {
                                    restoreGridFocus(activeRooms)
                                } else {
                                    // 仅更新 key，不强制移动焦点
                                    lastRenderedRoomKey = activeRooms.joinToString(separator = ",") { it.roomId.toString() }
                                }
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

    private fun restoreGridFocus(liveRooms: List<LiveRoom>) {
        if (liveRooms.isEmpty()) {
            return
        }
        val roomKey = liveRooms.joinToString(separator = ",") { it.roomId.toString() }
        if (roomKey == lastRenderedRoomKey) {
            return
        }
        lastRenderedRoomKey = roomKey
        viewRefs.gridView.postDelayed({
            var targetPosition = 0
            if (lastClickedRoomId != -1L) {
                val index = liveRooms.indexOfFirst { it.roomId == lastClickedRoomId }
                if (index >= 0) {
                    targetPosition = index
                }
            }
            viewRefs.gridView.setSelectedPosition(targetPosition)
            if (shouldRequestGridFocus()) {
                viewRefs.gridView.postDelayed({
                    viewRefs.gridView.findViewHolderForAdapterPosition(targetPosition)?.itemView?.requestFocus()
                }, 100)
            }
        }, 200)
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

    private fun focusCurrentTab() {
        when (currentTab) {
            MainTabType.Login -> viewRefs.tabLogin.requestFocus()
            MainTabType.Mine -> viewRefs.tabMine.requestFocus()
            MainTabType.Recommend -> viewRefs.tabRecommend.requestFocus()
            MainTabType.Following -> viewRefs.tabFollowing.requestFocus()
            MainTabType.Partition -> viewRefs.tabPartition.requestFocus()
        }
    }

    private fun handlePendingPlayRestore(state: com.blive.tv.ui.main.MainScreenState) {
        if (pendingRestoreRecommendTab && state.selectedTab == MainTabType.Recommend) {
            pendingRestoreRecommendTab = false
            viewRefs.tabRecommend.post { viewRefs.tabRecommend.requestFocus() }
        }
        if (pendingRestoreFollowingRoom && state.selectedTab == MainTabType.Following) {
            when (state.followingListState) {
                LiveListState.Content -> {
                    val targetIndex = state.followingRooms.indexOfFirst { it.roomId == lastPlayEntryRoomId }
                    pendingRestoreFollowingRoom = false
                    if (targetIndex >= 0) {
                        lastClickedRoomId = lastPlayEntryRoomId
                        viewRefs.gridView.post {
                            viewRefs.gridView.setSelectedPosition(targetIndex)
                            viewRefs.gridView.postDelayed({
                                viewRefs.gridView.findViewHolderForAdapterPosition(targetIndex)?.itemView?.requestFocus()
                            }, 100)
                        }
                    } else {
                        viewRefs.tabFollowing.post { viewRefs.tabFollowing.requestFocus() }
                    }
                }
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
