package com.blive.tv.ui.play

import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * 播放页底部抽屉设置面板控制器。
 *
 * 结构：上排分类 chip（横向），下排选项 pill（横向，跟随分类焦点联动刷新）。
 * 交互：
 * - 分类行左右移动：选项行实时预览该分类的选项
 * - 分类按确认：布尔分类（弹幕开关）直接切换；其余分类焦点进入选项行选中项
 * - 选项按确认：应用并通过 [render] 回调刷新选中态
 * - 焦点导航（分类行 <-> 选项行）由框架 focusSearch 自然处理，无需手工编排
 */
class PlaySettingsPanelController(
    private val settingsPanel: View,
    categoryRecyclerView: RecyclerView,
    optionRecyclerView: RecyclerView,
    private val onToggleDanmu: () -> Unit,
    private val onOptionSelected: (categoryId: String, optionId: String) -> Unit
) {
    var isVisible: Boolean = false
        private set

    private var activeCategoryId: String = PlaySettingIds.QUALITY
    private var lastState: LivePlayViewModel.UiState? = null

    private val categoryAdapter = SettingsCategoryAdapter(
        onItemClick = { chip -> onCategoryChipClicked(chip) },
        onItemFocused = { chip ->
            // 分类焦点联动：实时预览该分类的选项
            if (chip.id != activeCategoryId) {
                activeCategoryId = chip.id
                renderOptions()
            }
        }
    )

    private val optionAdapter = SettingsOptionAdapter(
        onItemClick = { pill -> onOptionSelected(activeCategoryId, pill.id) }
    )

    private val categoryRecycler: RecyclerView = categoryRecyclerView.apply {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        adapter = categoryAdapter
        itemAnimator = null
    }

    private val optionRecycler: RecyclerView = optionRecyclerView.apply {
        layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
        adapter = optionAdapter
        itemAnimator = null
    }

    fun show() {
        if (isVisible) return
        isVisible = true
        settingsPanel.visibility = View.VISIBLE
        // 底部滑入动画
        settingsPanel.post {
            settingsPanel.translationY = settingsPanel.height.toFloat()
            settingsPanel.animate().translationY(0f).setDuration(200).start()
            focusActiveCategory()
        }
    }

    fun hide() {
        if (!isVisible) return
        isVisible = false
        settingsPanel.animate().cancel()
        settingsPanel.animate()
            .translationY(settingsPanel.height.toFloat())
            .setDuration(150)
            .withEndAction {
                settingsPanel.visibility = View.GONE
                settingsPanel.translationY = 0f
            }
            .start()
    }

    fun toggle() {
        if (isVisible) hide() else show()
    }

    /** 用最新状态刷新面板（隐藏时也允许刷新数据，不做焦点操作） */
    fun render(state: LivePlayViewModel.UiState) {
        lastState = state
        val chips = PlaySettingsPanelMapper.buildCategoryChips(state)
        submitSafely(categoryRecycler) { categoryAdapter.submitList(chips) }
        renderOptions()
    }

    private fun renderOptions() {
        val state = lastState ?: return
        val pills = PlaySettingsPanelMapper.buildOptionPills(activeCategoryId, state)
        // 焦点联动回调可能发生在 RecyclerView 布局/滚动过程中，
        // 此时 notifyDataSetChanged 会抛 IllegalStateException，延迟到布局后提交
        submitSafely(optionRecycler) { optionAdapter.submitList(pills) }
    }

    private fun submitSafely(recyclerView: RecyclerView, action: () -> Unit) {
        if (recyclerView.isComputingLayout || recyclerView.isAnimating) {
            recyclerView.post(action)
        } else {
            action()
        }
    }

    private fun onCategoryChipClicked(chip: SettingCategoryChip) {
        activeCategoryId = chip.id
        renderOptions()
        if (PlaySettingsPanelMapper.isToggleCategory(chip.id)) {
            // 布尔分类：按确认直接切换
            onToggleDanmu()
        } else {
            // 进入选项行的当前选中项
            val targetIndex = optionAdapter.selectedIndex().takeIf { it >= 0 } ?: 0
            optionRecycler.post {
                optionRecycler.scrollToPosition(targetIndex)
                optionRecycler.post {
                    optionRecycler.findViewHolderForAdapterPosition(targetIndex)
                        ?.itemView?.requestFocus()
                }
            }
        }
    }

    private fun focusActiveCategory() {
        val index = categoryAdapter.indexOf(activeCategoryId).takeIf { it >= 0 } ?: 0
        categoryRecycler.scrollToPosition(index)
        categoryRecycler.post {
            categoryRecycler.findViewHolderForAdapterPosition(index)
                ?.itemView?.requestFocus()
        }
    }
}
