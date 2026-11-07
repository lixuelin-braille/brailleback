/*
 * Copyright 2026 The BrailleBack Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.braille.common.translate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.android.accessibility.braille.interfaces.BrailleWord;
import com.google.android.accessibility.braille.translate.BrailleTranslator;
import com.google.android.accessibility.braille.translate.TranslationResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * {@link SyllableSplitter} 的单元测试。
 *
 * <p>重点覆盖两阶段模型里最容易出错、且用户会立刻察觉的三件事：
 *
 * <ul>
 *   <li><b>吞字</b>：待确认的字没进 composing，确认时又没并进去，按空格就直接丢了
 *   <li><b>前缀被改写</b>：追加新格后前面已定的字变了
 *   <li><b>选择丢失</b>：用户换过候选，重算一遍又跳回字频最高的那个
 * </ul>
 */
@RunWith(JUnit4.class)
public class SyllableSplitterTest {

  /** 最小中文表：三个同音字（的/得/地）、一个两候选音节（是/事）、一个三格音节（哈）。 */
  private static final String TABLE =
      "sign \\x7684 145-26\n"
          + "sign \\x5F97 145-26\n"
          + "sign \\x5730 145-26\n"
          + "sign \\x662F 156-23\n"
          + "sign \\x4E8B 156-23\n"
          + "sign \\x54C8 125-35-1\n";

  /** 唯一候选音节，用来验证"没有第二种选择时直接落定"。 */
  private static final String SINGLE_TABLE = "sign \\x54C8 125-35-1\n";

  /** 索引里和翻译器都不产出字符时会怎样，所以只要翻译器能区分数字/标点就够了。 */
  private static final BrailleTranslator STUB_TRANSLATOR =
      new BrailleTranslator() {
        @Override
        public String translateToPrint(BrailleWord brailleWord) {
          String dots = brailleWord.toString();
          if (dots.equals("256")) {
            return "。";
          }
          // 模拟中文表里无条件数字定义：单格会被反译成数字。
          if (dots.matches("[1-6]")) {
            return "9";
          }
          return "";
        }

        @Override
        public String translateToPrintPartial(BrailleWord brailleWord) {
          return translateToPrint(brailleWord);
        }

        @Override
        public TranslationResult translate(CharSequence text, int cursorPosition) {
          throw new UnsupportedOperationException();
        }
      };

  private ChineseBrailleTableIndex index;
  private CandidateCursor cursor;

  @Before
  public void setUp() throws IOException {
    index = ChineseBrailleTableIndex.parse(new BufferedReader(new StringReader(TABLE)));
    cursor = new CandidateCursor();
  }

  @Test
  public void emptyWord_returnsEmptyResult() {
    SyllableSplitter.Result result =
        SyllableSplitter.split(new BrailleWord(), index, STUB_TRANSLATOR, cursor, -1, true);

    assertEquals("", result.confirmedText);
    assertFalse(result.hasPending());
    assertEquals(0, result.pendingCells);
    assertFalse(result.invalidTail);
  }

  @Test
  public void unconfirmedSyllable_staysOutOfComposing() {
    SyllableSplitter.Result result =
        SyllableSplitter.split(
            new BrailleWord("145-26"), index, STUB_TRANSLATOR, cursor, -1, true);

    // 关键：字不进 composing。盲人看不见下划线，字提前出现就分不清"还没上屏"和"已上屏"。
    assertEquals("", result.confirmedText);
    assertTrue(result.hasPending());
    assertEquals(0, result.pendingStart);
    assertEquals(3, result.pendingCandidates.size());
    assertNotNull(result.pendingCandidates.get(result.pendingChoice));
  }

  @Test
  public void confirmedSyllable_entersComposing() {
    SyllableSplitter.Result result =
        SyllableSplitter.split(
            new BrailleWord("145-26"), index, STUB_TRANSLATOR, cursor, /* lastConfirmedStart= */ 0,
            true);

    assertEquals(index.getCandidates("145-26").get(0), result.confirmedText);
    assertFalse(result.hasPending());
  }

  @Test
  public void secondSyllable_autoConfirmsTheFirstOne() {
    SyllableSplitter.Result result =
        SyllableSplitter.split(
            new BrailleWord("145-26-156-23"), index, STUB_TRANSLATOR, cursor, -1, true);

    // 打完第二个音节，第一个不再"最后一个"，自动确认进 composing；第二个等用户选。
    assertEquals(index.getCandidates("145-26").get(0), result.confirmedText);
    assertTrue(result.hasPending());
    assertEquals(2, result.pendingStart);
    assertEquals(2, result.pendingCandidates.size());
  }

