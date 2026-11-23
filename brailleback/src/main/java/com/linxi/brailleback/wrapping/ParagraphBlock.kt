/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.wrapping

import android.content.Context
import android.graphics.Rect
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils

/**
 * 识别网页正文中的“段块”。
 *
 * 微信公众号文章的正文不是一整段文本：每个段落是一个独立的、可聚焦的
 * 节点（`android.view.View`），文字放在 `content-desc` 里，段与段之间
 * 没有换行符。这里按“WebView 内 + 整屏宽 + 有足够文字”判定一个节点是
 * 正文段落，由 [ParagraphIndent] 在段首补两方空白。
 *
 * 标题（`activity-name`）、作者栏、时间、按钮等短节点不参与缩进。
 */
object ParagraphBlock {
    /** 少于这么多字认为是标签、按钮文字或页面装饰。 */
    const val MIN_TEXT_LENGTH = 10

    /** 正文段至少占屏幕宽度的比例，用来排除作者、时间等窄节点。 */
    const val MIN_WIDTH_RATIO = 0.6f

    /** 公众号文章标题的 view id。 */
    const val TITLE_VIEW_ID = "activity-name"

    private const val MAX_ANCESTOR_DEPTH = 40
    private const val MAX_CLUSTER_DEPTH = 4
    private const val MAX_TEXT_SEARCH_DEPTH = 3
    private const val MIN_CLUSTER_SIZE = 2
    private const val WEB_VIEW_CLASS = "android.webkit.WebView"
    private const val VIEW_CLASS = "android.view.View"
    private const val TEXT_VIEW_CLASS = "android.widget.TextView"
    private const val FRAME_LAYOUT_CLASS = "android.widget.FrameLayout"
    private const val LINEAR_LAYOUT_CLASS = "android.widget.LinearLayout"
    private const val RELATIVE_LAYOUT_CLASS = "android.widget.RelativeLayout"
    private const val VIEW_GROUP_CLASS = "android.view.ViewGroup"

    /** 纯判定逻辑，便于在 JVM 单测里覆盖。 */
    fun shouldIndent(
        className: String?,
        viewId: String?,
        contentLength: Int,
        widthPx: Int,
        screenWidthPx: Int,
        insideWebView: Boolean,
        visible: Boolean
    ): Boolean {
        if (!visible || !insideWebView) {
            return false
        }
        if (className != VIEW_CLASS && className != TEXT_VIEW_CLASS) {
            return false
        }
        if (viewId == TITLE_VIEW_ID) {
            return false
        }
        if (contentLength < MIN_TEXT_LENGTH) {
            return false
        }
        if (screenWidthPx <= 0 || widthPx <= 0) {
            return false
        }
        return widthPx >= screenWidthPx * MIN_WIDTH_RATIO
    }

