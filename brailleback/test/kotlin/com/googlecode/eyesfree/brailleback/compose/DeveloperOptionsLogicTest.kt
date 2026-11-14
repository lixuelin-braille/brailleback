/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「开发者选项」纯逻辑单测：日志级别、依赖启用态、日志预览。 */
class DeveloperOptionsLogicTest {

    private val values = listOf("9", "7", "6", "5", "4", "3", "2")
    private val entries = listOf(
        "NONE", "ASSERT", "ERROR", "WARN", "INFO", "DEBUG", "VERBOSE"
    )

    // ------------------------------------------------------------ 日志级别

    @Test
    fun parseLogLevelAcceptsStoredStringValues() {
        assertEquals(9, DeveloperOptionsLogic.parseLogLevel("9"))
        assertEquals(4, DeveloperOptionsLogic.parseLogLevel("4"))
        assertEquals(2, DeveloperOptionsLogic.parseLogLevel("2"))
    }

    @Test
    fun parseLogLevelRejectsGarbageWithoutThrowing() {
        assertEquals(null, DeveloperOptionsLogic.parseLogLevel(""))
        assertEquals(null, DeveloperOptionsLogic.parseLogLevel("abc"))
        assertEquals(null, DeveloperOptionsLogic.parseLogLevel(null))
    }

    @Test
    fun diagnosticBuildCannotPersistLogLevel() {
        // PreferenceUtils.updateLogLevel 在 DIAG_LOG_LEVEL>0 时返回 false，
        // 旧 Fragment 的 onPreferenceChange 也据此返回 false 不保存新值。
        assertTrue(DeveloperOptionsLogic.shouldPersistLogLevel(4, true))
        assertFalse(DeveloperOptionsLogic.shouldPersistLogLevel(4, false))
    }

    @Test
    fun illegalLogLevelIsNeverPersistedEvenIfUpdateSucceeds() {
        assertFalse(DeveloperOptionsLogic.shouldPersistLogLevel(null, true))
        assertFalse(DeveloperOptionsLogic.shouldPersistLogLevel(null, false))
    }

    @Test
    fun initialLogLevelFallsBackToXmlDefault() {
        assertEquals(
            "4", DeveloperOptionsLogic.initialLogLevel(null, "4", values)
        )
        assertEquals(
            "4", DeveloperOptionsLogic.initialLogLevel("", "4", values)
        )
        assertEquals(
            "4", DeveloperOptionsLogic.initialLogLevel("999", "4", values)
        )
    }

    @Test
    fun initialLogLevelKeepsAnyStoredLegalValue() {
        values.forEach { value ->
            assertEquals(
                value, DeveloperOptionsLogic.initialLogLevel(value, "4", values)
            )
        }
    }

    @Test
    fun labelForValueMapsLogPriorityToStringEntry() {
        assertEquals(
            "INFO", DeveloperOptionsLogic.labelForValue(values, entries, "4")
        )
        assertEquals(
            "VERBOSE", DeveloperOptionsLogic.labelForValue(values, entries, "2")
        )
    }

    @Test
    fun labelForValueNeverReadsBlankOnUnknownValue() {
        assertEquals(
            "NONE", DeveloperOptionsLogic.labelForValue(values, entries, "999")
        )
        assertEquals("", DeveloperOptionsLogic.labelForValue(emptyList(), emptyList(), "4"))
    }

    // -------------------------------------------------------- 叠加层与教程

    @Test
    fun tutorialRowFollowsOverlayDependency() {
        assertTrue(DeveloperOptionsLogic.tutorialEnabled(true))
        assertFalse(DeveloperOptionsLogic.tutorialEnabled(false))
    }

    @Test
    fun firstTimeTutorialIsCheckedOnlyWhenOverlayIsTurnedOn() {
        assertTrue(DeveloperOptionsLogic.shouldCheckFirstTimeTutorial(true))
        assertFalse(DeveloperOptionsLogic.shouldCheckFirstTimeTutorial(false))
    }

    // ------------------------------------------------------------ 日志预览

    @Test
    fun hasLogRejectsBlankContent() {
        assertFalse(DeveloperOptionsLogic.hasLog(""))
        assertFalse(DeveloperOptionsLogic.hasLog("  \n\t "))
        assertTrue(DeveloperOptionsLogic.hasLog("line"))
    }

    @Test
    fun emptyLogShowsHintInsteadOfBlankDialog() {
        assertEquals(
            "暂无日志记录",
            DeveloperOptionsLogic.logPreview("  \n", "暂无日志记录", "只看最近部分")
        )
    }

    @Test
    fun shortLogIsShownInFull() {
        val raw = "first line\nsecond line"
        assertEquals(
            raw, DeveloperOptionsLogic.logPreview(raw, "空", "只看最近部分", 4000)
        )
    }

    @Test
    fun longLogKeepsOnlyTheMostRecentPartWithWrittenHint() {
        val raw = "A".repeat(100) + "B".repeat(50)
        val preview = DeveloperOptionsLogic.logPreview(raw, "空", "只看最近部分", 20)
        assertTrue(preview.startsWith("只看最近部分\n"))
        assertEquals("B".repeat(20), preview.removePrefix("只看最近部分\n"))
    }

    @Test
    fun previewBoundaryAtExactlyMaxCharsIsNotTruncated() {
        val raw = "x".repeat(20)
        assertEquals(
            raw, DeveloperOptionsLogic.logPreview(raw, "空", "只看最近部分", 20)
        )
    }

    // -------------------------------------------------------------- 键清单

    @Test
    fun itemKeysMatchLegacyScreenOrder() {
        assertEquals(
            listOf(
                "log_level", "view_log", "share_log", "copy_log", "clear_log",
                "overlay_enabled", "show_overlay_tutorial"
            ),
            DeveloperOptionsLogic.ITEM_KEYS
        )
    }

    @Test
    fun onlyTwoKeysArePersisted() {
        assertEquals(
            listOf("log_level", "overlay_enabled"),
            DeveloperOptionsLogic.PERSISTED_KEYS
        )
        assertEquals(7, DeveloperOptionsLogic.ITEM_KEYS.size)
    }
}
