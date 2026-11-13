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

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.util.Log
import java.util.Locale

/**
 * Static bridge used by brltty's native Bluetooth layer.
 *
 * `bluetooth_android.c` resolves this class by name and looks up four static
 * methods on it:
 *
 * - `getName(long)` — display name for a device address, used by
 *   `bthObtainDeviceName()`;
 * - `getPairedDeviceCount()`, `getPairedDeviceAddress(int)` and
 *   `getPairedDeviceName(int)` — the bonded device list walked by
 *   `bthProcessDiscoveredDevices()`, which is how brltty identifies a display
 *   it was not told about explicitly.
 *
 * The class was missing from this fork: the native side then failed every one
 * of those lookups, so Bluetooth device names never resolved and brltty's own
 * paired-device discovery was a no-op. Nothing here opens a socket — the
 * RFCOMM socket and its file descriptors are still owned by [ReadThread].
 *
 * The bonded list is snapshotted so that a walk of `count` followed by
 * `address(i)` / `name(i)` cannot see the set shift underneath it.
 *
 * Every call is defensive: on Android 12+ reading bonded devices needs
 * `BLUETOOTH_CONNECT`, and this can be reached from the native driver thread
 * where an uncaught exception would take down the process.
 *
 * Declared as an `object` with `@JvmStatic` members: JNI looks the methods up
 * by class name, so they must stay real static methods on the generated
 * `BluetoothConnection` class.
 */
@SuppressLint("MissingPermission")
object BluetoothConnection {

    private const val LOG_TAG = "BluetoothConnection"

    /** Cache lifetime for the bonded-device snapshot. */
    private const val SNAPSHOT_TTL_MS = 2000L

    private val mLock = Any()

    private var mSnapshot: List<BluetoothDevice> = emptyList()
    private var mSnapshotAt = 0L

    /**
     * Returns the name to show for a device address.
     *
     * Falls back to the formatted address when the device is not bonded or the
     * name is unreadable, which keeps the caller's log useful either way.
     */
    @JvmStatic
    fun getName(bda: Long): String {
        val address = formatAddress(bda)
        for (device in snapshot()) {
            try {
                if (address.equals(device.address, ignoreCase = true)) {
                    val name = device.name
                    if (!name.isNullOrEmpty()) {
                        return name
                    }
                }
            } catch (e: SecurityException) {
                Log.w(LOG_TAG, "BLUETOOTH_CONNECT denied reading device name", e)
                break
            }
        }
        return address
    }

    /** Number of bonded devices; also refreshes the snapshot used below. */
    @JvmStatic
    fun getPairedDeviceCount(): Int = refreshSnapshot().size

    /** Bonded device address by index, or null when out of range. */
    @JvmStatic
    fun getPairedDeviceAddress(index: Int): String? {
        val device = deviceAt(index) ?: return null
        return try {
            device.address
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "BLUETOOTH_CONNECT denied reading device address", e)
            null
        }
    }

    /** Bonded device name by index, or null when unreadable. */
    @JvmStatic
    fun getPairedDeviceName(index: Int): String? {
        val device = deviceAt(index) ?: return null
        return try {
            device.name
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "BLUETOOTH_CONNECT denied reading device name", e)
            null
        }
    }

    /**
     * Formats a brltty Bluetooth address (the low 48 bits of the MAC) the way
     * Android prints it, e.g. `12:34:56:AB:CD:EF`.
     */
    @JvmStatic
    fun formatAddress(bda: Long): String {
        val mac = bda and 0xFFFFFFFFFFFFL
        return String.format(
                Locale.US, "%02X:%02X:%02X:%02X:%02X:%02X",
                (mac ushr 40) and 0xFFL,
                (mac ushr 32) and 0xFFL,
                (mac ushr 24) and 0xFFL,
                (mac ushr 16) and 0xFFL,
                (mac ushr 8) and 0xFFL,
                mac and 0xFFL)
    }

    private fun deviceAt(index: Int): BluetoothDevice? {
        val devices = snapshot()
        if (index < 0 || index >= devices.size) {
            return null
        }
        return devices[index]
    }

    private fun snapshot(): List<BluetoothDevice> = synchronized(mLock) {
        val now = System.currentTimeMillis()
        if (now - mSnapshotAt > SNAPSHOT_TTL_MS) {
            refreshSnapshotLocked(now)
        }
        mSnapshot
    }

    private fun refreshSnapshot(): List<BluetoothDevice> = synchronized(mLock) {
        refreshSnapshotLocked(System.currentTimeMillis())
        mSnapshot
    }

    private fun refreshSnapshotLocked(now: Long): List<BluetoothDevice> {
        mSnapshotAt = now
        mSnapshot = readBondedDevices()
        return mSnapshot
    }

    @Suppress("DEPRECATION")
    private fun readBondedDevices(): List<BluetoothDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return try {
            val bonded = adapter.bondedDevices
            if (bonded.isNullOrEmpty()) {
                emptyList()
            } else {
                bonded.filterNotNull()
            }
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "BLUETOOTH_CONNECT denied reading bonded devices", e)
            emptyList()
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "Could not read bonded devices", e)
            emptyList()
        }
    }
}
