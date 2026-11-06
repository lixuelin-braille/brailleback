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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class HidInputReportNormalizerTest {

    /**
     * The 140-byte report descriptor shipped in the Orbit / wujie firmware
     * (app-1.0.5-30.bin at 0x21bd0). Report ID 1 concatenates three Input
     * items: 32 router key bits, 16 key bits, and 8 padding bits.
     */
    private static final byte[] FIRMWARE_DESCRIPTOR = {
        (byte) 0x05, (byte) 0x41, (byte) 0x09, (byte) 0x01, (byte) 0xA1, (byte) 0x01,
        (byte) 0x85, (byte) 0x01, (byte) 0x05, (byte) 0x41, (byte) 0x0A, (byte) 0xFA,
        (byte) 0x00, (byte) 0x1A, (byte) 0x00, (byte) 0x01, (byte) 0x2A, (byte) 0x1F,
        (byte) 0x01, (byte) 0x15, (byte) 0x00, (byte) 0x25, (byte) 0x01, (byte) 0x75,
        (byte) 0x01, (byte) 0x95, (byte) 0x20, (byte) 0x81, (byte) 0x02, (byte) 0x05,
        (byte) 0x41, (byte) 0x0A, (byte) 0x01, (byte) 0x02, (byte) 0x0A, (byte) 0x02,
        (byte) 0x02, (byte) 0x0A, (byte) 0x03, (byte) 0x02, (byte) 0x0A, (byte) 0x04,
        (byte) 0x02, (byte) 0x0A, (byte) 0x05, (byte) 0x02, (byte) 0x0A, (byte) 0x06,
        (byte) 0x02, (byte) 0x0A, (byte) 0x07, (byte) 0x02, (byte) 0x0A, (byte) 0x08,
        (byte) 0x02, (byte) 0x0A, (byte) 0x09, (byte) 0x02, (byte) 0x0A, (byte) 0x1A,
        (byte) 0x02, (byte) 0x0A, (byte) 0x1B, (byte) 0x02, (byte) 0x0A, (byte) 0x10,
        (byte) 0x02, (byte) 0x0A, (byte) 0x11, (byte) 0x02, (byte) 0x0A, (byte) 0x12,
        (byte) 0x02, (byte) 0x0A, (byte) 0x13, (byte) 0x02, (byte) 0x0A, (byte) 0x14,
        (byte) 0x02, (byte) 0x15, (byte) 0x00, (byte) 0x25, (byte) 0x01, (byte) 0x75,
        (byte) 0x01, (byte) 0x95, (byte) 0x10, (byte) 0x81, (byte) 0x02, (byte) 0x75,
        (byte) 0x01, (byte) 0x95, (byte) 0x08, (byte) 0x81, (byte) 0x03, (byte) 0x85,
        (byte) 0x02, (byte) 0x05, (byte) 0x41, (byte) 0x09, (byte) 0x01, (byte) 0x09,
        (byte) 0x02, (byte) 0xA1, (byte) 0x02, (byte) 0x09, (byte) 0x03, (byte) 0x15,
        (byte) 0x00, (byte) 0x26, (byte) 0xFF, (byte) 0x00, (byte) 0x75, (byte) 0x08,
        (byte) 0x95, (byte) 0x1E, (byte) 0x91, (byte) 0x02, (byte) 0xC0, (byte) 0x85,
        (byte) 0x03, (byte) 0x05, (byte) 0x41, (byte) 0x09, (byte) 0x05, (byte) 0x16,
        (byte) 0x00, (byte) 0x00, (byte) 0x27, (byte) 0xFF, (byte) 0xFF, (byte) 0x00,
        (byte) 0x00, (byte) 0x75, (byte) 0x10, (byte) 0x95, (byte) 0x01, (byte) 0xB1,
        (byte) 0x02, (byte) 0xC0,
    };

    /** Report ID 1, 56 one-bit fields = 7-byte braille payload. */
    private static final byte[] DESCRIPTOR_REPORT_1_7_BYTES = {
        (byte) 0x05, (byte) 0x41,
        (byte) 0x09, (byte) 0x01,
        (byte) 0xA1, (byte) 0x01,
        (byte) 0x85, (byte) 0x01,
        (byte) 0x05, (byte) 0x41,
        (byte) 0x09, (byte) 0x00,
        (byte) 0x15, (byte) 0x00,
        (byte) 0x25, (byte) 0x01,
        (byte) 0x75, (byte) 0x01,
        (byte) 0x95, (byte) 0x38,
        (byte) 0x81, (byte) 0x02,
        (byte) 0xC0,
    };

    @Test
    public void parsesSingleNumberedInputReport() {
        HidInputReportNormalizer.InputReport report =
                HidInputReportNormalizer.singleInputReport(DESCRIPTOR_REPORT_1_7_BYTES);
        assertNotNull(report);
        assertEquals(1, report.id);
        assertEquals(7, report.payloadBytes);
    }

    @Test
    public void returnsNullForMissingDescriptor() {
        assertNull(HidInputReportNormalizer.singleInputReport(null));
        assertNull(HidInputReportNormalizer.singleInputReport(new byte[0]));
    }

    @Test
    public void returnsNullForUnnumberedDescriptor() {
        byte[] descriptor = {
            (byte) 0x05, (byte) 0x41,
            (byte) 0xA1, (byte) 0x01,
            (byte) 0x75, (byte) 0x08,
            (byte) 0x95, (byte) 0x07,
            (byte) 0x81, (byte) 0x02,
            (byte) 0xC0,
        };
        assertNull(HidInputReportNormalizer.singleInputReport(descriptor));
    }

    @Test
    public void returnsNullForAmbiguousMultipleInputReports() {
        byte[] descriptor = {
            (byte) 0x05, (byte) 0x41,
            (byte) 0xA1, (byte) 0x01,
            (byte) 0x85, (byte) 0x01,
            (byte) 0x75, (byte) 0x08,
            (byte) 0x95, (byte) 0x07,
            (byte) 0x81, (byte) 0x02,
            (byte) 0x85, (byte) 0x02,
            (byte) 0x75, (byte) 0x08,
            (byte) 0x95, (byte) 0x01,
            (byte) 0x81, (byte) 0x02,
            (byte) 0xC0,
        };
        assertNull(HidInputReportNormalizer.singleInputReport(descriptor));
    }

    @Test
    public void prefixesMissingReportId() {
        HidInputReportNormalizer.InputReport report =
                HidInputReportNormalizer.singleInputReport(DESCRIPTOR_REPORT_1_7_BYTES);
        byte[] raw = {
            0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00,
        };
        assertArrayEquals(
                new byte[] {0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00},
                HidInputReportNormalizer.normalize(raw, report));
    }

    @Test
    public void rewritesZeroPseudoReportId() {
        HidInputReportNormalizer.InputReport report =
                HidInputReportNormalizer.singleInputReport(DESCRIPTOR_REPORT_1_7_BYTES);
        byte[] raw = {
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04, 0x00,
        };
        assertArrayEquals(
                new byte[] {0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x04, 0x00},
                HidInputReportNormalizer.normalize(raw, report));
    }

    @Test
    public void leavesWellFormedReportUntouched() {
        HidInputReportNormalizer.InputReport report =
                HidInputReportNormalizer.singleInputReport(DESCRIPTOR_REPORT_1_7_BYTES);
        byte[] raw = {
            0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00,
        };
        byte[] result = HidInputReportNormalizer.normalize(raw, report);
        assertSame(raw, result);
        assertArrayEquals(raw, result);
    }

    @Test
    public void leavesUnexpectedLengthUntouched() {
        HidInputReportNormalizer.InputReport report =
                HidInputReportNormalizer.singleInputReport(DESCRIPTOR_REPORT_1_7_BYTES);
        byte[] raw = {0x01, 0x02, 0x03};
        assertSame(raw, HidInputReportNormalizer.normalize(raw, report));
    }

    @Test
    public void noReportShapeMeansNoChange() {
        byte[] raw = {0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00};
        assertSame(raw, HidInputReportNormalizer.normalize(raw, null));
    }

    @Test
    public void formatsHex() {
        assertEquals("00 7F FF", HidInputReportNormalizer.toHex(
                new byte[] {0x00, 0x7F, (byte) 0xFF}));
    }

    @Test
    public void parsesFirmwareDescriptorAsSevenByteReportOne() {
        assertEquals(140, FIRMWARE_DESCRIPTOR.length);
        HidInputReportNormalizer.InputReport report =
                HidInputReportNormalizer.singleInputReport(FIRMWARE_DESCRIPTOR);
        assertNotNull(report);
        assertEquals(1, report.id);
        assertEquals(7, report.payloadBytes);
    }

    @Test
    public void restoresReportIdOnFirmwareDescriptor() {
        HidInputReportNormalizer.InputReport report =
                HidInputReportNormalizer.singleInputReport(FIRMWARE_DESCRIPTOR);
        byte[] raw = {
            0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00,
        };
        assertArrayEquals(
                new byte[] {0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00},
                HidInputReportNormalizer.normalize(raw, report));
    }
}
