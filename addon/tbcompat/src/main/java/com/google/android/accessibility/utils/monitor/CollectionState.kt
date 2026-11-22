/*
 * 移植说明：TalkBack 的 CollectionState（987 行）负责跟踪集合/列表/表格结构，供朗读时
 * 播报"第 N 项，共 M 项"。盲文输入键盘不会朗读集合信息，
 * braille 子系统仅把它当作 TalkBackForBrailleDisplay 的返回类型，
 * 因此这里提供可编译的空实现外壳；宿主可自行填充。
 */

package com.google.android.accessibility.utils.monitor

import android.view.accessibility.AccessibilityEvent
import androidx.annotation.Nullable
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/** 集合状态（精简外壳）。 */
class CollectionState {
  private var rowCount: Int = UNDEFINED
  private var columnCount: Int = UNDEFINED

  /** 清空缓存状态。 */
  fun clear() {
    rowCount = UNDEFINED
    columnCount = UNDEFINED
  }

  /** 判断事件是否来自集合容器。 */
  fun isCollectionChangedEvent(@Nullable event: AccessibilityEvent?): Boolean {
    if (event == null) {
      return false
    }
    val type = event.eventType
    return type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
      type == AccessibilityEvent.TYPE_VIEW_SCROLLED
  }

  /** 更新集合状态（外壳实现不做结构推断）。 */
  fun updateCollectionInfo(@Nullable node: AccessibilityNodeInfoCompat?) {
    // no-op：盲文键盘不需要集合信息
  }

  fun getRowCount(): Int = rowCount

  fun getColumnCount(): Int = columnCount

  companion object {
    /** 行/列类型。 */
    const val UNDEFINED: Int = -1
  }
}
