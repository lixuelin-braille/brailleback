package com.googlecode.eyesfree.brailleback.wrapping

import com.googlecode.eyesfree.braille.translate.TranslationResult
import com.googlecode.eyesfree.brailleback.DisplayManager.Content
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WordWrapStrategy 换行/平移算法测试（迁移到 Kotlin 后的安全网）。
 *
 * 模型约定：测试用「一字一方」的理想翻译（除空格外），空格映射为空方
 * （cells[i] == 0，WordWrapStrategy 以空方为可拆行点）。行首/行尾标点
 * 规则（国标避头点/避尾点）用 brailleToTextPositions 反查原文字符触发。
 */
class WordWrapStrategyTest {

    /** 一字一方翻译：每字占 1 方；text[i] -> cell i -> text[i]。 */
    private fun translation(text: String, emptyCellChars: Set<Char> = setOf(' ')): TranslationResult {
        val n = text.length
        val cells = ByteArray(n) { i ->
            if (text[i] in emptyCellChars) 0.toByte() else 0x2801.toByte()
        }
        return TranslationResult(
            cells = cells,
            textToBraillePositions = IntArray(n) { it },
            brailleToTextPositions = IntArray(n) { it },
            cursorPosition = -1
        )
    }

    private fun content(text: String, splitParagraphs: Boolean = false): Content =
        Content().setText(text).setSplitParagraphs(splitParagraphs)

    // ---------- 无效输入 ----------

    @Test
    fun `invalid content resets state and blocks panning`() {
        val strategy = WordWrapStrategy()
        // 未设置内容时不可平移
        assertFalse(strategy.panLeft())
        assertFalse(strategy.panRight())

        // null 内容
        strategy.setContent(null, translation("abc"), 10)
        assertEquals(0, strategy.getDisplayStart())
        assertEquals(0, strategy.getDisplayEnd())

        // 空文本
        strategy.setContent(content(""), translation(""), 10)
        assertFalse(strategy.panLeft())

        // 非法宽度
        strategy.setContent(content("abc"), translation("abc"), 0)
        assertFalse(strategy.panRight())
    }

    // ---------- 基础分块与平移 ----------

    @Test
    fun `content wider than display breaks into fixed-width lines`() {
        // 30 方内容、10 方点显器、无拆行点 → 3 行
        val text = "a".repeat(30)
        val strategy = WordWrapStrategy()
        strategy.setContent(content(text), translation(text), 10)
        strategy.panTo(0, false)
        assertEquals(0, strategy.getDisplayStart())
        assertEquals(10, strategy.getDisplayEnd())

        assertTrue(strategy.panRight())
        assertEquals(10, strategy.getDisplayStart())
        assertEquals(20, strategy.getDisplayEnd())

        assertTrue(strategy.panRight())
        assertEquals(20, strategy.getDisplayStart())
        assertEquals(30, strategy.getDisplayEnd())

        // 到达右边缘
        assertFalse(strategy.panRight())

        assertTrue(strategy.panLeft())
        assertEquals(10, strategy.getDisplayStart())
        assertTrue(strategy.panLeft())
        assertEquals(0, strategy.getDisplayStart())
        // 到达左边缘
        assertFalse(strategy.panLeft())
    }

    @Test
    fun `display end is clamped to content length`() {
        val text = "a".repeat(25)
        val strategy = WordWrapStrategy()
        strategy.setContent(content(text), translation(text), 10)
        strategy.panTo(0, false)
        assertEquals(10, strategy.getDisplayEnd())
        strategy.panRight()
        assertEquals(20, strategy.getDisplayEnd())
        strategy.panRight()
        assertEquals(25, strategy.getDisplayEnd())
    }

    @Test
    fun `panTo without fix re-pivots line breaks to the target`() {
        val text = "a".repeat(30)
        val strategy = WordWrapStrategy()
        strategy.setContent(content(text), translation(text), 10)
        // 15 不是默认行首（0/10/20），fix=false 时以 15 为行首重算
        strategy.panTo(15, false)
        assertEquals(15, strategy.getDisplayStart())
        assertEquals(25, strategy.getDisplayEnd())
    }

