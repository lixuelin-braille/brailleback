/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 G1 多行封面分层断言（开关默认关闭）：
 * - 开启后 40×4：封面只占第 1 行（第 2–4 行字节全 0），图形带从第 2 行起摆放，
 *   图形区每页 3 行；totalBands 与封面「共 N 带」一致；封底排在最后。
 * - 关闭时既有整屏三段式输出逐字节不变（回归）。
 */
class TactileStripG1LayoutTest {

    private val spec = ImageStripEncoder.Spec.forDisplay(40, 4, 8)
    private val cells = 40

    private fun inkArray(cols: Int, rows: Int, inkAt: (x: Int, y: Int) -> Boolean): BooleanArray =
        BooleanArray(cols * rows) { i -> inkAt(i % cols, i / cols) }

    private fun rowOf(bytes: ByteArray, line: Int): ByteArray =
        bytes.copyOfRange(line * cells, (line + 1) * cells)

    private fun allZero(bytes: ByteArray): Boolean = bytes.all { it.toInt() == 0 }

    private fun sampleSlice(): SliceResult {
        val ink = inkArray(80, 24) { x, y -> x % 5 == 0 || y % 6 == 0 }
        // G1 图形区按少一行的版面切片。
        val graphicSpec = spec.copy(linesPerScreen = spec.linesPerScreen - 1)
        return TactileStripSlicer.slice(ink, 80, 24, graphicSpec)
    }

    private fun coverBytes(): ByteArray =
        TactileStripCaption.page(listOf("⠁".repeat(cells)), cells, spec.linesPerScreen, 8)

    @Test
    fun g1CoverOnlyFirstLineGraphicFromSecondLine() {
        val slice = sampleSlice()
        val book = TactileStripBookBuilder.build(
            slice, spec, listOf(coverBytes()), listOf(coverBytes()), g1 = true,
        )

        val cover = book.pages.first { it.kind == StripPageKind.COVER }
        // 封面第 2–4 行字节全 0。
        for (line in 1 until spec.linesPerScreen) {
            assertTrue("封面第 $line 行应全 0", allZero(rowOf(cover.bytes, line)))
        }
        // 封面第 1 行保留文案点位。
        assertTrue(rowOf(cover.bytes, 0).any { it.toInt() != 0 })

        val graphics = book.pages.filter { it.kind == StripPageKind.GRAPHIC }
        assertEquals(slice.totalBands, graphics.size)
        assertEquals(slice.totalBands, book.totalBands)

        graphics.forEachIndexed { i, page ->
            // 图形页第 1 行留空。
            assertTrue("图形页 ${i + 1} 第 1 行应全 0", allZero(rowOf(page.bytes, 0)))
            // 第 2–4 行与少一行版面打包的带字节逐位一致。
            val expected = slice.bands[i].bytes
            val lower = page.bytes.copyOfRange(cells, page.bytes.size)
            assertEquals(expected.toList(), lower.toList())
        }

        // 封底排在最后一条图形带之后。
        val back = book.pages.last { it.kind == StripPageKind.BACK }
        assertTrue(book.pages.indexOfLast { it.kind == StripPageKind.GRAPHIC } < book.pages.indexOf(back))
    }

    @Test
    fun g1OffKeepsLegacyWholeScreenBytes() {
        val slice = sampleSlice()
        val cover = listOf(coverBytes())
        val back = listOf(coverBytes())

        // 显式关闭 G1：与不带开关的既有成书结果逐字节一致。
        val legacy = TactileStripBookBuilder.build(slice, spec, cover, back)
        val off = TactileStripBookBuilder.build(slice, spec, cover, back, g1 = false)

        assertEquals(legacy.pages.size, off.pages.size)
        legacy.pages.zip(off.pages).forEach { (a, b) ->
            assertEquals(a.kind, b.kind)
            assertEquals(a.bytes.toList(), b.bytes.toList())
        }
    }

    @Test
    fun g1IgnoredOnSingleLineDevice() {
        // 单行设备：G1 无第二行可让，开关即使打开也不改变既有输出。
        val single = ImageStripEncoder.Spec.forDisplay(40, 1, 8)
        val ink = inkArray(80, 8) { x, y -> x % 5 == 0 }
        val raw = TactileStripSlicer.slice(ink, 80, 8, single)
        val legacy = TactileStripBookBuilder.build(raw, single, listOf(ByteArray(40)), listOf(ByteArray(40)))
        val on = TactileStripBookBuilder.build(raw, single, listOf(ByteArray(40)), listOf(ByteArray(40)), g1 = true)
        assertEquals(legacy.pages.size, on.pages.size)
        legacy.pages.zip(on.pages).forEach { (a, b) ->
            assertEquals(a.bytes.toList(), b.bytes.toList())
        }
    }
}
