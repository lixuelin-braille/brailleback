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
import android.content.SharedPreferences
import android.graphics.PointF
import android.util.Pair
import android.util.Size
import androidx.annotation.VisibleForTesting
import com.google.android.accessibility.braille.common.Constants.BrailleType
import com.google.android.accessibility.braille.common.translate.BrailleLanguages
import com.google.android.accessibility.braille.common.translate.BrailleLanguages.Code
import com.google.android.accessibility.braille.common.translate.GoogleTranslationResultCustomizer
import com.google.android.accessibility.braille.common.translate.KoreanTranslationResultCustomizer
import com.google.android.accessibility.braille.translate.GoogleBrailleTranslatorFactory
import com.google.android.accessibility.braille.translate.TranslationResultCustomizer
import com.google.android.accessibility.braille.translate.TranslatorFactory
import com.google.android.accessibility.braille.translate.liblouis.LibLouis
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.common.primitives.Ints
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.text.ParseException
import java.time.Instant
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Controls the braille common user preferences. */
class BrailleUserPreferences private constructor() {

  companion object {
    const val BRAILLE_SHARED_PREFS_FILENAME = "braille_keyboard"
    private const val SHARED_PREFS_VERSION = "brailleime_shared_prefs_version"
    private const val AAS12_0_SHARED_PREFS_VERSION = 1
    private const val AAS12_2_SHARED_PREFS_VERSION = 2
    private const val AAS13_1_SHARED_PREFS_VERSION = 3
    private const val AAS14_1_SHARED_PREFS_VERSION = 4
    private const val AAS16_2_SHARED_PREFS_VERSION = 5
    private var currentVersion = -1

    private const val CONTRACTED_MODE_DEFAULT = false
    // Braille keyboard constants.
    private const val SHOW_SWITCH_INPUT_CODE_GESTURE_TIP = true
    private const val ACCUMULATE_MODE_DEFAULT = true
    private const val REVERSE_DOTS_MODE_DEFAULT = false
    private const val LAUNCH_TUTORIAL_DEFAULT = true
    private const val EXIT_KEYBOARD_DEFAULT = 0
    private const val SHOW_OPTION_DIALOG_DEFAULT = 0
    // Braille display constants.
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

    private const val DEPRECATED_UEB_1 = "UEB_1"
    private const val DEPRECATED_UEB_2 = "UEB_2"
    private const val DEPRECATED_SINHALA_IN = "SINHALA_IN"

    // Prefs that are common to BK and BD
    /** Writes current using input {@link Code}. */
    @JvmStatic
    fun writeCurrentActiveInputCode(context: Context, code: Code) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putString(context.getString(R.string.pref_brailleime_translator_code), code.name)
        .apply()
    }

    /** Whether current active typing language is eight dot braille. */
    @JvmStatic
    fun isCurrentActiveInputCodeEightDot(context: Context): Boolean {
      return readCurrentActiveInputCodeAndCorrect(context).isEightDot()
    }

    /** Whether contracted enabled. */
    @JvmStatic
    fun readContractedMode(context: Context): Boolean {
      return getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .getBoolean(
          context.getString(R.string.pref_braille_contracted_mode),
          CONTRACTED_MODE_DEFAULT,
        )
    }

