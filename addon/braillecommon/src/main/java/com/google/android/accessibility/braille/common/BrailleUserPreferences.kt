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
import com.google.android.accessibility.braille.common.translate.BrailleLanguages.Code
import java.text.ParseException
import java.time.Instant

/**
 * Controls the braille common user preferences.
 *
 * <p>本类是全应用偏好设置的统一对外入口：公开 API 与历史签名保持不变，实现按偏好域拆分到同包的
 * 委托对象——翻译与盲文表见 [BrailleUserPreferencesTranslator]，盲文键盘见
 * [BrailleUserPreferencesKeyboard]，点显器见 [BrailleUserPreferencesDisplay]，SharedPreferences
 * 文件入口与版本迁移见 [BrailleUserPreferencesMigration]。
 */
class BrailleUserPreferences private constructor() {

  companion object {
    const val BRAILLE_SHARED_PREFS_FILENAME =
      BrailleUserPreferencesMigration.BRAILLE_SHARED_PREFS_FILENAME

    /** Interval of adjust auto scroll duration. Unit is millisecond. */
    const val AUTO_SCROLL_DURATION_INTERVAL_MS =
      BrailleUserPreferencesDisplay.AUTO_SCROLL_DURATION_INTERVAL_MS

    /** Maximum auto scroll duration. Unit is millisecond. */
    const val MAXIMUM_AUTO_SCROLL_DURATION_MS =
      BrailleUserPreferencesDisplay.MAXIMUM_AUTO_SCROLL_DURATION_MS

    /** Minimum auto scroll duration. Unit is millisecond. */
    const val MINIMUM_AUTO_SCROLL_DURATION_MS =
      BrailleUserPreferencesDisplay.MINIMUM_AUTO_SCROLL_DURATION_MS

    // ── 翻译与盲文表 ──────────────────────────────────────────────────────

    /** Writes current using input {@link Code}. */
    @JvmStatic
    fun writeCurrentActiveInputCode(context: Context, code: Code) =
      BrailleUserPreferencesTranslator.writeCurrentActiveInputCode(context, code)

    /** Whether current active typing language is eight dot braille. */
    @JvmStatic
    fun isCurrentActiveInputCodeEightDot(context: Context): Boolean =
      BrailleUserPreferencesTranslator.isCurrentActiveInputCodeEightDot(context)

    /** Whether contracted enabled. */
    @JvmStatic
    fun readContractedMode(context: Context): Boolean =
      BrailleUserPreferencesTranslator.readContractedMode(context)

    /** Writes contracted enabled status. */
    @JvmStatic
    fun writeContractedMode(context: Context, contractedMode: Boolean) =
      BrailleUserPreferencesTranslator.writeContractedMode(context, contractedMode)

    /** Returns braille type of current typing language. */
    @JvmStatic
    fun getCurrentTypingLanguageType(context: Context): BrailleType =
      BrailleUserPreferencesTranslator.getCurrentTypingLanguageType(context)

    /** Retrieves a {@link List<Code>} contains all user preferred and available {@link Code}. */
    @JvmStatic
    fun readAvailablePreferredCodes(context: Context): List<Code> =
      BrailleUserPreferencesTranslator.readAvailablePreferredCodes(context)

    /** Writes user preferred {@link List<Code>}. */
    @JvmStatic
    fun writePreferredCodes(context: Context, preferredCodes: List<Code>) =
      BrailleUserPreferencesTranslator.writePreferredCodes(context, preferredCodes)

    /**
     * 首次运行时把推荐语言播种进"语言"列表，只做一次。详见 [BrailleUserPreferencesTranslator]。
     */
    @JvmStatic
    fun ensureRecommendedPreferredCodes(context: Context) =
      BrailleUserPreferencesTranslator.ensureRecommendedPreferredCodes(context)

    /** Removes {@link Code} from preferred list. */
    @JvmStatic
    fun removePreferredCode(context: Context, code: Code) =
      BrailleUserPreferencesTranslator.removePreferredCode(context, code)

    /**
     * Retrieves current active input {@link Code}. If it's not in the preferred codes, returns the
     * first preferred code.
     */
    @JvmStatic
    fun readCurrentActiveInputCodeAndCorrect(context: Context): Code =
      BrailleUserPreferencesTranslator.readCurrentActiveInputCodeAndCorrect(context)

    /** Gets the next preferred output {@link Code}. */
    @JvmStatic
    fun getNextOutputCode(context: Context): Code =
      BrailleUserPreferencesTranslator.getNextOutputCode(context)

    /** Gets the next preferred input {@link Code}. */
    @JvmStatic
    fun getNextInputCode(context: Context): Code =
      BrailleUserPreferencesTranslator.getNextInputCode(context)

    /** Reads translator factory. */
    @JvmStatic
    fun readTranslatorFactory(context: Context) =
      BrailleUserPreferencesTranslator.readTranslatorFactory(context)

    /** Extracts the valid {@link Code} in String Set. */
    @JvmStatic
    fun extractValidCodes(codes: Set<String>): List<Code> =
      BrailleUserPreferencesTranslator.extractValidCodes(codes)

    /**
     * Reads current active output {@link Code}. Use the first match of system language or the first
     * preferred code if empty.
     */
    @JvmStatic
    fun readCurrentActiveOutputCodeAndCorrect(context: Context): Code =
      BrailleUserPreferencesTranslator.readCurrentActiveOutputCodeAndCorrect(context)

    @JvmStatic
    fun writeCurrentActiveOutputCode(context: Context, code: Code) =
      BrailleUserPreferencesTranslator.writeCurrentActiveOutputCode(context, code)

    // ── 盲文键盘（BK） ──────────────────────────────────────────────────────

    /** Reads should notify changing braille keyboard input code preference. */
    @JvmStatic
    fun readShowSwitchBrailleKeyboardInputCodeGestureTip(context: Context): Boolean =
      BrailleUserPreferencesKeyboard.readShowSwitchBrailleKeyboardInputCodeGestureTip(context)

    /** Writes should notify changing braille keyboard input code preference. */
    @JvmStatic
    fun writeShowSwitchBrailleKeyboardInputCodeGestureTip(context: Context, showTip: Boolean) =
      BrailleUserPreferencesKeyboard.writeShowSwitchBrailleKeyboardInputCodeGestureTip(context, showTip)

    /** Reads accumulate mode status. */
    @JvmStatic
    fun readAccumulateMode(context: Context): Boolean =
      BrailleUserPreferencesKeyboard.readAccumulateMode(context)

    /** Writes accumulate mode status. */
    @JvmStatic
    fun writeAccumulateMode(context: Context, accumulateMode: Boolean) =
      BrailleUserPreferencesKeyboard.writeAccumulateMode(context, accumulateMode)

    /** Reads reverse dots mode status. */
    @JvmStatic
    fun readReverseDotsMode(context: Context): Boolean =
      BrailleUserPreferencesKeyboard.readReverseDotsMode(context)

    /** Writes reverse dots mode status. */
    @JvmStatic
    fun writeReverseDotsMode(context: Context, reverseDotsMode: Boolean) =
      BrailleUserPreferencesKeyboard.writeReverseDotsMode(context, reverseDotsMode)

    /** Reads layout mode. */
    @JvmStatic
    fun readLayoutMode(context: Context): TouchDots =
      BrailleUserPreferencesKeyboard.readLayoutMode(context)

    /** Writes layout mode. */
    @JvmStatic
    fun writeLayoutMode(context: Context, touchDotsMode: TouchDots) =
      BrailleUserPreferencesKeyboard.writeLayoutMode(context, touchDotsMode)

    /** Reads calibration points for a phone device. */
    @JvmStatic
    @Throws(ParseException::class)
    fun readCalibrationPointsPhone(
      context: Context,
      isTableTop: Boolean,
      orientation: Int,
      screenSize: Size,
    ): List<PointF> =
      BrailleUserPreferencesKeyboard.readCalibrationPointsPhone(
        context,
        isTableTop,
        orientation,
        screenSize,
      )

    /** Writes calibration points for a phone device. */
    @JvmStatic
    @Throws(ParseException::class)
    fun writeCalibrationPointsPhone(
      context: Context,
      isTableTop: Boolean,
      orientation: Int,
      points: List<PointF>,
      screenSize: Size,
    ) =
      BrailleUserPreferencesKeyboard.writeCalibrationPointsPhone(
        context,
        isTableTop,
        orientation,
        points,
        screenSize,
      )

    /** Reads calibration points for a tablet device. */
    @JvmStatic
    @Throws(ParseException::class)
    fun readCalibrationPointsTablet(
      context: Context,
      isTabletop: Boolean,
      orientation: Int,
    ): List<PointF> =
      BrailleUserPreferencesKeyboard.readCalibrationPointsTablet(context, isTabletop, orientation)

    /** Writes calibration points for a tablet device. */
    @JvmStatic
    @Throws(ParseException::class)
    fun writeCalibrationPointsTablet(
      context: Context,
      isTabletop: Boolean,
      orientation: Int,
      points: List<PointF>,
      screenSize: Size,
    ) =
      BrailleUserPreferencesKeyboard.writeCalibrationPointsTablet(
        context,
        isTabletop,
        orientation,
        points,
        screenSize,
      )

    /** Sets that tutorial doesn't launch when opens Braille keyboard. */
    @JvmStatic
    fun setTutorialFinished(context: Context) =
      BrailleUserPreferencesKeyboard.setTutorialFinished(context)

    /** Returns true if it's the first use of Braille keyboard. */
    @JvmStatic
    fun shouldLaunchTutorial(context: Context): Boolean =
      BrailleUserPreferencesKeyboard.shouldLaunchTutorial(context)

    /** Sets exit keyboard count. */
    @JvmStatic
    fun writeExitKeyboardCount(context: Context, count: Int) =
      BrailleUserPreferencesKeyboard.writeExitKeyboardCount(context, count)

    /** Sets show option dialog count. */
    @JvmStatic
    fun writeShowOptionDialogCount(context: Context, count: Int) =
      BrailleUserPreferencesKeyboard.writeShowOptionDialogCount(context, count)

    /** Reads exit keyboard count. */
    @JvmStatic
    fun readExitKeyboardCount(context: Context): Int =
      BrailleUserPreferencesKeyboard.readExitKeyboardCount(context)

    /** Reads show option dialog count. */
    @JvmStatic
    fun readShowOptionDialogCount(context: Context): Int =
      BrailleUserPreferencesKeyboard.readShowOptionDialogCount(context)

    /** Writes whether to show change gesture intro again. */
    @JvmStatic
    fun writeChangeGestureIntroNeverShow(context: Context) =
      BrailleUserPreferencesKeyboard.writeChangeGestureIntroNeverShow(context)

    /** Reads whether to show change gesture intro again. */
    @JvmStatic
    fun readChangeGestureIntroNeverShow(context: Context): Boolean =
      BrailleUserPreferencesKeyboard.readChangeGestureIntroNeverShow(context)

    // ── 点显器（BD） ────────────────────────────────────────────────────────

    /** Reads word wrapping preference. */
    @JvmStatic
    fun readWordWrapping(context: Context): Boolean =
      BrailleUserPreferencesDisplay.readWordWrapping(context)

    /** Writes word wrapping preference. */
    @JvmStatic
    fun writeWordWrapping(context: Context, wordWrapping: Boolean) =
      BrailleUserPreferencesDisplay.writeWordWrapping(context, wordWrapping)

    /** Reads whether on-screen overlay is enabled by user. */
    @JvmStatic
    fun readOnScreenOverlayEnabled(context: Context): Boolean =
      BrailleUserPreferencesDisplay.readOnScreenOverlayEnabled(context)

    /** Writes whether on-screen overlay is enabled by user. */
    @JvmStatic
    fun writeOnScreenOverlayEnabled(context: Context, enabled: Boolean) =
      BrailleUserPreferencesDisplay.writeOnScreenOverlayEnabled(context, enabled)

    /** Reads whether to show search tutorial again. */
    @JvmStatic
    fun readSearchTutorialNeverShow(context: Context): Boolean =
      BrailleUserPreferencesDisplay.readSearchTutorialNeverShow(context)

    /** Writes whether to search tutorial again. */
    @JvmStatic
    fun writeSearchTutorialNeverShow(context: Context, neverShowAgain: Boolean) =
      BrailleUserPreferencesDisplay.writeSearchTutorialNeverShow(context, neverShowAgain)

    /** Reads should notify changing braille display input code preference. */
    @JvmStatic
    fun readShowSwitchBrailleDisplayInputCodeGestureTip(context: Context): Boolean =
      BrailleUserPreferencesDisplay.readShowSwitchBrailleDisplayInputCodeGestureTip(context)

    /** Writes should notify changing braille display input code preference. */
    @JvmStatic
    fun writeShowSwitchBrailleDisplayInputCodeGestureTip(context: Context, showTip: Boolean) =
      BrailleUserPreferencesDisplay.writeShowSwitchBrailleDisplayInputCodeGestureTip(context, showTip)

    /** Reads should notify changing braille display output code preference. */
    @JvmStatic
    fun readShowSwitchBrailleDisplayOutputCodeGestureTip(context: Context): Boolean =
      BrailleUserPreferencesDisplay.readShowSwitchBrailleDisplayOutputCodeGestureTip(context)

    /** Writes should notify changing braille display output code preference. */
    @JvmStatic
    fun writeShowSwitchBrailleDisplayOutputCodeGestureTip(context: Context, showTip: Boolean) =
      BrailleUserPreferencesDisplay.writeShowSwitchBrailleDisplayOutputCodeGestureTip(context, showTip)

    /** Writes should notify changing braille display output code preference. */
    @JvmStatic
    fun readShowNavigationCommandUnavailableTip(context: Context): Boolean =
      BrailleUserPreferencesDisplay.readShowNavigationCommandUnavailableTip(context)

    /** Writes should notify navigation commands are unavailable tip. */
    @JvmStatic
    fun writeShowNavigationCommandUnavailableTip(context: Context, showTip: Boolean) =
      BrailleUserPreferencesDisplay.writeShowNavigationCommandUnavailableTip(context, showTip)

    /** Reads whether to show usb connection dialog. */
    @JvmStatic
    fun readShowUsbConnectDialog(context: Context): Boolean =
      BrailleUserPreferencesDisplay.readShowUsbConnectDialog(context)

    /** Write whether to show usb connection dialog. */
    @JvmStatic
    fun writeShowUsbConnectDialog(context: Context, showAgain: Boolean) =
      BrailleUserPreferencesDisplay.writeShowUsbConnectDialog(context, showAgain)

    /** Reads whether to do reverse panning buttons preference. */
    @JvmStatic
    fun readReversePanningButtons(context: Context): Boolean =
      BrailleUserPreferencesDisplay.readReversePanningButtons(context)

    /** Writes whether to do reverse panning buttons preference. */
    @JvmStatic
    fun writeReversePanningButtons(context: Context, reversePanningButtons: Boolean) =
      BrailleUserPreferencesDisplay.writeReversePanningButtons(context, reversePanningButtons)

    /** Reads the timed message duration fraction user preference. */
    @JvmStatic
    fun readTimedMessageDurationFraction(context: Context): String =
      BrailleUserPreferencesDisplay.readTimedMessageDurationFraction(context)

    /**
     * Calculates the timed message duration in millisecond. Minimum is 3 seconds.
     *
     * <p>Equation: (amount of characters) / 5 * fraction seconds.
     */
    @JvmStatic
    fun getTimedMessageDurationInMillisecond(context: Context, amountOfCharacter: Int): Int =
      BrailleUserPreferencesDisplay.getTimedMessageDurationInMillisecond(context, amountOfCharacter)

    /** Gets intervals to blink the cursor. */
    @JvmStatic
    fun getAvailableBlinkingIntervalsMs(context: Context): Array<String> =
      BrailleUserPreferencesDisplay.getAvailableBlinkingIntervalsMs(context)

    /** Reads the interval to blink the cursor. */
    @JvmStatic
    fun readBlinkingIntervalMs(context: Context): Int =
      BrailleUserPreferencesDisplay.readBlinkingIntervalMs(context)

    /** Adds count when switching braille grade. */
    @JvmStatic
    fun writeSwitchContactedCount(context: Context) =
      BrailleUserPreferencesDisplay.writeSwitchContactedCount(context)

    /** Reads whether to announce tips when switching braille grade. */
    @JvmStatic
    fun readAnnounceSwitchContracted(context: Context): Boolean =
      BrailleUserPreferencesDisplay.readAnnounceSwitchContracted(context)

    /** Writes the interval to blink the cursor. */
    @JvmStatic
    fun writeBlinkingIntervalMs(context: Context, interval: Int) =
      BrailleUserPreferencesDisplay.writeBlinkingIntervalMs(context, interval)

    /** Reads the auto scroll duration. */
    @JvmStatic
    fun readAutoScrollDuration(context: Context): Int =
      BrailleUserPreferencesDisplay.readAutoScrollDuration(context)

    /** Writes the auto scroll duration. */
    @JvmStatic
    fun writeAutoScrollDuration(context: Context, durationMs: Int) =
      BrailleUserPreferencesDisplay.writeAutoScrollDuration(context, durationMs)

    /** Increases the auto scroll duration. */
    @JvmStatic
    fun increaseAutoScrollDuration(context: Context) =
      BrailleUserPreferencesDisplay.increaseAutoScrollDuration(context)

    /** Increases the auto scroll duration. */
    @JvmStatic
    fun decreaseAutoScrollDuration(context: Context) =
      BrailleUserPreferencesDisplay.decreaseAutoScrollDuration(context)

    /** Reads whether to auto adjust duration. */
    @JvmStatic
    fun readAutoAdjustDurationEnable(context: Context): Boolean =
      BrailleUserPreferencesDisplay.readAutoAdjustDurationEnable(context)

    /** Writes the enable state of auto adjust duration. */
    @JvmStatic
    fun writeAutoAdjustDurationEnable(context: Context, enable: Boolean) =
      BrailleUserPreferencesDisplay.writeAutoAdjustDurationEnable(context, enable)

    /** Reads whether to show popup message. */
    @JvmStatic
    fun readPopupMessageEnabled(context: Context): Boolean =
      BrailleUserPreferencesDisplay.readPopupMessageEnabled(context)

    /** Writes whether to show popup message. */
    @JvmStatic
    fun writePopupMessageEnabled(context: Context, enabled: Boolean) =
      BrailleUserPreferencesDisplay.writePopupMessageEnabled(context, enabled)

    /** Reads whether to save popup message. */
    @JvmStatic
    fun readPopupMessageHistoryEnabled(context: Context): Boolean =
      BrailleUserPreferencesDisplay.readPopupMessageHistoryEnabled(context)

    /** Writes whether to save popup message. */
    @JvmStatic
    fun writePopupMessageHistoryEnabled(context: Context, enabled: Boolean) =
      BrailleUserPreferencesDisplay.writePopupMessageHistoryEnabled(context, enabled)

    /** Reads whether to show captions. */
    @JvmStatic
    fun readCaptionEnabled(context: Context): Boolean =
      BrailleUserPreferencesDisplay.readCaptionEnabled(context)

    /** Writes whether to show captions. */
    @JvmStatic
    fun writeCaptionEnabled(context: Context, enabled: Boolean) =
      BrailleUserPreferencesDisplay.writeCaptionEnabled(context, enabled)

    /** Write whether to show caption tutorial dialog. */
    @JvmStatic
    fun writeShowCaptionTutorialDialogAgain(context: Context, showAgain: Boolean) =
      BrailleUserPreferencesDisplay.writeShowCaptionTutorialDialogAgain(context, showAgain)

    /** Reads whether to show caption tutorial dialog. */
    @JvmStatic
    fun readShowCaptionTutorialDialogAgain(context: Context): Boolean =
      BrailleUserPreferencesDisplay.readShowCaptionTutorialDialogAgain(context)

    /** Writes last time to show caption tutorial dialog. */
    @JvmStatic
    fun writeShowCaptionTime(context: Context, time: Long) =
      BrailleUserPreferencesDisplay.writeShowCaptionTime(context, time)

    /** Reads last time to show caption tutorial dialog. */
    @JvmStatic
    fun readShowCaptionTime(context: Context): Instant =
      BrailleUserPreferencesDisplay.readShowCaptionTime(context)

    /**
     * Writes gesture action mappings. If the list is empty, the action is removed from preferences.
     */
    @JvmStatic
    fun writeGestureAction(context: Context, actionGesturPair: Pair<BrailleImeAction, List<String>>) =
      BrailleUserPreferencesDisplay.writeGestureAction(context, actionGesturPair)

    /** Resets gesture action mapping. */
    @JvmStatic
    fun resetGestureAction(context: Context, action: BrailleImeAction) =
      BrailleUserPreferencesDisplay.resetGestureAction(context, action)

    /** Resets all gesture action map. */
    @JvmStatic
    fun resetAllGestureActionMap(context: Context) =
      BrailleUserPreferencesDisplay.resetAllGestureActionMap(context)

    /** Reads gesture action map. */
    @JvmStatic
    fun readGestureActionMap(context: Context): MutableMap<BrailleImeAction, List<String>> =
      BrailleUserPreferencesDisplay.readGestureActionMap(context)

    /** Reads whether to show command change dialog. */
    @JvmStatic
    fun readShowCommandChangeDialog(context: Context): Boolean =
      BrailleUserPreferencesDisplay.readShowCommandChangeDialog(context)

    /** Reads whether to show command change dialog. */
    @JvmStatic
    fun writeShowCommandChangeDialog(context: Context, showDialog: Boolean) =
      BrailleUserPreferencesDisplay.writeShowCommandChangeDialog(context, showDialog)

    // ── 文件入口与版本迁移 ──────────────────────────────────────────────────

    @JvmStatic
    fun getSharedPreferences(context: Context, fileName: String): SharedPreferences =
      BrailleUserPreferencesMigration.getSharedPreferences(context, fileName)

    /** Migrates user preferences if necerssary.. */
    @JvmStatic
    fun migrateIfNecessary(context: Context) =
      BrailleUserPreferencesMigration.migrateIfNecessary(context)

    @VisibleForTesting
    @JvmStatic
    fun testing_resetCurrentVersion(context: Context) =
      BrailleUserPreferencesMigration.testing_resetCurrentVersion(context)
  }
}
