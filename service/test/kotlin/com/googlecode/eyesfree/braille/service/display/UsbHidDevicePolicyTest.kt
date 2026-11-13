/*
 * Copyright (C) 2026 The BrailleBack Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.googlecode.eyesfree.braille.service.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the wired USB HID device check ported from TalkBack 16.2. */
class UsbHidDevicePolicyTest {

    private fun candidate(id: Int, vararg classes: Int) =
        UsbHidCandidate(id, 0x0403, 0x6001, classes)

    @Test
    fun hidClassConstantMatchesAndroidUsbConstants() {
        assertEquals(3, UsbHidDevicePolicy.USB_CLASS_HID)
    }

    @Test
    fun noAttachedDevicesReturnsNull() {
        assertNull(UsbHidDevicePolicy.firstCompatible(emptyList()))
    }

    @Test
    fun serialAndVendorSpecificDevicesAreRejected() {
        assertFalse(UsbHidDevicePolicy.hasHidInterface(intArrayOf(2, 10, 255)))
        assertNull(
            UsbHidDevicePolicy.firstCompatible(
                listOf(candidate(1, 2, 255), candidate(2, 10)),
            ),
        )
    }

    @Test
    fun deviceWithoutInterfacesIsRejected() {
        assertNull(UsbHidDevicePolicy.firstCompatible(listOf(candidate(1))))
    }

    @Test
    fun hidInterfaceAnywhereInDeviceIsAccepted() {
        assertTrue(UsbHidDevicePolicy.hasHidInterface(intArrayOf(2, 3, 255)))
        assertEquals(
            7,
            UsbHidDevicePolicy.firstCompatible(
                listOf(candidate(7, 2, 3, 255)),
            )?.deviceId,
        )
    }

    @Test
    fun firstCompatibleDeviceWins() {
        val devices = listOf(candidate(1, 2), candidate(2, 3), candidate(3, 3))
        assertEquals(2, UsbHidDevicePolicy.firstCompatible(devices)?.deviceId)
    }

    @Test
    fun bootKeyboardAndMouseAreRejected() {
        val keyboard = UsbHidCandidate(
            1, 0x1234, 0x0001, intArrayOf(3), intArrayOf(1), intArrayOf(1))
        val mouse = UsbHidCandidate(
            2, 0x1234, 0x0002, intArrayOf(3), intArrayOf(1), intArrayOf(2))
        val braille = UsbHidCandidate(
            3, 0x0403, 0xF208, intArrayOf(3), intArrayOf(0), intArrayOf(0))
        assertEquals(
            3,
            UsbHidDevicePolicy.firstCompatible(
                listOf(keyboard, mouse, braille))?.deviceId,
        )
    }
}
