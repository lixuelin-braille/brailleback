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
 * Bluetooth covers every wireless transport (standard Braille HID over GATT,
 * classic HID and classic RFCOMM). USB covers both the brltty serial driver
 * and USB HID.
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

    /**
     * 新用户默认档：偏好键从未写过时按 [AUTO] 走。
     *
     * 只改「读默认值」这一处：已经存下 `bluetooth` / `usb` 手动值的老用户
     * 不会被静默改写，[normalize] 对历史显式值的折法保持不变。
     */
    const val DEFAULT = AUTO

    @JvmStatic
    fun normalize(value: String?): String {
        val trimmed = value?.trim()?.lowercase() ?: return BLUETOOTH
        return when (trimmed) {
            USB, "usbhid" -> USB
            AUTO -> AUTO
            else -> BLUETOOTH
        }
    }

    /**
     * 读偏好的统一入口：缺省（新用户）给 [DEFAULT]，有显式值才归一化。
     * 与 [normalize] 分开，是为了不破坏「null 折蓝牙」这条既有兼容口径。
     */
    @JvmStatic
    fun readOrDefault(raw: String?): String = raw?.let { normalize(it) } ?: DEFAULT

    /** 点名了手动档（蓝牙 / USB）：自动路由不许把它拽走。 */
    @JvmStatic
    fun isManual(protocol: String?): Boolean {
        val value = normalize(protocol)
        return value == BLUETOOTH || value == USB
    }
}
