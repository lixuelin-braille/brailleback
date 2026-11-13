/*
 * Copyright (C) 2026 BrailleBack contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.googlecode.eyesfree.braille.service.display

import com.googlecode.eyesfree.braille.display.UsbDeviceEntry

/**
 * Pure, JVM-testable helpers for the wired USB (brltty) connection path.
 *
 * Kept free of Android APIs so the parameter-string format and the VID/PID
 * driver lookup can be unit tested without a device. The formats here are
 * dictated by the prebuilt native library:
 *
 *  - `Programs/device.h`: `DEVICE_PARAMETER_SEPARATOR '+'`
 *  - `Programs/usb.c`: parameter names `serialNumber`, `vendorIdentifier`,
 *    `productIdentifier`; both identifiers must be decimal unsigned 16-bit
 *    integers.
 *  - `Programs/usb.c`: an empty serial number skips verification.
 */
class UsbBrailleDevice private constructor() {
    companion object {
        private const val PREFIX = "usb:"
        private const val SEPARATOR = "+"

        /**
         * Builds the brltty device parameter for a USB point display.
         *
         * The leading `+` is intentional: it matches Google TalkBack's
         * `BrlttyUsbParameterProvider` and makes brltty prepend its own
         * (empty) default parameter before parsing the named ones.
         *
         * @param serialNumber USB serial string, may be null or non-printable
         * @param vendorId decimal USB VID, e.g. 0x0403 = 1027
         * @param productId decimal USB PID, e.g. 0x6001 = 24577
         */
        @JvmStatic
        fun buildParameter(serialNumber: String?, vendorId: Int, productId: Int): String =
                PREFIX + SEPARATOR +
                        "serialNumber=" + sanitizeSerialNumber(serialNumber) + SEPARATOR +
                        "vendorIdentifier=" + vendorId + SEPARATOR +
                        "productIdentifier=" + productId

        /**
         * Removes characters that could break brltty's parameter parser or are
         * not printable. An empty result is safe: brltty then skips
         * serial-number verification entirely.
         */
        @JvmStatic
        fun sanitizeSerialNumber(raw: String?): String {
            if (raw == null) {
                return ""
            }
            val cleaned = StringBuilder(raw.length)
            for (i in raw.indices) {
                val c = raw[i]
                // Keep printable ASCII only; '+', '=', ',' and ':' are parameter
                // syntax and must never appear inside a value.
                if (c.code in 0x20 until 0x7f
                        && c != '+' && c != '=' && c != ',' && c != ':') {
                    cleaned.append(c)
                }
            }
            return cleaned.toString()
        }

        /**
         * Returns every driver code in the native USB device table whose
         * VID/PID pair matches the plugged device. The native table can list
         * several drivers for one chip (e.g. an FTDI adapter used by many
         * displays).
         */
        @JvmStatic
        fun candidateDrivers(
            table: List<UsbDeviceEntry?>?,
            vendorId: Int,
            productId: Int
        ): List<String> {
            if (table == null || table.isEmpty()) {
                return emptyList()
            }
            val result = ArrayList<String>()
            for (entry in table) {
                if (entry == null) {
                    continue
                }
                val driver: String? = entry.driver
                if (driver == null) {
                    continue
                }
                if (entry.vendorId == vendorId
                        && entry.productId == productId
                        && !result.contains(driver)) {
                    result.add(driver)
                }
            }
            return result
        }

        /**
         * Picks the first usable driver code for a VID/PID pair, or null when
         * the plugged device is not in the native table.
         */
        @JvmStatic
        fun selectDriver(
            table: List<UsbDeviceEntry?>?,
            vendorId: Int,
            productId: Int
        ): String? {
            val candidates = candidateDrivers(table, vendorId, productId)
            return candidates.firstOrNull()
        }
    }
}
