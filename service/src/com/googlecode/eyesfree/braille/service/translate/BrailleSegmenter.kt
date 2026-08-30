/*
 * BrailleSegmenter - 盲文分词规则引擎（Kotlin 版）。
 * 与 Java 版一致：书名号内连写 + 数字整体保护 + 专名 addWord + jieba DAG 分词
 * + 后附成分合并 + 四字词 2+2 语节分写 + 黑白名单 + 化学式保护。
 * 输出"带词间空格的文本"并给出 原始<->分段 位置映射（供 textToBraillePositions 修正）。
 */

package com.googlecode.eyesfree.braille.service.translate

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.regex.Pattern

class BrailleSegmenter(private val dict: JiebaDict) {

    // 阳光读屏强制分写词库（对标：一丘之貉 -> 一丘 之 貉）
    private val splitDict = HashMap<String, Array<String>>()

    init {
        for (w in SPLIT4) {
            dict.delWord(w)
        }
        for (w in CONNECT) {
            dict.addWord(w, 100000)
        }
        for (w in NAMESET) {
            dict.addWord(w, 100000)
        }
        for (w in SPLIT4_INNER) {
            dict.addWord(w, 100000)
        }
    }

    /** 加载阳光强制分写词库：每行 "词\t分1 分2 分3"。 */
    @Synchronized
    fun loadSplitDict(inputStream: InputStream?) {
        if (inputStream == null) {
            return
        }
        try {
            val br = BufferedReader(InputStreamReader(inputStream, "UTF-8"))
            while (true) {
                val line = br.readLine() ?: break
                val l = line.trim()
                if (l.isEmpty() || l.startsWith("#")) {
                    continue
                }
                val tab = l.indexOf('\t')
                if (tab <= 0) {
                    continue
                }
                val word = l.substring(0, tab)
                val parts = l.substring(tab + 1).trim().split(" ").toTypedArray()
                if (parts.size >= 2 && !splitDict.containsKey(word)) {
                    splitDict[word] = parts
                }
            }
            br.close()
        } catch (e: Exception) {
            // 词库加载失败不阻塞主流程
        }
    }

    class Result {
        @JvmField
        var text: String = ""
        @JvmField
        var origToSeg: IntArray = IntArray(0)
        @JvmField
        var segToOrig: IntArray = IntArray(0)
    }

    private class Tok(val start: Int, val end: Int, val punct: Boolean)

    private fun isPunct(s: String?): Boolean {
        if (s == null || s.isEmpty()) {
            return false
        }
        for (c in s.toCharArray()) {
            if (!PUNCT.contains(c.toString())) {
                return false
            }
        }
        return true
    }

    /** 是否下标数字（₂₃₄...） */
    private fun isSubscript(c: Char): Boolean {
        return c in '\u2080'..'\u2089'
    }

    /** 校验是否为合法化学式（元素表 + 含数字下标）。 */
    private fun isChemFormula(s: String): Boolean {
        if (s.length < 2) {
            return false
        }
        if (!Character.isUpperCase(s[0])) {
            return false
        }
        var hasDigit = false
        var pos = 0
        while (pos < s.length) {
            val c = s[pos]
            if (c in 'A'..'Z') {
                var el = c.toString()
                pos++
                if (pos < s.length && s[pos] in 'a'..'z') {
                    el += s[pos]
                    pos++
                }
                if (!ELEMENTS.contains(el)) {
                    return false
                }
            } else if (c in '0'..'9') {
                hasDigit = true
                pos++
            } else if (isSubscript(c)) {
                hasDigit = true
                pos++
            } else {
                return false
            }
        }
        return hasDigit
    }

    /** 化学式保护：合并相邻 [字母][数字][字母]... token（如 H2O / CO2 / H2SO4）。 */
    private fun mergeChemFormulas(toks: MutableList<Tok>, text: String) {
        if (toks.size < 2) {
            return
        }
        val merged = ArrayList<Tok>()
        var i = 0
        while (i < toks.size) {
            val t = toks[i]
            val ts = text.substring(t.start, t.end)
            if (ts.matches(Regex("[A-Za-z]+"))) {
                val sb = StringBuilder(ts)
                var j = i + 1
                while (j < toks.size) {
                    val tj = toks[j]
                    val tjs = text.substring(tj.start, tj.end)
                    if (tjs.matches(Regex("[A-Za-z0-9\u2080-\u2089]+"))) {
                        sb.append(tjs)
                        j++
                    } else {
                        break
                    }
                }
                if (j - i > 1 && isChemFormula(sb.toString())) {
                    merged.add(Tok(t.start, toks[j - 1].end, false))
                    i = j
                    continue
                }
            }
            merged.add(t)
            i++
        }
        toks.clear()
        toks.addAll(merged)
    }