    @Test
    fun `panTo with fix snaps to nearest earlier line break`() {
        val text = "a".repeat(30)
        val strategy = WordWrapStrategy()
        strategy.setContent(content(text), translation(text), 10)
        // fix=true：不改行首，吸附到 15 之前最近的行首（10）
        strategy.panTo(15, true)
        assertEquals(10, strategy.getDisplayStart())
        assertEquals(20, strategy.getDisplayEnd())
    }

    @Test
    fun `panTo clamps out-of-range positions`() {
        val text = "a".repeat(30)
        val strategy = WordWrapStrategy()
        strategy.setContent(content(text), translation(text), 10)
        strategy.panTo(999, false)
        // clampPosition 把超界位置收敛到 size-1，行首不得越过最后一行
        assertTrue(strategy.getDisplayStart() in 20..29)
        assertTrue(strategy.getDisplayEnd() <= 30)
    }

    // ---------- 可拆行点（空方）----------

    @Test
    fun `lines prefer breaking at empty cells`() {
        // 6 个三字词 + 空方，共 23 方，10 方点显器 → 至少 3 行
        // 在空方处拆行，行尾空方可被吸收
        val text = "aaa bbb ccc ddd eee fff"
        val strategy = WordWrapStrategy()
        strategy.setContent(content(text), translation(text), 10)
        strategy.panTo(0, false)
        // 首行在 ccc 前的空方处断行："aaa bbb " = [0,8)
        assertEquals(0, strategy.getDisplayStart())
        assertEquals("首行应在空方处断行", 8, strategy.getDisplayEnd())
        // 连续平移应覆盖全文且单调前进
        val seen = mutableSetOf<Int>()
        var current = strategy.getDisplayStart()
        while (true) {
            seen.add(current)
            if (!strategy.panRight()) break
            assertTrue("平移应单调前进", strategy.getDisplayStart() > current)
            current = strategy.getDisplayStart()
        }
        assertTrue("全文 23 方至少 3 行，实际 ${seen.size} 行", seen.size >= 3)
    }

    // ---------- 段落拆分（split points）----------

    @Test
    fun `split paragraphs force line breaks at newlines`() {
        // "abcd\nefgh"，'\n' 占一方，i=4 处换行 → 拆分点在 cell 5（次段首）
        val text = "abcd\nefgh"
        val strategy = WordWrapStrategy()
        strategy.setContent(content(text, splitParagraphs = true), translation(text, emptyCellChars = setOf(' ', '\n')), 10)
        strategy.panTo(0, false)
        assertEquals("段落边界必须强制断行", 5, strategy.getDisplayEnd())
        assertEquals(0, strategy.getDisplayStart())
    }

    @Test
    fun `split paragraphs ignored when flag is off`() {
        val text = "abcd\nefgh"
        val strategy = WordWrapStrategy()
        strategy.setContent(content(text, splitParagraphs = false), translation(text, emptyCellChars = setOf(' ', '\n')), 10)
        strategy.panTo(0, false)
        // 不分段时 9 方（含 '\n' 自占一方）一行装下
        assertEquals(9, strategy.getDisplayEnd())
    }

    // ---------- 避头点（行首不得是标点）----------

    @Test
    fun `closing punctuation is pulled to previous line end within width`() {
        // 行在空方处提前断行（剩余宽度），下一字符是标点：
        // 避头点规则把标点收进行尾（仍在宽度内）→ 行尾=4，下一行从 4 开始
        // 行首是 'B' 而非标点，且无内容丢失。
        val text = "AA ，BB"
        val strategy = WordWrapStrategy()
        strategy.setContent(content(text), translation(text), 5)
        strategy.panTo(0, false)
        assertEquals("标点应收进行尾", 4, strategy.getDisplayEnd())
        assertTrue(strategy.panRight())
        assertEquals("下一行不应从标点开始", 4, strategy.getDisplayStart())
    }

