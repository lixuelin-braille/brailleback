/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DEBUG 参数注入解析断言（纯逻辑，不依赖 UI / Context）：
 * 合法值透传、非法值回落 null、缺失为空、大小写不敏感、部分合法部分非法时保留合法项。
 */
class TactileDebugParamsTest {

    @Test
    fun allValidMixedCaseMapsThrough() {
        val params = TactileDebugParams.parse(
            mapOf(
                "cells_per_line" to "4",
                "lines_per_screen" to "3",
                "dots" to "8",
                "normalizer" to "Auto",
                "g1_split_cover" to "true",
            ),
        )
        requireNotNull(params)
        assertEquals(4, params.cellsPerLine)
        assertEquals(3, params.linesPerScreen)
        assertEquals(8, params.dots)
        assertEquals(StockNormalizerMode.AUTO, params.normalizer)
        assertEquals(true, params.g1SplitCover)
    }

    @Test
    fun missingExtrasReturnsNull() {
        assertNull(TactileDebugParams.parse(emptyMap()))
        assertNull(
            TactileDebugParams.parse(
                mapOf("cells_per_line" to null, "dots" to null),
            ),
        )
    }

    @Test
    fun blankValuesFallBackToNull() {
        val params = TactileDebugParams.parse(
            mapOf("dots" to "   ", "normalizer" to ""),
        )
        assertNull(params)
    }

    @Test
    fun cellsAcceptOnlyAllowedChoices() {
        assertEquals(20, TactileDebugParams.parseCells("20"))
        assertEquals(30, TactileDebugParams.parseCells("30"))
        assertEquals(40, TactileDebugParams.parseCells("40"))
        assertEquals(4, TactileDebugParams.parseCells("4"))
        assertNull(TactileDebugParams.parseCells("5"))
        assertNull(TactileDebugParams.parseCells("24"))
        assertNull(TactileDebugParams.parseCells("abc"))
        assertNull(TactileDebugParams.parseCells("-1"))
    }

    @Test
    fun linesClampedToValidRange() {
        assertEquals(1, TactileDebugParams.parseLines("1"))
        assertEquals(8, TactileDebugParams.parseLines("8"))
        assertNull(TactileDebugParams.parseLines("0"))
        assertNull(TactileDebugParams.parseLines("9"))
        assertNull(TactileDebugParams.parseLines("abc"))
    }

    @Test
    fun dotsAcceptOnlySixOrEight() {
        assertEquals(6, TactileDebugParams.parseDots("6"))
        assertEquals(8, TactileDebugParams.parseDots("8"))
        assertNull(TactileDebugParams.parseDots("7"))
        assertNull(TactileDebugParams.parseDots("5"))
        assertNull(TactileDebugParams.parseDots("abc"))
    }

    @Test
    fun normalizerIsCaseInsensitive() {
        assertEquals(StockNormalizerMode.OFF, TactileDebugParams.parseNormalizer("off"))
        assertEquals(StockNormalizerMode.AUTO, TactileDebugParams.parseNormalizer("auto"))
        assertEquals(StockNormalizerMode.FORCE, TactileDebugParams.parseNormalizer("FORCE"))
        assertNull(TactileDebugParams.parseNormalizer("middle"))
    }

    @Test
    fun boolAcceptsCommonTruthyAndFalsyForms() {
        assertEquals(true, TactileDebugParams.parseBool("true"))
        assertEquals(true, TactileDebugParams.parseBool("TRUE"))
        assertEquals(true, TactileDebugParams.parseBool("1"))
        assertEquals(true, TactileDebugParams.parseBool("yes"))
        assertEquals(false, TactileDebugParams.parseBool("false"))
        assertEquals(false, TactileDebugParams.parseBool("FALSE"))
        assertEquals(false, TactileDebugParams.parseBool("0"))
        assertEquals(false, TactileDebugParams.parseBool("no"))
        assertNull(TactileDebugParams.parseBool("maybe"))
    }

    @Test
    fun partialValidKeepsLegalDropsIllegal() {
        val params = TactileDebugParams.parse(
            mapOf("dots" to "6", "cells_per_line" to "99"),
        )
        requireNotNull(params)
        assertEquals(6, params.dots)
        assertNull(params.cellsPerLine)
        assertTrue(params.anySet)
    }
}