    /** 普通段：数字分段后，非数字段走 jieba + 规则。 */
    private fun addPlainTokens(toks: MutableList<Tok>, text: String, from: Int, to: Int) {
        if (from >= to) {
            return
        }
        val seg = text.substring(from, to)
        val nm = NUM_PAT.matcher(seg)
        var last = 0
        while (nm.find()) {
            addLetterTokens(toks, text, from + last, from + nm.start())
            toks.add(Tok(from + nm.start(), from + nm.end(), false)) // 数字整体
            last = nm.end()
        }
        addLetterTokens(toks, text, from + last, to)
    }

    /** 连续 ASCII 字母串整体保护（BrailleBack/HID 等不逐字母切）。 */
    private fun addLetterTokens(toks: MutableList<Tok>, text: String, from: Int, to: Int) {
        if (from >= to) {
            return
        }
        val seg = text.substring(from, to)
        val lm = LETTER_RE.matcher(seg)
        var last = 0
        while (lm.find()) {
            addJiebaTokens(toks, text, from + last, from + lm.start())
            toks.add(Tok(from + lm.start(), from + lm.end(), false))
            last = lm.end()
        }
        addJiebaTokens(toks, text, from + last, to)
    }

    /** jieba 分词 + 附成分合并 + 2+2 分写。 */
    private fun addJiebaTokens(toks: MutableList<Tok>, text: String, from: Int, to: Int) {
        if (from >= to) {
            return
        }
        val seg = text.substring(from, to)
        if (seg.isEmpty()) {
            return
        }
        val words = dict.segmentWithPos(seg)
        val merged = ArrayList<Array<String>>()
        for (w in words) {
            val word = w[0]
            val ws = w[1].toInt()
            val we = w[2].toInt()
            // 阳光强制分写词库命中：直接按词库切分
            if (splitDict.containsKey(word) && word.length >= 2) {
                val parts = splitDict[word]!!
                var pos = ws
                for (part in parts) {
                    val next = pos + part.length
                    merged.add(arrayOf(part, pos.toString(), next.toString()))
                    pos = next
                }
                continue
            }
            // 后附成分合并（孩子们 <- 孩子 + 们）
            if (word.length == 1 && SUFFIX.contains(word) && merged.isNotEmpty()
                && !isPunct(merged[merged.size - 1][0])) {
                val prev = merged[merged.size - 1]
                merged[merged.size - 1] =
                    arrayOf(prev[0] + word, prev[1], we.toString())
                continue
            }
            // 数理化/虚词分写：4字结构中强虚词(之/而/所/如/即/若...)在第2/3位时独立成段
            if (word.length == 4 && !CONNECT.contains(word) && !SPLIT4.contains(word)
                && !NAMESET.contains(word)) {
                val c1 = word.substring(1, 2)
                val c2 = word.substring(2, 3)
                if (FUNC.contains(c1)) {
                    // 1+1+2：一丘之貉 -> 一丘 之 貉
                    merged.add(arrayOf(word.substring(0, 1), ws.toString(), (ws + 1).toString()))
                    merged.add(arrayOf(c1, (ws + 1).toString(), (ws + 2).toString()))
                    merged.add(arrayOf(word.substring(2, 4), (ws + 2).toString(), we.toString()))
                    continue
                }
                if (FUNC.contains(c2)) {
                    // 2+1+1：一挥而就 -> 一挥 而 就
                    merged.add(arrayOf(word.substring(0, 2), ws.toString(), (ws + 2).toString()))
                    merged.add(arrayOf(c2, (ws + 2).toString(), (ws + 3).toString()))
                    merged.add(arrayOf(word.substring(3, 4), (ws + 3).toString(), we.toString()))
                    continue
                }
            }
            // 四字词 2+2 语节分写（不在黑白名单/专名，且前2后2各自成词）
            if (word.length == 4 && !CONNECT.contains(word) && !SPLIT4.contains(word)
                && !NAMESET.contains(word) && !SPLIT4_INNER.contains(word)) {
                val a = word.substring(0, 2)
                val b = word.substring(2, 4)
                // 跨拆分点双字（如「二氧化碳」的「氧化」、「氢氧化钠」的「氧化」）：
                // 若它是比两侧半词都高频的真词，说明 2+2 会把一个固定搭配从中间劈开
                // （化学名等），此时保持连写。对照：「风声鹤唳」的「声鹤」非词，照常分写。
                val mid = word.substring(1, 3)
                val midFreq = dict.freq(mid)
                val cutsRealWord = midFreq > dict.freq(a) && midFreq > dict.freq(b)
                if (dict.isWord(a) && dict.isWord(b) && !cutsRealWord) {
                    merged.add(arrayOf(a, ws.toString(), (ws + 2).toString()))
                    merged.add(arrayOf(b, (ws + 2).toString(), we.toString()))
                    continue
                }
            }
            merged.add(w)
        }
        for (w in merged) {
            val ws = from + w[1].toInt()
            val we = from + w[2].toInt()
            toks.add(Tok(ws, we, isPunct(w[0])))
        }
    }

