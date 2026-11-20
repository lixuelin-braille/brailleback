package com.googlecode.eyesfree.braille.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * 数字 + 字母单位测试（真实词典）。
 *
 * 规则与 Brailix 的 numbers.json quantity 组一致：不维护单位白名单，
 * 数字后紧邻的拉丁字母串一律整体连写（3kg / 100mg / 3.5kg / 47cm）。
 * 字母串之后接汉字仍按词间空格（3.5kg 重）。
 *
 * 运行：gradle :service:testDebugUnitTest --tests '*NumberUnitTest*'
 */
class NumberUnitTest {

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
                println("[NumberUnit] jiebadt.txt 未找到，跳过")
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
    fun testIntegerWithUnit() {
        assumeLoaded()
        assertEquals("3kg", seg("3kg"))
        assertEquals("100mg", seg("100mg"))
        assertEquals("47cm", seg("47cm"))
        assertEquals("5m", seg("5m"))
    }

    @Test
    fun testDecimalWithUnit() {
        assumeLoaded()
        assertEquals("3.5kg", seg("3.5kg"))
        assertEquals("0.5m", seg("0.5m"))
        assertEquals("3,500m", seg("3,500m"))
        assertEquals("３．５kg", seg("３．５kg"))
    }

    @Test
    fun testUnitThenHanziKeepsSpace() {
        assumeLoaded()
        // Brailix golden: 3.5kg重 → 数字+单位连写，单位与汉字之间是词间空格；
        // 非单位字母串（3.5foo重）结构完全相同。
        assertEquals("3.5kg 重", seg("3.5kg重"))
        assertEquals("3.5foo 重", seg("3.5foo重"))
        assertEquals("3kg 重", seg("3kg重"))
    }

    @Test
    fun testNoRegressionForIdentifiersAndCompounds() {
        assumeLoaded()
        assertEquals("3D 打印", seg("3D打印"))
        assertEquals("A4 纸", seg("A4纸"))
        assertEquals("MP3", seg("MP3"))
        assertEquals("3${CONNECTOR}个 苹果", seg("3个苹果"))
    }

    @Test
    fun testExplicitSpaceIsRespected() {
        assumeLoaded()
        assertEquals("3.5 kg", seg("3.5 kg"))
        assertEquals("100 mg", seg("100 mg"))
    }
}
