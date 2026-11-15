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
 * The connection choices shown to the user.
 *
 * Bluetooth covers every wireless transport (HID, RFCOMM and the vendor BLE
 * path for Wujie). USB covers both the brltty serial driver and USB HID.
 * Auto means "pick at connect time": a plugged braille display wins over
 * Bluetooth, because a wired link does not share the radio and survives
 * Bluetooth being switched off. Legacy preference values are folded into
 * one of these three values.
 */
object ConnectionProtocolPolicy {
    const val BLUETOOTH = "bluetooth"
    const val USB = "usb"

    /**
     * Decide per connection attempt instead of pinning one transport.
     *
     * Kept as a stored value (not a derived one) so the user's choice survives
     * restarts and can be shown as selected in the settings list.
     */
    const val AUTO = "auto"

    const val PREFERENCE_KEY = "connection_protocol"

    @JvmStatic
    fun normalize(value: String?): String {
        val trimmed = value?.trim()?.lowercase() ?: return BLUETOOTH
        return when (trimmed) {
            USB, "usbhid" -> USB
            AUTO -> AUTO
            else -> BLUETOOTH
        }
    }
}
