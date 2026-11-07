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

import java.util.List;

/**
 * 把候选列表拼成读屏能念的文本。
 *
 * <p>为什么需要单独一段逻辑：中文盲文一个音节平均 15.6 个同音字，"共 85 个候选"这句对盲人
 * 毫无信息量——他看不见候选栏，不知道自己能选什么，也就永远不敢换字。真正有用的是<b>把候选
 * 本身念出来</b>，和输入法的候选栏行为一致：一次念一小段，用滑动在这一段里移动。
 *
 * <p>不依赖 Android（Context/Resources），因为它决定的是"念什么"，最容易出边界错误
 * （窗口越界、空列表、选择的序号已经超出缩短后的候选数），必须在 JVM 单测里直接验证。
 */
final class CandidateAnnouncer {

  /**
   * 一次最多念几个候选。
   *
   * <p>5 是听觉短时记忆能记住的上限附近：再多用户听完就忘了第一个是什么，而盲人要的是
   * "听一遍就知道大概有哪些、该怎么滑"，不是把 85 个字全念完（那要念一分多钟）。
   */
  static final int WINDOW = 5;

  private CandidateAnnouncer() {}

  /**
   * 取出从 {@code chosen} 开始的一段候选，拼成 {@code 你，尼，呢} 这样的文本。
   *
   * <p>起点跟着当前选择走，而不是固定从第一个开始：用户已经换到第 8 个时再念"你，尼，呢"，
   * 和他听到的"第 8 个"对不上，会以为选择丢了。
   *
   * @param candidates 已排序的候选，不能为 null
   * @param chosen 当前选中的序号，越界时夹到合法范围
   * @param window 最多取几个，{@code <= 0} 时返回空串
   * @param separator 分隔符（中文用全角逗号，英文用逗号加空格）
   */
  static String window(List<String> candidates, int chosen, int window, String separator) {
    if (candidates == null || candidates.isEmpty() || window <= 0) {
      return "";
    }
    final int size = candidates.size();
    final int start = Math.max(0, Math.min(chosen, size - 1));
    final int end = Math.min(size, start + window);
    StringBuilder text = new StringBuilder();
    for (int i = start; i < end; i++) {
      if (i > start) {
        text.append(separator);
      }
      text.append(candidates.get(i));
    }
    return text.toString();
  }

  /**
   * 这一段之后还有没有没念到的候选。
   *
   * <p>用来决定要不要补一句"共 N 个"：候选全都在这一段里时补总数是废话，用户听完就知道
   * 到头了；只有被截断时才必须说，否则他会以为候选就这么几个。
   */
  static boolean hasMore(List<String> candidates, int chosen, int window) {
    if (candidates == null || candidates.isEmpty() || window <= 0) {
      return false;
    }
    final int start = Math.max(0, Math.min(chosen, candidates.size() - 1));
    return candidates.size() > start + window;
  }
}
