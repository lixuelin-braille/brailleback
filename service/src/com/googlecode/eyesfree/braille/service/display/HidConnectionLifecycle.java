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
 * Tracks one HID connection attempt, including the asynchronous system
 * disconnect that follows it.
 *
 * <p>The system {@code BrailleDisplayController.disconnect()} call is
 * asynchronous. This state machine keeps a connection busy until either the
 * real disconnect callback or the service watchdog completes the teardown,
 * preventing a new connection from racing the old one.
 */
final class HidConnectionLifecycle {
    enum State {
        IDLE,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    private State mState = State.IDLE;

    synchronized State getState() {
        return mState;
    }

    synchronized boolean isIdle() {
        return mState == State.IDLE;
    }

    synchronized boolean isConnecting() {
        return mState == State.CONNECTING;
    }

    synchronized boolean isConnected() {
        return mState == State.CONNECTED;
    }

    synchronized boolean isDisconnecting() {
        return mState == State.DISCONNECTING;
    }

    synchronized boolean isBusy() {
        return mState != State.IDLE;
    }

    synchronized boolean beginConnect() {
        if (mState != State.IDLE) {
            return false;
        }
        mState = State.CONNECTING;
        return true;
    }

    synchronized boolean markConnected() {
        if (mState != State.CONNECTING) {
            return false;
        }
        mState = State.CONNECTED;
        return true;
    }

    synchronized boolean markConnectFailed() {
        if (mState != State.CONNECTING) {
            return false;
        }
        mState = State.IDLE;
        return true;
    }

    synchronized boolean beginDisconnect() {
        if (mState != State.CONNECTING && mState != State.CONNECTED) {
            return false;
        }
        mState = State.DISCONNECTING;
        return true;
    }

    /**
     * Completes a disconnect after either the system callback or the watchdog.
     * Repeated and late callbacks return {@code false}.
     */
    synchronized boolean markDisconnected() {
        if (mState != State.CONNECTING
                && mState != State.CONNECTED
                && mState != State.DISCONNECTING) {
            return false;
        }
        mState = State.IDLE;
        return true;
    }

    synchronized boolean finishDisconnectTimeout() {
        if (mState != State.DISCONNECTING) {
            return false;
        }
        mState = State.IDLE;
        return true;
    }
}
