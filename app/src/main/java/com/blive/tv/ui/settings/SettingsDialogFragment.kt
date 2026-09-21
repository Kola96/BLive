package com.blive.tv.ui.settings

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.blive.tv.R
import com.blive.tv.data.update.UpdateRepository
import com.blive.tv.ui.settings.update.UpdateViewModel
import com.blive.tv.utils.UserPreferencesManager
import kotlinx.coroutines.launch

class SettingsDialogFragment : DialogFragment() {

    private val updateViewModel: UpdateViewModel by activityViewModels()

    private lateinit var containerQuality: LinearLayout
    private lateinit var tvQualityValue: TextView

    private lateinit var containerDanmakuSwitch: LinearLayout
    private lateinit var tvDanmakuSwitchValue: TextView

    private lateinit var containerDanmakuSize: LinearLayout
    private lateinit var tvDanmakuSizeValue: TextView

    private lateinit var containerDanmakuAlpha: LinearLayout
    private lateinit var tvDanmakuAlphaValue: TextView

    private lateinit var containerDanmakuSpeed: LinearLayout
    private lateinit var tvDanmakuSpeedValue: TextView

    private lateinit var containerDanmakuArea: LinearLayout
    private lateinit var tvDanmakuAreaValue: TextView

    private lateinit var containerUpdateSource: LinearLayout
    private lateinit var tvUpdateSourceValue: TextView

    private lateinit var containerCheckUpdate: LinearLayout
    private lateinit var tvCheckUpdateValue: TextView

    private lateinit var btnClose: Button

    private val sourceModeOptions = listOf(
        UpdateRepository.SourceMode.AUTO,
        UpdateRepository.SourceMode.GITHUB,
        UpdateRepository.SourceMode.GITEE
    )

    private val qualityMap = mapOf(
        "原画" to 10000,
        "蓝光" to 400,
        "超清" to 250,
        "高清" to 150,
        "流畅" to 80
    )
    
    private val qualityNames by lazy { qualityMap.keys.toList() }

