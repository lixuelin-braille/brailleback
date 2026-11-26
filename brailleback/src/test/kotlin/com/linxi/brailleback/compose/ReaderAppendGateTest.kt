/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 正文窗口续装判据：
 *  1. 新判据看末可见项距列表末尾的距离，与视口能摆几项无关；
 *  2. 旧判据看首可见项下标，可见项多于预留槽位时物理不可达（4/5/6 三档对照）；
 *  3. 同一 totalItemsCount 只放行一次，列表真正变长后才允许下一次续装。
 */

package com.linxi.brailleback.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAppendGateTest {

    /** 旧判据：首可见下标上限 = total − 可见项数；是否还能碰到 total − 4 的阈值。 */
    private fun oldCriterionReachable(total: Int, visible: Int): Boolean {
        val maxFirst = total - visible
        return maxFirst >= total - 4
    }

    @Test
    fun `末可见项贴到末尾预留槽内即续装`() {
        // total=14，预留 2 项：距末尾 ≤2（last ≥ 11）触发。
        assertTrue(shouldAppendMore(lastVisibleIndex = 13, totalItemsCount = 14))
        assertTrue(shouldAppendMore(lastVisibleIndex = 12, totalItemsCount = 14))
        assertTrue(shouldAppendMore(lastVisibleIndex = 11, totalItemsCount = 14))
        assertFalse(shouldAppendMore(lastVisibleIndex = 10, totalItemsCount = 14))
    }

    @Test
    fun `空列表或下标非法不续装`() {
        assertFalse(shouldAppendMore(lastVisibleIndex = -1, totalItemsCount = 14))
        assertFalse(shouldAppendMore(lastVisibleIndex = 0, totalItemsCount = 0))
    }

    @Test
    fun `旧判据可见 4 项可达 5 6 项不可达`() {
        // 实测现场 total=14：视口摆 5 项时首可见上限 9，阈值 10，差 1 项不可达。
        assertTrue(oldCriterionReachable(total = 14, visible = 4))
        assertFalse(oldCriterionReachable(total = 14, visible = 5))
        assertFalse(oldCriterionReachable(total = 14, visible = 6))
    }

    @Test
    fun `新判据与可见项数无关到末尾都触发`() {
        // 滚到末尾时末可见恒为 total − 1；无论视口摆 4/5/6 项，新判据都成立。
        val total = 14
        for (visible in intArrayOf(4, 5, 6)) {
            assertTrue(shouldAppendMore(lastVisibleIndex = total - 1, totalItemsCount = total))
        }
        // 同现场下旧判据只在可见 4 项时可达，5/6 项不可达。
        assertTrue(oldCriterionReachable(total = total, visible = 4))
        assertFalse(oldCriterionReachable(total = total, visible = 5))
        assertFalse(oldCriterionReachable(total = total, visible = 6))
    }

    @Test
    fun `同一 total 只放行一次追加`() {
        val gate = AppendGate()
        assertTrue(gate.canAppend(lastVisibleIndex = 13, totalItemsCount = 14))
        gate.noteAppended(14)
        // 列表没变长：同一 total 重复判定不再放行追加。
        assertFalse(gate.canAppend(lastVisibleIndex = 13, totalItemsCount = 14))
        assertFalse(gate.canAppend(lastVisibleIndex = 12, totalItemsCount = 14))
        // 待命未落定前不做文末探测。
        assertFalse(gate.isSettled())
    }

    @Test
    fun `列表真正变长后允许下一次追加`() {
        val gate = AppendGate()
        assertTrue(gate.canAppend(lastVisibleIndex = 13, totalItemsCount = 14))
        gate.noteAppended(14)
        // 续装追加了一页段落，total 14 -> 19；观察到变长后待命落定。
        gate.observeTotal(19)
        assertTrue(gate.isSettled())
        assertTrue(gate.canAppend(lastVisibleIndex = 18, totalItemsCount = 19))
        gate.noteAppended(19)
        assertFalse(gate.canAppend(lastVisibleIndex = 18, totalItemsCount = 19))
    }

    @Test
    fun `未贴到末尾不放行也不占用去重名额`() {
        val gate = AppendGate()
        assertFalse(gate.canAppend(lastVisibleIndex = 5, totalItemsCount = 14))
        // 前面未触发不记名额；贴到末尾后第一次仍应放行。
        assertTrue(gate.canAppend(lastVisibleIndex = 13, totalItemsCount = 14))
    }

    @Test
    fun `追加落定后贴在末尾可做文末探测`() {
        val gate = AppendGate()
        gate.noteAppended(14)
        // 待命期间不追加；停在同一 total 允许文末探测（走溢出/播报分支，不重复追加）。
        assertFalse(gate.canAppend(lastVisibleIndex = 13, totalItemsCount = 14))
        assertTrue(gate.canProbeEnd(14))
        gate.observeTotal(19)
        assertTrue(gate.isSettled())
        assertFalse(gate.canProbeEnd(19))
        // 落定后新窗口仍贴末尾：允许下一次追加；追加一次后同 total 再次封锁。
        assertTrue(gate.canAppend(lastVisibleIndex = 18, totalItemsCount = 19))
        gate.noteAppended(19)
        assertFalse(gate.canAppend(lastVisibleIndex = 18, totalItemsCount = 19))
    }
}
