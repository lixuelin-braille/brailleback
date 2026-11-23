/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「声音与行为」Compose 子屏的纯逻辑单测（零 Android 依赖）。 */
class SoundBehaviorLogicTest {

    private val template = "音效音量：%1\$d"

    // ------------------------------------------------------------ 音量边界

    @Test
    fun volumeConstantsMatchLegacySeekBarAndSoundSchemeManager() {
        assertEquals(0, SoundBehaviorLogic.VOLUME_MIN)
        assertEquals(100, SoundBehaviorLogic.VOLUME_MAX)
        assertEquals(50, SoundBehaviorLogic.VOLUME_DEFAULT)
    }

    @Test
    fun coerceVolumeKeepsInRangeValues() {
        assertEquals(0, SoundBehaviorLogic.coerceVolume(0))
        assertEquals(1, SoundBehaviorLogic.coerceVolume(1))
        assertEquals(50, SoundBehaviorLogic.coerceVolume(50))
        assertEquals(99, SoundBehaviorLogic.coerceVolume(99))
        assertEquals(100, SoundBehaviorLogic.coerceVolume(100))
    }

    @Test
    fun coerceVolumeClampsOutOfRangeValues() {
        assertEquals(0, SoundBehaviorLogic.coerceVolume(-1))
        assertEquals(0, SoundBehaviorLogic.coerceVolume(-500))
        assertEquals(100, SoundBehaviorLogic.coerceVolume(101))
        assertEquals(100, SoundBehaviorLogic.coerceVolume(9999))
        assertEquals(0, SoundBehaviorLogic.coerceVolume(Int.MIN_VALUE))
        assertEquals(100, SoundBehaviorLogic.coerceVolume(Int.MAX_VALUE))
    }

    @Test
    fun stepVolumeAppliesDeltaAndClampsAtBothEnds() {
        assertEquals(51, SoundBehaviorLogic.stepVolume(50, 1))
        assertEquals(49, SoundBehaviorLogic.stepVolume(50, -1))
        assertEquals(100, SoundBehaviorLogic.stepVolume(99, 10))
        assertEquals(0, SoundBehaviorLogic.stepVolume(1, -10))
        assertEquals(0, SoundBehaviorLogic.stepVolume(0, -1))
        assertEquals(100, SoundBehaviorLogic.stepVolume(100, 1))
    }

    @Test
    fun stepVolumeClampsBeforeSteppingWhenInputIsDirty() {
        // 历史脏值不该在步进后又被带进合法区间之外
        assertEquals(100, SoundBehaviorLogic.stepVolume(500, 1))
        assertEquals(0, SoundBehaviorLogic.stepVolume(-20, -1))
    }

    @Test
    fun formatVolumeSubstitutesTemplateAndCoerces() {
        assertEquals("音效音量：50", SoundBehaviorLogic.formatVolume(template, 50))
        assertEquals("音效音量：0", SoundBehaviorLogic.formatVolume(template, -7))
        assertEquals("音效音量：100", SoundBehaviorLogic.formatVolume(template, 137))
    }

    @Test
    fun formatVolumeLeavesOtherTextIntact() {
        val long = "当前 %1\$d / 上限 100"
        assertEquals("当前 23 / 上限 100", SoundBehaviorLogic.formatVolume(long, 23))
    }

    @Test
    fun formatVolumeWithoutPlaceholderDoesNotThrow() {
        assertEquals("音量", SoundBehaviorLogic.formatVolume("音量", 42))
    }

    // ------------------------------------------------------------ 列表值

    @Test
    fun initialValueFallsBackToDefaultForMissingOrBlank() {
        assertEquals("clear", SoundBehaviorLogic.initialValue(null, "clear"))
        assertEquals("clear", SoundBehaviorLogic.initialValue("", "clear"))
        assertEquals("keep", SoundBehaviorLogic.initialValue("keep", "clear"))
        assertEquals("3", SoundBehaviorLogic.initialValue("3", "3"))
    }

    @Test
    fun labelForValuePicksMatchingEntry() {
        val values = listOf("clear", "keep")
        val entries = listOf("清空点显", "保留内容")
        assertEquals("清空点显", SoundBehaviorLogic.labelForValue(values, entries, "clear"))
        assertEquals("保留内容", SoundBehaviorLogic.labelForValue(values, entries, "keep"))
    }

    @Test
    fun labelForValueFallsBackToFirstEntryForUnknownOrNull() {
        val values = listOf("0", "1", "3", "5")
        val entries = listOf("关闭", "1分钟", "3分钟", "5分钟")
        assertEquals("关闭", SoundBehaviorLogic.labelForValue(values, entries, "99"))
        assertEquals("关闭", SoundBehaviorLogic.labelForValue(values, entries, null))
        assertEquals("关闭", SoundBehaviorLogic.labelForValue(values, entries, ""))
    }

    @Test
    fun labelForValueHandlesEmptyLists() {
        assertEquals("", SoundBehaviorLogic.labelForValue(emptyList(), emptyList(), "any"))
    }

    // ------------------------------------------------------------ 运动模式

    @Test
    fun motionModeWriteAlwaysSetsExplicitFlag() {
        val on = SoundBehaviorLogic.motionModeWrite(true)
        val off = SoundBehaviorLogic.motionModeWrite(false)
        assertEquals(true, on[SoundBehaviorLogic.KEY_MOTION_MODE_EXPLICIT])
        assertEquals(true, off[SoundBehaviorLogic.KEY_MOTION_MODE_EXPLICIT])
    }

    @Test
    fun motionModeWriteKeepsTheUserValue() {
        assertEquals(true, SoundBehaviorLogic.motionModeWrite(true)[SoundBehaviorLogic.KEY_MOTION_MODE])
        assertEquals(false, SoundBehaviorLogic.motionModeWrite(false)[SoundBehaviorLogic.KEY_MOTION_MODE])
    }

    @Test
    fun motionModeWriteTouchesExactlyTwoLegacyKeys() {
        assertEquals(
            setOf(SoundBehaviorLogic.KEY_MOTION_MODE, SoundBehaviorLogic.KEY_MOTION_MODE_EXPLICIT),
            SoundBehaviorLogic.motionModeWrite(true).keys
        )
        assertEquals(2, SoundBehaviorLogic.motionModeWrite(false).size)
    }

    @Test
    fun motionModeDefaultIsFalseLikeLegacyCheckbox() {
        assertFalse(SoundBehaviorLogic.MOTION_MODE_DEFAULT)
    }

    // ------------------------------------------------------------ 键清单

    @Test
    fun persistedKeysKeepLegacyOrderAndCount() {
        assertEquals(
            listOf(
                "pref_sound_enabled",
                "pref_sound_volume",
                "pause_behavior",
                "idle_clear_timeout",
                "motion_mode",
                "quiet_navigation",
            ),
            SoundBehaviorLogic.PERSISTED_KEYS
        )
        assertEquals(6, SoundBehaviorLogic.PERSISTED_KEYS.size)
        assertEquals(
            SoundBehaviorLogic.PERSISTED_KEYS.size,
            SoundBehaviorLogic.PERSISTED_KEYS.toSet().size
        )
    }

    @Test
    fun explicitMarkerKeyIsNotRenderedAsASettingRow() {
        assertFalse(SoundBehaviorLogic.PERSISTED_KEYS.contains(SoundBehaviorLogic.KEY_MOTION_MODE_EXPLICIT))
        assertTrue(SoundBehaviorLogic.PERSISTED_KEYS.contains(SoundBehaviorLogic.KEY_MOTION_MODE))
    }
}
