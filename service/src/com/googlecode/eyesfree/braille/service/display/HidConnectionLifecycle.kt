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
 * Tracks one HID connection attempt, including the asynchronous system
 * disconnect that follows it.
 *
 * The system `BrailleDisplayController.disconnect()` call is asynchronous.
 * This state machine keeps a connection busy until either the real disconnect
 * callback or the service watchdog completes the teardown, preventing a new
 * connection from racing the old one.
 */
class HidConnectionLifecycle {
    enum class State {
        IDLE,
        CONNECTING,
        CONNECTED,
        DISCONNECTING
    }

    private var mState = State.IDLE

    @Synchronized
    fun getState(): State = mState

    @Synchronized
    fun isIdle(): Boolean = mState == State.IDLE

    @Synchronized
    fun isConnecting(): Boolean = mState == State.CONNECTING

    @Synchronized
    fun isConnected(): Boolean = mState == State.CONNECTED

    @Synchronized
    fun isDisconnecting(): Boolean = mState == State.DISCONNECTING

    @Synchronized
    fun isBusy(): Boolean = mState != State.IDLE

    @Synchronized
    fun beginConnect(): Boolean {
        if (mState != State.IDLE) {
            return false
        }
        mState = State.CONNECTING
        return true
    }

    @Synchronized
    fun markConnected(): Boolean {
        if (mState != State.CONNECTING) {
            return false
        }
        mState = State.CONNECTED
        return true
    }

    @Synchronized
    fun markConnectFailed(): Boolean {
        if (mState != State.CONNECTING) {
            return false
        }
        mState = State.IDLE
        return true
    }

    @Synchronized
    fun beginDisconnect(): Boolean {
        if (mState != State.CONNECTING && mState != State.CONNECTED) {
            return false
        }
        mState = State.DISCONNECTING
        return true
    }

    /** Completes a disconnect after either the system callback or the watchdog.
     *  Repeated and late callbacks return false. */
    @Synchronized
    fun markDisconnected(): Boolean {
        if (mState != State.CONNECTING
                && mState != State.CONNECTED
                && mState != State.DISCONNECTING) {
            return false
        }
        mState = State.IDLE
        return true
    }

    @Synchronized
    fun finishDisconnectTimeout(): Boolean {
        if (mState != State.DISCONNECTING) {
            return false
        }
        mState = State.IDLE
        return true
    }
}
