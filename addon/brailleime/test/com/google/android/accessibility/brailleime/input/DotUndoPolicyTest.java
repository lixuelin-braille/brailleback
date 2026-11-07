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

package com.google.android.accessibility.brailleime.input;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * {@link DotUndoPolicy} 的单元测试。
 *
 * <p>每个用例锁一个条件。其中"右滑不能触发退点"最关键：方向判断写反会让上屏手势在攒点的那
 * 1.2 秒里变成退点，用户按右滑打不出字，而且只在时间窗内复现，几乎无法自查。
 */
@RunWith(JUnit4.class)
public class DotUndoPolicyTest {

  /** 常规场景：中文攒点中，单指左滑，手上有 2 个点。 */
  private static boolean undoTypical() {
    return DotUndoPolicy.shouldUndoDot(
        /* isSwipe= */ true,
        /* touchCount= */ 1,
        /* isLeft= */ true,
        /* accumulateEnabled= */ true,
        /* pendingDotCount= */ 2);
  }

  @Test
  public void typicalLeftSwipeWhileAccumulating_undoesTheDot() {
    assertTrue(undoTypical());
  }

  @Test
  public void rightSwipe_neverUndoesDot() {
    // 右滑是"上屏"，是打字最高频的手势之一，任何情况下都不能被退点吃掉。
    assertFalse(
        DotUndoPolicy.shouldUndoDot(true, 1, /* isLeft= */ false, true, 2));
  }

  @Test
  public void twoFingerLeftSwipe_isNotDotUndo() {
    // 双指左滑是删词，不能被当成退点——否则用户想删词，结果只退了一个点。
    assertFalse(
        DotUndoPolicy.shouldUndoDot(true, /* touchCount= */ 2, true, true, 2));
  }

  @Test
  public void leftSwipeWithNoPendingDot_deletesCell() {
    // 没有未结算的点：交给常规左滑删一格。不这样，"删整格"就永远做不到。
    assertFalse(
        DotUndoPolicy.shouldUndoDot(true, 1, true, true, /* pendingDotCount= */ 0));
  }

  @Test
  public void accumulateDisabled_neverUndoesDot() {
    // 英文一级盲文一格一字符，没有"攒点"概念，左滑必须保持原义（删字母）。
    assertFalse(
        DotUndoPolicy.shouldUndoDot(true, 1, true, /* accumulateEnabled= */ false, 2));
  }

  @Test
  public void tapIsNotSwipe() {
    // 点击、长按、标定都不是滑动，不该进入退点路径。
    assertFalse(
        DotUndoPolicy.shouldUndoDot(/* isSwipe= */ false, 1, true, true, 2));
  }

  @Test
  public void singlePendingDot_canStillBeUndone() {
    // 边界：只按了 1 个点就发现按错了，必须能退（去掉 < 1 这类边界错误）。
    assertTrue(DotUndoPolicy.shouldUndoDot(true, 1, true, true, /* pendingDotCount= */ 1));
  }
}
