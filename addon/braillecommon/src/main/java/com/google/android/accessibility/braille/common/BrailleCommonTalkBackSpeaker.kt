/*
 * Copyright (C) 2023 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.braille.common

import androidx.annotation.Nullable
import com.google.android.accessibility.braille.interfaces.TalkBackForBrailleCommon
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions

/** Speaker provides announce abilities based on TalkBack. */
class BrailleCommonTalkBackSpeaker : TalkBackSpeaker {

  /**
   * 没有读屏可借时的兜底播报。
   *
   * <p>读屏桥（{@link TalkBackForBrailleCommon}）只有在读屏无障碍服务里才会被注入。输入法单独
   * 运行时它是空的，原来这里直接 return，于是盲人打盲文听不到任何候选——这正是「不开无障碍就没
   * 语音」的根子。兜底实现由主模块注册（addon 不感知它用的是系统 TTS 还是别的），只在桥为空时
   * 生效，所以读屏在跑时不会重复播报。
   */
  interface FallbackSpeaker {
    fun speak(text: CharSequence?, delayMs: Int)
  }

  @Nullable private var talkBackForBrailleCommon: TalkBackForBrailleCommon? = null

  companion object {
    private const val TAG = "BrailleCommonTalkBackSpeaker"

    private var instance: BrailleCommonTalkBackSpeaker? = null

    @Nullable @Volatile private var fallbackSpeaker: FallbackSpeaker? = null

    /** 主模块在输入法创建时注册兜底播报；传入 null 表示撤销（例如输入法销毁）。 */
    @JvmStatic
    fun setFallbackSpeaker(@Nullable speaker: FallbackSpeaker?) {
      fallbackSpeaker = speaker
    }

    /** Get the static singleton instance, creating it if necessary. */
    @JvmStatic
    fun getInstance(): BrailleCommonTalkBackSpeaker {
      if (instance == null) {
        instance = BrailleCommonTalkBackSpeaker()
      }
      return instance!!
    }
  }

  fun initialize(talkBackForBrailleCommon: TalkBackForBrailleCommon) {
    this.talkBackForBrailleCommon = talkBackForBrailleCommon
  }

  override fun speak(text: CharSequence, delayMs: Int, speakOptions: SpeakOptions) {
    if (talkBackForBrailleCommon != null) {
      talkBackForBrailleCommon!!.speak(text, delayMs, speakOptions)
      return
    }
    val fallback = fallbackSpeaker
    if (fallback != null) {
      fallback.speak(text, delayMs)
      return
    }
    BrailleCommonLog.e(TAG, "Instance does not init correctly.")
  }
}
