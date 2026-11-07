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

/**
 * 判定一次手势是"退掉刚按的那个点"，还是交给常规左滑处理（删一格）。
 *
 * <p>中文盲文一格要按 3 个点，按错最后一个点时只能整格重打，代价大且容易再错一次。所以让
 * 左滑始终表示同一个意思——<b>撤销最后一次输入动作</b>：还有没结算的点就退点，没有点才退格。
 *
 * <p>抽成不依赖 Android 的纯判定类，是因为这里<b>方向判断写反的后果很严重</b>：把"左滑"写成
 * "右滑"会让上屏手势在攒点期间变成退点，用户按右滑打不出字，而且只在那 1.2 秒的时间窗里复现，
 * 极难自查。这类逻辑必须在 JVM 单测里锁死。
 */
final class DotUndoPolicy {

  private DotUndoPolicy() {}

  /**
   * @param isSwipe 这次结果是不是滑动手势（点击、长按、标定都不算）
   * @param touchCount 手指数量；只有单指才可能是"退点"，双指左滑是删词
   * @param isLeft 滑动方向是否为左
   * @param accumulateEnabled 当前码表是否启用逐点累积（只有多格拼一字的语言才有攒点的概念）
   * @param pendingDotCount 当前格已经攒下的点数
   */
  static boolean shouldUndoDot(
      boolean isSwipe,
      int touchCount,
      boolean isLeft,
      boolean accumulateEnabled,
      int pendingDotCount) {
    return isSwipe && touchCount == 1 && isLeft && accumulateEnabled && pendingDotCount > 0;
  }
}
