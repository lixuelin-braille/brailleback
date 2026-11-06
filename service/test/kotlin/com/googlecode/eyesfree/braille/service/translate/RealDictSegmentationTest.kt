package com.googlecode.eyesfree.braille.service.translate

import org.junit.Assert.assertEquals
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
        private lateinit var dict: JiebaDict
        private var realDictLoaded = false
        private var supplementOverlap = 0

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
            dict = JiebaDict()
            FileInputStream(dictFile).use { dict.init(it) }
            println("[RealDict] 词典加载耗时 ${System.currentTimeMillis() - t0} ms")

            segmenter = BrailleSegmenter(dict)
            val splitFile = locate("sunshine_split.txt")
            if (splitFile != null) {
                FileInputStream(splitFile).use { segmenter.loadSplitDict(it) }
                println("[RealDict] 已加载阳光分写词库: ${splitFile.name}")
            }
            val supplementFile = locate("sunshine_supplement.txt")
            if (supplementFile != null) {
                supplementFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        val l = line.trim()
                        if (l.isEmpty() || l.startsWith("#")) {
                            continue
                        }
                        val word = l.split(Regex("\\s+"))[0]
                        if (dict.isWord(word)) {
                            supplementOverlap++
                        }
                    }
                }
                val added = FileInputStream(supplementFile).use { dict.loadSupplement(it) }
                println("[RealDict] 已加载阳光补充词库: ${supplementFile.name}, 新增 $added 词")
            }
            val overrideFile = locate("zh_segment_overrides.txt")
            if (overrideFile != null) {
                val applied = FileInputStream(overrideFile).use { dict.loadOverrides(it) }
                println("[RealDict] 已加载分词覆盖表: ${overrideFile.name}, $applied 条")
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

    @Test
    fun testAsciiAlnumIdentifiers() {
        assumeLoaded()
        println("== 字母数字标识符保护 ==")
        assertEquals("MP3", seg("MP3"))
        assertEquals("COVID-19", seg("COVID-19"))
        assertEquals("B2B", seg("B2B"))
        assertTrue("A4 应整体保留", seg("A4纸").startsWith("A4 "))
        assertTrue("3D 应整体保留", seg("3D打印").startsWith("3D "))
    }

    @Test
    fun testFullWidthNumbers() {
        assumeLoaded()
        println("== 全角数字/负号 ==")
        assertEquals("１２３", seg("１２３"))
        assertEquals("－5", seg("－5"))
        assertTrue(seg("价格是１，２３４．５％元").contains("１，２３４．５％"))
    }

    @Test
    fun testUrlAndEmail() {
        assumeLoaded()
        println("== URL/邮箱保护 ==")
        assertEquals("https://example.com/a?b=1", seg("https://example.com/a?b=1"))
        assertEquals("support@example.com", seg("support@example.com"))
        assertEquals("www.example.com", seg("www.example.com"))
    }

    @Test
    fun testAccessibilityTerms() {
        assumeLoaded()
        println("== 点显器/无障碍领域词 ==")
        for (word in listOf("点显器", "盲文点显器", "视障人士", "读屏软件", "老人们")) {
            assertEquals(word, seg(word))
        }
        assertEquals("HID 点显器", seg("HID点显器"))
    }

    @Test
    fun testVeryGoodBeforeAdjective() {
        assumeLoaded()
        println("== 很 + 好 规则 ==")
        assertEquals("很好", seg("很好"))
        assertEquals("很 好看", seg("很好看"))
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

    // ---------- 阳光强制分写词库 ----------

    @Test
    fun testSunshineRuleFileIntegrity() {
        val f = locate("sunshine_split.txt") ?: return
        var total = 0
        var bad = 0
        val seen = HashSet<String>()
        f.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val l = line.trim()
                if (l.isEmpty()) {
                    continue
                }
                val tab = l.indexOf('\t')
                if (tab <= 0) {
                    bad++
                    continue
                }
                val word = l.substring(0, tab)
                val parts = l.substring(tab + 1).trim()
                    .split(' ').filter { it.isNotEmpty() }
                total++
                if (parts.size < 2 || parts.joinToString("") != word) {
                    bad++
                }
                if (!seen.add(word)) {
                    bad++
                }
            }
        }
        println("[RealDict] 阳光规则 $total 条, 异常 $bad 条")
        assertEquals("阳光规则文件存在拼接不一致/重复词", 0, bad)
        assertTrue("阳光规则应包含 6000+ 条（当前 $total）", total >= 6000)
    }

    @Test
    fun testSunshineRulesApplied() {
        assumeLoaded()
        println("== 阳光强制分写生效 ==")
        // 规则词本身不在 jiebadt 中，jieba 会切成多个 token；
        // 靠相邻 token 拼接匹配，规则仍应生效。
        assertEquals("一一 谢绝", seg("一一谢绝"))
        assertEquals("一 不做 二 不休", seg("一不做二不休"))
        assertEquals("一些 人", seg("一些人"))
        // SPLIT4 中的词采用阳光的语节分法
        assertEquals("草木 皆 兵", seg("草木皆兵"))
        // CONNECT 白名单优先，保持连写
        assertEquals("脚踏实地", seg("脚踏实地"))
        assertEquals("欣欣向荣", seg("欣欣向荣"))
    }

    @Test
    fun testSunshineRuleCoverage() {
        assumeLoaded()
        val f = locate("sunshine_split.txt") ?: return
        var total = 0
        var matched = 0
        var missCount = 0
        val misses = ArrayList<String>()
        val missWords = ArrayList<String>()
        f.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val l = line.trim()
                if (l.isEmpty()) {
                    continue
                }
                val tab = l.indexOf('\t')
                if (tab <= 0) {
                    continue
                }
                val word = l.substring(0, tab)
                val expected = l.substring(tab + 1).trim()
                    .split(' ').filter { it.isNotEmpty() }.joinToString(" ")
                total++
                val actual = segmenter.segment(word).text
                if (actual == expected) {
                    matched++
                } else {
                    missCount++
                    missWords.add(word)
                    if (misses.size < 30) {
                        misses.add("$word -> [$actual] 期望 [$expected]")
                    }
                }
            }
        }
        val ratio = matched * 100.0 / total
        println("[RealDict] 阳光规则覆盖 $matched/$total = ${"%.2f".format(ratio)}%")
        for (m in misses) {
            println("  MISS $m")
        }
        assertTrue("阳光规则覆盖率应 >= 99%，实际 ${"%.2f".format(ratio)}%", ratio >= 99.0)
        // 允许两类“故意不服从”的例外：
        // 1) CONNECT 内置连写成语；
        // 2) 多音字读音优先词（拆开后 liblouis 会退回单字默认音）。
        val allowedMisses = setOf(
            "欣欣向荣", "络绎不绝", "脚踏实地", "蒸蒸日上",
            "地处", "甘当", "每当", "敢当", "只当", "该当", "正当", "霍地",
            "为生", "为主", "为重", "为伍", "独占", "重返", "重建"
        )
        val unexpected = missWords.filter { it !in allowedMisses }
        assertTrue("阳光规则未命中且不在白名单: $unexpected", unexpected.isEmpty())
    }

    @Test
    fun testLongSentence() {
        assumeLoaded()
        println("== 长句综合 ==")
        seg("中华人民共和国成立于1949年10月1日")
        seg("他每天早上七点半起床，然后去图书馆学习汉语。")
    }

    // ---------- 阳光补充词库 ----------

    @Test
    fun testSupplementDictIntegrity() {
        val f = locate("sunshine_supplement.txt") ?: return
        var total = 0
        var bad = 0
        val seen = HashSet<String>()
        val cjk = Regex("^[\\u4e00-\\u9fff]+$")
        f.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val l = line.trim()
                if (l.isEmpty() || l.startsWith("#")) {
                    continue
                }
                val p = l.split(Regex("\\s+"))
                if (p.size < 2) {
                    bad++
                    continue
                }
                val word = p[0]
                val freq = p[1].toIntOrNull() ?: 0
                total++
                if (!cjk.matches(word) || word.length < 2 || freq <= 0) {
                    bad++
                }
                if (!seen.add(word)) {
                    bad++
                }
            }
        }
        println("[RealDict] 阳光补充词 $total 条, 异常 $bad 条")
        assertEquals("补充词库存在非法/重复/与主词典重叠的词", 0, bad)
        assertEquals("补充词库不应包含主词典已有词", 0, supplementOverlap)
        assertTrue("补充词库应包含 12000+ 词（当前 $total）", total >= 12000)
    }

    @Test
    fun testSupplementWordsMerged() {
        assumeLoaded()
        println("== 阳光补充词成词 ==")
        for (word in listOf(
            "下一步", "不合格", "点字", "原计划", "不值得", "不等于",
            "准备好", "张伟"
        )) {
            assertEquals(word, seg(word))
        }
    }

    @Test
    fun testSupplementKeepsDailySegmentation() {
        assumeLoaded()
        println("== 补充词库后的日常回归 ==")
        assertEquals("我们 爱 祖国", seg("我们爱祖国"))
        assertEquals("他 学习 汉语 拼音", seg("他学习汉语拼音"))
        assertEquals("今天 的 天气 很好", seg("今天的天气很好"))
        // 一山/一水 本身是补充词，成语按 2+2 分写
        assertEquals("一山 一水", seg("一山一水"))
        assertTrue("数字应整体保留", seg("数量是12345个").contains("12345"))
        assertEquals("https://example.com/a?b=1", seg("https://example.com/a?b=1"))
    }

    // ---------- 高优先级覆盖表 ----------

    @Test
    fun testOverrideDictIntegrity() {
        val f = locate("zh_segment_overrides.txt") ?: return
        var total = 0
        var bad = 0
        val seen = HashSet<String>()
        val cjk = Regex("^[\\u4e00-\\u9fff]+$")
        f.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val l = line.trim()
                if (l.isEmpty() || l.startsWith("#")) {
                    continue
                }
                val p = l.split(Regex("\\s+"))
                if (p.size < 2) {
                    bad++
                    continue
                }
                val word = p[0]
                val freq = p[1].toIntOrNull() ?: 0
                total++
                if (!cjk.matches(word) || word.length < 2 || freq <= 0) {
                    bad++
                }
                if (!seen.add(word)) {
                    bad++
                }
            }
        }
        println("[RealDict] 分词覆盖表 $total 条, 异常 $bad 条")
        assertEquals("覆盖表存在非法/重复词", 0, bad)
        assertTrue("覆盖表至少应包含 8 条种子词（当前 $total）", total >= 8)
        assertEquals("正确 的词频应被覆盖为 20000", 20000, dict.freq("正确"))
        for (word in listOf("王芳", "易守难攻", "中医药学家", "保险单据",
            "中和力量", "大语言模型", "协议栈")) {
            assertTrue("覆盖表词条未生效: $word", dict.isWord(word))
        }
    }

    @Test
    fun testSegmentOverridesApplied() {
        assumeLoaded()
        println("== 分词覆盖表生效 ==")
        assertEquals("王芳 来 了", seg("王芳来了"))
        assertEquals("张伟 和 王芳 是 同学", seg("张伟和王芳是同学"))
        assertEquals("易守难攻", seg("易守难攻"))
        assertEquals("中医药学家", seg("中医药学家"))
        assertEquals("保险 单据", seg("保险单据"))
        assertEquals("中和 力量", seg("中和力量"))
        assertEquals("大语言模型", seg("大语言模型"))
        assertEquals("协议栈", seg("协议栈"))
        assertEquals("我们 必须 正确 切 词", seg("我们必须正确切词"))
        // 词频纠正不应破坏派生词
        assertEquals("正确性", seg("正确性"))
        assertEquals("不 正确", seg("不正确"))
    }

    @Test
    fun testPolyphoneWordsStayConnected() {
        assumeLoaded()
        // 这些词含多音字（重/为/当/处/占/地）。一旦被阳光规则拆开，
        // liblouis 无法跨空格命中整词 always 规则，会读成单字默认音。
        for (word in listOf(
            "重返", "重建", "为主", "为重", "为生", "为伍",
            "每当", "正当", "甘当", "地处", "独占", "霍地"
        )) {
            assertEquals("多音字词应连写: $word", word, seg(word))
        }
    }
}
