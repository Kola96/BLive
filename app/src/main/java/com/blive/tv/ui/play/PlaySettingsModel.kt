package com.blive.tv.ui.play

sealed interface SettingsItem {
    val id: String
}

data class PlaySettingsCategory(
    override val id: String,
    val name: String,
    val currentValue: String,
    val isExpanded: Boolean = false
) : SettingsItem

data class PlaySettingsOption(
    override val id: String,
    val name: String,
    val isSelected: Boolean,
    val categoryId: String = "" // 关联的分类ID
) : SettingsItem

data class QualityOption(
    val qn: Int,
    val name: String,
    val isSelected: Boolean
)

data class CdnOption(
    val host: String,
    val cdnName: String,
    val isSelected: Boolean
)

data class CodecOption(
    val codecName: String,
    val displayName: String,
    val isSelected: Boolean
)

data class DanmuSpeedOption(
    val speed: Float,
    val name: String,
    val isSelected: Boolean
)

data class DanmuOpacityOption(
    val opacity: Float,
    val name: String,
    val isSelected: Boolean
)

data class DanmuSizeOption(
    val scale: Float,
    val name: String,
    val isSelected: Boolean
)
