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
import com.blive.tv.ui.main.AreaTagAdapter
import com.blive.tv.ui.main.AreaTagItem
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

import android.widget.EditText
import com.google.android.flexbox.FlexboxLayout
import android.view.LayoutInflater

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
    private val tabGridRestoreStates = mutableMapOf(
        MainTabType.Recommend to GridRestoreState(),
        MainTabType.Following to GridRestoreState(),
        MainTabType.Partition to GridRestoreState(),
        MainTabType.Search to GridRestoreState()
    )
    private var pendingGridRestoreTab: MainTabType? = null
    private var lastPlayEntryTab: MainTabType? = null
    private var lastPlayEntryRoomId: Long = -1L
    private var pendingRestoreFromPlay: Boolean = false
    private var pendingRestoreFollowingRoom: Boolean = false
    private var pendingRestoreRecommendTab: Boolean = false
    private var isRestoringSearchFocusFromPlay: Boolean = false
    private var lastBackPressedAt = 0L
    private val backPressExitWindowMs = 3000L
    private val livePlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingRestoreFromPlay = true
            if (window.decorView.hasWindowFocus()) {
                window.decorView.post {
                    restoreFocusAfterPlayReturn()
                }
            }
        } else {
            clearSearchPlayRestoreState()
        }
    }

    private val searchLeftKeyHandler = View.OnKeyListener { _, keyCode, event ->
        if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            viewRefs.tabSearch.requestFocus()
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
            onNavigateBack = { focusCurrentTab() }
        )
        level2AreaAdapter = AreaTagAdapter(
            isLevel1 = false,
            onItemClick = { areaId -> viewModel.selectLevel2Area(areaId) },
            onNavigateBack = { focusCurrentTab() }
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
                    val level2AreaGrid = findViewById<androidx.leanback.widget.HorizontalGridView>(R.id.level2_area_grid)
                    if (level2AreaGrid.visibility == View.VISIBLE && level2AreaGrid.childCount > 0) {
                        level2AreaGrid.requestFocus()
                    } else {
                        val level1AreaGrid = findViewById<androidx.leanback.widget.HorizontalGridView>(R.id.level1_area_grid)
                        level1AreaGrid.requestFocus()
                    }
                }
            },
            onRoomClicked = { roomId ->
                lastClickedRoomId = roomId
                lastPlayEntryTab = currentTab
                lastPlayEntryRoomId = roomId
                if (currentTab == MainTabType.Search && isShowingSearchResult) {
                    isRestoringSearchFocusFromPlay = true
                }
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
                if (currentTab == MainTabType.Search && isRestoringSearchFocusFromPlay) {
                    clearSearchPlayRestoreState()
                }
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
        focusNavigator = MainFocusNavigator(
            gridView = viewRefs.gridView,
            level1AreaGrid = findViewById(R.id.level1_area_grid),
            level2AreaGrid = findViewById(R.id.level2_area_grid),
            emptyRefreshButton = viewRefs.emptyRefreshButton,
            errorRefreshButton = viewRefs.errorRefreshButton,
            loadingContainer = viewRefs.loadingContainer,
            btnSettings = btnSettings,
            btnLogout = btnLogout,
            searchEditText = searchEditText
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
                if (shouldBlockTransientTabFocus(targetTab)) {
                    positionTabSlider(getTabView(currentTab), focused = false)
                    viewRefs.gridView.post {
                        restoreGridFocus(MainTabType.Search, viewModel.screenState.value.searchRooms)
                    }
                } else {
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
                    if (targetTab != currentTab) {
                        pendingGridRestoreTab = targetTab.takeIf { isLiveListTab(it) }
                        viewModel.switchTab(targetTab)
                    }
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

                // 检查是否所有 Tab 都失去了焦点，如果是则进入“选中但未聚焦”状态
                v.postDelayed({
                    val currentFocus = currentFocus
                    val tabs = listOf(viewRefs.tabLogin, viewRefs.tabMine, viewRefs.tabRecommend, viewRefs.tabFollowing, viewRefs.tabPartition, viewRefs.tabSearch)
                    if (currentFocus !in tabs) {
                        // 焦点不在 Tab 上，滑块保持在当前选中 Tab 的位置，但边框变色
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
                    focusNavigator.focusContent(
                        state = currentLiveListState,
                        tab = currentTab,
                        gridItemCount = liveRoomAdapter.itemCount,
                        targetPosition = resolveRestorePosition(currentTab, liveRoomAdapter.getCurrentData()) ?: 0,
                        isShowingSearchResult = isShowingSearchResult
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
        viewRefs.tabSearch.setOnKeyListener(tabKeyHandler)

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

        btnSearch.setOnClickListener {
            // 先把焦点移到导航栏的搜索 tab 上，防止搜索框隐藏后焦点丢失导致漂移到其他 tab
            viewRefs.tabSearch.requestFocus()
            val keyword = searchEditText.text.toString()
            viewModel.performSearch(keyword)
        }

        searchEditText.setOnEditorActionListener { _, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                // 先把焦点移到导航栏的搜索 tab 上
                viewRefs.tabSearch.requestFocus()
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
                        currentLiveListState = activeListState

                        if (previousIsLoggedIn != state.isLoggedIn) {
                            uiRenderer.renderLoginState(state.isLoggedIn)
                        }

                        if (previousShowingSearchResult != state.isShowingSearchResult && state.isShowingSearchResult) {
                            // 当搜索结果页出现时，输入框会被隐藏导致焦点丢失，提前把焦点移到 tab 上
                            viewRefs.tabSearch.requestFocus()
                            
                            // 如果结果已经就绪，标记需要恢复焦点到 Grid
                            if (activeListState == LiveListState.Content) {
                                pendingGridRestoreTab = MainTabType.Search
                            }
                        } else if (
                            previousTab == MainTabType.Search &&
                            previousShowingSearchResult &&
                            !state.isShowingSearchResult &&
                            state.selectedTab == MainTabType.Search &&
                            !pendingRestoreFromPlay &&
                            !isRestoringSearchFocusFromPlay
                        ) {
                            // 当从搜索结果页退出到输入页时（且仍留在搜索 Tab），自动聚焦到输入框
                            focusNavigator.focusContent(activeListState, state.selectedTab, activeRooms.size, 0, false)
                        }
                        
                        uiRenderer.renderTabState(state.selectedTab, state.isShowingSearchResult, state.searchKeyword)
                        if (!isTabView(currentFocus) && getTabView(state.selectedTab).visibility == View.VISIBLE) {
                            positionTabSlider(getTabView(state.selectedTab), focused = false)
                        }
                        
                        bindUserProfile(state.userProfile)
                        
                        if (state.selectedTab == MainTabType.Partition) {
                            val level1Items = state.partitionAreas.map { AreaTagItem(it.id, it.name) }
                            level1AreaAdapter.updateData(level1Items, state.selectedLevel1AreaId)
                            
                            val selectedLevel1 = state.partitionAreas.find { it.id == state.selectedLevel1AreaId }
                            if (selectedLevel1 != null && selectedLevel1.id != 0) {
                                val level2Items = selectedLevel1.list.map { AreaTagItem(it.id.toIntOrNull() ?: 0, it.name) }
                                level2AreaAdapter.updateData(level2Items, state.selectedLevel2AreaId)
                                if (findViewById<View>(R.id.level2_area_grid).visibility != View.VISIBLE) {
                                    findViewById<View>(R.id.level2_area_grid).visibility = View.VISIBLE
                                }
                            } else {
                                level2AreaAdapter.updateData(emptyList(), 0)
                                if (findViewById<View>(R.id.level2_area_grid).visibility != View.GONE) {
                                    findViewById<View>(R.id.level2_area_grid).visibility = View.GONE
                                }
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
            if (tab == MainTabType.Search && isShowingSearchResult) {
                viewRefs.gridView.post {
                    focusNavigator.focusContent(currentLiveListState, tab, 0, 0, true)
                }
            }
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
            if (shouldRequestGridFocus() || (tab == MainTabType.Following && lastPlayEntryRoomId == targetRoomId) || (tab == MainTabType.Search && isShowingSearchResult)) {
                requestGridItemFocus(
                    targetPosition = targetPosition,
                    attemptsRemaining = if (tab == MainTabType.Search && isRestoringSearchFocusFromPlay) 6 else 3,
                    onFailure = {
                        if (tab == MainTabType.Search) {
                            clearSearchPlayRestoreState()
                            viewRefs.tabSearch.requestFocus()
                        }
                    }
                )
            }
        }
    }

    private fun requestGridItemFocus(targetPosition: Int, attemptsRemaining: Int, onFailure: (() -> Unit)? = null) {
        val holder = viewRefs.gridView.findViewHolderForAdapterPosition(targetPosition)
        if (holder?.itemView != null) {
            holder.itemView.requestFocus()
            return
        }
        if (attemptsRemaining <= 0) {
            val fallbackChild = viewRefs.gridView.getChildAt(0)
            if (fallbackChild != null) {
                fallbackChild.requestFocus()
            } else {
                onFailure?.invoke()
            }
            return
        }
        viewRefs.gridView.post {
            viewRefs.gridView.setSelectedPosition(targetPosition)
            requestGridItemFocus(targetPosition, attemptsRemaining - 1, onFailure)
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
        if (targetIndex != currentIndex) {
            clearSearchRestoreBlockForUserNavigation()
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
        // 如果焦点在当前 Tab 上且我们刚刚切换了状态（如开始搜索），也允许转移焦点到 Grid
        if (focusedView == viewRefs.tabSearch && isShowingSearchResult) {
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
        return tab == MainTabType.Recommend || tab == MainTabType.Following || tab == MainTabType.Partition || tab == MainTabType.Search
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
        clearSearchRestoreBlockForUserNavigation()
        when (currentTab) {
            MainTabType.Login -> viewRefs.tabLogin.requestFocus()
            MainTabType.Mine -> viewRefs.tabMine.requestFocus()
            MainTabType.Recommend -> viewRefs.tabRecommend.requestFocus()
            MainTabType.Following -> viewRefs.tabFollowing.requestFocus()
            MainTabType.Partition -> viewRefs.tabPartition.requestFocus()
            MainTabType.Search -> viewRefs.tabSearch.requestFocus()
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

    private fun shouldBlockTransientTabFocus(targetTab: MainTabType): Boolean {
        return isRestoringSearchFocusFromPlay &&
            currentTab == MainTabType.Search &&
            isShowingSearchResult &&
            targetTab != MainTabType.Search
    }

    private fun clearSearchPlayRestoreState() {
        isRestoringSearchFocusFromPlay = false
    }

    private fun clearSearchRestoreBlockForUserNavigation() {
        if (currentTab == MainTabType.Search && isShowingSearchResult && isRestoringSearchFocusFromPlay) {
            clearSearchPlayRestoreState()
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
            MainTabType.Search -> {
                pendingRestoreFollowingRoom = false
                pendingRestoreRecommendTab = false
                isRestoringSearchFocusFromPlay = true
                pendingGridRestoreTab = MainTabType.Search
                positionTabSlider(viewRefs.tabSearch, focused = false)
                restoreGridFocus(MainTabType.Search, viewModel.screenState.value.searchRooms)
            }
            else -> Unit
        }
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
