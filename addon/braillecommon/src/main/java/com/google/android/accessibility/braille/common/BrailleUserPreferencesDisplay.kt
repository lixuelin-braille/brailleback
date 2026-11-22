/*
 * Copyright (C) 2021 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.braille.common

import android.content.Context
import android.util.Pair
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.common.primitives.Ints
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/** 点显器（BD）偏好：换行/悬浮层、各类提示、闪烁与定时、自动滚动、弹窗与字幕、手势动作映射。 */
class BrailleUserPreferencesDisplay private constructor() {

  companion object {
    // Braille display constants.
    private const val SHOW_SWITCH_INPUT_CODE_GESTURE_TIP = true
    private const val SHOW_SWITCH_OUTPUT_CODE_GESTURE_TIP = true
    private const val SHOW_NAVIGATION_COMMAND_UNAVAILABLE_TIP = true
    private const val SHOW_USB_CONNECT_DIALOG = true
    private const val WORD_WRAP_DEFAULT = true
    private const val SHOW_OVERLAY_DEFAULT = false
    private const val REVERSE_PANNING_BUTTONS = false
    private const val POPUP_MESSAGE_DEFAULT = false
    private const val POPUP_MESSAGE_HISTORY_DEFAULT = false
    private const val CAPTION_DEFAULT = false
    private const val BLINKING_INTERVAL_MS_DEFAULT = "750"
    private const val TIMED_MESSAGE_DURATION_FRACTION_DEFAULT = "1"

    private const val MAX_SWITCH_BRAILLE_GRADE_COUNT = 5

    private const val AUTO_SCROLL_DURATION_MS_DEFAULT = 3000

    /** Interval of adjust auto scroll duration. Unit is millisecond. */
    const val AUTO_SCROLL_DURATION_INTERVAL_MS = 500

    /** Maximum auto scroll duration. Unit is millisecond. */
    const val MAXIMUM_AUTO_SCROLL_DURATION_MS = 20000

    /** Minimum auto scroll duration. Unit is millisecond. */
    const val MINIMUM_AUTO_SCROLL_DURATION_MS = 500

    private const val AUTO_ADJUST_DURATION_ENABLE_DEFAULT = true

    private const val READ_CHARACTER_PER_SECOND = 5
    private const val MILLIS_PER_SECOND = 1000
    private const val MINIMUM_TIMED_MESSAGE_DURATION_MILLISECOND = 3000

    private const val BRAILLE_SHARED_PREFS_FILENAME =
      BrailleUserPreferencesMigration.BRAILLE_SHARED_PREFS_FILENAME

    private fun getSharedPreferences(context: Context, fileName: String) =
      BrailleUserPreferencesMigration.getSharedPreferences(context, fileName)

    /** Reads word wrapping preference. */
    @JvmStatic
    fun readWordWrapping(context: Context): Boolean {
      return SharedPreferencesUtils.getBooleanPref(
        getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME),
        context.resources,
        R.string.pref_braille_word_wrap_key,
        WORD_WRAP_DEFAULT,
      )
    }

