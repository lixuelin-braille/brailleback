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

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class BondedDeviceFilterTest {
    private static final String ORBIT = "68:09:47:68:54:FE";
    private static final String TSM = "00:00:0E:02:D0:0B";

    @Test
    public void acceptsLastConnectedDevice() {
        assertTrue(DeviceFinder.shouldAutoConnectBondedDevice(
                ORBIT, ORBIT, Collections.<String>emptyList()));
    }

    @Test
    public void acceptsSupportedDevice() {
        assertTrue(DeviceFinder.shouldAutoConnectBondedDevice(
                ORBIT, null, Arrays.asList(ORBIT)));
    }

    @Test
    public void rejectsUnrelatedBondedDevice() {
        assertFalse(DeviceFinder.shouldAutoConnectBondedDevice(
                TSM, ORBIT, Arrays.asList(ORBIT)));
    }

    @Test
    public void rejectsMissingAddress() {
        assertFalse(DeviceFinder.shouldAutoConnectBondedDevice(
                null, ORBIT, Arrays.asList(ORBIT)));
    }
}
