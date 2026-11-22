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

import com.google.android.accessibility.braille.interfaces.BrailleDisplayForBrailleIme
import com.google.android.accessibility.braille.interfaces.BrailleImeForBrailleDisplay
import com.google.android.accessibility.braille.interfaces.BrailleImeForTalkBack
import com.google.android.accessibility.braille.interfaces.TalkBackForBrailleIme.ServiceStatus
import com.google.android.accessibility.utils.KeyboardUtils
import com.google.android.accessibility.utils.input.CursorGranularity
import com.google.common.collect.ImmutableSet

/**
 * TalkBack 侧桥接实现：把读屏的挂起/恢复、明暗屏、触摸交互、粒度校验等回调转交给 [BrailleIme]
 * 编排。从 [BrailleIme] 拆出，本类只持有回调对象本身，不持有输入法生命周期状态。
 */
class BrailleImeTalkBackBridge(private val ime: BrailleIme) : BrailleImeForTalkBack {

  override fun onTalkBackSuspended() {
    BrailleImeLog.d(TAG, "onTalkBackSuspended")
    // We might get service state off when TalkBack turns off, but we'll handle it in
    // accessibilityServiceStatusChangeObserver.
    if (ime.isInputViewShown() &&
      BrailleIme.talkBackForBrailleIme!!.getServiceStatus() == ServiceStatus.SUSPEND
    ) {
      if (ime.keyboardView!!.isTutorialShown()) {
        ime.brailleImeAnalytics!!.logTutorialFinishedByTalkbackStop()
      }
      if (KeyboardUtils.areMultipleImesEnabled(ime)) {
        ime.switchToNextInputMethod()
      } else {
        ime.keyboardView!!.tearDown()
        ime.showTalkBackSuspendDialog()
      }
    }
  }

  override fun onTalkBackResumed() {
    BrailleImeLog.d(TAG, "onTalkBackResumed")
    // This callback won't be triggered when service state changes from off to on because it's
    // set to null when off so we register it back in
    // accessibilityServiceStatusChangeObserver.
    if (ime.isInputViewShown()) {
      ime.dismissDialogs()
      ime.createViewContainerAndAddView()
    }
  }

  override fun isTouchInteracting(): Boolean {
    return !ime.brailleDisplayConnectedAndNotSuspended && ime.keyboardView!!.isTouchInteracting()
  }

  override fun getBrailleImeForBrailleDisplay(): BrailleImeForBrailleDisplay {
    return ime.brailleImeForBrailleDisplay
  }

  override fun onScreenDim() {
    ime.keyboardView!!.setKeyboardViewTransparent(true)
  }

  override fun onScreenBright() {
    ime.keyboardView!!.setKeyboardViewTransparent(false)
  }

  override fun isGranularityValid(cursorGranularity: CursorGranularity): Boolean {
    return VALID_GRANULARITIES.contains(cursorGranularity)
  }

  override fun isBrailleKeyboardActivated(): Boolean {
    return ime.isInputViewShown()
  }

  private companion object {
    private const val TAG = "BrailleIme"

    // An Immutable set includes the granularities which are related to editing.
    private val VALID_GRANULARITIES: ImmutableSet<CursorGranularity> =
      ImmutableSet.of(
        CursorGranularity.CHARACTER,
        CursorGranularity.WORD,
        CursorGranularity.LINE,
        CursorGranularity.PARAGRAPH)
  }
}
