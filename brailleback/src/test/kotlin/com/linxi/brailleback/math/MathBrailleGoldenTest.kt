/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 中文数学盲文通道 golden 核对（20 条，全部 Brailix 实跑对齐）。
 *
 * 来源标注：「Brailix 实跑」——离线调用 Brailix 源码 Pipeline(profile="cn_current")
 * 的 translate_math_inline(mathml, "mathml") 产出，逐字符对齐；调用方式见交付说明 4.4。
 * 注：Brailix 后端不受理 <mfenced>，其实跑输入为 mrow 内显式 <mo>(</mo>/<mo>)</mo>
 * 等价形态；括号类用例在本测试里可用 <mfenced> 表达同一语义，矩阵/方程组必须用
 * mrow + 显式 <mo> 围栏形态（与 Brailix 一致）。
 */

package com.linxi.brailleback.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MathBrailleGoldenTest {

    private data class Golden(
        val label: String,
        val source: String,
        val mathml: String,
        val expected: String,
    )

    private val goldens = listOf(
        Golden(
            "a²+b²=c²",
            "Brailix 实跑",
            "<math><mrow><msup><mi>a</mi><mn>2</mn></msup><mo>+</mo>" +
                "<msup><mi>b</mi><mn>2</mn></msup><mo>=</mo>" +
                "<msup><mi>c</mi><mn>2</mn></msup></mrow></math>",
            "⠰⠁⠌⠆⠀⠖⠰⠃⠌⠆⠀⠶⠰⠉⠌⠆",
        ),
        Golden(
            "1/2",
            "Brailix 实跑",
            "<math><mfrac><mn>1</mn><mn>2</mn></mfrac></math>",
            "⠼⠁⠆",
        ),
        Golden(
            "√2",
            "Brailix 实跑",
            "<math><msqrt><mn>2</mn></msqrt></math>",
            "⠩⠱⠼⠃⠹",
        ),
        Golden(
            "sin(x)",
            "Brailix 实跑",
            "<math><mrow><mi>sin</mi><mfenced><mi>x</mi></mfenced></mrow></math>",
            "⠫⠎⠣⠰⠭⠜",
        ),
        Golden(
            "(a+b)/2",
            "Brailix 实跑",
            "<math><mfrac><mfenced><mrow><mi>a</mi><mo>+</mo><mi>b</mi></mrow></mfenced><mn>2</mn></mfrac></math>",
            "⠆⠣⠰⠁⠀⠖⠰⠃⠜⠀⠳⠼⠃⠰",
        ),
        Golden(
            "x^2",
            "Brailix 实跑",
            "<math><msup><mi>x</mi><mn>2</mn></msup></math>",
            "⠰⠭⠌⠆",
        ),
        Golden(
            "√(a+b)",
            "Brailix 实跑",
            "<math><msqrt><mfenced><mrow><mi>a</mi><mo>+</mo><mi>b</mi></mrow></mfenced></msqrt></math>",
            "⠩⠱⠣⠰⠁⠀⠖⠰⠃⠜⠹",
        ),
        Golden(
            "f(x,y)",
            "Brailix 实跑",
            "<math><mrow><mi>f</mi><mfenced><mi>x</mi><mi>y</mi></mfenced></mrow></math>",
            "⠰⠋⠣⠰⠭⠐⠀⠰⠽⠜",
        ),
        Golden(
            "2+3=5",
            "Brailix 实跑",
            "<math><mrow><mn>2</mn><mo>+</mo><mn>3</mn><mo>=</mo><mn>5</mn></mrow></math>",
            "⠼⠃⠀⠖⠼⠉⠀⠶⠼⠑",
        ),
        Golden(
            "[a,b]",
            "Brailix 实跑",
            "<math><mfenced open=\"[\" close=\"]\"><mi>a</mi><mi>b</mi></mfenced></math>",
            "⠷⠰⠁⠐⠀⠰⠃⠾",
        ),
        Golden(
            "x_i^2 (msubsup)",
            "Brailix 实跑",
            "<math><msubsup><mi>x</mi><mi>i</mi><mn>2</mn></msubsup></math>",
            "⠰⠭⠡⠊⠱⠌⠆",
        ),
        Golden(
            "∛8 (mroot)",
            "Brailix 实跑",
            "<math><mroot><mn>8</mn><mn>3</mn></mroot></math>",
            "⠩⠼⠉⠱⠼⠓⠹",
        ),
        Golden(
            "√√2 (根号嵌套)",
            "Brailix 实跑",
            "<math><msqrt><msqrt><mn>2</mn></msqrt></msqrt></math>",
            "⠩⠱⠩⠱⠼⠃⠹⠹",
        ),
        Golden(
            "matrix 2×2",
            "Brailix 实跑",
            "<math><mrow><mo>(</mo><mtable>" +
                "<mtr><mtd><mi>a</mi></mtd><mtd><mi>b</mi></mtd></mtr>" +
                "<mtr><mtd><mi>c</mi></mtd><mtd><mi>d</mi></mtd></mtr>" +
                "</mtable><mo>)</mo></mrow></math>",
            "⠀⠣⠰⠁⠀⠰⠃⠜⠀⠣⠰⠉⠀⠰⠙⠜⠀",
        ),
        Golden(
            "cases 2 rows",
            "Brailix 实跑",
            "<math><mrow><mo>{</mo><mtable>" +
                "<mtr><mtd><mi>x</mi><mo>+</mo><mi>y</mi></mtd></mtr>" +
                "<mtr><mtd><mi>x</mi><mo>−</mo><mi>y</mi></mtd></mtr>" +
                "</mtable></mrow></math>",
            "⠀⠎⠇⠣⠎⠀⠰⠭⠀⠖⠰⠽⠀⠣⠀⠰⠭⠀⠤⠰⠽⠀",
        ),
        Golden(
            "x̄ (bar accent)",
            "Brailix 实跑",
            "<math><mover><mi>x</mi><mo>¯</mo></mover></math>",
            "⠰⠭⠘⠒",
        ),
        Golden(
            "v⃗ (vec accent)",
            "Brailix 实跑",
            "<math><mover><mi>v</mi><mo>→</mo></mover></math>",
            "⠰⠧⠘⠒⠂",
        ),
        Golden(
            "greek α",
            "Brailix 实跑",
            "<math><mi>α</mi></math>",
            "⠨⠁",
        ),
        Golden(
            "greek Ω",
            "Brailix 实跑",
            "<math><mi>Ω</mi></math>",
            "⠸⠺",
        ),
        Golden(
            "嵌套分数 (1/2)/3",
            "Brailix 实跑",
            "<math><mfrac><mfrac><mn>1</mn><mn>2</mn></mfrac><mn>3</mn></mfrac></math>",
            "⠼⠁⠆⠳⠼⠉",
        ),
    )

    private fun byLabel(label: String): Golden =
        goldens.first { it.label.startsWith(label) }

    @Test
    fun `golden 逐条对齐 Brailix 输出`() {
        goldens.forEach { g ->
            val result = MathBraille.encode(g.mathml, dotsPerCell = 6)
            assertEquals(
                "[${g.label}] 来源=${g.source} 盲文串不一致",
                g.expected,
                result.unicode,
            )
            assertEquals(g.expected.length, result.cells.size)
            // unicode 与 cells 双向一致。
            result.cells.forEachIndexed { i, mask ->
                assertEquals(
                    "第 $i 方掩码与字符不一致",
                    (0x2800 + mask).toChar(),
                    result.unicode[i],
                )
            }
        }
    }

    @Test
    fun `6 点输出高位第 7 8 点恒为 0`() {
        goldens.forEach { g ->
            val result = MathBraille.encode(g.mathml, dotsPerCell = 6)
            assertTrue(
                "${g.label} 存在高位非 0：${result.cells.joinToString()}",
                result.cells.all { it and 0xC0 == 0 },
            )
        }
    }

    @Test
    fun `8 点复用同一套 6 点点位且高位留 0 可扩展`() {
        val half = byLabel("1/2")
        val result6 = MathBraille.encode(half.mathml, dotsPerCell = 6)
        val result8 = MathBraille.encode(half.mathml, dotsPerCell = 8)
        assertEquals(result6.cells.toList(), result8.cells.toList())
        assertEquals(8, result8.dotsPerCell)
        assertTrue(result8.cells.all { it and 0xC0 == 0 })
    }

    @Test
    fun `不支持的标签抛明确异常并带标签名`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            MathBraille.encode("<math><mstack><mi>a</mi></mstack></math>")
        }
        assertTrue(e.message!!.contains("mstack"))
    }

    @Test
    fun `无表项的运算符抛异常不静默出错盲文`() {
        assertThrows(IllegalArgumentException::class.java) {
            MathBraille.encode("<math><mo>@</mo></math>")
        }
    }

    @Test
    fun `mtext 中文走可注入提供者`() {
        val provider = ChineseBrailleProvider { "⠓⠑⠇⠇⠕" }
        val result = MathBraille.encode(
            "<math><mtext>三角形</mtext></math>",
            dotsPerCell = 6,
            chineseProvider = provider,
        )
        assertEquals("⠓⠑⠇⠇⠕", result.unicode)
    }

    @Test
    fun `线性写法与 MathML 等价`() {
        val fromMathml = MathBraille.encode(byLabel("a²+b²=c²").mathml, dotsPerCell = 6)
        val fromLinear = MathBraille.encode("a^2+b^2=c^2", dotsPerCell = 6)
        assertEquals(fromMathml.unicode, fromLinear.unicode)

        val halfMathml = MathBraille.encode(byLabel("1/2").mathml, dotsPerCell = 6)
        val halfLinear = MathBraille.encode("1/2", dotsPerCell = 6)
        assertEquals(halfMathml.unicode, halfLinear.unicode)
    }

    @Test
    fun `全角标点与 Unicode 减号归一后等价半角线性写法`() {
        val half = MathBraille.encode("1/2", dotsPerCell = 6)
        val full = MathBraille.encode("1／2", dotsPerCell = 6)
        assertEquals(half.unicode, full.unicode)

        val halfEq = MathBraille.encode("a^2+b^2=c^2", dotsPerCell = 6)
        val fullEq = MathBraille.encode("a^2＋b^2＝c^2", dotsPerCell = 6)
        assertEquals(halfEq.unicode, fullEq.unicode)

        val minus = MathBraille.encode("a−b", dotsPerCell = 6)
        val minusAscii = MathBraille.encode("a-b", dotsPerCell = 6)
        assertEquals(minusAscii.unicode, minus.unicode)
    }

    @Test
    fun `运算符空格规则：二元前后出空方`() {
        val result = MathBraille.encode(byLabel("2+3=5").mathml, dotsPerCell = 6)
        // ⠼⠃ ⠖… 与 …⠉ ⠶…：+ 前、= 前各有一个空方。
        assertEquals(0, result.cells[2])
        assertEquals(0, result.cells[6])
    }

    @Test
    fun `矩阵四元素全部保留不丢单元`() {
        val result = MathBraille.encode(byLabel("matrix").mathml, dotsPerCell = 6)
        // 裸字母点位：a=1, b=3, c=9, d=25。
        for (bare in listOf(1, 3, 9, 25)) {
            assertTrue("矩阵缺元素裸点位 $bare：${result.unicode}", bare in result.cells.toList())
        }
        // 两行开合界符各一对：( =35, ) =28。
        assertEquals(2, result.cells.count { it == 35 })
        assertEquals(2, result.cells.count { it == 28 })
    }

    @Test
    fun `方程组两行都有 cases 段前缀不丢行`() {
        val result = MathBraille.encode(byLabel("cases").mathml, dotsPerCell = 6)
        // 调色板 first/middle/last + 行首 first(14) / last(35)。
        assertTrue("缺 cases.first 段", 14 in result.cells.toList())
        assertTrue("缺 cases.last 段", 35 in result.cells.toList())
        // 两行的 x 裸点位（45）都在。
        assertEquals(2, result.cells.count { it == 45 })
    }

    @Test
    fun `希腊字母带 greek class 前缀`() {
        val alpha = MathBraille.encode(byLabel("greek α").mathml, dotsPerCell = 6)
        assertEquals("⠨⠁", alpha.unicode)
        // greek_lower 前缀 40，裸 α=1。
        assertEquals(40, alpha.cells[0])
        assertEquals(1, alpha.cells[1])

        val omega = MathBraille.encode(byLabel("greek Ω").mathml, dotsPerCell = 6)
        assertEquals("⠸⠺", omega.unicode)
        // greek_upper 前缀 56，裸 Ω=58。
        assertEquals(56, omega.cells[0])
        assertEquals(58, omega.cells[1])
    }
}
