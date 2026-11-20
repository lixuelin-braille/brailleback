/*
 * Copyright (C) 2015 Google Inc.
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

import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.service.R
import timber.log.Timber

// Connection callbacks for DisplayService; split out of DisplayService.kt.

/**
 * Callbacks from the {@link HidDisplayConnection}.
 */
fun DisplayService.createHidListener(): HidDisplayConnection.Listener =
        object : HidDisplayConnection.Listener {
    override fun onHidConnected(source: HidDisplayConnection?,
            properties: BrailleDisplayProperties?) {
        // Do NOT clear mConnectionProgress here: it must be broadcast to
        // clients so the settings UI re-renders the connected state.
        // handleOnDisplayConnected -> clearConnectionProgress() does that.
        mHandler.onHidConnected(source, properties)
    }

    override fun onHidDisconnected(source: HidDisplayConnection?) {
        mHandler.onHidDisconnected(source)
    }

    override fun onHidConnectFailed(source: HidDisplayConnection?, error: Int) {
        mHandler.onHidConnectFailed(source, error)
    }
}

fun DisplayService.createWujieListener(): WujieGattConnection.Listener =
        object : WujieGattConnection.Listener {
    override fun onWujieConnected(source: WujieGattConnection?,
            properties: BrailleDisplayProperties?) {
        mHandler.onWujieConnected(source, properties)
    }

    override fun onWujieInputEvent(source: WujieGattConnection?,
            event: BrailleInputEvent?) {
        mHandler.onWujieInputEvent(source, event)
    }

    override fun onWujieDisconnected(source: WujieGattConnection?) {
        mHandler.onWujieDisconnected(source)
    }

    override fun onWujieConnectFailed(source: WujieGattConnection?,
            error: Int) {
        mHandler.onWujieConnectFailed(source, error)
    }
}

/** Callbacks from the wired USB connection. */
fun DisplayService.createUsbListener(): UsbDisplayConnection.Listener =
        object : UsbDisplayConnection.Listener {
    override fun onUsbConnected(source: UsbDisplayConnection?,
            properties: BrailleDisplayProperties?) {
        if (source != mUsbConnection) {
            Timber.w("Ignoring stale USB connected callback")
            return
        }
        mHandler.onDisplayConnected(properties)
    }

    override fun onUsbDisconnected(source: UsbDisplayConnection?) {
        if (source != mUsbConnection) {
            Timber.w("Ignoring stale USB disconnected callback")
            return
        }
        mHandler.onDisplayDisconnected()
    }

    override fun onUsbConnectFailed(source: UsbDisplayConnection?,
            reason: String?) {
        if (source != mUsbConnection) {
            Timber.w("Ignoring stale USB failure callback")
            return
        }
        mUsbConnection = null
        mConnectPending = false
        Timber.w("USB connection failed: %s", reason)
        if (isUsbProtocol() && !mUsbHidTried && canUseUsbHid()) {
            mUsbHidTried = true
            Timber.i("Serial USB path unavailable; trying USB HID")
            connectUsbHid()
            return
        }
        mHandler.setConnectionProgress(
                getString(R.string.connprog_usb_failed, reason))
        scheduleAutoReconnectIfNeeded()
    }
}
