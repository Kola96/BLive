package com.blive.tv

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
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
import com.blive.tv.ui.main.AreaTagAdapter
import com.blive.tv.ui.main.AreaTagItem
import com.blive.tv.ui.main.FocusAction
import com.blive.tv.ui.main.FocusOrchestrator
import com.blive.tv.ui.main.FocusResolution
import com.blive.tv.ui.main.MainScreenState
import com.blive.tv.ui.main.MainTabType
import com.blive.tv.ui.main.MainRepository
import com.blive.tv.ui.main.MainUiRenderer
import com.blive.tv.ui.main.MainViewModel
import com.blive.tv.ui.main.MainViewModelFactory
import com.blive.tv.ui.main.MainViewRefs
import com.blive.tv.ui.main.MineActionTarget
import com.blive.tv.ui.main.UserProfile
import com.blive.tv.ui.play.LivePlayActivity
import com.blive.tv.ui.settings.SettingsDialogFragment
import com.blive.tv.utils.ToastHelper
import com.blive.tv.utils.TokenManager
import com.bumptech.glide.Glide
import com.google.zxing.WriterException
import kotlinx.coroutines.launch

import android.widget.EditText
import com.google.android.flexbox.FlexboxLayout
import android.view.LayoutInflater

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(MainRepository())
    }

    private val loginViewModel: LoginViewModel by viewModels {
        LoginViewModelFactory(LoginRepository(applicationContext))
    }

    private lateinit var viewRefs: MainViewRefs
    private lateinit var uiRenderer: MainUiRenderer
    private lateinit var focusOrchestrator: FocusOrchestrator
    private lateinit var liveRoomAdapter: LiveRoomAdapter
    private lateinit var level1AreaAdapter: AreaTagAdapter
    private lateinit var level2AreaAdapter: AreaTagAdapter
    
    // User Profile
    private lateinit var userNicknameView: TextView
    private lateinit var userAvatarView: ImageView
    private lateinit var userBadgeContainer: LinearLayout
    private lateinit var userVipTagView: TextView
    private lateinit var userLevelTagView: TextView
    private lateinit var btnSettings: FrameLayout
    private lateinit var btnLogout: FrameLayout

    // Search UI
    private lateinit var searchEditText: EditText
    private lateinit var btnSearch: FrameLayout
    private lateinit var searchHistoryContainer: View
    private lateinit var searchHistoryFlexbox: FlexboxLayout
    private lateinit var btnClearHistory: TextView

    // Login UI
    private lateinit var qrCodeImage: ImageView
    private lateinit var statusText: TextView
    private lateinit var countdownText: TextView
    private val qrCodeFactory = QrCodeBitmapFactory(220)
    private var lastQrCodeUrl: String? = null

    private var isLoggedIn: Boolean = false
    private var lastClickedRoomId: Long = -1L
    private var lastRenderedRoomKey: String = ""
    private var lastAdapterDataKey: String = ""
    private var currentLiveListState: LiveListState = LiveListState.Loading
    private var currentTab: MainTabType = MainTabType.Login
    private var isShowingSearchResult: Boolean = false
    private var lastPlayEntryTab: MainTabType? = null
    private var lastPlayEntryRoomId: Long = -1L
    private var pendingRestoreFromPlay: Boolean = false
    private var inFlightFocusToken: Long = 0L
    private val focusRetryAttempts = mutableMapOf<Long, Int>()
    private var lastBackPressedAt = 0L
    private val backPressExitWindowMs = 3000L
    private val focusRetryDelayMs = 32L
    private val maxFocusRetryAttempts = 12
    private val livePlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingRestoreFromPlay = true
            if (window.decorView.hasWindowFocus()) {
                window.decorView.post {
                    restoreFocusAfterPlayReturn()
                }
            }
        } else {
            pendingRestoreFromPlay = false
        }
    }

    private val searchLeftKeyHandler = View.OnKeyListener { _, keyCode, event ->
        if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            viewModel.requestTabFocus(MainTabType.Search)
            true
        } else {
            false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        initGrid()
        initAreaTags()
        initControllers()
        setupListeners()
        observeViewModel()
        refreshByLoginState()
    }

    private fun initAreaTags() {
        level1AreaAdapter = AreaTagAdapter(
            isLevel1 = true,
            onItemClick = { areaId -> viewModel.selectLevel1Area(areaId) },
            onNavigateBack = { viewModel.onNavigateBackToTab() },
            onItemFocused = { index ->
                viewModel.onPartitionLevel1Focused(index)
            }
        )
        level2AreaAdapter = AreaTagAdapter(
            isLevel1 = false,
            onItemClick = { areaId -> viewModel.selectLevel2Area(areaId) },
            onNavigateBack = { viewModel.onNavigateBackToTab() },
            onItemFocused = { index ->
                viewModel.onPartitionLevel2Focused(index)
            }
        )
        val level1AreaGrid = findViewById<androidx.leanback.widget.HorizontalGridView>(R.id.level1_area_grid)
        val level2AreaGrid = findViewById<androidx.leanback.widget.HorizontalGridView>(R.id.level2_area_grid)
        
        level1AreaGrid.adapter = level1AreaAdapter
        level1AreaGrid.itemAnimator = null
        level1AreaGrid.setNumRows(1)
        
        level2AreaGrid.adapter = level2AreaAdapter
        level2AreaGrid.itemAnimator = null
        level2AreaGrid.setNumRows(1)
    }

    override fun onResume() {
        super.onResume()
        refreshByLoginState(fromResume = true)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && pendingRestoreFromPlay) {
            window.decorView.post {
                restoreFocusAfterPlayReturn()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && TokenManager.isLoggedIn(this)) {
            viewModel.refreshCurrentTab(force = true)
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (currentTab == MainTabType.Search && viewModel.screenState.value.isShowingSearchResult) {
                viewModel.exitSearchResult()
                return true
            }
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
        val tabSearch = findViewById<View>(R.id.tab_search)
        val tabFocusSlider = findViewById<View>(R.id.tab_focus_slider)

        val loginContainer = findViewById<View>(R.id.login_container)
        val mineContainer = findViewById<View>(R.id.mine_container)
        val gridContainer = findViewById<View>(R.id.grid_container)
        val areaSelectorsContainer = findViewById<View>(R.id.area_selectors_container)
        val searchInputContainer = findViewById<View>(R.id.search_input_container)

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

        searchEditText = findViewById(R.id.search_edit_text)
        btnSearch = findViewById(R.id.btn_search)
        searchHistoryContainer = findViewById(R.id.search_history_container)
        searchHistoryFlexbox = findViewById(R.id.search_history_flexbox)
        btnClearHistory = findViewById(R.id.btn_clear_history)

        viewRefs = MainViewRefs(
            tabLogin = tabLogin,
            tabMine = tabMine,
            tabRecommend = tabRecommend,
            tabFollowing = tabFollowing,
            tabPartition = tabPartition,
            tabSearch = tabSearch,
            tabFocusSlider = tabFocusSlider,
            loginContainer = loginContainer,
            mineContainer = mineContainer,
            gridContainer = gridContainer,
            areaSelectorsContainer = areaSelectorsContainer,
            searchInputContainer = searchInputContainer,
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
                if (currentTab == MainTabType.Partition) {
                    viewModel.onPartitionGridBackRequested()
                }
            },
            onRoomClicked = { roomId ->
                lastClickedRoomId = roomId
                lastPlayEntryTab = currentTab
                lastPlayEntryRoomId = roomId
                val clickedPosition = liveRoomAdapter.getPositionByRoomId(roomId)
                if (clickedPosition >= 0) {
                    viewModel.onGridFocused(currentTab, clickedPosition, roomId)
                }
                val intent = Intent(this, LivePlayActivity::class.java)
                intent.putExtra("room_id", roomId)
                livePlayLauncher.launch(intent)
            },
            onNavigateToTab = { viewModel.onNavigateBackToTab() },
            onRoomFocused = { position, roomId ->
                viewModel.onGridFocused(currentTab, position, roomId)
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

    private fun resolveGridColumnCount(): Int = 4

    private fun initControllers() {
        uiRenderer = MainUiRenderer(viewRefs)
        focusOrchestrator = FocusOrchestrator()
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
                viewModel.onNavigateBackToTab()
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
            val targetTab = when (v.id) {
                R.id.tab_login -> MainTabType.Login
                R.id.tab_mine -> MainTabType.Mine
                R.id.tab_recommend -> MainTabType.Recommend
                R.id.tab_following -> MainTabType.Following
                R.id.tab_partition -> MainTabType.Partition
                R.id.tab_search -> MainTabType.Search
                else -> currentTab
            }

            if (hasFocus) {
                iconView?.animate()?.scaleX(0f)?.scaleY(0f)?.alpha(0f)?.setDuration(200)?.withEndAction {
                    iconView.visibility = View.INVISIBLE
                }?.start()

                textView?.visibility = View.VISIBLE
                textView?.scaleX = 0f
                textView?.scaleY = 0f
                textView?.alpha = 0f
                textView?.animate()?.scaleX(1f)?.scaleY(1f)?.alpha(1f)?.setDuration(200)?.start()

                v.post {
                    animateTabSlider(v)
                }

                val viewName = runCatching { resources.getResourceEntryName(v.id) }.getOrDefault(v.id.toString())
                Log.d("MainFocus", "Tab获得焦点 viewId=${v.id} name=$viewName")
                viewModel.onTabFocused(targetTab)
            } else {
                iconView?.visibility = View.VISIBLE
                iconView?.scaleX = 0f
                iconView?.scaleY = 0f
                iconView?.alpha = 0f
                iconView?.animate()?.scaleX(1f)?.scaleY(1f)?.alpha(1f)?.setDuration(200)?.start()
                
                textView?.animate()?.scaleX(0f)?.scaleY(0f)?.alpha(0f)?.setDuration(200)?.withEndAction {
                    textView.visibility = View.INVISIBLE
                }?.start()

                v.postDelayed({
                    val currentFocus = currentFocus
                    val tabs = listOf(viewRefs.tabLogin, viewRefs.tabMine, viewRefs.tabRecommend, viewRefs.tabFollowing, viewRefs.tabPartition, viewRefs.tabSearch)
                    if (currentFocus !in tabs) {
                        updateTabSliderStroke(false)
                    }
                }, 50)
            }
        }
        viewRefs.tabLogin.onFocusChangeListener = tabFocusHandler
        viewRefs.tabMine.onFocusChangeListener = tabFocusHandler
        viewRefs.tabRecommend.onFocusChangeListener = tabFocusHandler
        viewRefs.tabFollowing.onFocusChangeListener = tabFocusHandler
        viewRefs.tabPartition.onFocusChangeListener = tabFocusHandler
        viewRefs.tabSearch.onFocusChangeListener = tabFocusHandler

        val tabKeyHandler = View.OnKeyListener { v, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@OnKeyListener false
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val targetTab = when (v.id) {
                        R.id.tab_login -> MainTabType.Login
                        R.id.tab_mine -> MainTabType.Mine
                        R.id.tab_recommend -> MainTabType.Recommend
                        R.id.tab_following -> MainTabType.Following
                        R.id.tab_partition -> MainTabType.Partition
                        R.id.tab_search -> MainTabType.Search
                        else -> currentTab
                    }
                    Log.d("MainFocus", "Tab右移请求 tab=$targetTab")
                    viewModel.requestContentFocus(targetTab)
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
        viewRefs.tabSearch.setOnKeyListener(tabKeyHandler)

        btnSettings.setOnClickListener {
            if (!supportFragmentManager.isStateSaved && !isFinishing && !isDestroyed) {
                SettingsDialogFragment().show(supportFragmentManager, "settings")
            }
        }
        btnSettings.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                viewModel.requestTabFocus(MainTabType.Mine)
                true
            } else {
                false
            }
        }
        btnSettings.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                viewModel.onMineActionFocused(MineActionTarget.Settings)
            }
        }
        btnLogout.setOnClickListener {
            TokenManager.clearToken(this)
            refreshByLoginState()
            ToastHelper.showTextToast(this, "退出登录成功")
        }
        btnLogout.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                viewModel.onMineActionFocused(MineActionTarget.Logout)
            }
        }

        btnSearch.setOnClickListener {
            val keyword = searchEditText.text.toString()
            viewModel.performSearch(keyword)
        }

        searchEditText.setOnEditorActionListener { _, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val keyword = searchEditText.text.toString()
                viewModel.performSearch(keyword)
                true
            } else {
                false
            }
        }

        btnClearHistory.setOnClickListener {
            viewModel.clearSearchHistory()
        }

        // 搜索页面组件的焦点处理
        searchEditText.setOnKeyListener(searchLeftKeyHandler)
        searchEditText.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                viewModel.onSearchInputFocused()
            }
        }
        btnSearch.setOnKeyListener(searchLeftKeyHandler)
        btnClearHistory.setOnKeyListener(searchLeftKeyHandler)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.screenState.collect { state ->
                        val previousTab = currentTab
                        val previousIsLoggedIn = isLoggedIn
                        val previousShowingSearchResult = isShowingSearchResult
                        
                        currentTab = state.selectedTab
                        isLoggedIn = state.isLoggedIn
                        isShowingSearchResult = state.isShowingSearchResult
                        
                        val activeRooms = when (state.selectedTab) {
                            MainTabType.Following -> state.followingRooms
                            MainTabType.Partition -> state.partitionRooms
                            MainTabType.Search -> state.searchRooms
                            else -> state.recommendRooms
                        }
                        val activeListState = when (state.selectedTab) {
                            MainTabType.Following -> state.followingListState
                            MainTabType.Partition -> state.partitionListState
                            MainTabType.Search -> state.searchListState
                            else -> state.recommendListState
                        }
                        if (previousIsLoggedIn != state.isLoggedIn) {
                            uiRenderer.renderLoginState(state.isLoggedIn)
                        }

                        // ===== FOCUS RESCUE START =====
                        // 预测当前焦点的视图是否即将被隐藏（GONE），若是，则将焦点临时转移到当前的 Tab 上以防焦点意外漂移
                        var focusNeedsRescue = false
                        
                        val showLogin = state.selectedTab == MainTabType.Login
                        val showMine = state.selectedTab == MainTabType.Mine
                        val showGrid = state.selectedTab == MainTabType.Recommend || 
                            state.selectedTab == MainTabType.Following || 
                            state.selectedTab == MainTabType.Partition || 
                            (state.selectedTab == MainTabType.Search && state.isShowingSearchResult)
                        val showAreaSelectors = state.selectedTab == MainTabType.Partition
                        val showSearchInput = state.selectedTab == MainTabType.Search && !state.isShowingSearchResult

                        if (!showLogin && isFocusWithin(viewRefs.loginContainer)) focusNeedsRescue = true
                        if (!showMine && isFocusWithin(viewRefs.mineContainer)) focusNeedsRescue = true
                        if (!showGrid && isFocusWithin(viewRefs.gridContainer)) focusNeedsRescue = true
                        if (!showAreaSelectors && isFocusWithin(viewRefs.areaSelectorsContainer)) focusNeedsRescue = true
                        if (!showSearchInput && isFocusWithin(viewRefs.searchInputContainer)) focusNeedsRescue = true

                        if (isLiveListTab(state.selectedTab) && currentLiveListState != activeListState) {
                            if (activeListState != LiveListState.Content && isFocusWithin(viewRefs.gridView)) focusNeedsRescue = true
                            if (activeListState != LiveListState.Empty && isFocusWithin(viewRefs.emptyContainer)) focusNeedsRescue = true
                            if (activeListState != LiveListState.Error && isFocusWithin(viewRefs.errorContainer)) focusNeedsRescue = true
                            if (activeListState != LiveListState.Loading && isFocusWithin(viewRefs.loadingContainer)) focusNeedsRescue = true
                        }

                        if (focusNeedsRescue) {
                            getTabView(state.selectedTab).requestFocus()
                        }
                        
                        if (state.selectedTab == MainTabType.Search && state.searchHistory.isEmpty() && isFocusWithin(searchHistoryContainer)) {
                            searchEditText.requestFocus()
                        }
                        // ===== FOCUS RESCUE END =====

                        currentLiveListState = activeListState
                        
                        uiRenderer.renderTabState(state.selectedTab, state.isShowingSearchResult, state.searchKeyword)
                        if (!isTabView(currentFocus) && getTabView(state.selectedTab).visibility == View.VISIBLE) {
                            positionTabSlider(getTabView(state.selectedTab), focused = false)
                        }
                        
                        bindUserProfile(state.userProfile)
                        
                        if (state.selectedTab == MainTabType.Partition) {
                            val level1Items = state.partitionAreas.map { AreaTagItem(it.id, it.name) }
                            level1AreaAdapter.updateData(level1Items, state.selectedLevel1AreaId)
                            
                            val selectedLevel1 = state.partitionAreas.find { it.id == state.selectedLevel1AreaId }
                            val level1Grid = findViewById<androidx.leanback.widget.HorizontalGridView>(R.id.level1_area_grid)
                            if (selectedLevel1 != null && selectedLevel1.id != 0 && selectedLevel1.list.isNotEmpty()) {
                                val level2Items = selectedLevel1.list.map { AreaTagItem(it.id.toIntOrNull() ?: 0, it.name) }
                                level2AreaAdapter.updateData(level2Items, state.selectedLevel2AreaId)
                                if (findViewById<View>(R.id.level2_area_grid).visibility != View.VISIBLE) {
                                    findViewById<View>(R.id.level2_area_grid).visibility = View.VISIBLE
                                }
                                level1Grid.nextFocusDownId = R.id.level2_area_grid
                            } else {
                                level2AreaAdapter.updateData(emptyList(), 0)
                                if (findViewById<View>(R.id.level2_area_grid).visibility != View.GONE) {
                                    findViewById<View>(R.id.level2_area_grid).visibility = View.GONE
                                }
                                level1Grid.nextFocusDownId = R.id.main_grid
                            }
                        }
                        
                        if (state.selectedTab == MainTabType.Search) {
                            if (state.searchHistory.isNotEmpty()) {
                                searchHistoryContainer.visibility = View.VISIBLE
                                searchHistoryFlexbox.removeAllViews()
                                state.searchHistory.forEach { keyword ->
                                    val tagView = LayoutInflater.from(this@MainActivity).inflate(R.layout.item_search_history, searchHistoryFlexbox, false) as TextView
                                    tagView.text = keyword
                                    tagView.setOnClickListener {
                                        searchEditText.setText(keyword)
                                        viewModel.performSearch(keyword)
                                    }
                                    tagView.setOnKeyListener(searchLeftKeyHandler)
                                    searchHistoryFlexbox.addView(tagView)
                                }
                            } else {
                                searchHistoryContainer.visibility = View.GONE
                            }
                        }

                        if (isLiveListTab(state.selectedTab)) {
                            val isStateChanged = previousTab != state.selectedTab || previousShowingSearchResult != state.isShowingSearchResult
                            if (isStateChanged && activeListState == LiveListState.Content) {
                                prepositionGrid(state, state.selectedTab, activeRooms)
                            }
                            liveRoomAdapter.updateData(activeRooms)
                            uiRenderer.renderLiveListState(activeListState)
                            if (activeListState == LiveListState.Content) {
                                val roomKey = buildRoomKey(activeRooms)
                                lastRenderedRoomKey = roomKey
                            } else {
                                lastRenderedRoomKey = ""
                            }
                        }

                        if (state.selectedTab == MainTabType.Login && previousTab != MainTabType.Login) {
                            loginViewModel.startLoginFlow()
                        } else if (state.selectedTab != MainTabType.Login && previousTab == MainTabType.Login) {
                            loginViewModel.stopLoginFlow()
                        }
                        dispatchFocusIntent(state, activeListState, activeRooms)
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

    private fun requestGridItemFocus(
        targetPosition: Int,
        attemptsRemaining: Int,
        onSuccess: () -> Unit,
        onFailure: (() -> Unit)? = null
    ) {
        val holder = viewRefs.gridView.findViewHolderForAdapterPosition(targetPosition)
        if (holder?.itemView != null) {
            if (holder.itemView.requestFocus()) {
                confirmFocusApplied(viewRefs.gridView, onSuccess) {
                    onFailure?.invoke()
                }
            } else if (viewRefs.gridView.requestFocus()) {
                confirmFocusApplied(viewRefs.gridView, onSuccess) {
                    onFailure?.invoke()
                }
            } else {
                onFailure?.invoke()
            }
            return
        }
        if (attemptsRemaining <= 0) {
            val fallbackChild = viewRefs.gridView.getChildAt(0)
            if (fallbackChild != null) {
                if (fallbackChild.requestFocus()) {
                    confirmFocusApplied(viewRefs.gridView, onSuccess) {
                        onFailure?.invoke()
                    }
                } else if (viewRefs.gridView.requestFocus()) {
                    confirmFocusApplied(viewRefs.gridView, onSuccess) {
                        onFailure?.invoke()
                    }
                } else {
                    onFailure?.invoke()
                }
            } else if (viewRefs.gridView.requestFocus()) {
                confirmFocusApplied(viewRefs.gridView, onSuccess) {
                    onFailure?.invoke()
                }
            } else {
                onFailure?.invoke()
            }
            return
        }
        viewRefs.gridView.post {
            viewRefs.gridView.setSelectedPosition(targetPosition)
            requestGridItemFocus(targetPosition, attemptsRemaining - 1, onSuccess, onFailure)
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
            if (viewRefs.tabSearch.visibility == View.VISIBLE) {
                add(viewRefs.tabSearch)
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

    private fun isLiveListTab(tab: MainTabType): Boolean {
        return tab == MainTabType.Recommend || tab == MainTabType.Following || tab == MainTabType.Partition || tab == MainTabType.Search
    }

    private fun buildRoomKey(liveRooms: List<LiveRoom>): String {
        return liveRooms.joinToString(separator = ",") { it.roomId.toString() }
    }

    private fun resolveRestorePosition(state: MainScreenState, tab: MainTabType, liveRooms: List<LiveRoom>): Int? {
        if (liveRooms.isEmpty()) {
            return null
        }
        val restoreState = if (tab == MainTabType.Partition) {
            state.partitionFocusState.gridRestoreState
        } else {
            state.gridRestoreStates[tab]
        } ?: return 0
        restoreState.roomId?.let { roomId ->
            val matchedIndex = liveRooms.indexOfFirst { it.roomId == roomId }
            if (matchedIndex >= 0) {
                return matchedIndex
            }
        }
        return restoreState.position.coerceIn(0, liveRooms.lastIndex)
    }

    private fun prepositionGrid(state: MainScreenState, tab: MainTabType, liveRooms: List<LiveRoom>) {
        val targetPosition = resolveRestorePosition(state, tab, liveRooms) ?: 0
        viewRefs.gridView.scrollToPosition(targetPosition)
    }

    private fun dispatchFocusIntent(
        state: MainScreenState,
        activeListState: LiveListState,
        activeRooms: List<LiveRoom>
    ) {
        if (dispatchImmediateFocusIntent(state, activeListState, activeRooms)) {
            return
        }
        dispatchPendingContentFocus(state, activeListState, activeRooms)
    }

    private fun dispatchImmediateFocusIntent(
        state: MainScreenState,
        activeListState: LiveListState,
        activeRooms: List<LiveRoom>
    ): Boolean {
        val focusIntent = state.focusIntent
        if (focusIntent.token == 0L || focusIntent.token == inFlightFocusToken) {
            return false
        }
        val hasLevel1Items = findViewById<View>(R.id.level1_area_grid).visibility == View.VISIBLE &&
            level1AreaAdapter.itemCount > 0
        val hasLevel2Items = findViewById<View>(R.id.level2_area_grid).visibility == View.VISIBLE &&
            level2AreaAdapter.itemCount > 0
        when (
            val resolution = focusOrchestrator.resolveFocusIntent(
                state,
                FocusOrchestrator.Context(
                    selectedTab = state.selectedTab,
                    liveListState = activeListState,
                    liveRooms = activeRooms,
                    isShowingSearchResult = state.isShowingSearchResult,
                    hasLevel1Items = hasLevel1Items,
                    hasLevel2Items = hasLevel2Items
                )
            )
        ) {
            FocusResolution.None -> return false
            FocusResolution.Pending -> return true
            is FocusResolution.Ready -> {
                executeFocusAction(
                    token = focusIntent.token,
                    isPendingContentFocus = false,
                    action = resolution.action,
                    state = state,
                    activeListState = activeListState,
                    activeRooms = activeRooms
                )
                return true
            }
        }
    }

    private fun dispatchPendingContentFocus(
        state: MainScreenState,
        activeListState: LiveListState,
        activeRooms: List<LiveRoom>
    ) {
        val pendingRequest = state.pendingContentFocusRequest
        if (pendingRequest.token == 0L || pendingRequest.token == inFlightFocusToken) {
            return
        }
        if (pendingRequest.tab != null && pendingRequest.tab != state.selectedTab) {
            Log.d("MainFocus", "丢弃过期内容焦点请求 token=${pendingRequest.token} requestTab=${pendingRequest.tab} selectedTab=${state.selectedTab}")
            viewModel.consumePendingContentFocus(pendingRequest.token)
            return
        }
        val hasLevel1Items = findViewById<View>(R.id.level1_area_grid).visibility == View.VISIBLE &&
            level1AreaAdapter.itemCount > 0
        val hasLevel2Items = findViewById<View>(R.id.level2_area_grid).visibility == View.VISIBLE &&
            level2AreaAdapter.itemCount > 0
        when (
            val resolution = focusOrchestrator.resolvePendingContentFocus(
                state,
                FocusOrchestrator.Context(
                    selectedTab = state.selectedTab,
                    liveListState = activeListState,
                    liveRooms = activeRooms,
                    isShowingSearchResult = state.isShowingSearchResult,
                    hasLevel1Items = hasLevel1Items,
                    hasLevel2Items = hasLevel2Items
                )
            )
        ) {
            FocusResolution.None, FocusResolution.Pending -> return
            is FocusResolution.Ready -> {
                executeFocusAction(
                    token = pendingRequest.token,
                    isPendingContentFocus = true,
                    action = resolution.action,
                    state = state,
                    activeListState = activeListState,
                    activeRooms = activeRooms
                )
            }
        }
    }

    private fun executeFocusAction(
        token: Long,
        isPendingContentFocus: Boolean,
        action: FocusAction,
        state: MainScreenState,
        activeListState: LiveListState,
        activeRooms: List<LiveRoom>
    ) {
        inFlightFocusToken = token
        val onSuccess = {
            inFlightFocusToken = 0L
            focusRetryAttempts.remove(token)
            Log.d("MainFocus", "焦点动作成功 token=$token pending=$isPendingContentFocus action=$action")
            if (isPendingContentFocus) {
                viewModel.consumePendingContentFocus(token)
            } else {
                viewModel.consumeFocusIntent(token)
            }
        }
        val onFailure = {
            if (inFlightFocusToken == token) {
                inFlightFocusToken = 0L
            }
            Log.d("MainFocus", "焦点动作失败 token=$token pending=$isPendingContentFocus action=$action")
            scheduleFocusRetry(token, isPendingContentFocus)
        }
        when (action) {
            is FocusAction.FocusTab -> requestViewFocus(getTabView(action.tab), onSuccess, onFailure)
            is FocusAction.FocusContent -> requestDefaultContentFocus(
                action = action,
                state = state,
                activeListState = activeListState,
                activeRooms = activeRooms,
                onSuccess = onSuccess,
                onFailure = onFailure
            )
            is FocusAction.FocusGrid -> requestGridFocus(action.position, activeRooms, onSuccess, onFailure)
            is FocusAction.FocusPartitionLevel1 -> focusAreaIndex(
                findViewById(R.id.level1_area_grid),
                action.index,
                onSuccess,
                onFailure
            )
            is FocusAction.FocusPartitionLevel2 -> {
                val level2Grid = findViewById<androidx.leanback.widget.HorizontalGridView>(R.id.level2_area_grid)
                if (level2Grid.visibility == View.VISIBLE && level2AreaAdapter.itemCount > 0) {
                    focusAreaIndex(level2Grid, action.index, onSuccess, onFailure)
                } else {
                    focusAreaIndex(
                        findViewById(R.id.level1_area_grid),
                        state.partitionFocusState.level1Index,
                        onSuccess,
                        onFailure
                    )
                }
            }
            FocusAction.FocusSearchInput -> requestViewFocus(searchEditText, onSuccess, onFailure)
            is FocusAction.FocusMineAction -> {
                val targetView = when (action.action) {
                    MineActionTarget.Settings -> btnSettings
                    MineActionTarget.Logout -> btnLogout
                }
                requestViewFocus(targetView, onSuccess, onFailure)
            }
        }
    }

    private fun scheduleFocusRetry(token: Long, isPendingContentFocus: Boolean) {
        if (!isFocusTokenActive(token, isPendingContentFocus)) {
            focusRetryAttempts.remove(token)
            return
        }
        val nextAttempt = (focusRetryAttempts[token] ?: 0) + 1
        if (nextAttempt > maxFocusRetryAttempts) {
            focusRetryAttempts.remove(token)
            if (isPendingContentFocus) {
                viewModel.consumePendingContentFocus(token)
            } else {
                viewModel.consumeFocusIntent(token)
            }
            Log.d("MainFocus", "焦点重试放弃 token=$token pending=$isPendingContentFocus")
            return
        }
        focusRetryAttempts[token] = nextAttempt
        Log.d("MainFocus", "焦点重试排队 token=$token pending=$isPendingContentFocus attempt=$nextAttempt")
        window.decorView.postDelayed({
            if (isFinishing || isDestroyed) {
                return@postDelayed
            }
            if (!isFocusTokenActive(token, isPendingContentFocus) || inFlightFocusToken == token) {
                return@postDelayed
            }
            val state = viewModel.screenState.value
            val activeRooms = when (state.selectedTab) {
                MainTabType.Following -> state.followingRooms
                MainTabType.Partition -> state.partitionRooms
                MainTabType.Search -> state.searchRooms
                else -> state.recommendRooms
            }
            val activeListState = when (state.selectedTab) {
                MainTabType.Following -> state.followingListState
                MainTabType.Partition -> state.partitionListState
                MainTabType.Search -> state.searchListState
                else -> state.recommendListState
            }
            dispatchFocusIntent(state, activeListState, activeRooms)
        }, focusRetryDelayMs)
    }

    private fun isFocusTokenActive(token: Long, isPendingContentFocus: Boolean): Boolean {
        val state = viewModel.screenState.value
        return if (isPendingContentFocus) {
            state.pendingContentFocusRequest.token == token
        } else {
            state.focusIntent.token == token
        }
    }

    private fun dispatchFocusFromLatestState() {
        val state = viewModel.screenState.value
        val activeRooms = when (state.selectedTab) {
            MainTabType.Following -> state.followingRooms
            MainTabType.Partition -> state.partitionRooms
            MainTabType.Search -> state.searchRooms
            else -> state.recommendRooms
        }
        val activeListState = when (state.selectedTab) {
            MainTabType.Following -> state.followingListState
            MainTabType.Partition -> state.partitionListState
            MainTabType.Search -> state.searchListState
            else -> state.recommendListState
        }
        dispatchFocusIntent(state, activeListState, activeRooms)
    }

    private fun requestDefaultContentFocus(
        action: FocusAction.FocusContent,
        state: MainScreenState,
        activeListState: LiveListState,
        activeRooms: List<LiveRoom>,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        when (action.tab) {
            MainTabType.Mine -> requestViewFocus(btnSettings, onSuccess, onFailure)
            MainTabType.Recommend, MainTabType.Following -> {
                when (activeListState) {
                    LiveListState.Content -> requestGridEntranceFocus(action.targetPosition, activeRooms, onSuccess, onFailure)
                    LiveListState.Empty -> requestViewFocus(viewRefs.emptyRefreshButton, onSuccess, onFailure)
                    LiveListState.Error -> requestViewFocus(viewRefs.errorRefreshButton, onSuccess, onFailure)
                    LiveListState.Loading -> onFailure()
                }
            }
            MainTabType.Partition -> {
                if (findViewById<View>(R.id.level1_area_grid).visibility == View.VISIBLE && level1AreaAdapter.itemCount > 0) {
                    requestAreaEntranceFocus(
                        findViewById(R.id.level1_area_grid),
                        state.partitionFocusState.level1Index,
                        onSuccess,
                        onFailure
                    )
                } else {
                    onFailure()
                }
            }
            MainTabType.Search -> {
                if (!state.isShowingSearchResult) {
                    requestViewFocus(searchEditText, onSuccess, onFailure)
                } else {
                    when (activeListState) {
                        LiveListState.Content -> requestGridEntranceFocus(action.targetPosition, activeRooms, onSuccess, onFailure)
                        LiveListState.Empty -> requestViewFocus(viewRefs.emptyRefreshButton, onSuccess, onFailure)
                        LiveListState.Error -> requestViewFocus(viewRefs.errorRefreshButton, onSuccess, onFailure)
                        LiveListState.Loading -> onFailure()
                    }
                }
            }
            MainTabType.Login -> requestViewFocus(getTabView(MainTabType.Login), onSuccess, onFailure)
        }
    }

    private fun requestGridEntranceFocus(
        targetPosition: Int,
        activeRooms: List<LiveRoom>,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        if (activeRooms.isEmpty()) {
            onFailure()
            return
        }
        val safePosition = targetPosition.coerceIn(0, activeRooms.lastIndex)
        viewRefs.gridView.post {
            viewRefs.gridView.setSelectedPosition(safePosition)
            requestViewFocus(viewRefs.gridView, onSuccess, onFailure)
        }
    }

    private fun requestAreaEntranceFocus(
        gridView: androidx.leanback.widget.HorizontalGridView,
        index: Int,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        if (gridView.adapter?.itemCount == 0) {
            onFailure()
            return
        }
        val safeIndex = index.coerceAtLeast(0)
        gridView.post {
            gridView.setSelectedPosition(safeIndex)
            requestViewFocus(gridView, onSuccess, onFailure)
        }
    }

    private fun requestViewFocus(
        targetView: View,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        if (targetView.visibility != View.VISIBLE) {
            onFailure()
            return
        }
        if (targetView.requestFocus()) {
            confirmFocusApplied(targetView, onSuccess, onFailure)
            return
        }
        targetView.post {
            if (targetView.requestFocus()) {
                confirmFocusApplied(targetView, onSuccess, onFailure)
            } else {
                onFailure()
            }
        }
    }

    private fun requestGridFocus(
        targetPosition: Int,
        activeRooms: List<LiveRoom>,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        if (activeRooms.isEmpty()) {
            onFailure()
            return
        }
        val safePosition = targetPosition.coerceIn(0, activeRooms.lastIndex)
        viewRefs.gridView.post {
            viewRefs.gridView.setSelectedPosition(safePosition)
            requestGridItemFocus(safePosition, 10, onSuccess, onFailure)
        }
    }

    private fun confirmFocusApplied(
        targetView: View,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        targetView.post {
            if (isFocusWithin(targetView)) {
                onSuccess()
            } else {
                onFailure()
            }
        }
    }

    private fun isFocusWithin(targetView: View): Boolean {
        var focused = currentFocus ?: return false
        while (true) {
            if (focused === targetView) {
                return true
            }
            focused = focused.parent as? View ?: return false
        }
    }

    private fun focusAreaIndex(
        gridView: androidx.leanback.widget.HorizontalGridView,
        index: Int,
        onSuccess: () -> Unit,
        onFailure: () -> Unit,
        attemptsRemaining: Int = 4
    ) {
        if (gridView.childCount == 0 && gridView.adapter?.itemCount == 0) {
            onFailure()
            return
        }
        val safeIndex = index.coerceAtLeast(0)
        gridView.post {
            gridView.setSelectedPosition(safeIndex)
            val holder = gridView.findViewHolderForAdapterPosition(safeIndex)
            when {
                holder?.itemView?.requestFocus() == true -> confirmFocusApplied(gridView, onSuccess, onFailure)
                gridView.requestFocus() -> confirmFocusApplied(gridView, onSuccess, onFailure)
                attemptsRemaining > 0 -> focusAreaIndex(gridView, safeIndex, onSuccess, onFailure, attemptsRemaining - 1)
                else -> onFailure()
            }
        }
    }

    private fun restoreFocusAfterPlayReturn() {
        if (!pendingRestoreFromPlay) {
            return
        }
        pendingRestoreFromPlay = false
        viewModel.restoreAfterPlayReturn(lastPlayEntryTab, lastPlayEntryRoomId)
    }

    private fun animateTabSlider(targetView: View) {
        val slider = viewRefs.tabFocusSlider
        val container = findViewById<View>(R.id.main_tab_container)
        
        // 使用固定高度使高亮块保持方形
        val sliderHeight = resources.getDimensionPixelSize(R.dimen.main_tab_slider_size)
        
        // 计算目标 Y 坐标，使滑块在 tabView 中垂直居中
        val targetCenterY = container.top + targetView.top + targetView.height / 2
        val targetY = targetCenterY - sliderHeight / 2
        
        val targetX = container.left + targetView.left
        val targetWidth = targetView.width

        slider.animate().cancel()
        slider.alpha = 1f
        updateTabSliderStroke(true)

        if (slider.visibility != View.VISIBLE) {
            slider.visibility = View.VISIBLE
            // Ensure layout params are updated before translation
            slider.layoutParams = (slider.layoutParams as FrameLayout.LayoutParams).apply {
                width = targetWidth
                height = sliderHeight
            }
            slider.requestLayout()
            
            // Wait for layout update before setting translation to avoid incorrect positions
            slider.post {
                val updatedTargetCenterY = container.top + targetView.top + targetView.height / 2
                val updatedTargetY = updatedTargetCenterY - sliderHeight / 2
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
            
            // 动画调整宽度和高度
            if ((slider.width != targetWidth || slider.height != sliderHeight) && targetWidth > 0) {
                val startWidth = slider.width
                val startHeight = slider.height
                val animator = ValueAnimator.ofFloat(0f, 1f)
                animator.addUpdateListener { anim ->
                    val fraction = anim.animatedValue as Float
                    slider.layoutParams.width = (startWidth + (targetWidth - startWidth) * fraction).toInt()
                    slider.layoutParams.height = (startHeight + (sliderHeight - startHeight) * fraction).toInt()
                    slider.requestLayout()
                }
                animator.duration = 250
                animator.start()
            }
        }
    }

    private fun positionTabSlider(targetView: View, focused: Boolean) {
        val slider = viewRefs.tabFocusSlider
        val container = findViewById<View>(R.id.main_tab_container)
        val sliderHeight = resources.getDimensionPixelSize(R.dimen.main_tab_slider_size)
        val targetWidth = targetView.width

        slider.animate().cancel()
        slider.alpha = 1f
        slider.visibility = View.VISIBLE
        updateTabSliderStroke(focused)
        slider.layoutParams = (slider.layoutParams as FrameLayout.LayoutParams).apply {
            width = targetWidth
            height = sliderHeight
        }
        slider.requestLayout()
        slider.post {
            val updatedTargetCenterY = container.top + targetView.top + targetView.height / 2
            val updatedTargetY = updatedTargetCenterY - sliderHeight / 2
            val updatedTargetX = container.left + targetView.left
            slider.translationX = updatedTargetX.toFloat()
            slider.translationY = updatedTargetY.toFloat()
        }
    }

    private fun hideTabSlider() {
        viewRefs.tabFocusSlider.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { viewRefs.tabFocusSlider.visibility = View.INVISIBLE }
            .start()
    }

    private fun updateTabSliderStroke(focused: Boolean) {
        val slider = viewRefs.tabFocusSlider
        val background = slider.background as? GradientDrawable ?: return
        val strokeWidth = resources.getDimensionPixelSize(R.dimen.main_tab_slider_stroke_width)
        val strokeColor = if (focused) Color.WHITE else Color.parseColor("#FF6699")
        background.setStroke(strokeWidth, strokeColor)
    }

    private fun getTabView(tab: MainTabType): View {
        return when (tab) {
            MainTabType.Login -> viewRefs.tabLogin
            MainTabType.Mine -> viewRefs.tabMine
            MainTabType.Recommend -> viewRefs.tabRecommend
            MainTabType.Following -> viewRefs.tabFollowing
            MainTabType.Partition -> viewRefs.tabPartition
            MainTabType.Search -> viewRefs.tabSearch
        }
    }

    private fun isTabView(view: View?): Boolean {
        return view == viewRefs.tabLogin ||
            view == viewRefs.tabMine ||
            view == viewRefs.tabRecommend ||
            view == viewRefs.tabFollowing ||
            view == viewRefs.tabPartition ||
            view == viewRefs.tabSearch
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
