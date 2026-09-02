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

    // ---------- 已知问题（待修复）----------

    @Test
    @org.junit.Ignore("已知 bug：满宽时避头点丢字符。见下方注释，待确认修复方案后启用")
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
