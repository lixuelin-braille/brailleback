/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

import com.googlecode.eyesfree.utils.LogFileWriter
import com.linxi.brailleback.utils.DebugLogActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * 导出硬上限（256 KB 尾部截尾）与启动自愈（超规格旧分片归档）的纯逻辑断言。
 *
 * 不触碰 Context / Intent；Binder 超限与 FileProvider 接线在真机复核。
 */
class LogExportCapTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    @Test
    fun exportCapIs256Kilobytes() {
        assertEquals(256 * 1024, LogFileWriter.EXPORT_MAX_BYTES)
    }

    @Test
    fun tailByBytesKeepsTailWithinByteLimit() {
        val lines = (1..300).map { "line-$it padding-pad-pad" }
        val full = lines.joinToString("\n")
        val capped = LogFileWriter.tailByBytes(full, 1024)

        val bytes = capped.toByteArray(StandardCharsets.UTF_8)
        assertTrue("截尾后不得超过字节上限", bytes.size <= 1024)
        assertEquals("尾部区间最后一行应为原文最后一行", lines.last(), capped.lines().last())
        assertFalse("截尾后不应保留最早一行", capped.contains(lines.first()))
        // 留存窗口起点应明显晚于开头。
        assertTrue("留存区间应落在尾部", capped.lines().first().let { it > "line-100" })
    }

    @Test
    fun tailByBytesUnderLimitReturnsOriginal() {
        val text = "a\nb\nc"
        assertEquals(text, LogFileWriter.tailByBytes(text, 256 * 1024))
    }

    @Test
    fun cappedExportNeverExceeds256Kilobytes() {
        val huge = (1..10_000).joinToString("\n") { "entry-$it " + "x".repeat(80) }
        val out = LogFileWriter.tailByBytes(huge, LogFileWriter.EXPORT_MAX_BYTES)
        assertTrue(
            "导出文本字节数不得超过 256 KB",
            out.toByteArray(StandardCharsets.UTF_8).size <= LogFileWriter.EXPORT_MAX_BYTES,
        )
        assertTrue("超长文本应被截到尾部", out.contains("entry-10000"))
    }

    @Test
    fun healArchivesOversizedShardsAndLeavesOtherFilesAlone() {
        val directory = Files.createTempDirectory("heal-shards").toFile()
        try {
            val mainName = "brailleback.log"
            writeShard(directory, "$mainName.1", 500)      // 规格内，保留
            writeShard(directory, "$mainName.2", 2_000)    // 超规格，归档
            writeShard(directory, "$mainName.3", 3_000)     // 超规格，归档
            writeShard(directory, "brailleback-service.log", 5_000) // 旧版连接日志，不碰
            writeShard(directory, "bookshelf.xml", 4_000)  // 书籍/偏好类文件，不碰

            val moved = LogFileWriter.healOversizedRotations(
                directory, mainName, maxBytes = 1_000, legacyCapBytes = 2_000_000, now = 1_000L,
            )

            assertEquals("两份超规格分片都应被归档", 2, moved.size)
            assertTrue("规格内分片 .1 保留", File(directory, "$mainName.1").isFile)
            assertFalse("超规格 .2 已移走", File(directory, "$mainName.2").exists())
            assertFalse("超规格 .3 已移走", File(directory, "$mainName.3").exists())
            assertTrue("旧版连接日志不被清理", File(directory, "brailleback-service.log").isFile)
            assertTrue("书籍文件不被清理", File(directory, "bookshelf.xml").isFile)

            val archives = directory.listFiles { f -> f.name.startsWith("$mainName.legacy-") }
                ?: emptyArray()
            assertEquals("应产生两份归档", 2, archives.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun healPrunesLegacyArchivesOverCapOldestFirst() {
        val directory = Files.createTempDirectory("heal-legacy-cap").toFile()
        try {
            val mainName = "brailleback.log"
            writeShard(directory, "$mainName.2", 2_000)
            writeShard(directory, "$mainName.3", 3_000)

            LogFileWriter.healOversizedRotations(
                directory, mainName, maxBytes = 1_000, legacyCapBytes = 2_500, now = 1_000L,
            )

            val archives = directory.listFiles { f -> f.name.startsWith("$mainName.legacy-") }
                ?: emptyArray()
            val total = archives.sumOf { it.length() }
            assertTrue("归档总量不得超过 2 MB 口径的小上限", total <= 2_500)
            assertTrue("超上限后应丢弃最旧归档", archives.size < 2)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun copyKeepsLast200LinesAndCopiesLineCapConstant() {
        assertEquals(200, DebugLogActions.COPY_MAX_LINES)
        val full = (1..300).joinToString("\n") { "line-$it" }
        val clip = DebugLogActions.tailLines(full, DebugLogActions.COPY_MAX_LINES)
        assertEquals("复制只保留最近 200 行", 200, clip.lines().size)
        assertEquals("末行应为原文最后一行", "line-300", clip.lines().last())
        assertFalse("最早一行不应进入剪贴板", clip.contains("line-1\n"))
        val short = "a\nb\nc"
        assertEquals("未超行数原样返回", short, DebugLogActions.tailLines(short, 200))
    }

    @Test
    fun copyAndShareTruncationStringsMention256Kilobytes() {
        val strings = projectFile("brailleback/src/main/res/values/strings.xml").readText()
        assertTrue("复制截断文案应说明仅含最近 256 KB", strings.contains("debug_log_copy_truncated"))
        assertTrue(strings.contains("256 KB"))
        assertTrue("应有分享截断文案", strings.contains("debug_log_share_truncated"))
    }

    private fun writeShard(dir: File, name: String, bytes: Int) {
        File(dir, name).writeText("x".repeat(bytes), StandardCharsets.UTF_8)
    }
}
