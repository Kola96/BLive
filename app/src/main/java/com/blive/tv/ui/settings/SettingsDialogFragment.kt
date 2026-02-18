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
import com.blive.tv.R
import com.blive.tv.utils.UserPreferencesManager

class SettingsDialogFragment : DialogFragment() {

    private lateinit var containerQuality: LinearLayout
    private lateinit var tvQualityValue: TextView
    
    private lateinit var containerDanmakuSwitch: LinearLayout
    private lateinit var tvDanmakuSwitchValue: TextView
    
    private lateinit var containerDanmakuSize: LinearLayout
    private lateinit var tvDanmakuSizeValue: TextView
    
    private lateinit var containerDanmakuAlpha: LinearLayout
    private lateinit var tvDanmakuAlphaValue: TextView
    
    private lateinit var btnClose: Button

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

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            // 设置背景为透明，消除圆角外的白色尖角
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            // 设置宽高属性
            window.setLayout(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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
        
        btnClose = view.findViewById(R.id.btn_close)

        setupQualityControl()
        setupDanmakuSwitchControl()
        setupDanmakuSizeControl()
        setupDanmakuAlphaControl()

        btnClose.setOnClickListener {
            dismiss()
        }
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
}