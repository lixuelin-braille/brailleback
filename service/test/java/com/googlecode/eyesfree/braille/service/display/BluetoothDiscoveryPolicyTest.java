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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BluetoothDiscoveryPolicyTest {
    @Test
    public void startsWhenEveryRequirementIsMet() {
        assertTrue(BluetoothDiscoveryPolicy.shouldStartDiscovery(
                true, false, true, true, false, false, false, true, true,
                true));
    }

    @Test
    public void doesNotScanWhileConnectedOrConnecting() {
        assertFalse(BluetoothDiscoveryPolicy.shouldStartDiscovery(
                true, false, true, true, true, false, false, true, true,
                true));
        assertFalse(BluetoothDiscoveryPolicy.shouldStartDiscovery(
                true, false, true, true, false, true, false, true, true,
                true));
    }

    @Test
    public void doesNotScanWhenUserOrPowerStateDisallowsIt() {
        assertFalse(BluetoothDiscoveryPolicy.shouldStartDiscovery(
                false, false, true, true, false, false, false, true, true,
                true));
        assertFalse(BluetoothDiscoveryPolicy.shouldStartDiscovery(
                true, true, true, true, false, false, false, true, true,
                true));
        assertFalse(BluetoothDiscoveryPolicy.shouldStartDiscovery(
                true, false, true, true, false, false, false, false, true,
                true));
    }

    @Test
    public void doesNotScanWithoutPermissionOrBluetooth() {
        assertFalse(BluetoothDiscoveryPolicy.shouldStartDiscovery(
                true, false, true, true, false, false, false, true, false,
                true));
        assertFalse(BluetoothDiscoveryPolicy.shouldStartDiscovery(
                true, false, true, true, false, false, false, true, true,
                false));
    }
}
