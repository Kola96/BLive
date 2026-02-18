@file:Suppress("DEPRECATION")

package com.blive.tv.ui.play

import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blive.tv.R
import com.blive.tv.data.model.RoomPlayInfoResponse
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.blive.tv.danmu.DanmuItem
import com.blive.tv.danmu.DanmuMessage
import com.blive.tv.danmu.DanmuTcpClient
import com.blive.tv.danmu.SimpleDanmuView
import com.blive.tv.network.RetrofitClient
import com.blive.tv.utils.TokenManager
import com.blive.tv.utils.UserPreferencesManager

class LivePlayActivity : AppCompatActivity() {

    private lateinit var playerView: com.google.android.exoplayer2.ui.PlayerView
    private lateinit var loadingProgress: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var settingsPanel: View
    private lateinit var playSettingsRecyclerView: RecyclerView
    private lateinit var danmuSettingsRecyclerView: RecyclerView
    private lateinit var simpleDanmuView: SimpleDanmuView
    
    private var player: ExoPlayer? = null
    private var currentRoomId: Long = -1L
    private var playUrlList: List<String> = emptyList()
    private var currentUrlIndex: Int = 0
    
    // 弹幕TCP客户端
    private lateinit var danmuTcpClient: DanmuTcpClient
    
    private var qualityOptions: List<QualityOption> = emptyList()
    private var cdnOptions: List<CdnOption> = emptyList()
    private var codecOptions: List<CodecOption> = emptyList()
    private var danmuSpeedOptions: List<DanmuSpeedOption> = emptyList()
    private var danmuOpacityOptions: List<DanmuOpacityOption> = emptyList()
    private var danmuSizeOptions: List<DanmuSizeOption> = emptyList()
    
    private var selectedQn: Int = 10000
    private var selectedCdnHost: String = ""
    private var selectedCodec: String = "avc"
    private var selectedDanmuEnable: Boolean = true
    private var selectedDanmuSpeed: Float = 1.0f
    private var selectedDanmuOpacity: Float = 1.0f
    private var selectedDanmuSize: Float = 1.0f
    
    private var isSettingsVisible: Boolean = false
    private var currentExpandedCategory: String? = null
    
    private lateinit var playSettingsAdapter: PlaySettingsCategoryAdapter
    private lateinit var danmuSettingsAdapter: PlaySettingsCategoryAdapter

    companion object {
        private const val TAG = "LivePlayActivity"
        private const val CATEGORY_QUALITY = "quality"
        private const val CATEGORY_CDN = "cdn"
        private const val CATEGORY_CODEC = "codec"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_play)

        playerView = findViewById(R.id.player_view)
        loadingProgress = findViewById(R.id.loading_progress)
        errorText = findViewById(R.id.error_text)
        settingsPanel = findViewById(R.id.settings_panel)
        playSettingsRecyclerView = findViewById(R.id.play_settings_recycler_view)
        danmuSettingsRecyclerView = findViewById(R.id.danmu_settings_recycler_view)
        simpleDanmuView = findViewById(R.id.simple_danmu_view)
        
        // Initialize from Preferences
        selectedQn = UserPreferencesManager.getQualityQn(this)
        selectedDanmuEnable = UserPreferencesManager.isDanmakuEnabled(this)
        selectedDanmuSize = UserPreferencesManager.getDanmakuSizeScale(this)
        selectedDanmuOpacity = UserPreferencesManager.getDanmakuAlpha(this)
        
        // Apply danmaku settings
        simpleDanmuView.isDanmuEnabled = selectedDanmuEnable
        simpleDanmuView.danmuSizeScale = selectedDanmuSize
        simpleDanmuView.danmuAlpha = selectedDanmuOpacity
        
        currentRoomId = intent.getLongExtra("room_id", -1L)
        Log.d(TAG, "开始播放直播间，roomId: $currentRoomId")

        // 初始化弹幕TCP客户端
        danmuTcpClient = DanmuTcpClient(
            roomId = currentRoomId,
            onDanmuReceived = { danmuMessages ->
                handleDanmuMessages(danmuMessages)
            },
            onConnectionStatusChanged = { status ->
                Log.d(TAG, "弹幕连接状态变化: $status")
            }
        )
        
        if (currentRoomId == -1L) {
            Log.e(TAG, "直播间ID无效")
            showError("直播间ID无效")
            return
        }

