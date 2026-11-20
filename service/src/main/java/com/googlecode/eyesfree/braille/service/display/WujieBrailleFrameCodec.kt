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
 * Encodes a braille window for the Orbit / Wujie 30-cell display.
 *
 * The firmware accepts either 30 raw cell bytes or a 0x02 report-id byte
 * followed by 30 cell bytes. Each cell uses the low six bits for dots 1-6;
 * the firmware masks every other bit and treats 0xA0 as a blank cell.
 */
class WujieBrailleFrameCodec private constructor() {
    companion object {
        /** Number of cells in one row of the supported display. */
        const val CELL_COUNT = 30
        /** Optional report-id prefix understood by the firmware. */
        const val REPORT_ID: Byte = 0x02

        private val BLANK_SENTINEL: Byte = 0xA0.toByte()

        /**
         * Builds one display frame.
         *
         * @param patterns source dot patterns; may be shorter or longer than the
         *     display row. Missing cells are blank and extra cells are ignored.
         * @param cellCount number of cells on the display (30 for the wujie 30).
         * @param withReportId whether to prefix the frame with [REPORT_ID].
         */
        @JvmStatic
        fun encode(patterns: ByteArray?, cellCount: Int, withReportId: Boolean): ByteArray {
            if (cellCount <= 0 || cellCount > 0xFF) {
                throw IllegalArgumentException("Unsupported cell count: $cellCount")
            }
            val offset = if (withReportId) 1 else 0
            val frame = ByteArray(offset + cellCount)
            if (withReportId) {
                frame[0] = REPORT_ID
            }
            if (patterns != null) {
                val copied = minOf(patterns.size, cellCount)
                for (i in 0 until copied) {
                    val value = patterns[i]
                    frame[offset + i] = if (value == BLANK_SENTINEL) {
                        0.toByte()
                    } else {
                        (value.toInt() and 0x3F).toByte()
                    }
                }
            }
            return frame
        }

        /** Returns the exact frame length the firmware expects. */
        @JvmStatic
        fun expectedFrameLength(cellCount: Int, withReportId: Boolean): Int =
                cellCount + if (withReportId) 1 else 0
    }
}
