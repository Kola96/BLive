package com.blive.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.blive.tv.ui.login.LoginActivity
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
import com.blive.tv.ui.settings.SettingsDialogFragment
import com.blive.tv.utils.ToastHelper
import com.blive.tv.utils.TokenManager
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels {
        MainViewModelFactory(MainRepository())
    }

    private lateinit var viewRefs: MainViewRefs
    private lateinit var uiRenderer: MainUiRenderer
    private lateinit var focusNavigator: MainFocusNavigator
    private lateinit var liveRoomAdapter: LiveRoomAdapter
    private lateinit var userNicknameView: TextView
    private lateinit var userAvatarView: ImageView
    private lateinit var followingTabView: TextView
    private lateinit var recommendTabView: TextView
    private lateinit var loginButton: FrameLayout

    private var lastClickedRoomId: Long = -1L
    private var lastRenderedRoomKey: String = ""
    private var currentLiveListState: LiveListState = LiveListState.Loading
    private var currentTab: MainTabType = MainTabType.Following
    private var lastBackPressedAt = 0L
    private val backPressExitWindowMs = 3000L

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
        refreshByLoginState()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU && TokenManager.isLoggedIn(this)) {
            viewModel.refreshCurrentTab()
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
        val userInfoContainer = findViewById<android.widget.LinearLayout>(R.id.user_info_container)
        val loginButtonContainer = findViewById<android.widget.LinearLayout>(R.id.login_button_container)
        val gridView = findViewById<androidx.leanback.widget.VerticalGridView>(R.id.main_grid)
        val loadingContainer = findViewById<android.widget.LinearLayout>(R.id.live_list_loading_container)
        val emptyContainer = findViewById<android.widget.LinearLayout>(R.id.live_list_empty_container)
        val errorContainer = findViewById<android.widget.LinearLayout>(R.id.live_list_error_container)
        val tabContainer = findViewById<android.widget.LinearLayout>(R.id.main_tab_container)
        val userAvatarContainer = findViewById<FrameLayout>(R.id.user_avatar_container)
        val emptyRefreshButton = findViewById<android.widget.ImageButton>(R.id.live_list_empty_refresh_button)
        val errorRefreshButton = findViewById<android.widget.ImageButton>(R.id.live_list_error_refresh_button)
        userNicknameView = findViewById(R.id.user_nickname)
        userAvatarView = findViewById(R.id.user_avatar)
        followingTabView = findViewById(R.id.main_tab_following)
        recommendTabView = findViewById(R.id.main_tab_recommend)
        loginButton = findViewById(R.id.login_button)
        viewRefs = MainViewRefs(
            userInfoContainer = userInfoContainer,
            loginButtonContainer = loginButtonContainer,
            gridView = gridView,
            loadingContainer = loadingContainer,
            emptyContainer = emptyContainer,
            errorContainer = errorContainer,
            userAvatarContainer = userAvatarContainer,
            tabContainer = tabContainer,
            followingTabView = followingTabView,
            recommendTabView = recommendTabView,
            emptyRefreshButton = emptyRefreshButton,
            errorRefreshButton = errorRefreshButton
        )
    }

    private fun initGrid() {
        liveRoomAdapter = LiveRoomAdapter(
            onFirstRowUp = {
                focusCurrentTab()
            },
            onRoomClicked = { roomId ->
                lastClickedRoomId = roomId
            }
        )
        viewRefs.gridView.adapter = liveRoomAdapter
        viewRefs.gridView.setNumColumns(4)
        viewRefs.gridView.overScrollMode = View.OVER_SCROLL_NEVER
        viewRefs.gridView.setScrollEnabled(true)
        viewRefs.gridView.isFocusable = true
        viewRefs.gridView.isFocusableInTouchMode = true
        viewRefs.gridView.nextFocusUpId = R.id.main_tab_following
    }

    private fun initControllers() {
        uiRenderer = MainUiRenderer(viewRefs)
        focusNavigator = MainFocusNavigator(
            userAvatarContainer = viewRefs.userAvatarContainer,
            nicknameView = userNicknameView,
            gridView = viewRefs.gridView,
            emptyRefreshButton = viewRefs.emptyRefreshButton,
            errorRefreshButton = viewRefs.errorRefreshButton
        )
    }

    private fun setupListeners() {
        loginButton.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        viewRefs.emptyRefreshButton.setOnClickListener {
            viewModel.refreshCurrentTab()
        }
        viewRefs.errorRefreshButton.setOnClickListener {
            viewModel.refreshCurrentTab()
        }
        followingTabView.setOnClickListener {
            viewModel.switchTab(MainTabType.Following)
        }
        recommendTabView.setOnClickListener {
            viewModel.switchTab(MainTabType.Recommend)
        }
        followingTabView.setOnKeyListener { _, keyCode, event ->
            val keyEvent = event ?: return@setOnKeyListener false
            handleTabKey(MainTabType.Following, keyCode, keyEvent)
        }
        recommendTabView.setOnKeyListener { _, keyCode, event ->
            val keyEvent = event ?: return@setOnKeyListener false
            handleTabKey(MainTabType.Recommend, keyCode, keyEvent)
        }
        viewRefs.userAvatarContainer.setOnClickListener {
            if (TokenManager.isLoggedIn(this)) {
                showUserMenu(viewRefs.userAvatarContainer)
            }
        }
        viewRefs.userAvatarContainer.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }
            focusNavigator.handleAvatarKey(keyCode, currentLiveListState, liveRoomAdapter.itemCount)
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.screenState.collect { state ->
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
                        updateGridNextFocusUp(state.selectedTab)
                        if (!state.isLoggedIn) {
                            requestLoginButtonFocus()
                        }
                        bindUserProfile(state.userProfile)
                        liveRoomAdapter.updateData(activeRooms)
                        uiRenderer.renderLiveListState(activeListState)
                        if (activeListState == LiveListState.Content) {
                            restoreGridFocus(activeRooms)
                        } else {
                            lastRenderedRoomKey = ""
                        }
                    }
                }
                launch {
                    viewModel.toastEvent.collect { message ->
                        ToastHelper.showTextToast(this@MainActivity, message)
                    }
                }
            }
        }
    }

    private fun bindUserProfile(profile: UserProfile?) {
        if (profile == null) {
            userNicknameView.text = ""
            userAvatarView.setImageResource(android.R.drawable.ic_media_play)
            return
        }
        userNicknameView.text = profile.nickname
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
            viewRefs.gridView.postDelayed({
                viewRefs.gridView.findViewHolderForAdapterPosition(targetPosition)?.itemView?.requestFocus()
            }, 100)
        }, 200)
    }

    private fun handleTabKey(tabType: MainTabType, keyCode: Int, event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (tabType == MainTabType.Recommend) {
                    viewModel.switchTab(MainTabType.Following)
                    followingTabView.requestFocus()
                    true
                } else {
                    false
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (tabType == MainTabType.Following) {
                    viewModel.switchTab(MainTabType.Recommend)
                    recommendTabView.requestFocus()
                    true
                } else {
                    false
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveFocusFromTabToContent()
                true
            }

            else -> false
        }
    }

    private fun moveFocusFromTabToContent() {
        when (currentLiveListState) {
            LiveListState.Content -> {
                if (liveRoomAdapter.itemCount <= 0) {
                    return
                }
                viewRefs.gridView.scrollToPosition(0)
                viewRefs.gridView.post {
                    val holder = viewRefs.gridView.findViewHolderForAdapterPosition(0)
                    if (holder != null) {
                        holder.itemView.requestFocus()
                    } else {
                        viewRefs.gridView.getChildAt(0)?.requestFocus()
                    }
                }
            }

            LiveListState.Empty -> viewRefs.emptyRefreshButton.requestFocus()
            LiveListState.Error -> viewRefs.errorRefreshButton.requestFocus()
            LiveListState.Loading -> Unit
        }
    }

    private fun focusCurrentTab() {
        if (currentTab == MainTabType.Following) {
            followingTabView.requestFocus()
        } else {
            recommendTabView.requestFocus()
        }
    }

    private fun updateGridNextFocusUp(selectedTab: MainTabType) {
        viewRefs.gridView.nextFocusUpId = if (selectedTab == MainTabType.Following) {
            R.id.main_tab_following
        } else {
            R.id.main_tab_recommend
        }
    }

    private fun refreshByLoginState() {
        val isLoggedIn = TokenManager.isLoggedIn(this)
        viewModel.syncLoginState(isLoggedIn)
        if (isLoggedIn) {
            viewModel.refreshAfterLogin()
        }
    }

    private fun requestLoginButtonFocus() {
        viewRefs.loginButtonContainer.postDelayed({
            loginButton.requestFocus()
        }, 100)
    }

    private fun showUserMenu(anchorView: View) {
        val popupMenu = PopupMenu(this, anchorView)
        popupMenu.menu.add(0, 0, 0, "偏好设置")
        popupMenu.menu.add(0, 1, 1, "登出")
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                0 -> {
                    if (!supportFragmentManager.isStateSaved && !isFinishing && !isDestroyed) {
                        SettingsDialogFragment().show(supportFragmentManager, "settings")
                    }
                    true
                }

                1 -> {
                    logout()
                    true
                }

                else -> false
            }
        }
        popupMenu.show()
    }

    private fun logout() {
        TokenManager.clearToken(this)
        refreshByLoginState()
        ToastHelper.showTextToast(this, "退出登录成功")
    }
}
