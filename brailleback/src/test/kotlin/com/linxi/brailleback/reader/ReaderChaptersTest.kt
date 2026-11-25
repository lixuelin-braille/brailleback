/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 目录章节切分契约：认「第X章」「序言」「尾声」这类标题行、记全书字符下标；
 * 正文里出现的「第三章的内容」不算标题；目录页与正文重复的标题只留一条。
 */

package com.linxi.brailleback.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderChaptersTest {

    @Test
    fun detectsNumberedChaptersWithGlobalOffsets() {
        val text = "第一章 落难\n正文内容\n第二章 出山\n正文"
        val chapters = ReaderChapters.detect(text)

        assertEquals(2, chapters.size)
        assertEquals("第一章 落难", chapters[0].title)
        assertEquals(0, chapters[0].startChar)
        assertEquals("第二章 出山", chapters[1].title)
        // "第一章 落难"(6) + \n + "正文内容"(4) + \n = 12
        assertEquals(12, chapters[1].startChar)
    }

    @Test
    fun longBodyLineMentioningChapterIsNotHeading() {
        val text = "第三章的内容如下，我们接着讲主角如何进城，以及他遇到的那位老人。\n第四章 归途"
        val chapters = ReaderChapters.detect(text)

        assertEquals(1, chapters.size)
        assertEquals("第四章 归途", chapters[0].title)
    }

    @Test
    fun detectsNamedHeadings() {
        val text = "序言\n正文\n尾声\n正文"
        val chapters = ReaderChapters.detect(text)

        assertEquals(listOf("序言", "尾声"), chapters.map { it.title })
    }

    @Test
    fun duplicateTocTitleKeptOnce() {
        val text = "第一章 落难\n第一章 落难\n正文"
        val chapters = ReaderChapters.detect(text)

        assertEquals(1, chapters.size)
        assertEquals(0, chapters[0].startChar)
    }

    @Test
    fun emptyOrUnstructuredTextYieldsNothing() {
        assertTrue(ReaderChapters.detect("").isEmpty())
        assertTrue(ReaderChapters.detect("一段没有任何标题的正文，\n只有几行。").isEmpty())
    }

    @Test
    fun entriesAreCapped() {
        val sb = StringBuilder()
        repeat(400) { i -> sb.append("第").append(i + 1).append("章 标题\n正文\n") }
        val chapters = ReaderChapters.detect(sb.toString())
        assertTrue("目录条目要有上限", chapters.size <= 300)
    }
}
