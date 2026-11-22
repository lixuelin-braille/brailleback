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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发现窗口用的名称匹配纯行为测试：命中支持表（含 TSM→sk 驱动）的点显器名
 * 才算候选；空名、手机等无关设备不进列表。
 */
class DeviceFinderNameMatchTest {

    @Test
    fun matchesTsmSkDisplayName() {
        assertTrue(DeviceFinder.matchesKnownName("TSM 3647"))
        assertTrue(DeviceFinder.matchesKnownName("TSM3647"))
    }

    @Test
    fun matchesOtherKnownDisplayNames() {
        assertTrue(DeviceFinder.matchesKnownName("Orbit Reader 20"))
        assertTrue(DeviceFinder.matchesKnownName("Brailliant BI 14"))
    }

    @Test
    fun doesNotMatchBlankOrUnknownNames() {
        assertFalse(DeviceFinder.matchesKnownName(null))
        assertFalse(DeviceFinder.matchesKnownName(""))
        assertFalse(DeviceFinder.matchesKnownName("   "))
        assertFalse(DeviceFinder.matchesKnownName("iPhone 15 Pro"))
        assertFalse(DeviceFinder.matchesKnownName("Bluetooth Mouse"))
    }
}
