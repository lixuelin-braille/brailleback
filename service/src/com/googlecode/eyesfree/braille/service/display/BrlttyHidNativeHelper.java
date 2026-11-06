/*
 * Copyright 2024 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlecode.eyesfree.braille.service.display;

import android.annotation.SuppressLint;
import android.accessibilityservice.BrailleDisplayController;
import java.io.IOException;
import java.util.ArrayDeque;
import timber.log.Timber;

/**
 * A Helper class helps hid_android.c in brltty access BrailleDisplayController APIs.
 *
 * <p>This is a port of Google TalkBack's BrlttyHidNativeHelper, adjusted to the
 * BrailleBack package structure. The native hid_android.c bridge calls the
 * {@link #getReportDescriptor()}, {@link #writeBrailleDisplay(byte[])} and
 * {@link #readBrailleDisplay()} methods over JNI.
 */
@SuppressLint("NewApi") // Only called from the API 35+ HID connection path.
public final class BrlttyHidNativeHelper {
  private static final String TAG = "HidAndroidHelper";
  private static final Object lock = new Object();
  private static final ArrayDeque<byte[]> inputReports = new ArrayDeque<byte[]>();

  private static byte[] reportDescriptor;

  /** Input report shape derived from {@link #reportDescriptor}. */
  private static HidInputReportNormalizer.InputReport inputReport;

  private static BrailleDisplayController brailleDisplayController;

  private BrlttyHidNativeHelper() {}

  /** Prepares a report descriptor for BRLTTY to interpret the format of incoming reports. */
  public static void setup(BrailleDisplayController controller, byte[] descriptor) {
    synchronized (lock) {
      brailleDisplayController = controller;
      reportDescriptor = descriptor == null ? null : descriptor.clone();
      inputReport = HidInputReportNormalizer.singleInputReport(reportDescriptor);
    }
    Timber.tag(TAG).i("HID input report: %s",
        inputReport == null ? "unnumbered/ambiguous"
            : "id=" + inputReport.id + " payload=" + inputReport.payloadBytes + "B");
  }

  /**
   * Saves the Braille display's input to the input cache. The cache will be read very soon after
   * when hid_android.c is notified that some input is available and it calls readBrailleDisplay.
   */
  public static void onInput(byte[] input) {
    if (input == null) {
      return;
    }
    byte[] normalized = HidInputReportNormalizer.normalize(input, inputReport);
    synchronized (lock) {
      if (normalized != input) {
        // Log at warn level: the display is sending reports that violate
        // its own descriptor, and BRLTTY would otherwise drop every key.
        Timber.tag("HidInput").w("restored report id: %dB %s -> %dB %s",
            input.length, HidInputReportNormalizer.toHex(input),
            normalized.length, HidInputReportNormalizer.toHex(normalized));
      } else {
        Timber.tag("HidInput").v("normalized %dB: %s",
            input.length, HidInputReportNormalizer.toHex(input));
      }
      // The caller may reuse its buffer after this callback returns.
      inputReports.add(normalized.clone());
    }
  }

  /** Resets the cached state, e.g. when the HID connection is torn down. */
  public static void reset() {
    synchronized (lock) {
      brailleDisplayController = null;
      reportDescriptor = null;
      inputReport = null;
      inputReports.clear();
    }
  }

  /**
   * Returns report descriptor to BRLTTY. It could be null if braille display is not connected or
   * the report descriptor is not setup in {@link #setup}.
   */
  public static byte[] getReportDescriptor() {
    synchronized (lock) {
      return reportDescriptor;
    }
  }

  /** Outputs text to the braille display. */
  public static void writeBrailleDisplay(byte[] buffer) {
    BrailleDisplayController controller;
    synchronized (lock) {
      controller = brailleDisplayController;
    }
    if (controller == null) {
      Timber.tag(TAG).e("write while BrailleDisplayController is null (connection torn down?)");
      return;
    }
    Timber.tag(TAG).d("write %d bytes", buffer.length);
    try {
      controller.write(buffer);
    } catch (IOException e) {
      Timber.tag(TAG).e(e, "Error writing to BrailleDisplayController");
      try {
        controller.disconnect();
      } catch (RuntimeException ignored) {
        // The connection is already gone; nothing else to release.
      }
    } catch (RuntimeException e) {
      // e.g. IllegalArgumentException for an over-sized buffer (bad report
      // layout): report it but keep the connection alive.
      Timber.tag(TAG).e(e, "BrailleDisplayController.write rejected the report");
    }
  }

  /** Reads braille display's input from cache saved in {@link #onInput}. */
  public static byte[] readBrailleDisplay() {
    synchronized (lock) {
      if (inputReports.isEmpty()) {
        return null;
      }
      return inputReports.pop();
    }
  }
}
