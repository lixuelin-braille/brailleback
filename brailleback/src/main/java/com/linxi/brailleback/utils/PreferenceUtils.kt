/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.utils

import android.content.Context
import android.content.SharedPreferences
import com.googlecode.eyesfree.utils.SettingsStore
import android.util.Log
import com.linxi.brailleback.BuildConfig
import com.linxi.brailleback.R
import com.googlecode.eyesfree.utils.LogUtils
import com.googlecode.eyesfree.utils.TestBuild

/**
 * brailleback盲文-specific preference utilities (Kotlin 版).
 */
object PreferenceUtils {

    /**
     * Log level forced in debug builds.
     *
     * Debug builds are what gets handed to testers, so the level must stay
     * quiet enough that a day of use does not rotate the log file away. INFO
     * keeps connection lifecycle, warnings and errors, and drops the
     * per-event and per-node chatter.
     */
    private const val DEBUG_LOG_LEVEL = Log.INFO

    /** Mirrors R.string.pref_log_level_default (INFO). */
    private const val RELEASE_LOG_LEVEL = Log.INFO

    /**
     * Debug 构建的日志地板：本测试包默认 VERBOSE（文件日志写 verbose），
     * 其余 debug 包维持 INFO。设置项仍保留可改，旧偏好比地板更静时归一到地板。
     */
    private val debugFloor: Int
        get() = if (TestBuild.VERBOSE_BY_DEFAULT) Log.VERBOSE else DEBUG_LOG_LEVEL

    /**
     * Initializes the [LogUtils] log level from the shared preferences.
     * In debug builds the floor applies: testers can raise verbosity, but an
     * older build may have persisted a quieter level, so normalize it here
     * to keep the settings screen and the effective level in agreement.
     */
    @JvmStatic
    fun initLogLevel(context: Context) {
        if (BuildConfig.DIAG_LOG_LEVEL > 0) {
            LogUtils.setLogLevel(BuildConfig.DIAG_LOG_LEVEL)
            return
        }
        if (BuildConfig.DEBUG) {
            val prefs = SettingsStore.get(context)
            val key = context.getString(R.string.pref_log_level_key)
            val stored = prefs.getString(key, null)?.toIntOrNull()
            if (stored != null && stored > debugFloor) {
                prefs.edit().putString(key, debugFloor.toString()).apply()
            }
        }
        val logLevel = getLogLevel(context)
        LogUtils.setLogLevel(logLevel)
    }

    /**
     * Updates the log level, if this preference can be edited in this build.
     *
     * @return True if the log level was changed.
     */
    @JvmStatic
    fun updateLogLevel(logLevel: Int): Boolean {
        // 诊断包里级别是硬编码的，设置页改不动它。
        if (BuildConfig.DIAG_LOG_LEVEL > 0) {
            LogUtils.setLogLevel(BuildConfig.DIAG_LOG_LEVEL)
            return false
        }
        // Keep the live level consistent with getLogLevel(): in debug builds a
        // tester may raise verbosity above the floor, but never drop below it.
        LogUtils.setLogLevel(
            if (BuildConfig.DEBUG) minOf(logLevel, debugFloor) else logLevel)
        return true
    }

    /**
     * Returns the appropriate log level to use, given the build type and user
     * preferences. In debug builds, this is forced to be at the floor.
     */
    @JvmStatic
    fun getLogLevel(context: Context): Int {
        // 诊断包：级别硬编码，不看偏好项，避免被设置页或旧偏好改回去。
        if (BuildConfig.DIAG_LOG_LEVEL > 0) {
            return BuildConfig.DIAG_LOG_LEVEL
        }
        val prefs: SharedPreferences =
            SettingsStore.get(context)
        // A level chosen on the settings screen always wins, including in
        // debug builds: a tester must be able to raise the verbosity while
        // reproducing an issue and then hand back a detailed log.
        val stored = prefs.getString(
            context.getString(R.string.pref_log_level_key), null)
        stored?.toIntOrNull()?.let {
            // Debug builds are the test builds: never quieter than the floor,
            // or a connection report loses the lifecycle lines that make it useful.
            return if (BuildConfig.DEBUG) minOf(it, debugFloor) else it
        }
        return if (BuildConfig.DEBUG) debugFloor else RELEASE_LOG_LEVEL
    }
}
