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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** {@link ChineseBrailleSignNames} 的单元测试。 */
@RunWith(JUnit4.class)
public class ChineseBrailleSignNamesTest {

  @Test
  public void describe_shengmuReadsChineseName() {
    // 用户原话："一二点是波"。这一条是需求本身，不许改。
    assertEquals("波", ChineseBrailleSignNames.describe("12"));
    assertEquals("坡", ChineseBrailleSignNames.describe("1234"));
    assertEquals("摸", ChineseBrailleSignNames.describe("134"));
    assertEquals("佛", ChineseBrailleSignNames.describe("124"));
    assertEquals("得", ChineseBrailleSignNames.describe("145"));
    assertEquals("特", ChineseBrailleSignNames.describe("2345"));
    assertEquals("讷", ChineseBrailleSignNames.describe("1345"));
    assertEquals("勒", ChineseBrailleSignNames.describe("123"));
    assertEquals("知", ChineseBrailleSignNames.describe("34"));
    assertEquals("吃", ChineseBrailleSignNames.describe("12345"));
    assertEquals("诗", ChineseBrailleSignNames.describe("156"));
    assertEquals("日", ChineseBrailleSignNames.describe("245"));
    assertEquals("资", ChineseBrailleSignNames.describe("1356"));
    assertEquals("雌", ChineseBrailleSignNames.describe("14"));
    assertEquals("思", ChineseBrailleSignNames.describe("234"));
  }

  @Test
  public void describe_homophoneShengmuReadsBoth() {
    // g/j、k/q、h/x 在盲文里同点位（互补分布），只听一个会让人以为打错了。
    assertTrue(ChineseBrailleSignNames.describe("1245").contains("哥"));
    assertTrue(ChineseBrailleSignNames.describe("1245").contains("基"));
    assertTrue(ChineseBrailleSignNames.describe("13").contains("科"));
    assertTrue(ChineseBrailleSignNames.describe("13").contains("欺"));
    assertTrue(ChineseBrailleSignNames.describe("125").contains("喝"));
    assertTrue(ChineseBrailleSignNames.describe("125").contains("希"));
  }

  @Test
  public void describe_yunmuReadsChineseName() {
    assertEquals("啊", ChineseBrailleSignNames.describe("35"));
    assertEquals("衣", ChineseBrailleSignNames.describe("24"));
    assertEquals("乌", ChineseBrailleSignNames.describe("136"));
    assertEquals("迂", ChineseBrailleSignNames.describe("346"));
    assertEquals("哀", ChineseBrailleSignNames.describe("246"));
    assertEquals("安", ChineseBrailleSignNames.describe("1236"));
    assertEquals("恩", ChineseBrailleSignNames.describe("356"));
    assertEquals("昂", ChineseBrailleSignNames.describe("236"));
    assertEquals("儿", ChineseBrailleSignNames.describe("1235"));
    assertEquals("腰", ChineseBrailleSignNames.describe("345"));
    assertEquals("央", ChineseBrailleSignNames.describe("1346"));
    assertEquals("英", ChineseBrailleSignNames.describe("16"));
    assertEquals("蛙", ChineseBrailleSignNames.describe("123456"));
    assertEquals("窝", ChineseBrailleSignNames.describe("135"));
    assertEquals("弯", ChineseBrailleSignNames.describe("12456"));
    assertEquals("温", ChineseBrailleSignNames.describe("25"));
    assertEquals("汪", ChineseBrailleSignNames.describe("2356"));
    assertEquals("约", ChineseBrailleSignNames.describe("23456"));
    assertEquals("冤", ChineseBrailleSignNames.describe("12346"));
    assertEquals("晕", ChineseBrailleSignNames.describe("456"));
  }

  @Test
  public void describe_oAndEReadsBoth() {
    // o / e 同点位（b p m f 后接 o，d t n l g k h 后接 e）。
    String name = ChineseBrailleSignNames.describe("26");
    assertTrue(name.contains("喔"));
    assertTrue(name.contains("鹅"));
  }

  @Test
  public void describe_toneReadsOrdinal() {
    assertEquals("一声", ChineseBrailleSignNames.describe("1"));
    assertEquals("二声", ChineseBrailleSignNames.describe("2"));
    assertEquals("三声", ChineseBrailleSignNames.describe("3"));
    assertEquals("四声", ChineseBrailleSignNames.describe("23"));
    assertEquals("轻声", ChineseBrailleSignNames.describe("0"));
  }

  @Test
  public void describe_unknownCellReturnsNull() {
    // 认不出来就必须返回 null，让调用方回落到点位号，而不是编一个名字。
    assertNull(ChineseBrailleSignNames.describe("12345678"));
    assertNull(ChineseBrailleSignNames.describe("7"));
    assertNull(ChineseBrailleSignNames.describe(""));
    assertNull(ChineseBrailleSignNames.describe(null));
  }

