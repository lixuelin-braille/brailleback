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

package com.google.android.accessibility.braille.interfaces;

import android.view.View;
import com.google.android.accessibility.utils.output.FeedbackController;
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions;

/** Exposes some TalkBack behavior to BrailleCommon. */
public interface TalkBackForBrailleCommon {
  /** TalkBack provides the ability to speak an announcement. */
  void speak(CharSequence charSequence, int delayMs, SpeakOptions speakOptions);

  /** Gets TalkBack's feedback controller. */
  FeedbackController getFeedBackController();

  /**
   * 【移植改造】给宿主提供一个可挂靠的视图，用于把播报内容以无障碍公告（TYPE_ANNOUNCEMENT）的
   * 形式转交屏幕阅读器朗读。
   *
   * <p>原版 TalkBack 的 {@link #speak} 直接进自己的语音队列，天然和读屏同源；第三方宿主（如
   * BrailleBack）没有这条通道，只能自己起 TTS，于是会出现"读屏读一段、自带 TTS 读另一段"的
   * 双声问题。默认空实现，保持对既有实现（含林夕读屏）的二进制兼容。
   */
  default void setAnnouncementView(View view) {}
}
