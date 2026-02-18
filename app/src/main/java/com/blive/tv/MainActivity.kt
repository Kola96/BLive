package com.blive.tv

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.blive.tv.data.model.LiveUserItem
import com.blive.tv.data.model.UserInfoResponse
import com.blive.tv.network.RetrofitClient
import com.blive.tv.ui.login.LoginActivity
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

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 初始化UI组件
        initHeader()
        initGridView()
        updateUIAccordingToLoginState()

        // 设置登录按钮点击事件
        setupLoginButton()

        // 设置用户头像点击事件
        setupUserAvatarClick()
    }

    private var lastFocusPositionFromGrid = 0

    // 初始化Header
    private fun initHeader() {
        // Header已经在布局中定义，这里可以添加额外的初始化逻辑
    }

    // 初始化GridView
    private fun initGridView() {
        // 初始化VerticalGridView
        val gridView = findViewById<androidx.leanback.widget.VerticalGridView>(R.id.main_grid)
        
        // 生成模拟数据
        val mockData = generateMockData()
        
        // 创建RecyclerView适配器
        val adapter = LiveRoomAdapter(mockData, gridView)
        
        // 设置适配器到GridView
        gridView.adapter = adapter
        
        // 设置列数为4
        gridView.setNumColumns(4)
        
        // 禁用过度滚动
        gridView.overScrollMode = View.OVER_SCROLL_NEVER
        gridView.setScrollEnabled(true)
        
        // 启用焦点管理
        gridView.setFocusable(true)
        gridView.setFocusableInTouchMode(true)
        
        // 延迟设置焦点到第一个项目，确保视图已初始化完成
        gridView.postDelayed({
            if (mockData.isNotEmpty()) {
                // 设置第一个项目获得焦点
                val firstItem = gridView.getChildAt(0)
                if (firstItem != null) {
                    firstItem.requestFocus()
                } else {
                    // 如果直接获取子项失败，尝试通过滚动到位置0并请求焦点
                    gridView.scrollToPosition(0)
                    gridView.postDelayed({
                        val firstItemAfterScroll = gridView.getChildAt(0)
                        firstItemAfterScroll?.requestFocus()
                    }, 100)
                }
            }
        }, 200)
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
        val sessData = TokenManager.getSessData(this)
        if (sessData != null) {
            // 构建Cookie头
            val cookie = "SESSDATA=$sessData"
            
            // 从第一页开始获取
            fetchLiveUsersPage(cookie, 1, mutableListOf())
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
                                if (shouldContinueFetching && page < (liveUsersResponse.data?.totalPage ?: 1)) {
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
                            Toast.makeText(this@MainActivity, "获取直播列表失败：${liveUsersResponse?.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "网络请求失败：${response.code()}", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(
                    call: retrofit2.Call<com.blive.tv.data.model.LiveUsersResponse>,
                    t: Throwable
                ) {
                    Toast.makeText(this@MainActivity, "网络连接错误：${t.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    // 处理获取到的直播间数据
    private fun handleLiveUsersData(liveUsers: List<LiveUserItem>) {
        if (liveUsers.isNotEmpty()) {
            // 将LiveUserItem转换为LiveRoom对象
            val liveRooms = liveUsers.mapIndexed { index, item ->
                LiveRoom(
                    id = index + 1,
                    roomId = item.roomId,
                    coverUrl = item.roomCover, // 使用直播间封面
                    anchorName = item.uname,
                    anchorAvatar = item.face, // 主播头像
                    roomTitle = item.title, // 直播间标题
                    areaName = item.areaNameV2 // 直播间分区
                )
            }
            
            // 更新GridView数据
            updateGridViewData(liveRooms)
        } else {
            Toast.makeText(this, "暂无关注的直播", Toast.LENGTH_SHORT).show()
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
        
        // 延迟设置焦点到第一个项目，确保数据已加载完成
        gridView.postDelayed({
            if (liveRooms.isNotEmpty()) {
                // 设置第一个项目获得焦点
                val firstItem = gridView.getChildAt(0)
                if (firstItem != null) {
                    firstItem.requestFocus()
                } else {
                    // 如果直接获取子项失败，尝试通过滚动到位置0并请求焦点
                    gridView.scrollToPosition(0)
                    gridView.postDelayed({
                        val firstItemAfterScroll = gridView.getChildAt(0)
                        firstItemAfterScroll?.requestFocus()
                    }, 100)
                }
            }
        }, 200)
    }

    // 根据登录状态更新UI
    private fun updateUIAccordingToLoginState() {
        val userInfoContainer = findViewById<android.widget.LinearLayout>(R.id.user_info_container)
        val loginButtonContainer = findViewById<android.widget.LinearLayout>(R.id.login_button_container)
        val gridView = findViewById<androidx.leanback.widget.VerticalGridView>(R.id.main_grid)

        if (TokenManager.isLoggedIn(this)) {
            // 已登录状态
            userInfoContainer.visibility = View.VISIBLE
            loginButtonContainer.visibility = View.GONE
            gridView.visibility = View.VISIBLE
            
            // 获取用户信息
            fetchUserInfo()
            
            // 获取真实的直播间数据
            fetchLiveUsers()
        } else {
            // 未登录状态
            userInfoContainer.visibility = View.GONE
            loginButtonContainer.visibility = View.VISIBLE
            gridView.visibility = View.GONE
            
            // 延迟设置焦点到登录按钮
            loginButtonContainer.postDelayed({
                val loginButton = findViewById<android.widget.FrameLayout>(R.id.login_button)
                loginButton.requestFocus()
            }, 100)
        }
    }

    // 设置登录按钮点击事件
    private fun setupLoginButton() {
        val loginButton = findViewById<android.widget.FrameLayout>(R.id.login_button)
        loginButton.setOnClickListener {
            // 跳转到登录Activity
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
        }
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

    // 显示用户菜单
    private fun showUserMenu(anchorView: android.view.View) {
        val popupMenu = android.widget.PopupMenu(this, anchorView)
        
        // 动态添加菜单项
        popupMenu.menu.add(0, 0, 0, "登出")
        
        popupMenu.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == 0) {
                logout()
                true
            } else {
                false
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
        Toast.makeText(this, "退出登录成功", Toast.LENGTH_SHORT).show()
    }



    override fun onResume() {
        super.onResume()
        // 每次返回主界面时，检查登录状态并更新UI
        updateUIAccordingToLoginState()
    }
    
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_MENU) {
            // 检查是否已登录且在直播间列表页面
            if (TokenManager.isLoggedIn(this)) {
                val gridView = findViewById<androidx.leanback.widget.VerticalGridView>(R.id.main_grid)
                if (gridView.visibility == View.VISIBLE) {
                    // 刷新直播间列表
                    fetchLiveUsers()
                    // 显示刷新提示
                    Toast.makeText(this, "正在刷新直播间列表...", Toast.LENGTH_SHORT).show()
                    return true
                }
            }
        }
        
        
        return super.onKeyDown(keyCode, event)
    }
    
    // 生成模拟数据
    private fun generateMockData(): List<LiveRoom> {
        return listOf(
            LiveRoom(1, 80397L, "", "主播1", "", "精彩直播，不容错过！", "游戏"),
            LiveRoom(2, 80398L, "", "主播2", "", "欢迎来到我的直播间！", "娱乐"),
            LiveRoom(3, 80399L, "", "主播3", "", "今天给大家带来精彩内容！", "音乐"),
            LiveRoom(4, 80400L, "", "主播4", "", "感谢大家的支持！", "舞蹈"),
            LiveRoom(5, 80401L, "", "主播5", "", "欢迎新朋友！", "游戏"),
            LiveRoom(6, 80402L, "", "主播6", "", "精彩继续！", "娱乐"),
            LiveRoom(7, 80403L, "", "主播7", "", "大家晚上好！", "音乐"),
            LiveRoom(8, 80404L, "", "主播8", "", "今天的直播很精彩！", "舞蹈")
        )
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
                            if (adapterPosition >= 0 && adapterPosition < 4) {
                                lastFocusPositionFromGrid = adapterPosition
                                val userAvatarContainer = itemView.rootView.findViewById<android.widget.FrameLayout>(R.id.user_avatar_container)
                                userAvatarContainer.requestFocus()
                                return@setOnKeyListener true
                            }
                        } else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                            // 如果是最后一个Item，按右键时保持焦点，防止焦点丢失
                            if (adapterPosition == itemCount - 1) {
                                return@setOnKeyListener true
                            }
                        }
                    }
                    false
                }
                
                // 设置点击事件，点击卡片跳转到播放页面
                itemView.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION && pos >= 0 && pos < liveRooms.size) {
                        val liveRoom = liveRooms[pos]
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
