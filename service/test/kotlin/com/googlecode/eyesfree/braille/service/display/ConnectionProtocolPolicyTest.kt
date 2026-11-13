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
        for (value in listOf(null, "", "auto", "hid", "rfcomm", "wujie")) {
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
}
