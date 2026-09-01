/*
 * Copyright (C) 2011 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.eyesfree.utils

import android.content.SharedPreferences
import android.content.res.Resources
import android.os.Build

/**
 * Utility methods for interacting with [SharedPreferences] objects.
 */
object SharedPreferencesUtils {
    /**
     * Returns the value of an integer preference stored as a string. This is
     * necessary when using a [android.preference.ListPreference] to manage an
     * integer preference, since the entries must be [String] values.
     *
     * @param prefs Shared preferences from which to obtain the value.
     * @param res Resources from which to obtain the key and default value.
     * @param keyResId Resource identifier for the key.
     * @param defaultResId Resource identifier for the default value.
     * @return The preference value, or the default value if not set.
     */
    @JvmStatic
    fun getIntFromStringPref(
        prefs: SharedPreferences, res: Resources, keyResId: Int, defaultResId: Int
    ): Int {
        val strPref = prefs.getString(
            res.getString(keyResId), res.getString(defaultResId))!!
        return Integer.parseInt(strPref)
    }

    /**
     * Returns the value of a floating point preference stored as a string.
     * This is necessary when using a [android.preference.ListPreference] to
     * manage a floating point preference, since the entries must be
     * [String] values.
     *
     * @param prefs Shared preferences from which to obtain the value.
     * @param res Resources from which to obtain the key and default value.
     * @param keyResId Resource identifier for the key.
     * @param defaultResId Resource identifier for the default value.
     * @return The preference value, or the default value if not set.
     */
    @JvmStatic
    fun getFloatFromStringPref(
        prefs: SharedPreferences, res: Resources, keyResId: Int, defaultResId: Int
    ): Float {
        val strPref = prefs.getString(
            res.getString(keyResId), res.getString(defaultResId))!!
        return java.lang.Float.parseFloat(strPref)
    }

    /**
     * Returns the value of a string preference.
     *
     * @param prefs Shared preferences from which to obtain the value.
     * @param res Resources from which to obtain the key and default value.
     * @param keyResId Resource identifier for the key.
     * @param defaultResId Resource identifier for the default value.
     * @return The preference value, or the default value if not set.
     */
    @JvmStatic
    fun getStringPref(
        prefs: SharedPreferences, res: Resources, keyResId: Int, defaultResId: Int
    ): String? {
        return prefs.getString(
            res.getString(keyResId),
            if (defaultResId == 0) null else res.getString(defaultResId)
        )
    }

    /**
     * Returns the value of an integer preference.
     *
     * @param prefs Shared preferences from which to obtain the value.
     * @param res Resources from which to obtain the key and default value.
     * @param keyResId Resource identifier for the key.
     * @param defaultResId Resource identifier for the default value.
     * @return The preference value, or the default value if not set.
     */
    @JvmStatic
    fun getIntPref(
        prefs: SharedPreferences, res: Resources, keyResId: Int, defaultResId: Int
    ): Int {
        require(defaultResId != 0) { "defaultResId should not be 0" }
        return prefs.getInt(res.getString(keyResId), res.getInteger(defaultResId))
    }

    /**
     * Returns the value of a boolean preference.
     *
     * @param prefs Shared preferences from which to obtain the value.
     * @param res Resources from which to obtain the key and default value.
     * @param keyResId Resource identifier for the key.
     * @param defaultResId Resource identifier for the default value.
     * @return The preference value, or the default value if not set.
     */
    @JvmStatic
    fun getBooleanPref(
        prefs: SharedPreferences, res: Resources, keyResId: Int, defaultResId: Int
    ): Boolean {
        return prefs.getBoolean(res.getString(keyResId), res.getBoolean(defaultResId))
    }

    /**
     * Stores the value of a boolean preference.
     *
     * @param prefs Shared preferences from which to obtain the value.
     * @param res Resources from which to obtain the key and default value.
     * @param keyResId Resource identifier for the key.
     * @param value The value to store.
     */
    @JvmStatic
    fun putBooleanPref(
        prefs: SharedPreferences, res: Resources, keyResId: Int, value: Boolean
    ) {
        prefs.edit().putBoolean(res.getString(keyResId), value).commit()
    }

    /**
     * Stores the value of a [String] preference.
     *
     * @param prefs Shared preferences from which to obtain the value.
     * @param res Resources from which to obtain the key and default value.
     * @param keyResId Resource identifier for the key.
     * @param value The value to store.
     */
    @JvmStatic
    fun putStringPref(
        prefs: SharedPreferences, res: Resources, keyResId: Int, value: String?
    ) {
        prefs.edit().putString(res.getString(keyResId), value).commit()
    }

    /**
     * Stores the value of an integer preference.
     *
     * @param prefs Shared preferences from which to obtain the value.
     * @param res Resources from which to obtain the key and default value.
     * @param keyResId Resource identifier for the key.
     * @param value The value to store.
     */
    @JvmStatic
    fun putIntPref(
        prefs: SharedPreferences, res: Resources, keyResId: Int, value: Int
    ) {
        prefs.edit().putInt(res.getString(keyResId), value).apply()
    }

    /**
     * Stores the value of a boolean preference async. On Froyo and below it
     * uses a background thread for this. That means commits can happen out of
     * order.
     *
     * @param prefs Shared preferences from which to obtain the value.
     * @param key The key of the preference.
     * @param value The value to store.
     */
    @JvmStatic
    fun storeBooleanAsync(prefs: SharedPreferences, key: String, value: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            prefs.edit().putBoolean(key, value).apply()
        } else {
            Thread {
                prefs.edit().putBoolean(key, value).commit()
            }.start()
        }
    }
}
