package com.blive.tv.ui.settings

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 * - 左侧分类列：DPAD_UP/DOWN 切换分类（右侧内容同步刷新），DPAD_RIGHT 进入右侧
 * - 右侧设置项（锁定式调节）：确认键锁定设置项，锁定后左右键切换值；
 *   返回键解除锁定，未锁定时方向键正常移动焦点（左键回到分类列）
 * - BACK 关闭对话框
 */
class SettingsDialogFragment : DialogFragment() {

    private val updateViewModel: UpdateViewModel by activityViewModels()

    private lateinit var rvCategories: RecyclerView
    private lateinit var rvSettings: RecyclerView

    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var settingItemAdapter: SettingItemAdapter

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

        setupCategories()
        setupSettings()

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
        // 关闭默认 change 动画：焦点项重绑时视图被替换会导致焦点漂移级联（崩溃根源之一）
        rvCategories.itemAnimator = null
    }

    private fun setupSettings() {
        settingItemAdapter = SettingItemAdapter()
        rvSettings.layoutManager = LinearLayoutManager(requireContext())
        rvSettings.adapter = settingItemAdapter
        rvSettings.itemAnimator = null
    }

    /** 根据分类渲染右侧设置项 */
    private fun renderSettingsFor(category: SettingsCategory) {
        val items = when (category) {
            SettingsCategory.PLAYBACK -> buildPlaybackItems()
            SettingsCategory.DANMAKU -> buildDanmakuItems()
            SettingsCategory.UPDATE -> buildUpdateItems()
        }
        settingItemAdapter.submitList(items)
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
                }
            ),
            SettingItem.CycleOption(
                key = "danmaku_size",
                label = getString(R.string.danmaku_size_label),
                options = danmakuSizes.map { "${(it * 100).toInt()}%" },
                currentIndex = sizeIndex,
                onChanged = { newIndex ->
                    UserPreferencesManager.setDanmakuSizeScale(ctx, danmakuSizes[newIndex])
                }
            ),
            SettingItem.CycleOption(
                key = "danmaku_alpha",
                label = getString(R.string.danmaku_alpha_label),
                options = danmakuAlphas.map { "${(it * 100).toInt()}%" },
                currentIndex = alphaIndex,
                onChanged = { newIndex ->
                    UserPreferencesManager.setDanmakuAlpha(ctx, danmakuAlphas[newIndex])
                }
            ),
            SettingItem.CycleOption(
                key = "danmaku_speed",
                label = getString(R.string.danmaku_speed_label),
                options = listOf("慢速", "正常", "快速", "极速"),
                currentIndex = speedIndex,
                onChanged = { newIndex ->
                    UserPreferencesManager.setDanmakuSpeed(ctx, danmakuSpeeds[newIndex])
                }
            ),
            SettingItem.CycleOption(
                key = "danmaku_area",
                label = getString(R.string.danmaku_area_label),
                options = listOf("全屏", "半屏", "1/4屏"),
                currentIndex = areaIndex,
                onChanged = { newIndex ->
                    UserPreferencesManager.setDanmakuArea(ctx, danmakuAreas[newIndex])
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
