/*
 * 移植说明：TalkBack 的 CollectionState（987 行）负责跟踪集合/列表/表格结构，供朗读时
 * 播报"第 N 项，共 M 项"。盲文输入键盘不会朗读集合信息，
 * braille 子系统仅把它当作 TalkBackForBrailleDisplay 的返回类型，
 * 因此这里提供可编译的空实现外壳；宿主可自行填充。
 */

package com.google.android.accessibility.utils.monitor;

import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;

/** 集合状态（精简外壳）。 */
public class CollectionState {

  /** 行/列类型。 */
  public static final int UNDEFINED = -1;

  private int rowCount = UNDEFINED;
  private int columnCount = UNDEFINED;

  /** 清空缓存状态。 */
  public void clear() {
    rowCount = UNDEFINED;
    columnCount = UNDEFINED;
  }

  /** 判断事件是否来自集合容器。 */
  public boolean isCollectionChangedEvent(@Nullable AccessibilityEvent event) {
    if (event == null) {
      return false;
    }
    int type = event.getEventType();
    return type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        || type == AccessibilityEvent.TYPE_VIEW_SCROLLED;
  }

  /** 更新集合状态（外壳实现不做结构推断）。 */
  public void updateCollectionInfo(@Nullable AccessibilityNodeInfoCompat node) {
    // no-op：盲文键盘不需要集合信息
  }

  public int getRowCount() {
    return rowCount;
  }

  public int getColumnCount() {
    return columnCount;
  }
}
