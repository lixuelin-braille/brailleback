/*
 * Copyright 2024 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.googlecode.eyesfree.braille.service.display

import java.util.LinkedHashMap

/**
 * Restores the HID report ID that some BLE braille displays omit on the wire.
 *
 * BRLTTY expects every numbered input report to start with its report ID
 * (see `Drivers/Braille/HID/braille.c`, where `handlePressedKeysArray()`
 * rejects a packet whose first byte is not `inputReportId`). Some displays
 * advertise a numbered descriptor (for example report ID 1 with a 7-byte
 * braille payload) but transmit the payload alone. When the platform hands
 * such a payload through unchanged, the leading key byte is interpreted as
 * report ID 0 and every key press is dropped.
 *
 * The expected payload size and report ID are derived from the report
 * descriptor that the platform supplied for the connected device, so this is
 * not a hard-coded guess: if the descriptor declares exactly one numbered
 * input report, a payload of that exact size is prefixed with that report ID.
 * Devices with several input reports, or with unnumbered reports, are left
 * untouched.
 */
class HidInputReportNormalizer private constructor() {
    /** Describes the single numbered input report declared by a descriptor.
     *  @JvmField keeps `report.id` / `report.payloadBytes` readable from the
     *  Java unit tests and from the JNI-facing BrlttyHidNativeHelper. */
    class InputReport(
        @JvmField val id: Int,
        @JvmField val payloadBytes: Int
    )

    companion object {
        private const val TYPE_MAIN = 0
        private const val TYPE_GLOBAL = 1
        private const val TAG_INPUT = 0x8
        private const val TAG_REPORT_ID = 0x8
        private const val TAG_REPORT_SIZE = 0x7
        private const val TAG_REPORT_COUNT = 0x9

        /**
         * Returns the one numbered input report declared by [descriptor], or
         * null when the descriptor is missing, unnumbered, or ambiguous.
         */
        @JvmStatic
        fun singleInputReport(descriptor: ByteArray?): InputReport? {
            if (descriptor == null || descriptor.isEmpty()) {
                return null
            }
            val inputBytesById = LinkedHashMap<Int, Int>()
            var reportId = 0
            var reportSize = 0
            var reportCount = 0
            var i = 0
            while (i < descriptor.size) {
                val prefix = descriptor[i++].toInt() and 0xFF
                if (prefix == 0xFE) {
                    // Long item: prefix, data size, long item tag, data.
                    if (i + 1 >= descriptor.size) {
                        break
                    }
                    val dataSize = descriptor[i].toInt() and 0xFF
                    i += 2 + dataSize
                    continue
                }
                val sizeCode = prefix and 0x3
                val size = if (sizeCode == 3) 4 else sizeCode
                val type = (prefix shr 2) and 0x3
                val tag = (prefix shr 4) and 0xF
                if (i + size > descriptor.size) {
                    break
                }
                var value = 0L
                for (b in 0 until size) {
                    value = value or ((descriptor[i + b].toLong() and 0xFFL) shl (8 * b))
                }
                i += size
                if (type == TYPE_GLOBAL) {
                    when (tag) {
                        TAG_REPORT_ID -> reportId = value.toInt()
                        TAG_REPORT_SIZE -> reportSize = value.toInt()
                        TAG_REPORT_COUNT -> reportCount = value.toInt()
                    }
                } else if (type == TYPE_MAIN && tag == TAG_INPUT) {
                    val bits = reportSize * reportCount
                    if (bits > 0 && bits % 8 == 0) {
                        val bytes = bits / 8
                        // Several Input items may share a report ID (for example
                        // key bits followed by constant padding). HID concatenates
                        // them into one wire report, so the payload length is the
                        // sum, not the widest individual item.
                        val total = (inputBytesById[reportId] ?: 0) + bytes
                        inputBytesById[reportId] = total
                    }
                }
            }
            if (inputBytesById.size != 1) {
                return null
            }
            val only = inputBytesById.entries.first()
            val id = only.key
            if (id == 0) {
                return null
            }
            return InputReport(id, only.value)
        }

        /**
         * Returns a report that starts with its report ID, or [input] itself
         * when no repair is needed (or possible with [report]).
         */
        @JvmStatic
        fun normalize(input: ByteArray?, report: InputReport?): ByteArray? {
            if (input == null || report == null) {
                return input
            }
            if (input.size == report.payloadBytes) {
                val withId = ByteArray(input.size + 1)
                withId[0] = report.id.toByte()
                input.copyInto(withId, 1)
                return withId
            }
            if (input.size == report.payloadBytes + 1 && input[0].toInt() == 0) {
                val fixed = input.clone()
                fixed[0] = report.id.toByte()
                return fixed
            }
            return input
        }

        /** Formats a report as uppercase hex, for diagnostics. */
        @JvmStatic
        fun toHex(bytes: ByteArray?): String {
            if (bytes == null) {
                return "null"
            }
            val sb = StringBuilder(bytes.size * 3)
            for (b in bytes) {
                if (sb.isNotEmpty()) {
                    sb.append(' ')
                }
                val value = b.toInt() and 0xFF
                sb.append(Character.forDigit(value shr 4, 16).uppercaseChar())
                sb.append(Character.forDigit(value and 0xF, 16).uppercaseChar())
            }
            return sb.toString()
        }
    }
}
