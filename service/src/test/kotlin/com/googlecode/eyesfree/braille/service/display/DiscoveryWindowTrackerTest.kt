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
 * 用户发现窗口与自动重连窗口的来源区分 / 让位规则。纯状态机测试，不起
 * 蓝牙扫描、不依赖 Android 框架类。
 */
class DiscoveryWindowTrackerTest {

    private val duration = 12_000L

    @Test
    fun startsWithNoWindow() {
        val tracker = DiscoveryWindowTracker()
        assertFalse(tracker.isActive)
        assertFalse(tracker.isUserActive)
        assertFalse(tracker.isAutoActive)
        assertEquals(DiscoverySource.NONE, tracker.source)
    }

    @Test
    fun userWindowMarksUserSource() {
        val tracker = DiscoveryWindowTracker()
        tracker.startUser(now = 0L, durationMillis = duration)
        assertTrue(tracker.isUserActive)
        assertFalse(tracker.isAutoActive)
        assertEquals(DiscoverySource.USER_DISCOVERY, tracker.source)
    }

    @Test
    fun autoWindowYieldsToActiveUserWindow() {
        val tracker = DiscoveryWindowTracker()
        tracker.startUser(now = 0L, durationMillis = duration)
        // 用户窗口仍在有效期内：自动重连不得抢占、不得延长用户窗口。
        assertFalse(tracker.startAuto(now = 1_000L, durationMillis = duration))
        assertEquals(DiscoverySource.USER_DISCOVERY, tracker.source)
    }

    @Test
    fun autoWindowOpensAfterUserWindowExpired() {
        val tracker = DiscoveryWindowTracker()
        tracker.startUser(now = 0L, durationMillis = duration)
        assertTrue(tracker.startAuto(now = duration + 1L, durationMillis = duration))
        assertEquals(DiscoverySource.AUTO_RECONNECT, tracker.source)
    }

    @Test
    fun extendAutoDoesNotTouchUserWindow() {
        val tracker = DiscoveryWindowTracker()
        tracker.startUser(now = 0L, durationMillis = duration)
        assertFalse(tracker.extendAuto(now = 1_000L, durationMillis = duration))
        assertEquals(DiscoverySource.USER_DISCOVERY, tracker.source)
    }

    @Test
    fun extendAutoRefreshesAutoWindow() {
        val tracker = DiscoveryWindowTracker()
        assertTrue(tracker.startAuto(now = 0L, durationMillis = duration))
        assertTrue(tracker.extendAuto(now = 5_000L, durationMillis = duration))
        assertTrue(tracker.isAutoActive)
    }

    @Test
    fun expiredOnlyWhenActiveAndPastDeadline() {
        val tracker = DiscoveryWindowTracker()
        assertFalse(tracker.expired(now = 0L))
        tracker.startUser(now = 0L, durationMillis = duration)
        assertFalse(tracker.expired(now = duration - 1))
        assertTrue(tracker.expired(now = duration))
    }

    @Test
    fun finishClearsWindow() {
        val tracker = DiscoveryWindowTracker()
        tracker.startUser(now = 0L, durationMillis = duration)
        tracker.finish()
        assertFalse(tracker.isActive)
        assertEquals(DiscoverySource.NONE, tracker.source)
    }
}
