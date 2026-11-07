/*
 * Copyright (C) 2015 Google Inc.
 * 移植说明：原版依赖 utils.R 中的 granularity_* 字符串与 WebInterfaceUtils。
 * 盲文输入只用到 CHARACTER / WORD / LINE / PARAGRAPH 四个常量，
 * 因此这里保留全部枚举项与 id（保持数值语义一致），但把资源 id 置 0 并去掉 Web 提取逻辑。
 */

package com.google.android.accessibility.utils.input;

import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 光标移动粒度。 */
public enum CursorGranularity {
  DEFAULT(1, 0),
  CHARACTER(2, AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER),
  WORD(3, AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD),
  LINE(4, AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_LINE),
  PARAGRAPH(5, AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_PARAGRAPH),
  WEB_LIST(8, 0),
  HEADING(10, 0),
  CONTROL(11, 0),
  LINK(12, 0),
  WEB_LANDMARK(13, 0),
  WEB_BUTTON(14, 0),
  WEB_CHECKBOX(15, 0),
  WEB_EDITFIELD(16, 0),
  WEB_FOCUSABLE(17, 0),
  WEB_H1(18, 0),
  WEB_H2(19, 0),
  WEB_H3(20, 0),
  WEB_H4(21, 0),
  WEB_H5(22, 0),
  WEB_H6(23, 0),
  WEB_GRAPHIC(24, 0),
  WEB_LISTITEM(25, 0),
  WEB_TABLE(26, 0),
  WEB_COMBOBOX(27, 0),
  WEB_VISITED_LINK(28, 0),
  WINDOWS(29, 0),
  CONTAINER(30, 0),
  SEARCH(31, 0),
  ROW_COLUMN(32, 0),
  ROW(33, 0),
  COLUMN(34, 0),
  WEB_UNVISITED_LINK(35, 0),
  WEB_RADIO(36, 0),
  LIST(37, 0),
  SENTENCE(38, 6);

  private static final int NO_VALUE = 0;

  /** 原版为字符串资源 id；适配层没有该资源，统一为 0。 */
  public final int resourceId = 0;

  public final int id;

  /** 对应的 AccessibilityNodeInfoCompat 移动粒度；0 表示框架无对应值。 */
  public final int value;

  CursorGranularity(int id, int value) {
    this.id = id;
    this.value = value;
  }

  public static @Nullable CursorGranularity fromId(int id) {
    for (CursorGranularity granularity : values()) {
      if (granularity.id == id) {
        return granularity;
      }
    }
    return null;
  }

  /** 把 AccessibilityNodeInfo 的移动粒度拆成粒度列表。 */
  public static void extractFromMask(
      int inputMask, List<CursorGranularity> output, boolean isWebView) {
    for (CursorGranularity granularity : values()) {
      if (granularity.value != NO_VALUE
          && (inputMask & (1 << (granularity.value - 1))) != 0
          && (isWebView || !granularity.isWebOnlyGranularity())) {
        output.add(granularity);
      }
    }
  }

  public boolean isWebOnlyGranularity() {
    return id >= WEB_LIST.id && id <= WEB_RADIO.id && this != HEADING && this != CONTROL
        && this != LINK && this != LIST;
  }

  public boolean supportsWebGranularity() {
    return isWebOnlyGranularity() || this == CHARACTER || this == WORD || this == LINE;
  }

  public boolean isNativeMacroGranularity() {
    return this == HEADING || this == CONTROL || this == LINK || this == LIST;
  }

  public boolean isMicroGranularity() {
    return value != NO_VALUE;
  }

  public boolean isTableNavigationGranularity() {
    return this == ROW || this == COLUMN || this == ROW_COLUMN;
  }

  /** 便于调试输出。 */
  public static List<CursorGranularity> all() {
    return new ArrayList<>(Arrays.asList(values()));
  }
}
