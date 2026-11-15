/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import android.content.Context
import android.graphics.Rect
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.util.Log
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.brailleback.rule.BrailleRule
import com.googlecode.eyesfree.brailleback.rule.BrailleRuleRepository
import com.googlecode.eyesfree.brailleback.utils.StringUtils
import com.googlecode.eyesfree.brailleback.wrapping.HeadingRules
import com.googlecode.eyesfree.brailleback.wrapping.ParagraphBlock
import com.googlecode.eyesfree.brailleback.wrapping.ParagraphIndent
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoRef
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils
import com.googlecode.eyesfree.utils.LogUtils

/**
 * Turns a subset of the node tree into braille.
 */
class NodeBrailler(
    private val mContext: Context,
    private val mRuleRepository: BrailleRuleRepository,
    private val mSelfBrailleManager: SelfBrailleManager
) {

    /**
     * Converts [node] and its surroundings to annotated text.
     *
     * [focusNode] is the braille cursor's logical focus.  It is used when
     * braille navigation deliberately does not move system accessibility
     * focus (for example, in braille-only teaching mode).  When omitted, the
     * system accessibility focus is used, preserving the original behaviour.
     */
    fun brailleNode(
        node: AccessibilityNodeInfoCompat,
        focusNode: AccessibilityNodeInfoCompat? = null
    ): Content? {
        var content = mSelfBrailleManager.contentForNode(node)
        if (content == null) {
            val toFormat = ArrayList<AccessibilityNodeInfoCompat>()
            findNodesToFormat(node, toFormat)
            LogUtils.log(this, Log.VERBOSE, "Going to format %d nodes", toFormat.size)
            val sb = SpannableStringBuilder()
            for (n in toFormat) {
                formatSubtree(n, sb, focusNode)
            }
            content = Content(sb)
            val heading = toFormat.size == 1 && HeadingRules.isHeading(node, sb)
            // 阅读类长文本（微信读书等把整页正文放在一个 content-desc 里，
            // 段间用空行分隔）按段落排版：段首空两方，下一段从行首开始。
            // 短节点、单换行文本不受影响。
            if (heading) {
                // 标题（WebView h1~h6、原生 accessibilityHeading、公众号
                // activity-name、第X章/节等）：整块居中，不做段首缩进。
                content.setHeading(true)
            } else if (ParagraphIndent.shouldFormat(sb)) {
                content.setParagraphIndent(true)
            } else if (ParagraphBlock.isBodyBlock(node, mContext, sb.trim().length)) {
                // 公众号这类“每段一个节点”的网页正文：整块内容就是一段，
                // 段首（下标 0）也要补两方。
                content.setParagraphIndent(true)
                content.setFirstParagraphIndent(true)
            }
            content.setFirstNode(toFormat[0])
                .setLastNode(toFormat[toFormat.size - 1])
            AccessibilityNodeInfoUtils.recycleNodes(*toFormat.toTypedArray())
        }
        if (focusNode != null) {
            ensureFocusSpan(content, focusNode)
        }
        return content
    }

    /** Makes sure logical focus is visible even for self-brailled content. */
    private fun ensureFocusSpan(
        content: Content,
        focusNode: AccessibilityNodeInfoCompat
    ) {
        val spannable = content.getText() as? Spannable ?: return
        if (spannable.getSpans(
                0, spannable.length, DisplaySpans.FocusSpan::class.java).isNotEmpty()) {
            return
        }
        val nodeSpan = spannable.getSpans(
                0, spannable.length, AccessibilityNodeInfoCompat::class.java)
                .firstOrNull { it == focusNode }
        if (nodeSpan != null) {
            DisplaySpans.addFocus(
                    spannable, spannable.getSpanStart(nodeSpan), spannable.getSpanEnd(nodeSpan))
        } else {
            DisplaySpans.addFocus(spannable, 0, spannable.length)
        }
    }

    /** Formats `node` and its descendants, appending the result to `result`. */
    private fun formatSubtree(
        node: AccessibilityNodeInfoCompat,
        result: Editable,
        focusNode: AccessibilityNodeInfoCompat?
    ) {
        if (!node.isVisibleToUser) {
            return
        }

        val rule = mRuleRepository.find(node)
        val subtreeResult = SpannableStringBuilder()
        rule?.format(subtreeResult, mContext, node)
        if (rule?.includeChildren(node, mContext) == true) {
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i)
                if (child == null) {
                    continue
                }
                if (isDuplicateOfParentText(node, child)) {
                    child.recycle()
                    continue
                }
                formatSubtree(child, subtreeResult, focusNode)
                child.recycle()
            }
        }
        if (!TextUtils.isEmpty(subtreeResult)) {
            // If the node is accessibility focused, add the focus span here to
            // cover the node and its formatted children. This is a fallback in
            // case the formatting rule hasn't set focus by itself.
            // When a logical focus node is supplied, only that node is marked:
            // the system focus may legitimately point somewhere else in
            // braille-only navigation mode.
            val isFocused = if (focusNode != null) {
                node == focusNode
            } else {
                node.isAccessibilityFocused
            }
            if (isFocused
                && subtreeResult.getSpans(0, subtreeResult.length,
                    DisplaySpans.FocusSpan::class.java).isEmpty()) {
                DisplaySpans.addFocus(subtreeResult, 0, subtreeResult.length)
            }
            addNodeSpanForUncovered(node, subtreeResult)
            StringUtils.appendWithSpaces(result, subtreeResult)
        }
    }

    /**
     * 网页正文常见“父节点 content-desc 与子 TextView 文本完全相同”的结构
     * （微信公众号每段都是这样）。父节点已经输出过同一段文字时跳过子节点，
     * 否则同一段会被拼两遍。
     */
    private fun isDuplicateOfParentText(
        parent: AccessibilityNodeInfoCompat,
        child: AccessibilityNodeInfoCompat
    ): Boolean {
        if (child.childCount > 0) {
            return false
        }
        return ParagraphBlock.isDuplicateText(
            parent.contentDescription,
            AccessibilityNodeInfoUtils.getNodeText(child))
    }

    /** Adds `node` as a span on `content` if not already fully covered. */
    private fun addNodeSpanForUncovered(node: AccessibilityNodeInfoCompat, spannable: Spannable) {
        val spans = spannable.getSpans(0, spannable.length, AccessibilityNodeInfoCompat::class.java)
        for (span in spans) {
            if (spannable.getSpanStart(span) == 0
                && spannable.getSpanEnd(span) == spannable.length) {
                return
            }
        }
        DisplaySpans.setAccessibilityNode(spannable, node)
    }

    /**
     * Finds the display extent for `node`, putting the result in `outLeft` and
     * `outRight`. The display extent is the first and last node that will
     * contribute to the content of the display when `node` has accessibility
     * focus.
     */
    fun findDisplayExtentFromNode(
        node: AccessibilityNodeInfoCompat,
        outLeft: AccessibilityNodeInfoRef,
        outRight: AccessibilityNodeInfoRef
    ) {
        val nodeRect = Rect()
        node.getBoundsInScreen(nodeRect)
        // 节点已被回收时 unOwned 返回 null：放弃这次边界计算，不抛异常。
        val current = AccessibilityNodeInfoRef.unOwned(node) ?: return
        var currentIncludesChildren = includesChildren(current.get() ?: return)
        // Walk up the node tree as long as all siblings overlap the original
        // node vertically on screen.
        do {
            if (currentIncludesChildren) {
                if (!findOverlappingRange(current.get() ?: break, nodeRect, outLeft, outRight)) {
                    break
                }
            } else {
                // If the start node doesn't include its children, don't include
                // any adjacent nodes, since that causes line-by-line navigation
                // to be confusing and get stuck in a cycle.
                outLeft.reset(current)
                outRight.reset(current)
                break
            }
            if (!current.parent()) {
                break
            }
            // TODO: why not format bottom-up and avoid having to lookup the
            // rule twice?  Consider that at some point.
            currentIncludesChildren = includesChildren(current.get() ?: break)
            // Don't let any parent prevent us from getting onto the display,
            // *we* contain the focused node.
        } while (currentIncludesChildren)
    }

    /**
     * Finds the list of nodes that should be formatted and put on the braille
     * display together with `node`.  The result, including a copy of `node`
     * itself, is appended to `outResult`.
     */
    private fun findNodesToFormat(node: AccessibilityNodeInfoCompat, outResult: ArrayList<AccessibilityNodeInfoCompat>) {
        val left = AccessibilityNodeInfoRef()
        val right = AccessibilityNodeInfoRef()
        findDisplayExtentFromNode(node, left, right)
        do {
            val owned = left.makeOwned().release() ?: break
            outResult.add(owned)
            // 两端都为空时视为同一位置：用可空安全的 != 比较，不再依赖非空断言。
        } while (left.get() != right.get() && left.nextSibling())
    }

    /**
     * 沿兄弟链朝一个方向扫，直到越界、节点被回收、或遇到不在同一行的节点。
     *
     * 返回 true 表示扫到了链的尽头（覆盖了全部兄弟）。用单一出口而不是在循环里
     * 堆 break：原来这里同时有 `get() 为空就 break` 和 `不同行就 break` 两个跳出口，
     * 一旦两个方向都这么写，函数就没法一眼看懂在哪里停。
     */
    private fun scanSiblings(
        ref: AccessibilityNodeInfoRef,
        rect: Rect,
        tmpRect: Rect,
        step: (AccessibilityNodeInfoRef) -> Boolean,
        onAccept: (AccessibilityNodeInfoRef) -> Unit
    ): Boolean {
        while (step(ref)) {
            val node = ref.get() ?: return false
            node.getBoundsInScreen(tmpRect)
            if (!includesChildren(node) || !shouldPutOnSameLine(tmpRect, rect)) {
                return false
            }
            onAccept(ref)
        }
        return true
    }
    /**
     * Finds the range of siblings adjacent to `startFrom` that overlap `rect`
     * vertically.  Sets `outLeft` and `outRight` to the inclusive range that
     * overlaps.  If there are no overlapping siblings in one of the directions,
     * the corresponding parameter will be set to `startFrom`.  Returns `true`
     * if the range covers all siblings of `startFrom`, `false` otherwise.
     */
    private fun findOverlappingRange(
        startFrom: AccessibilityNodeInfoCompat,
        rect: Rect,
        outLeft: AccessibilityNodeInfoRef,
        outRight: AccessibilityNodeInfoRef
    ): Boolean {
        val tmpRect = Rect()
        // 起点的 ref 建立不起来时，没有可比对的兄弟节点，直接判定「不覆盖」。
        val left = AccessibilityNodeInfoRef.unOwned(startFrom) ?: return false
        outLeft.reset(left)
        var ret = scanSiblings(left, rect, tmpRect, { it.previousSibling() }) { outLeft.reset(it) }
        val right = AccessibilityNodeInfoRef.unOwned(startFrom)
        if (right == null) {
            left.recycle()
            return false
        }
        outRight.reset(right)
        // 两个方向都要扫：左边失败不代表右边不用扫，这里不能用 && 短路。
        if (!scanSiblings(right, rect, tmpRect, { it.nextSibling() }) { outRight.reset(it) }) {
            ret = false
        }
        outLeft.makeOwned()
        outRight.makeOwned()
        left.recycle()
        right.recycle()
        return ret
    }

    /**
     * Returns `true` if the two rectangles should be considered to be on the
     * same 'line' for the purposes of putting horizontally adjacent content on
     * the braille display at the same time.
     */
    private fun shouldPutOnSameLine(a: Rect, b: Rect): Boolean {
        var ret = a.top < b.bottom && b.top < a.bottom
        if (ret) {
            val aHeight = a.bottom - a.top
            val bHeight = b.bottom - b.top
            if (aHeight > HEIGHT_DIFF_THRESHOLD * bHeight
                || bHeight > HEIGHT_DIFF_THRESHOLD * aHeight) {
                ret = false
            }
        }
        return ret
    }

    private fun includesChildren(node: AccessibilityNodeInfoCompat): Boolean {
        val rule = mRuleRepository.find(node)
        return rule?.includeChildren(node, mContext) == true
    }

    companion object {
        /**
         * Height difference between node rectangles under which they are put on
         * the braille display at the same time if adjacent and vertically
         * overlapping.
         */
        private const val HEIGHT_DIFF_THRESHOLD = 4
    }
}