    /** 主入口：分词并生成带空格文本 + 位置映射。 */
    fun segment(text: String?): Result {
        val r = Result()
        if (text == null || text.isEmpty()) {
            r.text = if (text == null) "" else text
            r.origToSeg = IntArray(0)
            r.segToOrig = IntArray(0)
            return r
        }
        val toks = ArrayList<Tok>()
        val bm = BOOK_RE.matcher(text)
        var last = 0
        while (bm.find()) {
            addPlainTokens(toks, text, last, bm.start())
            // 《书名》 -> 《 + 书名(整体) + 》
            toks.add(Tok(bm.start(), bm.start() + 1, true))   // 《
            toks.add(Tok(bm.start() + 1, bm.end() - 1, false)) // 书名整体
            toks.add(Tok(bm.end() - 1, bm.end(), true))        // 》
            last = bm.end()
        }
        addPlainTokens(toks, text, last, text.length)

        // 化学式保护：合并 H2O/CO2/H2SO4 等
        mergeChemFormulas(toks, text)

        // 拼装带空格文本 + 映射
        val sb = StringBuilder()
        val L = text.length
        val origToSeg = IntArray(L)
        origToSeg.fill(-1)
        var prevWord = false
        for (t in toks) {
            if (t.punct) {
                sb.append(text, t.start, t.end)
                prevWord = false
            } else {
                if (prevWord) {
                    sb.append(' ')
                }
                for (i in t.start until t.end) {
                    sb.append(text[i])
                    origToSeg[i] = sb.length - 1
                }
                prevWord = true
            }
        }
        // 未覆盖的原始字符（如孤立标点 token 之外的）兜底
        val M = sb.length
        val segToOrig = IntArray(M)
        segToOrig.fill(-1)
        for (i in 0 until L) {
            val sp = origToSeg[i]
            if (sp >= 0) {
                segToOrig[sp] = i
            }
        }
        // 空格位置映射到前一个原始字符；未映射位置兜底
        var lastOrig = -1
        for (i in 0 until M) {
            if (segToOrig[i] >= 0) {
                lastOrig = segToOrig[i]
            } else {
                segToOrig[i] = if (lastOrig >= 0) lastOrig else 0
            }
        }
        r.text = sb.toString()
        r.origToSeg = origToSeg
        r.segToOrig = segToOrig
        return r
    }

