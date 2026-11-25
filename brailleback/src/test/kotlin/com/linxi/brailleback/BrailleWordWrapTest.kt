package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换行 / 翻屏不劈字的测试。
 *
 * 通用盲文一个汉字占 1–3 方、词内连续书写，词间只有空方 U+2800（缩进也用空方）、
 * 段间有 '\n'。旧实现里两处都是「按方数硬切」：
 * - `moveLine` 固定挪 `mDisplayCells` 方 → 下一屏从词中间开始，上一屏只剩半个字；
 * - `screenAt` 每行按 `cellsPerLine` 硬切 → 行尾半个字跑到下一行。
 * 现在两处都先回退到词边界（`wrapEnd` / `nextOffset` / `prevOffset` /
 * `wordStartAtOrBefore`）。
 */
class BrailleWordWrapTest {

    private val blank = '\u2800'

    /** 用互不相同的盲文格拼词，词间一个空方。 */
    private fun wordStream(): String {
        val words = listOf("abcd", "ef", "ghijk", "l", "mnopqrst", "uv")
        return words.joinToString(blank.toString())
    }

    private fun isWordStart(s: String, i: Int): Boolean =
        i <= 0 || s[i - 1] == blank || s[i - 1] == '\n'

    /**
     * 屏首安全：前一格是分隔（词首），或者本格就是分隔（屏首带一个空方，
     * 用于保留缩进层次）。两种都不会把字劈开。
     */
    private fun isSafeStart(s: String, i: Int): Boolean =
        i <= 0 || isWordStart(s, i) || s[i] == blank || s[i] == '\n'

    /** [i] 所在那个词的方数；[i] 若是分隔则返回 0。 */
    private fun wordLengthAt(s: String, i: Int): Int {
        if (i < 0 || i >= s.length) return 0
        if (s[i] == blank || s[i] == '\n') return 0
        var from = i
        while (from > 0 && !isWordStart(s, from)) from--
        var to = i
        while (to + 1 <= s.length - 1 && !isWordStart(s, to + 1)) to++
        return to - from + 1
    }

    /** 从 0 起按 [unit] 走完整条流，返回每屏起点（含末尾补的终点）。 */
    private fun screenStarts(stream: String, unit: Int): List<Int> {
        val out = ArrayList<Int>()
        var off = 0
        out.add(0)
        while (off < stream.length) {
            val next = BrailleFileDecoder.nextOffset(stream, off, unit)
            if (next <= off) break
            out.add(next)
            off = next
        }
        return out
    }

    @Test
    fun `screen starts always land on a word boundary`() {
        val stream = wordStream()
        for (unit in 3..12) {
            val starts = screenStarts(stream, unit)
            for (s in starts) {
                if (s >= stream.length) continue // 末尾终点
                // 只有「词本身比一屏还长」时才允许硬切
                if (wordLengthAt(stream, s) > unit && s > 0 &&
                    !isWordStart(stream, s)) continue
                assertTrue(
                    "unit=$unit start=$s len=${stream.length} starts=$starts 落在词中间",
                    isSafeStart(stream, s))
            }
        }
    }

    @Test
    fun `a screen never ends inside a word shorter than the screen`() {
        val stream = wordStream()
        for (unit in 3..12) {
            val starts = screenStarts(stream, unit)
            for (i in 0 until starts.size - 1) {
                val from = starts[i]
                val to = starts[i + 1]
                assertTrue("单屏超过 unit：[$from,$to)", to - from <= unit)
                if (to >= stream.length) continue
                // 合法屏尾只有两种：末格是分隔（空方/换行），或下一格才是分隔。
                val ok = isWordStart(stream, to) ||
                    stream[to] == blank || stream[to] == '\n' ||
                    // 一整词就比一屏长时只能硬切
                    wordLengthAt(stream, to) >= unit
                assertTrue("屏 [$from,$to) 把词劈开", ok)
            }
        }
    }

