/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.utils

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.util.Log
import com.googlecode.eyesfree.brailleback.BuildConfig
import com.googlecode.eyesfree.brailleback.R
import com.googlecode.eyesfree.utils.LogUtils
import com.googlecode.eyesfree.utils.SharedPreferencesUtils

/**
 * brailleback盲文-specific preference utilities (Kotlin 版).
 */
object PreferenceUtils {

    /** Log level forced in debug builds. */
    private const val DEBUG_LOG_LEVEL = Log.VERBOSE

    /**
     * Initializes the [LogUtils] log level from the shared preferences.
     * In debug builds, this is locked to be "VERBOSE".
     * In release builds, it is user-configurable.
     */
    @JvmStatic
    fun initLogLevel(context: Context) {
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
        if (BuildConfig.DEBUG) {
            return false
        }
        LogUtils.setLogLevel(logLevel)
        return true
    }

    /**
     * Returns the appropriate log level to use, given the build type and user
     * preferences. In debug builds, this is forced to be "VERBOSE".
     */
    @JvmStatic
    fun getLogLevel(context: Context): Int {
        if (BuildConfig.DEBUG) {
            return DEBUG_LOG_LEVEL
        }
        val prefs: SharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(context)
        return SharedPreferencesUtils.getIntFromStringPref(
            prefs, context.resources, R.string.pref_log_level_key,
            R.string.pref_log_level_default)
    }
}
