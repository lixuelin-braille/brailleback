/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.compose

import com.linxi.brailleback.PageGeometry
import org.junit.Assert.assertEquals
import org.junit.Test

/** 导出排版目录的纯逻辑单测：设备方数可用性、菜单项顺序、跟随后的实际版面。 */
class ExportGeometryCatalogTest {

    private val columns = listOf(20, 30, 32, 40)
    private val lines = listOf(20, 25, 28)

    @Test
    fun noDisplayMeansNoFollowItem() {
        val choices = ExportGeometryCatalog.choices(null, columns, lines, 25)
        assertEquals(GeometryChoice.Fixed(20, 20), choices.first())
        assertEquals(GeometryChoice.Custom, choices.last())
        // 4 档方数 × 3 档行数 + 自定义
        assertEquals(13, choices.size)
    }

    @Test
    fun connectedDisplayPutsFollowFirstAndKeepsRestOrder() {
        val choices = ExportGeometryCatalog.choices(32, columns, lines, 25)
        assertEquals(GeometryChoice.FollowDisplay(32, 25), choices[0])
        assertEquals(GeometryChoice.Fixed(20, 20), choices[1])
        assertEquals(14, choices.size)
        assertEquals(GeometryChoice.Custom, choices.last())
    }

    @Test
    fun reportedCellsOutOfRangeIsIgnored() {
        assertEquals(null, ExportGeometryCatalog.followCells(null))
        assertEquals(null, ExportGeometryCatalog.followCells(0))
        assertEquals(null, ExportGeometryCatalog.followCells(-8))
        assertEquals(null, ExportGeometryCatalog.followCells(7))
        assertEquals(8, ExportGeometryCatalog.followCells(8))
        assertEquals(40, ExportGeometryCatalog.followCells(40))
        assertEquals(80, ExportGeometryCatalog.followCells(80))
        assertEquals(null, ExportGeometryCatalog.followCells(81))
        assertEquals(null, ExportGeometryCatalog.followCells(4096))
    }

    @Test
    fun followUsesDeviceCellsAndKeepsSavedLines() {
        val resolved = ExportGeometryCatalog.resolve(
            stored = PageGeometry(40, 25), followEnabled = true, reportedCells = 32)
        assertEquals(PageGeometry(32, 25), resolved)
    }

    @Test
    fun disconnectedOrDisabledFallsBackToStoredLayout() {
        val stored = PageGeometry(30, 28)
        assertEquals(stored, ExportGeometryCatalog.resolve(stored, true, null))
        assertEquals(stored, ExportGeometryCatalog.resolve(stored, true, 0))
        assertEquals(stored, ExportGeometryCatalog.resolve(stored, true, 500))
        // 关掉跟随：即使连着 32 方设备也用固定版面
        assertEquals(stored, ExportGeometryCatalog.resolve(stored, false, 32))
    }

    @Test
    fun followNeverChangesLinesOnlyColumns() {
        val resolved = ExportGeometryCatalog.resolve(
            stored = PageGeometry(20, 20), followEnabled = true, reportedCells = 40)
        assertEquals(40, resolved.columns)
        assertEquals(20, resolved.linesPerPage)
    }
}