    @Test
    fun `line never starts with closing punctuation when panning through`() {
        val text = "AA ，BBBBBB"
        val strategy = WordWrapStrategy()
        strategy.setContent(content(text), translation(text), 5)
        // 逐行右移，验证每一行的行首字符都不是闭标点（一字一方映射下
        // 行首下标即文字下标）。
        strategy.panTo(0, false)
        while (true) {
            val start = strategy.getDisplayStart()
            assertTrue("行首 $start 不应是标点 '${text[start]}'", !text[start].isClosingPunctForTest())
            if (!strategy.panRight()) break
        }
    }

    private fun Char.isClosingPunctForTest(): Boolean =
        this in "，。！？；：、）】》〉”’…·,.!?;:)]}>\"'"

    // ---------- 避尾点（行尾不得是开括号）----------

    @Test
    fun `opening punctuation is pushed to next line start`() {
        // 6 方文本、5 方点显器：默认行尾 cell 4 是 '（'
        // 避尾点规则把开括号推到下一行 → 行尾缩到 4
        val text = "AAAB（C"
        val strategy = WordWrapStrategy()
        strategy.setContent(content(text), translation(text), 5)
        strategy.panTo(0, false)
        assertEquals("开括号不应留在行尾", 4, strategy.getDisplayEnd())
    }

    // ---------- 满宽避头点：回退断点，不得吞字符 ----------

    @Test
    fun `full-width line must not skip punctuation pulled into line end`() {
        // 现场（已用探针实证，5 方点显器、一字一方映射）：
        //   text = "AAAAA，B"
        //   避头点逻辑把内部行尾从 5 推到 6（把 '，' 收进行尾），
        //   但 getDisplayEnd() = min(start+width, mDisplayEnd) = 5，
        //   于是 L1 显示 [0,5)="AAAAA"，panRight 后 L2 从 6 开始，
        //   → cell 5 的 '，' 永远不会被显示（内容丢失）。
        //
        // 期望的正确行为（修复后本测试应通过）：
        //   行满宽且下一字符是标点时，应回退断点让标点连同前一字符
        //   一起落到下一行（L1=[0,4)，L2 从 4 开始），保证：
        //   1) 无内容丢失； 2) 行首不是标点。
        val text = "AAAAA，BBBB"
        val width = 5
        val strategy = WordWrapStrategy()
        strategy.setContent(content(text), translation(text), width)
        strategy.panTo(0, false)
        // 收集每一行的 [start, end) 显示窗口
        val windows = mutableListOf<Pair<Int, Int>>()
        while (true) {
            windows.add(strategy.getDisplayStart() to strategy.getDisplayEnd())
            if (!strategy.panRight()) break
        }
        // 1) 无内容丢失：每一行的终点必须等于下一行的起点
        for (i in 1 until windows.size) {
            assertEquals(
                "第 ${i - 1} 行终点 ${windows[i - 1].second} 应等于第 $i 行起点 ${windows[i].first}（无内容丢失）",
                windows[i - 1].second, windows[i].first
            )
        }
        // 2) 覆盖到文末
        assertEquals("最后一行应到达文末", text.length, windows.last().second)
        // 3) 行首不得是标点
        for ((s, _) in windows) {
            assertTrue("行首 $s 不应是标点", !text[s].isClosingPunctForTest())
        }
    }

