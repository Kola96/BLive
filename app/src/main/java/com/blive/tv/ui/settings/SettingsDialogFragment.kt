package com.blive.tv.ui.settings

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blive.tv.BuildConfig
import com.blive.tv.R
import com.blive.tv.data.update.UpdateRepository
import com.blive.tv.ui.settings.model.SettingItem
import com.blive.tv.ui.settings.model.SettingsCategory
import com.blive.tv.ui.settings.update.UpdateViewModel
import com.blive.tv.utils.UserPreferencesManager
import kotlinx.coroutines.launch

/**
 * 设置对话框（二级菜单版）：
 * - 左侧分类列：播放 / 弹幕 / 更新
 * - 右侧内容列：当前分类下的设置项
 *
 * 交互：
 * - DPAD_UP/DOWN 在左侧分类间移动，右侧内容同步刷新
 * - DPAD_RIGHT 从左侧进入右侧，DPAD_LEFT 从右侧第一列返回左侧
 * - BACK 关闭对话框
 */
class SettingsDialogFragment : DialogFragment() {

    private val updateViewModel: UpdateViewModel by activityViewModels()

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvSettings: RecyclerView
    private lateinit var btnClose: Button

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var settingItemAdapter: SettingItemAdapter

    // 记录每个分类右侧上次的焦点位置，切回来时恢复
    private val lastFocusPositions = mutableMapOf<SettingsCategory, Int>()

    private val qualityMap = linkedMapOf(
        "原画" to 10000,
        "蓝光" to 400,
        "超清" to 250,
        "高清" to 150,
        "流畅" to 80
    )

