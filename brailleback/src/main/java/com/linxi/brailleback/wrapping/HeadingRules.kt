/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.wrapping

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * 标题识别：优先用无障碍语义，其次用已知 view id，最后用章节文字规则兜底。
 *
 * Android 9（API 28）起 `AccessibilityNodeInfo.isHeading()` 可被读屏读取：
 * WebView 会把 `h1`~`h6` 映射成 heading，原生控件可设置
 * `android:accessibilityHeading="true"`。老应用不上报语义时，用
 * `activity-name`（公众号标题）和“第X章/节/回/卷”等文字规则补足。
 */
object HeadingRules {
    /** 超过这个长度不当作标题，避免把整段正文居中。 */
    const val MAX_HEADING_LENGTH = 60

    /** 微信公众号文章标题（WebView 里的 h1#activity-name）。 */
    const val WECHAT_TITLE_VIEW_ID = "activity-name"

    private val CHAPTER_PATTERNS = listOf(
        Regex("^第[0-9一二三四五六七八九十百千零两〇]+[章节回卷篇部集]"),
        Regex("^[序跋](言|记)?$"),
        Regex("^前言$|^后记$|^尾声$|^引言$|^结语$|^目录$|^序言$"),
        Regex("^(chapter|part|section)\\s+[0-9ivxlcdm]+\\b", RegexOption.IGNORE_CASE)
    )

    /** 节点是否带无障碍标题语义（WebView 的 h1~h6、原生 accessibilityHeading）。 */
    fun hasHeadingSemantics(node: AccessibilityNodeInfoCompat): Boolean = node.isHeading

    /** 通用入口：语义 / view id / 文字规则任一命中即为标题。 */
    fun isHeading(node: AccessibilityNodeInfoCompat, text: CharSequence?): Boolean {
        if (text == null) {
            return false
        }
        val trimmed = text.toString().trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_HEADING_LENGTH || trimmed.contains('\n')) {
            return false
        }
        if (hasHeadingSemantics(node)) {
            return true
        }
        if (node.viewIdResourceName == WECHAT_TITLE_VIEW_ID) {
            return true
        }
        return looksLikeHeading(trimmed)
    }

    /** 纯文字规则，便于单测。 */
    fun looksLikeHeading(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_HEADING_LENGTH) {
            return false
        }
        if (trimmed.contains('\n')) {
            return false
        }
        return CHAPTER_PATTERNS.any { it.containsMatchIn(trimmed) }
    }
}
