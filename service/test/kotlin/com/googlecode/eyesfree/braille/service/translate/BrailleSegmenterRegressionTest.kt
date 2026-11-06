package com.googlecode.eyesfree.braille.service.translate

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * 回归测试：验证 Kotlin 迁移后的 JiebaDict + BrailleSegmenter
 * 与原 Java 版行为一致（分词规则、位置映射、特殊场景保护）。
 *
 * 在 JVM 上运行：`gradle :service:testDebugUnitTest`
 */
class BrailleSegmenterRegressionTest {

    companion object {
        private lateinit var segmenter: BrailleSegmenter
        private lateinit var dict: JiebaDict

        @BeforeClass
        @JvmStatic
        fun setUp() {
            dict = JiebaDict()
            // 用内嵌迷你词库初始化，避免依赖 Android assets，保证 JVM 可跑
            val miniDict = buildString {
                append("我 1000\n")
                append("我们 500\n")
                append("爱 800\n")
                append("祖国 600\n")
                append("中华 700\n")
                append("人民 900\n")
                append("共和国 800\n")
                append("中国 1000\n")
                append("共产党 1000\n")
                append("科学 800\n")
                append("孩子 600\n")
                append("们 400\n")
                append("北京 1000\n")
                append("奥运会 1000\n")
                append("成功 800\n")
                append("举办 800\n")
                append("学习 900\n")
                append("汉语 800\n")
                append("拼音 700\n")
                append("很好 600\n")
                append("山 1000\n")
                append("上 1000\n")
                append("鸟 800\n")
                append("语 800\n")
                append("花 800\n")
                append("香 800\n")
                append("水 800\n")
                append("秀 800\n")
                append("风 800\n")
                append("和 800\n")
                append("日 800\n")
                append("丽 800\n")
                append("一 900\n")
                append("二 900\n")
                append("三 900\n")
                append("分 700\n")
                append("之 700\n")
                append("一丘 200\n")
                append("貉 200\n")
                append("风马牛 100\n")
                append("不相及 100\n")
                append("牛 500\n")
                append("不相 200\n")
                append("及 500\n")
                append("高锰酸钾 100\n")
                append("研究生 200\n")
                append("院 200\n")
                append("深 500\n")
                append("呼吸 700\n")
                append("书 900\n")
                append("目 900\n")
                append("声 800\n")
                append("鹤 400\n")
                append("唳 400\n")
                append("草 800\n")
                append("木 800\n")
                append("皆 800\n")
                append("兵 800\n")
                append("无 500\n")
                append("缝 500\n")
                append("钢 500\n")
                append("管 500\n")
                append("劳 500\n")
                append("动 500\n")
                append("模 500\n")
                append("范 500\n")
                append("苹果 800\n")
                append("香蕉 800\n")
                append("果汁 800\n")
                append("水分子 500\n")
                append("水 1000\n")
                append("分子 800\n")
                append("二分之一 500\n")
                append("米每秒 500\n")
                append("图书馆 900\n")
                append("好 800\n")
                append("看 800\n")
                append("吗 600\n")
            }
            val stream: InputStream = ByteArrayInputStream(miniDict.toByteArray(Charsets.UTF_8))
            dict.init(stream)
            segmenter = BrailleSegmenter(dict)
        }
    }

    // ------------------------------------------------------------------
    // 基础：普通句子分词，词间应有空格
    // ------------------------------------------------------------------

    @Test
    fun testPlainSentenceSpacing() {
        val r = segmenter.segment("我们爱祖国")
        assertNotNull(r)
        // 应切为 "我们 爱 祖国"
        assertEquals("我们 爱 祖国", r.text)
        // 位置映射长度与原文本一致
        assertEquals(5, r.origToSeg.size)
    }

    @Test
    fun testMappingsConsistent() {
        val r = segmenter.segment("我爱中国")
        assertEquals("我 爱 中国", r.text)
        // origToSeg 每个原字符都应映射到分段文本中的位置
        for (i in r.origToSeg.indices) {
            assertTrue("origToSeg[$i] should >= 0", r.origToSeg[i] >= 0)
        }
        // segToOrig 反向映射
        val seg = r.text
        for (i in seg.indices) {
            if (seg[i] != ' ') {
                assertTrue("segToOrig[$i] should >= 0", r.segToOrig[i] >= 0)
            }
        }
    }

    // ------------------------------------------------------------------
    // 特殊场景：书名号内连写
    // ------------------------------------------------------------------

