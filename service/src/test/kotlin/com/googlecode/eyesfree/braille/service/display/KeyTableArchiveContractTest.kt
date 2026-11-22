/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.braille.service.display

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 键位表压缩包的结构契约。
 *
 * <p>背景：键位表只有真机连接、brltty 编译键表那一刻才被读取，源码测试全仓
 * 都没有校验；上游表换成裁剪版时，如果忘记重打 `keytables.zip`，包里还是
 * 旧表，真机上表现为「改了半天没生效」，从日志里完全看不出来。这里把三件
 * 事钉死：
 *
 * 1. 包内条目数必须与 `tables/keytables` 下的表文件数一致（不许漏表）；
 * 2. 包内 `sk/ntk.ktb` 必须与磁盘上的表逐字节一致（改了表就必须重打包）；
 * 3. 上游参考表只作对照，绝不能被打进包。
 */
class KeyTableArchiveContractTest {

    private fun root(): File {
        val candidates = listOf(File("."), File(".."), File("../.."))
        return candidates.firstOrNull { File(it, "service/tables/keytables").isDirectory }
            ?: error("找不到仓库根（cwd=${System.getProperty("user.dir")}）")
    }

    private fun archive(): File = File(root(), "service/src/main/res/raw/keytables.zip")

    private fun tableDir(): File = File(root(), "service/tables/keytables")

    private fun tableFiles(): List<File> =
        tableDir().walkTopDown()
            .filter { it.isFile && (it.extension == "ktb" || it.extension == "kti") }
            .toList()

    /** 表在包内的条目名，用 '/' 分隔、相对 keytables 目录。 */
    private fun entryName(file: File): String =
        file.relativeTo(tableDir()).invariantSeparatorsPath

    @Test
    fun `包内条目数与表目录文件数一致`() {
        val files = tableFiles()
        assertTrue("表目录里应该有键位表", files.isNotEmpty())
        ZipFile(archive()).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(
                "包内条目数应与表目录文件数一致（tables=${files.size}，包内=${entries.size}）：" +
                    "少一条就说明漏打包，真机上该型号会退回 brltty 内置默认表",
                files.size,
                entries.size,
            )
            val packed = entries.map { it.name }.toSet()
            val missing = files.map { entryName(it) }.filter { it !in packed }
            assertEquals(
                "以下表没被打进包：" + missing.joinToString(),
                emptyList<String>(),
                missing,
            )
        }
    }

    @Test
    fun `包内 sk ntk 表与磁盘表逐字节一致`() {
        val disk = File(tableDir(), "sk/ntk.ktb")
        assertTrue("磁盘上应有裁剪后的 sk/ntk.ktb", disk.isFile)
        ZipFile(archive()).use { zip ->
            val entry = zip.getEntry("sk/ntk.ktb")
                ?: error("包内缺少 sk/ntk.ktb：TSM 3647 会退回默认 chord 表")
            val packed = zip.getInputStream(entry).use { it.readBytes() }
            assertArrayEquals(
                "包内 sk/ntk.ktb 与磁盘表不一致：改了表必须重打 keytables.zip",
                disk.readBytes(),
                packed,
            )
        }
    }

    @Test
    fun `上游参考表不进包`() {
        val upstream = File(tableDir(), "sk/ntk-upstream.ktb.txt")
        assertTrue("上游原表应留在仓库里作对照与回滚依据", upstream.isFile)
        ZipFile(archive()).use { zip ->
            val packed = zip.entries().toList().map { it.name }
            assertFalse(
                "上游参考表是 .txt，不许被打进包（否则包里多一条非表条目）",
                packed.any { it.endsWith("ntk-upstream.ktb.txt") },
            )
        }
    }

    @Test
    fun `裁剪后的表不再依赖 chord`() {
        // 只看生效语句，注释里解释「上游怎么用 chord」不算依赖。
        val directives = File(tableDir(), "sk/ntk.ktb").readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertFalse(
            "裁剪版表不能出现 assign chord：Space 一旦被当修饰键，点位会被吞、routing 变 KEY_FUNCTION",
            directives.any { it.startsWith("assign chord") },
        )
        assertFalse(
            "裁剪版表不能再 include 上游 chord 文件",
            directives.any { it.startsWith("include ") },
        )
        assertTrue(
            "routing 必须恒为 ROUTE",
            directives.any { it == "bind RoutingKey ROUTE" },
        )
    }
}
