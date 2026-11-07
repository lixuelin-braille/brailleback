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
   * 这一格在当前位置上，是否已经是一个完整的盲文符号（可以立刻交出去）。
   *
   * <p>给键盘层判断"停顿多久才把攒下的点结算成一格"用。一格一字符的语言（英文）按完就该结算，
   * 默认返回 {@code true}，行为与原来一致。
   *
   * <p>多格拼一字、且中间状态自己也可能合法时才需要覆写（中文现行盲文：{@code 1} 是声调、
   * {@code 12} 是声母，用户打 {@code 12} 的第一点时听到的是"声调"，此时若按 {@code 12} 的
   * 时限结算，这一格就被切断了）。
   */
  default boolean isCompleteCell(BrailleCharacter cell) {
    return true;
  }

  /** Moves the cursor in the text field. */
  boolean moveTextFieldCursor(ImeConnection imeConnection, int index);

  /** Gets the holdings info for braille display. */
  HoldingsInfo getHoldingsInfo(ImeConnection imeConnection);
}
