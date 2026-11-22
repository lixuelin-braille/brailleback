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

import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.translate.BrailleLanguages
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.brailleime.analytics.BrailleImeAnalytics

/**
 * 设置变更联动：监听输入码与缩略模式偏好变化，先落定已有缓冲，再按新偏好重建编辑缓冲、刷新键盘
 * 点数与点显器回显。
 */
class BrailleImeSettingsObserver(private val ime: BrailleIme) {

  fun createEditBuffer() {
    val code = BrailleUserPreferences.readCurrentActiveInputCodeAndCorrect(ime)
    val contractedMode =
      BrailleUserPreferences.readContractedMode(ime) && code.isSupportsContracted(ime)
    BrailleImeLog.d(
      TAG, "Code: " + code.getUserFacingName(ime) + " contracted: " + contractedMode)

    val translatorFactory = BrailleUserPreferences.readTranslatorFactory(ime)
    ime.editBuffer =
      BrailleLanguages.createEditBuffer(
        ime,
        BrailleCommonTalkBackSpeaker.getInstance(),
        code,
        translatorFactory,
        contractedMode)
    // 诊断：确认当前使用的盲文码、是否缩略、以及编辑缓冲类型（tag=BBTranslate）。
    android.util.Log.d(
      "BBTranslate",
      "createEditBuffer code=" +
        code.name +
        " contracted=" +
        contractedMode +
        " factory=" +
        translatorFactory.javaClass.simpleName +
        " buffer=" +
        ime.editBuffer!!.javaClass.simpleName)
  }

  fun refreshEditBufferAndBrailleDisplay() {
    if (ime.editBuffer != null) {
      ime.editBuffer!!.commit(ime.getImeConnection())
    }
    createEditBuffer()
    ime.window!!.setTitle(Utils.getBrailleKeyboardDisplayName(ime))
    ime.showOnBrailleDisplay(/* retranslate= */ true)
  }

  val onSharedPreferenceChangeListener: OnSharedPreferenceChangeListener =
    OnSharedPreferenceChangeListener { _, key ->
      if (key == ime.getString(R.string.pref_brailleime_translator_code)) {
        val newCode =
          BrailleUserPreferences.readCurrentActiveInputCodeAndCorrect(ime)
        if (!ime.brailleDisplayConnectedAndNotSuspended) {
          BrailleCommonTalkBackSpeaker.getInstance()
            .speak(
              ime.getString(
                R.string.switch_to_language_announcement,
                newCode.getUserFacingName(ime)),
              TalkBackSpeaker.AnnounceType.INTERRUPT)
        }
        if (ime.keyboardView!!.getBrailleInputViewDotCount() !=
          BrailleUserPreferences.getCurrentTypingLanguageType(ime).getDotCount()
        ) {
          ime.keyboardView!!.refreshInputView()
        }
        refreshEditBufferAndBrailleDisplay()
      } else if (key == ime.getString(R.string.pref_braille_contracted_mode)) {
        val contractedMode = BrailleUserPreferences.readContractedMode(ime)
        if (BrailleUserPreferences.readCurrentActiveInputCodeAndCorrect(ime)
          .isSupportsContracted(ime)
        ) {
          BrailleImeAnalytics.getInstance(ime).logContractedToggle(contractedMode)
        }
        if (!ime.brailleDisplayConnectedAndNotSuspended) {
          BrailleCommonTalkBackSpeaker.getInstance()
            .speak(
              ime.getString(
                if (contractedMode) R.string.switched_to_contracted_announcement
                else R.string.switched_to_uncontracted_announcement),
              TalkBackSpeaker.AnnounceType.INTERRUPT)
        }
        refreshEditBufferAndBrailleDisplay()
      }
    }

  private companion object {
    private const val TAG = "BrailleIme"
  }
}