    private val danmakuSizes = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f)
    private val danmakuAlphas = listOf(0.25f, 0.5f, 0.75f, 1.0f)
    private val danmakuSpeeds = listOf(0.5f, 1.0f, 1.5f, 2.0f)
    private val danmakuAreas = listOf(1.0f, 0.5f, 0.25f)

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val displayMetrics = resources.displayMetrics
            val maxHeight = (displayMetrics.heightPixels * 0.80f).toInt()
            window.setLayout(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                maxHeight
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvCategories = view.findViewById(R.id.rv_categories)
        rvSettings = view.findViewById(R.id.rv_settings)
        btnClose = view.findViewById(R.id.btn_close)

        setupCategories()
        setupSettings()

        btnClose.setOnClickListener { dismiss() }

        observeUpdateCheckState()

        // 默认显示第一个分类
        renderSettingsFor(categoryAdapter.focusedCategory)

        // 默认焦点在左侧第一项
        rvCategories.post {
            rvCategories.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun setupCategories() {
        categoryAdapter = CategoryAdapter(
            categories = SettingsCategory.values().toList(),
            onCategoryFocused = { category ->
                renderSettingsFor(category)
            }
        )
        rvCategories.layoutManager = LinearLayoutManager(requireContext())
        rvCategories.adapter = categoryAdapter
    }

    private fun setupSettings() {
        settingItemAdapter = SettingItemAdapter(
            onNavigateBack = {
                // 从右侧按 LEFT 回到左侧分类列
                val position = categoryAdapter.focusedCategory.ordinal
                rvCategories.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        )
        rvSettings.layoutManager = LinearLayoutManager(requireContext())
        rvSettings.adapter = settingItemAdapter
    }

    /** 根据分类渲染右侧设置项 */
    private fun renderSettingsFor(category: SettingsCategory) {
        val items = when (category) {
            SettingsCategory.PLAYBACK -> buildPlaybackItems()
            SettingsCategory.DANMAKU -> buildDanmakuItems()
            SettingsCategory.UPDATE -> buildUpdateItems()
        }
        settingItemAdapter.submitList(items)

        // 恢复该分类上次的焦点位置
        val lastPos = lastFocusPositions[category] ?: 0
        rvSettings.post {
            if (rvSettings.hasFocus() || rvSettings.isFocused) {
                // 焦点已经在右侧，恢复位置
                settingItemAdapter.notifyDataSetChanged()
                rvSettings.findViewHolderForAdapterPosition(lastPos)?.itemView?.requestFocus()
            }
        }
    }

    // ---------------- 播放 ----------------

    private fun buildPlaybackItems(): List<SettingItem> {
        val ctx = requireContext()
        val currentQn = UserPreferencesManager.getQualityQn(ctx)
        val qualityNames = qualityMap.keys.toList()
        val currentQualityIndex = qualityNames.indexOfFirst { qualityMap[it] == currentQn }
            .takeIf { it >= 0 } ?: 0

        return listOf(
            SettingItem.CycleOption(
                key = "quality",
                label = getString(R.string.quality_label),
                options = qualityNames,
                currentIndex = currentQualityIndex,
                onChanged = { newIndex ->
                    val qn = qualityMap[qualityNames[newIndex]] ?: 10000
                    UserPreferencesManager.setQualityQn(ctx, qn)
                    refreshSettings()
                }
            )
        )
    }

    // ---------------- 弹幕 ----------------

    private fun buildDanmakuItems(): List<SettingItem> {
        val ctx = requireContext()

        val isEnabled = UserPreferencesManager.isDanmakuEnabled(ctx)
        val sizeScale = UserPreferencesManager.getDanmakuSizeScale(ctx)
        val alpha = UserPreferencesManager.getDanmakuAlpha(ctx)
        val speed = UserPreferencesManager.getDanmakuSpeed(ctx)
        val area = UserPreferencesManager.getDanmakuArea(ctx)

        val sizeIndex = danmakuSizes.indexOfFirst { Math.abs(it - sizeScale) < 0.01f }
            .takeIf { it >= 0 } ?: 2
        val alphaIndex = danmakuAlphas.indexOfFirst { Math.abs(it - alpha) < 0.01f }
            .takeIf { it >= 0 } ?: 3
        val speedIndex = danmakuSpeeds.indexOfFirst { Math.abs(it - speed) < 0.01f }
            .takeIf { it >= 0 } ?: 1
        val areaIndex = danmakuAreas.indexOfFirst { Math.abs(it - area) < 0.01f }
            .takeIf { it >= 0 } ?: 0

        return listOf(
            SettingItem.CycleOption(
                key = "danmaku_switch",
                label = getString(R.string.danmaku_switch_label),
                options = listOf("开启", "关闭"),
                currentIndex = if (isEnabled) 0 else 1,
                onChanged = { newIndex ->
                    UserPreferencesManager.setDanmakuEnabled(ctx, newIndex == 0)
                    refreshSettings()
                }
            ),
            SettingItem.CycleOption(
                key = "danmaku_size",
                label = getString(R.string.danmaku_size_label),
                options = danmakuSizes.map { "${(it * 100).toInt()}%" },
                currentIndex = sizeIndex,
                onChanged = { newIndex ->
                    UserPreferencesManager.setDanmakuSizeScale(ctx, danmakuSizes[newIndex])
                    refreshSettings()
                }
            ),
            SettingItem.CycleOption(
                key = "danmaku_alpha",
                label = getString(R.string.danmaku_alpha_label),
                options = danmakuAlphas.map { "${(it * 100).toInt()}%" },
                currentIndex = alphaIndex,
                onChanged = { newIndex ->
                    UserPreferencesManager.setDanmakuAlpha(ctx, danmakuAlphas[newIndex])
                    refreshSettings()
                }
            ),
            SettingItem.CycleOption(
                key = "danmaku_speed",
                label = getString(R.string.danmaku_speed_label),
                options = listOf("慢速", "正常", "快速", "极速"),
                currentIndex = speedIndex,
                onChanged = { newIndex ->
                    UserPreferencesManager.setDanmakuSpeed(ctx, danmakuSpeeds[newIndex])
                    refreshSettings()
                }
            ),
            SettingItem.CycleOption(
                key = "danmaku_area",
                label = getString(R.string.danmaku_area_label),
                options = listOf("全屏", "半屏", "1/4屏"),
                currentIndex = areaIndex,
                onChanged = { newIndex ->
                    UserPreferencesManager.setDanmakuArea(ctx, danmakuAreas[newIndex])
                    refreshSettings()
                }
            )
        )
    }

    // ---------------- 更新 ----------------

    private fun buildUpdateItems(): List<SettingItem> {
        val sourceModeNames = listOf(
            getString(R.string.update_source_auto),
            getString(R.string.update_source_github),
            getString(R.string.update_source_gitee)
        )
        val currentMode = updateViewModel.getSourceMode()
        val currentModeIndex = when (currentMode) {
            UpdateRepository.SourceMode.AUTO -> 0
            UpdateRepository.SourceMode.GITHUB -> 1
            UpdateRepository.SourceMode.GITEE -> 2
        }

        return listOf(
            SettingItem.CycleOption(
                key = "update_source",
                label = getString(R.string.update_source_label),
                options = sourceModeNames,
                currentIndex = currentModeIndex,
                onChanged = { newIndex ->
                    val newMode = when (newIndex) {
                        1 -> UpdateRepository.SourceMode.GITHUB
                        2 -> UpdateRepository.SourceMode.GITEE
                        else -> UpdateRepository.SourceMode.AUTO
                    }
                    updateViewModel.setSourceMode(newMode)
                    refreshSettings()
                }
            ),
            SettingItem.Action(
                key = "check_update",
                label = getString(R.string.update_check_label),
                value = getString(R.string.update_check_action),
                onClick = { startManualCheck() }
            ),
            SettingItem.Display(
                key = "current_version",
                label = "当前版本",
                value = "v" + BuildConfig.VERSION_NAME
            )
        )
    }

    /** 根据当前分类重新构建右侧列表 */
    private fun refreshSettings() {
        renderSettingsFor(categoryAdapter.focusedCategory)
    }

    // ---------------- 检查更新 ----------------

    private fun startManualCheck() {
        if (updateViewModel.checkState.value == UpdateViewModel.CheckState.Checking) {
            return
        }
        settingItemAdapter.updateActionValue("check_update", getString(R.string.update_checking))
        updateViewModel.manualCheck()
    }

    private fun observeUpdateCheckState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateViewModel.checkState.collect { state ->
                    when (state) {
                        UpdateViewModel.CheckState.Checking -> {
                            settingItemAdapter.updateActionValue("check_update", getString(R.string.update_checking))
                        }
                        is UpdateViewModel.CheckState.UpdateAvailable -> {
                            settingItemAdapter.updateActionValue("check_update", getString(R.string.update_check_action))
                            // 关闭设置对话框，避免遮挡 UpdateDialogFragment
                            dismissAllowingStateLoss()
                        }
                        UpdateViewModel.CheckState.NoUpdate -> {
                            settingItemAdapter.updateActionValue("check_update", getString(R.string.update_no_update))
                            scheduleResetCheckUpdateText()
                            updateViewModel.dismissDialog()
                        }
                        is UpdateViewModel.CheckState.CheckError -> {
                            settingItemAdapter.updateActionValue("check_update", getString(R.string.update_check_failed))
                            scheduleResetCheckUpdateText()
                            updateViewModel.resetError()
                        }
                        UpdateViewModel.CheckState.Idle -> {
                            // 不主动改文字，让 scheduleResetCheckUpdateText 控制恢复节奏
                        }
                    }
                }
            }
        }
    }

    private var resetTextRunnable: Runnable? = null
    private fun scheduleResetCheckUpdateText() {
        resetTextRunnable?.let { rvSettings.removeCallbacks(it) }
        val r = Runnable {
            settingItemAdapter.updateActionValue("check_update", getString(R.string.update_check_action))
        }
        resetTextRunnable = r
        rvSettings.postDelayed(r, 2500)
    }
}