    @Test
    fun `punctuation-heavy chinese text pans without losing cells`() {
        val text = ("你好，世界。这是一段用于验证避头点规则的中文文本，" +
            "它足够长，会跨越多行；其中包括逗号、句号！还有问号？" +
            "分号；冒号：顿号、以及括号（）等等，用来覆盖各种标点场景。")
        val width = 12
        val strategy = WordWrapStrategy()
        strategy.setContent(content(text), translation(text), width)
        strategy.panTo(0, false)
        val windows = mutableListOf<Pair<Int, Int>>()
        while (true) {
            windows.add(strategy.getDisplayStart() to strategy.getDisplayEnd())
            if (!strategy.panRight()) break
        }
        // 1) 行与行之间不得出现空洞（后一行起点不得越过前一行显示终点）
        for (i in 1 until windows.size) {
            assertTrue(
                "第 $i 行起点 ${windows[i].first} 越过了第 ${i - 1} 行显示终点 ${windows[i - 1].second}",
                windows[i].first <= windows[i - 1].second
            )
        }
        // 2) 覆盖到文末
        assertEquals("最后一行应到达文末", text.length, windows.last().second)
        // 3) 窗口不越界
        for ((s, e) in windows) {
            assertTrue("窗口宽度 $s..$e 超过点显器 $width", e - s <= width)
        }
        // 4) 行首不得是闭标点
        for ((s, _) in windows) {
            assertTrue("行首 $s 是标点 '${text[s]}'", !text[s].isClosingPunctForTest())
        }
    }

    @Test
    fun `panning left from the end keeps the same invariants`() {
        // 注意：行划分以 pivot 为中心双向计算，向左与向右的具体断点可以
        // 不同（Java 版同样如此，属设计特性）。这里只校验不变量：
        // 无空洞、覆盖全文、窗口不越界。
        val text = "abcdefg，hijklmn。opqrst，uvwxyz！"
        val width = 7
        val left = WordWrapStrategy()
        left.setContent(content(text), translation(text), width)
        left.panTo(text.length - 1, false)
        val backward = mutableListOf<Pair<Int, Int>>()
        while (true) {
            backward.add(left.getDisplayStart() to left.getDisplayEnd())
            if (!left.panLeft()) break
        }
        backward.reverse()

        for (i in 1 until backward.size) {
            assertTrue(
                "向左：第 $i 行起点 ${backward[i].first} 越过上一行终点 ${backward[i - 1].second}",
                backward[i].first <= backward[i - 1].second
            )
        }
        assertEquals("向左：首行应从 0 开始", 0, backward.first().first)
        assertEquals("向左：末行应到达文末", text.length, backward.last().second)
        for ((s, e) in backward) {
            assertTrue("向左：窗口 $s..$e 超宽", e - s <= width)
        }
    }

