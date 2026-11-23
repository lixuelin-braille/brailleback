/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 「导出为盲文」契约：SAF 走 CreateDocument 契约、Manifest 不加存储权限、
 * 导出文案双侧齐全。
 */

package com.linxi.brailleback

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrailleReaderExportContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()

    private val base = "brailleback/"

    @Test
    fun registersCreateDocumentLaunchers() {
        val activity = text(base + "src/main/java/com/linxi/brailleback/BrailleReaderActivity.kt")
        assertTrue("导出必须注册 CreateDocument 契约",
            activity.contains("ActivityResultContracts.CreateDocument("))
        assertTrue("BRF 用 octet-stream",
            activity.contains("\"application/octet-stream\""))
        assertTrue("文本类用 text/plain", activity.contains("\"text/plain\""))
    }

    @Test
    fun manifestDeclaresNoStoragePermission() {
        val manifest = text(base + "src/main/AndroidManifest.xml")
        listOf(
            "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE",
            "MANAGE_EXTERNAL_STORAGE",
        ).forEach { permission ->
            assertFalse("导出不得申请存储权限：$permission",
                manifest.contains(permission))
        }
    }

    @Test
    fun exportKeysLocalizedInBothLocales() {
        val english = text(base + "src/main/res/values/strings.xml")
        val chinese = text(base + "src/main/res/values-zh-rCN/strings.xml")
        listOf(
            "braille_reader_export", "braille_reader_export_title",
            "braille_reader_export_fmt_brf", "braille_reader_export_fmt_brl",
            "braille_reader_export_fmt_utf", "braille_reader_export_preparing",
            "braille_reader_export_done", "braille_reader_export_fail",
            "braille_reader_export_cancelled", "braille_reader_export_empty",
            "braille_reader_export_warn_unmappable",
            "braille_reader_export_warn_dropped",
        ).forEach { name ->
            val pattern = """<string name="$name">""".toRegex()
            assertTrue("英文缺少 $name", pattern.containsMatchIn(english))
            assertTrue("中文缺少 $name", pattern.containsMatchIn(chinese))
        }
    }
}
