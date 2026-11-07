/*
 * Copyright (C) 2014 Google Inc.
 * 移植说明：原版位于 TalkBack utils 模块，本文件为 BrailleBack 侧的等价实现。
 * 与原版的差异：LogUtils 换为 android.util.Log；AccessibilityNodeInfoUtils.toCompat
 * 与 WebInterfaceUtils.isWebContainer 内联，避免拖入整个 utils 模块。
 */

package com.google.android.accessibility.utils;

import static android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY;
import static android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT;

import android.accessibilityservice.AccessibilityService;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Functions to find focus. */
public class FocusFinder {

  private static final String TAG = "FocusFinder";
  private final AccessibilityService service;

  /** Screen focus types in accessibility. */
  @IntDef({FOCUS_INPUT, FOCUS_ACCESSIBILITY})
  @Retention(RetentionPolicy.SOURCE)
  public @interface FocusType {}

  public FocusFinder(AccessibilityService service) {
    this.service = service;
  }

  /** Returns the view that has accessibility-focus. */
  public @Nullable AccessibilityNodeInfoCompat findAccessibilityFocus() {
    return getAccessibilityFocusNode(service, false);
  }

  /** Finds the view that has the specified focus type. The type is defined in {@link FocusType}. */
  public @Nullable AccessibilityNodeInfoCompat findFocusCompat(@FocusType int focusType) {
    switch (focusType) {
      case FOCUS_ACCESSIBILITY -> {
        return getAccessibilityFocusNode(service, false);
      }
      case FOCUS_INPUT -> {
        return toCompat(service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT));
      }
      default -> {}
    }
    return null;
  }

  /**
   * Returns the accessibility focus by calling {@link AccessibilityService#findFocus(int)}. If no
   * focus is found, it allows to return the root node of the active window.
   *
   * @param fallbackOnRoot true for returning the root node if no focus is found.
   */
  public static @Nullable AccessibilityNodeInfoCompat getAccessibilityFocusNode(
      AccessibilityService service, boolean fallbackOnRoot) {
    AccessibilityNodeInfo ret = null;
    AccessibilityNodeInfo focused = service.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
    if (focused == null) {
      // 退化路径：从当前窗口根节点重新查找无障碍焦点。
      AccessibilityNodeInfo root = service.getRootInActiveWindow();
      if (root != null) {
        focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
      }
    }

    if (focused != null) {
      // WebView 里的焦点节点即使 isVisibleToUser 为 false 也要返回。
      if (focused.isVisibleToUser() || isWebContainer(toCompat(focused))) {
        ret = focused;
        focused = null;
      }
    }

    if (ret == null && fallbackOnRoot) {
      ret = service.getRootInActiveWindow();
      if (ret == null) {
        Log.e(TAG, "No current window root");
      }
    }

    if (ret != null) {
      // 使用 AccessibilityNodeProvider 时返回的节点可能已失效。
      if (!ret.refresh()) {
        return null;
      }
      return toCompat(ret);
    }

    return null;
  }

  /** 等价于 TalkBack 的 AccessibilityNodeInfoUtils.toCompat。 */
  public static @Nullable AccessibilityNodeInfoCompat toCompat(@Nullable AccessibilityNodeInfo info) {
    return (info == null) ? null : AccessibilityNodeInfoCompat.wrap(info);
  }

  /** 等价于 TalkBack 的 WebInterfaceUtils.isWebContainer 的简化判断。 */
  private static boolean isWebContainer(@Nullable AccessibilityNodeInfoCompat node) {
    if (node == null) {
      return false;
    }
    CharSequence className = node.getClassName();
    return className != null && className.toString().contains("WebView");
  }
}
