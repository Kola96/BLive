package com.blive.tv.danmu

import android.graphics.Color

/**
 * 弹幕数据模型
 */
class DanmuItem(
    val id: Long = System.currentTimeMillis(),
    val text: String,
    val color: Int = Color.WHITE,
    val size: Float = 16f,
    var speed: Float = 1f,
    val type: Int = TYPE_SCROLL
) {
    companion object {
        // 弹幕类型
        const val TYPE_SCROLL = 0 // 滚动弹幕
        const val TYPE_TOP = 1     // 顶部弹幕
        const val TYPE_BOTTOM = 2  // 底部弹幕
    }
}
