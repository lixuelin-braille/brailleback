/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「盲文显示」子屏纯逻辑的行为测试。 */
class BrailleDisplayLogicTest {

    @Test
    fun persistedKeysMatchLegacyPreferenceNamesAndOrder() {
        assertEquals(
            listOf(
                "braille_table", "braille_word_wrap", "cursor_dots",
                "braille_contracted_mode", "chinese_segmentation",
            ),
            BrailleDisplayLogic.PERSISTED_KEYS
        )
        assertEquals(5, BrailleDisplayLogic.PERSISTED_KEYS.toSet().size)
    }

    @Test
    fun defaultTableIsTheLegacyAutoSentinel() {
        assertEquals("auto", BrailleDisplayLogic.DEFAULT_TABLE)
        assertEquals(BrailleTableCatalog.AUTO_VALUE, BrailleDisplayLogic.DEFAULT_TABLE)
    }

    @Test
    fun initialTableFallsBackForMissingValue() {
        assertEquals("auto", BrailleDisplayLogic.initialTable(null))
        assertEquals("auto", BrailleDisplayLogic.initialTable(""))
        assertEquals("zh-CN-cbs", BrailleDisplayLogic.initialTable("zh-CN-cbs"))
    }

    @Test
    fun booleanDefaultsMatchLegacyXml() {
        assertTrue(
            BrailleDisplayLogic.defaultBoolean(
                BrailleDisplayLogic.KEY_WORD_WRAP, true, true
            )
        )
        assertTrue(
            BrailleDisplayLogic.defaultBoolean(
                BrailleDisplayLogic.KEY_CHINESE_SEGMENTATION, true, true
            )
        )
        assertFalse(
            BrailleDisplayLogic.defaultBoolean(
                BrailleDisplayLogic.KEY_CURSOR_DOTS, true, true
            )
        )
        assertFalse(
            BrailleDisplayLogic.defaultBoolean(
                BrailleDisplayLogic.KEY_CONTRACTED_MODE, true, true
            )
        )
    }

    @Test
    fun booleanDefaultsFollowInjectedResourceValues() {
        assertFalse(
            BrailleDisplayLogic.defaultBoolean(
                BrailleDisplayLogic.KEY_WORD_WRAP, false, true
            )
        )
        assertFalse(
            BrailleDisplayLogic.defaultBoolean(
                BrailleDisplayLogic.KEY_CHINESE_SEGMENTATION, true, false
            )
        )
        assertFalse(BrailleDisplayLogic.defaultBoolean("unknown", true, true))
    }
}
