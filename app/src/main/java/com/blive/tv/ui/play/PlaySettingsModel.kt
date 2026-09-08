package com.blive.tv.ui.play

/**
 * 播放页底部抽屉设置面板的纯数据模型与映射函数（无 Android 依赖，可单测）。
 */

/** 设置分类 ID */
object PlaySettingIds {
    const val QUALITY = "quality"
    const val CDN = "cdn"
    const val CODEC = "codec"
    const val DANMU_SWITCH = "danmu_switch"
    const val DANMU_SPEED = "danmu_speed"
    const val DANMU_OPACITY = "danmu_opacity"
    const val DANMU_SIZE = "danmu_size"
    const val DANMU_AREA = "danmu_area"
}

/** 分类 chip：名称 + 当前值 */
data class SettingCategoryChip(
    val id: String,
    val name: String,
    val value: String
)

/** 选项 pill */
data class SettingOptionPill(
    val id: String,
    val label: String,
    val isSelected: Boolean
)

// ---------------- 流解析选项模型（PlayStreamResolver / ViewModel 使用） ----------------

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

object PlaySettingsPanelMapper {

    val DANMU_SPEED_OPTIONS = listOf(0.5f, 1.0f, 1.5f, 2.0f)
    val DANMU_OPACITY_OPTIONS = listOf(0.25f, 0.5f, 0.75f, 1.0f)
    val DANMU_SIZE_OPTIONS = listOf(0.5f, 0.75f, 1.0f, 1.5f, 2.0f)
    val DANMU_AREA_OPTIONS = listOf(1.0f, 0.5f, 0.25f)

    fun speedName(speed: Float): String = when (speed) {
        0.5f -> "慢速"
        1.5f -> "快速"
        2.0f -> "极速"
        else -> "正常"
    }

    fun areaName(area: Float): String = when (area) {
        0.5f -> "半屏"
        0.25f -> "1/4屏"
        else -> "全屏"
    }

    private fun percentName(value: Float): String = "${(value * 100).toInt()}%"

    private fun snap(value: Float, options: List<Float>): Float =
        options.minBy { kotlin.math.abs(it - value) }

    /** 弹幕开关是唯一"按确认直接切换"的布尔分类 */
    fun isToggleCategory(categoryId: String): Boolean = categoryId == PlaySettingIds.DANMU_SWITCH

    fun buildCategoryChips(state: LivePlayViewModel.UiState): List<SettingCategoryChip> {
        return listOf(
            SettingCategoryChip(
                PlaySettingIds.QUALITY, "画质",
                state.qualityOptions.find { it.qn == state.selectedQn }?.name ?: "未知"
            ),
            SettingCategoryChip(
                PlaySettingIds.CDN, "线路",
                state.cdnOptions.find { it.host == state.selectedCdnHost }?.cdnName ?: "未知"
            ),
            SettingCategoryChip(
                PlaySettingIds.CODEC, "编码",
                state.codecOptions.find { it.codecName == state.selectedCodec }?.displayName ?: "未知"
            ),
            SettingCategoryChip(
                PlaySettingIds.DANMU_SWITCH, "弹幕",
                if (state.danmuEnabled) "开启" else "关闭"
            ),
            SettingCategoryChip(
                PlaySettingIds.DANMU_SPEED, "速度",
                speedName(snap(state.danmuSpeed, DANMU_SPEED_OPTIONS))
            ),
            SettingCategoryChip(
                PlaySettingIds.DANMU_OPACITY, "不透明度",
                percentName(snap(state.danmuOpacity, DANMU_OPACITY_OPTIONS))
            ),
            SettingCategoryChip(
                PlaySettingIds.DANMU_SIZE, "大小",
                percentName(snap(state.danmuSize, DANMU_SIZE_OPTIONS))
            ),
            SettingCategoryChip(
                PlaySettingIds.DANMU_AREA, "显示区域",
                areaName(snap(state.danmuArea, DANMU_AREA_OPTIONS))
            )
        )
    }

    fun buildOptionPills(categoryId: String, state: LivePlayViewModel.UiState): List<SettingOptionPill> {
        return when (categoryId) {
            PlaySettingIds.QUALITY -> state.qualityOptions.map {
                SettingOptionPill(it.qn.toString(), it.name, it.qn == state.selectedQn)
            }
            PlaySettingIds.CDN -> state.cdnOptions.map {
                SettingOptionPill(it.host, it.cdnName, it.host == state.selectedCdnHost)
            }
            PlaySettingIds.CODEC -> state.codecOptions.map {
                SettingOptionPill(it.codecName, it.displayName, it.codecName == state.selectedCodec)
            }
            PlaySettingIds.DANMU_SWITCH -> listOf(
                SettingOptionPill("1", "开启", state.danmuEnabled),
                SettingOptionPill("0", "关闭", !state.danmuEnabled)
            )
            PlaySettingIds.DANMU_SPEED -> DANMU_SPEED_OPTIONS.map {
                SettingOptionPill(it.toString(), speedName(it), snap(state.danmuSpeed, DANMU_SPEED_OPTIONS) == it)
            }
            PlaySettingIds.DANMU_OPACITY -> DANMU_OPACITY_OPTIONS.map {
                SettingOptionPill(it.toString(), percentName(it), snap(state.danmuOpacity, DANMU_OPACITY_OPTIONS) == it)
            }
            PlaySettingIds.DANMU_SIZE -> DANMU_SIZE_OPTIONS.map {
                SettingOptionPill(it.toString(), percentName(it), snap(state.danmuSize, DANMU_SIZE_OPTIONS) == it)
            }
            PlaySettingIds.DANMU_AREA -> DANMU_AREA_OPTIONS.map {
                SettingOptionPill(it.toString(), areaName(it), snap(state.danmuArea, DANMU_AREA_OPTIONS) == it)
            }
            else -> emptyList()
        }
    }
}
