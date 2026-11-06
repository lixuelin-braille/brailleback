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

/** Pure eligibility rules for the short Bluetooth discovery window. */
final class BluetoothDiscoveryPolicy {
    private BluetoothDiscoveryPolicy() {}

    static boolean shouldStartDiscovery(
            boolean autoConnectEnabled,
            boolean autoReconnectSuppressed,
            boolean haveClients,
            boolean dataFilesReady,
            boolean connectionActive,
            boolean connectPending,
            boolean waitingForHidController,
            boolean screenInteractive,
            boolean adapterEnabled,
            boolean scanPermissionGranted) {
        return autoConnectEnabled
                && !autoReconnectSuppressed
                && haveClients
                && dataFilesReady
                && !connectionActive
                && !connectPending
                && !waitingForHidController
                && screenInteractive
                && adapterEnabled
                && scanPermissionGranted;
    }
}
