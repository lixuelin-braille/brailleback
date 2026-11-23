/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 点位 JSON 契约：文档级元信息、逐格字段（dots/mask/line/column/role/
 * source_span/source_text）、1 起坐标、手写 JSON 往返解析。
 */

package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportDocumentTest {

    private fun cell(dots: Int): Char = (0x2800 + dots).toChar()

    @Test
    fun documentCarriesSchemaTitleGeometryAndPageCount() {
        // 3 行点 1，每行 10 方；几何 20 方 × 25 行 → 1 页。
        val stream = (1..3).joinToString("\n") {
            (1..10).joinToString("") { cell(1).toString() }
        }
        val geometry = PageGeometry(20, 25)
        val doc = ExportDocument.build(stream, "测试书", geometry)

        assertEquals(ExportDocument.SCHEMA_VERSION, doc.schemaVersion)
        assertEquals("测试书", doc.title)
        assertEquals(20, doc.geometry.columns)
        assertEquals(25, doc.geometry.linesPerPage)
        assertEquals(1, doc.pageCount)
        assertEquals(30, doc.cells.size)
    }

    @Test
    fun cellFieldsDotsMaskRoleAndOneBasedCoordinates() {
        // 第一个点 1（mask=1），第二个空方（mask=0, space）。
        val stream = cell(1).toString() + cell(0)
        val doc = ExportDocument.build(stream, "book", PageGeometry(40, 25))

        val ink = doc.cells[0]
        assertEquals(listOf(1), ink.dots)
        assertEquals(1, ink.mask)
        assertEquals(1, ink.line)
        assertEquals(1, ink.column)
        assertEquals(ExportDocument.ROLE_INK, ink.role)
        assertNull(ink.sourceSpan)
        assertNull(ink.sourceText)

        val space = doc.cells[1]
        assertEquals(emptyList<Int>(), space.dots)
        assertEquals(0, space.mask)
        assertEquals(1, space.line)
        assertEquals(2, space.column)
        assertEquals(ExportDocument.ROLE_SPACE, space.role)
    }

    @Test
    fun coordinatesWrapByGeometryColumns() {
        // 5 个点 1，几何 2 方一行 → 行1: 列1,2；行2: 列1,2；行3: 列1。
        val stream = (1..5).joinToString("") { cell(1).toString() }
        val doc = ExportDocument.build(stream, "book", PageGeometry(2, 25))

        assertEquals(5, doc.cells.size)
        // 行1: 列1,2；行2: 列1,2；行3: 列1
        assertEquals(1, doc.cells[0].column)
        assertEquals(1, doc.cells[0].line)
        assertEquals(2, doc.cells[1].column)
        assertEquals(1, doc.cells[1].line)
        assertEquals(1, doc.cells[2].column)
        assertEquals(2, doc.cells[2].line)
    }

    @Test
    fun jsonRoundTripPreservesAllFields() {
        val stream = (1..3).joinToString("\n") {
            (1..8).joinToString("") { cell(3).toString() }
        }
        val doc = ExportDocument.build(stream, "往返书", PageGeometry(30, 20))

        val json = doc.toJson()
        val restored = ExportDocument.fromJson(json)

        assertEquals(doc.schemaVersion, restored.schemaVersion)
        assertEquals(doc.title, restored.title)
        assertEquals(doc.geometry.columns, restored.geometry.columns)
        assertEquals(doc.geometry.linesPerPage, restored.geometry.linesPerPage)
        assertEquals(doc.pageCount, restored.pageCount)
        assertEquals(doc.cells.size, restored.cells.size)
        doc.cells.forEachIndexed { i, expected ->
            val actual = restored.cells[i]
            assertEquals(expected.dots, actual.dots)
            assertEquals(expected.mask, actual.mask)
            assertEquals(expected.line, actual.line)
            assertEquals(expected.column, actual.column)
            assertEquals(expected.role, actual.role)
            assertEquals(expected.sourceSpan, actual.sourceSpan)
            assertEquals(expected.sourceText, actual.sourceText)
        }
    }

    @Test
    fun jsonContainsBrailixAlignedFieldNames() {
        val doc = ExportDocument.build(cell(1).toString(), "book", PageGeometry(40, 25))
        val json = doc.toJson()
        assertTrue(json.contains("\"dots\""))
        assertTrue(json.contains("\"mask\""))
        assertTrue(json.contains("\"line\""))
        assertTrue(json.contains("\"column\""))
        assertTrue(json.contains("\"role\""))
        assertTrue(json.contains("\"source_span\""))
        assertTrue(json.contains("\"source_text\""))
        assertTrue(json.contains("\"schemaVersion\""))
    }

    @Test
    fun jsonExportCountsPrintCharactersDropped() {
        val result = BrailleExporter.export(
            "中" + cell(1),
            BrailleExportFormat.JSON,
            PageGeometry(40, 25),
            "book",
        )
        assertEquals(1, result.droppedChars)
        val doc = ExportDocument.fromJson(result.content)
        assertEquals(1, doc.cells.size)
    }
}
