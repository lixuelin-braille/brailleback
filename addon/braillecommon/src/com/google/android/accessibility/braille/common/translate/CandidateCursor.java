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

import java.util.HashMap;
import java.util.Map;

/**
 * 尾部音节的候选游标：记住用户在哪个候选上，并能前后循环移动。
 *
 * <p>为什么不直接把序号存在 {@link EditBufferChinese} 的字段里：每落一格都会把整串 holdings
 * 重新切分一遍，字段只在"本次切分的最后"有效，一旦用户继续输入就会丢。改按"音节起始格下标"
 * 记在 map 里，追加输入时前缀的起始下标不变，用户的选择才能跨重算保留下来。
 *
 * <p>删除、提交让 holdings 变短后必须 {@link #trimTo}，否则"删掉重打"会意外复用上一次的选择
 * ——用户看不见输入框，只会听到一个字跳成了他刚才选过的另一个字。
 *
 * <p>刻意不依赖 Android：候选逻辑全是纯粹的算术和状态，抽出来才能在 JVM 单测里直接覆盖。
 */
final class CandidateCursor {

  /** 音节起始格下标 → 用户给这个音节选定的候选序号。 */
  private final Map<Integer, Integer> choices = new HashMap<>();

  /**
   * 取 {@code start} 位置音节的选中序号。
   *
   * <p>没有记录（用户还没换过）或记录越界（候选列表比上次短）时归 0，也就是字频最高的那个，
   * 并写回 map，保证后续 {@link #shift} 从一个确定的位置起算。
   */
  int indexFor(int start, int count) {
    if (count <= 0) {
      return 0;
    }
    int index = choices.getOrDefault(start, 0);
    if (index < 0 || index >= count) {
      index = 0;
    }
    choices.put(start, index);
    return index;
  }

  /**
   * 从 {@code current} 前后移动一格并写回，返回新序号。
   *
   * <p>循环而不是夹紧：候选有几十个时夹紧会让用户滑到最后一个就再也回不去（只能反向滑），
   * 循环则一直同方向滑总能绕回来。候选只有一个时原样返回，此时调用方本来也不该进来。
   */
  int shift(int start, int current, int count, int delta) {
    if (count <= 1) {
      return current;
    }
    int next = Math.floorMod(current + delta, count);
    choices.put(start, next);
    return next;
  }

  /** 丢弃起始下标已不存在的记录（holdings 被删短或清空后调用）。 */
  void trimTo(int holdingsSize) {
    choices.keySet().removeIf(start -> start >= holdingsSize);
  }

  /** 当前记录的条目数，仅供测试与诊断。 */
  int size() {
    return choices.size();
  }
}
