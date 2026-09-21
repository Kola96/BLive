package com.blive.tv.data.update

import android.content.Context
import android.content.SharedPreferences
import com.blive.tv.BuildConfig
import com.blive.tv.utils.VersionComparator

/**
 * 更新检查的业务规则：
 * - 远端版本是否比本地新
 * - 自动检查的频率控制（同一版本 24h 内只提示一次；忽略的版本不再提示）
 */
object UpdateChecker {
    private const val PREF_NAME = "update_checker"
    private const val KEY_SOURCE_MODE = "source_mode"
    private const val KEY_LAST_PROMPTED_VERSION = "last_prompted_version"
    private const val KEY_LAST_PROMPT_TIME = "last_prompt_time"
    private const val KEY_IGNORED_VERSION = "ignored_version"

    private const val PROMPT_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 小时

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getSourceMode(context: Context): UpdateRepository.SourceMode {
        val name = prefs(context).getString(KEY_SOURCE_MODE, UpdateRepository.SourceMode.AUTO.name)
        return runCatching { UpdateRepository.SourceMode.valueOf(name!!) }
            .getOrDefault(UpdateRepository.SourceMode.AUTO)
    }

    fun setSourceMode(context: Context, mode: UpdateRepository.SourceMode) {
        prefs(context).edit().putString(KEY_SOURCE_MODE, mode.name).apply()
    }

    /** 远端版本是否比当前安装版本新 */
    fun isNewer(remote: UpdateInfo): Boolean =
        VersionComparator.isNewer(remote.versionName, BuildConfig.VERSION_NAME)

    /**
     * 是否应该自动弹窗提示（手动检查不调用此判断）。
     * - 该版本被用户忽略 → false
     * - 24h 内已提示过同一版本 → false
     * - 否则 true
     */
    fun shouldAutoPrompt(context: Context, remote: UpdateInfo): Boolean {
        val p = prefs(context)
        if (p.getString(KEY_IGNORED_VERSION, null) == remote.versionName) return false
        val lastVersion = p.getString(KEY_LAST_PROMPTED_VERSION, null)
        val lastTime = p.getLong(KEY_LAST_PROMPT_TIME, 0L)
        if (lastVersion == remote.versionName &&
            System.currentTimeMillis() - lastTime < PROMPT_INTERVAL_MS
        ) {
            return false
        }
        return true
    }

    /** 记录"已经给用户弹过窗" */
    fun markPrompted(context: Context, remote: UpdateInfo) {
        prefs(context).edit()
            .putString(KEY_LAST_PROMPTED_VERSION, remote.versionName)
            .putLong(KEY_LAST_PROMPT_TIME, System.currentTimeMillis())
            .apply()
    }

    /** 用户选择"忽略此版本" */
    fun ignoreVersion(context: Context, versionName: String) {
        prefs(context).edit().putString(KEY_IGNORED_VERSION, versionName).apply()
    }

    /** 清除忽略（一般不需要，仅供调试） */
    fun clearIgnored(context: Context) {
        prefs(context).edit().remove(KEY_IGNORED_VERSION).apply()
    }
}