    /** Writes contracted enabled status. */
    @JvmStatic
    fun writeContractedMode(context: Context, contractedMode: Boolean) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putBoolean(context.getString(R.string.pref_braille_contracted_mode), contractedMode)
        .apply()
    }

    /** Returns braille type of current typing language. */
    @JvmStatic
    fun getCurrentTypingLanguageType(context: Context): BrailleType {
      return if (isCurrentActiveInputCodeEightDot(context)) {
        BrailleType.EIGHT_DOT
      } else {
        BrailleType.SIX_DOT
      }
    }

    /** Retrieves a {@link List<Code>} contains all user preferred and available {@link Code}. */
    @JvmStatic
    fun readAvailablePreferredCodes(context: Context): List<Code> {
      return BrailleLanguages.extractAvailableCodes(context, readPreferredCodes(context))
    }

    /** Writes user preferred {@link List<Code>}. */
    @JvmStatic
    fun writePreferredCodes(context: Context, preferredCodes: List<Code>) {
      val selectedCodesInStringSet = mutableSetOf<String>()
      for (code in preferredCodes) {
        selectedCodesInStringSet.add(code.name)
      }
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putStringSet(
          context.getString(R.string.pref_brailleime_translator_codes_preferred),
          selectedCodesInStringSet,
        )
        .apply()
    }

    /**
     * 首次运行时把推荐语言播种进"语言"列表，只做一次。
     *
     * <p>原版 TalkBack 的"语言"列表默认是空的，此时 {@link #readPreferredCodes} 会退化成"只有系统
     * 默认的一种语言"，于是"首选输入语言"下拉里只有一项，用户会以为"切了没反应"。这里播种一份
     * 常用组合，让用户能真正切换。
     *
     * <p><b>顺序必须与原版一致：把 {@link BrailleLanguages#getDefaultCode} 放在最前面，并直接写成
     * 当前输入码。</b>原版的默认输入码就是"系统语言对应的盲文码"——中文系统下是中文现行盲文（三格
     * 一字）。如果这里把英文一级表排在首位，中文系统首次启动就会落在英文表上，单格只会打出英文字母，
     * 永远打不出汉字。
     */
    @JvmStatic
    fun ensureRecommendedPreferredCodes(context: Context) {
      val prefs: SharedPreferences = getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
      val seededKey = "pref_brailleime_recommended_codes_seeded"
      if (prefs.getBoolean(seededKey, false)) {
        return
      }
      // 默认码按系统语言推导，与原版保持一致。
      val defaultCode = BrailleLanguages.getDefaultCode(context)
      val codesKey = context.getString(R.string.pref_brailleime_translator_codes_preferred)
      if (prefs.getStringSet(codesKey, ImmutableSet.of())!!.isEmpty()) {
        val recommended = mutableListOf<Code>()
        recommended.add(defaultCode)
        for (code in
          ImmutableList.of(
            Code.CHINESE_CHINA_CURRENT_WITH_TONES,
            Code.CHINESE_CHINA_COMMON,
            Code.EN_US_EBAE,
          )) {
          if (!recommended.contains(code) && code.isAvailable(context)) {
            recommended.add(code)
          }
        }
        writePreferredCodes(context, recommended)
      }
      // 目前没有存过输入码时，落成系统默认码，不要留给"列表第一项"这种不确定行为。
      val currentCodeKey = context.getString(R.string.pref_brailleime_translator_code)
      if (!prefs.contains(currentCodeKey)) {
        writeCurrentActiveInputCode(context, defaultCode)
      }
      prefs.edit().putBoolean(seededKey, true).apply()
    }

    /** Removes {@link Code} from preferred list. */
    @JvmStatic
    fun removePreferredCode(context: Context, code: Code) {
      val preferredCodes = readPreferredCodes(context).toMutableList()
      if (preferredCodes.remove(code)) {
        writePreferredCodes(context, preferredCodes)
      }
    }

    /**
     * Retrieves current active input {@link Code}. If it's not in the preferred codes, returns the
     * first preferred code.
     */
    @JvmStatic
    fun readCurrentActiveInputCodeAndCorrect(context: Context): Code {
      val storedCode = readCurrentActiveInputCode(context)
      val selectedAvailableCodes = readAvailablePreferredCodes(context)
      if (selectedAvailableCodes.contains(storedCode)) {
        return storedCode
      }
      val firstPreferredCode = selectedAvailableCodes[0]
      // Update current active input code preference.
      writeCurrentActiveInputCode(context, firstPreferredCode)
      return firstPreferredCode
    }

    /** Gets the next preferred output {@link Code}. */
    @JvmStatic
    fun getNextOutputCode(context: Context): Code {
      val preferredCodes = readAvailablePreferredCodes(context)
      val currentCode = readCurrentActiveOutputCodeAndCorrect(context)
      var currentCodeIndex = preferredCodes.indexOf(currentCode) + 1
      if (currentCodeIndex >= preferredCodes.size) {
        currentCodeIndex = 0
      }
      return preferredCodes[currentCodeIndex]
    }

    /** Gets the next preferred input {@link Code}. */
    @JvmStatic
    fun getNextInputCode(context: Context): Code {
      val preferredCodes = readAvailablePreferredCodes(context)
      val currentCode = readCurrentActiveInputCodeAndCorrect(context)
      var currentCodeIndex = preferredCodes.indexOf(currentCode) + 1
      if (currentCodeIndex >= preferredCodes.size) {
        currentCodeIndex = 0
      }
      return preferredCodes[currentCodeIndex]
    }

    /** Reads translator factory. */
    @JvmStatic
    fun readTranslatorFactory(context: Context): TranslatorFactory {
      val translatorFactorClass: Class<out TranslatorFactory> = LibLouis::class.java
      return GoogleBrailleTranslatorFactory(
        TranslatorFactory.forName(TranslatorFactory.getNameFromClass(translatorFactorClass)),
        getTranslationResultCustomizer(context),
      )
    }

    private fun getTranslationResultCustomizer(context: Context): TranslationResultCustomizer {
      val code = readCurrentActiveOutputCodeAndCorrect(context)
      if (code == Code.KOREAN_2006) {
        return KoreanTranslationResultCustomizer(context)
      }
      return GoogleTranslationResultCustomizer(context)
    }

    /** Reads current using input {@link Code}. */
    private fun readCurrentActiveInputCode(context: Context): Code {
      val codeName =
        getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
          .getString(
            context.getString(R.string.pref_brailleime_translator_code),
            BrailleLanguages.getDefaultCode(context).name,
          )
      return BrailleCommonUtils.valueOfSafe(codeName!!, BrailleLanguages.getDefaultCode(context))
    }

    /** Reads user preferred {@link List<Code>}. */
    private fun readPreferredCodes(context: Context): List<Code> {
      val preferredCodesInStringSet: Set<String> =
        getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
          .getStringSet(
            context.getString(R.string.pref_brailleime_translator_codes_preferred),
            ImmutableSet.of(),
          )!!
      if (preferredCodesInStringSet.isEmpty()) {
        return ImmutableList.of(BrailleLanguages.getDefaultCode(context))
      }
      val preferredCodesInList: List<Code> = extractValidCodes(preferredCodesInStringSet)
      return if (preferredCodesInList.isEmpty()) {
        ImmutableList.of(BrailleLanguages.getDefaultCode(context))
      } else {
        preferredCodesInList
      }
    }

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

    /** Extracts the valid {@link Code} in String Set. */
    @JvmStatic
    fun extractValidCodes(codes: Set<String>): List<Code> {
      return codes
        .map { BrailleCommonUtils.valueOfSafe(it, Code.STUB) }
        .filter { it != Code.STUB }
    }

    @JvmStatic
    fun getSharedPreferences(context: Context, fileName: String): SharedPreferences {
      migrateIfNecessary(context)
      return SharedPreferencesUtils.getSharedPreferences(context, fileName)
    }

    /** Migrates user preferences if necerssary.. */
    @JvmStatic
    fun migrateIfNecessary(context: Context) {
      val brailleKeyboardSharedPreferences: SharedPreferences =
        SharedPreferencesUtils.getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
      if (currentVersion == -1) {
        currentVersion =
          brailleKeyboardSharedPreferences.getInt(
            SHARED_PREFS_VERSION,
            0,
          ) // 0 could be older versions or never open Talkback.
      }
      val talkBackSharedPreferences: SharedPreferences =
        SharedPreferencesUtils.getSharedPreferences(context)
      val brailleKeyboardSharedPreferencesEditor = brailleKeyboardSharedPreferences.edit()
      // Since we did not record the version number in older builds, it is difficult to determine if
      // this is the first time the application has been launched. Therefore, the command change
      // dialog will be displayed regardless.
      if (currentVersion < AAS16_2_SHARED_PREFS_VERSION) {
        writeShowCommandChangeDialog(context, /* showDialog= */ true)
      }
      // 1. Rename released key names to new names.
      // 2. copy the existing (old) prefs from TalkBack SharedPreference to braille keyboard
      // SharedPreference, and then remove the old TalkBack SharedPreference.
      // 3. Mark migrated.
      if (currentVersion < AAS12_0_SHARED_PREFS_VERSION) {
        val talkBackSharedPreferencesEditor = talkBackSharedPreferences.edit()
        // Start migrating.
        val newAccumulateModeKey = context.getString(R.string.pref_brailleime_accumulate_mode)
        val oldAccumulatedModeKey = getOldKey(newAccumulateModeKey)
        brailleKeyboardSharedPreferencesEditor.putBoolean(
          newAccumulateModeKey,
          talkBackSharedPreferences.getBoolean(oldAccumulatedModeKey, ACCUMULATE_MODE_DEFAULT),
        )
        talkBackSharedPreferencesEditor.remove(oldAccumulatedModeKey)

        val newReverseDotsModeKey = context.getString(R.string.pref_brailleime_reverse_dots_mode)
        val oldReverseDotsModeKey = getOldKey(newReverseDotsModeKey)
        brailleKeyboardSharedPreferencesEditor.putBoolean(
          newReverseDotsModeKey,
          talkBackSharedPreferences.getBoolean(oldReverseDotsModeKey, REVERSE_DOTS_MODE_DEFAULT),
        )
        talkBackSharedPreferencesEditor.remove(oldReverseDotsModeKey)

        val newContractedModeKey = "pref_brailleime_contracted_mode"
        val oldContractedModeKey = getOldKey(newContractedModeKey)
        brailleKeyboardSharedPreferencesEditor.putBoolean(
          newContractedModeKey,
          talkBackSharedPreferences.getBoolean(oldContractedModeKey, false),
        )
        talkBackSharedPreferencesEditor.remove(oldContractedModeKey)

        val newAutoLaunchKey = context.getString(R.string.pref_brailleime_auto_launch_tutorial)
        val oldAutoLaunchKey = getOldKey(newAutoLaunchKey)
        brailleKeyboardSharedPreferencesEditor.putBoolean(
          newAutoLaunchKey,
          talkBackSharedPreferences.getBoolean(oldAutoLaunchKey, LAUNCH_TUTORIAL_DEFAULT),
        )
        talkBackSharedPreferencesEditor.remove(oldAutoLaunchKey)

        val newExitCountKey = context.getString(R.string.pref_brailleime_exit_keyboard_count)
        val oldExitCountKey = getOldKey(newExitCountKey)
        brailleKeyboardSharedPreferencesEditor.putInt(
          newExitCountKey,
          talkBackSharedPreferences.getInt(oldExitCountKey, EXIT_KEYBOARD_DEFAULT),
        )
        talkBackSharedPreferencesEditor.remove(oldExitCountKey)

        val newShowOptionDialogCountKey =
          context.getString(R.string.pref_brailleime_show_option_dialog_count)
        val oldShowOptionDialogCountKey = getOldKey(newShowOptionDialogCountKey)
        brailleKeyboardSharedPreferencesEditor.putInt(
          newShowOptionDialogCountKey,
          talkBackSharedPreferences.getInt(
            oldShowOptionDialogCountKey,
            SHOW_OPTION_DIALOG_DEFAULT,
          ),
        )
        talkBackSharedPreferencesEditor.remove(oldShowOptionDialogCountKey)

        // Upgrade version.
        brailleKeyboardSharedPreferencesEditor.putInt(
          SHARED_PREFS_VERSION,
          AAS12_0_SHARED_PREFS_VERSION,
        )
        currentVersion = AAS12_0_SHARED_PREFS_VERSION

        brailleKeyboardSharedPreferencesEditor.apply()
        talkBackSharedPreferencesEditor.apply()
      }
      // Replace Code.UEB with Code.UEB1 and Code.UEB2. Code.UEB is no longer exists.
      if (currentVersion < AAS12_2_SHARED_PREFS_VERSION) {
        // Update current Code.
        val contracted =
          brailleKeyboardSharedPreferences.getBoolean("pref_brailleime_contracted_mode", false)
        val codePrefKey = context.getString(R.string.pref_brailleime_translator_code)
        if (brailleKeyboardSharedPreferences.getString(codePrefKey, "UEB").equals("UEB")) {
          val newCode = if (contracted) DEPRECATED_UEB_2 else DEPRECATED_UEB_1
          brailleKeyboardSharedPreferencesEditor.putString(codePrefKey, newCode)
        }
        // Update selected Codes.
        val selectedCodePrefKey =
          context.getString(R.string.pref_brailleime_translator_codes_preferred)
        val selectedCodesInStringSet =
          brailleKeyboardSharedPreferences
            .getStringSet(selectedCodePrefKey, ImmutableSet.of())!!
            .toMutableSet()
        if (selectedCodesInStringSet.remove(Code.UEB.name)) {
          selectedCodesInStringSet.add(DEPRECATED_UEB_1)
          selectedCodesInStringSet.add(DEPRECATED_UEB_2)
        }
        if (selectedCodesInStringSet.isEmpty()) {
          selectedCodesInStringSet.add(BrailleLanguages.getDefaultCode(context).name)
        }
        brailleKeyboardSharedPreferencesEditor.putStringSet(
          selectedCodePrefKey,
          selectedCodesInStringSet,
        )
        // Upgrade version.
        brailleKeyboardSharedPreferencesEditor.putInt(
          SHARED_PREFS_VERSION,
          AAS12_2_SHARED_PREFS_VERSION,
        )
        currentVersion = AAS12_2_SHARED_PREFS_VERSION

        brailleKeyboardSharedPreferencesEditor.apply()
      }
      // Replace UEB_1 and UEB_2 to UEB.
      if (currentVersion < AAS13_1_SHARED_PREFS_VERSION) {
        var contracted = false
        // Update input active code.
        var codePrefKey = context.getString(R.string.pref_brailleime_translator_code)
        val inputCode = brailleKeyboardSharedPreferences.getString(codePrefKey, Code.UEB.name)
        if (inputCode == DEPRECATED_UEB_1) {
          brailleKeyboardSharedPreferencesEditor.putString(codePrefKey, Code.UEB.name)
        } else if (inputCode == DEPRECATED_UEB_2) {
          brailleKeyboardSharedPreferencesEditor.putString(codePrefKey, Code.UEB.name)
          contracted = true
        }
        // Update output active code.
        codePrefKey = context.getString(R.string.pref_bd_output_code)
        val outputCode = brailleKeyboardSharedPreferences.getString(codePrefKey, Code.UEB.name)
        if (outputCode == DEPRECATED_UEB_1) {
          brailleKeyboardSharedPreferencesEditor.putString(codePrefKey, Code.UEB.name)
        } else if (outputCode == DEPRECATED_UEB_2) {
          brailleKeyboardSharedPreferencesEditor.putString(codePrefKey, Code.UEB.name)
          contracted = true
        }
        // Update contracted.
        val contractedModePrefKey = context.getString(R.string.pref_braille_contracted_mode)
        brailleKeyboardSharedPreferencesEditor.putBoolean(contractedModePrefKey, contracted)

        // Update selected Codes.
        var hasUeb = false
        val selectedCodePrefKey =
          context.getString(R.string.pref_brailleime_translator_codes_preferred)
        val selectedCodesInStringSet =
          brailleKeyboardSharedPreferences
            .getStringSet(selectedCodePrefKey, ImmutableSet.of())!!
            .toMutableSet()
        if (selectedCodesInStringSet.remove(DEPRECATED_UEB_1)) {
          hasUeb = true
        }
        if (selectedCodesInStringSet.remove(DEPRECATED_UEB_2)) {
          hasUeb = true
        }
        if (hasUeb) {
          selectedCodesInStringSet.add(Code.UEB.name)
        }
        if (selectedCodesInStringSet.isEmpty()) {
          selectedCodesInStringSet.add(BrailleLanguages.getDefaultCode(context).name)
        }
        brailleKeyboardSharedPreferencesEditor.putStringSet(
          selectedCodePrefKey,
          selectedCodesInStringSet,
        )

        // Upgrade version.
        brailleKeyboardSharedPreferencesEditor.putInt(
          SHARED_PREFS_VERSION,
          AAS13_1_SHARED_PREFS_VERSION,
        )
        currentVersion = AAS13_1_SHARED_PREFS_VERSION

        brailleKeyboardSharedPreferencesEditor.apply()
      }
      // Replace SINHALA_IN with SINHALA or SINDHI_IN.
      if (currentVersion < AAS14_1_SHARED_PREFS_VERSION) {
        val locale = Locale.getDefault()
        val newCode: String
        if (locale.language == Code.SINDHI_IN.locale.language &&
          locale.country == Code.SINDHI_IN.locale.country
        ) {
          newCode = Code.SINDHI_IN.name
        } else {
          newCode = Code.SINHALA.name
        }
        // Update selected Codes.
        val selectedCodePrefKey =
          context.getString(R.string.pref_brailleime_translator_codes_preferred)
        val selectedCodesInStringSet =
          brailleKeyboardSharedPreferences
            .getStringSet(selectedCodePrefKey, ImmutableSet.of())!!
            .toMutableSet()
        if (selectedCodesInStringSet.remove(DEPRECATED_SINHALA_IN)) {
          selectedCodesInStringSet.add(newCode)
        }
        brailleKeyboardSharedPreferencesEditor.putStringSet(
          selectedCodePrefKey,
          selectedCodesInStringSet,
        )
        // Update input active code.
        val codePrefKey = context.getString(R.string.pref_brailleime_translator_code)
        val inputCode = brailleKeyboardSharedPreferences.getString(codePrefKey, Code.UEB.name)
        if (inputCode == DEPRECATED_SINHALA_IN) {
          brailleKeyboardSharedPreferencesEditor.putString(codePrefKey, newCode)
        }
        // Update output active code.
        val outputCodeKey = context.getString(R.string.pref_bd_output_code)
        val outputCode = brailleKeyboardSharedPreferences.getString(outputCodeKey, Code.UEB.name)
        if (outputCode == DEPRECATED_SINHALA_IN) {
          brailleKeyboardSharedPreferencesEditor.putString(outputCodeKey, newCode)
        }
        // Upgrade version.
        brailleKeyboardSharedPreferencesEditor.putInt(
          SHARED_PREFS_VERSION,
          AAS14_1_SHARED_PREFS_VERSION,
        )
        currentVersion = AAS14_1_SHARED_PREFS_VERSION

        brailleKeyboardSharedPreferencesEditor.apply()
      }
      // Show command change dialog.
      if (currentVersion < AAS16_2_SHARED_PREFS_VERSION) {
        brailleKeyboardSharedPreferencesEditor.putInt(
          SHARED_PREFS_VERSION,
          AAS16_2_SHARED_PREFS_VERSION,
        )
        currentVersion = AAS16_2_SHARED_PREFS_VERSION
      }
    }

    private fun getOldKey(key: String): String {
      return key.replace("_brailleime", "")
    }

    // Prefs that are BD-specific
    /**
     * Reads current active output {@link Code}. Use the first match of system language or the first
     * preferred code if empty.
     */
    @JvmStatic
    fun readCurrentActiveOutputCodeAndCorrect(context: Context): Code {
      var returnCode: Code
      val codeName =
        getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
          .getString(
            context.getString(R.string.pref_bd_output_code),
            BrailleLanguages.getDefaultCode(context).name,
          )
      returnCode = BrailleCommonUtils.valueOfSafe(codeName!!, BrailleLanguages.getDefaultCode(context))
      val availablePreferredCodes = readAvailablePreferredCodes(context)
      if (!availablePreferredCodes.contains(returnCode)) {
        returnCode = availablePreferredCodes[0]
      }
      // Update current active output code preference.
      writeCurrentActiveOutputCode(context, returnCode)
      return returnCode
    }

    @JvmStatic
    fun writeCurrentActiveOutputCode(context: Context, code: Code) {
      getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .putString(context.getString(R.string.pref_bd_output_code), code.name)
        .apply()
    }

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

    /** Reads should notify changing braille display input code preference. */
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

    @VisibleForTesting
    @JvmStatic
    fun testing_resetCurrentVersion(context: Context) {
      currentVersion = -1
      SharedPreferencesUtils.getSharedPreferences(context, BRAILLE_SHARED_PREFS_FILENAME)
        .edit()
        .remove(SHARED_PREFS_VERSION)
        .apply()
    }
  }
}
