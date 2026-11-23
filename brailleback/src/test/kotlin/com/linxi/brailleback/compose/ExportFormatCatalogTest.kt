/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.compose

import com.linxi.brailleback.BrailleExportFormat
import org.junit.Assert.assertEquals
import org.junit.Test

/** 导出格式目录的纯逻辑单测：序号与格式必须一一对应，越界回落 BRF。 */
class ExportFormatCatalogTest {

    @Test
    fun formatsFollowEnumDeclarationOrder() {
        assertEquals(BrailleExportFormat.entries, ExportFormatCatalog.formats)
    }

    @Test
    fun pickIndexMapsToSameFormat() {
        assertEquals(BrailleExportFormat.BRF, ExportFormatCatalog.formatAt(0))
        assertEquals(BrailleExportFormat.BRL, ExportFormatCatalog.formatAt(1))
        assertEquals(BrailleExportFormat.UNICODE_TXT, ExportFormatCatalog.formatAt(2))
        assertEquals(BrailleExportFormat.JSON, ExportFormatCatalog.formatAt(3))
    }

    @Test
    fun outOfRangeIndexFallsBackToBrf() {
        assertEquals(BrailleExportFormat.BRF, ExportFormatCatalog.formatAt(-1))
        assertEquals(BrailleExportFormat.BRF, ExportFormatCatalog.formatAt(4))
        assertEquals(BrailleExportFormat.BRF, ExportFormatCatalog.formatAt(99))
    }

    @Test
    fun everyFormatAppearsExactlyOnce() {
        val labels = ExportFormatCatalog.formats
        assertEquals(4, labels.size)
        assertEquals(labels.toSet().size, labels.size)
        BrailleExportFormat.entries.forEach { format ->
            val picks = BrailleExportFormat.entries.indices.count {
                ExportFormatCatalog.formatAt(it) == format
            }
            assertEquals("格式 $format 在弹窗里只能出现一次", 1, picks)
        }
    }
}