    companion object {
        private const val LOG_TAG = "BrailleSegmenter"

        // 应分写的四字整体词（12.2.3 按词语分写）—— 从词典删除
        private val SPLIT4_ARR = arrayOf(
            "风声鹤唳", "草木皆兵", "中华人民共和国", "无缝钢管", "劳动模范",
            "特殊教育词典", "环境保护规划", "生产关系", "人民共和国"
        )
        // 应连写的词（12.2.1/2.2/2.4/2.5/2.6）—— 强制成词
        private val CONNECT_ARR = arrayOf(
            "孩子们", "艺术家", "科学性", "超声波", "很好", "山上", "每年", "对不起", "一刀切",
            "中小学", "图书馆", "大红花", "研究生院", "高锰酸钾", "古生物学家",
            "秋高气爽", "山清水秀", "风和日丽", "兴高采烈", "实事求是", "脚踏实地",
            "层出不穷", "鸟语花香", "万紫千红", "欣欣向荣", "蒸蒸日上", "络绎不绝",
            // 数理化: 分数连写 + 复合单位整体（电脑版已验证）
            "二分之一", "三分之一", "四分之一", "五分之一", "六分之一", "七分之一", "八分之一",
            "九分之一", "十分之一", "百分之一", "千分之一", "三分之二", "四分之三", "五分之二",
            "五分之三", "五分之四", "六分之五",
            "米每秒", "千米每小时", "克每升", "千克每立方米", "牛每米", "焦耳每千克", "安培每米",
            "伏特每米", "弧度每秒", "平方米每秒", "千克每平方米", "摩尔每升", "瓦特每平方米"
        )
        // 分写内部词（12.2.3 分写后需要的内部双字词）
        private val SPLIT4_INNER_ARR = arrayOf(
            "风声", "鹤唳", "草木", "皆兵", "中华", "人民", "共和国", "劳动", "模范", "无缝", "钢管"
        )
        // 专名词表（人名/音译/机构）—— 强制成词
        private val NAMES_ARR = arrayOf(
            "塞维利亚", "德沃夏克", "勃拉姆斯", "安塞尔米", "忽必力", "朱良春", "卫鞅", "汤姆生",
            "霍金", "刘柏龄", "侯彪", "庄王", "斯美塔那", "哈森姆", "维京古尔", "卡达西亚", "桑托斯",
            "博尔顿", "史蒂文", "舒马赫", "硕贝德", "汪美成", "黄道亮", "习近平", "灵娃", "埃特纳火山",
            "汤因比", "马克思", "恩格斯", "列宁", "斯大林", "牛顿", "爱因斯坦", "莎士比亚", "贝多芬",
            "莫扎特", "达芬奇", "亚里士多德", "柏拉图", "苏格拉底", "汉斯利克", "贾宝玉", "林黛玉",
            "中国共产党", "人民代表大会", "无界点显器", "无界点显器(HID)"
        )
        private const val SUFFIX_STR = "们子儿头性者员家手化度法学论品"
        // 强虚词（4字结构分写，借鉴阳光读屏 rules.conf 虚词分写）
        private const val FUNC_STR = "之而所如即若亦俱尚犹且及焉哉乎耳"
        // 化学元素表（118 常用元素，用于化学式校验）
        private val ELEMENTS_ARR = arrayOf(
            "H", "He", "Li", "Be", "B", "C", "N", "O", "F", "Ne", "Na", "Mg", "Al", "Si", "P", "S", "Cl", "Ar",
            "K", "Ca", "Sc", "Ti", "V", "Cr", "Mn", "Fe", "Co", "Ni", "Cu", "Zn", "Ga", "Ge", "As", "Se", "Br",
            "Kr", "Rb", "Sr", "Y", "Zr", "Nb", "Mo", "Tc", "Ru", "Rh", "Pd", "Ag", "Cd", "In", "Sn", "Sb", "Te",
            "I", "Xe", "Cs", "Ba", "La", "Ce", "Pr", "Nd", "Pm", "Sm", "Eu", "Gd", "Tb", "Dy", "Ho", "Er", "Tm",
            "Yb", "Lu", "Hf", "Ta", "W", "Re", "Os", "Ir", "Pt", "Au", "Hg", "Tl", "Pb", "Bi", "Po", "At", "Rn",
            "Fr", "Ra", "Ac", "Th", "Pa", "U", "Np", "Pu", "Am", "Cm", "Bk", "Cf", "Es", "Fm", "Md", "No", "Lr",
            "Rf", "Db", "Sg", "Bh", "Hs", "Mt", "Ds", "Rg", "Cn", "Nh", "Fl", "Mc", "Lv", "Ts", "Og"
        )

        private const val PUNCT_STR =
            "，。、；：？！“”‘’（）《》〈〉—…··,.;:!?()[]{} "

        private val SPLIT4 = HashSet<String>(listOf(*SPLIT4_ARR))
        private val CONNECT = HashSet<String>(listOf(*CONNECT_ARR))
        private val SUFFIX = HashSet<String>()
        private val PUNCT = HashSet<String>()
        private val NAMESET = HashSet<String>(listOf(*NAMES_ARR))
        private val SPLIT4_INNER = HashSet<String>(listOf(*SPLIT4_INNER_ARR))
        private val FUNC = HashSet<String>()
        private val ELEMENTS = HashSet<String>(listOf(*ELEMENTS_ARR))

        private val NUM_PAT = Pattern.compile(
            "(-?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?%?|-?\\d+(?:\\.\\d+)?%?)")
        private val BOOK_RE = Pattern.compile("(《[^》]*》)")
        private val LETTER_RE = Pattern.compile("[A-Za-z]+")

        init {
            for (c in SUFFIX_STR.toCharArray()) {
                SUFFIX.add(c.toString())
            }
            for (c in PUNCT_STR.toCharArray()) {
                PUNCT.add(c.toString())
            }
            for (c in FUNC_STR.toCharArray()) {
                FUNC.add(c.toString())
            }
        }
    }
}
