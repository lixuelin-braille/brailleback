/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 刷新闸门。
 *
 * 这里钉住的是原来那三个 `@Volatile` 之间的不变量：指纹只在真的送达后才转正，
 * 否则「恢复刷新后的第一帧」会被自己当成重复帧丢掉，屏上停在旧内容。
 */
class RefreshGateTest {

    @Test
    fun `没送达过时任何指纹都不算重复`() {
        val gate = RefreshGate()
        assertEquals("初始送达时刻必须是 0，否则限速会把第一帧推后", 0L, gate.lastDeliveredAt())
        assertFalse(gate.isDuplicateOfLast("A"))
    }

    @Test
    fun `送达后同指纹才算重复`() {
        val gate = RefreshGate()
        gate.markPending("A")
        gate.onDelivered(1000L)

        assertTrue("同一帧重绘要能被丢弃", gate.isDuplicateOfLast("A"))
        assertFalse(gate.isDuplicateOfLast("B"))
    }

    @Test
    fun `待发指纹未送达就不转正`() {
        val gate = RefreshGate()
        gate.markPending("A")
        gate.onDelivered(1000L)
        // 下一帧只是排队，被冻结拦下没发出去。
        gate.markPending("B")

        assertFalse("没送达就不能算重复，否则恢复后屏上还是 A", gate.isDuplicateOfLast("B"))
        assertTrue("上一帧的送达记录仍有效", gate.isDuplicateOfLast("A"))
    }

    @Test
    fun `送达时刻随帧更新`() {
        val gate = RefreshGate()
        gate.markPending("A")
        gate.onDelivered(1000L)
        assertEquals(1000L, gate.lastDeliveredAt())

        gate.markPending("B")
        gate.onDelivered(1250L)
        assertEquals(1250L, gate.lastDeliveredAt())
        assertTrue(gate.isDuplicateOfLast("B"))
        assertFalse("旧的已送达指纹要被替换掉", gate.isDuplicateOfLast("A"))
    }

    @Test
    fun `跨线程读写不出现撕裂`() {
        // 一帧送达 = 时刻 + 指纹一起转正。两个线程并发跑，读到的要么是送达前、
        // 要么是送达后的状态：不能出现「时刻已更新但指纹还是空的」。
        val gate = RefreshGate()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)

        val deliverer = Thread {
            start.await()
            repeat(200) { i ->
                gate.markPending("F$i")
                gate.onDelivered(1000L + i)
            }
            done.countDown()
        }
        val outlier = Thread {
            start.await()
            var torn = 0
            repeat(200) {
                val up = gate.lastDeliveredAt()
                if (up == 0L && gate.isDuplicateOfLast("F0")) torn++
            }
            if (torn > 0) error("读到送达时刻为 0 却已认得指纹 F0")
            done.countDown()
        }
        deliverer.start(); outlier.start(); start.countDown()
        assertTrue("并发用例超时", done.await(10, TimeUnit.SECONDS))

        assertTrue("最后一次送达必须生效", gate.lastDeliveredAt() >= 1000L)
        assertTrue(gate.isDuplicateOfLast("F199"))
    }
}
