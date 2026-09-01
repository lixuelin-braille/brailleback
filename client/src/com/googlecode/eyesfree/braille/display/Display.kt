/*
 * Copyright (C) 2012 Google Inc.
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
 * An interface for accessing a Braille display.
 *
 * @see DisplayClient
 */
interface Display {

    companion object {
        /** Initial value, which is never reported to the listener. */
        const val STATE_UNKNOWN = -2
        const val STATE_ERROR = -1
        const val STATE_NOT_CONNECTED = 0
        const val STATE_CONNECTED = 1
    }

    /**
     * A callback interface to get informed about connection state changes.
     */
    interface OnConnectionStateChangeListener {
        fun onConnectionStateChanged(state: Int)
    }

    /**
     * A callback interface to get informed about connection progress changes.
     */
    interface OnConnectionChangeProgressListener {
        /**
         * The service is taking some action that might eventually lead
         * to a change in connection state, such as trying to connect.
         * The [description] is a human-readable (localized) string
         * describing the current progress.  There is no guarantee that this
         * method will be called before a change in connection state.
         */
        fun onConnectionChangeProgress(description: String?)
    }

    /**
     * A callback interface for input from the braille display.
     */
    interface OnInputEventListener {
        fun onInputEvent(inputEvent: BrailleInputEvent)
    }

    /**
     * Sets a [listener] for connection state changes.
     * [listener] can be `null` to remove a previously set listener.
     */
    fun setOnConnectionStateChangeListener(listener: OnConnectionStateChangeListener?)

    /**
     * Sets a [listener] for connection change progress.
     * [listener] can be `null` to remove a previously set listener.
     */
    fun setOnConnectionChangeProgressListener(listener: OnConnectionChangeProgressListener?)

    /**
     * Sets a [listener] for input events.  [listener] can be `null` to
     * remove a previously set listener.
     */
    fun setOnInputEventListener(listener: OnInputEventListener?)

    /**
     * Returns the display properties, or `null` if not connected to a display.
     */
    val displayProperties: BrailleDisplayProperties?

    /**
     * Displays a given dots configuration on the braille display.
     *
     * @param patterns Dots configuration to be displayed.
     * @param text Plain text equivalent of the displayed dots.
     * @param brailleToTextPositions Map from indices in `patterns`
     *        to the corresponding indices in `text`. Must be equal
     *        in length to `patterns`, and contain only valid
     *        indices in `text`.
     */
    fun displayDots(
        patterns: ByteArray?,
        text: CharSequence?,
        brailleToTextPositions: IntArray?
    )

    /**
     * Asks the service to try to connect to a display.
     *
     * @see IBrailleService.poll
     */
    fun poll()

    /**
     * Asks the service to disconnect the currently connected braille display.
     * This is intended to be used in response to an explicit user action, such
     * as a customizable volume-key gesture that toggles the Bluetooth
     * connection.  Unlike [shutdown], this does not unbind from the service,
     * so the display can be reconnected later via [poll].
     *
     * @see IBrailleService.disconnect
     */
    fun disconnect()

    /**
     * Unbinds from the braille display service and deallocates any resources.
     * This method should be called when the braille display is no longer in
     * use by this client.
     */
    fun shutdown()

    /**
     * Returns `true` if this display is simulated.
     * Intended for user feedback purposes. This value may change, but only
     * while the display is disconnected.
     */
    val isSimulated: Boolean
}
