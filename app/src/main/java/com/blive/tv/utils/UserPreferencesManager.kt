package com.blive.tv.utils

import android.content.Context
import android.content.SharedPreferences

object UserPreferencesManager {
    private const val PREF_NAME = "user_preferences"

    private const val KEY_QUALITY_QN = "quality_qn"
    private const val KEY_DANMAKU_ENABLED = "danmaku_enabled"
    private const val KEY_DANMAKU_SIZE_SCALE = "danmaku_size_scale"
    private const val KEY_DANMAKU_ALPHA = "danmaku_alpha"
    private const val KEY_DANMAKU_SPEED = "danmaku_speed"
    private const val KEY_DANMAKU_AREA = "danmaku_area"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun getQualityQn(context: Context): Int {
        return getPreferences(context).getInt(KEY_QUALITY_QN, 10000)
    }

    fun setQualityQn(context: Context, value: Int) {
        getPreferences(context).edit().putInt(KEY_QUALITY_QN, value).apply()
    }

    fun isDanmakuEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_DANMAKU_ENABLED, true)
    }

    fun setDanmakuEnabled(context: Context, value: Boolean) {
        getPreferences(context).edit().putBoolean(KEY_DANMAKU_ENABLED, value).apply()
    }

    fun getDanmakuSizeScale(context: Context): Float {
        return getPreferences(context).getFloat(KEY_DANMAKU_SIZE_SCALE, 1.0f)
    }

    fun setDanmakuSizeScale(context: Context, value: Float) {
        getPreferences(context).edit().putFloat(KEY_DANMAKU_SIZE_SCALE, value).apply()
    }

    fun getDanmakuAlpha(context: Context): Float {
        return getPreferences(context).getFloat(KEY_DANMAKU_ALPHA, 1.0f)
    }

    fun setDanmakuAlpha(context: Context, value: Float) {
        getPreferences(context).edit().putFloat(KEY_DANMAKU_ALPHA, value).apply()
    }

    fun getDanmakuSpeed(context: Context): Float {
        return getPreferences(context).getFloat(KEY_DANMAKU_SPEED, 1.0f)
    }

    fun setDanmakuSpeed(context: Context, value: Float) {
        getPreferences(context).edit().putFloat(KEY_DANMAKU_SPEED, value).apply()
    }

    /** 弹幕显示区域占屏比例：1.0=全屏，0.5=上半屏，0.25=顶部四分之一 */
    fun getDanmakuArea(context: Context): Float {
        return getPreferences(context).getFloat(KEY_DANMAKU_AREA, 1.0f)
    }

    fun setDanmakuArea(context: Context, value: Float) {
        getPreferences(context).edit().putFloat(KEY_DANMAKU_AREA, value).apply()
    }
}
