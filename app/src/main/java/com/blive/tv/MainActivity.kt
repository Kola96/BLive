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
        MainViewModelFactory(MainRepository(applicationContext))
    }

    private lateinit var viewRefs: MainViewRefs
    private lateinit var uiRenderer: MainUiRenderer
    private lateinit var focusNavigator: MainFocusNavigator
    private lateinit var liveRoomAdapter: LiveRoomAdapter
    private lateinit var userNicknameView: TextView
    private lateinit var userAvatarView: ImageView
    private lateinit var loginButton: FrameLayout

    private var lastClickedRoomId: Long = -1L
    private var lastRenderedRoomKey: String = ""
    private var currentLiveListState: LiveListState = LiveListState.Loading
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
            viewModel.refreshLiveRooms()
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
        val userAvatarContainer = findViewById<FrameLayout>(R.id.user_avatar_container)
        val emptyRefreshButton = findViewById<android.widget.ImageButton>(R.id.live_list_empty_refresh_button)
        val errorRefreshButton = findViewById<android.widget.ImageButton>(R.id.live_list_error_refresh_button)
        userNicknameView = findViewById(R.id.user_nickname)
        userAvatarView = findViewById(R.id.user_avatar)
        loginButton = findViewById(R.id.login_button)
        viewRefs = MainViewRefs(
            userInfoContainer = userInfoContainer,
            loginButtonContainer = loginButtonContainer,
            gridView = gridView,
            loadingContainer = loadingContainer,
            emptyContainer = emptyContainer,
            errorContainer = errorContainer,
            userAvatarContainer = userAvatarContainer,
            emptyRefreshButton = emptyRefreshButton,
            errorRefreshButton = errorRefreshButton
        )
    }

    private fun initGrid() {
        liveRoomAdapter = LiveRoomAdapter(
            onFirstRowUp = {
                focusNavigator.focusAvatar()
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
        viewRefs.gridView.nextFocusUpId = R.id.user_avatar_container
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
            viewModel.refreshLiveRooms()
        }
        viewRefs.errorRefreshButton.setOnClickListener {
            viewModel.refreshLiveRooms()
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
                        currentLiveListState = state.liveListState
                        uiRenderer.renderLoginState(state.isLoggedIn)
                        if (!state.isLoggedIn) {
                            requestLoginButtonFocus()
                        }
                        bindUserProfile(state.userProfile)
                        liveRoomAdapter.updateData(state.liveRooms)
                        uiRenderer.renderLiveListState(state.liveListState)
                        if (state.liveListState == LiveListState.Content) {
                            restoreGridFocus(state.liveRooms)
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
                    SettingsDialogFragment().show(supportFragmentManager, "settings")
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
