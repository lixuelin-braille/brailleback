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

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.google.android.accessibility.brailleime.dialog.ContextMenuDialog
import com.google.android.accessibility.brailleime.dialog.TalkBackOffDialog
import com.google.android.accessibility.brailleime.dialog.TalkBackSuspendDialog
import com.google.android.accessibility.brailleime.dialog.TooFewTouchPointsDialog
import com.google.android.accessibility.brailleime.settings.BrailleImePreferencesActivity
import com.google.android.accessibility.brailleime.tutorial.TutorialView.TutorialState.State

/**
 * 对话框协调器：持有菜单、读屏关闭、读屏挂起、触点不足四类弹窗的回调，负责弹窗的弹出/收起，
 * 以及输入法切换广播（切换走后清掉残留弹窗）。教程回调复用这里的「打开设置」动作。
 */
class BrailleImeDialogCoordinator(private val ime: BrailleIme) {

  fun showTalkBackOffDialog() {
    // When screen rotates, onStartInputView is called and if there is a dialog showing, keep it
    // showing instead of adding a new one.
    if (!ime.talkbackOffDialog!!.isShowing()) {
      ime.brailleImeAnalytics!!.logTalkBackOffDialogDisplay()
      ime.keyboardView!!.showViewAttachedDialog(ime.talkbackOffDialog!!)
    }
  }

  fun showTalkBackSuspendDialog() {
    // When screen rotates, onStartInputView is called and if there is a dialog showing, keep it
    // showing instead of adding a new one.
    if (!ime.talkBackSuspendDialog!!.isShowing()) {
      ime.brailleImeAnalytics!!.logTalkBackOffDialogDisplay()
      ime.keyboardView!!.showViewAttachedDialog(ime.talkBackSuspendDialog!!)
    }
  }

  fun showTooFewTouchPointsDialog() {
    // When screen rotates, onStartInputView is called and if there is a dialog showing, keep it
    // showing instead of adding a new one.
    if (!ime.tooFewTouchPointsDialog!!.isShowing()) {
      ime.brailleImeAnalytics!!.logFewTouchPointsDialogDisplay()
      ime.keyboardView!!.showViewAttachedDialog(ime.tooFewTouchPointsDialog!!)
    }
  }

  fun dismissDialogs() {
    ime.talkbackOffDialog!!.dismiss()
    ime.contextMenuDialog!!.dismiss()
    ime.tooFewTouchPointsDialog!!.dismiss()
    ime.talkBackSuspendDialog!!.dismiss()
  }

  /** 打开盲文键盘设置页；教程回调与菜单回调共用同一入口。 */
  fun launchSettings() {
    val intent = Intent()
    val name = ComponentName(ime.packageName, BrailleImePreferencesActivity::class.java.name)
    intent.component = name
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ime.startActivity(intent)
  }

  val contextMenuDialogCallback: ContextMenuDialog.Callback =
    object : ContextMenuDialog.Callback {
      override fun onDialogHidden() {
        ime.activateBrailleIme()
        ime.startAnalyticsPossibly()
        ime.layoutOrientator!!.startIfNeeded()
        ime.escapeReminder!!.startTimer()
        ime.showOnBrailleDisplay(/* retranslate= */ false)
      }

      override fun onDialogShown() {
        ime.deactivateBrailleIme()
        ime.brailleImeAnalytics!!.collectSessionEvents()
        ime.escapeReminder!!.cancelTimer()
        ime.layoutOrientator!!.stop()
      }

      override fun onLaunchSettings() {
        launchSettings()
      }

      override fun onTutorialOpen() {
        ime.reactivate(State.INTRO)
      }

      override fun onTutorialClosed() {}

      override fun onCalibration() {
        ime.activateBrailleIme()
        ime.layoutOrientator!!.startIfNeeded()
        ime.keyboardView!!.calibrateBrailleInputView()
      }
    }

  val talkBackOffDialogCallback: TalkBackOffDialog.Callback =
    object : TalkBackOffDialog.Callback {
      override fun onSwitchToNextIme() {
        ime.switchToNextInputMethod()
      }

      override fun onLaunchSettings() {
        // Highlight TalkBack item in Accessibility Settings upon arriving there (Pixel only).
        Utils.highlightTalkBackSettings(ime)
        // Collapse notification panel (quick settings).
        Utils.collapseNotificationPanel(ime)
      }
    }

  val talkBackSuspendDialogCallback: TalkBackSuspendDialog.Callback =
    object : TalkBackSuspendDialog.Callback {
      override fun onSwitchToNextIme() {
        ime.switchToNextInputMethod()
      }
    }

  val tooFewTouchPointsDialogCallback: TooFewTouchPointsDialog.Callback =
    object : TooFewTouchPointsDialog.Callback {
      override fun onSwitchToNextIme() {
        ime.switchToNextInputMethod()
      }
    }

  val imeChangeListener: BroadcastReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_INPUT_METHOD_CHANGED) {
          dismissDialogs()
        }
      }
    }
}
