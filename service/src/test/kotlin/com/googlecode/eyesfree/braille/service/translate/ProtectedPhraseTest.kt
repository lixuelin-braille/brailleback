package com.googlecode.eyesfree.braille.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/**
 * liblouis 多音字词组保护测试（真实词典）。
 *
 * 这些词组不在 jieba 主词典里，Java 分词器会把它们拆成单字；如果不合并，
 * 词间空格会让 liblouis 的 `always 词组` 规则失效，多音字退回默认读音。
 *
 * 运行：gradle :service:testDebugUnitTest --tests '*ProtectedPhraseTest*'
 */
class ProtectedPhraseTest {

    companion object {
        private const val CONNECTOR = '\u2824'
        private lateinit var dict: JiebaDict
        private lateinit var segmenter: BrailleSegmenter
        private var loaded = false

        private fun locate(name: String): File? {
            val candidates = listOf(
                "assets/$name",
                "service/src/main/assets/$name",
                "../service/src/main/assets/$name",
                "src/main/assets/$name"
            )
            for (c in candidates) {
                val f = File(c)
                if (f.isFile) {
                    return f
                }
            }
            return null
        }

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val dictFile = locate("jiebadt.txt") ?: return
            dict = JiebaDict()
            FileInputStream(dictFile).use { dict.init(it) }
            segmenter = BrailleSegmenter(dict)
            locate("sunshine_split.txt")?.let {
                FileInputStream(it).use { s -> segmenter.loadSplitDict(s) }
            }
            locate("liblouis_phrases.txt")?.let {
                FileInputStream(it).use { s -> segmenter.loadProtectedPhrases(s) }
            }
            locate("sunshine_supplement.txt")?.let {
                FileInputStream(it).use { s -> dict.loadSupplement(s) }
            }
            locate("zh_segment_overrides.txt")?.let {
                FileInputStream(it).use { s -> dict.loadOverrides(s) }
            }
            loaded = true
        }

        private fun check(s: BrailleSegmenter, text: String): String {
            val r = s.segment(text)
            val bare = r.text.filter { it != ' ' && it != CONNECTOR }
            assertTrue(
                "分词丢失/改变了字符: 原文[$text] 结果[${r.text}]",
                bare == text.filter { it != ' ' }
            )
            for (i in text.indices) {
                if (text[i].isWhitespace()) {
                    continue
                }
                val sp = r.origToSeg[i]
                assertTrue("原文位置 $i 未映射: ${r.text}", sp in r.text.indices)
                assertEquals("原文位置 $i 映射到错误字符: ${r.text}", text[i], r.text[sp])
                assertEquals("位置映射不可逆: ${r.text}", i, r.segToOrig[sp])
            }
            println("  「$text」  ->  「${r.text}」")
            return r.text
        }
    }

    private fun assumeLoaded() {
        org.junit.Assume.assumeTrue("真实词典未加载，跳过", loaded)
    }

    @Test
    fun testUnknownPhrasesStayTogether() {
        assumeLoaded()
        // 均不在 jiebadt.txt 中：Java 分词器原本会拆成单字
        assertEquals("肺俞", check(segmenter, "肺俞"))
        assertEquals("俞穴", check(segmenter, "俞穴"))
        assertEquals("阿弥", check(segmenter, "阿弥"))
        assertEquals("纤薄", check(segmenter, "纤薄"))
        assertEquals("薄收", check(segmenter, "薄收"))
        assertEquals("行行出", check(segmenter, "行行出"))
    }

    @Test
    fun testPhraseInsideSentence() {
        assumeLoaded()
        // 「阿弥陀佛」本身是 jieba 词，整体保持连写
        assertEquals("阿弥陀佛", check(segmenter, "阿弥陀佛"))
        // 「肺俞穴」不在词典：保护「肺俞」后与「穴」按词间空格分开
        assertEquals("肺俞 穴", check(segmenter, "肺俞穴"))
    }

    @Test
    fun testOriginalSpaceIsRespected() {
        assumeLoaded()
        assertEquals("肺 俞", check(segmenter, "肺 俞"))
    }

    @Test
    fun testUserSplitOverridesProtection() {
        assumeLoaded()
        val custom = BrailleSegmenter(dict)
        locate("sunshine_split.txt")?.let {
            FileInputStream(it).use { s -> custom.loadSplitDict(s) }
        }
        locate("liblouis_phrases.txt")?.let {
            FileInputStream(it).use { s -> custom.loadProtectedPhrases(s) }
        }
        val rule = "肺俞\t肺 俞\n".toByteArray(Charsets.UTF_8)
        custom.loadUserSegDict(ByteArrayInputStream(rule))
        assertEquals("肺 俞", check(custom, "肺俞"))
    }
}
