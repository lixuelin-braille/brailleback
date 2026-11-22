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

package com.googlecode.eyesfree.braille.display

/**
 * One candidate device in the service-reported device list.
 *
 * @property address Bluetooth MAC address, uppercase for stable comparison.
 * @property name remote display name; may be null when the scan result has not
 *   carried the name yet (permission or name inquiry still pending).
 * @property state one of [STATE_BONDED] / [STATE_UNPAIRED]; pairing states are
 *   added by the pairing slice.
 */
data class DeviceListEntry(
    val address: String,
    val name: String?,
    val state: Int
) {
    companion object {
        /** Already paired; the existing connection path applies. */
        const val STATE_BONDED = 0

        /** Discovered but not paired; needs an explicit pairing action. */
        const val STATE_UNPAIRED = 1
    }
}
