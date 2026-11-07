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

package com.google.android.accessibility.braille.common.translate;

import com.google.android.accessibility.braille.common.ImeConnection;
import com.google.android.accessibility.braille.interfaces.BrailleCharacter;
import com.google.android.accessibility.braille.interfaces.BrailleDisplayForBrailleIme.ResultForDisplay.HoldingsInfo;
import java.util.List;

/**
 * Receives and accumulates inputted {@link BrailleCharacter}, and commits the print translation of
 * that accumulation to the IME Editor appropriately.
 *
 * <p>Methods include append and delete mutators, which modify an active list of {@link
 * BrailleCharacter} contents. Some of these mutation invocations will result in a commission, which
 * means the translation of braille into print, and the transfer of that print to the IME Editor via
 * the InputConnection. The recognition of when to commit the contents is a function of the braille
 * language associated with this buffer, so an implementation will generally use a {@link
 * BrailleTranslator}, which converts braille into print in a language-specific way.
 *
 * <p>The invocation of any of the methods defined here will often involve signalling to the IME
 * Editor across the InputConnection (to effect the change of contents in that Editor).
 */
public interface EditBuffer {

  /**
   * Appends a {@link BrailleCharacter} to the end of the buffer, possibly mutating the IME Editor.
   */
  String appendBraille(ImeConnection imeConnection, BrailleCharacter brailleCharacter);

  /** Appends a space (' ') to the end of the buffer, possibly mutating the IME Editor. */
  void appendSpace(ImeConnection imeConnection);

  /** Appends a newline to the end of the buffer, possibly mutating the IME Editor. */
  void appendNewline(ImeConnection imeConnection);

  /**
   * Deletes a single character backward from the end of the buffer, possibly mutating the IME
   * Editor.
   */
  void deleteCharacterBackward(ImeConnection imeConnection);

  /** Deletes a single character from the end of the buffer, possibly mutating the IME Editor. */
  void deleteCharacterForward(ImeConnection imeConnection);

  /** Deletes a word from the end of the buffer, possibly mutating the IME Editor. */
  void deleteWord(ImeConnection imeConnection);

  /** Commits all {@link BrailleCharacter} in the buffer, possibly mutating the IME Editor. */
  void commit(ImeConnection imeConnection);

  /** Moves the cursor of the buffer forward. */
  boolean moveCursorForward(ImeConnection imeConnection);

  /** Moves the cursor of the buffer backward. */
  boolean moveCursorBackward(ImeConnection imeConnection);

  /** Moves the cursor of the holdings. */
  default boolean moveHoldingsCursor(ImeConnection imeConnection, int index) {
    return false;
  }

  /**
   * 把尾部音节的候选切到下一个／上一个。
   *
   * <p>只有"多格拼成一个字符、且同一个字符有多种写法"的语言才存在候选这回事（中文现行盲文：
   * 1343 个音节、平均 15.6 个同音字）。一格一字符的语言默认返回 {@code false}，调用方据此把
   * 这次手势按原义处理，所以不影响英文等现有输入。
   *
   * <p>只作用于<b>尾部</b>音节：盲文从尾部累积输入，前缀已在 composing 里固定，用户能改的只有
   * 刚打出来的那一段。
   *
   * @return {@code true} 表示这次手势已被"换候选"消费掉；{@code false} 表示当前没有可换的候选，
   *     调用方应继续原来的动作。
   */
  default boolean moveToNextCandidate(ImeConnection imeConnection) {
    return false;
  }

  /** 见 {@link #moveToNextCandidate}。 */
  default boolean moveToPreviousCandidate(ImeConnection imeConnection) {
    return false;
  }

  /**
   * 把一段<b>连续按下、还没分格</b>的点流，切成已经可以确定下来的音节。
   *
   * <p>为什么需要这一层：中文盲文一格是若干点的集合，点位之间存在包含关系（{@code 12} 是波，
   * 加 3 成勒、加 4 成佛），所以"这一格按完没有"在结构上无法判定，过去只能靠停顿猜——猜早了
   * 把一格切断、猜晚了把两格粘住，用户报的"打慢一点就拼不成"就是这个原因。改成按<b>音节</b>
   * 整串识别后，判据变成"这串点是不是一个不能再长的音节"，与手速完全无关。
   *
   * <p>返回的格会被调用方逐个 {@link #appendBraille} 进来，实现内部仍按原来的多格累积逻辑处理，
   * 不需要改成逐点接收。
   *
   * @param dotStream 尚未处理的点流，每个字符是一个点号，例如 {@code 1451362}
   * @param force {@code false} 表示用户还在打，只能切确实无疑的音节；{@code true} 表示用户已经
   *     示意这串点打完了（按空格、滑动上屏），此时按最长匹配直接切，因为"二声"永远是"四声"的
   *     前缀，不强制的话带二声的字永远出不来
   * @return {@code null} 表示还要等更多点才能判断（用户还没打完）；空列表表示这段点流已经
   *     不可能构成任何音节（按错了），调用方应退掉一个点重来；否则返回已确定音节的格序列
   */
  default List<BrailleCharacter> segmentDotStream(String dotStream, boolean force) {
    return null;
  }

  /** Moves the cursor in the text field. */
  boolean moveTextFieldCursor(ImeConnection imeConnection, int index);

  /** Gets the holdings info for braille display. */
  HoldingsInfo getHoldingsInfo(ImeConnection imeConnection);
}
