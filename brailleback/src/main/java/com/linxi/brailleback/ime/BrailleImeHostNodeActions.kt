/*
 * 节点导航 / 选择 / 剪贴板动作：从 BrailleImeHostBridge 拆出的节点动作闭环。
 *
 * 全部走 AccessibilityNodeInfo 标准动作，不依赖 TalkBack 导航子系统；聚焦文本框查找、
 * 点击/长按/滚动、选区与剪贴板动作原样保留。阅读粒度枚举与粒度状态仍留在主桥接。
 */
package com.linxi.brailleback.ime

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.FocusFinder
import com.linxi.brailleback.BrailleBackService

internal class BrailleImeHostNodeActions(
    private val service: BrailleBackService,
    private val focusFinder: FocusFinder,
) {

  /** 找到当前可操作的文本框：优先输入焦点，其次无障碍焦点，最后在活跃窗口里搜第一个可编辑节点。 */
  fun findTextNode(): AccessibilityNodeInfoCompat? {
    var node = focusFinder.findFocusCompat(AccessibilityNodeInfo.FOCUS_INPUT)
    if (isTextNode(node)) {
      return node
    }
    node = focusFinder.findAccessibilityFocus()
    if (isTextNode(node)) {
      return node
    }
    try {
      val root = service.getRootInActiveWindow()
      if (root == null) {
        return null
      }
      return findEditableDescendant(AccessibilityNodeInfoCompat.wrap(root), /* depth= */ 0)
    } catch (e: RuntimeException) {
      Log.w(BrailleImeHostBridge.TAG, "findTextNode failed", e)
      return null
    }
  }

  fun clickFocusedNode(): Boolean {
    var node = focusFinder.findAccessibilityFocus()
    if (node == null) {
      node = focusFinder.findFocusCompat(AccessibilityNodeInfo.FOCUS_INPUT)
    }
    return node != null && node.performAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
  }

  fun longClickFocusedNode(): Boolean {
    val node = focusFinder.findAccessibilityFocus()
    return node != null && node.performAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
  }

  fun scrollFocusedNode(action: Int): Boolean {
    val node = focusFinder.findAccessibilityFocus()
    return node != null && node.performAction(action)
  }

  fun performNodeAction(action: Int): Boolean {
    val node = findTextNode()
    return node != null && node.performAction(action)
  }

  private fun setSelection(node: AccessibilityNodeInfoCompat, start: Int, end: Int): Boolean {
    val args = Bundle()
    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, Math.max(0, start))
    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Math.max(0, end))
    return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
  }

  /** 全选。 */
  fun selectAll(): Boolean {
    val node = findTextNode()
    if (node == null || node.getText() == null) {
      return false
    }
    return setSelection(node, 0, node.getText().length)
  }

  /**
   * 选择区间。
   *
   * @param start NO_POSITION 表示沿用当前选区起点，end NO_POSITION 表示到文本末尾
   */
  fun selectRange(start: Int, end: Int): Boolean {
    val node = findTextNode()
    if (node == null) {
      return false
    }
    val text = node.getText()
    val length = if (text == null) 0 else text.length
    val resolvedStart = if (start == BrailleImeHostBridge.NO_POSITION) 0 else start
    val resolvedEnd = if (end == BrailleImeHostBridge.NO_POSITION) length else end
    return setSelection(node, resolvedStart, resolvedEnd)
  }

  /** 从当前光标/选区扩展到文本开头或末尾。 */
  fun selectCurrentTo(toStart: Boolean): Boolean {
    val node = findTextNode()
    if (node == null) {
      return false
    }
    val text = node.getText()
    val length = if (text == null) 0 else text.length
    var selStart = node.getTextSelectionStart()
    var selEnd = node.getTextSelectionEnd()
    if (selStart < 0) {
      selStart = 0
    }
    if (selEnd < 0) {
      selEnd = selStart
    }
    // 以"离目标更远的那一端"为基准扩展，保证从任意方向都能一次到位。
    if (toStart) {
      return setSelection(node, 0, Math.max(selStart, selEnd))
    }
    return setSelection(node, Math.min(selStart, selEnd), length)
  }

  private fun findEditableDescendant(
      node: AccessibilityNodeInfoCompat?,
      depth: Int
  ): AccessibilityNodeInfoCompat? {
    if (node == null || depth > 40) {
      return null
    }
    if (isTextNode(node)) {
      return node
    }
    for (i in 0 until node.getChildCount()) {
      val child = node.getChild(i)
      if (child == null) {
        continue
      }
      val found = findEditableDescendant(child, depth + 1)
      if (found != null) {
        return found
      }
    }
    return null
  }

  private fun isTextNode(node: AccessibilityNodeInfoCompat?): Boolean {
    return node != null && node.getText() != null
  }
}
