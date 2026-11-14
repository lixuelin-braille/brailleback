package com.googlecode.eyesfree.brailleback.wrapping

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 标题文字规则：章节标题、前后记、英文 Chapter；普通正文不误判。
 */
class HeadingRulesTest {

    @Test
    fun `chapter headings match`() {
        assertTrue(HeadingRules.looksLikeHeading("第一章 初见"))
        assertTrue(HeadingRules.looksLikeHeading("第12节 开始"))
        assertTrue(HeadingRules.looksLikeHeading("第七回"))
        assertTrue(HeadingRules.looksLikeHeading("第三卷 风起"))
        assertTrue(HeadingRules.looksLikeHeading("Chapter 3"))
        assertTrue(HeadingRules.looksLikeHeading("PART IV"))
    }

    @Test
    fun `front and back matter match`() {
        listOf("序", "序言", "跋", "前言", "后记", "尾声", "引言", "结语", "目录").forEach {
            assertTrue(it, HeadingRules.looksLikeHeading(it))
        }
    }

    @Test
    fun `ordinary prose is not a heading`() {
        assertFalse(HeadingRules.looksLikeHeading("这是正文里的一句话。"))
        assertFalse(HeadingRules.looksLikeHeading("一、先看第一个问题"))
        assertFalse(HeadingRules.looksLikeHeading("第"))
    }

    @Test
    fun `long or multiline text is not a heading`() {
        assertFalse(HeadingRules.looksLikeHeading("第一章" + "长".repeat(80)))
        assertFalse(HeadingRules.looksLikeHeading("第一章\n正文"))
    }
}
