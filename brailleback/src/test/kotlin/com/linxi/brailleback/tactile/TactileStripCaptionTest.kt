/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * 封面 / 封底排版：长度恒定、右侧补空、6 点高位清零、装不下抛异常。
 */
class TactileStripCaptionTest {

    @Test
    fun pageLengthAndRightPadding() {
        // ⠁ = 点 1（pattern 1），⠂ = 点 2（pattern 2）；第二行整行空。
        val out = TactileStripCaption.page(listOf("⠁⠂", ""), 4, 2, 8)
        assertEquals(8, out.size)
        assertEquals(1, out[0].toInt() and 0xFF)
        assertEquals(2, out[1].toInt() and 0xFF)
        assertEquals(0, out[2].toInt())
        assertEquals(0, out[3].toInt())
        assertEquals(0, out[4].toInt())
    }

    @Test
    fun sixDotHighBitsCleared() {
        // U+28FF = pattern 0xFF；6 点下第 7/8 点必须清零。
        val out = TactileStripCaption.page(listOf(0x28FF.toChar().toString()), 4, 1, 6)
        assertEquals(0, out[0].toInt() and 0xC0)
    }

    @Test
    fun eightDotKeepsHighBits() {
        val out = TactileStripCaption.page(listOf(0x28FF.toChar().toString()), 4, 1, 8)
        assertEquals(0xFF, out[0].toInt() and 0xFF)
    }

    @Test
    fun tooManyLinesThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            TactileStripCaption.page(List(3) { "" }, 4, 2, 8)
        }
    }

    @Test
    fun lineTooLongThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            TactileStripCaption.page(listOf("⠁".repeat(5)), 4, 1, 8)
        }
    }
}
