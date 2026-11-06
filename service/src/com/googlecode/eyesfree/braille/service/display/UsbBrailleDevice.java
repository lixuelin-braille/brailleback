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

import com.googlecode.eyesfree.braille.display.UsbDeviceEntry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure, JVM-testable helpers for the wired USB (brltty) connection path.
 *
 * <p>Kept free of Android APIs so the parameter-string format and the
 * VID/PID driver lookup can be unit tested without a device. The formats here
 * are dictated by the prebuilt native library:
 *
 * <ul>
 *   <li>{@code Programs/device.h}: {@code DEVICE_PARAMETER_SEPARATOR '+'}
 *   <li>{@code Programs/usb.c}: parameter names {@code serialNumber},
 *       {@code vendorIdentifier}, {@code productIdentifier}; both identifiers
 *       must be decimal unsigned 16-bit integers.
 *   <li>{@code Programs/usb.c}: an empty serial number skips verification.
 * </ul>
 */
public final class UsbBrailleDevice {

    private static final String PREFIX = "usb:";
    private static final String SEPARATOR = "+";

    private UsbBrailleDevice() {
    }

    /**
     * Builds the brltty device parameter for a USB point display.
     *
     * <p>The leading {@code +} is intentional: it matches Google TalkBack's
     * {@code BrlttyUsbParameterProvider} and makes brltty prepend its own
     * (empty) default parameter before parsing the named ones.
     *
     * @param serialNumber USB serial string, may be null or non-printable
     * @param vendorId     decimal USB VID, e.g. 0x0403 = 1027
     * @param productId    decimal USB PID, e.g. 0x6001 = 24577
     */
    public static String buildParameter(String serialNumber, int vendorId,
            int productId) {
        return PREFIX + SEPARATOR
                + "serialNumber=" + sanitizeSerialNumber(serialNumber) + SEPARATOR
                + "vendorIdentifier=" + vendorId + SEPARATOR
                + "productIdentifier=" + productId;
    }

    /**
     * Removes characters that could break brltty's parameter parser or are
     * not printable. an empty result is safe: brltty then skips serial-number
     * verification entirely.
     */
    public static String sanitizeSerialNumber(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); ++i) {
            char c = raw.charAt(i);
            // Keep printable ASCII only; '+', '=', ',' and ':' are parameter
            // syntax and must never appear inside a value.
            if (c >= 0x20 && c < 0x7f
                    && c != '+' && c != '=' && c != ',' && c != ':') {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }

    /**
     * Returns every driver code in the native USB device table whose VID/PID
     * pair matches the plugged device. The native table can list several
     * drivers for one chip (e.g. an FTDI adapter used by many displays).
     */
    public static List<String> candidateDrivers(List<UsbDeviceEntry> table,
            int vendorId, int productId) {
        if (table == null || table.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<String>();
        for (UsbDeviceEntry entry : table) {
            if (entry == null || entry.getDriver() == null) {
                continue;
            }
            if (entry.getVendorId() == vendorId
                    && entry.getProductId() == productId
                    && !result.contains(entry.getDriver())) {
                result.add(entry.getDriver());
            }
        }
        return result;
    }

    /**
     * Picks the first usable driver code for a VID/PID pair, or {@code null}
     * when the plugged device is not in the native table.
     */
    public static String selectDriver(List<UsbDeviceEntry> table,
            int vendorId, int productId) {
        List<String> candidates = candidateDrivers(table, vendorId, productId);
        return candidates.isEmpty() ? null : candidates.get(0);
    }
}