  @Test
  public void tables_doNotOverlap() {
    // 三张表一旦有交集，describe() 的结果就取决于 putAll 的顺序，属于隐性 bug。
    Set<String> shengmu = ChineseBrailleSignNames.shengmuCells();
    Set<String> yunmu = ChineseBrailleSignNames.yunmuCells();
    Set<String> tone = ChineseBrailleSignNames.toneCells();
    for (String cell : shengmu) {
      assertFalse("声母与韵母重码: " + cell, yunmu.contains(cell));
      assertFalse("声母与声调重码: " + cell, tone.contains(cell));
    }
    for (String cell : yunmu) {
      assertFalse("韵母与声调重码: " + cell, tone.contains(cell));
    }
  }

  @Test
  public void tables_size() {
    assertEquals(18, ChineseBrailleSignNames.shengmuCells().size());
    assertEquals(34, ChineseBrailleSignNames.yunmuCells().size());
    assertEquals(5, ChineseBrailleSignNames.toneCells().size());
    assertEquals(57, ChineseBrailleSignNames.allCells().size());
  }

  @Test
  public void classification_matchesDescribable() {
    // isXxx 与 describe 必须同时成立，否则调用方会拿到"是声母但没名字"的格。
    for (String cell : ChineseBrailleSignNames.allCells()) {
      assertTrue(cell, ChineseBrailleSignNames.describe(cell) != null);
      assertEquals(
          cell,
          1,
          (ChineseBrailleSignNames.isShengmu(cell) ? 1 : 0)
              + (ChineseBrailleSignNames.isYunmu(cell) ? 1 : 0)
              + (ChineseBrailleSignNames.isTone(cell) ? 1 : 0));
    }
  }

  @Test
  public void noSingleDotIsShengmuOrYunmu() {
    // 中文盲文里单点只有声调一种身份；单点被当成声母/韵母说明表里混进了英文盲文。
    for (String cell : new String[] {"1", "2", "3", "4", "5", "6"}) {
      assertFalse(cell, ChineseBrailleSignNames.isShengmu(cell));
      assertFalse(cell, ChineseBrailleSignNames.isYunmu(cell));
    }
  }

  @Test
  public void isCompleteAt_firstCell_acceptsShengmuAndYunmu() {
    assertTrue(ChineseBrailleSignNames.isCompleteAt("12", 0)); // 波（声母）
    assertTrue(ChineseBrailleSignNames.isCompleteAt("35", 0)); // 啊（零声母韵母）
  }

  @Test
  public void isCompleteAt_firstCell_rejectsSingleDot() {
    // 这就是用户报"打一个字时间太长就拼不成"的根因：打 12（波）先按点 1，
    // 点 1 单独是声调，若判成完整就会被立刻结算，点 2 变成下一格。
    for (String dot : new String[] {"1", "2", "3"}) {
      assertFalse(dot, ChineseBrailleSignNames.isCompleteAt(dot, 0));
    }
  }

  @Test
  public void isCompleteAt_secondCell_acceptsYunmuOnly() {
    assertTrue(ChineseBrailleSignNames.isCompleteAt("35", 1));
    assertTrue(ChineseBrailleSignNames.isCompleteAt("24", 1));
    // 第 2 格故意不接受声调：2 格字只占 19%，让它们多等，换来 81% 的三格字打韵母时不被切断。
    assertFalse(ChineseBrailleSignNames.isCompleteAt("1", 1));
    assertFalse(ChineseBrailleSignNames.isCompleteAt("23", 1));
    // 声母不能出现在第 2 格。
    assertFalse(ChineseBrailleSignNames.isCompleteAt("12", 1));
  }

  @Test
  public void isCompleteAt_thirdCell_acceptsToneOnly() {
    assertTrue(ChineseBrailleSignNames.isCompleteAt("1", 2));
    assertTrue(ChineseBrailleSignNames.isCompleteAt("23", 2));
    assertFalse(ChineseBrailleSignNames.isCompleteAt("12", 2));
    assertFalse(ChineseBrailleSignNames.isCompleteAt("35", 2));
  }

  @Test
  public void isCompleteAt_unknownCellIsComplete() {
    // 标点、表外组合没有"打一半"的中间态，一律按完整处理，走短时限。
    assertTrue(ChineseBrailleSignNames.isCompleteAt("36", 0)); // 标点
    assertTrue(ChineseBrailleSignNames.isCompleteAt("4567", 0)); // 含点 7，表外
    assertTrue(ChineseBrailleSignNames.isCompleteAt("12352", 1)); // 乱按
    assertFalse(ChineseBrailleSignNames.isCompleteAt("", 0));
    assertFalse(ChineseBrailleSignNames.isCompleteAt(null, 0));
  }
}
