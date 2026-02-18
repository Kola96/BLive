package com.blive.tv.utils

import android.content.Context
import android.content.SharedPreferences

object UserPreferencesManager {
    private const val PREF_NAME = "user_preferences"

    private const val KEY_QUALITY_QN = "quality_qn"
    private const val KEY_DANMAKU_ENABLED = "danmaku_enabled"
    private const val KEY_DANMAKU_SIZE_SCALE = "danmaku_size_scale"
    private const val KEY_DANMAKU_ALPHA = "danmaku_alpha"

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
}
