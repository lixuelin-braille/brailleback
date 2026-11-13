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

import android.content.Context
import android.content.res.Resources
import android.util.Log
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.googlecode.eyesfree.braille.display.BrailleKeyBinding
import com.googlecode.eyesfree.braille.display.BluetoothDeviceEntry
import com.googlecode.eyesfree.braille.display.UsbDeviceEntry
import java.io.File
import java.io.IOException

/**
 * Wrapper for the driver functionality of brltty.
 *
 * This class provides a low-level interface to the functionality of brltty that
 * is used to control braille displays.
 *
 * Since brltty is single-threaded and relies on some global state, there can
 * only be one instance of this class active at any given time. In addition,
 * after construction, all method calls must be made from one thread (which may
 * be different from the thread used to construct the object). The one exception
 * to this rule is [addBytesFromDevice], which can be called from any thread.
 *
 * ## JNI contract (do not "tidy" any of this)
 *
 * `BrlttyWrapper.c` binds to this class by name and by these exact members; a
 * rename or a change of static-ness resolves to a missing symbol at runtime,
 * not at compile time:
 *
 * - instance field `tablesDirPath` of type `String` — looked up with `GetFieldID`;
 * - private instance methods `sendBytesToDevice([B)Z` and `readDelayed(J)V` —
 *   called back from the driver thread;
 * - static natives `classInitNative`, `getUsbDriverTableNative`,
 *   `getBluetoothDriverTableNative` — declared in the companion so they stay
 *   real static methods on `BrlttyWrapper`;
 * - instance natives `initNative`, `startNative`, `stopNative`,
 *   `writeWindowNative`, `readCommandNative`, `addBytesFromDeviceNative`,
 *   `getKeyMapNative`, `getTextCellsNative`, `getStatusCellsNative` — these
 *   must stay instance members; moving them to the companion would change the
 *   JNI receiver from `jobject` to `jclass`.
 *
 * `tablesDirPath` is a plain Kotlin property only because Kotlin emits a
 * backing field of that exact name for it. Converting it to a computed
 * property would delete the field the native side reads.
 */
