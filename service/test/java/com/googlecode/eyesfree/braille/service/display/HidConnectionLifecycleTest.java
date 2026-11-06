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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HidConnectionLifecycleTest {
    @Test
    public void connectAndDisconnectWaitsForSystemCallback() {
        HidConnectionLifecycle lifecycle = new HidConnectionLifecycle();

        assertTrue(lifecycle.beginConnect());
        assertTrue(lifecycle.markConnected());
        assertTrue(lifecycle.beginDisconnect());
        assertEquals(HidConnectionLifecycle.State.DISCONNECTING,
                lifecycle.getState());

        assertTrue(lifecycle.markDisconnected());
        assertEquals(HidConnectionLifecycle.State.IDLE, lifecycle.getState());
        assertFalse(lifecycle.markDisconnected());
    }

    @Test
    public void disconnectDuringConnectCompletesOnlyOnce() {
        HidConnectionLifecycle lifecycle = new HidConnectionLifecycle();

        assertTrue(lifecycle.beginConnect());
        assertTrue(lifecycle.beginDisconnect());
        assertFalse(lifecycle.beginDisconnect());
        assertTrue(lifecycle.markDisconnected());
        assertFalse(lifecycle.finishDisconnectTimeout());
    }

    @Test
    public void timeoutCompletesTeardownAndLateCallbackIsIgnored() {
        HidConnectionLifecycle lifecycle = new HidConnectionLifecycle();

        assertTrue(lifecycle.beginConnect());
        assertTrue(lifecycle.markConnected());
        assertTrue(lifecycle.beginDisconnect());
        assertTrue(lifecycle.finishDisconnectTimeout());
        assertEquals(HidConnectionLifecycle.State.IDLE, lifecycle.getState());

        assertFalse(lifecycle.markDisconnected());
    }

    @Test
    public void connectFailureReturnsToIdleAndLateSuccessIsIgnored() {
        HidConnectionLifecycle lifecycle = new HidConnectionLifecycle();

        assertTrue(lifecycle.beginConnect());
        assertTrue(lifecycle.markConnectFailed());
        assertEquals(HidConnectionLifecycle.State.IDLE, lifecycle.getState());

        assertFalse(lifecycle.markConnected());
        assertFalse(lifecycle.markConnectFailed());
    }

    @Test
    public void connectedCallbackDuringLocalTeardownIsRejected() {
        HidConnectionLifecycle lifecycle = new HidConnectionLifecycle();

        assertTrue(lifecycle.beginConnect());
        assertTrue(lifecycle.beginDisconnect());
        assertFalse(lifecycle.markConnected());
        assertEquals(HidConnectionLifecycle.State.DISCONNECTING,
                lifecycle.getState());
    }
}
