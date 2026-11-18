package com.googlecode.eyesfree.braille.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * 用真实词典 + 真实词库验证 segment_connect.txt 上线后的实际效果。
 *
 * 这里刻意用「文章点名的错例」做断言：这些句子以前会被拆成单字片段，
 * 现在必须整体连写。
 */
class ConnectWordListAssetTest {

    companion object {
        private var segmenter: BrailleSegmenter? = null
        private var connectFile: File? = null

        fun locate(name: String): File? {
            val candidates = listOf(
                "assets/$name",
                "service/assets/$name",
                "../service/assets/$name",
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
            val splitFile = locate("sunshine_split.txt")
            connectFile = locate("segment_connect.txt")
            assertTrue("jiebadt.txt 必须存在", dictFile != null)
            assertTrue("sunshine_split.txt 必须存在", splitFile != null)
            assertTrue("segment_connect.txt 必须存在", connectFile != null)

            val dict = JiebaDict()
            FileInputStream(dictFile!!).use { dict.init(it) }
            val s = BrailleSegmenter(dict)
            FileInputStream(splitFile!!).use { s.loadSplitDict(it) }
            FileInputStream(connectFile!!).use { s.loadConnectExtra(it) }
            segmenter = s
        }
    }

    private fun assertLinked(word: String) {
        val r = segmenter!!.segment(word)
        assertEquals("「$word」应整体连写", word, r.text)
    }

    @Test
    fun idiomsAreLinked() {
        // 原文：现行盲文把成语拆成「一丘/之/貉」「求/之/不得」「不/以为/然」
        assertLinked("一丘之貉")
        assertLinked("求之不得")
        assertLinked("无动于衷")
    }

    @Test
    fun threeSyllableWholeConceptsAreLinked() {
        // 原文点名的三音节错例：一口气、一席话、一瞬间、分钟、背黑锅、敲竹杠
        assertLinked("一口气")
        assertLinked("一席话")
        assertLinked("一瞬间")
        assertLinked("分钟")
        assertLinked("背黑锅")
        assertLinked("敲竹杠")
    }

    @Test
    fun dictionaryWordsAreLinked() {
        // 原文：词典词分写违背第一条一般规则（的话、之所以、也罢、也好）
        assertLinked("之所以")
        assertLinked("的话")
        assertLinked("也罢")
        assertLinked("也好")
    }

    @Test
    fun ordinaryFourCharWordsStillSplitBySyllable() {
        // 防越界：条文类四字词仍按 2+2 语节分写（GF 0019 12.2.3）
        assertEquals("一元 方程", segmenter!!.segment("一元方程").text)
    }

    @Test
    fun sentenceKeepsWordBoundaries() {
        // 真实句子：连写词不能被强行并在相邻词上，边界仍要有空格
        val r = segmenter!!.segment("他一口气喝完了三分钟")
        assertTrue("连写词应出现", r.text.contains("一口气"))
        assertTrue("分写边界应保留", r.text.contains(" "))
    }

    @Test
    fun assetIsWellFormed() {
        val lines = connectFile!!.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue("词表条目数应 >= 2600，实际 ${lines.size}", lines.size >= 2600)
        assertTrue("不得有重复词面", lines.toSet().size == lines.size)
        for (w in lines) {
            assertTrue(
                "只允许纯汉字词面: $w",
                w.length >= 2 && w.all { it in '\u4E00'..'\u9FFF' }
            )
        }
    }

    @Test
    fun serviceLoadsConnectAssetAtStartup() {
        // 契约：词表不加载就等于没实现，所以钉住调用点。
        val candidates = listOf(
            File("src/com/googlecode/eyesfree/braille/service/translate/TranslatorService.java"),
            File("../service/src/com/googlecode/eyesfree/braille/service/translate/TranslatorService.java"),
            File("service/src/com/googlecode/eyesfree/braille/service/translate/TranslatorService.java")
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("TranslatorService.java 应可定位", file != null)
        val text = file!!.readText(Charsets.UTF_8)
        assertTrue("启动时必须打开 segment_connect.txt", text.contains("segment_connect.txt"))
        assertTrue("必须调用 loadConnectExtra", text.contains("loadConnectExtra"))
    }
}
