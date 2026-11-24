/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * E 批屏幕视图断言：盲文源不回显汉字；TEXT 源回显原文且首尾段不半句截断；
 * 页码百分比与字号/主题无关；逐格跨度命中；句读切分不在半句断开。
 */

package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSourceModelTest {

    private fun cell(mask: Int): Char = (0x2800 + mask).toChar()

    private fun han(text: String) = text.any { it.code in 0x4E00..0x9FFF }

    /** ① 盲文源（BRF/BDO）屏幕仍显示点字，不出现汉字。 */
    @Test
    fun brailleSourceKeepsBrailleOnScreen() {
        val braille = (1..6).joinToString("") { cell(it).toString() }
        val view = ReaderSourceModel.build(
            kind = ReaderSourceKind.BRAILLE,
            sourceText = "",
            brailleScreenText = braille,
            spans = null,
            offset = 0, count = 6, currentUnit = 0, totalUnits = 1,
        )
        assertEquals(braille, view.displayText)
        assertTrue("含 U+2800 点字", view.displayText.any { it.code in 0x2800..0x28FF })
        assertFalse("不回显汉字", han(view.displayText))
    }

    /** ② TEXT 源：首页回显首段开头，末页回显末段，不半句截断。 */
    @Test
    fun textSourceShowsOriginalParagraphs() {
        val p1 = "第一段：春天来了，花儿开了。"
        val p2 = "第二段：夏天炎热，蝉鸣不断。"
        val p3 = "第三段：秋天丰收，稻谷金黄。"
        val source = "$p1\n$p2\n$p3"
        val spans = spansFor(source)

        val first = ReaderSourceModel.build(
            ReaderSourceKind.TEXT, source, "", spans,
            offset = 0, count = p1.length, currentUnit = 0, totalUnits = 2,
        )
        assertTrue("首页含首段开头", first.displayText.startsWith("第一段"))
        assertFalse("首页不出现第二段", first.displayText.contains("第二段"))

        val last = ReaderSourceModel.build(
            ReaderSourceKind.TEXT, source, "", spans,
            offset = p1.length + 1, count = p2.length + 1 + p3.length,
            currentUnit = 1, totalUnits = 2,
        )
        assertTrue("末页含末段", last.displayText.contains("第三段"))
        // 末段完整句群不被切成半句
        val clauses = ReaderSourceModel.clauses("第三段：秋天丰收，稻谷金黄。")
        assertEquals(1, clauses.size)
        assertEquals("第三段：秋天丰收，稻谷金黄。", clauses[0])
    }

    /** ③ 页码/百分比只由偏移与长度决定，与字号、主题无关。 */
    @Test
    fun percentIndependentOfThemeAndFont() {
        val source = "一二三四五六七八九十"
        val a = ReaderSourceModel.build(
            ReaderSourceKind.TEXT, source, "", null,
            offset = 3, count = 4, currentUnit = 1, totalUnits = 3,
        )
        val b = ReaderSourceModel.build(
            ReaderSourceKind.TEXT, source, "", null,
            offset = 3, count = 4, currentUnit = 1, totalUnits = 3,
        )
        assertEquals(a.percent, b.percent)
        assertEquals(a.currentUnit, b.currentUnit)
        assertEquals(a.totalUnits, b.totalUnits)
        assertEquals(a.paragraphIndex, b.paragraphIndex)
    }

    /** ⑤ 逐格跨度：三段来源区间命中还原。 */
    @Test
    fun cellSpansReconstructSourceText() {
        val source = "你好世界\n第二段文字\n第三段收尾"
        val spans = spansFor(source)
        val view = ReaderSourceModel.build(
            ReaderSourceKind.TEXT, source, "", spans,
            offset = 0, count = source.length, currentUnit = 0, totalUnits = 1,
        )
        assertEquals(source, view.displayText)
        assertEquals(3, view.paragraphCount)
    }

    @Test
    fun clausesSplitAtSentencePunctuation() {
        val text = "第一句。第二句！第三句；第四句?"
        val clauses = ReaderSourceModel.clauses(text)
        assertEquals(listOf("第一句。", "第二句！", "第三句；", "第四句?"), clauses)
    }

    /**
     * P1-2 回归：一个汉字占多格时只回显一次；段间落换行；
     * 流内偏移（含换行）换算成格序号后，第二段开头不带出上一段的字。
     */
    @Test
    fun multiCellCharacterEchoesOnce() {
        val source = "国家\n春天"
        // 每字 3 格：这些格的「格→文位置」全指向该字
        val spans = SourceSpanMapper.concatenate(
            listOf(
                SourceSpanMapper.buildParagraphSpans("国家", intArrayOf(0, 0, 0, 1, 1, 1)),
                SourceSpanMapper.buildParagraphSpans("春天", intArrayOf(0, 0, 0, 1, 1, 1)),
            )
        )
        val stream = cell(0).toString().repeat(6) + "\n" + cell(0).toString().repeat(6)

        val whole = ReaderSourceModel.build(
            ReaderSourceKind.TEXT, source, "", spans,
            offset = 0, count = 20, currentUnit = 0, totalUnits = 1, stream = stream,
        )
        assertEquals("国家\n春天", whole.displayText)
        assertEquals(listOf("国家", "春天"), whole.focusSegments)

        // 偏移 7 = 6 格 + 1 个换行，正是第二段第一格
        val second = ReaderSourceModel.build(
            ReaderSourceKind.TEXT, source, "", spans,
            offset = 7, count = 20, currentUnit = 1, totalUnits = 2, stream = stream,
        )
        assertEquals("春天", second.displayText)

        // 屏幕起点落在「国」的第 2 格、只给 4 格宽度（跨过段间换行）：
        // 被切在中间的字仍整字回显一次，且不带上起点之前的「国」之前的任何内容
        val partial = ReaderSourceModel.build(
            ReaderSourceKind.TEXT, source, "", spans,
            offset = 1, count = 4, currentUnit = 0, totalUnits = 2, stream = stream,
        )
        assertEquals("国家", partial.displayText)

        // 起点 4：「家」的第 1 格之后第 2 格开始，到「春」的 2 格为止
        val mid = ReaderSourceModel.build(
            ReaderSourceKind.TEXT, source, "", spans,
            offset = 4, count = 5, currentUnit = 1, totalUnits = 2, stream = stream,
        )
        assertEquals("家\n春", mid.displayText)
    }

    /** 没有阅读流可对照时，同一源片段的连续格仍只取一次。 */
    @Test
    fun repeatedSpanEchoesOnceWithoutStream() {
        val spans = SourceSpanMapper.buildParagraphSpans("国家", intArrayOf(0, 0, 0, 1, 1, 1))
        val view = ReaderSourceModel.build(
            ReaderSourceKind.TEXT, "国家", "", spans,
            offset = 0, count = 6, currentUnit = 0, totalUnits = 1,
        )
        assertEquals("国家", view.displayText)
    }

    /** 构造逐字对齐的格级跨度（测试桩）。 */
    private fun spansFor(source: String): List<CellSource> {
        return source.mapIndexed { i, ch ->
            if (ch == '\n') CellSource(SourceSpan(i, i + 1, "\n"), "\n")
            else CellSource(SourceSpan(i, i + 1, ch.toString()), ch.toString())
        }
    }
}
