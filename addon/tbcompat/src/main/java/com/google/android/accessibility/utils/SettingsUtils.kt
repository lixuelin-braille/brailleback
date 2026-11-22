/*
 * Copyright (C) 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.utils

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Utility class to access [android.provider.Settings]. */
class SettingsUtils private constructor() {

  companion object {
    /** Value of hidden constant {@code android.provider.Settings.Secure.USER_SETUP_COMPLETE} */
    const val USER_SETUP_COMPLETE = "user_setup_complete"

    /**
     * Value of hidden constant {@code
     * android.provider.Settings.Global.ACCESSIBILITY_VIBRATION_WATCH_ENABLED}
     */
    const val VIBRATION_WATCH_ENABLED = "a11y_vibration_watch_enabled"

    /** Tethered Configuration state. */
    const val TETHER_CONFIG_STATE = "tethered_config_state"

    /** Tethered configuration state is unknown. */
    const val TETHERED_CONFIG_UNKNOWN = 0

    /** Device is set in tethered mode. */
    const val TETHERED_CONFIG_TETHERED = 2

    /** Do-not-disturb, DND, state. */
    private const val ZEN_MODE = "zen_mode"

    @JvmStatic
    fun allowLinksOutOfSettings(context: Context): Boolean {
      // Do not allow access to web during setup. REFERTO affects android M-O.
      return 1 == Settings.Secure.getInt(context.contentResolver, USER_SETUP_COMPLETE, 0)
    }

    /**
     * Returns whether a specific accessibility service is enabled.
     *
     * @param context The parent context.
     * @param packageName The package name of the accessibility service.
     * @return {@code true} of the service is enabled.
     */
    @JvmStatic
    fun isAccessibilityServiceEnabled(context: Context, packageName: String): Boolean {
      val resolver: ContentResolver = context.contentResolver
      val enabledServices =
        Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)

      return enabledServices?.contains(packageName) ?: false
    }

    /**
     * Returns flag whether animation setting is definitely disabled. If no setting found, returns
     * false.
     */
    @JvmStatic
    fun isAnimationDisabled(context: Context): Boolean {
      return FeatureSupport.supportsUserDisablingOfGlobalAnimations() &&
        getGlobalFloat(context, Settings.Global.WINDOW_ANIMATION_SCALE) == 0f &&
        getGlobalFloat(context, Settings.Global.TRANSITION_ANIMATION_SCALE) == 0f &&
        getGlobalFloat(context, Settings.Global.ANIMATOR_DURATION_SCALE) == 0f
    }

    /**
     * Requests system settings writing permission if the parent context needs.
     *
     * @param context The parent context
     * @return {@code true} has the permission; {@code false} need to request the permission
     */
    @JvmStatic
    fun requestWriteSettingsPermission(context: Context): Boolean {
      val hasWritePermission = Settings.System.canWrite(context)
      if (hasWritePermission) {
        return true
      }
      // Starting in M, we need the user to manually allow the app to modify system settings.
      val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
      intent.setData(Uri.parse("package:" + context.packageName))
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(intent)
      return false
    }

    /** Works only when the caller is a system app. */
    @JvmStatic
    fun isVibrationWatchEnabled(context: Context): Boolean {
      return Settings.Global.getInt(context.contentResolver, VIBRATION_WATCH_ENABLED, 0) != 0
    }

    /** Returns whether the device is in standalone or restricted connectivity mode */
    @JvmStatic
    fun isStandaloneOrRestricted(context: Context): Boolean {
      return !isConfigFullyTethered(getTetherConfiguration(context))
    }

    /** Returns whether the config is fully tethered or not. */
    @JvmStatic
    fun isConfigFullyTethered(config: Int): Boolean {
      return config == TETHERED_CONFIG_TETHERED || config == TETHERED_CONFIG_UNKNOWN
    }

    /** Gets current tether configuration value. */
    @JvmStatic
    fun getTetherConfiguration(context: Context): Int {
      return Settings.Global.getInt(
        context.contentResolver,
        TETHER_CONFIG_STATE,
        TETHERED_CONFIG_UNKNOWN,
      )
    }

    /** Returns device do-not-disturb, DND, state. */
    @JvmStatic
    fun getDoNotDisturbState(context: Context): Int {
      return Settings.Global.getInt(context.contentResolver, ZEN_MODE, 0)
    }

    /**
     * Returns whether touch exploration is enabled. This is more reliable than {@code
     * AccessibilityManager.isTouchExplorationEnabled()} because it updates atomically.
     */
    @JvmStatic
    fun isTouchExplorationEnabled(resolver: ContentResolver): Boolean {
      return Settings.Secure.getInt(resolver, Settings.Secure.TOUCH_EXPLORATION_ENABLED, 0) == 1
    }

    /** Returns value of constants in Settings.Global. */
    private fun getGlobalFloat(context: Context, constantName: String): Float {
      return Settings.Global.getFloat(context.contentResolver, constantName, -1f)
    }
  }
}
