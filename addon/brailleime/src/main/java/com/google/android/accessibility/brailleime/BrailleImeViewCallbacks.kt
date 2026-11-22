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
import com.google.android.accessibility.braille.common.ImeConnection
import com.google.android.accessibility.braille.common.translate.EditBuffer
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.brailleime.input.BrailleDisplayImeStripView
import com.google.android.accessibility.brailleime.keyboardview.KeyboardView.KeyboardViewCallback
import com.google.android.accessibility.brailleime.tutorial.TutorialView.TutorialState.State

/**
 * 视图与 Actor 回调：动作执行器向输入法请求收键盘/切键盘/菜单/提交动作，键盘视图就绪/更新/
 * 清空时激活或停用输入法，以及点显器条视图在触屏键盘与下一个输入法之间切换。
 */
class BrailleImeViewCallbacks(private val ime: BrailleIme) {

  val brailleImeActorCallback: BrailleImeActor.Callback =
    object : BrailleImeActor.Callback {
      override fun hideBrailleKeyboard() {
        ime.hideSelf()
        ime.escapeReminder!!.increaseExitKeyboardCounter()
      }

      override fun switchToNextInputMethod() {
        ime.switchToNextInputMethod()
        ime.escapeReminder!!.increaseExitKeyboardCounter()
      }

      override fun showContextMenu() {
        ime.keyboardView!!.showViewAttachedDialog(ime.contextMenuDialog!!)
        ime.brailleImeAnalytics!!.logGestureActionOpenOptionsMenu()
        ime.escapeReminder!!.increaseOptionDialogCounter()
      }

      override fun performEditorAction() {
        ime.performEditorAction(ime.getImeConnection().inputConnection)
      }

      override fun isConnectionValid(): Boolean {
        return ime.isConnectionValid()
      }

      override fun getImeConnection(): ImeConnection {
        return ime.getImeConnection()
      }

      override fun getEditBuffer(): EditBuffer? {
        return ime.editBuffer
      }
    }

  val keyboardViewCallback: KeyboardViewCallback =
    object : KeyboardViewCallback {
      override fun onViewReady() {
        BrailleImeLog.d(TAG, "onViewReady")
        ime.activateIfNeeded()
        ime.startAnalyticsPossibly()
        if (!ime.keyboardView!!.isTutorialShown()) {
          ime.showOnBrailleDisplay(/* retranslate= */ false)
        }
      }

      override fun onViewUpdated() {
        BrailleImeLog.d(TAG, "onViewUpdated")
        if (!ime.contextMenuDialog!!.isShowing() && ime.tutorialState != State.INTRO) {
          ime.activateBrailleIme()
        }
      }

      override fun onViewCleared() {
        BrailleImeLog.d(TAG, "onViewCleared")
        ime.deactivateIfNeeded()
        // Finish session while screen off because no called onFinishInputView() in this case.
        ime.brailleImeAnalytics!!.collectSessionEvents()
      }

      override fun isHideScreenMode(): Boolean {
        return BrailleIme.talkBackForBrailleIme!!.isHideScreenMode()
      }
    }

  val brailleDisplayKeyboardCallback: BrailleDisplayImeStripView.CallBack =
    object : BrailleDisplayImeStripView.CallBack {
      override fun onSwitchToOnscreenKeyboard() {
        BrailleImeLog.d(TAG, "onStripClicked")
        ime.brailleDisplayConnectedAndNotSuspended = false
        ime.updateInputView()
        BrailleCommonTalkBackSpeaker.getInstance()
          .speak(
            ime.getString(R.string.switch_on_screen_keyboard_announcement),
            TalkBackSpeaker.AnnounceType.INTERRUPT)
        ime.keyboardView!!.setTableMode(ime.isCurrentTableTopMode())
        BrailleIme.brailleDisplayForBrailleIme!!.suspendInFavorOfBrailleKeyboard()
      }

      override fun onSwitchToNextKeyboard() {
        ime.switchToNextInputMethod()
      }
    }

  private companion object {
    private const val TAG = "BrailleIme"
  }
}
