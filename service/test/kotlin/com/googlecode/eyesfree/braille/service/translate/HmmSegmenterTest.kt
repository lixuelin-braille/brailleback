package com.googlecode.eyesfree.braille.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * 校验移植后的 BMES HMM：
 * 1) 解码结果与 jieba 0.42.1 finalseg 逐字一致（对照值由 Python 生成）；
 * 2) 接入词典后只合并未登录词，不破坏既有切分；
 * 3) 虚词不领起未登录词、用户词典边界不被跨越；
 * 4) 输出的位置区间连续且拼回原文，不丢字。
 */
class HmmSegmenterTest {

    companion object {
        private var hmm: HmmSegmenter? = null
        private var dict: JiebaDict? = null

        private fun locate(name: String): File? {
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
            val emit = locate("hmm_emit.txt")
            if (emit != null) {
                val s = HmmSegmenter()
                val n = FileInputStream(emit).use { s.load(it) }
                println("[HMM] 发射概率表: ${emit.name}, chars=$n")
                if (n > 0) {
                    hmm = s
                }
            }
            val dictFile = locate("jiebadt.txt")
            if (dictFile != null) {
                val d = JiebaDict()
                FileInputStream(dictFile).use { d.init(it) }
                if (emit != null) {
                    FileInputStream(emit).use { d.loadHmm(it) }
                }
                dict = d
            }
        }
    }

    private fun hmmOrSkip(): HmmSegmenter {
        org.junit.Assume.assumeTrue("hmm_emit.txt 不可用，跳过", hmm != null)
        return hmm!!
    }

    private fun dictOrSkip(): JiebaDict {
        org.junit.Assume.assumeTrue("真实词典不可用，跳过", dict != null)
        org.junit.Assume.assumeTrue("HMM 未加载，跳过", dict!!.isHmmReady())
        return dict!!
    }

    private fun tokens(sentence: String): List<String> {
        return dictOrSkip().withHmm(dictOrSkip().segmentWithPos(sentence)).map { it[0] }
    }

    // ---------- 与 jieba finalseg 的逐字对照 ----------

    @Test
    fun testParityWithJiebaFinalseg() {
        val cases = listOf(
            "小明考上了清华大学" to "小明/考上/了/清华大学",
            "王小明和李小红结婚了" to "王小明/和/李小红/结婚/了",
            "筋伤多因外力作用" to "筋伤/多/因/外力/作用",
            "阴虚不能制约阳而致的虚热证" to "阴虚不能/制约阳/而致/的/虚/热证",
            "血是循行于脉中而富于营养的" to "血是/循行/于/脉/中/而/富于/营养/的",
            "经脉循行" to "经脉/循行",
            "膝关节外展受限" to "膝/关节/外展/受限",
            "冈上肌肌腱炎" to "冈上/肌肌/腱炎",
            "临床多见于中老年人" to "临床/多/见/于/中/老年/人",
            "阳明经多气多血" to "阳明/经多气/多血"
        )
        val seg = hmmOrSkip()
        for ((input, expected) in cases) {
            assertEquals("HMM 解码与 jieba 不一致: $input", expected, seg.cut(input).joinToString("/"))
        }
    }

    @Test
    fun testUnseenCharDoesNotBreak() {
        val seg = hmmOrSkip()
        // 表外字符按 jieba 的 MIN_FLOAT 处理；此处只要求不丢字、不抛异常。
        assertEquals("ab", seg.cut("ab").joinToString(""))
        assertEquals(listOf("〇"), seg.cut("〇"))
        assertEquals("〇ab一", seg.cut("〇ab一").joinToString(""))
    }

    // ---------- 未登录词识别 ----------

    @Test
    fun testMergesUnknownNamesAndTerms() {
        assertTrue("小明 应合并", tokens("小明考上了清华大学").contains("小明"))
        assertTrue("李小红 应合并", tokens("王小明和李小红结婚了").contains("李小红"))
        assertTrue("循行 应合并", tokens("血是循行于脉中").contains("循行"))
        assertTrue("筋伤 应合并", tokens("筋伤多因外力作用").contains("筋伤"))
    }

    @Test
    fun testFunctionLeadIsNotMerged() {
        assertTrue("而致 不应合并", !tokens("阴虚不能制约阳而致的虚热证").contains("而致"))
        assertTrue("阳而致 不应合并", !tokens("阴虚不能制约阳而致的虚热证").contains("阳而致"))
        assertTrue("之气 不应合并", !tokens("包括主呼吸之气和主一身之气").contains("之气"))
        assertTrue("可致 不应合并", !tokens("诱因可致余邪复盛").contains("可致"))
        assertTrue("所胜 不应合并", !tokens("其所胜之脏").contains("所胜"))
    }

    @Test
    fun testContentLeadStillMerges() {
        // 「无 / 不 / 过 / 对」不列入虚词表，避免误伤真词。
        assertTrue("过多 应合并", tokens("出汗过多").contains("过多"))
        assertTrue("对侧 应合并", tokens("对侧肢体").contains("对侧"))
        // 词尾虚词的正常搭配不受影响。
        assertTrue("起于 应合并", tokens("手太阴肺经起于中焦").contains("起于"))
        assertTrue("多见于 应合并", tokens("冈上肌肌腱炎多见于中老年人").contains("多见于"))
    }

    // ---------- 结构与边界 ----------

    @Test
    fun testPositionsStayContiguous() {
        val d = dictOrSkip()
        val sentence = "阴虚不能制约阳而致的虚热证，多见于久病年老体衰者。"
        val words = d.withHmm(d.segmentWithPos(sentence))
        val rebuilt = StringBuilder()
        var prevEnd = 0
        for (w in words) {
            assertEquals("区间不连续: $sentence -> ${words.map { it[0] }}",
                prevEnd, w[1].toInt())
            prevEnd = w[2].toInt()
            rebuilt.append(w[0])
        }
        assertEquals("HMM 改变了字符", sentence, rebuilt.toString())
    }

    @Test
    fun testProtectedBoundaryBlocksMerge() {
        val d = dictOrSkip()
        val raw = d.segmentWithPos("李小红")
        assertEquals(listOf("李", "小", "红"), raw.map { it[0] })
        val blocked = BooleanArray(raw.size + 1) { true }
        val unmerged = d.withHmm(raw, blocked)
        assertEquals(listOf("李", "小", "红"), unmerged.map { it[0] })
        val merged = d.withHmm(raw, null)
        assertEquals(listOf("李小红"), merged.map { it[0] })
    }
}
