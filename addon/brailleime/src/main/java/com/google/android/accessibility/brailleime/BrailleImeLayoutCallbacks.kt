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

import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.TouchDots
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.brailleime.LayoutOrientator.LayoutOrientatorCallback
import com.google.android.accessibility.brailleime.input.BrailleInputView.FingersPattern

/**
 * 朝向与桌面模式检测回调：传感器判定横屏/桌面模式后播报提示并切换键盘形态；同时把
 * [OrientationMonitor] 的朝向变化转发给教程视图注册的委托回调。
 */
class BrailleImeLayoutCallbacks(
  private val ime: BrailleIme,
  private val announcer: BrailleImeCalibrationAnnouncer,
) {

  val layoutOrientatorCallback: LayoutOrientatorCallback =
    object : LayoutOrientatorCallback {
      override fun useSensorsToDetectLayout(): Boolean {
        return BrailleUserPreferences.readLayoutMode(ime) == TouchDots.AUTO_DETECT &&
          !ime.keyboardView!!.isTutorialShown() &&
          !ime.brailleDisplayConnectedAndNotSuspended
      }

      override fun onDetectionChanged(isTabletop: Boolean, isFirstChangedEvent: Boolean) {
        val layout =
          ime.getString(
            if (isTabletop) R.string.switch_to_tabletop_announcement else R.string.screen_away)
        var calibrationTips = ""
        if (ime.keyboardView!!.inTwoStepCalibration()) {
          if (!isFirstChangedEvent) {
            calibrationTips =
              announcer.getTwoStepsCalibrationAnnounceString(FingersPattern.NO_FINGERS)
          }
        } else if (isTabletop) {
          calibrationTips =
            ime.getString(
              R.string.calibration_tip_announcement,
              BrailleUserPreferences.getCurrentTypingLanguageType(ime.applicationContext)
                .getDotCount())
        }
        if (isFirstChangedEvent) {
          val finalCalibrationTips = calibrationTips
          BrailleCommonTalkBackSpeaker.getInstance()
            .speak(layout, BrailleIme.ANNOUNCE_DELAY_MS)
          announcer.postDelayedOnCalibrationHandler(
            { BrailleCommonTalkBackSpeaker.getInstance().speak(finalCalibrationTips) },
            BrailleIme.ANNOUNCE_DELAY_MS.toLong())
        } else {
          BrailleCommonTalkBackSpeaker.getInstance()
            .speak(layout, TalkBackSpeaker.AnnounceType.INTERRUPT)
          announcer.removeCalibrationHandlerCallbacks()
          BrailleCommonTalkBackSpeaker.getInstance()
            .speak(
              calibrationTips,
              if (ime.keyboardView!!.inTwoStepCalibration())
                announcer.getRepeatAnnouncementRunnable(
                  announcer.getRepeatedTwoStepCalibrationAnnounceString(FingersPattern.NO_FINGERS))
              else null)
        }
        ime.keyboardView!!.setTableMode(isTabletop)
      }
    }

  val orientationMonitorCallback: OrientationMonitor.Callback =
    object : OrientationMonitor.Callback {
      override fun onOrientationChanged(orientation: OrientationMonitor.Orientation) {
        if (ime.orientationCallbackDelegate != null) {
          ime.orientationCallbackDelegate!!.onOrientationChanged(orientation)
        }
      }
    }
}
