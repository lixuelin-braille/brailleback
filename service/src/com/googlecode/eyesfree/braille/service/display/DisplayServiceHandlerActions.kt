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

import android.os.Message
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.IBrailleServiceCallback
import com.googlecode.eyesfree.braille.service.R
import timber.log.Timber

// Message actions for DisplayServiceHandler; split out of DisplayService.kt.

fun handleRegisterCallback(
        service: DisplayService, callback: IBrailleServiceCallback?) {
    service.mClients.register(callback)
    if (service.mDataFileState == DisplayService.FILES_EXTRACTED
            && service.mConnectionState == DisplayService.STATE_DISCONNECTED
            && !service.isConnectionActive()
            && !service.mConnectPending
            && !service.mWaitingForHidController) {
        // onBind() runs before the client has registered, so its
        // failed attempt cannot open a discovery window yet. Fill that
        // gap now that haveClients() is true.
        service.mDiscoveryAllowedForCycle = true
        service.startDiscoveryWindow("client registered")
    }
    if (service.mConnectionProgress != null) {
        service.sendConnectionProgress(callback)
    }
    if (service.mDataFileState == DisplayService.FILES_NOT_EXTRACTED
            || (service.mConnectionState == DisplayService.STATE_DISCONNECTED
                    && (service.mReadThread != null
                            || (service.mHidConnection != null
                                    && !service.mHidConnection!!
                                            .isDisconnecting())
                            || service.mWujieConnection != null
                            || service.mUsbConnection != null))) {
        // Extraction or connection in progress, there will be a
        // broadcast of the state when it either succeeds or fails.
        return
    }
    service.sendConnectionState(callback)
}

fun handleOnDisplayConnected(
        service: DisplayService, properties: BrailleDisplayProperties?) {
    handleConnectionEstablished(service, properties)
}

fun handleOnHidConnected(
        service: DisplayService, event: HidEvent) {
    if (event.connection != service.mHidConnection) {
        Timber.w("Ignoring stale HID connected callback")
        return
    }
    handleConnectionEstablished(service, event.properties)
}

fun handleConnectionEstablished(
        service: DisplayService, properties: BrailleDisplayProperties?) {
    service.clearHidControllerWait()
    service.mConnectionState = DisplayService.STATE_CONNECTED
    // Also broadcast the progress clear, so clients do not keep
    // showing a stale "connecting..." message and suppress the
    // connected state.
    service.clearConnectionProgress()
    service.mDisplayProperties = properties
    service.mConnectPending = false
    service.mHidTried = false
    service.mWujieTried = false
    service.mUsbSerialTried = false
    service.mUsbHidTried = false
    service.mHidConnectionIsUsb = false
    service.mHidRetryCount = 0
    service.mAutoReconnectAttempt = 0
    service.mAutoReconnectSuppressed = false
    service.mDiscoveryAllowedForCycle = false
    service.stopDiscoverySession("display connected")
    service.cancelAutoReconnect()
    service.mHandler.cancelHidRetry()
    service.broadcastConnectionState()
}

fun handleOnDisplayDisconnected(service: DisplayService) {
    service.mReadThread = null
    // Also released by the wired path (which has no ReadThread).
    service.mUsbConnection = null
    handleConnectionReleased(service)
}

fun handleOnHidDisconnected(
        service: DisplayService, event: HidEvent) {
    if (event.connection != service.mHidConnection) {
        Timber.w("Ignoring stale HID disconnected callback")
        return
    }
    service.mHidConnection = null
    handleConnectionReleased(service)
}

fun handleConnectionReleased(service: DisplayService) {
    service.resetConnectionAttemptFlags()
    service.mHandler.cancelHidRetry()
    service.clearConnectionProgress()
    if (service.mConnectionState != DisplayService.STATE_DISCONNECTED) {
        service.mConnectionState = DisplayService.STATE_DISCONNECTED
        service.broadcastConnectionState()
    }
    if (service.mConnectPending) {
        // Don't get stuck retrying if connecting fails.
        service.mConnectPending = false
        Timber.i("Previous connection released; reconnecting once")
        service.connectBraille()
        return
    }
    service.scheduleAutoReconnectIfNeeded()
}

