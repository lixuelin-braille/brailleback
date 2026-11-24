/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多行设备封面/封底合并成整屏；字段一项不缺；单行设备保持多屏且字段齐全。
 * 屏数按「行数 / linesPerScreen」推算（fake 翻译 1 字符 = 1 方，不二次折行）。
 */
class TactileStripCoverTextTest {

    @Test
    fun multiLineFitsOneScreenAllFieldsPresent() {
        for (cells in listOf(40, 20)) {
            for (dots in listOf(8, 6)) {
                val bandHeight = if (dots == 8) 16 else 12
                val cover = TactileStripCoverText.coverLines(
                    widthCells = cells, bandHeightDots = bandHeight,
                    totalBands = 10, dotsPerCell = dots, rotated = false, maxLines = 4,
                )
                // 4 行屏：封面合并后 ≤4 行 → 1 屏。
                assertTrue("${cells}方${dots}点封面行数 ${cover.size}", cover.size <= 4)
                val joined = cover.joinToString("|")
                assertTrue(joined.contains("图形开始"))
                assertTrue(joined.contains("宽 $cells 方"))
                assertTrue(joined.contains("高 10 带"))
                assertTrue(joined.contains("每带"))
                assertTrue(joined.contains("方向：原图上方在前"))
                assertTrue(joined.contains("共 10 带"))
                assertTrue(joined.contains("按下一页开始"))

                val back = TactileStripCoverText.backLines(
                    widthCells = cells, totalBands = 10, dotsPerCell = dots, maxLines = 4,
                )
                assertTrue("${cells}方${dots}点封底行数 ${back.size}", back.size <= 4)
                val backJoined = back.joinToString("|")
                assertTrue(backJoined.contains("图形结束"))
                assertTrue(backJoined.contains("共 10 带"))
                assertTrue(backJoined.contains("宽 $cells 方"))
                assertTrue(backJoined.contains("返回或换图"))
            }
        }
    }

    @Test
    fun singleLineDeviceKeepsAllFieldsAcrossScreens() {
        val cover = TactileStripCoverText.coverLines(
            widthCells = 40, bandHeightDots = 4, totalBands = 10,
            dotsPerCell = 8, rotated = false, maxLines = 1,
        )
        // 单行物理装不下六项，保持多屏；字段一项不缺。
        assertTrue(cover.size > 1)
        val joined = cover.joinToString("|")
        assertTrue(joined.contains("图形开始"))
        assertTrue(joined.contains("宽 40 方"))
        assertTrue(joined.contains("高 10 带"))
        assertTrue(joined.contains("方向：原图上方在前"))
        assertTrue(joined.contains("共 10 带"))
        assertTrue(joined.contains("按下一页开始"))
    }
}
