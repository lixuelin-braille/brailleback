/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页状态机：首末页边界、越界不变、页签文案按封面 / 图形 / 封底区分。
 */
class TactileStripPagerTest {

    private fun oneBandBook(coverCount: Int, backCount: Int): StripBook {
        val slice = TactileStripSlicer.slice(
            BooleanArray(8 * 4) { true }, 8, 4,
            ImageStripEncoder.Spec.forDisplay(4, 1, 8)
        )
        return TactileStripBookBuilder.build(
            slice,
            ImageStripEncoder.Spec.forDisplay(4, 1, 8),
            List(coverCount) { ByteArray(4) },
            List(backCount) { ByteArray(4) },
        )
    }

    @Test
    fun boundariesAndLabel() {
        val pager = TactileStripPager(oneBandBook(2, 1))
        assertEquals(4, pager.pageCount)
        assertEquals(0, pager.index)
        assertTrue(pager.canNext())
        assertFalse(pager.canPrev())
        assertEquals("封面", pager.label())

        assertTrue(pager.next())
        assertEquals("封面", pager.label())
        assertTrue(pager.next())
        assertEquals(StripPageKind.GRAPHIC, pager.current.kind)
        assertEquals("第 3 页，第 1/1 带", pager.label())

        assertTrue(pager.next())
        assertEquals("封底", pager.label())
        assertFalse(pager.canNext())
        assertFalse(pager.next())
        assertEquals(3, pager.index)

        assertTrue(pager.prev())
        assertEquals(StripPageKind.GRAPHIC, pager.current.kind)
        assertTrue(pager.first())
        assertEquals(0, pager.index)
        assertFalse(pager.first())
    }

    @Test
    fun singlePageNoNavigation() {
        val b = oneBandBook(0, 0)
        val pager = TactileStripPager(b)
        assertEquals(1, pager.pageCount)
        assertFalse(pager.canNext())
        assertFalse(pager.canPrev())
        assertFalse(pager.next())
        assertFalse(pager.prev())
    }

    @Test
    fun emptyBookRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TactileStripPager(StripBook(emptyList(), 0, 4, 1, 8))
        }
    }
}
