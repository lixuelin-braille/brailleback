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

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.BrailleKeyBinding
import com.googlecode.eyesfree.braille.service.R
import java.util.ArrayDeque
import java.util.ArrayList
import java.util.Collections
import java.util.Comparator
import java.util.HashMap
import java.util.Locale
import java.util.UUID
import timber.log.Timber

/**
 * Talks to the Wujie / Orbit 30-cell display over its private BLE GATT
 * service.
 *
 * The firmware exposes one custom service whose value characteristics use
 * the short UUIDs `…def1` through `…defa`. A braille frame is 30
 * raw cell bytes (or 0x02 followed by 30 bytes) written to one of the writable
 * characteristics. This class discovers the actual handles and properties at
 * runtime instead of hard-coding a handle number, then serialises every GATT
 * operation because the Android stack allows only one at a time.
 */
@SuppressLint("MissingPermission")
class WujieGattConnection {
    /** Callbacks to [DisplayService]. */
    interface Listener {
        fun onWujieConnected(
                source: WujieGattConnection?, properties: BrailleDisplayProperties?)
        fun onWujieInputEvent(
                source: WujieGattConnection?, event: BrailleInputEvent?)
        fun onWujieDisconnected(source: WujieGattConnection?)
        fun onWujieConnectFailed(source: WujieGattConnection?, error: Int)
    }

    private val mContext: Context
    private val mListener: Listener
    private val mHandler = Handler(Looper.getMainLooper())

    private val mLock = Object()
    private val mOps = ArrayDeque<Runnable>()
    private val mFrames = ArrayDeque<ByteArray>(1)
    private val mWriteCandidates = ArrayList<BluetoothGattCharacteristic>()
    private val mNotifyChars = ArrayList<BluetoothGattCharacteristic>()

    @Volatile private var mGatt: BluetoothGatt? = null
    @Volatile private var mClosed = false
    private var mOpInFlight = false
    private var mFrameOpQueued = false
    private var mCandidateIndex = 0
    private var mInFlightFrame: ByteArray? = null
    private var mMtu = 23
    private var mServicesDiscovered = false
    private var mConnectedNotified = false
    private var mFailureNotified = false
    private var mSetupComplete = false

    private val mConnectTimeout = Runnable {
        Timber.w("Wujie GATT connect timed out")
        fail(ERROR_CONNECT)
    }

    private val mSetupTimeout = Runnable {
        if (!mSetupComplete) {
            Timber.w("Wujie GATT setup incomplete after %d ms; " +
                    "continuing with the display", SETUP_TIMEOUT_MS)
            finishSetup()
        }
    }