  @Test
  public void uniqueCandidate_settlesWithoutWaitingForConfirmation() {
    ChineseBrailleTableIndex single =
        assertDoesNotThrowParse(new BufferedReader(new StringReader(SINGLE_TABLE)));

    SyllableSplitter.Result result =
        SyllableSplitter.split(new BrailleWord("125-35-1"), single, STUB_TRANSLATOR, cursor, -1, true);

    // 只有一个候选就没有选择可做，直接落定，省掉一次没有意义的"上屏"。
    assertEquals("哈", result.confirmedText);
    assertFalse(result.hasPending());
  }

  @Test
  public void allowPendingFalse_settlesImmediately() {
    SyllableSplitter.Result result =
        SyllableSplitter.split(
            new BrailleWord("145-26"), index, STUB_TRANSLATOR, cursor, -1, /* allowPending= */ false);

    // 密码框/非文本框没有 composing 可用，挂起等于吞字，必须直接落定。
    assertEquals(index.getCandidates("145-26").get(0), result.confirmedText);
    assertFalse(result.hasPending());
  }

  @Test
  public void halfSyllable_reportsPendingCellsAndProducesNothing() {
    SyllableSplitter.Result result =
        SyllableSplitter.split(new BrailleWord("145"), index, STUB_TRANSLATOR, cursor, -1, true);

    // 145 是音节首格但不是完整音节：挂起等后续格，且必须让上层知道有半截会被丢掉。
    assertEquals("", result.confirmedText);
    assertFalse(result.hasPending());
    assertEquals(1, result.pendingCells);
  }

  @Test
  public void shiftedChoice_survivesResplit() {
    BrailleWord word = new BrailleWord("145-26");
    List<String> candidates = index.getCandidates("145-26");

    SyllableSplitter.Result first =
        SyllableSplitter.split(word, index, STUB_TRANSLATOR, cursor, -1, true);
    assertEquals(candidates.get(0), first.pendingCandidates.get(first.pendingChoice));

    // 用户左滑一次。
    int next =
        cursor.shift(first.pendingStart, first.pendingChoice, first.pendingCandidates.size(), 1);
    assertTrue("三个候选应该真的换了一个字", next != first.pendingChoice);

    // 重算（追加下一格、删字都会触发）之后选择必须还在，否则用户刚换的字会当场跳回去。
    SyllableSplitter.Result again =
        SyllableSplitter.split(word, index, STUB_TRANSLATOR, cursor, -1, true);
    assertEquals(next, again.pendingChoice);
    assertEquals(candidates.get(next), again.pendingCandidates.get(again.pendingChoice));

    // 确认之后，落进 composing 的是用户选的那个，不是字频最高的那个。
    SyllableSplitter.Result confirmed =
        SyllableSplitter.split(
            word, index, STUB_TRANSLATOR, cursor, again.pendingStart, true);
    assertEquals(candidates.get(next), confirmed.confirmedText);
  }

  @Test
  public void numberSign_switchesToDigitMode() {
    BrailleWord word = new BrailleWord("3456-1");

    SyllableSplitter.Result result =
        SyllableSplitter.split(word, index, STUB_TRANSLATOR, cursor, -1, true);

    // 3456 只切状态不产字符，后面的单格翻成数字。
    assertEquals("9", result.confirmedText);
    assertFalse(result.hasPending());
  }

  @Test
  public void punctuation_producesItsOwnCharacter() {
    SyllableSplitter.Result result =
        SyllableSplitter.split(new BrailleWord("256"), index, STUB_TRANSLATOR, cursor, -1, true);

    assertEquals("。", result.confirmedText);
    assertFalse(result.hasPending());
  }

  @Test
  public void syllableThenHalfCell_keepsSyllableConfirmed() {
    // 打完一个完整音节，又开始打下一个音节的第一格。
    SyllableSplitter.Result result =
        SyllableSplitter.split(
            new BrailleWord("145-26-156"), index, STUB_TRANSLATOR, cursor, -1, true);

    // 前一个字要落定（不然它会随下一个音节一起消失），尾部半截要如实报告。
    assertEquals(index.getCandidates("145-26").get(0), result.confirmedText);
    assertFalse(result.hasPending());
    assertEquals(1, result.pendingCells);
  }

  private static ChineseBrailleTableIndex assertDoesNotThrowParse(BufferedReader reader) {
    try {
      return ChineseBrailleTableIndex.parse(reader);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }
}
