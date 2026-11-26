/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 「到文末」播报判据：翻译仍在追加时不报（翻译前沿不是书末）；
 * 翻译结束后只报一次。
 */

package com.linxi.brailleback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderEndAnnounceTest {

    @Test
    fun `翻译仍在追加时不报到头`() {
        // 翻译中：无论是否已报过，都不把翻译前沿当书末。
        assertFalse(canAnnounceBookEnd(translating = true, alreadyAnnounced = false))
        assertFalse(canAnnounceBookEnd(translating = true, alreadyAnnounced = true))
    }

    @Test
    fun `翻译结束且未报过才允许播报`() {
        assertTrue(canAnnounceBookEnd(translating = false, alreadyAnnounced = false))
    }

    @Test
    fun `同一文末不重复播报`() {
        assertFalse(canAnnounceBookEnd(translating = false, alreadyAnnounced = true))
    }

    @Test
    fun `往回退到倒数第二页之前才复位播报标记`() {
        // 14 → 12，总 150：退回末尾两页之外，解除标记。
        assertTrue(shouldReenableEndAnnounce(newUnit = 12, oldUnit = 14, totalUnits = 150))
    }

    @Test
    fun `往前滑贴近末尾不复位`() {
        // 144 → 145 是前进；145 → 146 也是前进：都不能解除标记，避免同一文末反复播报。
        assertFalse(shouldReenableEndAnnounce(newUnit = 145, oldUnit = 144, totalUnits = 150))
        assertFalse(shouldReenableEndAnnounce(newUnit = 146, oldUnit = 145, totalUnits = 150))
    }

    @Test
    fun `往回退仍在末尾两页之内不复位`() {
        // 149 → 148，总 150：还在末尾两页之间，保持已播报。
        assertFalse(shouldReenableEndAnnounce(newUnit = 148, oldUnit = 149, totalUnits = 150))
    }

    @Test
    fun `页号未变不复位`() {
        assertFalse(shouldReenableEndAnnounce(newUnit = 38, oldUnit = 38, totalUnits = 41))
    }
}
