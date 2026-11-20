/*
 * Copyright (C) 2026 BrailleBack contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.googlecode.eyesfree.braille.service.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.googlecode.eyesfree.braille.display.UsbDeviceEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** Unit tests for the pure USB parameter / driver-selection logic. */
public class UsbBrailleDeviceTest {

    private static UsbDeviceEntry entry(String driver, int vid, int pid) {
        return new UsbDeviceEntry(driver, vid, pid);
    }

    @Test
    public void buildParameter_usesTalkBackFormatAndDecimalIds() {
        // 0x0403 / 0x6001 = FTDI FT232, the first entry of the real native
        // table observed on device during the P0 probe.
        assertEquals(
                "usb:+serialNumber=ABC123+vendorIdentifier=1027+productIdentifier=24577",
                UsbBrailleDevice.buildParameter("ABC123", 1027, 24577));
    }

    @Test
    public void buildParameter_emptySerialIsStillAValidParameter() {
        assertEquals(
                "usb:+serialNumber=+vendorIdentifier=1027+productIdentifier=24577",
                UsbBrailleDevice.buildParameter(null, 1027, 24577));
    }

    @Test
    public void sanitizeSerialNumber_stripsParameterSyntaxAndNonPrintable() {
        assertEquals("ABC-123", UsbBrailleDevice.sanitizeSerialNumber("ABC-123"));
        assertEquals("AB123", UsbBrailleDevice.sanitizeSerialNumber("A+B=1,2:3"));
        assertEquals("AB", UsbBrailleDevice.sanitizeSerialNumber("A\n\u0000B"));
        assertEquals("AB", UsbBrailleDevice.sanitizeSerialNumber("A\u4e2dB"));
        assertEquals("", UsbBrailleDevice.sanitizeSerialNumber(null));
    }

    @Test
    public void buildParameter_serialIsSanitized() {
        assertEquals(
                "usb:+serialNumber=AB12+vendorIdentifier=1+productIdentifier=2",
                UsbBrailleDevice.buildParameter("A+B=1,2", 1, 2));
    }

    @Test
    public void candidateDrivers_returnsEveryDistinctMatchInOrder() {
        List<UsbDeviceEntry> table = Arrays.asList(
                entry("at", 1027, 24577),
                entry("bm", 1027, 24577),
                entry("at", 1027, 24577), // duplicate driver code
                entry("vo", 4, 5),
                entry("fs", 1027, 9));
        assertEquals(Arrays.asList("at", "bm"),
                UsbBrailleDevice.candidateDrivers(table, 1027, 24577));
    }

    @Test
    public void candidateDrivers_requiresBothIdsToMatch() {
        List<UsbDeviceEntry> table = Arrays.asList(
                entry("vo", 4, 5),
                entry("fs", 1027, 9));
        assertTrue(UsbBrailleDevice.candidateDrivers(table, 1027, 24577).isEmpty());
        assertTrue(UsbBrailleDevice.candidateDrivers(table, 4, 9).isEmpty());
    }

    @Test
    public void candidateDrivers_handlesNullAndEmptyTable() {
        assertTrue(UsbBrailleDevice.candidateDrivers(null, 1027, 24577).isEmpty());
        assertTrue(UsbBrailleDevice.candidateDrivers(
                Collections.<UsbDeviceEntry>emptyList(), 1027, 24577).isEmpty());
    }

    @Test
    public void candidateDrivers_skipsNullEntries() {
        // A null driver cannot be constructed here: UsbDeviceEntry is a Kotlin
        // data class with a non-null String constructor parameter. The
        // defensive null check in the production code still guards against a
        // malformed JNI-created instance.
        List<UsbDeviceEntry> table = new ArrayList<UsbDeviceEntry>();
        table.add(null);
        table.add(entry("vo", 4, 5));
        table.add(entry("at", 1027, 24577));
        assertEquals(Collections.singletonList("at"),
                UsbBrailleDevice.candidateDrivers(table, 1027, 24577));
    }

    @Test
    public void selectDriver_returnsFirstMatchOrNull() {
        List<UsbDeviceEntry> table = Arrays.asList(
                entry("vo", 4, 5),
                entry("at", 1027, 24577),
                entry("bm", 1027, 24577));
        assertEquals("at", UsbBrailleDevice.selectDriver(table, 1027, 24577));
        assertNull(UsbBrailleDevice.selectDriver(table, 9999, 9999));
        assertNull(UsbBrailleDevice.selectDriver(null, 1027, 24577));
    }
}