    @Test
    fun testBookTitleNoSplit() {
        val r = segmenter.segment("《红楼梦》很好看")
        assertNotNull(r)
        // 书名号内不空格，书名整体保留
        assertTrue(r.text.startsWith("《红楼梦》"))
    }

    // ------------------------------------------------------------------
    // 数字整体保护
    // ------------------------------------------------------------------

    @Test
    fun testNumberNotSplit() {
        val r = segmenter.segment("数量是12345个")
        assertNotNull(r)
        // 数字不切分
        assertTrue(r.text.contains("12345"))
        // 12345 中不应出现空格（即原字符 5 个连续无空格）
        val idx = r.text.indexOf("12345")
        assertTrue(idx >= 0)
        // 数字后跟"个"是词（数字 token 之后是独立"个"词）
        val after = r.text.substring(idx + 5).trim()
        assertTrue(after.startsWith("个"))
    }

    @Test
    fun testDecimalNumberNotSplit() {
        val r = segmenter.segment("价格是3.14元")
        assertNotNull(r)
        assertTrue(r.text.contains("3.14"))
    }

    // ------------------------------------------------------------------
    // 化学式保护
    // ------------------------------------------------------------------

    @Test
    fun testChemFormula() {
        val r = segmenter.segment("化学式是H2O")
        assertNotNull(r)
        // H2O 应整体保留，不拆成 H 2 O
        assertTrue("H2O should be kept together, got: ${r.text}", r.text.contains("H2O"))
    }

    @Test
    fun testAsciiAlnumIdentifiersNotSplit() {
        for (word in listOf("MP3", "B2B", "C3PO", "COVID-19")) {
            assertEquals(word, segmenter.segment(word).text)
        }
        assertTrue(segmenter.segment("A4纸").text.startsWith("A4 "))
        assertTrue(segmenter.segment("3D打印").text.startsWith("3D "))
    }

    @Test
    fun testFullWidthNumbersNotSplit() {
        assertEquals("１２３", segmenter.segment("１２３").text)
        assertEquals("－５", segmenter.segment("－５").text)
        assertTrue(segmenter.segment("价格是１，２３４．５％元").text
            .contains("１，２３４．５％"))
    }

    @Test
    fun testUrlAndEmailNotSplit() {
        assertEquals(
            "https://example.com/a?b=1",
            segmenter.segment("https://example.com/a?b=1").text
        )
        assertEquals(
            "support@example.com",
            segmenter.segment("support@example.com").text
        )
        assertEquals(
            "www.example.com",
            segmenter.segment("www.example.com").text
        )
        assertTrue(
            segmenter.segment("访问https://example.com/a?b=1后").text
                .contains("https://example.com/a?b=1")
        )
    }

    // ------------------------------------------------------------------
    // 专名词表强制成词
    // ------------------------------------------------------------------

    @Test
    fun testProperNameKeptTogether() {
        // 苹果/香蕉 是内置专名示例词（词典有），应连写
        val r = segmenter.segment("我喜欢吃苹果")
        assertNotNull(r)
        assertTrue(r.text.contains("苹果"))
    }

    // ------------------------------------------------------------------
    // 应连写的词（CONNECT）
    // ------------------------------------------------------------------

    @Test
    fun testConnectWords() {
        val r = segmenter.segment("图书馆很好")
        assertNotNull(r)
        // "图书馆" 在 CONNECT 表中强制成词，不应拆
        assertTrue("图书馆 should be one word, got: ${r.text}", r.text.contains("图书馆"))
        // "很好" 也强制成词
        assertTrue(r.text.contains("很好"))
    }

    @Test
    fun testVeryGoodBeforeAdjective() {
        val localDict = JiebaDict()
        localDict.init(ByteArrayInputStream(
            "很 700\n好 800\n看 800\n好看 900\n".toByteArray(Charsets.UTF_8)))
        val localSegmenter = BrailleSegmenter(localDict)
        assertEquals("很好", localSegmenter.segment("很好").text)
        assertEquals("很 好看", localSegmenter.segment("很好看").text)
    }

    // ------------------------------------------------------------------
    // 应分写的四字词（SPLIT4）
    // ------------------------------------------------------------------

    @Test
    fun testSplit4Words() {
        // 风声鹤唳 在 SPLIT4 中应分写为 风声 鹤 唳（通过 splitDict 或 2+2）
        val r = segmenter.segment("风声鹤唳")
        assertNotNull(r)
        // 应被切分（不保留为整体"风声鹤唳"）
        assertTrue("should be split, got: ${r.text}", !r.text.contains("风声鹤唳"))
    }

