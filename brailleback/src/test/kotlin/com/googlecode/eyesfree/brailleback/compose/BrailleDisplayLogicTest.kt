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

    // ------------------------------------------------------- 两级全屏导航

    private val groups = listOf(
        BrailleTableCatalog.TableGroup(
            "zh", "中文",
            listOf(BrailleTableCatalog.TableChoice("zh-CN-cbs", "中文（中国）")),
        ),
        BrailleTableCatalog.TableGroup(
            "en", "英语",
            listOf(BrailleTableCatalog.TableChoice("en-US-g1", "英语（美国）")),
        ),
    )

    @Test
    fun backWalksThreeRoutesInOrder() {
        val tables = BrailleDisplayLogic.TableRoute.Tables("zh")
        assertEquals(
            BrailleDisplayLogic.TableRoute.Languages,
            BrailleDisplayLogic.back(tables)
        )
        assertEquals(
            BrailleDisplayLogic.TableRoute.Settings,
            BrailleDisplayLogic.back(BrailleDisplayLogic.TableRoute.Languages)
        )
        // 到设置列表后不再吞返回键，交还系统退出 Activity。
        assertEquals(
            BrailleDisplayLogic.TableRoute.Settings,
            BrailleDisplayLogic.back(BrailleDisplayLogic.TableRoute.Settings)
        )
    }

    @Test
    fun isPickerOnlyTrueForTheTwoFullScreenRoutes() {
        assertFalse(BrailleDisplayLogic.isPicker(BrailleDisplayLogic.TableRoute.Settings))
        assertTrue(BrailleDisplayLogic.isPicker(BrailleDisplayLogic.TableRoute.Languages))
        assertTrue(BrailleDisplayLogic.isPicker(BrailleDisplayLogic.TableRoute.Tables("zh")))
    }

    @Test
    fun tableRouteCarriesLanguageCodeNotDisplayName() {
        assertEquals("zh", BrailleDisplayLogic.TableRoute.Tables("zh").language)
        assertEquals(
            BrailleDisplayLogic.TableRoute.Tables("zh"),
            BrailleDisplayLogic.TableRoute.Tables("zh")
        )
    }

    @Test
    fun groupForLanguageIsCaseInsensitiveAndSafeForUnknown() {
        assertEquals("zh", BrailleDisplayLogic.groupForLanguage(groups, "ZH")?.language)
        assertFalse(BrailleDisplayLogic.groupForLanguage(groups, "fr") != null)
    }

    @Test
    fun languageOfTableLocatesCurrentSelection() {
        assertEquals("zh", BrailleDisplayLogic.languageOfTable(groups, "zh-CN-cbs"))
        assertEquals("en", BrailleDisplayLogic.languageOfTable(groups, "en-US-g1"))
    }

    @Test
    fun languageOfTableReturnsNullForAutoEmptyOrUnknown() {
        assertEquals(null, BrailleDisplayLogic.languageOfTable(groups, "auto"))
        assertEquals(null, BrailleDisplayLogic.languageOfTable(groups, ""))
        assertEquals(null, BrailleDisplayLogic.languageOfTable(groups, "no-such-table"))
        assertEquals(null, BrailleDisplayLogic.languageOfTable(emptyList(), "zh-CN-cbs"))
    }
}