    /** Writes word wrapping preference. */
    @JvmStatic
    fun writeWordWrapping(context: Context, wordWrapping: Boolean) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(context.getString(R.string.pref_braille_word_wrap_key), wordWrapping)
        .apply()
    }

    /** Reads whether on-screen overlay is enabled by user. */
    @JvmStatic
    fun readOnScreenOverlayEnabled(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(context.getString(R.string.pref_braille_overlay_key), SHOW_OVERLAY_DEFAULT)
    }

    /** Writes whether on-screen overlay is enabled by user. */
    @JvmStatic
    fun writeOnScreenOverlayEnabled(context: Context, enabled: Boolean) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(context.getString(R.string.pref_braille_overlay_key), enabled)
        .apply()
    }

    /** Reads whether to show search tutorial again. */
    @JvmStatic
    fun readSearchTutorialNeverShow(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(context.getString(R.string.pref_search_tutorial_never_show), false)
    }

    /** Writes whether to search tutorial again. */
    @JvmStatic
    fun writeSearchTutorialNeverShow(context: Context, neverShowAgain: Boolean) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(context.getString(R.string.pref_search_tutorial_never_show), neverShowAgain)
        .apply()
    }

    /** Reads should notify changing braille display input code preference. */
    @JvmStatic
    fun readShowSwitchBrailleDisplayInputCodeGestureTip(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(
          context.getString(R.string.pref_bd_show_switch_input_code_gesture_tip),
          SHOW_SWITCH_INPUT_CODE_GESTURE_TIP,
        )
    }

    /** Writes should notify changing braille display input code preference. */
    @JvmStatic
    fun writeShowSwitchBrailleDisplayInputCodeGestureTip(context: Context, showTip: Boolean) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(
          context.getString(R.string.pref_bd_show_switch_input_code_gesture_tip),
          showTip,
        )
        .apply()
    }

    /** Reads should notify changing braille display output code preference. */
    @JvmStatic
    fun readShowSwitchBrailleDisplayOutputCodeGestureTip(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(
          context.getString(R.string.pref_bd_show_switch_output_code_gesture_tip),
          SHOW_SWITCH_OUTPUT_CODE_GESTURE_TIP,
        )
    }

    /** Writes should notify changing braille display output code preference. */
    @JvmStatic
    fun writeShowSwitchBrailleDisplayOutputCodeGestureTip(context: Context, showTip: Boolean) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(
          context.getString(R.string.pref_bd_show_switch_output_code_gesture_tip),
          showTip,
        )
        .apply()
    }

    /** Writes should notify changing braille display output code preference. */
    @JvmStatic
    fun readShowNavigationCommandUnavailableTip(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(
          context.getString(R.string.pref_bd_show_navigation_command_unavailable_tip),
          SHOW_NAVIGATION_COMMAND_UNAVAILABLE_TIP,
        )
    }

    /** Writes should notify navigation commands are unavailable tip. */
    @JvmStatic
    fun writeShowNavigationCommandUnavailableTip(context: Context, showTip: Boolean) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(
          context.getString(R.string.pref_bd_show_navigation_command_unavailable_tip),
          showTip,
        )
        .apply()
    }

    /** Reads whether to show usb connection dialog. */
    @JvmStatic
    fun readShowUsbConnectDialog(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(
          context.getString(R.string.pref_bd_show_usb_connect_dialog),
          SHOW_USB_CONNECT_DIALOG,
        )
    }

    /** Write whether to show usb connection dialog. */
    @JvmStatic
    fun writeShowUsbConnectDialog(context: Context, showAgain: Boolean) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(context.getString(R.string.pref_bd_show_usb_connect_dialog), showAgain)
        .apply()
    }

    /** Reads whether to do reverse panning buttons preference. */
    @JvmStatic
    fun readReversePanningButtons(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(
          context.getString(R.string.pref_bd_reverse_panning_buttons),
          REVERSE_PANNING_BUTTONS,
        )
    }

    /** Writes whether to do reverse panning buttons preference. */
    @JvmStatic
    fun writeReversePanningButtons(context: Context, reversePanningButtons: Boolean) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(
          context.getString(R.string.pref_bd_reverse_panning_buttons),
          reversePanningButtons,
        )
        .apply()
    }

    /** Reads the timed message duration fraction user preference. */
    @JvmStatic
    fun readTimedMessageDurationFraction(context: Context): String {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getString(
          context.getString(R.string.pref_bd_timed_message_duration_fraction_key),
          TIMED_MESSAGE_DURATION_FRACTION_DEFAULT,
        )!!
    }

    /**
     * Calculates the timed message duration in millisecond. Minimum is 3 seconds.
     *
     * <p>Equation: (amount of characters) / 5 * fraction seconds.
     */
    @JvmStatic
    fun getTimedMessageDurationInMillisecond(context: Context, amountOfCharacter: Int): Int {
      val fraction = readTimedMessageDurationFraction(context).toFloat()
      return max(
        java.lang.Math.round(
          amountOfCharacter / READ_CHARACTER_PER_SECOND.toFloat() * fraction * MILLIS_PER_SECOND,
        ),
        MINIMUM_TIMED_MESSAGE_DURATION_MILLISECOND,
      )
    }

    /** Gets intervals to blink the cursor. */
    @JvmStatic
    fun getAvailableBlinkingIntervalsMs(context: Context): Array<String> {
      return context.resources.getStringArray(R.array.blinking_interval_entries_values)
    }

    /** Reads the interval to blink the cursor. */
    @JvmStatic
    fun readBlinkingIntervalMs(context: Context): Int {
      val interval =
        getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
          .getString(
            context.getString(R.string.pref_bd_blinking_interval_key),
            BLINKING_INTERVAL_MS_DEFAULT,
          )!!
      if (getAvailableBlinkingIntervalsMs(context).any { it == interval }) {
        return interval.toInt()
      }
      return BLINKING_INTERVAL_MS_DEFAULT.toInt()
    }

    /** Adds count when switching braille grade. */
    @JvmStatic
    fun writeSwitchContactedCount(context: Context) {
      val count = readSwitchContractedCount(context) + 1
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putInt(
          context.getString(R.string.pref_bd_switch_contracted_count_key),
          min(MAX_SWITCH_BRAILLE_GRADE_COUNT + 1, count),
        )
        .apply()
    }

    /** Reads whether to announce tips when switching braille grade. */
    @JvmStatic
    fun readAnnounceSwitchContracted(context: Context): Boolean {
      return readSwitchContractedCount(context) <= MAX_SWITCH_BRAILLE_GRADE_COUNT
    }

    private fun readSwitchContractedCount(context: Context): Int {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getInt(context.getString(R.string.pref_bd_switch_contracted_count_key), 0)
    }

    /** Writes the interval to blink the cursor. */
    @JvmStatic
    fun writeBlinkingIntervalMs(context: Context, interval: Int) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putString(
          context.getString(R.string.pref_bd_blinking_interval_key),
          interval.toString(),
        )
        .apply()
    }

    /** Reads the auto scroll duration. */
    @JvmStatic
    fun readAutoScrollDuration(context: Context): Int {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getInt(
          context.getString(R.string.pref_bd_auto_scroll_duration_key),
          AUTO_SCROLL_DURATION_MS_DEFAULT,
        )
    }

    /** Writes the auto scroll duration. */
    @JvmStatic
    fun writeAutoScrollDuration(context: Context, durationMs: Int) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putInt(
          context.getString(R.string.pref_bd_auto_scroll_duration_key),
          Ints.constrainToRange(
            durationMs,
            MINIMUM_AUTO_SCROLL_DURATION_MS,
            MAXIMUM_AUTO_SCROLL_DURATION_MS,
          ),
        )
        .apply()
    }

    /** Increases the auto scroll duration. */
    @JvmStatic
    fun increaseAutoScrollDuration(context: Context) {
      writeAutoScrollDuration(
        context,
        readAutoScrollDuration(context) + AUTO_SCROLL_DURATION_INTERVAL_MS,
      )
    }

    /** Increases the auto scroll duration. */
    @JvmStatic
    fun decreaseAutoScrollDuration(context: Context) {
      writeAutoScrollDuration(
        context,
        readAutoScrollDuration(context) - AUTO_SCROLL_DURATION_INTERVAL_MS,
      )
    }

    /** Reads whether to auto adjust duration. */
    @JvmStatic
    fun readAutoAdjustDurationEnable(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(
          context.getString(R.string.pref_bd_auto_adjust_duration_enable_key),
          AUTO_ADJUST_DURATION_ENABLE_DEFAULT,
        )
    }

    /** Writes the enable state of auto adjust duration. */
    @JvmStatic
    fun writeAutoAdjustDurationEnable(context: Context, enable: Boolean) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(
          context.getString(R.string.pref_bd_auto_adjust_duration_enable_key),
          enable,
        )
        .apply()
    }

    /** Reads whether to show popup message. */
    @JvmStatic
    fun readPopupMessageEnabled(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(
          context.getString(R.string.pref_bd_popup_announcement_key),
          POPUP_MESSAGE_DEFAULT,
        )
    }

    /** Writes whether to show popup message. */
    @JvmStatic
    fun writePopupMessageEnabled(context: Context, enabled: Boolean) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(context.getString(R.string.pref_bd_popup_announcement_key), enabled)
        .apply()
    }

    /** Reads whether to save popup message. */
    @JvmStatic
    fun readPopupMessageHistoryEnabled(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(
          context.getString(R.string.pref_bd_popup_announcement_history_key),
          POPUP_MESSAGE_HISTORY_DEFAULT,
        )
    }

    /** Writes whether to save popup message. */
    @JvmStatic
    fun writePopupMessageHistoryEnabled(context: Context, enabled: Boolean) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(
          context.getString(R.string.pref_bd_popup_announcement_history_key),
          enabled,
        )
        .apply()
    }

    /** Reads whether to show captions. */
    @JvmStatic
    fun readCaptionEnabled(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(context.getString(R.string.pref_bd_caption_key), CAPTION_DEFAULT)
    }

    /** Writes whether to show captions. */
    @JvmStatic
    fun writeCaptionEnabled(context: Context, enabled: Boolean) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(context.getString(R.string.pref_bd_caption_key), enabled)
        .apply()
    }

    /** Write whether to show caption tutorial dialog. */
    @JvmStatic
    fun writeShowCaptionTutorialDialogAgain(context: Context, showAgain: Boolean) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(
          context.getString(R.string.pref_bd_show_caption_tutorial_dialog_again),
          showAgain,
        )
        .apply()
    }

    /** Reads whether to show caption tutorial dialog. */
    @JvmStatic
    fun readShowCaptionTutorialDialogAgain(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(
          context.getString(R.string.pref_bd_show_caption_tutorial_dialog_again),
          true,
        )
    }

    /** Writes last time to show caption tutorial dialog. */
    @JvmStatic
    fun writeShowCaptionTime(context: Context, time: Long) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putLong(
          context.getString(R.string.pref_bd_show_caption_tutorial_dialog_timestamp),
          time,
        )
        .apply()
    }

    /** Reads last time to show caption tutorial dialog. */
    @JvmStatic
    fun readShowCaptionTime(context: Context): Instant {
      return Instant.ofEpochMilli(
        getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
          .getLong(
            context.getString(R.string.pref_bd_show_caption_tutorial_dialog_timestamp),
            0,
          ),
      )
    }

    /**
     * Writes gesture action mappings. If the list is empty, the action is removed from preferences.
     */
    @JvmStatic
    fun writeGestureAction(context: Context, actionGesturPair: Pair<BrailleImeAction, List<String>>) {
      val actionGestureMap: MutableMap<BrailleImeAction, List<String>> = readGestureActionMap(context)
      if (actionGesturPair.second.isEmpty()) {
        actionGestureMap.remove(actionGesturPair.first)
      } else {
        actionGestureMap[actionGesturPair.first] = actionGesturPair.second
      }
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putString(
          context.getString(R.string.pref_bd_gesture_action_key),
          Gson().toJson(actionGestureMap),
        )
        .apply()
    }

    /** Resets gesture action mapping. */
    @JvmStatic
    fun resetGestureAction(context: Context, action: BrailleImeAction) {
      val actionGestureMap: MutableMap<BrailleImeAction, List<String>> = readGestureActionMap(context)
      actionGestureMap.remove(action)
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putString(
          context.getString(R.string.pref_bd_gesture_action_key),
          Gson().toJson(actionGestureMap),
        )
        .apply()
    }

    /** Resets all gesture action map. */
    @JvmStatic
    fun resetAllGestureActionMap(context: Context) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .remove(context.getString(R.string.pref_bd_gesture_action_key))
        .apply()
    }

    /** Reads gesture action map. */
    @JvmStatic
    fun readGestureActionMap(context: Context): MutableMap<BrailleImeAction, List<String>> {
      val raw =
        getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
          .getString(context.getString(R.string.pref_bd_gesture_action_key), "")!!
      val type: Type = object : TypeToken<HashMap<BrailleImeAction, List<String>>>() {}.type
      val map: MutableMap<BrailleImeAction, List<String>>? = Gson().fromJson(raw, type)
      return map ?: mutableMapOf()
    }

    /** Reads whether to show command change dialog. */
    @JvmStatic
    fun readShowCommandChangeDialog(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(context.getString(R.string.pref_bd_show_command_change_dialog), false)
    }

    /** Reads whether to show command change dialog. */
    @JvmStatic
    fun writeShowCommandChangeDialog(context: Context, showDialog: Boolean) {
      SharedPreferencesUtils.getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(context.getString(R.string.pref_bd_show_command_change_dialog), showDialog)
        .apply()
    }
  }
}
