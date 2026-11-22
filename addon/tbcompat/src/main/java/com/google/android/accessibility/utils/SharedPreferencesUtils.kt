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

package com.google.android.accessibility.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.preference.PreferenceManager
import androidx.core.content.ContextCompat

/** Utility methods for interacting with [SharedPreferences] objects. */
class SharedPreferencesUtils private constructor() {

  companion object {
    /**
     * Returns the value of an integer preference stored as a string. This is necessary when using a
     * [android.preference.ListPreference] to manage an integer preference, since the entries
     * must be String values.
     *
     * @param prefs Shared preferences from which to obtain the value
     * @param res Resources from which to obtain the key and default value
     * @param keyResId Resource identifier for the key
     * @param defaultResId Resource identifier for the default value
     * @return The preference value, or the default value if not set
     */
    @JvmStatic
    fun getIntFromStringPref(
      prefs: SharedPreferences,
      res: Resources,
      keyResId: Int,
      defaultResId: Int,
    ): Int {
      val strPref = prefs.getString(res.getString(keyResId), res.getString(defaultResId))
      return Integer.parseInt(strPref)
    }

    /**
     * Returns the value of a floating point preference stored as a string. This is necessary when
     * using a [android.preference.ListPreference] to manage a floating point preference, since
     * the entries must be String values.
     *
     * @param prefs Shared preferences from which to obtain the value
     * @param res Resources from which to obtain the key and default value
     * @param keyResId Resource identifier for the key
     * @param defaultResId Resource identifier for the default value
     * @return The preference value, or the default value if not set
     */
    @JvmStatic
    fun getFloatFromStringPref(
      prefs: SharedPreferences,
      res: Resources,
      keyResId: Int,
      defaultResId: Int,
    ): Float {
      val strPref = prefs.getString(res.getString(keyResId), res.getString(defaultResId))
      return strPref!!.toFloat()
    }

    /**
     * Returns the value of a string preference.
     *
     * @param prefs Shared preferences from which to obtain the value
     * @param res Resources from which to obtain the key and default value
     * @param keyResId Resource identifier for the key
     * @param defaultResId Resource identifier for the default value
     * @return The preference value, or the default value if not set
     */
    @JvmStatic
    fun getStringPref(
      prefs: SharedPreferences,
      res: Resources,
      keyResId: Int,
      defaultResId: Int,
    ): String? {
      return prefs.getString(
        res.getString(keyResId),
        if (defaultResId == 0) null else res.getString(defaultResId),
      )
    }

    /**
     * Stores the value of a String preference.
     *
     * @param prefs Shared preferences from which to obtain the value
     * @param res Resources from which to obtain the key and default value
     * @param keyResId Resource identifier for the key
     * @param value The value to store
     */
    @JvmStatic
    fun putStringPref(
      prefs: SharedPreferences,
      res: Resources,
      keyResId: Int,
      value: String?,
    ) {
      val editor = prefs.edit()
      editor.putString(res.getString(keyResId), value)
      editor.apply()
    }

    /**
     * Stores the value of a String preference.
     *
     * @param prefs Shared preferences from which to obtain the value
     * @param key the preference key
     * @param value The value to store
     */
    @JvmStatic
    fun putStringPref(prefs: SharedPreferences, key: String, value: String?) {
      val editor = prefs.edit()
      editor.putString(key, value)
      editor.apply()
    }

    /**
     * Returns the value of an integer preference.
     *
     * @param prefs Shared preferences from which to obtain the value
     * @param res Resources from which to obtain the key and default value
     * @param keyResId Resource identifier for the key
     * @param defaultResId Resource identifier for the default value
     * @return The preference value, or the default value if not set
     */
    @JvmStatic
    fun getIntPref(
      prefs: SharedPreferences,
      res: Resources,
      keyResId: Int,
      defaultResId: Int,
    ): Int {
      if (defaultResId == 0) {
        throw IllegalArgumentException("defaultResId should not be 0")
      }
      return prefs.getInt(res.getString(keyResId), res.getInteger(defaultResId))
    }

    /**
     * Stores the value of an integer preference.
     *
     * @param prefs Shared preferences from which to obtain the value
     * @param res Resources from which to obtain the key and default value
     * @param keyResId Resource identifier for the key
     * @param value The value to store
     */
    @JvmStatic
    fun putIntPref(prefs: SharedPreferences, res: Resources, keyResId: Int, value: Int) {
      val editor = prefs.edit()
      editor.putInt(res.getString(keyResId), value)
      editor.apply()
    }

    /**
     * Returns the value of a boolean preference.
     *
     * @param prefs Shared preferences from which to obtain the value
     * @param res Resources from which to obtain the key and default value
     * @param keyResId Resource identifier for the key
     * @param defaultResId Resource identifier for the default value
     * @return The preference value, or the default value if not set
     */
    @JvmStatic
    fun getBooleanPref(
      prefs: SharedPreferences,
      res: Resources,
      keyResId: Int,
      defaultResId: Int,
    ): Boolean {
      return prefs.getBoolean(res.getString(keyResId), res.getBoolean(defaultResId))
    }

    /**
     * Returns the value of a boolean preference.
     *
     * @param prefs Shared preferences from which to obtain the value
     * @param res Resources from which to obtain the key and default value
     * @param keyResId Resource identifier for the key
     * @param defaultValue The default value
     * @return The preference value, or the default value if not set
     */
    @JvmStatic
    fun getBooleanPref(
      prefs: SharedPreferences,
      res: Resources,
      keyResId: Int,
      defaultValue: Boolean,
    ): Boolean {
      return prefs.getBoolean(res.getString(keyResId), defaultValue)
    }

    /**
     * Stores the value of a boolean preference.
     *
     * @param prefs Shared preferences from which to obtain the value
     * @param res Resources from which to obtain the key and default value
     * @param keyResId Resource identifier for the key
     * @param value The value to store
     */
    @JvmStatic
    fun putBooleanPref(
      prefs: SharedPreferences,
      res: Resources,
      keyResId: Int,
      value: Boolean,
    ) {
      storeBooleanAsync(prefs, res.getString(keyResId), value)
    }

    /**
     * Stores the value of a boolean preference async.
     *
     * @param prefs Shared preferences from which to obtain the value
     * @param key The pref key
     * @param value The value to store
     */
    @JvmStatic
    fun storeBooleanAsync(prefs: SharedPreferences, key: String, value: Boolean) {
      val editor = prefs.edit()
      editor.putBoolean(key, value)
      editor.apply()
    }

    /**
     * Gets the appropriate SharedPreferences depending on the device capabilities. On systems that
     * support device-protected storage (Android N or later with compatible device), returns
     * SharedPreferences backed by device-protected storage. Otherwise, returns SharedPreferences
     * backed by a standard credential-protected storage context.
     */
    @JvmStatic
    fun getSharedPreferences(context: Context): SharedPreferences {
      val deContext = ContextCompat.createDeviceProtectedStorageContext(context)
      if (deContext != null) {
        return PreferenceManager.getDefaultSharedPreferences(deContext)
      } else {
        return PreferenceManager.getDefaultSharedPreferences(context)
      }
    }

    /**
     * Gets the appropriate SharedPreferences depending on the device capabilities by {@code name}. On
     * systems that support device-protected storage (Android N or later with compatible device),
     * returns SharedPreferences backed by device-protected storage. Otherwise, returns
     * SharedPreferences backed by a standard credential-protected storage context.
     */
    @JvmStatic
    fun getSharedPreferences(context: Context, name: String): SharedPreferences {
      val deContext = ContextCompat.createDeviceProtectedStorageContext(context)
      if (deContext != null) {
        return deContext.getSharedPreferences(name, Context.MODE_PRIVATE)
      }
      return context.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    /**
     * Move existing preferences file from credential protected storage to device protected storage.
     * This is used to migrate data between storage locations after an Android upgrade from
     * Build.VERSION < N to Build.VERSION >= N.
     */
    @JvmStatic
    fun migrateSharedPreferences(context: Context) {
      if (BuildVersionUtils.isAtLeastN()) {
        val deContext = ContextCompat.createDeviceProtectedStorageContext(context)
        if (deContext != null) {
          deContext.moveSharedPreferencesFrom(
            context,
            PreferenceManager.getDefaultSharedPreferencesName(context),
          )
        }
      }
    }

    /** Removes the preferences. */
    @JvmStatic
    fun remove(pref: SharedPreferences, vararg keys: String) {
      for (key in keys) {
        if (pref.contains(key)) {
          pref.edit().remove(key).apply()
        }
      }
    }
  }
}
