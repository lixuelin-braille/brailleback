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

import android.os.Handler
import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.FeedbackManager
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.brailleime.input.BrailleInputView.FingersPattern
import com.google.android.accessibility.utils.output.SpeechController.UtteranceCompleteRunnable
import java.util.concurrent.atomic.AtomicInteger

/**
 * 两点校准的播报与提示音中枢：持有校准播报 Handler、语音流水号、左右手/三点四点提示文案与
 * 重复播报回调。触屏输入平面回调与横竖屏朝向回调共用本类，避免两套 Handler 与文案分叉。
 */
class BrailleImeCalibrationAnnouncer(private val ime: BrailleIme) {

  private val instructionSpeechId = AtomicInteger()
  private val calibrationAnnouncementHandler = Handler()

  /** 清除所有挂起的校准播报任务（输入法停用/销毁时调用）。 */
  fun clearCallbacks() {
    calibrationAnnouncementHandler.removeCallbacksAndMessages(null)
  }

  fun isEightDotsBraille(): Boolean {
    return BrailleUserPreferences.isCurrentActiveInputCodeEightDot(ime.applicationContext)
  }

  fun getTwoStepsCalibrationAnnounceString(fingersPattern: FingersPattern): String {
    val reverseDot = BrailleUserPreferences.readReverseDotsMode(ime)
    val sb = StringBuilder()
    return when (fingersPattern) {
      FingersPattern.NO_FINGERS,
      FingersPattern.FIVE_FINGERS,
      FingersPattern.SIX_FINGERS,
      FingersPattern.SEVEN_FINGERS -> {
        sb.append(
            ime.getString(
              R.string.calibration_step1_hold_left_or_right_finger_announcement,
              ime.getString(if (reverseDot) R.string.right_hand else R.string.left_hand)))
          .append(" ")
          .append(
            ime.getString(
              if (isEightDotsBraille())
                R.string.calibration_hold_left_or_right_four_finger_announcement
              else R.string.calibration_hold_left_or_right_three_finger_announcement,
              ime.getString(if (reverseDot) R.string.right_hand else R.string.left_hand)))
        sb.toString()
      }
      FingersPattern.FIRST_THREE_FINGERS -> {
        sb.append(
            ime.getString(
              R.string.calibration_step2_hold_left_or_right_finger_announcement,
              ime.getString(if (reverseDot) R.string.left_hand else R.string.right_hand)))
          .append(" ")
          .append(
            ime.getString(
              if (isEightDotsBraille())
                R.string.calibration_hold_left_or_right_four_finger_announcement
              else R.string.calibration_hold_left_or_right_three_finger_announcement,
              ime.getString(if (reverseDot) R.string.left_hand else R.string.right_hand)))
        sb.toString()
      }
      FingersPattern.FIRST_FOUR_FINGERS -> {
        sb.append(
            ime.getString(
              R
                .string
                .eightDot_braille_calibration_step2_hold_left_or_right_finger_announcement,
              ime.getString(if (reverseDot) R.string.left_hand else R.string.right_hand)))
          .append(" ")
          .append(
            ime.getString(
              if (isEightDotsBraille())
                R.string.calibration_hold_left_or_right_four_finger_announcement
              else R.string.calibration_hold_left_or_right_three_finger_announcement,
              ime.getString(if (reverseDot) R.string.left_hand else R.string.right_hand)))
        sb.toString()
      }
      else -> ""
    }
  }

  fun getRepeatedTwoStepCalibrationAnnounceString(fingersPattern: FingersPattern): String {
    val reverseDot = BrailleUserPreferences.readReverseDotsMode(ime)
    return when (fingersPattern) {
      FingersPattern.NO_FINGERS,
      FingersPattern.FIVE_FINGERS,
      FingersPattern.SIX_FINGERS,
      FingersPattern.SEVEN_FINGERS ->
        ime.getString(
          if (isEightDotsBraille())
            R.string.calibration_hold_left_or_right_four_finger_announcement
          else R.string.calibration_hold_left_or_right_three_finger_announcement,
          ime.getString(if (reverseDot) R.string.right_hand else R.string.left_hand))
      FingersPattern.FIRST_THREE_FINGERS, FingersPattern.FIRST_FOUR_FINGERS ->
        ime.getString(
          if (isEightDotsBraille())
            R.string.calibration_hold_left_or_right_four_finger_announcement
          else R.string.calibration_hold_left_or_right_three_finger_announcement,
          ime.getString(if (reverseDot) R.string.left_hand else R.string.right_hand))
      else -> ""
    }
  }

  fun speakAnnouncementRepeatedly(announcement: CharSequence, delay: Int) {
    calibrationAnnouncementHandler.removeCallbacksAndMessages(null)
    // Do not use the delay in Talkback because we want to be able to cancel it.
    calibrationAnnouncementHandler.postDelayed(
      {
        BrailleCommonTalkBackSpeaker.getInstance()
          .speak(announcement, getRepeatAnnouncementRunnable(announcement))
      },
      delay.toLong())
  }

  fun getRepeatAnnouncementRunnable(repeatedAnnouncement: CharSequence): UtteranceCompleteRunnable {
    val speechId = instructionSpeechId.incrementAndGet()
    return UtteranceCompleteRunnable { status ->
      if (speechId == instructionSpeechId.get() && ime.keyboardView!!.inTwoStepCalibration()) {
        speakAnnouncementRepeatedly(repeatedAnnouncement, CALIBRATION_ANNOUNCEMENT_REPEAT_MS)
      }
    }
  }

  /** 校准成功：先连播提示音，再延时播报结果文案。 */
  fun playCalibrationDoneSoundAndAnnouncement(announcement: String) {
    for (i in 0 until CALIBRATION_EARCON_REPEAT_COUNT) {
      ime.feedbackManager!!.emitFeedback(
        FeedbackManager.Type.CALIBRATION, CALIBRATION_EARCON_DELAY_MS * i)
    }
    // Wait a second for playing sound and then speak the post-action announcement.
    BrailleCommonTalkBackSpeaker.getInstance()
      .speak(
        announcement,
        ANNOUNCE_CALIBRATION_DELAY_MS,
        TalkBackSpeaker.AnnounceType.INTERRUPT)
  }

  /** 供输入平面回调把两步校准首条提示延时挂到播报 Handler 上。 */
  fun postDelayedOnCalibrationHandler(runnable: Runnable, delayMs: Long) {
    calibrationAnnouncementHandler.postDelayed(runnable, delayMs)
  }

  fun removeCalibrationHandlerCallbacks() {
    calibrationAnnouncementHandler.removeCallbacksAndMessages(null)
  }

  internal companion object {
    internal const val ANNOUNCE_CALIBRATION_DELAY_MS = 1500
    internal const val CALIBRATION_EARCON_DELAY_MS = 500
    internal const val CALIBRATION_EARCON_REPEAT_COUNT = 3
    internal const val CALIBRATION_ANNOUNCEMENT_REPEAT_MS = 8000
  }
}
