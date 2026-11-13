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

import android.os.Handler
import android.os.Looper
import android.os.Message
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.IBrailleServiceCallback
import java.lang.ref.WeakReference
import timber.log.Timber

// Main-thread message pump for DisplayService; split out of DisplayService.kt.

class HidEvent(
        val connection: HidDisplayConnection?,
        val properties: BrailleDisplayProperties?,
        val error: Int)

class WujieEvent(
        val connection: WujieGattConnection?,
        val properties: BrailleDisplayProperties?,
        val error: Int)

class WujieInputEvent(
        val connection: WujieGattConnection?,
        val event: BrailleInputEvent?)

class MainHandler(service: DisplayService) :
        Handler(Looper.getMainLooper()) {
    private val mService: WeakReference<DisplayService> =
            WeakReference(service)

    companion object {
        private const val MSG_REGISTER_CALLBACK = 1
        private const val MSG_ON_DISPLAY_CONNECTED = 2
        private const val MSG_ON_DISPLAY_DISCONNECTED = 3
        private const val MSG_SET_CONNECTION_PROGRESS = 4
        private const val MSG_ON_INPUT_EVENT = 5
        private const val MSG_CONNECT_BRAILLE = 6
        private const val MSG_DISCONNECT_BRAILLE = 7
        private const val MSG_ON_HID_CONNECTED = 8
        private const val MSG_ON_HID_DISCONNECTED = 9
        private const val MSG_ON_HID_CONNECT_FAILED = 10
        private const val MSG_RETRY_HID = 11
        private const val MSG_ON_WUJIE_CONNECTED = 12
        private const val MSG_ON_WUJIE_DISCONNECTED = 13
        private const val MSG_ON_WUJIE_CONNECT_FAILED = 14
        private const val MSG_ON_WUJIE_INPUT_EVENT = 15
        private const val MSG_HID_CONTROLLER_TIMEOUT = 16
        private const val MSG_AUTO_RECONNECT = 17
        private const val MSG_DISCOVERY_TIMEOUT = 18
        private const val MSG_DISCOVERY_RESTART = 19
    }

    fun registerCallback(callback: IBrailleServiceCallback?) {
        obtainMessage(MSG_REGISTER_CALLBACK, callback).sendToTarget()
    }

    fun onDisplayConnected(properties: BrailleDisplayProperties?) {
        obtainMessage(MSG_ON_DISPLAY_CONNECTED, properties).sendToTarget()
    }

    fun onDisplayDisconnected() {
        sendEmptyMessage(MSG_ON_DISPLAY_DISCONNECTED)
    }

    fun onHidConnected(source: HidDisplayConnection?,
            properties: BrailleDisplayProperties?) {
        obtainMessage(MSG_ON_HID_CONNECTED,
                HidEvent(source, properties, 0)).sendToTarget()
    }

    fun onHidDisconnected(source: HidDisplayConnection?) {
        obtainMessage(MSG_ON_HID_DISCONNECTED,
                HidEvent(source, null, 0)).sendToTarget()
    }

    fun onHidConnectFailed(source: HidDisplayConnection?, error: Int) {
        obtainMessage(MSG_ON_HID_CONNECT_FAILED,
                HidEvent(source, null, error)).sendToTarget()
    }

    fun onWujieConnected(source: WujieGattConnection?,
            properties: BrailleDisplayProperties?) {
        obtainMessage(MSG_ON_WUJIE_CONNECTED,
                WujieEvent(source, properties, 0)).sendToTarget()
    }

    fun onWujieDisconnected(source: WujieGattConnection?) {
        obtainMessage(MSG_ON_WUJIE_DISCONNECTED,
                WujieEvent(source, null, 0)).sendToTarget()
    }

    fun onWujieInputEvent(source: WujieGattConnection?,
            event: BrailleInputEvent?) {
        obtainMessage(MSG_ON_WUJIE_INPUT_EVENT,
                WujieInputEvent(source, event)).sendToTarget()
    }

    fun onWujieConnectFailed(source: WujieGattConnection?,
            error: Int) {
        obtainMessage(MSG_ON_WUJIE_CONNECT_FAILED,
                WujieEvent(source, null, error)).sendToTarget()
    }

    fun setConnectionProgress(description: String?) {
        obtainMessage(MSG_SET_CONNECTION_PROGRESS, description)
                .sendToTarget()
    }

    fun onInputEvent(event: BrailleInputEvent?) {
        obtainMessage(MSG_ON_INPUT_EVENT, event).sendToTarget()
    }

    fun unscheduleDisconnect() {
        removeMessages(MSG_DISCONNECT_BRAILLE)
    }

    fun scheduleDisconnect(delayMillis: Long) {
        sendMessageDelayed(obtainMessage(MSG_DISCONNECT_BRAILLE, 0, 0),
                delayMillis)
    }

    fun connectBraille() {
        obtainMessage(MSG_CONNECT_BRAILLE, 0, 0).sendToTarget()
    }

    fun connectBrailleExplicit() {
        obtainMessage(MSG_CONNECT_BRAILLE, 1, 0).sendToTarget()
    }

    fun disconnectBraille() {
        obtainMessage(MSG_DISCONNECT_BRAILLE, 1, 0).sendToTarget()
    }

    fun scheduleAutoReconnect(attempt: Int) {
        scheduleAutoReconnect(attempt, -1)
    }

    fun scheduleAutoReconnect(attempt: Int, overrideDelay: Long) {
        var delay = DisplayService.AUTO_RECONNECT_INITIAL_DELAY shl Math.min(attempt, 4)
        delay = Math.min(delay, DisplayService.AUTO_RECONNECT_MAX_DELAY)
        if (overrideDelay >= 0) {
            delay = overrideDelay
        }
        removeMessages(MSG_AUTO_RECONNECT)
        sendEmptyMessageDelayed(MSG_AUTO_RECONNECT, delay)
        Timber.i("Scheduling automatic reconnect attempt %d in %d ms",
                attempt + 1, delay)
    }

    fun cancelAutoReconnect() {
        removeMessages(MSG_AUTO_RECONNECT)
    }

    fun hasAutoReconnectScheduled(): Boolean {
        return hasMessages(MSG_AUTO_RECONNECT)
    }

    fun scheduleDiscoveryTimeout(delayMillis: Long) {
        removeMessages(MSG_DISCOVERY_TIMEOUT)
        sendEmptyMessageDelayed(MSG_DISCOVERY_TIMEOUT, delayMillis)
    }

    fun cancelDiscoveryTimeout() {
        removeMessages(MSG_DISCOVERY_TIMEOUT)
    }

    fun scheduleDiscoveryRestart(delayMillis: Long) {
        removeMessages(MSG_DISCOVERY_RESTART)
        sendEmptyMessageDelayed(MSG_DISCOVERY_RESTART, delayMillis)
    }

    fun cancelDiscoveryRestart() {
        removeMessages(MSG_DISCOVERY_RESTART)
    }

    fun scheduleHidRetry() {
        sendEmptyMessageDelayed(MSG_RETRY_HID, DisplayService.HID_RETRY_DELAY)
    }

    fun cancelHidRetry() {
        removeMessages(MSG_RETRY_HID)
    }

    fun scheduleHidControllerTimeout(delayMillis: Long) {
        removeMessages(MSG_HID_CONTROLLER_TIMEOUT)
        sendEmptyMessageDelayed(MSG_HID_CONTROLLER_TIMEOUT, delayMillis)
    }

    fun cancelHidControllerTimeout() {
        removeMessages(MSG_HID_CONTROLLER_TIMEOUT)
    }

    override fun handleMessage(msg: Message) {
        val service = mService.get()
        if (service == null) {
            return
        }
        when (msg.what) {
            MSG_REGISTER_CALLBACK -> {
                handleRegisterCallback(service,
                        msg.obj as IBrailleServiceCallback)
            }
            MSG_ON_DISPLAY_CONNECTED -> {
                handleOnDisplayConnected(service,
                    msg.obj as BrailleDisplayProperties)
            }
            MSG_ON_DISPLAY_DISCONNECTED -> {
                handleOnDisplayDisconnected(service)
            }
            MSG_SET_CONNECTION_PROGRESS -> {
                handleSetConnectionProgress(service, msg.obj as String)
            }
            MSG_ON_INPUT_EVENT -> {
                handleOnInputEvent(service, msg.obj as BrailleInputEvent)
            }
            MSG_CONNECT_BRAILLE -> {
                if (msg.arg1 != 0) {
                    service.startExplicitConnect()
                } else {
                    service.connectBraille()
                }
            }
            MSG_DISCONNECT_BRAILLE -> {
                service.cancelPendingReconnect()
                service.disconnectBraille(msg.arg1 != 0)
            }
            MSG_ON_HID_CONNECTED -> {
                handleOnHidConnected(service, msg.obj as HidEvent)
            }
            MSG_ON_HID_DISCONNECTED -> {
                handleOnHidDisconnected(service, msg.obj as HidEvent)
            }
            MSG_ON_HID_CONNECT_FAILED -> {
                handleOnHidConnectFailed(service, msg.obj as HidEvent)
            }
            MSG_RETRY_HID -> {
                handleRetryHid(service)
            }
            MSG_ON_WUJIE_CONNECTED -> {
                handleOnWujieConnected(service, msg.obj as WujieEvent)
            }
            MSG_ON_WUJIE_DISCONNECTED -> {
                handleOnWujieDisconnected(service, msg.obj as WujieEvent)
            }
            MSG_ON_WUJIE_CONNECT_FAILED -> {
                handleOnWujieConnectFailed(service, msg.obj as WujieEvent)
            }
            MSG_ON_WUJIE_INPUT_EVENT -> {
                handleOnWujieInputEvent(service,
                        msg.obj as WujieInputEvent)
            }
            MSG_HID_CONTROLLER_TIMEOUT -> {
                handleHidControllerTimeout(service)
            }
            MSG_AUTO_RECONNECT -> {
                handleAutoReconnect(service)
            }
            MSG_DISCOVERY_TIMEOUT -> {
                service.stopDiscoverySession("discovery window expired")
            }
            MSG_DISCOVERY_RESTART -> {
                service.startDiscoveryInternal("discovery cycle restart")
            }
            else -> {  // fall out
            }
        }
    }
}
