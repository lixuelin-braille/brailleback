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
     * Initializes the [LogUtils] log level from the shared preferences.
     * In debug builds INFO is a floor: testers can raise verbosity, but an
     * older build may have persisted a quieter level, so normalize it here
     * to keep the settings screen and the effective level in agreement.
     */
    @JvmStatic
    fun initLogLevel(context: Context) {
        if (BuildConfig.DEBUG) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val key = context.getString(R.string.pref_log_level_key)
            val stored = prefs.getString(key, null)?.toIntOrNull()
            if (stored != null && stored > DEBUG_LOG_LEVEL) {
                prefs.edit().putString(key, DEBUG_LOG_LEVEL.toString()).apply()
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
        // Keep the live level consistent with getLogLevel(): in debug builds a
        // tester may raise verbosity above INFO, but never drop below it.
        LogUtils.setLogLevel(
            if (BuildConfig.DEBUG) minOf(logLevel, DEBUG_LOG_LEVEL) else logLevel)
        return true
    }

    /**
     * Returns the appropriate log level to use, given the build type and user
     * preferences. In debug builds, this is forced to be "INFO".
     */
    @JvmStatic
    fun getLogLevel(context: Context): Int {
        val prefs: SharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(context)
        // A level chosen on the settings screen always wins, including in
        // debug builds: a tester must be able to raise the verbosity while
        // reproducing an issue and then hand back a detailed log.
        val stored = prefs.getString(
            context.getString(R.string.pref_log_level_key), null)
        stored?.toIntOrNull()?.let {
            // Debug builds are the test builds: never quieter than INFO, or a
            // connection report loses the lifecycle lines that make it useful.
            return if (BuildConfig.DEBUG) minOf(it, DEBUG_LOG_LEVEL) else it
        }
        return if (BuildConfig.DEBUG) DEBUG_LOG_LEVEL else RELEASE_LOG_LEVEL
    }
}
