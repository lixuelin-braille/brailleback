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
 * Pure-JVM port of the wired-device check in Google TalkBack 16.2
 * (`UsbConnectManager.isHidDevice`): a display that speaks the standard Braille
 * HID protocol exposes at least one USB interface whose class is
 * [USB_CLASS_HID]. On Android 15+ the system [android.accessibilityservice
 * .BrailleDisplayController] owns the report descriptor and the transport, so
 * this path deliberately does **not** consult brltty's VID/PID serial driver
 * table.
 *
 * Android `UsbDevice` objects never cross this boundary, which keeps the
 * decision testable on a plain JVM.
 */
data class UsbHidCandidate(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val interfaceClasses: IntArray,
    val interfaceSubclasses: IntArray = IntArray(interfaceClasses.size),
    val interfaceProtocols: IntArray = IntArray(interfaceClasses.size),
)

object UsbHidDevicePolicy {
    /** `UsbConstants.USB_CLASS_HID`, inlined so this file has no Android deps. */
    const val USB_CLASS_HID = 3
    private const val BOOT_SUBCLASS = 1
    private const val BOOT_KEYBOARD = 1
    private const val BOOT_MOUSE = 2

    @JvmStatic
    fun hasHidInterface(interfaceClasses: IntArray): Boolean =
        interfaceClasses.any { it == USB_CLASS_HID }

    /**
     * A generic HID interface is accepted, but an interface that is explicitly
     * a boot keyboard or boot mouse is not: those are almost certainly the
     * user's normal input devices, not a braille display.
     */
    @JvmStatic
    fun hasUsableHidInterface(candidate: UsbHidCandidate): Boolean {
        val classes = candidate.interfaceClasses
        for (index in classes.indices) {
            if (classes[index] != USB_CLASS_HID) {
                continue
            }
            val subclass = candidate.interfaceSubclasses.getOrNull(index) ?: 0
            val protocol = candidate.interfaceProtocols.getOrNull(index) ?: 0
            if (subclass == BOOT_SUBCLASS
                && (protocol == BOOT_KEYBOARD || protocol == BOOT_MOUSE)
            ) {
                continue
            }
            return true
        }
        return false
    }

    /** First attached device exposing a usable HID interface, or null. */
    @JvmStatic
    fun firstCompatible(candidates: List<UsbHidCandidate>): UsbHidCandidate? =
        candidates.firstOrNull { hasUsableHidInterface(it) }
}