    /** 网页正文段块（微信公众号等）。 */
    fun isWebViewBlock(
        node: AccessibilityNodeInfoCompat,
        context: Context,
        contentLength: Int
    ): Boolean {
        if (!isInsideWebView(node)) {
            return false
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val screenWidth = context.resources.displayMetrics.widthPixels
        return shouldIndent(
            className = node.className?.toString(),
            viewId = node.viewIdResourceName,
            contentLength = contentLength,
            widthPx = bounds.width(),
            screenWidthPx = screenWidth,
            insideWebView = true,
            visible = node.isVisibleToUser
        )
    }

    /** 原生阅读页段块（知乎等，含 Compose 的 View 文本节点）。 */
    fun isNativeReadingBlock(
        node: AccessibilityNodeInfoCompat,
        context: Context,
        contentLength: Int
    ): Boolean {
        if (isInsideWebView(node)) {
            return false
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val className = node.className?.toString()
        val viewId = node.viewIdResourceName
        val textShape = shouldIndentNative(
            className = className,
            viewId = null,
            contentLength = contentLength,
            widthPx = bounds.width(),
            screenWidthPx = screenWidth,
            focusable = node.isFocusable,
            clickable = node.isClickable,
            visible = node.isVisibleToUser
        )
        // 正文模板的 view id 会在每一段重复（如天坦百宝箱的 text1）。
        // 只有一个节点带这个 id 时仍视为页面装饰，不参与缩进。
        val viewIdAllowed = viewId.isNullOrEmpty() ||
            hasRepeatedViewId(node, screenWidth, viewId)
        if (textShape && viewIdAllowed) {
            return hasReadingCluster(node, screenWidth)
        }
        // 可点击的段容器：容器 content-desc 与子节点文本完全相同
        // （天坦百宝箱每段都是 FrameLayout + TextView 同文）。
        if (isParagraphContainer(
                node = node,
                className = className,
                contentLength = contentLength,
                widthPx = bounds.width(),
                screenWidthPx = screenWidth,
                focusable = node.isFocusable,
                visible = node.isVisibleToUser
            )
        ) {
            return hasReadingCluster(node, screenWidth)
        }
        return false
    }

    /**
     * 可点击段容器的纯判定：允许 `FrameLayout` 这类容器直接承载正文，
     * 但必须真的有一个子节点与父节点文本相同，避免把普通按钮/卡片当正文。
     */
    fun isParagraphContainerShape(
        className: String?,
        contentLength: Int,
        widthPx: Int,
        screenWidthPx: Int,
        focusable: Boolean,
        visible: Boolean,
        hasDuplicateChildText: Boolean
    ): Boolean {
        if (!visible || !focusable || !hasDuplicateChildText) {
            return false
        }
        if (className != FRAME_LAYOUT_CLASS &&
            className != LINEAR_LAYOUT_CLASS &&
            className != RELATIVE_LAYOUT_CLASS &&
            className != VIEW_GROUP_CLASS &&
            className != VIEW_CLASS
        ) {
            return false
        }
        if (contentLength < MIN_TEXT_LENGTH) {
            return false
        }
        if (screenWidthPx <= 0 || widthPx <= 0) {
            return false
        }
        return widthPx >= screenWidthPx * MIN_WIDTH_RATIO
    }

    private fun isParagraphContainer(
        node: AccessibilityNodeInfoCompat,
        className: String?,
        contentLength: Int,
        widthPx: Int,
        screenWidthPx: Int,
        focusable: Boolean,
        visible: Boolean
    ): Boolean {
        val parentText = AccessibilityNodeInfoUtils.getNodeText(node) ?: return false
        return isParagraphContainerShape(
            className = className,
            contentLength = contentLength,
            widthPx = widthPx,
            screenWidthPx = screenWidthPx,
            focusable = focusable,
            visible = visible,
            hasDuplicateChildText = hasDuplicateChildText(node, parentText)
        )
    }

    private fun hasDuplicateChildText(
        node: AccessibilityNodeInfoCompat,
        parentText: CharSequence
    ): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (isDuplicateText(parentText, AccessibilityNodeInfoUtils.getNodeText(child))) {
                    return true
                }
            } finally {
                child.recycle()
            }
        }
        return false
    }

    /** 判断 [node] 是否是正文的一个段落块（网页或原生阅读页）。 */
    fun isBodyBlock(
        node: AccessibilityNodeInfoCompat,
        context: Context,
        contentLength: Int
    ): Boolean {
        if (isInsideWebView(node)) {
            return isWebViewBlock(node, context, contentLength)
        }
        return isNativeReadingBlock(node, context, contentLength)
    }

    /**
     * 原生阅读页（知乎等）的正文段块：可聚焦、不可点击、无 view id 的
     * 整屏宽 `TextView`。知乎问题标题可点击、作者栏/时间窄，都不满足。
     */
    fun shouldIndentNative(
        className: String?,
        viewId: String?,
        contentLength: Int,
        widthPx: Int,
        screenWidthPx: Int,
        focusable: Boolean,
        clickable: Boolean,
        visible: Boolean
    ): Boolean {
        if (!visible || !focusable || clickable) {
            return false
        }
        if (className != TEXT_VIEW_CLASS && className != VIEW_CLASS) {
            return false
        }
        if (!viewId.isNullOrEmpty()) {
            return false
        }
        if (contentLength < MIN_TEXT_LENGTH) {
            return false
        }
        if (screenWidthPx <= 0 || widthPx <= 0) {
            return false
        }
        return widthPx >= screenWidthPx * MIN_WIDTH_RATIO
    }

    /**
     * 向上找一层“装着多个阅读块”的容器（知乎正文在 RecyclerView 里）。
     * 只有单条长文本、没有同构邻居的节点不缩进，避免误伤聊天/设置页。
     */
    private fun hasReadingCluster(
        node: AccessibilityNodeInfoCompat,
        screenWidthPx: Int
    ): Boolean {
        var ancestor: AccessibilityNodeInfoCompat? = node
        var owned = false
        var level = 0
        try {
            while (ancestor != null && level < MAX_CLUSTER_DEPTH) {
                val parent = ancestor.parent ?: break
                if (owned) {
                    ancestor.recycle()
                }
                ancestor = parent
                owned = true
                level++
                if (countReadingChildren(ancestor, screenWidthPx) >= MIN_CLUSTER_SIZE) {
                    return true
                }
            }
            return false
        } finally {
            if (owned) {
                ancestor?.recycle()
            }
        }
    }

    private fun countReadingChildren(parent: AccessibilityNodeInfoCompat, screenWidthPx: Int): Int {
        var count = 0
        for (i in 0 until parent.childCount) {
            val child = parent.getChild(i) ?: continue
            try {
                if (containsReadingText(child, screenWidthPx, 0)) {
                    count++
                }
            } finally {
                child.recycle()
            }
        }
        return count
    }

    /**
     * 判断同一个 view id 是否在多段正文里重复出现（正文模板）。
     * 天坦百宝箱每段的 `TextView` 都是 `id/text1`，单看 id 无法区分
     * 正文与页面装饰；重复出现 ≥2 次才放行。
     */
    private fun hasRepeatedViewId(
        node: AccessibilityNodeInfoCompat,
        screenWidthPx: Int,
        viewId: String
    ): Boolean {
        var ancestor: AccessibilityNodeInfoCompat? = node
        var owned = false
        var level = 0
        try {
            while (ancestor != null && level < MAX_CLUSTER_DEPTH) {
                val parent = ancestor.parent ?: break
                if (owned) {
                    ancestor.recycle()
                }
                ancestor = parent
                owned = true
                level++
                var count = 0
                for (i in 0 until ancestor.childCount) {
                    val child = ancestor.getChild(i) ?: continue
                    try {
                        if (containsViewId(child, viewId, 0) &&
                            containsReadingText(child, screenWidthPx, 0)
                        ) {
                            count++
                        }
                    } finally {
                        child.recycle()
                    }
                    if (isRepeatedViewIdAllowed(count)) {
                        return true
                    }
                }
            }
            return false
        } finally {
            if (owned) {
                ancestor?.recycle()
            }
        }
    }

    private fun containsViewId(
        node: AccessibilityNodeInfoCompat,
        viewId: String,
        depth: Int
    ): Boolean {
        if (node.viewIdResourceName == viewId) {
            return true
        }
        if (depth >= MAX_TEXT_SEARCH_DEPTH) {
            return false
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (containsViewId(child, viewId, depth + 1)) {
                    return true
                }
            } finally {
                child.recycle()
            }
        }
        return false
    }

    /** 同一个 view id 至少要出现在两个正文块里，才认定为正文模板。 */
    fun isRepeatedViewIdAllowed(repeatCount: Int): Boolean {
        return repeatCount >= MIN_CLUSTER_SIZE
    }

    private fun containsReadingText(
        node: AccessibilityNodeInfoCompat,
        screenWidthPx: Int,
        depth: Int
    ): Boolean {
        if (!node.isVisibleToUser) {
            return false
        }
        val className = node.className?.toString()
        if (className == TEXT_VIEW_CLASS || className == VIEW_CLASS) {
            val text = AccessibilityNodeInfoUtils.getNodeText(node) ?: return false
            if (text.trim().length < MIN_TEXT_LENGTH) {
                return false
            }
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            return bounds.width() >= screenWidthPx * MIN_WIDTH_RATIO
        }
        if (depth >= MAX_TEXT_SEARCH_DEPTH) {
            return false
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (containsReadingText(child, screenWidthPx, depth + 1)) {
                    return true
                }
            } finally {
                child.recycle()
            }
        }
        return false
    }

    /**
     * 父节点 content-desc 与子节点文本完全相同时，子节点是同一段文字的
     * 另一份副本（微信公众号每段都是这种结构），不能重复输出。
     */
    fun isDuplicateText(parentDescription: CharSequence?, childText: CharSequence?): Boolean {
        if (parentDescription == null || parentDescription.isBlank()) {
            return false
        }
        if (childText == null) {
            return false
        }
        return childText.toString().trim() == parentDescription.toString().trim()
    }

    /** 向上查找最近的 WebView 祖先；[node] 借自调用方，不回收。 */
    private fun isInsideWebView(node: AccessibilityNodeInfoCompat): Boolean {
        var current: AccessibilityNodeInfoCompat? = node
        var owned = false
        var depth = 0
        try {
            while (current != null && depth < MAX_ANCESTOR_DEPTH) {
                if (current.className?.toString() == WEB_VIEW_CLASS) {
                    return true
                }
                val parent = current.parent
                if (owned) {
                    current.recycle()
                }
                current = parent
                owned = parent != null
                depth++
            }
            return false
        } finally {
            if (owned) {
                current?.recycle()
            }
        }
    }
}
