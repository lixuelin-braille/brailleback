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
import android.graphics.PointF
import android.util.Size
import java.text.ParseException

/** 盲文键盘（BK）偏好：输入模式、点位布局与校准、教程弹出、退出/选项计数、手势提示。 */
class BrailleUserPreferencesKeyboard private constructor() {

  companion object {
    private const val SHOW_SWITCH_INPUT_CODE_GESTURE_TIP = true
    private const val ACCUMULATE_MODE_DEFAULT = true
    private const val REVERSE_DOTS_MODE_DEFAULT = false
    private const val LAUNCH_TUTORIAL_DEFAULT = true
    private const val EXIT_KEYBOARD_DEFAULT = 0
    private const val SHOW_OPTION_DIALOG_DEFAULT = 0

    private const val BRAILLE_SHARED_PREFS_FILENAME =
      BrailleUserPreferencesMigration.BRAILLE_SHARED_PREFS_FILENAME

    private fun getSharedPreferences(context: Context, fileName: String) =
      BrailleUserPreferencesMigration.getSharedPreferences(context, fileName)

    // Prefs that are BK-specific
    /** Reads should notify changing braille keyboard input code preference. */
    @JvmStatic
    fun readShowSwitchBrailleKeyboardInputCodeGestureTip(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(
          context.getString(R.string.pref_show_switch_input_code_gesture_tip),
          SHOW_SWITCH_INPUT_CODE_GESTURE_TIP,
        )
    }

    /** Writes should notify changing braille keyboard input code preference. */
    @JvmStatic
    fun writeShowSwitchBrailleKeyboardInputCodeGestureTip(context: Context, showTip: Boolean) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(context.getString(R.string.pref_show_switch_input_code_gesture_tip), showTip)
        .apply()
    }

    /** Reads accumulate mode status. */
    @JvmStatic
    fun readAccumulateMode(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(
          context.getString(R.string.pref_brailleime_accumulate_mode),
          ACCUMULATE_MODE_DEFAULT,
        )
    }

    /** Writes accumulate mode status. */
    @JvmStatic
    fun writeAccumulateMode(context: Context, accumulateMode: Boolean) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(context.getString(R.string.pref_brailleime_accumulate_mode), accumulateMode)
        .apply()
    }

    /** Reads reverse dots mode status. */
    @JvmStatic
    fun readReverseDotsMode(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(
          context.getString(R.string.pref_brailleime_reverse_dots_mode),
          REVERSE_DOTS_MODE_DEFAULT,
        )
    }

    /** Writes reverse dots mode status. */
    @JvmStatic
    fun writeReverseDotsMode(context: Context, reverseDotsMode: Boolean) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(
          context.getString(R.string.pref_brailleime_reverse_dots_mode),
          reverseDotsMode,
        )
        .apply()
    }

    /** Reads layout mode. */
    @JvmStatic
    fun readLayoutMode(context: Context): TouchDots {
      return BrailleCommonUtils.valueOfSafe(
        getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
          .getString(
            context.getString(R.string.pref_brailleime_layout_mode),
            if (BrailleUtils.isPhoneSizedDevice(context.resources)) {
              TouchDots.AUTO_DETECT.name
            } else {
              TouchDots.TABLETOP.name
            },
          )!!,
        TouchDots.AUTO_DETECT,
      )
    }

    /** Writes layout mode. */
    @JvmStatic
    fun writeLayoutMode(context: Context, touchDotsMode: TouchDots) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putString(context.getString(R.string.pref_brailleime_layout_mode), touchDotsMode.name)
        .apply()
    }

    /** Reads calibration points for a phone device. */
    @JvmStatic
    @Throws(ParseException::class)
    fun readCalibrationPointsPhone(
      context: Context,
      isTableTop: Boolean,
      orientation: Int,
      screenSize: Size,
    ): List<PointF> {
      return BrailleUserPreferencesTouchDots.readLayoutPointsPhone(
        context,
        getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME),
        isTableTop,
        orientation,
        screenSize,
      )
    }

    /** Writes calibration points for a phone device. */
    @JvmStatic
    @Throws(ParseException::class)
    fun writeCalibrationPointsPhone(
      context: Context,
      isTableTop: Boolean,
      orientation: Int,
      points: List<PointF>,
      screenSize: Size,
    ) {
      BrailleUserPreferencesTouchDots.writeLayoutPointsPhone(
        context,
        getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME),
        isTableTop,
        orientation,
        screenSize,
        points,
      )
    }

    /** Reads calibration points for a tablet device. */
    @JvmStatic
    @Throws(ParseException::class)
    fun readCalibrationPointsTablet(
      context: Context,
      isTabletop: Boolean,
      orientation: Int,
    ): List<PointF> {
      return BrailleUserPreferencesTouchDots.readLayoutPointsTablet(
        context,
        getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME),
        isTabletop,
        orientation,
      )
    }

    /** Writes calibration points for a tablet device. */
    @JvmStatic
    @Throws(ParseException::class)
    fun writeCalibrationPointsTablet(
      context: Context,
      isTabletop: Boolean,
      orientation: Int,
      points: List<PointF>,
      screenSize: Size,
    ) {
      BrailleUserPreferencesTouchDots.writeLayoutPointsTablet(
        context,
        getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME),
        isTabletop,
        orientation,
        points,
        screenSize,
      )
    }

    /** Sets that tutorial doesn't launch when opens Braille keyboard. */
    @JvmStatic
    fun setTutorialFinished(context: Context) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(context.getString(R.string.pref_brailleime_auto_launch_tutorial), false)
        .apply()
    }

    /** Returns true if it's the first use of Braille keyboard. */
    @JvmStatic
    fun shouldLaunchTutorial(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(
          context.getString(R.string.pref_brailleime_auto_launch_tutorial),
          LAUNCH_TUTORIAL_DEFAULT,
        )
    }

    /** Sets exit keyboard count. */
    @JvmStatic
    fun writeExitKeyboardCount(context: Context, count: Int) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putInt(context.getString(R.string.pref_brailleime_exit_keyboard_count), count)
        .apply()
    }

    /** Sets show option dialog count. */
    @JvmStatic
    fun writeShowOptionDialogCount(context: Context, count: Int) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putInt(
          context.getString(R.string.pref_brailleime_show_option_dialog_count),
          count,
        )
        .apply()
    }

    /** Reads exit keyboard count. */
    @JvmStatic
    fun readExitKeyboardCount(context: Context): Int {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getInt(
          context.getString(R.string.pref_brailleime_exit_keyboard_count),
          EXIT_KEYBOARD_DEFAULT,
        )
    }

    /** Reads show option dialog count. */
    @JvmStatic
    fun readShowOptionDialogCount(context: Context): Int {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getInt(
          context.getString(R.string.pref_brailleime_show_option_dialog_count),
          SHOW_OPTION_DIALOG_DEFAULT,
        )
    }

    /** Writes whether to show change gesture intro again. */
    @JvmStatic
    fun writeChangeGestureIntroNeverShow(context: Context) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(
          context.getString(R.string.pref_brailleime_change_gesture_intro_again),
          true,
        )
        .apply()
    }

    /** Reads whether to show change gesture intro again. */
    @JvmStatic
    fun readChangeGestureIntroNeverShow(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(
          context.getString(R.string.pref_brailleime_change_gesture_intro_again),
          false,
        )
    }
  }
}
