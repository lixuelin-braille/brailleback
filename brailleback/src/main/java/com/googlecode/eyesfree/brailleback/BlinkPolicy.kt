/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

/**
 * 点显器覆盖点的闪烁规则。
 *
 * 原实现按「整屏字节是否与叠加版不同」决定要不要闪，而**焦点标记本身**就会让两者
 * 不同（`NodeBrailler` 给带无障碍焦点的节点整段加 `FocusSpan`）。于是只要屏幕上有
 * 焦点，屏上那一格就按 600ms 亮 / 800ms 灭无限循环；每次翻转还要把整屏重发给点显器，
 * 用户看到的就是「盲文点一直在闪」。
 *
 * 闪烁的原始语义是**可编辑光标**（输入框里那个位置提示），焦点标记只是「你在这一条」，
 * 不需要闪。所以这里把判定收窄到「当前显示区里有没有选区/光标」。
 */
internal object BlinkPolicy {

    /**
     * @param cells 选区/光标覆盖到的盲文格下标
     * @param start 当前显示区起始格（含）
     * @param end 当前显示区结束格（不含）
     * @return 显示区内是否有点需要明灭
     */
    fun shouldBlink(cells: Set<Int>, start: Int, end: Int): Boolean {
        for (cell in cells) {
            if (cell >= start && cell < end) {
                return true
            }
        }
        return false
    }

    /**
     * 这次刷新该发哪一份内容：叠加版（带选区/焦点点）还是原版。
     *
     * 不闪的时候**必须**用叠加版。闪烁是把 `overlaysOn` 来回翻转实现的，
     * 取消闪烁时它可能正停在 `false`，沿用下去会让焦点点凭空消失，
     * 一直到下次内容变化才回来。
     */
    fun showOverlays(blink: Boolean, overlaysOn: Boolean): Boolean = !blink || overlaysOn
}
