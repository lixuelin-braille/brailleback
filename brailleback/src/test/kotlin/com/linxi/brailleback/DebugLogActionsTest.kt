/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大日志出口的纯逻辑：分享分派阈值、剪贴板截断行数。
 *
 * 不触碰 Context / Intent；Binder 超限与 FileProvider 接线在真机复核。
 */
class DebugLogActionsTest {

    @Test
    fun smallTextGoesThroughExtraText() {
        assertEquals(
            DebugLogActions.ShareRoute.EXTRA_TEXT,
            DebugLogActions.shareRoute("hello, log"),
        )
    }

    @Test
    fun oversizedTextGoesThroughFileProvider() {
        val big = "a".repeat(200 * 1024 + 1)
        assertEquals(
            DebugLogActions.ShareRoute.FILE_PROVIDER,
            DebugLogActions.shareRoute(big),
        )
    }

    @Test
    fun tailLinesKeepsLastN() {
        val lines = (1..300).map { "line-$it" }
        val out = DebugLogActions.tailLines(lines.joinToString("\n"), DebugLogActions.COPY_MAX_LINES)
        val outLines = out.lines()
        assertEquals(DebugLogActions.COPY_MAX_LINES, outLines.size)
        assertTrue("截断后应保留最后一行", outLines.contains("line-300"))
        assertFalse("截断后不应保留最早一行", outLines.contains("line-1"))
        assertTrue("截断后应保留第 101 行（留存窗口起点附近）", outLines.contains("line-101"))
    }

    @Test
    fun tailLinesUnderLimitReturnsOriginal() {
        val text = "a\nb\nc"
        assertEquals(text, DebugLogActions.tailLines(text, DebugLogActions.COPY_MAX_LINES))
    }
}
