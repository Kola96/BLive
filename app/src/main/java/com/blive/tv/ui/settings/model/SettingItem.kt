package com.blive.tv.ui.settings.model

/**
 * 设置项模型（sealed class）。
 *
 * TV 端设置项的典型交互：
 * - CycleOption: 左右键切换值（画质、弹幕开关等）
 * - Action: 点击触发一次性动作（检查更新）
 * - Display: 只读信息（当前版本号）
 */
sealed class SettingItem {
    abstract val key: String
    abstract val label: String

    /** 左右键循环切换值 */
    data class CycleOption(
        override val key: String,
        override val label: String,
        val options: List<String>,
        val currentIndex: Int,
        val onChanged: (newIndex: Int) -> Unit
    ) : SettingItem()

    /** 点击触发一次性动作（右侧文本可变，例如 "点击检查" / "正在检查..."） */
    data class Action(
        override val key: String,
        override val label: String,
        val value: String,
        val onClick: () -> Unit
    ) : SettingItem()

    /** 只读展示 */
    data class Display(
        override val key: String,
        override val label: String,
        val value: String
    ) : SettingItem()
}

/** 设置分类 */
enum class SettingsCategory(val label: String) {
    PLAYBACK("播放"),
    DANMAKU("弹幕"),
    UPDATE("更新")
}