        setupRecyclerViews()
        fetchRoomPlayInfo(currentRoomId)
    }
    
    private fun setupRecyclerViews() {
        playSettingsRecyclerView.layoutManager = LinearLayoutManager(this)
        danmuSettingsRecyclerView.layoutManager = LinearLayoutManager(this)
        
        // Add spacing item decoration
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
                    // Add top margin for categories (except the first item)
                    if (viewType == 0 && position > 0) { // 0 is TYPE_CATEGORY
                        outRect.top = (16 * view.context.resources.displayMetrics.density).toInt()
                    }
                }
            }
        }
        playSettingsRecyclerView.addItemDecoration(spacingDecoration)
        danmuSettingsRecyclerView.addItemDecoration(spacingDecoration)

        playSettingsAdapter = PlaySettingsCategoryAdapter(
            items = emptyList(),
            onItemClick = { item ->
                onSettingsItemClicked(item)
            }
        )
        
        danmuSettingsAdapter = PlaySettingsCategoryAdapter(
            items = emptyList(),
            onItemClick = { item ->
                onSettingsItemClicked(item)
            }
        )
        
        playSettingsRecyclerView.adapter = playSettingsAdapter
        danmuSettingsRecyclerView.adapter = danmuSettingsAdapter
    }
    
    // 新增弹幕设置常量
    private val CATEGORY_DANMU_ENABLE = "danmu_enable"
    private val CATEGORY_DANMU_SPEED = "danmu_speed"
    private val CATEGORY_DANMU_OPACITY = "danmu_opacity"
    private val CATEGORY_DANMU_SIZE = "danmu_size"

    private fun onSettingsItemClicked(item: SettingsItem) {
        if (item is PlaySettingsCategory) {
            onCategoryClicked(item)
        } else if (item is PlaySettingsOption) {
            onOptionClicked(item)
        }
    }

    private fun onCategoryClicked(category: PlaySettingsCategory) {
        Log.d(TAG, "onCategoryClicked - category: ${category.name}, id: ${category.id}")
        
        // 所有设置项都支持展开/折叠
        if (currentExpandedCategory == category.id) {
            collapseAllCategories(focusCategoryId = category.id)
        } else {
            expandCategory(category.id)
        }
    }
    
    private fun onOptionClicked(option: PlaySettingsOption) {
        Log.d(TAG, "onOptionClicked - option: ${option.name}, id: ${option.id}, categoryId: ${option.categoryId}")
        
        when (option.categoryId) {
            CATEGORY_QUALITY -> {
                selectedQn = option.id.toInt()
                Log.d(TAG, "切换清晰度: ${option.name} ($selectedQn)")
                rebuildAndPlayUrl()
                updateCategories(focusTargetId = option.id)
            }
            CATEGORY_CDN -> {
                selectedCdnHost = option.id
                Log.d(TAG, "切换CDN: ${option.name}")
                rebuildAndPlayUrl()
                updateCategories(focusTargetId = option.id)
            }
            CATEGORY_CODEC -> {
                selectedCodec = option.id
                Log.d(TAG, "切换编码: ${option.name}")
                rebuildAndPlayUrl()
                updateCategories(focusTargetId = option.id)
            }
            CATEGORY_DANMU_ENABLE -> {
                val enable = option.id == "1"
                selectedDanmuEnable = enable
                handleDanmuEnableSelection(enable)
                updateCategories(focusTargetId = option.id)
            }
            CATEGORY_DANMU_SPEED -> {
                selectedDanmuSpeed = option.id.toFloat()
                Log.d(TAG, "切换弹幕速度: ${option.name}")
                handleDanmuSpeedSelection(selectedDanmuSpeed)
                updateCategories(focusTargetId = option.id)
            }
            CATEGORY_DANMU_OPACITY -> {
                selectedDanmuOpacity = option.id.toFloat()
                Log.d(TAG, "切换弹幕透明度: ${option.name}")
                handleDanmuOpacitySelection(selectedDanmuOpacity)
                updateCategories(focusTargetId = option.id)
            }
            CATEGORY_DANMU_SIZE -> {
                selectedDanmuSize = option.id.toFloat()
                Log.d(TAG, "切换弹幕字号: ${option.name}")
                handleDanmuSizeSelection(selectedDanmuSize)
                updateCategories(focusTargetId = option.id)
            }
        }
    }
    
    private fun expandCategory(categoryId: String) {
        Log.d(TAG, "expandCategory - categoryId: $categoryId")
        currentExpandedCategory = categoryId
        updateCategories(shouldFocusSelectedOption = true)
    }

    private fun collapseAllCategories(focusCategoryId: String? = null) {
        Log.d(TAG, "collapseAllCategories - focusCategoryId: $focusCategoryId")
        currentExpandedCategory = null
        updateCategories(focusTargetId = focusCategoryId)
    }
    
    private fun updateCategories(focusTargetId: String? = null, shouldFocusSelectedOption: Boolean = false) {
        // --- 播放设置组 ---
        val playCategories = mutableListOf<PlaySettingsCategory>()
        
        // 1. 画质
        playCategories.add(
            PlaySettingsCategory(
                id = CATEGORY_QUALITY,
                name = "画质",
                currentValue = qualityOptions.find { it.qn == selectedQn }?.name ?: "未知",
                isExpanded = currentExpandedCategory == CATEGORY_QUALITY
            )
        )
        
        // 2. 线路
        playCategories.add(
            PlaySettingsCategory(
                id = CATEGORY_CDN,
                name = "线路",
                currentValue = cdnOptions.find { it.host == selectedCdnHost }?.cdnName ?: "未知",
                isExpanded = currentExpandedCategory == CATEGORY_CDN
            )
        )
        
        // 3. 编码
        playCategories.add(
            PlaySettingsCategory(
                id = CATEGORY_CODEC,
                name = "编码",
                currentValue = codecOptions.find { it.codecName == selectedCodec }?.displayName ?: "未知",
                isExpanded = currentExpandedCategory == CATEGORY_CODEC
            )
        )
        
        // 构建播放设置显示列表
        val playDisplayList = mutableListOf<SettingsItem>()
        val data = response?.data
        
        for (category in playCategories) {
            playDisplayList.add(category)
            if (category.isExpanded) {
                when (category.id) {
                    CATEGORY_QUALITY -> {
                        val filteredOptions = qualityOptions.filter { option ->
                            data != null && 
                            findStreamUrl(data, "http_stream", "flv", selectedCodec, option.qn, selectedCdnHost).isNotEmpty() ||
                            data != null && 
                            findStreamUrl(data, "http_stream", "ts", selectedCodec, option.qn, selectedCdnHost).isNotEmpty()
                        }
                        playDisplayList.addAll(filteredOptions.map { 
                            PlaySettingsOption(it.qn.toString(), it.name, it.qn == selectedQn, category.id) 
                        })
                    }
                    CATEGORY_CDN -> {
                        val filteredOptions = cdnOptions.filter { option ->
                            data != null && 
                            findStreamUrl(data, "http_stream", "flv", selectedCodec, selectedQn, option.host).isNotEmpty() ||
                            data != null && 
                            findStreamUrl(data, "http_stream", "ts", selectedCodec, selectedQn, option.host).isNotEmpty()
                        }
                        playDisplayList.addAll(filteredOptions.map { 
                            PlaySettingsOption(it.host, it.cdnName, it.host == selectedCdnHost, category.id) 
                        })
                    }
                    CATEGORY_CODEC -> {
                        val filteredOptions = codecOptions.filter { option ->
                            data != null && 
                            findStreamUrl(data, "http_stream", "flv", option.codecName, selectedQn, selectedCdnHost).isNotEmpty() ||
                            data != null && 
                            findStreamUrl(data, "http_stream", "ts", option.codecName, selectedQn, selectedCdnHost).isNotEmpty()
                        }
                        playDisplayList.addAll(filteredOptions.map { 
                            PlaySettingsOption(it.codecName, it.displayName, it.codecName == selectedCodec, category.id) 
                        })
                    }
                }
            }
        }
        
        playSettingsAdapter.updateItems(playDisplayList)
        
        
        // --- 弹幕设置组 ---
        val danmuCategories = mutableListOf<PlaySettingsCategory>()
        
        // 1. 开关
        danmuCategories.add(
            PlaySettingsCategory(
                id = CATEGORY_DANMU_ENABLE,
                name = "开关",
                currentValue = if (selectedDanmuEnable) "开启" else "关闭",
                isExpanded = currentExpandedCategory == CATEGORY_DANMU_ENABLE
            )
        )
        
        // 2. 速度
        // TODO: 暂时屏蔽速度设置，因为动态变速实现有问题
        /*
        danmuCategories.add(
            PlaySettingsCategory(
                id = CATEGORY_DANMU_SPEED,
                name = "速度",
                currentValue = danmuSpeedOptions.find { it.speed == selectedDanmuSpeed }?.name ?: "正常",
                isExpanded = currentExpandedCategory == CATEGORY_DANMU_SPEED
            )
        )
        */
        
        // 3. 透明度
        danmuCategories.add(
            PlaySettingsCategory(
                id = CATEGORY_DANMU_OPACITY,
                name = "不透明度",
                currentValue = danmuOpacityOptions.find { it.opacity == selectedDanmuOpacity }?.name ?: "100%",
                isExpanded = currentExpandedCategory == CATEGORY_DANMU_OPACITY
            )
        )
        
        // 4. 字号
        danmuCategories.add(
            PlaySettingsCategory(
                id = CATEGORY_DANMU_SIZE,
                name = "大小",
                currentValue = danmuSizeOptions.find { it.scale == selectedDanmuSize }?.name ?: "100%",
                isExpanded = currentExpandedCategory == CATEGORY_DANMU_SIZE
            )
        )
        
        // 构建弹幕设置显示列表
        val danmuDisplayList = mutableListOf<SettingsItem>()
        
        for (category in danmuCategories) {
            danmuDisplayList.add(category)
            if (category.isExpanded) {
                when (category.id) {
                    CATEGORY_DANMU_ENABLE -> {
                        danmuDisplayList.add(PlaySettingsOption("1", "开启", selectedDanmuEnable, category.id))
                        danmuDisplayList.add(PlaySettingsOption("0", "关闭", !selectedDanmuEnable, category.id))
                    }
                    CATEGORY_DANMU_SPEED -> {
                        danmuDisplayList.addAll(danmuSpeedOptions.map { 
                            PlaySettingsOption(it.speed.toString(), it.name, it.speed == selectedDanmuSpeed, category.id) 
                        })
                    }
                    CATEGORY_DANMU_OPACITY -> {
                        danmuDisplayList.addAll(danmuOpacityOptions.map { 
                            PlaySettingsOption(it.opacity.toString(), it.name, it.opacity == selectedDanmuOpacity, category.id) 
                        })
                    }
                    CATEGORY_DANMU_SIZE -> {
                        danmuDisplayList.addAll(danmuSizeOptions.map { 
                            PlaySettingsOption(it.scale.toString(), it.name, it.scale == selectedDanmuSize, category.id) 
                        })
                    }
                }
            }
        }
        
        danmuSettingsAdapter.updateItems(danmuDisplayList)

        // 处理焦点恢复
        if (focusTargetId != null || shouldFocusSelectedOption) {
            // 延迟一帧等待 RecyclerView 更新
            playSettingsRecyclerView.post {
                restoreFocus(playSettingsRecyclerView, playSettingsAdapter, focusTargetId, shouldFocusSelectedOption)
            }
            danmuSettingsRecyclerView.post {
                restoreFocus(danmuSettingsRecyclerView, danmuSettingsAdapter, focusTargetId, shouldFocusSelectedOption)
            }
        }
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
            // 尝试找到目标ID对应的位置
            position = items.indexOfFirst { it.id == targetId }
        }
        
        if (position == -1 && focusSelected && currentExpandedCategory != null) {
            // 找到当前展开的 Category 下选中的 Option
            position = items.indexOfFirst { 
                it is PlaySettingsOption && it.categoryId == currentExpandedCategory && it.isSelected 
            }
            
            // 如果没有选中的（不应该），找第一个 Option
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
                // 如果 ViewHolder 还没创建（例如在屏幕外），让 RecyclerView 滚动过去
                recyclerView.scrollToPosition(position)
                // 滚动后再次尝试聚焦
                recyclerView.post {
                    val vh = recyclerView.findViewHolderForAdapterPosition(position)
                    vh?.itemView?.requestFocus()
                }
            }
        }
    }

    /**
     * 处理弹幕字号选择
     */
    private fun handleDanmuSizeSelection(scale: Float) {
        selectedDanmuSize = scale
        Log.d(TAG, "切换弹幕字号: $scale")
        // 更新弹幕容器字号
        simpleDanmuView.danmuSizeScale = scale
    }
    
    private fun rebuildAndPlayUrl() {
        val url = buildPlayUrlWithSelection()
        if (url.isNotEmpty()) {
            Log.d(TAG, "重新构建播放URL: $url")
            player?.release()
            player = null
            initializePlayer(url)
        } else {
            Toast.makeText(this, "该配置下没有可用的流", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fetchRoomPlayInfo(roomId: Long) {
        val sessData = TokenManager.getSessData(this)
        val cookie = if (sessData != null) "SESSDATA=$sessData" else ""
        
        Log.d(TAG, "请求直播间播放信息，roomId: $roomId, cookie: $cookie")
        
        RetrofitClient.liveApiService.getRoomPlayInfo(cookie, roomId).enqueue(
            object : retrofit2.Callback<RoomPlayInfoResponse> {
                override fun onResponse(
                    call: retrofit2.Call<RoomPlayInfoResponse>,
                    response: retrofit2.Response<RoomPlayInfoResponse>
                ) {
                    Log.d(TAG, "API响应码: ${response.code()}")
                    
                    if (response.isSuccessful) {
                        val playInfoResponse = response.body()
                        // Log.d(TAG, "响应body: $playInfoResponse")
                        
                        if (playInfoResponse != null && playInfoResponse.code == 0) {
                            Log.d(TAG, "API返回成功，live_status: ${playInfoResponse.data?.liveStatus}")
                            
                            parsePlayOptions(playInfoResponse)
                            playUrlList = buildAllPlayUrls(playInfoResponse)
                            
                            if (playUrlList.isNotEmpty()) {
                                Log.d(TAG, "成功构建 ${playUrlList.size} 个播放URL")
                                currentUrlIndex = 0
                                initializePlayer(playUrlList[currentUrlIndex])
                            } else {
                                Log.e(TAG, "无法构建播放URL")
                                showError("无法获取播放地址")
                            }
                            
                            // 获取弹幕服务器信息，无论是否登录都尝试启动弹幕客户端
                            fetchDanmuInfo()
                        } else {
                            Log.e(TAG, "API返回错误，code: ${playInfoResponse?.code}, message: ${playInfoResponse?.message}")
                            showError("获取播放信息失败：${playInfoResponse?.message}")
                            
                            // 即使API返回错误，也尝试启动弹幕客户端
                            fetchDanmuInfo()
                        }
                    } else {
                        Log.e(TAG, "网络请求失败，响应码: ${response.code()}")
                        showError("网络请求失败：${response.code()}")
                        
                        // 即使网络请求失败，也尝试启动弹幕客户端
                        fetchDanmuInfo()
                    }
                }

                override fun onFailure(
                    call: retrofit2.Call<RoomPlayInfoResponse>,
                    t: Throwable
                ) {
                    Log.e(TAG, "网络连接错误", t)
                    showError("网络连接错误：${t.message}")
                    
                    // 即使网络请求失败，也尝试启动弹幕客户端
                    fetchDanmuInfo()
                }
            }
        )
    }
    
    /**
     * 获取弹幕服务器信息 - 已废弃，改用DanmuTcpClient内部实现
     */
    private fun fetchDanmuInfo() {
        Log.d(TAG, "开始启动弹幕TCP客户端")
        danmuTcpClient.start()
    }
    

    
    private fun parsePlayOptions(response: RoomPlayInfoResponse) {
        val data = response.data ?: return
        
        val qualitySet = mutableSetOf<Int>()
        val cdnSet = mutableSetOf<String>()
        val codecSet = mutableSetOf<String>()
        
        for (stream in data.playurlInfo.playurl.stream) {
            for (format in stream.format) {
                for (codec in format.codec) {
                    codecSet.add(codec.codecName)
                    qualitySet.addAll(codec.acceptQn)
                    for (urlInfo in codec.urlInfo) {
                        val cdnName = urlInfo.host.substringAfter("://").substringBefore(".")
                        cdnSet.add(cdnName)
                    }
                }
            }
        }
        
        // Calculate best quality based on preferences
        val prefQn = UserPreferencesManager.getQualityQn(this)
        if (qualitySet.isNotEmpty()) {
            if (qualitySet.contains(prefQn)) {
                selectedQn = prefQn
            } else {
                val maxQn = qualitySet.maxOrNull() ?: 0
                val minQn = qualitySet.minOrNull() ?: 0
                
                if (maxQn < prefQn) {
                    selectedQn = maxQn
                } else if (minQn > prefQn) {
                    selectedQn = minQn
                } else {
                    // Find largest qn in qualitySet that is < prefQn
                    selectedQn = qualitySet.filter { it < prefQn }.maxOrNull() ?: maxQn
                }
            }
        }

        qualityOptions = qualitySet.map { qn ->
            val name = when (qn) {
                10000 -> "原画"
                400 -> "蓝光"
                250 -> "超清"
                150 -> "高清"
                80 -> "流畅"
                else -> "未知($qn)"
            }
            QualityOption(qn, name, isSelected = qn == selectedQn)
        }.sortedByDescending { it.qn }
        
        cdnOptions = cdnSet.map { cdnName ->
            CdnOption(cdnName, cdnName, isSelected = cdnName == selectedCdnHost)
        }
        
        codecOptions = codecSet.map { codecName ->
            val displayName = when (codecName) {
                "avc" -> "H.264 (AVC)"
                "hevc" -> "H.265 (HEVC)"
                else -> codecName.uppercase()
            }
            CodecOption(codecName, displayName, isSelected = codecName == selectedCodec)
        }
        
        if (selectedCdnHost.isEmpty() && cdnOptions.isNotEmpty()) {
            selectedCdnHost = cdnOptions[0].host
        }
        
        Log.d(TAG, "解析到 ${qualityOptions.size} 个清晰度选项")
        Log.d(TAG, "解析到 ${cdnOptions.size} 个CDN选项")
        Log.d(TAG, "解析到 ${codecOptions.size} 个编码选项")
        
        // 初始化弹幕速度选项
        danmuSpeedOptions = listOf(
            DanmuSpeedOption(0.5f, "慢速", isSelected = selectedDanmuSpeed == 0.5f),
            DanmuSpeedOption(1.0f, "正常", isSelected = selectedDanmuSpeed == 1.0f),
            DanmuSpeedOption(1.5f, "快速", isSelected = selectedDanmuSpeed == 1.5f),
            DanmuSpeedOption(2.0f, "极速", isSelected = selectedDanmuSpeed == 2.0f)
        )
        
        // 初始化弹幕透明度选项
        val rawOpacityOptions = listOf(0.25f, 0.5f, 0.75f, 1.0f)
        // 吸附到最近的有效值
        selectedDanmuOpacity = rawOpacityOptions.minByOrNull { Math.abs(it - selectedDanmuOpacity) } ?: 1.0f
        
        danmuOpacityOptions = listOf(
            DanmuOpacityOption(0.25f, "25%", isSelected = selectedDanmuOpacity == 0.25f),
            DanmuOpacityOption(0.5f, "50%", isSelected = selectedDanmuOpacity == 0.5f),
            DanmuOpacityOption(0.75f, "75%", isSelected = selectedDanmuOpacity == 0.75f),
            DanmuOpacityOption(1.0f, "100%", isSelected = selectedDanmuOpacity == 1.0f)
        )
        
        // 初始化弹幕字号选项
        val rawSizeOptions = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f)
        // 吸附到最近的有效值
        selectedDanmuSize = rawSizeOptions.minByOrNull { Math.abs(it - selectedDanmuSize) } ?: 1.0f
        
        danmuSizeOptions = listOf(
            DanmuSizeOption(0.5f, "50%", isSelected = selectedDanmuSize == 0.5f),
            DanmuSizeOption(0.75f, "75%", isSelected = selectedDanmuSize == 0.75f),
            DanmuSizeOption(1.0f, "100%", isSelected = selectedDanmuSize == 1.0f),
            DanmuSizeOption(1.5f, "150%", isSelected = selectedDanmuSize == 1.5f),
            DanmuSizeOption(2.0f, "200%", isSelected = selectedDanmuSize == 2.0f)
        )
        
        // 更新弹幕View的设置以保持一致
        simpleDanmuView.danmuAlpha = selectedDanmuOpacity
        simpleDanmuView.danmuSizeScale = selectedDanmuSize
        
        updateCategories()
    }
    
    private fun buildPlayUrlWithSelection(): String {
        val data = response?.data ?: return ""
        
        val preferredProtocolList = listOf("http_stream", "http_hls")
        val preferredFormatList = listOf("flv", "ts", "fmp4")
        
        for (protocol in preferredProtocolList) {
            for (format in preferredFormatList) {
                val url = findStreamUrl(data, protocol, format, selectedCodec, selectedQn, selectedCdnHost)
                if (url.isNotEmpty()) {
                    return url
                }
            }
        }
        
        return ""
    }
    
    private var response: RoomPlayInfoResponse? = null

    private fun buildAllPlayUrls(response: RoomPlayInfoResponse): List<String> {
        this.response = response
        val data = response.data ?: return emptyList()
        
        Log.d(TAG, "开始构建所有播放URL")
        Log.d(TAG, "可用协议数量: ${data.playurlInfo.playurl.stream.size}")

        val urlList = mutableListOf<String>()
        
        val preferredQnList = listOf(10000, 400, 250, 150, 80)
        val preferredProtocolList = listOf("http_stream", "http_hls")
        val preferredFormatList = listOf("flv", "ts", "fmp4")
        val preferredCodecList = listOf("avc", "hevc")
        
        for (protocol in preferredProtocolList) {
            for (format in preferredFormatList) {
                for (codec in preferredCodecList) {
                    for (qn in preferredQnList) {
                        val urls = findStreamUrls(data, protocol, format, codec, qn)
                        urlList.addAll(urls)
                    }
                }
            }
        }
        
        Log.d(TAG, "总共构建了 ${urlList.size} 个播放URL")
        return urlList
    }
    
    private fun findStreamUrl(
        data: com.blive.tv.data.model.RoomPlayInfoData,
        targetProtocol: String,
        targetFormat: String,
        targetCodec: String,
        targetQn: Int,
        targetCdn: String
    ): String {
        for (stream in data.playurlInfo.playurl.stream) {
            if (stream.protocolName == targetProtocol) {
                for (format in stream.format) {
                    if (format.formatName == targetFormat) {
                        for (codec in format.codec) {
                            if (codec.codecName == targetCodec && codec.acceptQn.contains(targetQn)) {
                                for (urlInfo in codec.urlInfo) {
                                    val cdnName = urlInfo.host.substringAfter("://").substringBefore(".")
                                    if (targetCdn.isEmpty() || cdnName == targetCdn) {
                                        val host = urlInfo.host.trim()
                                        val baseUrl = codec.baseUrl
                                        val extra = urlInfo.extra
                                        return "$host$baseUrl$extra"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return ""
    }
    
    private fun findStreamUrls(
        data: com.blive.tv.data.model.RoomPlayInfoData,
        targetProtocol: String,
        targetFormat: String,
        targetCodec: String,
        targetQn: Int
    ): List<String> {
        val urls = mutableListOf<String>()
        
        for (stream in data.playurlInfo.playurl.stream) {
            if (stream.protocolName == targetProtocol) {
                for (format in stream.format) {
                    if (format.formatName == targetFormat) {
                        for (codec in format.codec) {
                            if (codec.codecName == targetCodec && codec.acceptQn.contains(targetQn)) {
                                for (urlInfo in codec.urlInfo) {
                                    val host = urlInfo.host.trim()
                                    val baseUrl = codec.baseUrl
                                    val extra = urlInfo.extra
                                    val fullUrl = "$host$baseUrl$extra"
                                    
                                    // Log.d(TAG, "找到流: $targetProtocol/$targetFormat/$targetCodec/$qnName($targetQn) - CDN: ${host.substringAfter("://").substringBefore(".")}")
                                    urls.add(fullUrl)
                                }
                            }
                        }
                    }
                }
            }
        }
        
        return urls
    }

    private fun initializePlayer(url: String) {
        loadingProgress.visibility = View.VISIBLE
        errorText.visibility = View.GONE

        Log.d(TAG, "初始化播放器，当前URL索引: $currentUrlIndex，总URL数: ${playUrlList.size}")
        Log.d(TAG, "播放URL: $url")

        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            playerView.player = exoPlayer
            val mediaItem = MediaItem.fromUri(url)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true

            Log.d(TAG, "播放器准备完成，开始播放")

            exoPlayer.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_IDLE -> {
                            Log.d(TAG, "播放状态: IDLE")
                            val error = exoPlayer.playerError
                            if (error != null) {
                                Log.e(TAG, "播放失败，错误类型: ${error.javaClass.simpleName}")
                                Log.e(TAG, "错误消息: ${error.message}")
                                Log.e(TAG, "错误详情: ${error.errorCodeName}")
                                Log.e(TAG, "错误堆栈", error)
                                tryNextUrl(error)
                            }
                        }
                        Player.STATE_BUFFERING -> {
                            Log.d(TAG, "播放状态: BUFFERING (缓冲中)")
                        }
                        Player.STATE_READY -> {
                            Log.d(TAG, "播放状态: READY (准备就绪)")
                            loadingProgress.visibility = View.GONE
                            // 真实弹幕已通过WebSocket接入，不再需要测试弹幕
                            
                            // 确保SimpleDanmuView可见
                            simpleDanmuView.visibility = View.VISIBLE
                        }
                        Player.STATE_ENDED -> {
                            Log.d(TAG, "播放状态: ENDED (播放结束)")
                        }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "播放器错误: ${error.javaClass.simpleName}")
                    Log.e(TAG, "错误消息: ${error.message}")
                    Log.e(TAG, "错误代码: ${error.errorCode}")
                    Log.e(TAG, "错误代码名称: ${error.errorCodeName}")
                    Log.e(TAG, "是否可恢复: ${error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED}")
                    Log.e(TAG, "错误堆栈", error)
                    
                    tryNextUrl(error)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Log.d(TAG, "播放状态变化: $isPlaying")
                }

                override fun onVideoSizeChanged(videoSize: com.google.android.exoplayer2.video.VideoSize) {
                    Log.d(TAG, "视频尺寸: ${videoSize.width}x${videoSize.height}")
                }
            })
        }
    }
    
    private fun tryNextUrl(error: PlaybackException) {
        currentUrlIndex++
        
        if (currentUrlIndex < playUrlList.size) {
            Log.w(TAG, "当前URL播放失败，尝试下一个URL ($currentUrlIndex/${playUrlList.size})")
            
            player?.release()
            player = null
            
            val delay = 500L
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                initializePlayer(playUrlList[currentUrlIndex])
            }, delay)
        } else {
            Log.e(TAG, "所有URL都尝试过了，播放失败")
            
            val errorMessage = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "网络错误，请检查网络连接"
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败"
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接超时"
                PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "无效的内容类型"
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "HTTP状态错误，已尝试所有可用流"
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件未找到"
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码器初始化失败"
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "解码器查询失败"
                else -> "播放错误：${error.message}"
            }
            
            showError(errorMessage)
        }
    }

    private fun showError(message: String) {
        loadingProgress.visibility = View.GONE
        errorText.visibility = View.VISIBLE
        errorText.text = message
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    
    private fun toggleSettingsPanel() {
        isSettingsVisible = !isSettingsVisible
        
        if (isSettingsVisible) {
            Log.d(TAG, "显示设置面板")
            settingsPanel.visibility = View.VISIBLE
            
            // 确保焦点正确设置到画质选项
            settingsPanel.post {
                val categories = playSettingsAdapter.getCategories()
                val position = categories.indexOfFirst { it.id == CATEGORY_QUALITY }
                if (position >= 0) {
                    val viewHolder = playSettingsRecyclerView.findViewHolderForAdapterPosition(position)
                    if (viewHolder != null) {
                        viewHolder.itemView.requestFocus()
                        Log.d(TAG, "显示设置面板 - 请求焦点到画质选项成功")
                    } else {
                        playSettingsRecyclerView.requestFocus()
                        Log.d(TAG, "显示设置面板 - 请求焦点到playSettingsRecyclerView")
                    }
                } else {
                    playSettingsRecyclerView.requestFocus()
                    Log.d(TAG, "显示设置面板 - 未找到画质选项，请求焦点到playSettingsRecyclerView")
                }
            }
        } else {
            settingsPanel.visibility = View.GONE
            collapseAllCategories()
            settingsPanel.clearFocus()
            playerView.requestFocus()
            Log.d(TAG, "隐藏设置面板")
        }
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        Log.d(TAG, "onKeyDown - keyCode: $keyCode, isSettingsVisible: $isSettingsVisible, currentFocus: ${currentFocus?.javaClass?.simpleName}")
        
        // 当设置面板可见时，检查焦点是否有效
        if (isSettingsVisible) {
            val isFocusValid = isFocusOnValidItem()
            Log.d(TAG, "isFocusValid: $isFocusValid, currentFocus: ${currentFocus?.javaClass?.simpleName}")
            
            // 确认键或任意方向键，且焦点无效时，重置焦点到画质选项
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || 
                 keyCode == KeyEvent.KEYCODE_DPAD_UP || 
                 keyCode == KeyEvent.KEYCODE_DPAD_DOWN || 
                 keyCode == KeyEvent.KEYCODE_DPAD_LEFT || 
                 keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) && !isFocusValid) {
                Log.d(TAG, "设置面板可见但焦点无效，重置焦点到画质选项")
                resetFocusToQualityOption()
                return true
            }
        }
        
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                Log.d(TAG, "MENU key pressed")
                toggleSettingsPanel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                Log.d(TAG, "CENTER key pressed")
                // 焦点有效，让默认处理（例如点击当前焦点项）
                return super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_BACK -> {
                if (isSettingsVisible) {
                    if (currentExpandedCategory != null) {
                        collapseAllCategories(focusCategoryId = currentExpandedCategory)
                        return true
                    }
                    Log.d(TAG, "BACK key pressed while settings visible")
                    toggleSettingsPanel()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                Log.d(TAG, "DPAD_DOWN key pressed")
                if (!isSettingsVisible) {
                    toggleSettingsPanel()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                Log.d(TAG, "DPAD key pressed: $keyCode")
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    private fun isFocusOnValidItem(): Boolean {
        val currentFocusView = currentFocus ?: return false
        
        // 检查焦点是否在 RecyclerView 的 item 上
        val isOnCategoryItem = currentFocusView.parent is RecyclerView || 
                               (currentFocusView.parent as? View)?.parent is RecyclerView
        val isOnOptionItem = currentFocusView.parent is RecyclerView || 
                             (currentFocusView.parent as? View)?.parent is RecyclerView
        
        // 也检查焦点是否是 RecyclerView 本身
        val isOnRecyclerView = currentFocusView is RecyclerView
        
        Log.d(TAG, "isFocusOnValidItem - isOnCategoryItem: $isOnCategoryItem, isOnOptionItem: $isOnOptionItem, isOnRecyclerView: $isOnRecyclerView")
        
        return isOnCategoryItem || isOnOptionItem || isOnRecyclerView
    }
    
    private fun resetFocusToQualityOption() {
        val categories = playSettingsAdapter.getCategories()
        val position = categories.indexOfFirst { it.id == CATEGORY_QUALITY }
        if (position >= 0) {
            val viewHolder = playSettingsRecyclerView.findViewHolderForAdapterPosition(position)
            if (viewHolder != null) {
                viewHolder.itemView.requestFocus()
                Log.d(TAG, "resetFocusToQualityOption - 请求焦点到画质选项成功")
            } else {
                playSettingsRecyclerView.requestFocus()
                Log.d(TAG, "resetFocusToQualityOption - 请求焦点到playSettingsRecyclerView")
            }
        } else {
            playSettingsRecyclerView.requestFocus()
            Log.d(TAG, "resetFocusToQualityOption - 未找到画质选项，请求焦点到playSettingsRecyclerView")
        }
    }

    /**
     * 处理弹幕速度选择
     */
    private fun handleDanmuSpeedSelection(speed: Float) {
        if (selectedDanmuSpeed != speed) {
            selectedDanmuSpeed = speed
            Log.d(TAG, "切换弹幕速度: $speed")
            // 更新弹幕容器速度
            simpleDanmuView.danmuSpeedScale = speed
        }
    }
    
    /**
     * 处理弹幕开关选择
     */
    private fun handleDanmuEnableSelection(enable: Boolean) {
        Log.d(TAG, "切换弹幕开关: $enable")
        // 更新弹幕容器状态
        simpleDanmuView.isDanmuEnabled = enable
    }

    /**
     * 处理弹幕透明度选择
     */
    private fun handleDanmuOpacitySelection(opacity: Float) {
        selectedDanmuOpacity = opacity
        Log.d(TAG, "切换弹幕透明度: $opacity")
        // 更新弹幕容器透明度
        simpleDanmuView.danmuAlpha = opacity
    }
    
    /**
     * 处理接收到的弹幕消息
     */
    private fun handleDanmuMessages(danmuMessages: List<DanmuMessage>) {
        Log.d(TAG, "收到弹幕消息列表，数量: ${danmuMessages.size}")
        for (message in danmuMessages) {
            Log.d(TAG, "处理弹幕消息: $message")
            when (message) {
                is DanmuMessage.Danmu -> {
                    Log.d(TAG, "收到普通弹幕: ${message.content}, 颜色: ${message.color}, 模式: ${message.mode}")
                    // 转换为DanmuItem并添加到容器
                    val danmuItem = DanmuItem(
                        id = System.currentTimeMillis(),
                        text = message.content,
                        color = message.color,
                        speed = selectedDanmuSpeed,
                        type = when (message.mode) {
                            4 -> DanmuItem.TYPE_TOP
                            5 -> DanmuItem.TYPE_BOTTOM
                            else -> DanmuItem.TYPE_SCROLL
                        }
                    )
                    Log.d(TAG, "添加弹幕到容器: $danmuItem, 弹幕开关状态: $selectedDanmuEnable")
                    if (selectedDanmuEnable) {
                        simpleDanmuView.addDanmu(danmuItem)
                        Log.d(TAG, "弹幕已添加到容器")
                    } else {
                        Log.d(TAG, "弹幕开关已关闭，跳过添加弹幕")
                    }
                }
                is DanmuMessage.Gift -> {
                    Log.d(TAG, "收到礼物消息")
                }
                is DanmuMessage.EnterRoom -> {
                    Log.d(TAG, "用户进入房间")
                }
                is DanmuMessage.Other -> {
                    Log.d(TAG, "收到其他类型弹幕消息")
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
        // 释放弹幕资源
        simpleDanmuView.clear()
        // 停止弹幕TCP客户端
        danmuTcpClient.stop()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }
}