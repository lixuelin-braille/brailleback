package com.googlecode.eyesfree.braille.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * 用户分词词库：合并、切分、最长匹配、边界保护与非法词条。
 */
class UserSegDictTest {

    private fun newSegmenter(dictText: String): BrailleSegmenter {
        val dict = JiebaDict()
        dict.init(ByteArrayInputStream(dictText.toByteArray(Charsets.UTF_8)))
        return BrailleSegmenter(dict)
    }

    private fun load(segmenter: BrailleSegmenter, text: String): Int {
        return segmenter.loadUserSegDict(
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    }

    private fun dictOf(vararg words: String): String {
        return words.joinToString("") { "$it 1000\n" }
    }

    @Test
    fun testSinglePieceMergesAcrossJiebaTokens() {
        val segmenter = newSegmenter(dictOf("国", "家"))
        assertEquals("国 家", segmenter.segment("国家").text)

        assertEquals(1, load(segmenter, "国家\n"))
        assertEquals("国家", segmenter.segment("国家").text)
    }

    @Test
    fun testMultiplePiecesSplitOneJiebaToken() {
        val segmenter = newSegmenter(dictOf("国家通用"))
        assertEquals("国家通用", segmenter.segment("国家通用").text)

        assertEquals(1, load(segmenter, "国家通用\t国家 通用\n"))
        assertEquals("国家 通用", segmenter.segment("国家通用").text)
    }

    @Test
    fun testLongestEntryWins() {
        val segmenter = newSegmenter(dictOf("国家", "通用", "国家通用"))
        assertEquals(2, load(segmenter,
            "国家\t国 家\n" +
                "国家通用\t国家 通用\n"))
        // 若只命中较短的「国家」，结果会是「国 家 通用」。
        assertEquals("国家 通用", segmenter.segment("国家通用").text)
    }

    @Test
    fun testDoesNotMatchAcrossTokenBoundary() {
        val segmenter = newSegmenter(dictOf("中国", "家庭"))
        assertEquals("中国 家庭", segmenter.segment("中国家庭").text)

        assertEquals(1, load(segmenter, "国家\n"))
        // 「国家」横跨「中国 / 家庭」，不是任何 token 的起点，不能命中。
        assertEquals("中国 家庭", segmenter.segment("中国家庭").text)
    }

    @Test
    fun testUserRuleOverridesConnect() {
        val segmenter = newSegmenter(dictOf("图书馆", "图书", "馆"))
        assertEquals("图书馆", segmenter.segment("图书馆").text)

        assertEquals(1, load(segmenter, "图书馆\t图书 馆\n"))
        assertEquals("图书 馆", segmenter.segment("图书馆").text)
    }

    @Test
    fun testUserSplitIsNotMergedBySuffixRule() {
        val segmenter = newSegmenter(dictOf("孩子们", "孩子", "们"))
        assertEquals("孩子们", segmenter.segment("孩子们").text)

        assertEquals(1, load(segmenter, "孩子们\t孩子 们\n"))
        assertEquals("孩子 们", segmenter.segment("孩子们").text)
    }

    @Test
    fun testPunctuationAndProtectedRunsUnaffected() {
        val segmenter = newSegmenter(dictOf("国家", "通用"))
        assertEquals(1, load(segmenter, "国家\n"))

        assertEquals("国家，通用", segmenter.segment("国家，通用").text)
        assertEquals(
            "https://example.com/a?b=1",
            segmenter.segment("https://example.com/a?b=1").text)
        assertEquals("3.14", segmenter.segment("3.14").text)
        assertEquals("MP3", segmenter.segment("MP3").text)
    }

    @Test
    fun testInvalidEntriesAreSkipped() {
        val segmenter = newSegmenter(dictOf("甲乙丙"))
        val applied = load(segmenter,
            "\uFEFF# 注释\n" +
                "甲\n" +
                "甲乙\t\n" +
                "甲乙丙\t甲乙 丁\n" +
                "甲 乙丙\t甲乙 丙\n")

        assertEquals(0, applied)
        assertEquals("甲乙丙", segmenter.segment("甲乙丙").text)
    }

    @Test
    fun testLaterEntryOverridesEarlierOne() {
        val segmenter = newSegmenter(dictOf("国家", "国", "家"))
        assertEquals(1, load(segmenter, "国家\t国 家\n"))
        assertEquals("国 家", segmenter.segment("国家").text)

        assertEquals(1, load(segmenter, "国家\t国家\n"))
        assertEquals("国家", segmenter.segment("国家").text)
    }

    @Test
    fun testMatchRequiresSourceAdjacentTokens() {
        val dict = UserSegDict()
        assertEquals(1, dict.load(ByteArrayInputStream(
            "国家\n".toByteArray(Charsets.UTF_8))))
        assertEquals(1, dict.size())

        val adjacent = listOf(
            arrayOf("国", "0", "1"),
            arrayOf("家", "1", "2"))
        val hit = dict.match(adjacent, 0)
        assertNotNull(hit)
        assertEquals(listOf("国家"), hit!!.pieces)
        assertEquals(2, hit.endIndex)

        val withGap = listOf(
            arrayOf("国", "0", "1"),
            arrayOf("家", "2", "3"))
        assertNull(dict.match(withGap, 0))
    }

    @Test
    fun testMappingRoundTripAfterRewrite() {
        val input = "国家通用，中国家庭"
        val segmenter = newSegmenter(dictOf(
            "国家", "通用", "国家通用", "中国", "家庭"))
        assertEquals(1, load(segmenter, "国家通用\t国家 通用\n"))

        val r = segmenter.segment(input)
        assertEquals(input, r.text.filter { it != ' ' })
        assertEquals(input.length, r.origToSeg.size)
        for (i in input.indices) {
            // 标点按既有设计不写入 origToSeg，只校验文字字符。
            if (input[i] == '，') {
                continue
            }
            assertTrue("origToSeg[$i] should be mapped", r.origToSeg[i] >= 0)
        }
    }
}
