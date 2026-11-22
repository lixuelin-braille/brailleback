/*
 * Copyright 2021 Google Inc.
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
import android.view.KeyEvent
import com.google.android.accessibility.braille.common.BrailleImeAction
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.translate.BrailleLanguages.Code
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.interfaces.BrailleImeForBrailleDisplay
import com.google.android.accessibility.braille.interfaces.BrailleWord
import com.google.android.accessibility.braille.interfaces.ImeAction
import com.google.android.accessibility.braille.translate.BrailleTranslator
import java.util.Objects

/**
 * 点显器侧桥接实现：硬件点显器的连接/断开、动作下发、点位输入、光标移动、落定与 BARD Mobile
 * 快捷键都经此回调进入 [BrailleIme]。从 [BrailleIme] 拆出，本类只持有回调对象本身。
 */
class BrailleImeDisplayBridge(private val ime: BrailleIme) : BrailleImeForBrailleDisplay {

  override fun onBrailleDisplayConnected() {
    BrailleImeLog.d(TAG, "onBrailleDisplayConnected")
    ime.brailleDisplayConnectedAndNotSuspended = true
    ime.updateInputView()
  }

  override fun onBrailleDisplayDisconnected() {
    BrailleImeLog.d(TAG, "onBrailleDisplayDisconnected")
    ime.brailleDisplayConnectedAndNotSuspended = false
    if (FeatureFlagReader.enableBrailleImeOnDeviceWithoutFivePointers(ime.applicationContext) &&
      !Utils.isMultiTouchSupported(ime.applicationContext)
    ) {
      ime.switchToNextInputMethod()
      return
    }
    if (ime.showKeyboardView()) {
      ime.updateInputView()
    }
  }

  override fun performImeAction(action: ImeAction): Boolean {
    val result = ime.brailleImeActor!!.performAction(action as BrailleImeAction)
    if (result) {
      ime.showOnBrailleDisplay(/* retranslate= */ false)
    }
    return result
  }

  override fun sendBrailleDots(brailleCharacter: BrailleCharacter): Boolean {
    if (ime.brailleImeActor == null || ime.editBuffer == null || !ime.isConnectionValid()) {
      BrailleImeLog.d(TAG, "sendBrailleDots: not in Braille IME")
      return false
    }
    if (ime.keyboardView != null && ime.keyboardView!!.getStripView() != null) {
      ime.keyboardView!!.getStripView()!!.animateInput(brailleCharacter.toDotNumbers())
    }
    val result: Boolean
    if (brailleCharacter.isEmpty()) {
      result = ime.brailleImeActor!!.performAction(BrailleImeAction.ADD_SPACE_OR_NEXT_ITEM)
    } else if (brailleCharacter == BrailleCharacter.DOT7) {
      result =
        ime.brailleImeActor!!.performAction(BrailleImeAction.DELETE_CHARACTER_OR_PREVIOUS_ITEM)
    } else if (brailleCharacter == BrailleCharacter.DOT8) {
      result = commitHoldingsAndPerformEnterKeyAction()
    } else {
      result = !TextUtils.isEmpty(ime.brailleImeActor!!.performTyping(brailleCharacter))
    }
    if (result) {
      ime.showOnBrailleDisplay(/* retranslate= */ false)
    }
    return result
  }

  override fun moveTextFieldCursor(toIndex: Int): Boolean {
    val result = ime.editBuffer!!.moveTextFieldCursor(ime.getImeConnection(), toIndex)
    if (result) {
      ime.showOnBrailleDisplay(/* retranslate= */ false)
    }
    return result
  }

  override fun moveHoldingsCursor(toIndex: Int): Boolean {
    val result = ime.editBuffer!!.moveHoldingsCursor(ime.getImeConnection(), toIndex)
    if (result) {
      ime.showOnBrailleDisplay(/* retranslate= */ false)
    }
    return result
  }

  override fun commitHoldings() {
    if (ime.editBuffer != null) {
      ime.editBuffer!!.commit(ime.getImeConnection())
    }
  }

  override fun commitHoldingsAndPerformEnterKeyAction(): Boolean {
    commitHoldings()
    if (ime.currentInputConnection!!.sendKeyEvent(
      KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
    ) {
      return ime.currentInputConnection!!.sendKeyEvent(
        KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }
    return false
  }

  override fun updateResultForDisplay() {
    ime.showOnBrailleDisplay(/* retranslate= */ false)
  }

  override fun isBrailleKeyboardActivated(): Boolean {
    return ime.isInputViewShown() && ime.isTalkBackEnabled()
  }

  override fun handleBrailleKeyForBARDMobile(keyCode: Int): Boolean {
    // Only handle Bard application.
    if (ime.currentInputEditorInfo == null ||
      !Objects.equals(ime.currentInputEditorInfo!!.packageName, BARD_PACKAGE_NAME)
    ) {
      return false
    }
    // To allow BARD Mobile to receive keyboard shortcuts, must use English computer braille.
    // See BARD Mobile keyboard shortcuts:
    // https://nlsbard.loc.gov/apidocs/BARDMobile.userguide.iOS.1.0.html#BrailleShortcutKeys7.3
    val translator: BrailleTranslator =
      BrailleUserPreferences.readTranslatorFactory(ime)
        .create(ime, Code.EN_NABCC.name, /* contractedMode= */ false)
    val key = translator.translateToPrint(BrailleWord(byteArrayOf(keyCode.toByte())))
    return ime.currentInputConnection!!.commitText(key, /* newCursorPosition= */ 1)
  }

  private companion object {
    private const val TAG = "BrailleIme"
    private const val BARD_PACKAGE_NAME = "gov.loc.nls.dtb"
  }
}
