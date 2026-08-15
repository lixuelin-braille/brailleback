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

import android.accessibilityservice.BrailleDisplayController;
import android.util.Log;
import java.io.IOException;
import java.util.ArrayDeque;

/**
 * A Helper class helps hid_android.c in brltty access BrailleDisplayController APIs.
 *
 * <p>This is a port of Google TalkBack's BrlttyHidNativeHelper, adjusted to the
 * BrailleBack package structure. The native hid_android.c bridge calls the
 * {@link #getReportDescriptor()}, {@link #writeBrailleDisplay(byte[])} and
 * {@link #readBrailleDisplay()} methods over JNI.
 */
public final class BrlttyHidNativeHelper {
  private static final String TAG = "HidAndroidHelper";
  private static final Object lock = new Object();
  private static final ArrayDeque<byte[]> inputReports = new ArrayDeque<byte[]>();

  private static byte[] reportDescriptor;

  private static BrailleDisplayController brailleDisplayController;

  private BrlttyHidNativeHelper() {}

  /** Prepares a report descriptor for BRLTTY to interpret the format of incoming reports. */
  public static void setup(BrailleDisplayController controller, byte[] descriptor) {
    brailleDisplayController = controller;
    reportDescriptor = descriptor;
  }

  /**
   * Saves the Braille display's input to the input cache. The cache will be read very soon after
   * when hid_android.c is notified that some input is available and it calls readBrailleDisplay.
   */
  public static void onInput(byte[] input) {
    synchronized (lock) {
      StringBuilder sb = new StringBuilder();
      for (byte b : input) {
        sb.append(String.format("%02X ", b & 0xFF));
      }
      android.util.Log.d("HidInput", "input(" + input.length + "): " + sb.toString().trim());
      inputReports.add(input);
    }
  }

  /** Resets the cached state, e.g. when the HID connection is torn down. */
  public static void reset() {
    synchronized (lock) {
      brailleDisplayController = null;
      reportDescriptor = null;
      inputReports.clear();
    }
  }

  /**
   * Returns report descriptor to BRLTTY. It could be null if braille display is not connected or
   * the report descriptor is not setup in {@link #setup}.
   */
  public static byte[] getReportDescriptor() {
    return reportDescriptor;
  }

  /** Outputs text to the braille display. */
  public static void writeBrailleDisplay(byte[] buffer) {
    if (brailleDisplayController == null) {
      return;
    }
    Log.d(TAG, "write " + buffer.length + " bytes");
    try {
      brailleDisplayController.write(buffer);
    } catch (IOException e) {
      Log.e(TAG, "Error writing to BrailleDisplayController: " + e);
      brailleDisplayController.disconnect();
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
