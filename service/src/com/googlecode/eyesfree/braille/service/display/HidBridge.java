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

import android.accessibilityservice.BrailleDisplayController;
import android.os.Build;

/**
 * Bridges the {@code BrailleDisplayController} obtained by the accessibility
 * service (BrailleBackService) to the display service, which drives the native
 * BRLTTY "hid" driver.
 *
 * <p>{@code BrailleDisplayController} is only obtainable from an
 * {@code AccessibilityService}.  Both BrailleBackService and DisplayService run
 * in the same process, so we simply hand the controller over through this
 * static holder.
 */
public final class HidBridge {
  private static BrailleDisplayController sController;
  private static Runnable sControllerChangeListener;

  private HidBridge() {}

  /**
   * Called by the accessibility service when it is created/updated. Notifies
   * the registered listener so the display service can (re)connect the HID
   * display with the fresh controller (the system may issue a new controller
   * after a drop/reconnect).
   */
  public static void setController(BrailleDisplayController controller) {
    sController = controller;
    Runnable listener = sControllerChangeListener;
    if (listener != null && controller != null) {
      listener.run();
    }
  }

  /** Registers a listener invoked whenever a non-null controller is set. */
  public static void setControllerChangeListener(Runnable listener) {
    sControllerChangeListener = listener;
  }

  /** Returns the controller, or {@code null} if none is available. */
  public static BrailleDisplayController getController() {
    return sController;
  }

  /** Whether the Braille HID path is usable on this platform. */
  public static boolean isAvailable() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
        && sController != null;
  }
}
