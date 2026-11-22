/*
 * Copyright 2019 Google Inc.
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

package com.google.android.accessibility.brailleime

import android.text.TextUtils
import androidx.annotation.Nullable
import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.brailleime.analytics.BrailleImeAnalytics
import com.google.android.accessibility.brailleime.input.BrailleInputView
import com.google.android.accessibility.brailleime.input.BrailleInputView.CalibrationTriggeredType
import com.google.android.accessibility.brailleime.input.BrailleInputView.FingersPattern
import com.google.android.accessibility.brailleime.input.DotHoldSwipe
import com.google.android.accessibility.brailleime.input.Swipe

/**
 * 触屏输入平面回调：把 [BrailleInputView] 产生的滑动、长按、点位输入与两点校准事件分发给手势
 * 控制器、编辑缓冲与遥测；校准文案/提示音统一走 [BrailleImeCalibrationAnnouncer]。
 */
class BrailleImeInputPlaneCallback(
  private val ime: BrailleIme,
  private val announcer: BrailleImeCalibrationAnnouncer,
) : BrailleInputView.Callback {

  override fun onSwipeProduced(swipe: Swipe): Boolean {
    if (ime.brailleImeGestureController!!.performSwipeAction(swipe)) {
      ime.showOnBrailleDisplay(/* retranslate= */ false)
      if (!ime.brailleDisplayConnectedAndNotSuspended) {
        ime.escapeReminder!!.restartTimer()
      }
      return true
    }
    return false
  }

  override fun onDotHoldAndDotSwipe(dotHoldSwipe: DotHoldSwipe): Boolean {
    if (ime.brailleImeGestureController!!.performDotHoldAndSwipeAction(dotHoldSwipe)) {
      ime.showOnBrailleDisplay(/* retranslate= */ false)
      return true
    }
    return false
  }

  override fun isCalibrationHoldRecognized(
    inTwoStepCalibration: Boolean,
    pointersHeldCount: Int,
  ): Boolean {
    return isSixDotCalibration(pointersHeldCount) ||
      isEightDotCalibration(pointersHeldCount) ||
      (inTwoStepCalibration && isConfirmedTwoStepCalibration(pointersHeldCount))
  }

  override fun onHoldProduced(pointersHeldCount: Int): Boolean {
    return ime.brailleImeGestureController!!.performDotHoldAction(pointersHeldCount)
  }

  @Nullable
  override fun onBrailleProduced(brailleChar: BrailleCharacter): String? {
    val result = ime.brailleImeGestureController!!.performTyping(brailleChar)
    if (!TextUtils.isEmpty(result)) {
      ime.escapeReminder!!.restartTimer()
    }
    return result
  }

  /**
   * 把还没分格的点流交给编辑缓冲，请它切出已经能确定的音节。
   *
   * <p>键盘不再靠停顿猜格边界（那会把慢慢按的一格切断、把连着按的两格粘住），改成整串
   * 点按音节识别。没有连接时返回 {@code null}，键盘会继续攒着，等切回编辑缓冲再处理。
   */
  @Nullable
  override fun segmentDotStream(dotStream: String, force: Boolean): List<BrailleCharacter>? {
    val buffer = ime.editBuffer
    return if (buffer == null) null else buffer.segmentDotStream(dotStream, force)
  }

  override fun onCalibration(
    calibration: CalibrationTriggeredType,
    fingersPattern: FingersPattern,
  ): Boolean {
    announcer.removeCalibrationHandlerCallbacks()
    var processed = false
    if (isCalibrationSucceeded(fingersPattern)) {
      announcer.playCalibrationDoneSoundAndAnnouncement(
        ime.getString(
          if (fingersPattern == FingersPattern.REMAINING_THREE_FINGERS ||
            fingersPattern == FingersPattern.REMAINING_FOUR_FINGERS
          ) R.string.remaining_calibration_finish_announcement
          else R.string.calibration_finish_announcement))
      ime.keyboardView!!.saveInputViewPoints()
      ime.escapeReminder!!.startTimer()
      processed = true
      ime.brailleImeAnalytics!!.logCalibrationFinish(
        mapCalibrationToType(calibration), ime.isCurrentTableTopMode(), announcer.isEightDotsBraille())
    } else if ((calibration == CalibrationTriggeredType.MANUAL &&
        fingersPattern == FingersPattern.NO_FINGERS) ||
      fingersPattern == FingersPattern.FIVE_FINGERS ||
      fingersPattern == FingersPattern.SIX_FINGERS ||
      fingersPattern == FingersPattern.SEVEN_FINGERS
    ) {
      ime.escapeReminder!!.cancelTimer()
      processed = true
      ime.brailleImeAnalytics!!.logCalibrationStarted(
        mapCalibrationToType(calibration), ime.isCurrentTableTopMode(), announcer.isEightDotsBraille())
      // Add 6/7 dots calibration for 8-dot braille. Wait for showing braille keyboard and
      // layout mode announcement finish.
      announcer.postDelayedOnCalibrationHandler(
        {
          BrailleCommonTalkBackSpeaker.getInstance()
            .speak(
              announcer.getTwoStepsCalibrationAnnounceString(fingersPattern),
              announcer.getRepeatAnnouncementRunnable(
                announcer.getRepeatedTwoStepCalibrationAnnounceString(fingersPattern)))
        },
        BrailleImeCalibrationAnnouncer.ANNOUNCE_CALIBRATION_DELAY_MS.toLong())
    } else if (fingersPattern == FingersPattern.FIRST_THREE_FINGERS ||
      fingersPattern == FingersPattern.FIRST_FOUR_FINGERS
    ) {
      processed = true
      announcer.playCalibrationDoneSoundAndAnnouncement(
        announcer.getTwoStepsCalibrationAnnounceString(fingersPattern))
    }
    return processed
  }

  override fun onCalibrationFailed(calibration: CalibrationTriggeredType) {
    ime.brailleImeAnalytics!!.logCalibrationFailed(
      mapCalibrationToType(calibration), ime.isCurrentTableTopMode(), announcer.isEightDotsBraille())
    announcer.removeCalibrationHandlerCallbacks()
    BrailleCommonTalkBackSpeaker.getInstance()
      .speak(
        ime.getString(R.string.calibration_fail_announcement),
        TalkBackSpeaker.AnnounceType.INTERRUPT)
    if (calibration != CalibrationTriggeredType.MANUAL) {
      BrailleCommonTalkBackSpeaker.getInstance()
        .speak(
          ime.getString(
            R.string.calibration_fail_try_again_announcement,
            BrailleUserPreferences.getCurrentTypingLanguageType(ime.applicationContext).getDotCount()))
    }
  }

  override fun onTwoStepCalibrationRetry(isFirstStep: Boolean) {
    announcer.removeCalibrationHandlerCallbacks()
    val reverseDot = BrailleUserPreferences.readReverseDotsMode(ime)
    val announcement =
      ime.getString(
        if (announcer.isEightDotsBraille())
          R.string.calibration_hold_left_or_right_four_finger_announcement
        else R.string.calibration_hold_left_or_right_three_finger_announcement,
        if (isFirstStep)
          ime.getString(if (reverseDot) R.string.right_hand else R.string.left_hand)
        else ime.getString(if (reverseDot) R.string.left_hand else R.string.right_hand))
    BrailleCommonTalkBackSpeaker.getInstance()
      .speak(
        announcement,
        TalkBackSpeaker.AnnounceType.INTERRUPT,
        announcer.getRepeatAnnouncementRunnable(announcement))
  }

  private fun isCalibrationSucceeded(fingersPattern: FingersPattern): Boolean {
    val currentInputCodeEightDot = announcer.isEightDotsBraille()
    return (fingersPattern == FingersPattern.REMAINING_THREE_FINGERS &&
      !currentInputCodeEightDot) ||
      (fingersPattern == FingersPattern.REMAINING_FOUR_FINGERS &&
        currentInputCodeEightDot) ||
      (fingersPattern == FingersPattern.SIX_FINGERS && !currentInputCodeEightDot) ||
      (fingersPattern == FingersPattern.EIGHT_FINGERS && currentInputCodeEightDot)
  }

  private fun mapCalibrationToType(
    calibration: CalibrationTriggeredType,
  ): BrailleImeAnalytics.CalibrationTriggeredType =
    when (calibration) {
      CalibrationTriggeredType.FIVE_FINGERS ->
        BrailleImeAnalytics.CalibrationTriggeredType.FIVE_FINGER
      CalibrationTriggeredType.SIX_FINGERS ->
        BrailleImeAnalytics.CalibrationTriggeredType.SIX_FINGER
      CalibrationTriggeredType.SEVEN_FINGERS ->
        BrailleImeAnalytics.CalibrationTriggeredType.SEVEN_FINGER
      CalibrationTriggeredType.EIGHT_FINGERS ->
        BrailleImeAnalytics.CalibrationTriggeredType.EIGHT_FINGER
      CalibrationTriggeredType.MANUAL -> BrailleImeAnalytics.CalibrationTriggeredType.MANUAL
    }

  private fun isEightDotCalibration(pointersHeldCount: Int): Boolean {
    // Do 2-step calibration for 5/6/7 dots.
    return (5 <= pointersHeldCount && pointersHeldCount <= 8) && announcer.isEightDotsBraille()
  }

  private fun isSixDotCalibration(pointersHeldCount: Int): Boolean {
    return (pointersHeldCount == 5 || pointersHeldCount == 6) && !announcer.isEightDotsBraille()
  }

  private fun isConfirmedTwoStepCalibration(pointersHeldCount: Int): Boolean {
    return if (announcer.isEightDotsBraille()) pointersHeldCount == 4 else pointersHeldCount == 3
  }
}