fun handleOnHidConnectFailed(
        service: DisplayService, event: HidEvent) {
    if (event.connection != service.mHidConnection) {
        Timber.w("Ignoring stale HID failure callback %d", event.error)
        return
    }
    service.mHidConnection = null
    service.mConnectPending = false
    service.mLastHidError = event.error
    if (service.mHidConnectionIsUsb && service.isUsbProtocol()) {
        service.mHidConnectionIsUsb = false
        // A wired USB HID attempt with no device attached fails
        // synchronously inside connectUsb(). Calling connectBraille()
        // here would repost the same failure on the main looper, which
        // spins at thousands of iterations per second (observed
        // 47k retries in 17 s, ~106% CPU). Report the failure once and
        // let the USB attach broadcast or the backoff schedule a retry.
        Timber.w("Wired USB HID connect failed (%d); waiting for a "
                + "device or the next scheduled retry", event.error)
        service.mHandler.setConnectionProgress(
                service.getUsbHidFailureMessage(event.error))
        service.scheduleAutoReconnectIfNeeded()
        return
    }
    val protocol = service.getConnectionProtocol()
    val hidRequested = service.isBluetoothProtocol()
    if (event.error == -3 && service.mHidRetryCount == 0
            && hidRequested && service.mReadThread == null) {
        service.mHidRetryCount = 1
        service.mHidTried = false
        Timber.i("Retrying HID once after controller release conflict")
        service.mHandler.scheduleHidRetry()
        return
    }
    Timber.e("HID connect failed (%d), protocol=%s", event.error,
            protocol)
    // mHidTried remains true so auto mode falls through to RFCOMM and
    // forced HID reports the existing failure message.
    service.connectBraille()
    service.scheduleAutoReconnectIfNeeded()
}

fun handleOnWujieConnected(
        service: DisplayService, event: WujieEvent) {
    if (event.connection != service.mWujieConnection) {
        Timber.w("Ignoring stale Wujie connected callback")
        return
    }
    handleConnectionEstablished(service, event.properties)
}

fun handleOnWujieDisconnected(
        service: DisplayService, event: WujieEvent) {
    if (event.connection != service.mWujieConnection) {
        Timber.w("Ignoring stale Wujie disconnected callback")
        return
    }
    service.mWujieConnection = null
    handleConnectionReleased(service)
}

fun handleOnWujieConnectFailed(
        service: DisplayService, event: WujieEvent) {
    if (event.connection != service.mWujieConnection) {
        Timber.w("Ignoring stale Wujie failure callback %d",
                event.error)
        return
    }
    service.mWujieConnection = null
    service.mConnectPending = false
    Timber.e("Wujie GATT connect failed (%d)", event.error)
    val message: Int = when (event.error) {
        WujieGattConnection.ERROR_PERMISSION -> R.string.connprog_wujie_permission
        WujieGattConnection.ERROR_SERVICE -> R.string.connprog_wujie_service_missing
        WujieGattConnection.ERROR_MTU -> R.string.connprog_wujie_mtu
        WujieGattConnection.ERROR_NO_WRITE -> R.string.connprog_wujie_no_write
        else -> {
            service.mHandler.setConnectionProgress(
                    service.getString(
                            R.string.connprog_wujie_failed_code,
                            event.error))
            service.scheduleAutoReconnectIfNeeded()
            return
        }
    }
    service.mHandler.setConnectionProgress(
            service.getString(message))
    service.scheduleAutoReconnectIfNeeded()
}

fun handleOnWujieInputEvent(
        service: DisplayService, input: WujieInputEvent) {
    if (input.connection != service.mWujieConnection
            || input.event == null) {
        Timber.w("Ignoring stale Wujie input event")
        return
    }
    service.broadcastInputEvent(input.event)
}

fun handleRetryHid(service: DisplayService) {
    if (service.mHidConnection != null || service.mReadThread != null
            || service.mUsbConnection != null) {
        return
    }
    if (!service.isHidRequested()) {
        return
    }
    service.mHidTried = false
    if (!service.retryHidConnect()) {
        service.mHidTried = true
        service.connectBraille()
    }
}

fun handleHidControllerTimeout(service: DisplayService) {
    if (!service.mWaitingForHidController) {
        return
    }
    service.mWaitingForHidController = false
    if (!service.isBluetoothProtocol()) {
        return
    }
    if (HidBridge.isAvailable()) {
        // The controller arrived just before the timeout fired.
        service.mHidTried = false
        service.connectBraille()
        return
    }
    // HID is not available in this cycle, so use classic RFCOMM instead
    // of waiting forever.
    service.mHidTried = true
    service.connectBraille()
    service.scheduleAutoReconnectIfNeeded()
}

fun handleAutoReconnect(service: DisplayService) {
    if (!service.shouldAutoReconnect()) {
        Timber.i("Skipping automatic reconnect (no longer eligible)")
        return
    }
    Timber.i("Automatic reconnect attempt %d",
            service.mAutoReconnectAttempt)
    service.connectBraille()
}

fun handleSetConnectionProgress(
        service: DisplayService, description: String?) {
    if ((description == null && service.mConnectionProgress == null)
            || (description != null && description.equals(
                            service.mConnectionProgress))) {
        return
    }
    service.mConnectionProgress = description
    service.broadcastConnectionProgress()
}

fun handleOnInputEvent(
        service: DisplayService, event: BrailleInputEvent?) {
    service.broadcastInputEvent(event)
}
