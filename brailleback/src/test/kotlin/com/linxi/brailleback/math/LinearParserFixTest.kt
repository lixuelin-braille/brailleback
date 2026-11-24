/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 修复轮回归锁：
 * - P1-1 线性解析器不再丢弃函数括号实参（sin(x) / f(x,y)）；
 * - P2-1 线性 `sqrt` 别名映射到与 √ / msqrt 同一根号节点；
 * - P2-3 6/8 点选中态描述映射（UI 语义在补测轮用 uiautomator dump 复核）。
 */

package com.linxi.brailleback.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LinearParserFixTest {

    private fun encode(input: String) = MathBraille.encode(input, dotsPerCell = 6)

    // ------------------------------------------------------------- P1-1 函数实参

    @Test
    fun `sin(x) 函数括号实参不丢弃`() {
        assertEquals("⠫⠎⠣⠰⠭⠜", encode("sin(x)").unicode)
    }

    @Test
    fun `f(x,y) 多实参逗号分隔不丢弃`() {
        assertEquals("⠰⠋⠣⠰⠭⠐⠀⠰⠽⠜", encode("f(x,y)").unicode)
    }

    @Test
    fun `sin( x ) 括号内外空格归一`() {
        assertEquals("⠫⠎⠣⠰⠭⠜", encode("sin( x )").unicode)
    }

    @Test
    fun `sin() 空实参抛可读错误不静默丢弃`() {
        val e = assertThrows(IllegalArgumentException::class.java) { encode("sin()") }
        assertTrue(e.message!!.contains("实参为空"))
    }

    @Test
    fun `sin(x 未闭合括号抛可读错误`() {
        val e = assertThrows(IllegalArgumentException::class.java) { encode("sin(x") }
        assertTrue(e.message!!.contains(")"))
    }

    // ------------------------------------------------------------- P2-1 sqrt 别名

    @Test
    fun `sqrt(2) 与 √2 同一根号输出`() {
        assertEquals("⠩⠱⠼⠃⠹", encode("sqrt(2)").unicode)
        assertEquals(encode("√2").unicode, encode("sqrt(2)").unicode)
    }

    @Test
    fun `sqrt(a+b) 与 golden √(a+b) 一致`() {
        assertEquals("⠩⠱⠣⠰⠁⠀⠖⠰⠃⠜⠹", encode("sqrt(a+b)").unicode)
    }

    @Test
    fun `Sqrt 大小写不敏感`() {
        assertEquals(encode("√2").unicode, encode("Sqrt(2)").unicode)
        assertEquals(encode("√2").unicode, encode("SQRT(2)").unicode)
    }

    @Test
    fun `sqrt x 裸因子形态可用`() {
        assertEquals(encode("√x").unicode, encode("sqrt x").unicode)
    }

    @Test
    fun `sqrtx 不误判为根号别名`() {
        val result = encode("sqrtx")
        // 根号开方标记 ⠩（dots 1,4）不应出现在首方。
        assertFalse(result.unicode.startsWith("⠩"))
        // 逐字母回退：s 裸点位在输出中。
        assertTrue(result.cells.isNotEmpty())
    }

    // ------------------------------------------------------------- P2-3 选中态映射

    @Test
    fun `选中态描述映射：选中返回已选标签`() {
        assertEquals("已选中", MathAccessibility.dotStateDescription(true, "已选中", "未选中"))
    }

    @Test
    fun `选中态描述映射：未选返回未选标签`() {
        assertEquals("未选中", MathAccessibility.dotStateDescription(false, "已选中", "未选中"))
    }
}
