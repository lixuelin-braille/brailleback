package com.googlecode.eyesfree.braille.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * 字母/汉字复合词边界测试（真实词典）。
 *
 * 规则：外文字母（含希腊字母）与汉字在原文中直接相邻，且拼接后的词面
 * 命中复合词表时，在边界输出连接号 ⠤（U+2824，点 36），而不是词间空格。
 * 未命中时保持原有空格分隔，例如 A4纸 / 3D打印。
 *
 * 运行：gradle :service:testDebugUnitTest --tests '*CompoundBoundaryTest*'
 */
class CompoundBoundaryTest {

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
                println("[Compound] jiebadt.txt 未找到，跳过")
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
            // 去空格与合成连接号后必须与原文一致（不丢字、不改字）
            val bare = r.text.filter { it != ' ' && it != CONNECTOR }
            assertTrue(
                "分词丢失/改变了字符: 原文[$text] 结果[${r.text}]",
                bare == text.filter { it != ' ' }
            )
            // 每个原始字符都必须映射回自身
            for (i in text.indices) {
                if (text[i].isWhitespace()) {
                    continue // 空格是标点 token，不参与字符级映射
                }
                val sp = r.origToSeg[i]
                assertTrue("原文位置 $i 未映射: ${r.text}", sp in r.text.indices)
                assertEquals(
                    "原文位置 $i 映射到错误字符: ${r.text}",
                    text[i], r.text[sp]
                )
                assertEquals(
                    "位置映射不可逆: ${r.text}",
                    i, r.segToOrig[sp]
                )
            }
            println("  「$text」  ->  「${r.text}」")
            return r.text
        }
    }

    private fun assumeLoaded() {
        org.junit.Assume.assumeTrue("真实词典未加载，跳过", loaded)
    }

    @Test
    fun testLetterHanziCompounds() {
        assumeLoaded()
        val cases = mapOf(
            "x轴" to "x${CONNECTOR}轴",
            "y轴" to "y${CONNECTOR}轴",
            "z轴" to "z${CONNECTOR}轴",
            "X轴" to "X${CONNECTOR}轴",
            "Y轴" to "Y${CONNECTOR}轴",
            "Z轴" to "Z${CONNECTOR}轴",
            "T恤" to "T${CONNECTOR}恤",
            "T台" to "T${CONNECTOR}台",
            "T型" to "T${CONNECTOR}型",
            "X光" to "X${CONNECTOR}光",
            "X射线" to "X${CONNECTOR}射线",
            "B超" to "B${CONNECTOR}超",
            "U盘" to "U${CONNECTOR}盘",
            "U盾" to "U${CONNECTOR}盾",
            "pH值" to "pH${CONNECTOR}值",
            "AA制" to "AA${CONNECTOR}制",
            "α粒子" to "α${CONNECTOR}粒子",
            "β粒子" to "β${CONNECTOR}粒子",
            "γ射线" to "γ${CONNECTOR}射线",
            "γ刀" to "γ${CONNECTOR}刀"
        )
        for ((input, expected) in cases) {
            assertEquals("复合词边界错误: $input", expected, seg(input))
        }
        // 全角字母按半角查表，输出保留原文字形
        assertEquals("Ｘ${CONNECTOR}光", seg("Ｘ光"))
    }

    @Test
    fun testHanziLetterCompounds() {
        assumeLoaded()
        assertEquals("卡拉${CONNECTOR}OK", seg("卡拉OK"))
        for (letter in listOf("A", "B", "C", "D", "E")) {
            assertEquals(
                "维生素$letter",
                "维生素${CONNECTOR}$letter",
                seg("维生素$letter")
            )
        }
    }

    @Test
    fun testNonCompoundKeepsSpace() {
        assumeLoaded()
        // 字母数字标识符整体保护，不因包含字母而误连
        assertEquals("A4 纸", seg("A4纸"))
        assertEquals("3D 打印", seg("3D打印"))
        assertEquals("维生素 B12", seg("维生素B12"))
        // 词表未命中：不凭空插入连接号
        assertEquals("T 恤衫", seg("T恤衫"))
        assertEquals("X 光线", seg("X光线"))
        assertEquals("使用 CPU", seg("使用CPU"))
        // 原文已有空格，不构成相邻复合词
        assertEquals("已知 α", seg("已知 α"))
    }

    @Test
    fun testMultipleCompoundsInOneSentence() {
        assumeLoaded()
        assertEquals(
            "U${CONNECTOR}盘 和 X${CONNECTOR}光",
            seg("U盘和X光")
        )
    }

}