    // ------------------------------------------------------------------
    // 空输入
    // ------------------------------------------------------------------

    @Test
    fun testEmptyInput() {
        val r = segmenter.segment("")
        assertEquals("", r.text)
        assertEquals(0, r.origToSeg.size)
    }

    @Test
    fun testNullInput() {
        val r = segmenter.segment(null)
        assertEquals("", r.text)
    }

    // ------------------------------------------------------------------
    // loadSplitDict 强制分写词库
    // ------------------------------------------------------------------

    @Test
    fun testSplitDictLoading() {
        val splitStream = ByteArrayInputStream(
            "一丘之貉\t一丘 之 貉\n".toByteArray(Charsets.UTF_8))
        segmenter.loadSplitDict(splitStream)
        val r = segmenter.segment("一丘之貉")
        assertNotNull(r)
        // 命中 splitDict 后按 一丘 之 貉 切分
        assertEquals("一丘 之 貉", r.text)
    }

    @Test
    fun testSplitDictRejectsMismatchedParts() {
        val localDict = JiebaDict()
        localDict.init(ByteArrayInputStream(
            "甲乙丙丁 1000\n".toByteArray(Charsets.UTF_8)))
        val localSegmenter = BrailleSegmenter(localDict)
        localSegmenter.loadSplitDict(ByteArrayInputStream(
            "甲乙丙丁\t甲乙 丙\n".toByteArray(Charsets.UTF_8)))
        assertEquals("甲乙丙丁", localSegmenter.segment("甲乙丙丁").text)
    }

    @Test
    fun testSplitDictMatchesAcrossJiebaTokens() {
        // jieba 会切成「一看」+「就」+「懂」，规则词不出现在任何单个 token 中。
        // 旧实现只在单个 token 上查 splitDict，规则会静默失效。
        val localDict = JiebaDict()
        localDict.init(ByteArrayInputStream(
            "一 500\n看 500\n一看 900\n就 800\n懂 800\n".toByteArray(Charsets.UTF_8)))
        val localSegmenter = BrailleSegmenter(localDict)
        localSegmenter.loadSplitDict(ByteArrayInputStream(
            "一看就懂\t一 看 就 懂\n".toByteArray(Charsets.UTF_8)))
        assertEquals("一 看 就 懂", localSegmenter.segment("一看就懂").text)
    }

    @Test
    fun testConnectOverridesSplitDict() {
        // CONNECT 是内置强制连写白名单，优先级高于阳光分写规则。
        val localDict = JiebaDict()
        localDict.init(ByteArrayInputStream(
            "脚踏实地 1000\n".toByteArray(Charsets.UTF_8)))
        val localSegmenter = BrailleSegmenter(localDict)
        localSegmenter.loadSplitDict(ByteArrayInputStream(
            "脚踏实地\t脚 踏 实地\n".toByteArray(Charsets.UTF_8)))
        assertEquals("脚踏实地", localSegmenter.segment("脚踏实地").text)
    }

    // ------------------------------------------------------------------
    // 后附成分合并（孩子们 <- 孩子 + 们）
    // ------------------------------------------------------------------

    @Test
    fun testSuffixMerge() {
        val r = segmenter.segment("孩子们")
        assertNotNull(r)
        // 孩子们 应作为一个整体（孩子 + 们 合并）
        assertTrue(r.text.contains("孩子们"))
    }

    @Test
    fun testSuffixNotMergedAfterFunctionWord() {
        // 虚词/助词不能充当后附成分的词根：
        // 「的 + 头」必须分写，不能连成「的头」。
        assertEquals("的 头", segmenter.segment("的头").text)
        assertEquals("的 手", segmenter.segment("的手").text)
        assertEquals("在 家", segmenter.segment("在家").text)
        // 实义词根仍正常合并
        assertEquals("石头", segmenter.segment("石头").text)
        assertEquals("他们", segmenter.segment("他们").text)
    }

    // ------------------------------------------------------------------
    // 位置映射往返一致性：原字符数不变，空格只增不减字符
    // ------------------------------------------------------------------

    @Test
    fun testMappingRoundTrip() {
        val input = "我爱中华人民共和国图书馆学习汉语"
        val r = segmenter.segment(input)
        assertEquals(input.length, r.origToSeg.size)
        // 去除分段文本中的空格后应与原文本相同
        val stripped = r.text.filter { it != ' ' }
        assertEquals(input, stripped)
    }
}
