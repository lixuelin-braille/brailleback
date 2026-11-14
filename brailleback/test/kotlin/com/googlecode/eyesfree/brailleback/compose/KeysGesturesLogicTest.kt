/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「按键与手势」Compose 子屏的纯逻辑单测（零 Android 依赖）。 */
class KeysGesturesLogicTest {

    private val none = "none"
    private val pause = "pause_display"
    private val grade = "toggle_grade"
    private val bluetooth = "toggle_bluetooth"

    @Test
    fun slotDefaultsMatchLegacyXml() {
        assertEquals(
            pause,
            KeysGesturesLogic.defaultValueFor(
                KeysGesturesLogic.KEY_LONG_PRESS_UP, none, pause, grade
            )
        )
        assertEquals(
            none,
            KeysGesturesLogic.defaultValueFor(
                KeysGesturesLogic.KEY_LONG_PRESS_DOWN, none, pause, grade
            )
        )
        assertEquals(
            grade,
            KeysGesturesLogic.defaultValueFor(
                KeysGesturesLogic.KEY_DOUBLE_PRESS, none, pause, grade
            )
        )
        // 未知槽位安全回落到「关闭」，不能误触发动作
        assertEquals(
            none,
            KeysGesturesLogic.defaultValueFor("unknown_slot", none, pause, grade)
        )
    }

    @Test
    fun initialValueFallsBackOnlyForMissingValues() {
        assertEquals(pause, KeysGesturesLogic.initialValue(null, pause))
        assertEquals(pause, KeysGesturesLogic.initialValue("", pause))
        assertEquals(grade, KeysGesturesLogic.initialValue(grade, pause))
        assertEquals("legacy_junk", KeysGesturesLogic.initialValue("legacy_junk", pause))
    }

    @Test
    fun labelForValueNeverReadsBlank() {
        val values = listOf(none, pause, grade, bluetooth)
        val entries = listOf("关闭", "暂停/继续点显", "切换缩写模式", "切换蓝牙")
        assertEquals("关闭", KeysGesturesLogic.labelForValue(values, entries, none))
        assertEquals("切换缩写模式", KeysGesturesLogic.labelForValue(values, entries, grade))
        assertEquals("关闭", KeysGesturesLogic.labelForValue(values, entries, null))
        assertEquals("关闭", KeysGesturesLogic.labelForValue(values, entries, "junk"))
        assertEquals("", KeysGesturesLogic.labelForValue(emptyList(), emptyList(), none))
    }

    @Test
    fun helpIsAvailableOnlyWhenConnected() {
        assertTrue(KeysGesturesLogic.isHelpAvailable(KeysGesturesLogic.STATE_CONNECTED))
        assertFalse(KeysGesturesLogic.isHelpAvailable(0))
        assertFalse(KeysGesturesLogic.isHelpAvailable(-1))
        assertFalse(KeysGesturesLogic.isHelpAvailable(Int.MAX_VALUE))
    }

    @Test
    fun persistedKeysAreExactlyFourAndExcludeNavigationEntries() {
        assertEquals(
            listOf(
                "volume_gestures_enabled",
                "volume_long_press_up",
                "volume_long_press_down",
                "volume_double_press",
            ),
            KeysGesturesLogic.PERSISTED_KEYS
        )
        assertFalse(KeysGesturesLogic.PERSISTED_KEYS.contains(KeysGesturesLogic.KEY_KEY_BINDINGS))
        assertFalse(KeysGesturesLogic.PERSISTED_KEYS.contains(KeysGesturesLogic.KEY_CUSTOM_KEY_MAP))
    }
}