    // 与LivePlayActivity对齐的弹幕选项
    private val danmakuSizes = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f)
    private val danmakuAlphas = listOf(0.25f, 0.5f, 0.75f, 1.0f)
    private val danmakuSpeeds = listOf(0.5f, 1.0f, 1.5f, 2.0f)
    private val danmakuAreas = listOf(1.0f, 0.5f, 0.25f)

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            // 设置背景为透明，消除圆角外的白色尖角
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            // 高度最多占屏幕的 80%，避免设置项过多时超出屏幕
            val displayMetrics = resources.displayMetrics
            val maxHeight = (displayMetrics.heightPixels * 0.80f).toInt()
            window.setLayout(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
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

        containerQuality = view.findViewById(R.id.container_quality)
        tvQualityValue = view.findViewById(R.id.tv_quality_value)
        
        containerDanmakuSwitch = view.findViewById(R.id.container_danmaku_switch)
        tvDanmakuSwitchValue = view.findViewById(R.id.tv_danmaku_switch_value)
        
        containerDanmakuSize = view.findViewById(R.id.container_danmaku_size)
        tvDanmakuSizeValue = view.findViewById(R.id.tv_danmaku_size_value)
        
        containerDanmakuAlpha = view.findViewById(R.id.container_danmaku_alpha)
        tvDanmakuAlphaValue = view.findViewById(R.id.tv_danmaku_alpha_value)

        containerDanmakuSpeed = view.findViewById(R.id.container_danmaku_speed)
        tvDanmakuSpeedValue = view.findViewById(R.id.tv_danmaku_speed_value)

        containerDanmakuArea = view.findViewById(R.id.container_danmaku_area)
        tvDanmakuAreaValue = view.findViewById(R.id.tv_danmaku_area_value)

        containerUpdateSource = view.findViewById(R.id.container_update_source)
        tvUpdateSourceValue = view.findViewById(R.id.tv_update_source_value)

        containerCheckUpdate = view.findViewById(R.id.container_check_update)
        tvCheckUpdateValue = view.findViewById(R.id.tv_check_update_value)

        btnClose = view.findViewById(R.id.btn_close)

        setupQualityControl()
        setupDanmakuSwitchControl()
        setupDanmakuSizeControl()
        setupDanmakuAlphaControl()
        setupDanmakuSpeedControl()
        setupDanmakuAreaControl()
        setupUpdateSourceControl()
        setupCheckUpdateControl()

        btnClose.setOnClickListener {
            dismiss()
        }

        observeUpdateCheckState()
    }

    private fun setupQualityControl() {
        updateQualityDisplay()
        
        containerQuality.setOnClickListener {
            changeQuality(1)
        }
        
        containerQuality.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        changeQuality(-1)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        changeQuality(1)
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }
    
    private fun changeQuality(direction: Int) {
        val currentQuality = UserPreferencesManager.getQualityQn(requireContext())
        var currentIndex = qualityNames.indexOfFirst { qualityMap[it] == currentQuality }
        if (currentIndex == -1) currentIndex = 0
        
        var newIndex = currentIndex + direction
        if (newIndex < 0) newIndex = qualityNames.size - 1
        if (newIndex >= qualityNames.size) newIndex = 0
        
        val selectedName = qualityNames[newIndex]
        val selectedQuality = qualityMap[selectedName] ?: 10000
        UserPreferencesManager.setQualityQn(requireContext(), selectedQuality)
        
        updateQualityDisplay()
    }
    
    private fun updateQualityDisplay() {
        val currentQuality = UserPreferencesManager.getQualityQn(requireContext())
        val name = qualityNames.find { qualityMap[it] == currentQuality } ?: "原画"
        tvQualityValue.text = name
    }

    private fun setupDanmakuSwitchControl() {
        updateDanmakuSwitchDisplay()
        
        containerDanmakuSwitch.setOnClickListener {
            toggleDanmakuSwitch()
        }
        
        containerDanmakuSwitch.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        toggleDanmakuSwitch()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }
    
    private fun toggleDanmakuSwitch() {
        val currentState = UserPreferencesManager.isDanmakuEnabled(requireContext())
        UserPreferencesManager.setDanmakuEnabled(requireContext(), !currentState)
        updateDanmakuSwitchDisplay()
    }
    
    private fun updateDanmakuSwitchDisplay() {
        val isEnabled = UserPreferencesManager.isDanmakuEnabled(requireContext())
        tvDanmakuSwitchValue.text = if (isEnabled) "开启" else "关闭"
    }

    private fun setupDanmakuSizeControl() {
        updateDanmakuSizeDisplay()
        
        containerDanmakuSize.setOnClickListener {
            changeDanmakuSize(1)
        }
        
        containerDanmakuSize.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        changeDanmakuSize(-1)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        changeDanmakuSize(1)
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }
    
    private fun changeDanmakuSize(direction: Int) {
        val currentSize = UserPreferencesManager.getDanmakuSizeScale(requireContext())
        // Find nearest index
        var currentIndex = danmakuSizes.indexOfFirst { Math.abs(it - currentSize) < 0.01f }
        if (currentIndex == -1) {
             // If not found (e.g. legacy value), find nearest
             currentIndex = danmakuSizes.minByOrNull { Math.abs(it - currentSize) }?.let { danmakuSizes.indexOf(it) } ?: 2 // Default to 1.0 (index 2)
        }
        
        var newIndex = currentIndex + direction
        // Clamp index
        if (newIndex < 0) newIndex = 0
        if (newIndex >= danmakuSizes.size) newIndex = danmakuSizes.size - 1
        
        val newSize = danmakuSizes[newIndex]
        UserPreferencesManager.setDanmakuSizeScale(requireContext(), newSize)
        updateDanmakuSizeDisplay()
    }
    
    private fun updateDanmakuSizeDisplay() {
        val currentSize = UserPreferencesManager.getDanmakuSizeScale(requireContext())
        val percent = (currentSize * 100).toInt()
        tvDanmakuSizeValue.text = "$percent%"
    }

    private fun setupDanmakuAlphaControl() {
        updateDanmakuAlphaDisplay()
        
        containerDanmakuAlpha.setOnClickListener {
            changeDanmakuAlpha(1)
        }
        
        containerDanmakuAlpha.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        changeDanmakuAlpha(-1)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        changeDanmakuAlpha(1)
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }
    
    private fun changeDanmakuAlpha(direction: Int) {
        val currentAlpha = UserPreferencesManager.getDanmakuAlpha(requireContext())
        // Find nearest index
        var currentIndex = danmakuAlphas.indexOfFirst { Math.abs(it - currentAlpha) < 0.01f }
        if (currentIndex == -1) {
             currentIndex = danmakuAlphas.minByOrNull { Math.abs(it - currentAlpha) }?.let { danmakuAlphas.indexOf(it) } ?: 3 // Default to 1.0 (index 3)
        }
        
        var newIndex = currentIndex + direction
        // Clamp index
        if (newIndex < 0) newIndex = 0
        if (newIndex >= danmakuAlphas.size) newIndex = danmakuAlphas.size - 1
        
        val newAlpha = danmakuAlphas[newIndex]
        UserPreferencesManager.setDanmakuAlpha(requireContext(), newAlpha)
        updateDanmakuAlphaDisplay()
    }
    
    private fun updateDanmakuAlphaDisplay() {
        val currentAlpha = UserPreferencesManager.getDanmakuAlpha(requireContext())
        val percent = (currentAlpha * 100).toInt()
        tvDanmakuAlphaValue.text = "$percent%"
    }

    // ---------------- 更新源 / 检查更新 ----------------

    private fun setupUpdateSourceControl() {
        fun displayName(mode: UpdateRepository.SourceMode): String = when (mode) {
            UpdateRepository.SourceMode.AUTO -> getString(R.string.update_source_auto)
            UpdateRepository.SourceMode.GITHUB -> getString(R.string.update_source_github)
            UpdateRepository.SourceMode.GITEE -> getString(R.string.update_source_gitee)
        }

        fun updateDisplay() {
            tvUpdateSourceValue.text = displayName(updateViewModel.getSourceMode())
        }

        fun change(direction: Int) {
            val current = updateViewModel.getSourceMode()
            val currentIndex = sourceModeOptions.indexOf(current).takeIf { it >= 0 } ?: 0
            val newIndex = (currentIndex + direction).floorMod(sourceModeOptions.size)
            updateViewModel.setSourceMode(sourceModeOptions[newIndex])
            updateDisplay()
        }

        updateDisplay()
        containerUpdateSource.setOnClickListener { change(1) }
        containerUpdateSource.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        change(-1)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        change(1)
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }

    private fun setupCheckUpdateControl() {
        containerCheckUpdate.setOnClickListener {
            startManualCheck()
        }
        containerCheckUpdate.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
            ) {
                startManualCheck()
                return@setOnKeyListener true
            }
            false
        }
    }

    private fun startManualCheck() {
        // 已经在检查中就不重复触发
        if (updateViewModel.checkState.value == UpdateViewModel.CheckState.Checking) {
            return
        }
        tvCheckUpdateValue.text = getString(R.string.update_checking)
        updateViewModel.manualCheck()
    }

    private fun observeUpdateCheckState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateViewModel.checkState.collect { state ->
                    when (state) {
                        UpdateViewModel.CheckState.Checking -> {
                            tvCheckUpdateValue.text = getString(R.string.update_checking)
                        }
                        is UpdateViewModel.CheckState.UpdateAvailable -> {
                            tvCheckUpdateValue.text = getString(R.string.update_check_action)
                            // 关闭设置对话框，避免遮挡 UpdateDialogFragment
                            // UpdateDialogFragment 由 MainActivity.observeUpdateState 统一弹出
                            dismissAllowingStateLoss()
                        }
                        UpdateViewModel.CheckState.NoUpdate -> {
                            // Toast 在 Dialog 下方可能被遮挡，直接把结果显示在按钮文字上
                            tvCheckUpdateValue.text = getString(R.string.update_no_update)
                            scheduleResetCheckUpdateText()
                            updateViewModel.dismissDialog()
                        }
                        is UpdateViewModel.CheckState.CheckError -> {
                            tvCheckUpdateValue.text = getString(R.string.update_check_failed)
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
        resetTextRunnable?.let { tvCheckUpdateValue.removeCallbacks(it) }
        val r = Runnable {
            tvCheckUpdateValue.text = getString(R.string.update_check_action)
        }
        resetTextRunnable = r
        tvCheckUpdateValue.postDelayed(r, 2500)
    }

    private fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod

    // ---------------- 速度/区域：通用循环选项控制 ----------------

    private fun setupDanmakuSpeedControl() {
        setupCyclingControl(
            container = containerDanmakuSpeed,
            valueView = tvDanmakuSpeedValue,
            options = danmakuSpeeds,
            read = { UserPreferencesManager.getDanmakuSpeed(requireContext()) },
            write = { UserPreferencesManager.setDanmakuSpeed(requireContext(), it) },
            display = { speed ->
                when (speed) {
                    0.5f -> "慢速"
                    1.5f -> "快速"
                    2.0f -> "极速"
                    else -> "正常"
                }
            }
        )
    }

    private fun setupDanmakuAreaControl() {
        setupCyclingControl(
            container = containerDanmakuArea,
            valueView = tvDanmakuAreaValue,
            options = danmakuAreas,
            read = { UserPreferencesManager.getDanmakuArea(requireContext()) },
            write = { UserPreferencesManager.setDanmakuArea(requireContext(), it) },
            display = { area ->
                when (area) {
                    0.5f -> "半屏"
                    0.25f -> "1/4屏"
                    else -> "全屏"
                }
            }
        )
    }

    /** 左右键/点击循环切换档位 */
    private fun setupCyclingControl(
        container: LinearLayout,
        valueView: TextView,
        options: List<Float>,
        read: () -> Float,
        write: (Float) -> Unit,
        display: (Float) -> String
    ) {
        fun updateDisplay() {
            valueView.text = display(read())
        }

        fun change(direction: Int) {
            val current = read()
            var currentIndex = options.indexOfFirst { Math.abs(it - current) < 0.01f }
            if (currentIndex == -1) {
                // 历史遗留值：吸附到最近档位
                currentIndex = options.indexOf(options.minByOrNull { Math.abs(it - current) })
            }
            val newIndex = (currentIndex + direction).coerceIn(0, options.size - 1)
            write(options[newIndex])
            updateDisplay()
        }

        updateDisplay()
        container.setOnClickListener { change(1) }
        container.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        change(-1)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        change(1)
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }
}