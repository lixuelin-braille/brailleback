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
import org.junit.Test

class ConnectionProtocolPolicyTest {
    @Test
    fun bluetoothCoversEveryLegacyWirelessValue() {
        // "auto" 曾经是四种无线协议里的一个（HID 优先、再 RFCOMM），
        // 现在是独立的第三项，所以不在这张表里；其余旧值一律归到蓝牙。
        for (value in listOf(null, "", "hid", "rfcomm", "wujie", "bluetooth")) {
            assertEquals(
                ConnectionProtocolPolicy.BLUETOOTH,
                ConnectionProtocolPolicy.normalize(value),
            )
        }
    }

    @Test
    fun usbCoversBothWiredValues() {
        assertEquals(
            ConnectionProtocolPolicy.USB,
            ConnectionProtocolPolicy.normalize("usb"),
        )
        assertEquals(
            ConnectionProtocolPolicy.USB,
            ConnectionProtocolPolicy.normalize("usbhid"),
        )
    }

    @Test
    fun autoSurvivesNormalizationOrItWouldBecomeBluetooth() {
        // 归一化一旦把 auto 折成 bluetooth，"自动"这项就永远是蓝牙，选它等于没选。
        assertEquals(
            ConnectionProtocolPolicy.AUTO,
            ConnectionProtocolPolicy.normalize("auto"),
        )
    }

    @Test
    fun normalizeToleratesCaseAndPadding() {
        // 值来自 SharedPreferences 的历史写入，大小写/空白不应改变语义。
        assertEquals(
            ConnectionProtocolPolicy.AUTO,
            ConnectionProtocolPolicy.normalize("  Auto "),
        )
        assertEquals(
            ConnectionProtocolPolicy.USB,
            ConnectionProtocolPolicy.normalize("USB"),
        )
        assertEquals(
            ConnectionProtocolPolicy.BLUETOOTH,
            ConnectionProtocolPolicy.normalize(" Bluetooth\n"),
        )
    }
}
