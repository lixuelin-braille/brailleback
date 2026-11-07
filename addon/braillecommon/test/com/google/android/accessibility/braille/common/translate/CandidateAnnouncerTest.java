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
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** {@link CandidateAnnouncer} 的单元测试。 */
@RunWith(JUnit4.class)
public class CandidateAnnouncerTest {

  private static final String SEP = "，";

  private static final List<String> MANY =
      ImmutableList.of("的", "得", "地", "德", "底", "第", "低", "滴");

  @Test
  public void window_startsAtChosen() {
    // 用户已经换到第 3 个（index 2）时，念出来的第一句必须是他当前听到的那个字，
    // 否则"第 3 个，地"和后面念的"的，得，地"对不上，他会以为选择丢了。
    assertEquals("地，德，底", CandidateAnnouncer.window(MANY, 2, 3, SEP));
  }

  @Test
  public void window_stopsAtEnd() {
    // 起点靠后时窗口必须被列表末尾截断，不能补空串也不能越界。
    assertEquals("第，低，滴", CandidateAnnouncer.window(MANY, 5, 5, SEP));
  }

  @Test
  public void window_clampsOutOfRangeChosen() {
    // 候选数会随切分变化缩短，记录里的旧序号可能越界。序号过大夹到最后一个候选
    // （而不是退回第一个——那会让用户以为选择被重置了），负数夹到第一个。
    assertEquals("滴", CandidateAnnouncer.window(MANY, 99, 2, SEP));
    assertEquals("的，得", CandidateAnnouncer.window(MANY, -3, 2, SEP));
  }

  @Test
  public void window_emptyInputs() {
    assertEquals("", CandidateAnnouncer.window(Collections.emptyList(), 0, 5, SEP));
    assertEquals("", CandidateAnnouncer.window(MANY, 0, 0, SEP));
    assertEquals("", CandidateAnnouncer.window(MANY, 0, -1, SEP));
  }

  @Test
  public void window_exactlyWindowSizeIsNotTruncated() {
    // 候选恰好等于窗口大小：已经念全，不该再说"共 N 个"。
    List<String> three = ImmutableList.of("的", "得", "地");
    assertEquals("的，得，地", CandidateAnnouncer.window(three, 0, 3, SEP));
    assertFalse(CandidateAnnouncer.hasMore(three, 0, 3));
  }

  @Test
  public void hasMore_reportsTruncation() {
    assertTrue(CandidateAnnouncer.hasMore(MANY, 0, 5));
    assertFalse(CandidateAnnouncer.hasMore(MANY, 3, 5));
    assertFalse(CandidateAnnouncer.hasMore(Collections.emptyList(), 0, 5));
  }
}
