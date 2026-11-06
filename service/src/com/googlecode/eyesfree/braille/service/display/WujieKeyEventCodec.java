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
 * Decodes the four-byte ASCII key notifications sent by the Wujie firmware on
 * the {@code def1} characteristic.
 *
 * <p>The firmware sends only one notification after a key {@code next} or
 * {@code prev}. There is no press/release flag and no initial key state is
 * sent when notifications are enabled. Payloads that do not match either
 * value are ignored so a future firmware notification cannot generate a
 * spurious key press.
 */
final class WujieKeyEventCodec {
    static final int COMMAND_NONE = 0;
    static final int COMMAND_NEXT = 1;
    static final int COMMAND_PREVIOUS = 2;

    private WujieKeyEventCodec() {
    }

    /**
     * Returns one of the {@code COMMAND_*} constants, or
     * {@link #COMMAND_NONE} when {@code value} is not a known key payload.
     */
    static int decode(byte[] value) {
        if (value == null) {
            return COMMAND_NONE;
        }
        int length = value.length;
        // Accept trailing NUL padding in case a firmware revision sends a
        // fixed-width string, but do not ignore bytes before the command.
        while (length > 0 && value[length - 1] == 0) {
            length--;
        }
        if (length != 4) {
            return COMMAND_NONE;
        }
        if (matches(value, "next")) {
            return COMMAND_NEXT;
        }
        if (matches(value, "prev")) {
            return COMMAND_PREVIOUS;
        }
        return COMMAND_NONE;
    }

    private static boolean matches(byte[] value, String expected) {
        for (int i = 0; i < expected.length(); i++) {
            if (value[i] != (byte) expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
