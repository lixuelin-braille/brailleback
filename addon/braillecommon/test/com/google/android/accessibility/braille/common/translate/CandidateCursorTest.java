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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** {@link CandidateCursor} 的单元测试。 */
@RunWith(JUnit4.class)
public class CandidateCursorTest {

  @Test
  public void indexFor_noRecord_returnsZeroAndRemembers() {
    CandidateCursor cursor = new CandidateCursor();

    assertEquals(0, cursor.indexFor(0, 5));
    // 写回之后 size 应该从 0 变 1：后续 shift 才有确定的起点。
    assertEquals(1, cursor.size());
    assertEquals(0, cursor.indexFor(0, 5));
  }

  @Test
  public void indexFor_countGrows_keepsUserChoice() {
    CandidateCursor cursor = new CandidateCursor();
    cursor.shift(0, 0, 3, 1);
    cursor.shift(0, 1, 3, 1);

    // 用户已经选到第 2 个，之后候选列表变长（比如换了一张表）不能把选择打回 0。
    assertEquals(2, cursor.indexFor(0, 10));
  }

  @Test
  public void indexFor_recordBeyondNewCount_fallsBackToZero() {
    CandidateCursor cursor = new CandidateCursor();
    cursor.shift(0, 0, 10, 1);
    cursor.shift(0, 1, 10, 1);
    cursor.shift(0, 2, 10, 1);
    assertEquals(3, cursor.indexFor(0, 10));

    // 候选列表缩短，旧记录越界，必须回到 0 而不是抛异常／输出空字。
    // 先测 index == count 这个边界：写成 > 就会漏掉，然后在取候选时抛越界。
    assertEquals(0, cursor.indexFor(0, 3));
    assertEquals(0, cursor.indexFor(0, 2));
  }

  @Test
  public void indexFor_zeroCount_returnsZeroWithoutRecording() {
    CandidateCursor cursor = new CandidateCursor();

    assertEquals(0, cursor.indexFor(4, 0));
    assertEquals(0, cursor.size());
  }

  @Test
  public void shift_wrapsForwardAtEnd() {
    CandidateCursor cursor = new CandidateCursor();

    assertEquals(1, cursor.shift(0, 0, 3, 1));
    assertEquals(2, cursor.shift(0, 1, 3, 1));
    // 最后一个再往后要绕回 0，而不是夹在 2 上。
    assertEquals(0, cursor.shift(0, 2, 3, 1));
  }

  @Test
  public void shift_wrapsBackwardAtStart() {
    CandidateCursor cursor = new CandidateCursor();

    // 第一个再往前要绕到最后一个。
    assertEquals(2, cursor.shift(0, 0, 3, -1));
  }

  @Test
  public void shift_singleCandidate_staysPut() {
    CandidateCursor cursor = new CandidateCursor();

    assertEquals(0, cursor.shift(0, 0, 1, 1));
    assertEquals(0, cursor.shift(0, 0, 1, -1));
    // 只有一个候选时不该写记录：写了等于把"没得选"变成"选过第 0 个"，无法区分。
    assertEquals(0, cursor.size());
  }

  @Test
  public void trimTo_dropsRecordsPastNewLength() {
    CandidateCursor cursor = new CandidateCursor();
    cursor.indexFor(0, 5);
    cursor.indexFor(3, 5);
    cursor.indexFor(6, 5);
    assertEquals(3, cursor.size());

    // holdings 被删短到 3 格：起始下标 3 和 6 的音节已不存在（合法起始只能是 0..2），
    // 记录必须丢弃，否则"删掉重打"会意外复用上一次的选择。
    // 这里用 key == holdingsSize 作边界，把 >= 写成 > 的实现会漏删。
    cursor.trimTo(3);
    assertEquals(1, cursor.size());

    // 保留下来的记录仍然有效。
    cursor.shift(0, 0, 5, 1);
    assertEquals(1, cursor.indexFor(0, 5));
    // 被丢弃的位置回到默认值（顺带重新写入一条记录）。
    assertEquals(0, cursor.indexFor(3, 5));
    assertEquals(2, cursor.size());
  }

  @Test
  public void trimTo_zero_clearsEverything() {
    CandidateCursor cursor = new CandidateCursor();
    cursor.indexFor(0, 5);
    cursor.indexFor(2, 5);

    cursor.trimTo(0);
    assertEquals(0, cursor.size());
  }
}
