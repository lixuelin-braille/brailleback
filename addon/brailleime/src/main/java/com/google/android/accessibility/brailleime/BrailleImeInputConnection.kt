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

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.provider.Settings
import android.text.TextUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker
import com.google.android.accessibility.braille.common.BrailleCommonUtils
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.ImeConnection
import com.google.android.accessibility.braille.common.ImeConnection.AnnounceType
import com.google.android.accessibility.braille.common.TouchDots
import com.google.android.accessibility.braille.common.translate.EditBufferUtils
import com.google.android.accessibility.braille.interfaces.BrailleDisplayForBrailleIme.ResultForDisplay
import com.google.android.accessibility.utils.AccessibilityServiceCompatUtils.Constants
import com.google.android.accessibility.utils.BuildVersionUtils
import com.google.android.accessibility.utils.KeyboardUtils
import java.util.Optional

/**
 * 输入连接与上屏助手：校验输入连接有效性、构造 [ImeConnection]、把编辑缓冲与文本回显发布到
 * 点显器、执行编辑器提交动作，以及退出本键盘并切换到下一个输入法。
 */
class BrailleImeInputConnection(private val ime: BrailleIme) {

  fun isConnectionValid(): Boolean {
    if (ime.currentInputConnection == null) {
      BrailleImeLog.e(TAG, "lack of InputConnection")
      return false
    }
    if (ime.currentInputEditorInfo == null) {
      BrailleImeLog.e(TAG, "lack of InputEditorInfo")
      return false
    }
    return true
  }

  fun getImeConnection(): ImeConnection {
    var announceType: AnnounceType = AnnounceType.SILENCE
    // 移植改造：宿主（BrailleBack）的桥接对象可能在服务销毁/重建期间被注销，
    // 此时 brailleDisplayForBrailleIme 与 talkBackForBrailleIme 都可能为 null。
    // 原版 TalkBack 中这两个引用与键盘同生共死，不会出现，故无判空；此处必须补上，
    // 否则隐藏键盘时（onFinishInputView -> tearDown -> onViewCleared -> deactivateIfNeeded）
    // 会抛 NullPointerException 直接闪退。
    val displayConnected =
      BrailleIme.brailleDisplayForBrailleIme != null &&
        BrailleIme.brailleDisplayForBrailleIme!!.isBrailleDisplayConnectedAndNotSuspended()
    val shouldAnnounceCharacter =
      BrailleIme.talkBackForBrailleIme != null &&
        (if (displayConnected)
          BrailleIme.talkBackForBrailleIme!!.shouldAnnounceCharacterForPhysicalKeyboard()
        else BrailleIme.talkBackForBrailleIme!!.shouldAnnounceCharacterForOnScreenKeyboard())
    if (shouldAnnounceCharacter) {
      announceType = AnnounceType.NORMAL
    }
    return ImeConnection(
      ime.currentInputConnection!!, ime.currentInputEditorInfo!!, announceType)
  }

  fun showOnBrailleDisplay() {
    showOnBrailleDisplay(/* retranslate= */ false)
  }

  fun showOnBrailleDisplay(retranslate: Boolean) {
    if (!ime.isInputViewShown()) {
      return
    }
    ime.mainHandler!!.post(
      {
        if (BrailleIme.brailleDisplayForBrailleIme == null ||
          ime.editBuffer == null ||
          !isConnectionValid()
        ) {
          return@post
        }
        val result: ResultForDisplay =
          ResultForDisplay.builder()
            .setHoldingsInfo(ime.editBuffer!!.getHoldingsInfo(getImeConnection()))
            .setOnScreenText(EditBufferUtils.getTextFieldText(ime.currentInputConnection!!))
            .setTextSelection(
              BrailleCommonUtils.getTextSelection(ime.currentInputConnection!!))
            .setIsMultiLine(
              EditBufferUtils.isMultiLineField(ime.currentInputEditorInfo!!.inputType))
            .setAction(Utils.getActionLabel(ime, ime.currentInputEditorInfo).toString())
            .setHint(Utils.getHint(getImeConnection()).toString())
            .setTextMasked(
              BrailleCommonUtils.isPasswordField(ime.currentInputEditorInfo) &&
                !BrailleCommonUtils.isVisiblePasswordField(ime.currentInputEditorInfo))
            .setRetranslate(retranslate)
            .build()
        BrailleIme.brailleDisplayForBrailleIme!!.showOnDisplay(result)
      })
  }