class BrlttyWrapper(
        private val mDeviceInfo: DeviceFinder.DeviceInfo,
        private val mDriverThread: DriverThread,
        private val mResources: Resources,
        tablesDir: File,
        context: Context
) {

    /**
     * The tables directory handed to brltty. Kept as a plain `val` so the
     * compiler emits the `tablesDirPath` field that JNI reads by name.
     */
    @Suppress("unused")
    private val tablesDirPath: String = tablesDir.path

    init {
        initNative(context)
    }

    /**
     * Initializes the driver, making sure there is a device connected.
     */
    fun start(): Boolean {
        var brailleDevice: String? = mDeviceInfo.getBrailleDevice()
        if (brailleDevice == null) {
            try {
                brailleDevice = deviceBrlttyAddress()
            } catch (e: SecurityException) {
                Log.e(LOG_TAG,
                        "BLUETOOTH_CONNECT permission denied; cannot start driver",
                        e)
                return false
            }
        }
        return startNative(mDeviceInfo.getDriverCode(), brailleDevice,
                START_TIMEOUT_FACTOR)
    }

    /**
     * Uninitializes the driver and cleans up any resources allocated by this
     * object.
     */
    fun stop() {
        stopNative()
    }

    fun getDisplayProperties(): BrailleDisplayProperties {
        val keyBindings: Array<BrailleKeyBinding> = try {
            getKeyMap()
        } catch (ex: RuntimeException) {
            // A single unnamed key used to abort the whole key map listing and
            // the resulting exception killed the driver thread (and therefore
            // the process). The display itself is initialised at this point,
            // so report it without key bindings instead of crashing.
            Log.e(LOG_TAG, "Couldn't read key map; continuing without key bindings",
                    ex)
            emptyArray()
        }
        return BrailleDisplayProperties(
                getTextCellsNative(),
                getStatusCellsNative(),
                keyBindings,
                getFriendlyKeyNames(keyBindings))
    }

    /**
     * Sets the content of the braille display to [pattern], where each byte
     * represents one braille cell. [pattern] should be as large as the total
     * number of cells on the display.
     */
    fun writeWindow(pattern: ByteArray): Boolean = writeWindowNative(pattern)

    /**
     * Polls the driver for a single key command, returning -1 if no command is
     * available.
     *
     * This is typically used when more input data from the device has been
     * detected. This call does not block indefinitely, but it can still block
     * for small intervals while waiting for more input from the device.
     */
    fun readCommand(): Int = readCommandNative()

    /**
     * Adds more data to be consumed by the driver. This method can be called at
     * any time after an object of this class has been constructed until
     * [stop] is called. It can be called from any thread. In particular, it is
     * important to keep calling this method during other calls that might
     * consume input so that they can proceed and consume that input.
     */
    @Throws(IOException::class)
    fun addBytesFromDevice(bytes: ByteArray, size: Int) {
        addBytesFromDeviceNative(bytes, size)
    }

    /**
     * Returns the address of the connected device in the form expected by the
     * brltty drivers.
     */
    private fun deviceBrlttyAddress(): String {
        val device = mDeviceInfo.getBluetoothDevice()
        if (device == null) {
            throw IllegalStateException("Display ${mDeviceInfo.getDriverCode()} has no bluetooth device")
        }
        return String.format("bluetooth:%s", device.address)
    }

    private fun getKeyMap(): Array<BrailleKeyBinding> {
        val fullKeyMap = getKeyMapNative() ?: return emptyArray()
        val relevant = fullKeyMap.filter { hasAllFriendlyKeyNames(it) }
        if (relevant.size == fullKeyMap.size) {
            return fullKeyMap
        }
        return relevant.toTypedArray()
    }

    private fun hasAllFriendlyKeyNames(binding: BrailleKeyBinding): Boolean {
        val friendlyNames = mDeviceInfo.getFriendlyKeyNames()
        return binding.keyNames.all { friendlyNames.containsKey(it) }
    }

    private fun getFriendlyKeyNames(
            bindings: Array<BrailleKeyBinding>
    ): Map<String, String> {
        val result = HashMap<String, String>()
        val friendlyNames = mDeviceInfo.getFriendlyKeyNames()
        for (binding in bindings) {
            for (key in binding.keyNames) {
                val resId = friendlyNames[key]
                result[key] = if (resId != null) mResources.getString(resId) else key
            }
        }
        return result
    }

    // Native methods. See the class comment: instance vs static matters.

    @Suppress("unused")
    private external fun initNative(context: Context): Boolean

    @Suppress("unused")
    private external fun startNative(
            driverCode: String?,
            brailleDevice: String?,
            timeoutFactor: Float
    ): Boolean

    @Suppress("unused")
    private external fun stopNative()

    private external fun writeWindowNative(pattern: ByteArray): Boolean

    private external fun readCommandNative(): Int

    @Throws(IOException::class)
    private external fun addBytesFromDeviceNative(bytes: ByteArray, size: Int)

    private external fun getKeyMapNative(): Array<BrailleKeyBinding>?

    private external fun getTextCellsNative(): Int

    private external fun getStatusCellsNative(): Int

    // Callbacks used from native code. Names and signatures are asserted by
    // BrlttyWrapper.c's GetMethodID calls.

    /**
     * Writes bytes from the driver over bluetooth to the device.
     */
    @Suppress("unused")
    private fun sendBytesToDevice(command: ByteArray): Boolean =
            mDriverThread.sendBytesToDevice(command)

    /**
     * Called from the command logic in the driver to have the driver thread
     * woken up to read another command after [delayMillis] milliseconds.
     */
    @Suppress("unused")
    private fun readDelayed(delayMillis: Long) {
        mDriverThread.readDelayed(delayMillis)
    }

    // End callbacks.

    companion object {

        private const val LOG_TAG = "BrlttyWrapper"
        private const val START_TIMEOUT_FACTOR = 2f

        init {
            System.loadLibrary("brlttywrap")
            classInitNative()
        }

        /** Static natives: their JNI receiver is `jclass`, not `jobject`. */
        @Suppress("unused")
        @JvmStatic
        private external fun classInitNative()

        @Suppress("unused")
        @JvmStatic
        private external fun getUsbDriverTableNative(): List<UsbDeviceEntry>?

        @Suppress("unused")
        @JvmStatic
        private external fun getBluetoothDriverTableNative(): List<BluetoothDeviceEntry>?

        /**
         * Returns the brltty USB device table (driver code / VID / PID) exported
         * by the prebuilt `libbrlttywrap.so`.
         *
         * Any linkage or JNI failure is logged and turned into an empty list so
         * that a broken/stale .so can never take down the display service.
         */
        @JvmStatic
        fun getUsbDriverTable(): List<UsbDeviceEntry> = try {
            getUsbDriverTableNative() ?: emptyList()
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Could not read the native USB driver table", t)
            emptyList()
        }

        /** P0 probe (debug builds only): logs the size and first table entry. */
        @JvmStatic
        fun logUsbDriverTableProbe() {
            val entries = getUsbDriverTable()
            Log.i(LOG_TAG, "USB driver table probe: entries=" + entries.size)
            if (entries.isNotEmpty()) {
                val first = entries[0]
                Log.i(LOG_TAG, "USB driver table probe: first=" + first.driver
                        + " vid=" + first.vendorId + " pid=" + first.productId)
            }
        }

        /**
         * Returns the brltty bluetooth name table (name prefix / driver code)
         * exported by the prebuilt `libbrlttywrap.so`.
         *
         * BRLTTY ships its own map from a device's advertised name to the driver
         * that can talk to it. The app carried a hand-written subset of it in
         * [DeviceFinder], which covered only ten driver codes; this exposes the
         * full table so that displays missing from the app's list can still be
         * recognised over RFCOMM.
         *
         * Any linkage or JNI failure is logged and turned into an empty list.
         */
        @JvmStatic
        fun getBluetoothDriverTable(): List<BluetoothDeviceEntry> = try {
            getBluetoothDriverTableNative() ?: emptyList()
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Could not read the native bluetooth driver table", t)
            emptyList()
        }
    }
}
