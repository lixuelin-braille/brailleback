/*
 * Copyright (C) 2015 Google Inc.
 * 移植说明：原版依赖 utils.R 中的 granularity_* 字符串与 WebInterfaceUtils。
 * 盲文输入只用到 CHARACTER / WORD / LINE / PARAGRAPH 四个常量，
 * 因此这里保留全部枚举项与 id（保持数值语义一致），但把资源 id 置 0 并去掉 Web 提取逻辑。
 */

package com.google.android.accessibility.utils.input

import androidx.annotation.Nullable
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import java.util.ArrayList

/** 光标移动粒度。 */
enum class CursorGranularity(
  val id: Int,
  val value: Int,
) {
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

  /** 原版为字符串资源 id；适配层没有该资源，统一为 0。 */
  val resourceId: Int = 0

  /** 对应的 AccessibilityNodeInfoCompat 移动粒度；0 表示框架无对应值。 */

  fun isWebOnlyGranularity(): Boolean =
    id >= WEB_LIST.id &&
      id <= WEB_RADIO.id &&
      this != HEADING &&
      this != CONTROL &&
      this != LINK &&
      this != LIST

  fun supportsWebGranularity(): Boolean =
    isWebOnlyGranularity() || this == CHARACTER || this == WORD || this == LINE

  fun isNativeMacroGranularity(): Boolean =
    this == HEADING || this == CONTROL || this == LINK || this == LIST

  fun isMicroGranularity(): Boolean = value != NO_VALUE

  fun isTableNavigationGranularity(): Boolean = this == ROW || this == COLUMN || this == ROW_COLUMN

  companion object {
    private const val NO_VALUE = 0

    @JvmStatic
    @Nullable
    fun fromId(id: Int): CursorGranularity? {
      for (granularity in values()) {
        if (granularity.id == id) {
          return granularity
        }
      }
      return null
    }

    /** 把 AccessibilityNodeInfo 的移动粒度拆成粒度列表。 */
    @JvmStatic
    fun extractFromMask(
      inputMask: Int,
      output: MutableList<CursorGranularity>,
      isWebView: Boolean,
    ) {
      for (granularity in values()) {
        if (granularity.value != NO_VALUE &&
          (inputMask and (1 shl (granularity.value - 1))) != 0 &&
          (isWebView || !granularity.isWebOnlyGranularity())
        ) {
          output.add(granularity)
        }
      }
    }

    /** 便于调试输出。 */
    @JvmStatic
    fun all(): List<CursorGranularity> = ArrayList(values().toList())
  }
}
