package com.googlecode.eyesfree.braille.service.translate

import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * 诊断：liblouis 中文表里的多音字词组规则，在我们的分词结果中是否被空格拆开。
 *
 * liblouis 的 `always 词组 盲文格` 规则要求词组连续出现；一旦分词器在词组
 * 内部插入词间空格，规则就匹配不到，多音字会退回单字默认读音。
 *
 * 运行：gradle :service:testDebugUnitTest --tests '*LiblouisPhraseSegmentationTest*'
 */
class LiblouisPhraseSegmentationTest {

    companion object {
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

        private fun locateTable(): File? {
            val candidates = listOf(
                "jni/liblouiswrapper/liblouis/tables/zhcn-g1.ctb",
                "service/jni/liblouiswrapper/liblouis/tables/zhcn-g1.ctb",
                "../service/jni/liblouiswrapper/liblouis/tables/zhcn-g1.ctb"
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
            val dict = JiebaDict()
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

        private val ALWAYS_RE =
            Regex("^always ((?:\\\\x[0-9A-Fa-f]{4})+) [0-9-]+\\s*$")
        private val ESC_RE = Regex("\\\\x([0-9A-Fa-f]{4})")

        private fun hanziPhrases(): List<String> {
            val table = locateTable() ?: return emptyList()
            val out = ArrayList<String>()
            table.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    val m = ALWAYS_RE.matchEntire(line) ?: continue
                    val phrase = ESC_RE.findAll(m.groupValues[1])
                        .map { it.groupValues[1].toInt(16).toChar() }
                        .joinToString("")
                    if (phrase.length >= 2 && phrase.all { it in '\u4E00'..'\u9FFF' }) {
                        out.add(phrase)
                    }
                }
            }
            return out
        }

    /** 实际加载的保护表（含 tools/liblouis_phrase_overrides.txt 的人工覆盖）。 */
    private fun assetPhrases(): List<String> {
        val file = locate("liblouis_phrases.txt") ?: return emptyList()
        return file.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.length >= 2 && !it.startsWith("#") }
            .filter { line -> line.all { it in '\u4E00'..'\u9FFF' } }
    }
    }

    @Test
    fun testLiblouisPhrasesStayIntact() {
        org.junit.Assume.assumeTrue("词典未加载，跳过", loaded)
        val phrases = hanziPhrases()
        assertTrue("未找到 zhcn-g1.ctb 或其中没有词组规则", phrases.isNotEmpty())
        val broken = ArrayList<String>()
        for (p in phrases) {
            val r = segmenter.segment(p)
            if (r.text.contains(' ')) {
                broken.add("$p -> ${r.text}")
            }
        }
        println("[LiblouisPhrase] 词组 ${phrases.size} 条，被拆开 ${broken.size} 条")
        for (b in broken.take(40)) {
            println("  $b")
        }
        assertTrue(
            "liblouis 多音字词组被分词拆开 ${broken.size} 条（前 40 条见 stdout）",
            broken.isEmpty()
        )
    }

    @Test
    fun testProtectedAssetStaysIntact() {
        org.junit.Assume.assumeTrue("词典未加载，跳过", loaded)
        val phrases = assetPhrases()
        assertTrue("未找到 liblouis_phrases.txt", phrases.isNotEmpty())
        val broken = ArrayList<String>()
        for (p in phrases) {
            val r = segmenter.segment(p)
            if (r.text.contains(' ')) {
                broken.add("$p -> ${r.text}")
            }
        }
        println("[LiblouisPhrase] 保护表 ${phrases.size} 条，被拆开 ${broken.size} 条")
        for (b in broken.take(40)) {
            println("  $b")
        }
        assertTrue(
            "保护表词组被分词拆开 ${broken.size} 条（前 40 条见 stdout）",
            broken.isEmpty()
        )
    }
}
