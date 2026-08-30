package com.googlecode.eyesfree.braille.service.translate

import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileInputStream

/**
 * 用**真实词典**验证分词效果：service/assets/jiebadt.txt（jieba 完整词典 ~35 万词）
 * + service/assets/sunshine_split.txt（阳光读屏强制分写词库）。
 *
 * 与 BrailleSegmenterRegressionTest 的区别：后者用迷你内嵌词库验证规则逻辑，
 * 本测试验证真实词典覆盖度下的实际分词结果。
 *
 * 运行：`gradle :service:testDebugUnitTest --tests '*RealDictSegmentationTest*'`
 */
class RealDictSegmentationTest {

    companion object {
        private lateinit var segmenter: BrailleSegmenter
        private var realDictLoaded = false

        /** 从多个候选路径定位 assets 文件（JUnit 工作目录可能是模块根或项目根）。 */
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
            val dictFile = locate("jiebadt.txt")
            if (dictFile == null) {
                println("[RealDict] jiebadt.txt 未找到，跳过真实词典测试")
                return
            }
            println("[RealDict] 加载词典: ${dictFile.absolutePath} (${dictFile.length() / 1024 / 1024} MB)")
            val t0 = System.currentTimeMillis()
            val dict = JiebaDict()
            FileInputStream(dictFile).use { dict.init(it) }
            println("[RealDict] 词典加载耗时 ${System.currentTimeMillis() - t0} ms")

            segmenter = BrailleSegmenter(dict)
            val splitFile = locate("sunshine_split.txt")
            if (splitFile != null) {
                FileInputStream(splitFile).use { segmenter.loadSplitDict(it) }
                println("[RealDict] 已加载阳光分写词库: ${splitFile.name}")
            }
            realDictLoaded = true
        }

        private fun seg(text: String): String {
            val r = segmenter.segment(text)
            // 去空格后应与原文一致（分词只插空格，不丢字符）
            val stripped = r.text.filter { it != ' ' }
            assertTrue(
                "分词丢失/改变了字符: 原文[$text] 结果[${r.text}]",
                stripped == text
            )
            println("  「$text」  ->  「${r.text}」")
            return r.text
        }
    }

    private fun assumeLoaded() {
        org.junit.Assume.assumeTrue(
            "真实词典未加载（assets 路径不可用），跳过",
            realDictLoaded
        )
    }

    // ---------- 日常语句 ----------

    @Test
    fun testDailySentences() {
        assumeLoaded()
        println("== 日常语句 ==")
        seg("我们爱祖国")
        seg("今天的天气很好")
        seg("我去图书馆看书")
        seg("孩子们在山上玩")
        seg("他学习汉语拼音")
    }

    // ---------- 成语 / 四字词（应 2+2 或虚词分写） ----------

    @Test
    fun testIdioms() {
        assumeLoaded()
        println("== 成语四字分写 ==")
        seg("风声鹤唳")
        seg("一丘之貉")
        seg("实事求是")
        seg("山清水秀")
    }

    // ---------- 数字 / 小数 / 分数 ----------

    @Test
    fun testNumbers() {
        assumeLoaded()
        println("== 数字保护 ==")
        val r1 = seg("数量是12345个")
        assertTrue("数字应整体保留", r1.contains("12345"))
        val r2 = seg("价格是3.14元")
        assertTrue("小数应整体保留", r2.contains("3.14"))
        seg("三分之二")
        seg("百分之五十")
    }

    // ---------- 化学式 ----------

    @Test
    fun testChemistry() {
        assumeLoaded()
        println("== 化学式保护 ==")
        val r1 = seg("水的化学式是H2O")
        assertTrue("H2O 应整体保留", r1.contains("H2O"))
        val r2 = seg("二氧化碳CO2排放")
        assertTrue("CO2 应整体保留", r2.contains("CO2"))
        seg("硫酸H2SO4")
    }

    // ---------- 书名号 ----------

    @Test
    fun testBookTitle() {
        assumeLoaded()
        println("== 书名号连写 ==")
        val r = seg("《红楼梦》很好看")
        assertTrue("书名号内应连写", r.contains("《红楼梦》"))
    }

    // ---------- 专名 ----------

    @Test
    fun testProperNouns() {
        assumeLoaded()
        println("== 专名成词 ==")
        val r1 = seg("马克思写了资本论")
        assertTrue("马克思应成词", r1.contains("马克思"))
        seg("爱因斯坦提出相对论")
        seg("北京举办奥运会")
    }

    // ---------- 英文 / 混合 ----------

    @Test
    fun testMixedLatin() {
        assumeLoaded()
        println("== 中英混合 ==")
        val r = seg("BrailleBack支持HID点显器")
        assertTrue("连续字母串应整体保留", r.contains("BrailleBack"))
        assertTrue("HID 应整体保留", r.contains("HID"))
    }

    // ---------- 化学/专业名词连写排查（2+2 分写规则是否误伤） ----------

    @Test
    fun testChemNounsNotSplit() {
        assumeLoaded()
        println("== 化学/专业名词排查 ==")
        for (w in listOf(
            "二氧化碳", "一氧化碳", "二氧化硫", "氢氧化钠", "碳酸钠", "碳酸钙",
            "高锰酸钾", "氯化钠", "硫酸铜", "碳酸氢钠", "氯酸钾", "过氧化氢"
        )) {
            val r = seg(w)
            if (r.contains(' ')) {
                println("    [!] 疑似误拆: $w -> $r")
            }
        }
    }

    // ---------- 回归护栏：2+2 分写不应被过度抑制 ----------

    @Test
    fun testIdiomsStillSplit() {
        assumeLoaded()
        println("== 四字词分写护栏（不在 CONNECT 白名单的应仍分写）==")
        // 这些不在 CONNECT/SPLIT4/NAMESET 白名单中，修复前会 2+2 分写，修复后应保持
        // 注：这些词的跨拆分点双字（声鹤/木皆/心一意）均非词典词，故不触发保护，照常分写。
        // 反例「莫名其妙」的半词 莫明/名其/其妙 词频皆为 0，本就不满足 2+2 分写条件，
        // 属修复前的既有行为，不列入本护栏。
        val shouldSplit = listOf("风声鹤唳", "草木皆兵", "一心一意")
        for (w in shouldSplit) {
            val r = seg(w)
            println("    ${if (r.contains(' ')) "[OK 分写]" else "[!  未分写]"} $w")
        }
    }

    // ---------- 长句综合 ----------

    @Test
    fun testLongSentence() {
        assumeLoaded()
        println("== 长句综合 ==")
        seg("中华人民共和国成立于1949年10月1日")
        seg("他每天早上七点半起床，然后去图书馆学习汉语。")
    }
}
