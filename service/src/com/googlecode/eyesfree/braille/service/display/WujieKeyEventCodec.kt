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
 * Decodes the four-byte ASCII key notifications sent by the Wujie firmware on
 * the `def1` characteristic.
 *
 * The firmware sends only one notification after a key `next` or `prev`.
 * There is no press/release flag and no initial key state is sent when
 * notifications are enabled. Payloads that do not match either value are
 * ignored so a future firmware notification cannot generate a spurious key
 * press.
 */
class WujieKeyEventCodec private constructor() {
    companion object {
        const val COMMAND_NONE = 0
        const val COMMAND_NEXT = 1
        const val COMMAND_PREVIOUS = 2

        /**
         * Returns one of the `COMMAND_*` constants, or [COMMAND_NONE] when
         * [value] is not a known key payload.
         */
        @JvmStatic
        fun decode(value: ByteArray?): Int {
            if (value == null) {
                return COMMAND_NONE
            }
            var length = value.size
            // Accept trailing NUL padding in case a firmware revision sends a
            // fixed-width string, but do not ignore bytes before the command.
            while (length > 0 && value[length - 1].toInt() == 0) {
                length--
            }
            if (length != 4) {
                return COMMAND_NONE
            }
            if (matches(value, "next")) {
                return COMMAND_NEXT
            }
            if (matches(value, "prev")) {
                return COMMAND_PREVIOUS
            }
            return COMMAND_NONE
        }

        private fun matches(value: ByteArray, expected: String): Boolean {
            for (i in expected.indices) {
                if (value[i] != expected[i].code.toByte()) {
                    return false
                }
            }
            return true
        }
    }
}
