package com.blive.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import com.blive.tv.data.model.LiveUserItem
import com.blive.tv.data.model.UserInfoResponse
import com.blive.tv.network.RetrofitClient
import com.blive.tv.ui.login.LoginActivity
import com.blive.tv.utils.ToastHelper
import com.blive.tv.utils.TokenManager

// 直播间数据模型
data class LiveRoom(
    val id: Int,
    val roomId: Long,
    val coverUrl: String,
    val anchorName: String,
    val anchorAvatar: String,
    val roomTitle: String,
    val areaName: String
)

enum class LiveListState {
    Loading,
    Content,
    Empty,
    Error
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initHeader()
        initGridView()
        setupLoginButton()
        setupUserAvatarClick()
        setupStateRefreshButtons()
        updateUIAccordingToLoginState()
    }

    private var lastFocusPositionFromGrid = 0
    private var liveListState = LiveListState.Loading
    private var isLoadingLiveList = false
    private var lastBackPressedAt = 0L
    private val backPressExitWindowMs = 3000L

    // 初始化Header
    private fun initHeader() {
        // Header已经在布局中定义，这里可以添加额外的初始化逻辑
    }

    private fun initGridView() {
        val gridView = findViewById<androidx.leanback.widget.VerticalGridView>(R.id.main_grid)
        val adapter = LiveRoomAdapter(emptyList(), gridView)
        gridView.adapter = adapter
        gridView.setNumColumns(4)
        gridView.overScrollMode = View.OVER_SCROLL_NEVER
        gridView.setScrollEnabled(true)
        gridView.setFocusable(true)
        gridView.setFocusableInTouchMode(true)
    }

    // 获取用户信息
    private fun fetchUserInfo() {
        val sessData = TokenManager.getSessData(this)
        if (sessData != null) {
            // 构建Cookie头
            val cookie = "SESSDATA=$sessData"
            
            RetrofitClient.apiService.getNavInfo(cookie).enqueue(
                object : retrofit2.Callback<UserInfoResponse> {
                    override fun onResponse(
                        call: retrofit2.Call<UserInfoResponse>,
                        response: retrofit2.Response<UserInfoResponse>
                    ) {
                        if (response.isSuccessful) {
                            val userInfoResponse = response.body()
                            if (userInfoResponse != null && userInfoResponse.code == 0) {
                                val userData = userInfoResponse.data
                                if (userData != null && userData.isLogin) {
                                    // 更新用户昵称
                                    val userNickname = findViewById<android.widget.TextView>(R.id.user_nickname)
                                    userNickname.text = userData.uname
                                    
                                    // 更新用户头像
                                    val userAvatar = findViewById<android.widget.ImageView>(R.id.user_avatar)
                                    if (userData.face.isNotEmpty()) {
                                        // 使用Glide加载用户头像并处理为圆形
                                        com.bumptech.glide.Glide.with(this@MainActivity)
                                            .load(userData.face)
                                            .placeholder(android.R.drawable.ic_media_play)
                                            .error(android.R.drawable.ic_media_play)
                                            .circleCrop()
                                            .into(userAvatar)
                                    }
                                }
                            }
                        }
                    }

                    override fun onFailure(
                        call: retrofit2.Call<UserInfoResponse>,
                        t: Throwable
                    ) {
                        // 忽略获取用户信息失败的情况，不影响主功能
                    }
                }
            )
        }
    }
    
    // 获取用户关注的直播间列表（递归分页获取）
    private fun fetchLiveUsers() {
        if (isLoadingLiveList) {
            return
        }
        val sessData = TokenManager.getSessData(this)
        if (sessData != null) {
            val cookie = "SESSDATA=$sessData"
            isLoadingLiveList = true
            renderLiveListState(LiveListState.Loading)
            fetchLiveUsersPage(cookie, 1, mutableListOf())
        } else {
            renderLiveListState(LiveListState.Error)
        }
    }

    // 分页获取直播间列表
    private fun fetchLiveUsersPage(cookie: String, page: Int, accumulatedRooms: MutableList<LiveUserItem>) {
        RetrofitClient.liveApiService.getLiveUsers(cookie, page, 20).enqueue(
            object : retrofit2.Callback<com.blive.tv.data.model.LiveUsersResponse> {
                override fun onResponse(
                    call: retrofit2.Call<com.blive.tv.data.model.LiveUsersResponse>,
                    response: retrofit2.Response<com.blive.tv.data.model.LiveUsersResponse>
                ) {
                    if (response.isSuccessful) {
                        val liveUsersResponse = response.body()
                        if (liveUsersResponse != null && liveUsersResponse.code == 0) {
                            val currentPageRooms = liveUsersResponse.data?.list
                            if (currentPageRooms != null && currentPageRooms.isNotEmpty()) {
                                var shouldContinueFetching = true
                                
                                // 遍历当前页的直播间
                                for (room in currentPageRooms) {
                                    if (room.liveStatus == 1) {
                                        // 只收集正在直播的直播间
                                        accumulatedRooms.add(room)
                                    } else {
                                        // 遇到第一个非直播状态的直播间，停止翻页
                                        shouldContinueFetching = false
                                        break
                                    }
                                }
                                
                                // 如果当前页所有直播间都是直播状态，且不是最后一页，则继续获取下一页
                                if (shouldContinueFetching && page < liveUsersResponse.data.totalPage) {
                                    fetchLiveUsersPage(cookie, page + 1, accumulatedRooms)
                                } else {
                                    // 停止翻页，处理所有收集到的直播间数据
                                    handleLiveUsersData(accumulatedRooms)
                                }
                            } else {
                                // 当前页没有数据，处理已收集的直播间
                                handleLiveUsersData(accumulatedRooms)
                            }
                        } else {
                            isLoadingLiveList = false
                            renderLiveListState(LiveListState.Error)
                            ToastHelper.showTextToast(this@MainActivity, "获取直播列表失败：${liveUsersResponse?.message}")
                        }
                    } else {
                        isLoadingLiveList = false
                        renderLiveListState(LiveListState.Error)
                        ToastHelper.showTextToast(this@MainActivity, "网络请求失败：${response.code()}")
                    }
                }

                override fun onFailure(
                    call: retrofit2.Call<com.blive.tv.data.model.LiveUsersResponse>,
                    t: Throwable
                ) {
                    isLoadingLiveList = false
                    renderLiveListState(LiveListState.Error)
                    ToastHelper.showTextToast(this@MainActivity, "网络连接错误：${t.message}")
                }
            }
        )
    }

    // 处理获取到的直播间数据
    private fun handleLiveUsersData(liveUsers: List<LiveUserItem>) {
        isLoadingLiveList = false
        if (liveUsers.isNotEmpty()) {
            val liveRooms = liveUsers.mapIndexed { index, item ->
                LiveRoom(
                    id = index + 1,
                    roomId = item.roomId, 
                    coverUrl = item.roomCover,
                    anchorName = item.uname,
                    anchorAvatar = item.face,
                    roomTitle = item.title,
                    areaName = item.areaNameV2
                )
            }
            updateGridViewData(liveRooms)
            renderLiveListState(LiveListState.Content)
        } else {
            updateGridViewData(emptyList())
            renderLiveListState(LiveListState.Empty)
        }
    }

    // 更新GridView数据
    private fun updateGridViewData(liveRooms: List<LiveRoom>) {
        val gridView = findViewById<androidx.leanback.widget.VerticalGridView>(R.id.main_grid)
        val currentAdapter = gridView.adapter as? LiveRoomAdapter
        if (currentAdapter != null) {
            // 更新适配器数据
            currentAdapter.updateData(liveRooms)
        } else {
            // 创建新的适配器
            val adapter = LiveRoomAdapter(liveRooms, gridView)
            gridView.adapter = adapter
        }
        
        // 设置焦点导航，确保可以从GridView切换到用户头像
        gridView.nextFocusUpId = R.id.user_avatar_container
        
        // 延迟设置焦点，确保数据已加载完成
        gridView.postDelayed({
            if (liveRooms.isNotEmpty()) {
                var targetPosition = 0
                
                // 尝试恢复上次点击的焦点
                if (lastClickedRoomId != -1L) {
                    val index = liveRooms.indexOfFirst { it.roomId == lastClickedRoomId }
                    if (index != -1) {
                        targetPosition = index
                    } else {
                        // 如果原来的房间找不到了，默认聚焦到第一个
                        targetPosition = 0
                    }
                }
                
                // 执行聚焦逻辑
                // Leanback的VerticalGridView使用setSelectedPosition可以同时滚动并设置焦点
                gridView.setSelectedPosition(targetPosition)
                
                // 为了双重保险，延时请求一次焦点
                gridView.postDelayed({
                    val holder = gridView.findViewHolderForAdapterPosition(targetPosition)
                    if (holder != null) {
                        holder.itemView.requestFocus()
                    }
                }, 100)
            }
        }, 200)
    }

    // 根据登录状态更新UI
    private fun updateUIAccordingToLoginState() {
        val userInfoContainer = findViewById<android.widget.LinearLayout>(R.id.user_info_container)
        val loginButtonContainer = findViewById<android.widget.LinearLayout>(R.id.login_button_container)
        val gridView = findViewById<androidx.leanback.widget.VerticalGridView>(R.id.main_grid)
        val loadingContainer = findViewById<android.widget.LinearLayout>(R.id.live_list_loading_container)
        val emptyContainer = findViewById<android.widget.LinearLayout>(R.id.live_list_empty_container)
        val errorContainer = findViewById<android.widget.LinearLayout>(R.id.live_list_error_container)
        val userAvatarContainer = findViewById<android.widget.FrameLayout>(R.id.user_avatar_container)

        if (TokenManager.isLoggedIn(this)) {
            userInfoContainer.visibility = View.VISIBLE
            loginButtonContainer.visibility = View.GONE
            fetchUserInfo()
            fetchLiveUsers()
        } else {
            isLoadingLiveList = false
            liveListState = LiveListState.Loading
            userInfoContainer.visibility = View.GONE
            loginButtonContainer.visibility = View.VISIBLE
            gridView.visibility = View.GONE
            loadingContainer.visibility = View.GONE
            emptyContainer.visibility = View.GONE
            errorContainer.visibility = View.GONE
            userAvatarContainer.nextFocusDownId = R.id.main_grid
            loginButtonContainer.postDelayed({
                val loginButton = findViewById<android.widget.FrameLayout>(R.id.login_button)
                loginButton.requestFocus()
            }, 100)
        }
    }

    private fun renderLiveListState(state: LiveListState) {
        liveListState = state
        val gridView = findViewById<androidx.leanback.widget.VerticalGridView>(R.id.main_grid)
        val loadingContainer = findViewById<android.widget.LinearLayout>(R.id.live_list_loading_container)
        val emptyContainer = findViewById<android.widget.LinearLayout>(R.id.live_list_empty_container)
        val errorContainer = findViewById<android.widget.LinearLayout>(R.id.live_list_error_container)
        val userAvatarContainer = findViewById<android.widget.FrameLayout>(R.id.user_avatar_container)
        val emptyRefreshButton = findViewById<android.widget.ImageButton>(R.id.live_list_empty_refresh_button)
        val errorRefreshButton = findViewById<android.widget.ImageButton>(R.id.live_list_error_refresh_button)

        when (state) {
            LiveListState.Loading -> {
                gridView.visibility = View.GONE
                loadingContainer.visibility = View.VISIBLE
                emptyContainer.visibility = View.GONE
                errorContainer.visibility = View.GONE
                userAvatarContainer.nextFocusDownId = R.id.main_grid
            }
            LiveListState.Content -> {
                gridView.visibility = View.VISIBLE
                loadingContainer.visibility = View.GONE
                emptyContainer.visibility = View.GONE
                errorContainer.visibility = View.GONE
                userAvatarContainer.nextFocusDownId = R.id.main_grid
            }
            LiveListState.Empty -> {
                gridView.visibility = View.GONE
                loadingContainer.visibility = View.GONE
                emptyContainer.visibility = View.VISIBLE
                errorContainer.visibility = View.GONE
                userAvatarContainer.nextFocusDownId = R.id.live_list_empty_refresh_button
                emptyContainer.postDelayed({
                    emptyRefreshButton.requestFocus()
                }, 100)
            }
            LiveListState.Error -> {
                gridView.visibility = View.GONE
                loadingContainer.visibility = View.GONE
                emptyContainer.visibility = View.GONE
                errorContainer.visibility = View.VISIBLE
                userAvatarContainer.nextFocusDownId = R.id.live_list_error_refresh_button
                errorContainer.postDelayed({
                    errorRefreshButton.requestFocus()
                }, 100)
            }
        }
    }

    // 设置登录按钮点击事件
    private fun setupLoginButton() {
        val loginButton = findViewById<android.widget.FrameLayout>(R.id.login_button)
        loginButton.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupStateRefreshButtons() {
        val emptyRefreshButton = findViewById<android.widget.ImageButton>(R.id.live_list_empty_refresh_button)
        val errorRefreshButton = findViewById<android.widget.ImageButton>(R.id.live_list_error_refresh_button)
        emptyRefreshButton.setOnClickListener {
            retryFetchLiveRooms()
        }
        errorRefreshButton.setOnClickListener {
            retryFetchLiveRooms()
        }
    }

    private fun retryFetchLiveRooms() {
        if (!TokenManager.isLoggedIn(this)) {
            return
        }
        if (isLoadingLiveList) {
            ToastHelper.showTextToast(this, "正在刷新直播间列表...")
            return
        }
        fetchLiveUsers()
    }

    // 设置用户头像点击事件
    private fun setupUserAvatarClick() {
        val userAvatarContainer = findViewById<android.widget.FrameLayout>(R.id.user_avatar_container)
        
        userAvatarContainer.setOnClickListener {
            if (TokenManager.isLoggedIn(this)) {
                showUserMenu(userAvatarContainer)
            }
        }
        
        userAvatarContainer.setOnKeyListener { _, keyCode, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val gridView = findViewById<androidx.leanback.widget.VerticalGridView>(R.id.main_grid)
                        val adapter = gridView.adapter as? LiveRoomAdapter
                        val itemCount = adapter?.itemCount ?: 0
                        when (liveListState) {
                            LiveListState.Content -> {
                                if (gridView.visibility == android.view.View.VISIBLE && itemCount > 0) {
                                    gridView.scrollToPosition(0)
                                    gridView.post {
                                        val holder = gridView.findViewHolderForAdapterPosition(0)
                                        if (holder != null) {
                                            holder.itemView.requestFocus()
                                        } else {
                                            val child = gridView.getChildAt(0)
                                            child?.requestFocus()
                                        }
                                    }
                                }
                            }
                            LiveListState.Empty -> {
                                val refreshButton = findViewById<android.widget.ImageButton>(R.id.live_list_empty_refresh_button)
                                refreshButton.requestFocus()
                            }
                            LiveListState.Error -> {
                                val refreshButton = findViewById<android.widget.ImageButton>(R.id.live_list_error_refresh_button)
                                refreshButton.requestFocus()
                            }
                            LiveListState.Loading -> {
                            }
                        }
                        return@setOnKeyListener true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val nickname = findViewById<android.widget.TextView>(R.id.user_nickname)
                        if (nickname.isFocusable) {
                            nickname.requestFocus()
                        }
                        return@setOnKeyListener true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        return@setOnKeyListener true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }

    // 记录最后点击的直播间ID
    private var lastClickedRoomId: Long = -1L

    // 显示用户菜单
    private fun showUserMenu(anchorView: android.view.View) {
        val popupMenu = android.widget.PopupMenu(this, anchorView)
        
        // 动态添加菜单项
        popupMenu.menu.add(0, 0, 0, "偏好设置")
        popupMenu.menu.add(0, 1, 1, "登出")
        
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                0 -> {
                    com.blive.tv.ui.settings.SettingsDialogFragment().show(supportFragmentManager, "settings")
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

    // 执行退出登录
    private fun logout() {
        // 清除登录凭证
        TokenManager.clearToken(this)
        // 更新UI
        updateUIAccordingToLoginState()
        // 显示退出成功提示
        ToastHelper.showTextToast(this, "退出登录成功")
    }



    override fun onResume() {
        super.onResume()
        // 每次返回主界面时，检查登录状态并更新UI
        updateUIAccordingToLoginState()
    }
    
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            if (TokenManager.isLoggedIn(this)) {
                retryFetchLiveRooms()
                return true
            }
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
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
    
    // RecyclerView适配器
    inner class LiveRoomAdapter(
        private var liveRooms: List<LiveRoom>,
        private val gridView: androidx.leanback.widget.VerticalGridView
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<LiveRoomAdapter.LiveRoomViewHolder>() {
        
        // 更新数据
        fun updateData(newLiveRooms: List<LiveRoom>) {
            liveRooms = newLiveRooms
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LiveRoomViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_live_room, parent, false)
            return LiveRoomViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: LiveRoomViewHolder, position: Int) {
            val liveRoom = liveRooms[position]
            holder.bind(liveRoom)
        }
        
        override fun getItemCount(): Int {
            return liveRooms.size
        }
        
        inner class LiveRoomViewHolder(view: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            private val coverImage = view.findViewById<android.widget.ImageView>(R.id.cover_image)
            private val anchorAvatar = view.findViewById<android.widget.ImageView>(R.id.anchor_avatar)
            private val anchorName = view.findViewById<android.widget.TextView>(R.id.anchor_name)
            private val roomTitle = view.findViewById<android.widget.TextView>(R.id.room_title)
            private val areaName = view.findViewById<android.widget.TextView>(R.id.area_name)
            
            init {
                // 设置按键监听，当焦点在第一行时按上键切换到用户头像
                itemView.setOnKeyListener { _, keyCode, event ->
                    if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                        if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                            // 如果焦点在第一行，切换到用户头像
                            if (bindingAdapterPosition >= 0 && bindingAdapterPosition < 4) {
                                lastFocusPositionFromGrid = bindingAdapterPosition
                                val userAvatarContainer = itemView.rootView.findViewById<android.widget.FrameLayout>(R.id.user_avatar_container)
                                userAvatarContainer.requestFocus()
                                return@setOnKeyListener true
                            }
                        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                            // 如果是最后一个Item，按右键时保持焦点，防止焦点丢失
                            if (bindingAdapterPosition == itemCount - 1) {
                                return@setOnKeyListener true
                            }
                        }
                    }
                    false
                }
                
                // 设置点击事件，点击卡片跳转到播放页面
                itemView.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos in liveRooms.indices) {
                        val liveRoom = liveRooms[pos]
                        
                        // 记录点击的直播间ID
                        lastClickedRoomId = liveRoom.roomId
                        
                        val intent = android.content.Intent(itemView.context, com.blive.tv.ui.play.LivePlayActivity::class.java)
                        intent.putExtra("room_id", liveRoom.roomId)
                        itemView.context.startActivity(intent)
                    }
                }
            }
            
            fun bind(liveRoom: LiveRoom) {
                // 设置直播封面
                if (liveRoom.coverUrl.isNotEmpty()) {
                    // 使用Glide加载网络图片
                    com.bumptech.glide.Glide.with(itemView.context)
                        .load(liveRoom.coverUrl)
                        .placeholder(android.R.drawable.ic_media_play) // 加载中显示的占位图
                        .error(android.R.drawable.ic_media_play) // 加载失败显示的占位图
                        .into(coverImage)
                } else {
                    // 如果没有封面URL，显示默认占位图
                    coverImage.setImageResource(android.R.drawable.ic_media_play)
                }
                
                // 设置主播头像
                if (liveRoom.anchorAvatar.isNotEmpty()) {
                    // 使用Glide加载主播头像并处理为圆形
                    com.bumptech.glide.Glide.with(itemView.context)
                        .load(liveRoom.anchorAvatar)
                        .placeholder(android.R.drawable.ic_media_play) // 加载中显示的占位图
                        .error(android.R.drawable.ic_media_play) // 加载失败显示的占位图
                        .circleCrop() // 圆形裁剪，确保头像为圆形
                        .into(anchorAvatar)
                } else {
                    // 如果没有头像URL，显示默认占位图并处理为圆形
                    com.bumptech.glide.Glide.with(itemView.context)
                        .load(android.R.drawable.ic_media_play)
                        .circleCrop() // 圆形裁剪
                        .into(anchorAvatar)
                }
                
                // 设置主播名称
                anchorName.text = liveRoom.anchorName
                
                // 设置直播间标题
                roomTitle.text = liveRoom.roomTitle
                
                // 设置直播间分区
                areaName.text = liveRoom.areaName
            }
        }
    }
}