  /**
   * Performs the 'actions' specified, via imeOptions, by the application such as 'Send'. A
   * typical IME usually surfaces the trigger for such an action with an (often blue-colored)
   * action button.
   *
   * <p>By far the most common case is for an application to specify a single action (as opposed to
   * multiple). We don't currently support the distinguishment of multiple actions in that case.
   *
   * <p>Return {@code true} if the keyboard should remain showing.
   */
  fun performEditorAction(inputConnection: InputConnection?) {
    val editorInfo = ime.currentInputEditorInfo!!
    val editorAction = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
    BrailleImeLog.d(TAG, "performEnterAction editorAction = $editorAction")
    if (editorAction != EditorInfo.IME_ACTION_UNSPECIFIED &&
      editorAction != EditorInfo.IME_ACTION_NONE
    ) {
      if (TextUtils.equals(editorInfo.packageName, Constants.ANDROID_MESSAGES_PACKAGE_NAME)) {
        // Messages uses async thread to check conditions when performing submit. We pend the task
        // with 50 millis seconds to avoid perform action failed.
        Handler().postDelayed({ inputConnection!!.performEditorAction(editorAction) }, 50)
      } else {
        inputConnection!!.performEditorAction(editorAction)
      }
      if (editorAction == EditorInfo.IME_ACTION_NEXT) {
        BrailleCommonTalkBackSpeaker.getInstance().speak(ime.getString(R.string.perform_action_next))
      } else {
        BrailleCommonTalkBackSpeaker.getInstance()
          .speak(ime.getString(R.string.perform_action_submitting))
      }
    } else {
      // 输入框没声明提交动作（IME_ACTION_NONE / IME_ACTION_UNSPECIFIED）。原来这里静默返回，
      // 调用方又已经先把键盘收掉了，用户会以为"手势失效"。现在明确告知没有提交动作。
      BrailleCommonTalkBackSpeaker.getInstance()
        .speak(ime.getString(R.string.perform_action_unavailable))
    }
  }

  fun isCurrentTableTopMode(): Boolean {
    val layoutOptional: Optional<TouchDots> = ime.layoutOrientator!!.getDetectedLayout()
    val mode = BrailleUserPreferences.readLayoutMode(ime)
    return mode == TouchDots.TABLETOP ||
      (mode == TouchDots.AUTO_DETECT && layoutOptional.isPresent &&
        layoutOptional.get() == TouchDots.TABLETOP)
  }

  /**
   * Attempt to exit this IME and switch to another.
   *
   * <p>First, try switching to Gboard if it exists. Otherwise, switch to the next IME if one
   * exists.
   *
   * <p>If switching to the next IME fails (which can happen because there are no other IMEs
   * installed and enabled OR for an unknown reason (which DOES occur on some phones), show the
   * system IME picker if there is another IME installed and enabled.
   *
   * <p>Finally, if there are not other IMEs installed and enabled, launch IME Settings.
   */
  fun switchToNextInputMethod(): Boolean {
    if (BrailleIme.talkBackForBrailleIme != null) {
      BrailleIme.talkBackForBrailleIme!!.interruptSpeak()
    }
    if (isConnectionValid() && ime.editBuffer != null) {
      // Commit holdings here, otherwise InputConnect will become invalid after switch keyboard.
      ime.editBuffer!!.commit(getImeConnection())
    }
    if (!KeyboardUtils.areMultipleImesEnabled(ime)) {
      // Show a toast and bring up Ime settings to user.
      Toast.makeText(ime, ime.getString(R.string.bring_ime_settings_page), Toast.LENGTH_SHORT)
        .show()
      val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
      intent.addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or
          Intent.FLAG_ACTIVITY_CLEAR_TASK or
          Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
      ime.startActivity(intent)
      return false
    }
    var succeeded: Boolean
    // Default switch to gboard.
    val inputMethodInfoId = KeyboardUtils.getEnabledImeId(ime, Constants.GBOARD_PACKAGE_NAME)
    if (!TextUtils.isEmpty(inputMethodInfoId)) {
      // This api doesn't tell us switch succeed or not. Assume it switch successfully.
      ime.switchInputMethod(inputMethodInfoId)
      succeeded = true
    } else if (BuildVersionUtils.isAtLeastP()) {
      succeeded = ime.switchToNextInputMethod(false)
    } else {
      val token = ime.window!!.window!!.attributes.token
      val inputMethodManager =
        ime.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
      succeeded = inputMethodManager.switchToNextInputMethod(token, false)
    }
    // REFERTO: Switch to next keyboard manually by giving ime id.
    if (!succeeded) {
      // This api doesn't tell us switch succeed or not. Assume it switch successfully.
      ime.switchInputMethod(KeyboardUtils.getNextEnabledImeId(ime))
    }
    return true
  }

  private companion object {
    private const val TAG = "BrailleIme"
  }
}
