package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点显器闪烁规则测试。
 *
 * 背景：焦点标记会让「叠加版」与「原版」字节不同，旧判定据此认为要闪，
 * 于是只要屏上有焦点就 600ms 亮 / 800ms 灭无限循环，点显器看着一直在闪。
 * 现在只有选区/光标才闪。这里把每条边界都钉住，避免又退回「比字节」的老路。
 */
class BlinkPolicyTest {

    @Test
    fun `显示区内的选区格要闪`() {
        assertTrue(BlinkPolicy.shouldBlink(setOf(12), 10, 20))
    }

    @Test
    fun `显示区起点那一格也算在内`() {
        // start 是闭端点：写成 `> start` 就把屏上第一格漏掉
        assertTrue(BlinkPolicy.shouldBlink(setOf(10), 10, 20))
    }

    @Test
    fun `显示区终点那一格不算`() {
        // end 是开端点
        assertFalse(BlinkPolicy.shouldBlink(setOf(20), 10, 20))
    }

    @Test
    fun `选区在屏幕左边外面不闪`() {
        assertFalse(BlinkPolicy.shouldBlink(setOf(9), 10, 20))
    }

    @Test
    fun `没有选区就不闪`() {
        // 最常见的真实输入：屏上有焦点标记，但没有可编辑光标。
        // 焦点格不进 cells，所以这里必须是空集。
        assertFalse(BlinkPolicy.shouldBlink(emptySet(), 10, 20))
    }

    @Test
    fun `多格选区只要有一格在屏上就闪`() {
        // 前一格在屏左、后一格在屏右，中间那格才落在显示区里
        assertTrue(BlinkPolicy.shouldBlink(setOf(3, 15, 25), 10, 20))
    }

    @Test
    fun `闪烁中按 overlaysOn 决定亮灭`() {
        assertTrue(BlinkPolicy.showOverlays(blink = true, overlaysOn = true))
        assertFalse(BlinkPolicy.showOverlays(blink = true, overlaysOn = false))
    }

    @Test
    fun `不闪时一律显示叠加点`() {
        // 关键分支：pulse 正好停在灭相时取消闪烁，覆盖点必须还在。
        // 沿用 overlaysOn=false 会让焦点点凭空消失到下次内容变化。
        assertTrue(BlinkPolicy.showOverlays(blink = false, overlaysOn = false))
        assertTrue(BlinkPolicy.showOverlays(blink = false, overlaysOn = true))
    }
}
