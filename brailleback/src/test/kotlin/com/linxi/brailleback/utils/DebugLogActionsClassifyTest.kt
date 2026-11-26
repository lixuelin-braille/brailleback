/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.utils

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分享失败分类纯函数断言：三类已命名异常各归其类，兜底走 UNKNOWN；
 * 诊断字段必须带异常类名与 message，便于事后在文件日志里定位。
 */
class DebugLogActionsClassifyTest {

    @Test
    fun activityNotFoundIsNoReceiver() {
        // 本地单测 android.jar 桩构造器不保留 message，这里只断言分类与类名前缀；
        // message 保留行为在实机日志留痕要求中核对。
        val outcome = DebugLogActions.classifyFailure(
            ActivityNotFoundException("no launcher")
        )
        assertEquals(DebugLogActions.ExportFailureCategory.NO_RECEIVER, outcome.category)
        assertTrue(outcome.reason.startsWith("ActivityNotFoundException:"))
    }

    @Test
    fun securityExceptionIsUriPermission() {
        val outcome = DebugLogActions.classifyFailure(
            SecurityException("denied")
        )
        assertEquals(DebugLogActions.ExportFailureCategory.URI_PERMISSION, outcome.category)
        assertTrue(outcome.reason.startsWith("SecurityException:"))
    }

    @Test
    fun illegalArgumentIsBadArgument() {
        val outcome = DebugLogActions.classifyFailure(
            IllegalArgumentException("bad uri")
        )
        assertEquals(DebugLogActions.ExportFailureCategory.BAD_ARGUMENT, outcome.category)
        assertTrue(outcome.reason.startsWith("IllegalArgumentException:"))
        assertTrue(outcome.reason.contains("bad uri"))
    }

    @Test
    fun otherExceptionFallsBackToUnknown() {
        val outcome = DebugLogActions.classifyFailure(
            IllegalStateException("boom")
        )
        assertEquals(DebugLogActions.ExportFailureCategory.UNKNOWN, outcome.category)
        assertTrue(outcome.reason.startsWith("IllegalStateException:"))
    }

    @Test
    fun outcomeIsAlwaysFailure() {
        val outcome = DebugLogActions.classifyFailure(RuntimeException("x"))
        assertEquals(false, outcome.success)
    }
}
