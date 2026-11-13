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

/**
 * The two connection choices shown to the user.
 *
 * Bluetooth covers every wireless transport (HID, RFCOMM and the vendor BLE
 * path for Wujie). USB covers both the brltty serial driver and USB HID.
 * Legacy preference values are folded into one of these two values.
 */
object ConnectionProtocolPolicy {
    const val BLUETOOTH = "bluetooth"
    const val USB = "usb"

    const val PREFERENCE_KEY = "connection_protocol"

    @JvmStatic
    fun normalize(value: String?): String =
        if (value == USB || value == "usbhid") USB else BLUETOOTH
}
