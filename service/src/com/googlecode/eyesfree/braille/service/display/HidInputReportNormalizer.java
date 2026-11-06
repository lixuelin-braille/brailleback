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

package com.googlecode.eyesfree.braille.service.display;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Restores the HID report ID that some BLE braille displays omit on the wire.
 *
 * <p>BRLTTY expects every numbered input report to start with its report ID
 * (see {@code Drivers/Braille/HID/braille.c}, where
 * {@code handlePressedKeysArray()} rejects a packet whose first byte is not
 * {@code inputReportId}). Some displays advertise a numbered descriptor (for
 * example report ID 1 with a 7-byte braille payload) but transmit the payload
 * alone. When the platform hands such a payload through unchanged, the
 * leading key byte is interpreted as report ID 0 and every key press is
 * dropped.
 *
 * <p>The expected payload size and report ID are derived from the report
 * descriptor that the platform supplied for the connected device, so this is
 * not a hard-coded guess: if the descriptor declares exactly one numbered
 * input report, a payload of that exact size is prefixed with that report ID.
 * Devices with several input reports, or with unnumbered reports, are left
 * untouched.
 */
final class HidInputReportNormalizer {
    /** Describes the single numbered input report declared by a descriptor. */
    static final class InputReport {
        final int id;
        final int payloadBytes;

        InputReport(int id, int payloadBytes) {
            this.id = id;
            this.payloadBytes = payloadBytes;
        }
    }

    private static final int TYPE_MAIN = 0;
    private static final int TYPE_GLOBAL = 1;
    private static final int TAG_INPUT = 0x8;
    private static final int TAG_REPORT_ID = 0x8;
    private static final int TAG_REPORT_SIZE = 0x7;
    private static final int TAG_REPORT_COUNT = 0x9;

    private HidInputReportNormalizer() {}

    /**
     * Returns the one numbered input report declared by {@code descriptor}, or
     * null when the descriptor is missing, unnumbered, or ambiguous.
     */
    static InputReport singleInputReport(byte[] descriptor) {
        if (descriptor == null || descriptor.length == 0) {
            return null;
        }
        Map<Integer, Integer> inputBytesById = new LinkedHashMap<>();
        int reportId = 0;
        int reportSize = 0;
        int reportCount = 0;
        int i = 0;
        while (i < descriptor.length) {
            int prefix = descriptor[i++] & 0xFF;
            if (prefix == 0xFE) {
                // Long item: prefix, data size, long item tag, data.
                if (i + 1 >= descriptor.length) {
                    break;
                }
                int dataSize = descriptor[i] & 0xFF;
                i += 2 + dataSize;
                continue;
            }
            int sizeCode = prefix & 0x3;
            int size = sizeCode == 3 ? 4 : sizeCode;
            int type = (prefix >> 2) & 0x3;
            int tag = (prefix >> 4) & 0xF;
            if (i + size > descriptor.length) {
                break;
            }
            long value = 0;
            for (int b = 0; b < size; b++) {
                value |= ((long) (descriptor[i + b] & 0xFF)) << (8 * b);
            }
            i += size;
            if (type == TYPE_GLOBAL) {
                if (tag == TAG_REPORT_ID) {
                    reportId = (int) value;
                } else if (tag == TAG_REPORT_SIZE) {
                    reportSize = (int) value;
                } else if (tag == TAG_REPORT_COUNT) {
                    reportCount = (int) value;
                }
            } else if (type == TYPE_MAIN && tag == TAG_INPUT) {
                int bits = reportSize * reportCount;
                if (bits > 0 && bits % 8 == 0) {
                    int bytes = bits / 8;
                    Integer previous = inputBytesById.get(reportId);
                    // Several Input items may share a report ID (for example
                    // key bits followed by constant padding). HID concatenates
                    // them into one wire report, so the payload length is the
                    // sum, not the widest individual item.
                    int total = (previous == null ? 0 : previous) + bytes;
                    inputBytesById.put(reportId, total);
                }
            }
        }
        if (inputBytesById.size() != 1) {
            return null;
        }
        Map.Entry<Integer, Integer> only = inputBytesById.entrySet().iterator().next();
        int id = only.getKey();
        if (id == 0) {
            return null;
        }
        return new InputReport(id, only.getValue());
    }

    /**
     * Returns a report that starts with its report ID, or {@code input} itself
     * when no repair is needed (or possible with {@code report}).
     */
    static byte[] normalize(byte[] input, InputReport report) {
        if (input == null || report == null) {
            return input;
        }
        if (input.length == report.payloadBytes) {
            byte[] withId = new byte[input.length + 1];
            withId[0] = (byte) report.id;
            System.arraycopy(input, 0, withId, 1, input.length);
            return withId;
        }
        if (input.length == report.payloadBytes + 1 && input[0] == 0) {
            byte[] fixed = input.clone();
            fixed[0] = (byte) report.id;
            return fixed;
        }
        return input;
    }

    /** Formats a report as uppercase hex, for diagnostics. */
    static String toHex(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            if (sb.length() > 0) {
                sb.append((char) 32);
            }
            int value = b & 0xFF;
            sb.append(Character.toUpperCase(Character.forDigit(value >> 4, 16)));
            sb.append(Character.toUpperCase(Character.forDigit(value & 0xF, 16)));
        }
        return sb.toString();
    }
}
