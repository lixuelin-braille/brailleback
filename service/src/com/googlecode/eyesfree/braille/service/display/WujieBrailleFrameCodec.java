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

package com.googlecode.eyesfree.braille.service.display;

/**
 * Encodes a braille window for the Orbit / Wujie 30-cell display.
 *
 * <p>The firmware accepts either 30 raw cell bytes or a 0x02 report-id byte
 * followed by 30 cell bytes. Each cell uses the low six bits for dots 1-6;
 * the firmware masks every other bit and treats 0xA0 as a blank cell.
 */
public final class WujieBrailleFrameCodec {
    /** Number of cells in one row of the supported display. */
    public static final int CELL_COUNT = 30;
    /** Optional report-id prefix understood by the firmware. */
    public static final byte REPORT_ID = 0x02;

    private static final byte BLANK_SENTINEL = (byte) 0xA0;

    private WujieBrailleFrameCodec() {
    }

    /**
     * Builds one display frame.
     *
     * @param patterns source dot patterns; may be shorter or longer than the
     *     display row. Missing cells are blank and extra cells are ignored.
     * @param cellCount number of cells on the display (30 for the wujie 30).
     * @param withReportId whether to prefix the frame with {@link #REPORT_ID}.
     */
    public static byte[] encode(byte[] patterns, int cellCount,
            boolean withReportId) {
        if (cellCount <= 0 || cellCount > 0xFF) {
            throw new IllegalArgumentException(
                    "Unsupported cell count: " + cellCount);
        }
        final int offset = withReportId ? 1 : 0;
        byte[] frame = new byte[offset + cellCount];
        if (withReportId) {
            frame[0] = REPORT_ID;
        }
        if (patterns != null) {
            int copied = Math.min(patterns.length, cellCount);
            for (int i = 0; i < copied; i++) {
                byte value = patterns[i];
                frame[offset + i] = value == BLANK_SENTINEL
                        ? 0 : (byte) (value & 0x3F);
            }
        }
        return frame;
    }

    /** Returns the exact frame length the firmware expects. */
    public static int expectedFrameLength(int cellCount, boolean withReportId) {
        return cellCount + (withReportId ? 1 : 0);
    }
}