    @Test
    fun `randomized text keeps all invariants at any width`() {
        val random = java.util.Random(20260902L)
        val chars = "汉字词语段落显示平移测试内容盲文点位".toCharArray()
        val puncts = "，。！？；：、".toCharArray()
        repeat(300) { iteration ->
            val n = 12 + random.nextInt(70)
            val sb = StringBuilder()
            for (i in 0 until n) {
                when {
                    random.nextInt(100) < 22 -> sb.append(puncts[random.nextInt(puncts.size)])
                    random.nextInt(100) < 8 -> sb.append(' ')
                    else -> sb.append(chars[random.nextInt(chars.size)])
                }
            }
            var text = sb.toString().trim()
            if (text.isEmpty()) return@repeat
            // 首字符若是标点，第一行仍从 0 开始，属不可避免情况，排除掉
            if (text[0].isClosingPunctForTest()) {
                text = text.dropWhile { it.isClosingPunctForTest() || it == ' ' }
            }
            if (text.isEmpty()) return@repeat
            val width = 3 + random.nextInt(13)

            // ---- 向右平移 ----
            val right = WordWrapStrategy()
            right.setContent(content(text), translation(text), width)
            right.panTo(0, false)
            var prevDisplayedEnd = -1
            var prevStart = -1
            var lines = 0
            while (true) {
                val s = right.getDisplayStart()
                val e = right.getDisplayEnd()
                assertTrue("#$iteration 窗口非空 ($s..$e, n=${text.length})", e > s)
                assertTrue("#$iteration 窗口越右界 ($e > ${text.length})", e <= text.length)
                assertTrue("#$iteration 窗口超宽 (${e - s} > $width)", e - s <= width)
                assertTrue("#$iteration 起点不后退", s > prevStart)
                if (prevDisplayedEnd >= 0 && s > prevDisplayedEnd) {
                    // 允许省略行尾空白（removable break point，盲文空方在行尾
                    // 不可见），但绝不允许跳过实字符。
                    val skipped = (prevDisplayedEnd until s).map { text[it] }
                    assertTrue(
                        "#$iteration 丢失内容：[$prevDisplayedEnd,$s) = \"${skipped.joinToString("")}\"" +
                            " (width=$width, text=\"$text\")",
                        skipped.all { it == ' ' }
                    )
                }
                // 病理豁免：若上一行可选的断点范围(start, start+width] 内全是
                // 闭标点（如连着 "；、：："），则任何断点都会让下一行以标点开头，
                // 此时算法按「不吞字优先」处理，属正确取舍。
                if (s > 0 && prevStart >= 0) {
                    val candidates =
                        (prevStart + 1..minOf(prevStart + width, text.length - 1)).map { text[it] }
                    val unavoidable =
                        candidates.isNotEmpty() && candidates.all { it.isClosingPunctForTest() }
                    if (!unavoidable) {
                        assertTrue(
                            "#$iteration 行首 $s 是标点 '${text[s]}' (width=$width, text=\"$text\")",
                            !text[s].isClosingPunctForTest()
                        )
                    }
                }
                prevStart = s
                prevDisplayedEnd = e
                lines++
                assertTrue("#$iteration 平移未终止（死循环？已 $lines 行）", lines < text.length + 5)
                if (!right.panRight()) break
            }
            assertEquals("#$iteration 最后一行应到达文末", text.length, prevDisplayedEnd)

            // ---- 向左平移（反向行划分，同样不得丢内容）----
            val left = WordWrapStrategy()
            left.setContent(content(text), translation(text), width)
            left.panTo(text.length - 1, false)
            var prevStartBack = text.length
            var firstStartBack = -1
            var linesBack = 0
            while (true) {
                val s = left.getDisplayStart()
                val e = left.getDisplayEnd()
                assertTrue("#$iteration(左) 窗口非空 ($s..$e)", e > s)
                assertTrue("#$iteration(左) 窗口超宽 (${e - s} > $width)", e - s <= width)
                if (e < prevStartBack) {
                    val skipped = (e until prevStartBack).map { text[it] }
                    assertTrue(
                        "#$iteration(左) 丢失内容：[$e,$prevStartBack) = \"${skipped.joinToString("")}\"" +
                            " (width=$width, text=\"$text\")",
                        skipped.all { it == ' ' }
                    )
                }
                firstStartBack = s
                prevStartBack = s
                linesBack++
                assertTrue(
                    "#$iteration(左) 平移未终止（死循环？已 $linesBack 行）",
                    linesBack < text.length + 5
                )
                if (!left.panLeft()) break
            }
            assertEquals("#$iteration(左) 首行应从 0 开始", 0, firstStartBack)
        }
    }

    @Test
    fun `display window always within content bounds`() {
        val text = "x".repeat(57)
        val strategy = WordWrapStrategy()
        strategy.setContent(content(text), translation(text), 13)
        for (pos in listOf(0, 7, 14, 33, 50, 56)) {
            strategy.panTo(pos, false)
            val start = strategy.getDisplayStart()
            val end = strategy.getDisplayEnd()
            assertTrue("pos=$pos start=$start 不越左界", start >= 0)
            assertTrue("pos=$pos end=$end 不越右界(57)", end <= 57)
            assertTrue("pos=$pos 窗口非空", end > start)
            assertTrue("pos=$pos 窗口宽度不超点显器", end - start <= 13)
        }
    }
}
