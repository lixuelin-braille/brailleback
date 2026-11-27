/*
 * Copyright (C) 2026 The BrailleBack Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.googlecode.eyesfree.braille.service.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AutoRetryGate] 纯状态机测试：注入可控时钟，不起蓝牙、不依赖 Android 框架类，
 * 不靠真实 sleep 卡时间。生产接线（活跃态是否真的喂进守卫）由
 * `AutoRetryWiringContractTest` 从源码层面另守一条。
 */
class AutoRetryGateTest {

    private var now = 0L
    private fun gate() = AutoRetryGate(
        minIntervalMillis = AutoRetryGate.AUTO_RETRY_MIN_INTERVAL_MS,
        dedupeWindowMillis = AutoRetryGate.AUTO_RETRY_DEDUPE_WINDOW_MS,
        settleMillis = AutoRetryGate.CONNECTION_SETTLE_MS,
        clock = { now },
    )

    @Test
    fun `five repeated HID appearances within one second produce a single attempt`() {
        val gate = gate()
        val key = "hid:42"
        var released = 0
        // 1 秒内连续 5 次「HID input device appeared」，去重窗口内只放行第一次。
        for (i in 0 until 5) {
            now = i * 120L
            if (gate.shouldAllow(active = false, inFlight = false, sourceKey = key)) {
                gate.recordReleased(key)
                released++
            }
        }
        assertEquals(1, released)
    }

    @Test
    fun `same device repeated broadcast is ignored within the dedupe window`() {
        val gate = gate()
        val key = "hid:7"
        assertTrue(gate.shouldAllow(false, false, key))
        gate.recordReleased(key)
        // 去重窗口内同一设备重复出现：忽略。
        now = 500L
        assertFalse(gate.shouldAllow(false, false, key))
        // 已过 800ms 最小间隔、但仍在 2000ms 去重窗口内：同设备依旧被吞。
        now = AutoRetryGate.AUTO_RETRY_MIN_INTERVAL_MS + 1L
        assertFalse(gate.shouldAllow(false, false, key))
        // 超过去重窗口后，同一设备再次出现可以放行（不是永久拉黑）。
        now = AutoRetryGate.AUTO_RETRY_DEDUPE_WINDOW_MS + 1L
        assertTrue(gate.shouldAllow(false, false, key))
    }

    @Test
    fun `different sources do not block each other`() {
        val gate = gate()
        assertTrue(gate.shouldAllow(false, false, "hid:1"))
        gate.recordReleased("hid:1")
        // 另一台设备在 800ms 内出现：不被 hid:1 的冷却 / 去重挡住。
        now = 100L
        assertTrue(gate.shouldAllow(false, false, "hid:2"))
    }

    @Test
    fun `null source key falls back to global min interval`() {
        val gate = gate()
        assertTrue(gate.shouldAllow(false, false, null))
        gate.recordReleased(null)
        // 无稳定键：全局最小间隔内重复触发被挡。
        now = 500L
        assertFalse(gate.shouldAllow(false, false, null))
        // 超过最小间隔后放行。
        now = AutoRetryGate.AUTO_RETRY_MIN_INTERVAL_MS + 1L
        assertTrue(gate.shouldAllow(false, false, null))
    }

    @Test
    fun `settle period after being active does not judge late broadcasts as failures`() {
        val gate = gate()
        // 先观察到一次活跃（连接成功 / 在途）：活跃短路，本身不放行重连。
        assertFalse(gate.shouldAllow(active = true, inFlight = false, sourceKey = null))
        // 状态短暂回落空闲：settle 稳定期内的迟到广播不判失败、不重试。
        now = 500L
        assertFalse(gate.shouldAllow(active = false, inFlight = false, sourceKey = "hid:9"))
        // 超过 settle 期后才允许再判。
        now = AutoRetryGate.CONNECTION_SETTLE_MS + 1L
        assertTrue(gate.shouldAllow(active = false, inFlight = false, sourceKey = "hid:9"))
    }

    @Test
    fun `active or in-flight attempt is never interrupted by a new trigger`() {
        val gate = gate()
        assertTrue(gate.shouldAllow(false, false, "hid:1"))
        gate.recordReleased("hid:1")
        now = 5_000L
        // 已连上：短路返回。
        assertFalse(gate.shouldAllow(active = true, inFlight = false, sourceKey = "hid:2"))
        // 有在途尝试（待连 / 等控制器）：同样不打断、不重建。
        assertFalse(gate.shouldAllow(active = false, inFlight = true, sourceKey = "hid:2"))
    }

    @Test
    fun `first trigger with no history is allowed`() {
        val gate = gate()
        assertTrue(gate.shouldAllow(false, false, "hid:3"))
    }
}