    @Test
    fun `prevOffset goes back to a word start without overshooting`() {
        val stream = wordStream()
        val unit = 6
        for (off in 1..stream.length) {
            val prev = BrailleFileDecoder.prevOffset(stream, off, unit)
            assertTrue("prev 必须落在词首：$prev", isWordStart(stream, prev))
            assertTrue("prev 不能越过 off：$prev > $off", prev <= off)
            assertTrue("prev 退得过多：off=$off prev=$prev", off - prev <= unit + 8)
        }
    }

    @Test
    fun `wordStartAtOrBefore snaps inside a word to its head`() {
        val stream = wordStream()
        assertEquals(5, BrailleFileDecoder.wordStartAtOrBefore(stream, 5))
        assertEquals(5, BrailleFileDecoder.wordStartAtOrBefore(stream, 6))
        assertEquals(0, BrailleFileDecoder.wordStartAtOrBefore(stream, 0))
    }

    @Test
    fun `screenAt keeps every line within width and loses no cell`() {
        val stream = "\u2801\u2801\u2803\u2803\u2800" + "\u2807".repeat(5)
        val screen = BrailleFileDecoder.screenAt(stream, 0, 4, 5)
        val lines = screen.split('\n')
        for (line in lines) {
            assertTrue("行超宽：${line.length}", line.length <= 4)
        }
        // 不丢方：各行（去掉回行缩进带来的空方前缀也算内容）拼回原文
        assertTrue(screen.replace("\n", "").length >= stream.length)
    }

    @Test
    fun `cutAtBoundary keeps previews on whole words`() {
        val s = "ab${blank}cd${blank}efgh"
        assertEquals("ab$blank", BrailleFileDecoder.cutAtBoundary(s, 3))
        assertEquals(s, BrailleFileDecoder.cutAtBoundary(s, 100))
        assertEquals("", BrailleFileDecoder.cutAtBoundary(s, 0))
    }

    @Test
    fun `long word longer than screen is hard cut`() {
        val s = "a".repeat(30)
        assertEquals(10, BrailleFileDecoder.nextOffset(s, 0, 10))
    }

    @Test
    fun `paragraph newline is skipped so next screen starts a new paragraph`() {
        val stream = "abc\nde"
        val next = BrailleFileDecoder.nextOffset(stream, 0, 3)
        assertEquals(4, next)
        assertEquals('d', stream[next])
    }

    /**
     * 行进度条依赖 [BrailleFileDecoder.lineStarts] 与 `screenAt` 完全对齐：
     * 行数必须等于屏幕上的行数，每行起点必须递增且落在词边界上。
     */
    @Test
    fun `lineStarts matches the lines screenAt renders`() {
        val stream = wordStream() + "\n" + wordStream()
        for (cells in 3..12) {
            for (maxLines in 1..8) {
                val starts = BrailleFileDecoder.lineStarts(stream, 0, cells, maxLines)
                val screen = BrailleFileDecoder.screenAt(stream, 0, cells, maxLines)
                val rendered = screen.split('\n').size
                assertEquals(
                    "cells=$cells maxLines=$maxLines 行数不一致",
                    rendered, starts.size)
                assertEquals(0, starts[0])
                for (i in 1 until starts.size) {
                    assertTrue("行起点未递增：$i", starts[i] > starts[i - 1])
                    // 只有「词本身比一行还长」时才允许从词中间开始
                    if (!isSafeStart(stream, starts[i])) {
                        assertTrue(
                            "第 $i 行起点 ${starts[i]} 落在词中间",
                            wordLengthAt(stream, starts[i]) >= cells)
                    }
                }
            }
        }
    }

    /** 页内行导航不能越过本页终点（`endLimit`）。 */
    @Test
    fun `lineStarts stops at endLimit`() {
        val stream = wordStream() + "\n" + wordStream()
        val starts = BrailleFileDecoder.lineStarts(stream, 0, 5, 10, 12)
        for (s in starts) assertTrue("行起点越过本页终点：$s", s < 12)
    }
}
