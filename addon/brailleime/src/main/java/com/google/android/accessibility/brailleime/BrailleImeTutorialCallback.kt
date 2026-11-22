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
import com.google.android.accessibility.braille.common.FeedbackManager
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.brailleime.tutorial.TutorialView.TutorialCallback
import com.google.android.accessibility.brailleime.tutorial.TutorialView.TutorialState.State
import com.google.android.accessibility.utils.output.SpeechController.UtteranceCompleteRunnable

/**
 * 教程与退出提醒回调：教程激活/停用、播报与提示音、教程完成/重开、朝向委托注册，以及空闲一段时间
 * 后提醒用户如何退出键盘。教程的「打开设置」动作复用 [BrailleImeDialogCoordinator] 同一入口。
 */
class BrailleImeTutorialCallback(
  private val ime: BrailleIme,
  private val coordinator: BrailleImeDialogCoordinator,
) : TutorialCallback {

  val escapeReminderCallback: EscapeReminder.Callback =
    object : EscapeReminder.Callback {
      override fun onRemind(utteranceCompleteRunnable: UtteranceCompleteRunnable) {
        BrailleCommonTalkBackSpeaker.getInstance()
          .speak(
            ime.getString(R.string.reminder_announcement),
            BrailleIme.ANNOUNCE_DELAY_MS,
            utteranceCompleteRunnable)
      }

      override fun shouldAnnounce(): Boolean {
        return ime.keyboardView!!.isViewContainerCreated() && ime.tutorialState == State.NONE
      }
    }

  override fun onBrailleImeActivated() {
    ime.activateBrailleIme()
  }

  override fun onBrailleImeInactivated() {
    ime.deactivateBrailleIme()
  }

  override fun onAudialAnnounce(
    announcement: String,
    delayMs: Int,
    announceType: TalkBackSpeaker.AnnounceType,
    utteranceCompleteRunnable: UtteranceCompleteRunnable?,
  ) {
    BrailleCommonTalkBackSpeaker.getInstance()
      .speak(announcement, delayMs, announceType, utteranceCompleteRunnable)
  }

  override fun onPlaySound(type: FeedbackManager.Type) {
    ime.feedbackManager!!.emitFeedback(type)
  }

  override fun onSwitchToNextInputMethod() {
    ime.switchToNextInputMethod()
    ime.brailleImeAnalytics!!.logTutorialFinishedBySwitchToNextInputMethod()
  }

  override fun onLaunchSettings() {
    coordinator.launchSettings()
    ime.brailleImeAnalytics!!.logTutorialFinishedByLaunchSettings()
  }

  override fun onTutorialFinished() {
    BrailleUserPreferences.setTutorialFinished(ime.applicationContext)
    BrailleIme.talkBackForBrailleIme!!.restoreSilenceOnProximity()
    ime.brailleImeAnalytics!!.logTutorialFinishedByTutorialCompleted()
    ime.reactivate(State.NONE)
  }

  override fun onRestartTutorial() {
    ime.reactivate(ime.keyboardView!!.getTutorialStatus())
  }

  override fun registerOrientationChange(callBack: OrientationMonitor.Callback) {
    ime.orientationCallbackDelegate = callBack
  }

  override fun unregisterOrientationChange() {
    ime.orientationCallbackDelegate = null
  }
}
