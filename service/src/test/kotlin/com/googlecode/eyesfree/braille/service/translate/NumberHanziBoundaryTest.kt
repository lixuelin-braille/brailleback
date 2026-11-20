package com.googlecode.eyesfree.braille.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * 数字/汉字边界测试（真实词典）。
 *
 * 规则：
 * - 数字后紧跟汉字 → 连接号 ⠤（10页 / 3个）；
 * - 百分数（50%）是完整词，后面仍用空格；
 * - 年份标记「年」直接连写（2026年），不加连接号；
 * - 汉字 + 数字默认空格，序数前缀「第」直接连写（第3 / 第2026年）。
 *
 * 运行：gradle :service:testDebugUnitTest --tests '*NumberHanziBoundaryTest*'
 */
class NumberHanziBoundaryTest {

    companion object {
        private const val CONNECTOR = '\u2824'
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
            val dictFile = locate("jiebadt.txt")
            if (dictFile == null) {
                println("[NumberHanzi] jiebadt.txt 未找到，跳过")
                return
            }
            val dict = JiebaDict()
            FileInputStream(dictFile).use { dict.init(it) }
            segmenter = BrailleSegmenter(dict)
            locate("sunshine_split.txt")?.let {
                FileInputStream(it).use { s -> segmenter.loadSplitDict(s) }
            }
            locate("sunshine_supplement.txt")?.let {
                FileInputStream(it).use { s -> dict.loadSupplement(s) }
            }
            locate("zh_segment_overrides.txt")?.let {
                FileInputStream(it).use { s -> dict.loadOverrides(s) }
            }
            loaded = true
        }

        private fun seg(text: String): String {
            val r = segmenter.segment(text)
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
    fun testNumberThenHanzi() {
        assumeLoaded()
        assertEquals("10${CONNECTOR}页", seg("10页"))
        assertEquals("3${CONNECTOR}个", seg("3个"))
        assertEquals("3${CONNECTOR}个 苹果", seg("3个苹果"))
        assertEquals("12345${CONNECTOR}个", seg("12345个"))
        assertEquals("3.5${CONNECTOR}米", seg("3.5米"))
        assertEquals("3,500${CONNECTOR}元", seg("3,500元"))
        assertEquals("１２３${CONNECTOR}个", seg("１２３个"))
    }

    @Test
    fun testYearMarkerAttachesDirectly() {
        assumeLoaded()
        assertEquals("2026年", seg("2026年"))
        assertEquals("2026年 5${CONNECTOR}月", seg("2026年5月"))
        assertEquals("2026年 5${CONNECTOR}月 17${CONNECTOR}日", seg("2026年5月17日"))
        assertEquals(
            "2026年 5${CONNECTOR}月 17${CONNECTOR}日 23${CONNECTOR}时",
            seg("2026年5月17日23时")
        )
    }

    @Test
    fun testOrdinalPrefix() {
        assumeLoaded()
        assertEquals("第3", seg("第3"))
        assertEquals("第3${CONNECTOR}页", seg("第3页"))
        assertEquals("第2026年", seg("第2026年"))
        assertEquals("第3${CONNECTOR}个 苹果", seg("第3个苹果"))
    }

    @Test
    fun testPercentAndUnitKeepSpace() {
        assumeLoaded()
        // 百分数是完整词，后面不接连接号
        assertEquals("50% 的 人", seg("50%的人"))
        // 字母单位与汉字之间仍是词间空格（3kg 是字母数字标识符）
        assertEquals("3kg 重", seg("3kg重"))
        assertEquals("3D 打印", seg("3D打印"))
        assertEquals("A4 纸", seg("A4纸"))
    }

    @Test
    fun testExplicitSpaceIsRespected() {
        assumeLoaded()
        assertEquals("10 页", seg("10 页"))
        assertEquals("2026年 5${CONNECTOR}月", seg("2026年 5月"))
    }

    @Test
    fun testNumberThenMultiCharWord() {
        assumeLoaded()
        // Brailix: Number + Connector + Word，多字词整体连写（10页码）。
        assertEquals("10${CONNECTOR}页码", seg("10页码"))
        assertEquals("5${CONNECTOR}公斤", seg("5公斤"))
        // 标准 jieba 把「3个月」切成 3 / 个 / 月：数量短语「3个」连写，
        // 名词「月」另起一词，因此是 3⠤个 月，而不是 3⠤个月。
        assertEquals("3${CONNECTOR}个 月", seg("3个月"))
    }
}