    private val mCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int,
                newState: Int) {
            if (mClosed || gatt != mGatt) {
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED
                    && status == BluetoothGatt.GATT_SUCCESS) {
                Timber.i("Wujie GATT connected; requesting MTU %d",
                        REQUESTED_MTU)
                mHandler.removeCallbacks(mConnectTimeout)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    gatt.requestMtu(REQUESTED_MTU)
                }
                if (!gatt.discoverServices()) {
                    Timber.e("Wujie GATT discoverServices() failed to start")
                    fail(ERROR_SERVICE)
                }
                mHandler.postDelayed(mSetupTimeout, SETUP_TIMEOUT_MS)
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Timber.i("Wujie GATT disconnected (status=%d)", status)
                if (!mConnectedNotified) {
                    fail(if (status == BluetoothGatt.GATT_SUCCESS)
                            ERROR_CONNECT else ERROR_CONNECT)
                } else {
                    notifyDisconnected()
                }
                closeGatt()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (mClosed || gatt != mGatt) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.e("Wujie GATT service discovery failed (status=%d)",
                        status)
                fail(ERROR_SERVICE)
                return
            }
            mServicesDiscovered = true
            inspectServices(gatt)
            if (mClosed || mFailureNotified) {
                return
            }
            startSetup(gatt)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (mClosed || gatt != mGatt) {
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mMtu = mtu
                Timber.i("Wujie GATT MTU is %d", mtu)
            } else {
                Timber.w("Wujie GATT MTU request failed (status=%d), MTU=%d",
                        status, mtu)
            }
            maybeEstablishDisplay()
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor, status: Int) {
            if (mClosed || gatt != mGatt) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("Wujie GATT descriptor write failed for %s " +
                        "(status=%d)", descriptor.getUuid(), status)
            }
            finishOp()
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic, status: Int) {
            if (mClosed || gatt != mGatt) {
                return
            }
            val value = characteristic.getValue()
            if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
                Timber.i("Wujie GATT read %s = %s",
                        shortUuid(characteristic.getUuid()), toHex(value))
                if (SPEC_UUID.equals(characteristic.getUuid())
                        && value.size >= 2) {
                    val rows = value[0].toInt() and 0xFF
                    val cells = value[1].toInt() and 0xFF
                    Timber.i("Wujie display reports %d row(s) x %d cell(s)",
                            rows, cells)
                }
            } else {
                Timber.w("Wujie GATT read %s failed (status=%d)",
                        shortUuid(characteristic.getUuid()), status)
            }
            finishOp()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic, status: Int) {
            if (mClosed || gatt != mGatt) {
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                finishOp()
                return
            }
            Timber.w("Wujie GATT write to %s failed (status=%d)",
                    shortUuid(characteristic.getUuid()), status)
            retryWithNextCandidate()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic) {
            if (mClosed || gatt != mGatt) {
                return
            }
            val value = characteristic.getValue()
            Timber.i("Wujie GATT notify %s = %s",
                    shortUuid(characteristic.getUuid()), toHex(value))
            if (!NOTIFY_UUID.equals(characteristic.getUuid())) {
                return
            }
            val key = WujieKeyEventCodec.decode(value)
            if (key == WujieKeyEventCodec.COMMAND_NONE) {
                Timber.w("Ignoring unknown Wujie key payload on def1")
                return
            }
            val command = if (key == WujieKeyEventCodec.COMMAND_NEXT)
                    BrailleInputEvent.CMD_NAV_PAN_RIGHT
                    else BrailleInputEvent.CMD_NAV_PAN_LEFT
            val event = BrailleInputEvent(
                    command, 0, SystemClock.uptimeMillis())
            Timber.i("Wujie key %s -> %s",
                    if (key == WujieKeyEventCodec.COMMAND_NEXT)
                            KEY_NAME_NEXT else KEY_NAME_PREVIOUS,
                    BrailleInputEvent.commandToString(command))
            mListener.onWujieInputEvent(this@WujieGattConnection, event)
        }
    }

    constructor(context: Context, listener: Listener) {
        mContext = context.applicationContext
        mListener = listener
    }

    /** Connects as a BLE GATT client. Must not be called twice. */
    fun connect(deviceAddress: String?) {
        if (deviceAddress == null) {
            fail(ERROR_CONNECT)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && mContext.checkSelfPermission(
                        Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            Timber.e("BLUETOOTH_CONNECT permission not granted")
            fail(ERROR_PERMISSION)
            return
        }
        val device: BluetoothDevice
        try {
            val adapter: android.bluetooth.BluetoothAdapter? =
                    android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) {
                fail(ERROR_CONNECT)
                return
            }
            device = adapter.getRemoteDevice(deviceAddress)
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Invalid Wujie device address %s", deviceAddress)
            fail(ERROR_CONNECT)
            return
        } catch (e: SecurityException) {
            Timber.e(e, "Invalid Wujie device address %s", deviceAddress)
            fail(ERROR_CONNECT)
            return
        }
        Timber.i("Connecting to Wujie display %s over LE GATT", deviceAddress)
        mGatt = device.connectGatt(mContext, false, mCallback,
                BluetoothDevice.TRANSPORT_LE)
        if (mGatt == null) {
            Timber.e("connectGatt returned null for %s", deviceAddress)
            fail(ERROR_CONNECT)
            return
        }
        mHandler.postDelayed(mConnectTimeout, CONNECT_TIMEOUT_MS)
    }

    /** Queues one braille window; only the newest pending frame is kept. */
    fun writeWindow(patterns: ByteArray?) {
        if (mClosed) {
            return
        }
        val frame = WujieBrailleFrameCodec.encode(
                patterns, WujieBrailleFrameCodec.CELL_COUNT, false)
        synchronized(mLock) {
            mFrames.clear()
            mFrames.add(frame)
            queueFrameOpLocked()
        }
    }

    /** Tears the connection down immediately. */
    fun disconnect() {
        if (mClosed) {
            return
        }
        mClosed = true
        mHandler.removeCallbacks(mConnectTimeout)
        mHandler.removeCallbacks(mSetupTimeout)
        val gatt = mGatt
        if (gatt != null) {
            try {
                gatt.disconnect()
            } catch (e: SecurityException) {
                Timber.w(e, "disconnect() denied")
            }
            closeGatt()
        }
        synchronized(mLock) {
            mOps.clear()
            mFrames.clear()
            mOpInFlight = false
            mFrameOpQueued = false
        }
        notifyDisconnected()
    }

    private fun inspectServices(gatt: BluetoothGatt) {
        val all = ArrayList<BluetoothGattCharacteristic>()
        val target = gatt.getService(SERVICE_UUID)
        if (target == null) {
            Timber.e("Wujie service %s not found", SERVICE_UUID)
            fail(ERROR_SERVICE)
            return
        }
        all.addAll(target.getCharacteristics())
        Timber.i("Wujie GATT characteristics (%d):", all.size)
        for (characteristic in all) {
            Timber.i("  %s props=0x%02x permissions=0x%02x",
                    shortUuid(characteristic.getUuid()),
                    characteristic.getProperties(),
                    characteristic.getPermissions())
        }

        mWriteCandidates.clear()
        mNotifyChars.clear()
        var writeA: BluetoothGattCharacteristic? = null
        var writeB: BluetoothGattCharacteristic? = null
        var aux: BluetoothGattCharacteristic? = null
        var extra: BluetoothGattCharacteristic? = null
        var notify: BluetoothGattCharacteristic? = null
        var version: BluetoothGattCharacteristic? = null
        var battery: BluetoothGattCharacteristic? = null
        var spec: BluetoothGattCharacteristic? = null
        for (characteristic in all) {
            val uuid = characteristic.getUuid()
            if (NOTIFY_UUID.equals(uuid)) {
                notify = characteristic
            } else if (WRITE_A_UUID.equals(uuid)) {
                writeA = characteristic
            } else if (WRITE_B_UUID.equals(uuid)) {
                writeB = characteristic
            } else if (AUX_UUID.equals(uuid)) {
                aux = characteristic
            } else if (EXTRA_UUID.equals(uuid)) {
                extra = characteristic
            } else if (VERSION_UUID.equals(uuid)) {
                version = characteristic
            } else if (BATTERY_UUID.equals(uuid)) {
                battery = characteristic
            } else if (SPEC_UUID.equals(uuid)) {
                spec = characteristic
            }
            if (isWritable(characteristic)) {
                // Add the known braille channels first, then any unknown
                // writable characteristic as a last-resort fallback.
                if (!BATTERY_UUID.equals(uuid) && !SPEC_UUID.equals(uuid)
                        && !VERSION_UUID.equals(uuid)) {
                    mWriteCandidates.add(characteristic)
                }
            }
        }
        Collections.sort(mWriteCandidates,
                object : Comparator<BluetoothGattCharacteristic> {
                    override fun compare(a: BluetoothGattCharacteristic,
                            b: BluetoothGattCharacteristic): Int {
                        return rankForWrite(a.getUuid())
                                .compareTo(rankForWrite(b.getUuid()))
                    }
                })
        if (notify != null) {
            mNotifyChars.add(notify)
        }
        if (version != null && supportsNotify(version)) {
            mNotifyChars.add(version)
        }
        if (battery != null && supportsNotify(battery)) {
            mNotifyChars.add(battery)
        }
        if (aux != null && supportsNotify(aux)) {
            mNotifyChars.add(aux)
        }
        Timber.i("Wujie write candidates: %s", describe(mWriteCandidates))
        Timber.i("Wujie notify characteristics: %s", describe(mNotifyChars))

        if (mWriteCandidates.isEmpty()) {
            Timber.e("Wujie display exposes no writable characteristic")
            fail(ERROR_NO_WRITE)
            return
        }
        mCandidateIndex = 0
        // Reference the known handles so the compiler does not warn about
        // unused locals if the layout changes.
        if (writeA == null) {
            Timber.w("Wujie write channel def2 not present")
        }
        if (writeB == null) {
            Timber.w("Wujie write channel def3 not present")
        }
        if (extra != null) {
            Timber.i("Wujie extra characteristic defa present")
        }
        if (spec != null) {
            Timber.i("Wujie spec characteristic found")
        }
    }

    private fun startSetup(gatt: BluetoothGatt) {
        // Subscribe to the device-to-phone channel first, then read the
        // identity characteristics. Each operation is serialised by mOps.
        for (characteristic in mNotifyChars) {
            enqueue(Runnable {
                enableNotifications(mGatt, characteristic)
            })
        }
        enqueueRead(SPEC_UUID)
        enqueueRead(VERSION_UUID)
        enqueueRead(BATTERY_UUID)
        maybeEstablishDisplay()
    }

    private fun enqueueRead(uuid: UUID) {
        enqueue(Runnable {
            val gatt = mGatt
            val characteristic = if (gatt == null) null
                    else findCharacteristic(gatt, uuid)
            if (characteristic == null
                    || gatt?.readCharacteristic(characteristic) != true) {
                finishOp()
            }
        })
    }

    private fun enableNotifications(gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?) {
        if (gatt == null || characteristic == null) {
            finishOp()
            return
        }
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Timber.w("setCharacteristicNotification failed for %s",
                    shortUuid(characteristic.getUuid()))
            finishOp()
            return
        }
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            finishOp()
            return
        }
        val cccdValue =
                if ((characteristic.getProperties()
                                and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (gatt.writeDescriptor(descriptor, cccdValue)
                    != BluetoothGatt.GATT_SUCCESS) {
                finishOp()
            }
        } else {
            descriptor.setValue(cccdValue)
            if (!gatt.writeDescriptor(descriptor)) {
                finishOp()
            }
        }
    }

    /** Called once the GATT tree is usable; reports the display upstream. */
    private fun maybeEstablishDisplay() {
        if (mClosed || mConnectedNotified || !mServicesDiscovered) {
            return
        }
        // Wait for a usable MTU. If the stack keeps the default 23, the write
        // will fail and the setup timeout reports the reason instead of
        // hanging forever.
        if (mMtu < MIN_MTU) {
            return
        }
        mConnectedNotified = true
        mHandler.removeCallbacks(mConnectTimeout)
        val properties = createDisplayProperties()
        Timber.i("Wujie display ready (MTU=%d)", mMtu)
        // The firmware blanks the display when no frame arrives within five
        // seconds, so send a blank frame immediately.
        ensureInitialFrame()
        mListener.onWujieConnected(this, properties)
    }

    private fun createDisplayProperties(): BrailleDisplayProperties {
        val keyBindings = arrayOf(
                BrailleKeyBinding(BrailleInputEvent.CMD_NAV_PAN_RIGHT,
                        arrayOf(KEY_NAME_NEXT), false),
                BrailleKeyBinding(BrailleInputEvent.CMD_NAV_PAN_LEFT,
                        arrayOf(KEY_NAME_PREVIOUS), false))
        val friendlyKeyNames = HashMap<String, String>()
        friendlyKeyNames.put(KEY_NAME_NEXT,
                mContext.getString(R.string.key_wujie_page_next))
        friendlyKeyNames.put(KEY_NAME_PREVIOUS,
                mContext.getString(R.string.key_wujie_page_previous))
        return BrailleDisplayProperties(
                WujieBrailleFrameCodec.CELL_COUNT, 0,
                keyBindings, friendlyKeyNames)
    }

    /** Sends one blank frame unless the upper layer already queued output. */
    private fun ensureInitialFrame() {
        synchronized(mLock) {
            if (mFrames.isEmpty()) {
                mFrames.add(WujieBrailleFrameCodec.encode(
                        null, WujieBrailleFrameCodec.CELL_COUNT, false))
            }
            queueFrameOpLocked()
        }
    }

    /** Queues the single pending frame operation. Caller holds mLock. */
    private fun queueFrameOpLocked() {
        if (mFrameOpQueued || !mServicesDiscovered) {
            return
        }
        mFrameOpQueued = true
        mOps.add(Runnable {
            writeNextFrame()
        })
        runNextOpLocked()
    }

    private fun finishSetup() {
        if (mSetupComplete) {
            return
        }
        mSetupComplete = true
        if (!mConnectedNotified && !mClosed) {
            if (mMtu < MIN_MTU) {
                Timber.e("Wujie display needs MTU >= %d but negotiated %d",
                        MIN_MTU, mMtu)
                fail(ERROR_MTU)
            } else {
                maybeEstablishDisplay()
            }
        }
    }

    private fun writeNextFrame() {
        val frame = synchronized(mLock) {
            val polled = mFrames.poll()
            if (polled == null) {
                mFrameOpQueued = false
                finishOpLocked()
                return
            }
            mFrameOpQueued = false
            polled
        }
        mInFlightFrame = frame
        if (!writeWithCurrentCandidate(frame)) {
            retryWithNextCandidate()
        }
    }

    private fun writeWithCurrentCandidate(frame: ByteArray): Boolean {
        val gatt = mGatt
        if (gatt == null || mWriteCandidates.isEmpty()) {
            return false
        }
        if (mCandidateIndex >= mWriteCandidates.size) {
            return false
        }
        val characteristic = mWriteCandidates.get(mCandidateIndex)
        val writeType = if (isWritableWithoutResponse(characteristic))
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(
                    characteristic, frame, writeType)
            return result == BluetoothGatt.GATT_SUCCESS
        }
        characteristic.setWriteType(writeType)
        characteristic.setValue(frame)
        return gatt.writeCharacteristic(characteristic)
    }

    private fun retryWithNextCandidate() {
        if (mCandidateIndex + 1 < mWriteCandidates.size) {
            mCandidateIndex++
            val frame = mInFlightFrame
            Timber.i("Wujie retrying frame on %s",
                    shortUuid(mWriteCandidates.get(mCandidateIndex).getUuid()))
            if (frame != null && writeWithCurrentCandidate(frame)) {
                return
            }
        }
        Timber.w("Wujie dropped a frame after exhausting write channels")
        mInFlightFrame = null
        synchronized(mLock) {
            mFrameOpQueued = false
            queueFrameOpLocked()
        }
        finishOp()
    }

    private fun enqueue(op: Runnable) {
        synchronized(mLock) {
            mOps.add(op)
            runNextOpLocked()
        }
    }

    private fun runNextOpLocked() {
        if (mOpInFlight || mClosed) {
            return
        }
        val next = mOps.poll()
        if (next == null) {
            return
        }
        mOpInFlight = true
        next.run()
    }

    private fun finishOp() {
        synchronized(mLock) {
            finishOpLocked()
        }
    }

    private fun finishOpLocked() {
        mOpInFlight = false
        runNextOpLocked()
    }

    private fun fail(error: Int) {
        if (mFailureNotified || mClosed) {
            return
        }
        mFailureNotified = true
        mHandler.removeCallbacks(mConnectTimeout)
        mHandler.removeCallbacks(mSetupTimeout)
        closeGatt()
        mListener.onWujieConnectFailed(this, error)
    }

    private fun notifyDisconnected() {
        if (mFailureNotified) {
            return
        }
        mFailureNotified = true
        mListener.onWujieDisconnected(this)
    }

    private fun closeGatt() {
        val gatt = mGatt
        mGatt = null
        if (gatt != null) {
            try {
                gatt.close()
            } catch (e: SecurityException) {
                Timber.w(e, "close() denied")
            }
        }
    }

    companion object {
        @JvmField val SERVICE_UUID: UUID =
                uuid("12345678-1234-5678-1234-56789abcdef0")
        @JvmField val NOTIFY_UUID: UUID =
                uuid("12345678-1234-5678-1234-56789abcdef1")
        @JvmField val WRITE_A_UUID: UUID =
                uuid("12345678-1234-5678-1234-56789abcdef2")
        @JvmField val WRITE_B_UUID: UUID =
                uuid("12345678-1234-5678-1234-56789abcdef3")
        @JvmField val BATTERY_UUID: UUID =
                uuid("12345678-1234-5678-1234-56789abcdef4")
        @JvmField val SPEC_UUID: UUID =
                uuid("12345678-1234-5678-1234-56789abcdef5")
        @JvmField val AUX_UUID: UUID =
                uuid("12345678-1234-5678-1234-56789abcdef6")
        @JvmField val VERSION_UUID: UUID =
                uuid("12345678-1234-5678-1234-56789abcdef7")
        @JvmField val EXTRA_UUID: UUID =
                uuid("12345678-1234-5678-1234-56789abcdefa")

        /** Client Characteristic Configuration descriptor (0x2902). */
        private val CCCD_UUID: UUID =
                uuid("00002902-0000-1000-8000-00805f9b34fb")

        /** A 30-cell write needs 30 value bytes plus the 3-byte ATT header. */
        private val MIN_MTU: Int = WujieBrailleFrameCodec.CELL_COUNT + 3
        private val REQUESTED_MTU = 500
        private val CONNECT_TIMEOUT_MS = 15000L
        private val SETUP_TIMEOUT_MS = 4000L

        private val KEY_NAME_NEXT = "next"
        private val KEY_NAME_PREVIOUS = "prev"

        const val ERROR_CONNECT = -1
        const val ERROR_PERMISSION = -2
        const val ERROR_SERVICE = -3
        const val ERROR_MTU = -4
        const val ERROR_NO_WRITE = -5

        private fun findCharacteristic(
                gatt: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
            val service = gatt.getService(SERVICE_UUID)
            if (service != null) {
                val characteristic = service.getCharacteristic(uuid)
                if (characteristic != null) {
                    return characteristic
                }
            }
            for (other in gatt.getServices()) {
                val characteristic = other.getCharacteristic(uuid)
                if (characteristic != null) {
                    return characteristic
                }
            }
            return null
        }

        private fun supportsNotify(
                characteristic: BluetoothGattCharacteristic): Boolean {
            val properties = characteristic.getProperties()
            return (properties
                    and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                    || (properties
                            and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        }

        private fun isWritable(
                characteristic: BluetoothGattCharacteristic): Boolean {
            val properties = characteristic.getProperties()
            return (properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                    || (properties
                            and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
        }

        private fun isWritableWithoutResponse(
                characteristic: BluetoothGattCharacteristic): Boolean {
            val properties = characteristic.getProperties()
            return (properties
                    and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    && (properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
        }

        private fun rankForWrite(uuid: UUID): Int {
            if (WRITE_A_UUID.equals(uuid)) {
                return 0
            }
            if (WRITE_B_UUID.equals(uuid)) {
                return 1
            }
            if (AUX_UUID.equals(uuid)) {
                return 2
            }
            if (EXTRA_UUID.equals(uuid)) {
                return 3
            }
            return 4
        }

        private fun describe(
                characteristics: List<BluetoothGattCharacteristic>): String {
            if (characteristics.isEmpty()) {
                return "(none)"
            }
            val builder = StringBuilder()
            for (characteristic in characteristics) {
                if (builder.length > 0) {
                    builder.append(", ")
                }
                builder.append(shortUuid(characteristic.getUuid()))
            }
            return builder.toString()
        }

        private fun shortUuid(uuid: UUID): String {
            val value = uuid.toString().lowercase(Locale.US)
            return if (value.startsWith(
                            "12345678-1234-5678-1234-56789abcdef"))
                    "def" + value.substring(value.length - 1)
                    else value
        }

        private fun toHex(value: ByteArray?): String {
            if (value == null) {
                return "null"
            }
            val builder = StringBuilder(value.size * 3)
            for (b in value) {
                if (builder.length > 0) {
                    builder.append(' ')
                }
                builder.append(String.format(Locale.US, "%02X",
                        b.toInt() and 0xFF))
            }
            return builder.toString()
        }

        private fun uuid(value: String): UUID {
            return UUID.fromString(value)
        }
    }
}
