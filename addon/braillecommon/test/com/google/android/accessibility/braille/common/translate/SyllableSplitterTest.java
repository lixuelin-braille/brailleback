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
          // 单格标点：中文表里引号类是单格定义，必须能打出来。
          if (dots.equals("45")) {
            return "\u201C";
          }
          // 中文表里的 lowercase 定义：点 13 是 k，反译出来是全角字母。
          if (dots.equals("13")) {
            return "\uFF4B";
          }
          // 防御：万一某个表里真有单格汉字（当前中文表 0 条），它属于正常内容，不能挡。
          if (dots.equals("24")) {
            return "\u4E2D";
          }
          // 点 6 是 capsletter（大写符），反译产物是私用区内部字符。
          if (dots.equals("6")) {
            return "\uE253";
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

  @Test
  public void singleCellLetter_isHeldInsteadOfOutput() {
    // 点 13 在中文表里不是汉字，而是 lowercase 的 k。用户把一格的点分开按、某一格提前结算时
    // 就会落下这种单格；照原样输出的话输入框里会出现一个全角字母，读屏念"全角 k"，
    // 而他按的是一个汉字的头几个点——这是最难自查的一类错误。
    SyllableSplitter.Result result =
        SyllableSplitter.split(new BrailleWord("13"), index, STUB_TRANSLATOR, cursor, -1, true);

    assertEquals("", result.confirmedText);
    assertEquals(1, result.pendingCells);
  }

  @Test
  public void singleCellCapsLetter_isHeldInsteadOfOutput() {
    // 点 6 是 capsletter（大写符），反译产物是私用区内部字符。写进输入框会被读成"大写"或乱码，
    // 这正是用户报的"按到 6 点以后直接读大写"。
    SyllableSplitter.Result result =
        SyllableSplitter.split(new BrailleWord("6"), index, STUB_TRANSLATOR, cursor, -1, true);

    assertEquals("", result.confirmedText);
    assertEquals(1, result.pendingCells);
  }

  @Test
  public void singleCellWithNoTranslation_isHeld() {
    // 表里既没有汉字也没有符号定义的单格返回空译文。输出空串等于用户按了完全没反应，
    // 必须挂起让上层播报点位。
    SyllableSplitter.Result result =
        SyllableSplitter.split(new BrailleWord("36"), index, STUB_TRANSLATOR, cursor, -1, true);

    assertEquals("", result.confirmedText);
    assertEquals(1, result.pendingCells);
  }

  @Test
  public void singleCellPunctuation_isStillOutput() {
    // 但单格标点必须保留：中文表里引号、间隔号是单格定义，一律挡掉就打不出标点了。
    SyllableSplitter.Result result =
        SyllableSplitter.split(new BrailleWord("45"), index, STUB_TRANSLATOR, cursor, -1, true);

    assertEquals("\u201C", result.confirmedText);
    assertFalse(result.hasPending());
  }

  @Test
  public void singleCellHanzi_isOutput() {
    // 防御性用例：如果某个中文表确实定义了单格汉字，那是正常内容，不能被"挡字母"的规则误伤。
    SyllableSplitter.Result result =
        SyllableSplitter.split(new BrailleWord("24"), index, STUB_TRANSLATOR, cursor, -1, true);

    assertEquals("\u4E2D", result.confirmedText);
  }

  // ---------------------------------------------------------------------------------------------
  // 尾部状态诊断
  //
  // 实测："合法首格 + 任意第二格"的 1092 种组合里有 79.4% 在表内不存在，所以笼统的"盲文组合
  // 无效"是最高频的一句播报，却等于什么都没说。下面每个用例锁死一种结构，保证上层能给出
  // 不同的、可执行的提示。

  @Test
  public void emptyWord_tailStateIsNone() {
    SyllableSplitter.Result result =
        SyllableSplitter.split(new BrailleWord(), index, STUB_TRANSLATOR, cursor, -1, true);

    assertEquals(SyllableSplitter.TailState.NONE, result.tailState);
  }

  @Test
  public void completeSyllable_tailStateIsNone() {
    SyllableSplitter.Result result =
        SyllableSplitter.split(
            new BrailleWord("145-26"), index, STUB_TRANSLATOR, cursor, -1, true);

    assertEquals(SyllableSplitter.TailState.NONE, result.tailState);
  }

  @Test
  public void firstCellOnly_tailStateIsIncomplete() {
    // 145 能作音节首格但还凑不成音节：这是正常中间态，上层只播点位，不要报警。
    SyllableSplitter.Result result =
        SyllableSplitter.split(new BrailleWord("145"), index, STUB_TRANSLATOR, cursor, -1, true);

    assertEquals(SyllableSplitter.TailState.INCOMPLETE, result.tailState);
    assertFalse(result.invalidTail);
  }

  @Test
  public void stemWithoutTone_tailStateIsAwaitTone() {
    // 125-35 是 125-35-1（哈）的声母韵母部分：只差一个声调格。
    // 这不是错误，必须让上层说"还差声调"，否则用户不知道自己停在半路上。
    SyllableSplitter.Result result =
        SyllableSplitter.split(new BrailleWord("125-35"), index, STUB_TRANSLATOR, cursor, -1, true);

    assertEquals(SyllableSplitter.TailState.AWAIT_TONE, result.tailState);
    assertEquals("", result.confirmedText);
    assertEquals(2, result.pendingCells);
    assertFalse("只差声调不是错误，不该报无效", result.invalidTail);
  }

  @Test
  public void wrongTone_tailStateIsWrongTone() {
    // 125-35 是合法音节干，但表里只有 125-35-1，打 125-35-2 就是声调打错了。
    // 这跟"两格本来接不上"要分开：前者退一格就能救回来。
    SyllableSplitter.Result result =
        SyllableSplitter.split(
            new BrailleWord("125-35-2"), index, STUB_TRANSLATOR, cursor, -1, true);

    assertEquals(SyllableSplitter.TailState.WRONG_TONE, result.tailState);
    assertTrue(result.invalidTail);
  }

  @Test
  public void unconnectableCells_tailStateIsNoSuchSyllable() {
    // 145 是合法首格，但 145-3 既不是音节，也不是任何三格音节的前缀。
    SyllableSplitter.Result result =
        SyllableSplitter.split(new BrailleWord("145-3"), index, STUB_TRANSLATOR, cursor, -1, true);

    assertEquals(SyllableSplitter.TailState.NO_SUCH_SYLLABLE, result.tailState);
    assertTrue(result.invalidTail);
  }

  @Test
  public void cellThatCannotStart_tailStateIsInvalidStart() {
    // 点 6 既不能作音节首格，也不是可以单独输出的符号（表里它是 capsletter）。
    // 这种情况几乎总是"一格的几个点没同时按、被拆成了多格"。
    SyllableSplitter.Result result =
        SyllableSplitter.split(new BrailleWord("6"), index, STUB_TRANSLATOR, cursor, -1, true);

    assertEquals(SyllableSplitter.TailState.INVALID_START, result.tailState);
    assertTrue(result.invalidTail);
  }

  @Test
  public void twoCellSyllableFollowedByWrongCell_reportsTailStateNotNone() {
    // 先切出一个完整音节，尾部又落了一格不能打头的单点：状态必须描述"尾部"，
    // 不能因为前面成功就报 NONE——否则上层会以为一切正常，把那半截静默丢掉。
    SyllableSplitter.Result result =
        SyllableSplitter.split(
            new BrailleWord("145-26-3"), index, STUB_TRANSLATOR, cursor, -1, true);

    assertEquals(SyllableSplitter.TailState.INVALID_START, result.tailState);
    assertEquals(index.getCandidates("145-26").get(0), result.confirmedText);
  }

  private static ChineseBrailleTableIndex assertDoesNotThrowParse(BufferedReader reader) {
    try {
      return ChineseBrailleTableIndex.parse(reader);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }
}
