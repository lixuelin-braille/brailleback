/*
 * Copyright (C) 2014 Google Inc.
 * 移植说明：原版位于 TalkBack utils 模块，本文件为 BrailleBack 侧的等价实现。
 * 与原版的差异：LogUtils 换为 android.util.Log；AccessibilityNodeInfoUtils.toCompat
 * 与 WebInterfaceUtils.isWebContainer 内联，避免拖入整个 utils 模块。
 */

package com.google.android.accessibility.utils

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.FOCUS_ACCESSIBILITY
import android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT
import androidx.annotation.IntDef
import androidx.annotation.Nullable
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/** Functions to find focus. */
class FocusFinder(private val service: AccessibilityService) {

  /** Screen focus types in accessibility. */
  @IntDef(FOCUS_INPUT, FOCUS_ACCESSIBILITY)
  @Retention(AnnotationRetention.SOURCE)
  annotation class FocusType

  /** Returns the view that has accessibility-focus. */
  @Nullable
  fun findAccessibilityFocus(): AccessibilityNodeInfoCompat? {
    return getAccessibilityFocusNode(service, false)
  }

  /** Finds the view that has the specified focus type. The type is defined in [FocusType]. */
  @Nullable
  fun findFocusCompat(@FocusType focusType: Int): AccessibilityNodeInfoCompat? {
    when (focusType) {
      FOCUS_ACCESSIBILITY -> return getAccessibilityFocusNode(service, false)
      FOCUS_INPUT -> return toCompat(service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))
    }
    return null
  }

  companion object {
    private const val TAG = "FocusFinder"

    /**
     * Returns the accessibility focus by calling [AccessibilityService.findFocus(int)]. If no
     * focus is found, it allows to return the root node of the active window.
     *
     * @param fallbackOnRoot true for returning the root node if no focus is found.
     */
    @JvmStatic
    @Nullable
    fun getAccessibilityFocusNode(
      service: AccessibilityService,
      fallbackOnRoot: Boolean,
    ): AccessibilityNodeInfoCompat? {
      var ret: AccessibilityNodeInfo? = null
      var focused = service.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
      if (focused == null) {
        // 退化路径：从当前窗口根节点重新查找无障碍焦点。
        val root = service.rootInActiveWindow
        if (root != null) {
          focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        }
      }

      if (focused != null) {
        // WebView 里的焦点节点即使 isVisibleToUser 为 false 也要返回。
        if (focused.isVisibleToUser || isWebContainer(toCompat(focused))) {
          ret = focused
          focused = null
        }
      }

      if (ret == null && fallbackOnRoot) {
        ret = service.rootInActiveWindow
        if (ret == null) {
          Log.e(TAG, "No current window root")
        }
      }

      if (ret != null) {
        // 使用 AccessibilityNodeProvider 时返回的节点可能已失效。
        if (!ret.refresh()) {
          return null
        }
        return toCompat(ret)
      }

      return null
    }

    /** 等价于 TalkBack 的 AccessibilityNodeInfoUtils.toCompat。 */
    @JvmStatic
    @Nullable
    fun toCompat(@Nullable info: AccessibilityNodeInfo?): AccessibilityNodeInfoCompat? {
      return if (info == null) null else AccessibilityNodeInfoCompat.wrap(info)
    }

    /** 等价于 TalkBack 的 WebInterfaceUtils.isWebContainer 的简化判断。 */
    private fun isWebContainer(@Nullable node: AccessibilityNodeInfoCompat?): Boolean {
      if (node == null) {
        return false
      }
      val className = node.getClassName()
      return className != null && className.toString().contains("WebView")
    }
  }
}
