/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 为什么需要它：文件名是手机→电脑刻印链路上用户唯一能核对的东西——主名清洗错了会丢图名，
 * 非法字符会让电脑端存不下，超长会被文件系统截断得面目全非。这些规则全在纯 JVM 层，
 * 不碰 Android，这里把中文名、非法字符、超长截断、空兜底、时间戳格式五条边界钉死。
 */
class TactileExportNamesTest {

    @Test
    fun chineseName_isKeptInFullName() {
        val name = TactileExportNames.build("示意图一.png", "20261124_1530")
        assertEquals("tactile_示意图一_20261124_1530.bmp", name)
        assertTrue("中文名必须保留以便读屏朗读", name.contains("示意图一"))
    }

    @Test
    fun illegalChars_areReplacedWithUnderscore() {
        val stem = TactileExportNames.sanitizeStem("a/b:c*d?e\"f<g>h|i.png")
        assertEquals("a_b_c_d_e_f_g_h_i", stem)
        assertFalse(
            "非法字符不得残留：$stem",
            stem.any { it in "\\/:*?\"<>|" }
        )
    }

    @Test
    fun overlongStem_isTruncatedTo40() {
        val raw = "字".repeat(60) + ".png"
        val stem = TactileExportNames.sanitizeStem(raw)
        assertEquals(40, stem.length)
        val name = TactileExportNames.build(raw, "20261124_1530")
        assertTrue("总长不得超过 100 字符：$name", name.length <= TactileExportNames.TOTAL_MAX)
    }

    @Test
    fun blankOrEmpty_stemFallsBackToImage() {
        assertEquals("image", TactileExportNames.sanitizeStem(""))
        assertEquals("image", TactileExportNames.sanitizeStem(null))
        assertEquals("image", TactileExportNames.sanitizeStem("   "))
        assertEquals("image", TactileExportNames.sanitizeStem("....png"))
        val name = TactileExportNames.build(null, "20261124_1530")
        assertEquals("tactile_image_20261124_1530.bmp", name)
    }

    @Test
    fun timestamp_matchesCompactPattern() {
        val ts = TactileExportNames.timestamp(1763908200000L)
        assertTrue(ts.matches(Regex("\\d{8}_\\d{4}")))
        val name = TactileExportNames.build("x.png", ts)
        assertTrue(name.endsWith("_$ts.bmp"))
    }

    @Test
    fun totalName_isWithin100Chars() {
        val raw = "测试文件".repeat(20) + ".jpg"
        val name = TactileExportNames.build(raw, "20261124_1530")
        assertTrue(name.length <= TactileExportNames.TOTAL_MAX)
    }

    @Test
    fun sizeFormat_switchesBetweenKbAndMb() {
        assertEquals("0 KB", TactileExportNames.formatSize(0))
        assertEquals("1 KB", TactileExportNames.formatSize(1024))
        assertEquals("1.0 MB", TactileExportNames.formatSize(1024L * 1024))
        assertEquals("1.5 MB", TactileExportNames.formatSize(1572864L))
    }

    @Test
    fun resolvedName_usesQueriedNameWithDuplicateSuffix() {
        val local = "tactile_photo_20261123_2231.bmp"
        val queried = "tactile_photo_20261123_2231 (1).bmp"
        assertEquals(queried, TactileExportNames.resolveDisplay(local, queried))
    }

    @Test
    fun resolvedName_fallsBackToLocal_whenQueriedNull() {
        val local = "tactile_image_20261123_2231.bmp"
        assertEquals(local, TactileExportNames.resolveDisplay(local, null))
    }

    @Test
    fun resolvedName_fallsBackToLocal_whenQueriedBlank() {
        val local = "tactile_image_20261123_2231.bmp"
        assertEquals(local, TactileExportNames.resolveDisplay(local, "   "))
    }

    @Test
    fun resolvedName_keepsLocal_whenQueriedEqualsLocal() {
        val local = "tactile_示意图一_20261123_2231.bmp"
        assertEquals(local, TactileExportNames.resolveDisplay(local, local))
    }

    @Test
    fun resolvedName_usesQueriedNameEvenWithDifferentExtension() {
        val local = "tactile_x_20261123_2231.bmp"
        val queried = "tactile_x_20261123_2231.png"
        assertEquals(queried, TactileExportNames.resolveDisplay(local, queried))
    }
}
