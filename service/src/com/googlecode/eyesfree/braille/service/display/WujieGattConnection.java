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

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties;
import com.googlecode.eyesfree.braille.display.BrailleInputEvent;
import com.googlecode.eyesfree.braille.display.BrailleKeyBinding;
import com.googlecode.eyesfree.braille.service.R;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import timber.log.Timber;

/**
 * Talks to the Wujie / Orbit 30-cell display over its private BLE GATT
 * service.
 *
 * <p>The firmware exposes one custom service whose value characteristics use
 * the short UUIDs {@code …def1} through {@code …defa}. A braille frame is 30
 * raw cell bytes (or 0x02 followed by 30 bytes) written to one of the writable
 * characteristics. This class discovers the actual handles and properties at
 * runtime instead of hard-coding a handle number, then serialises every GATT
 * operation because the Android stack allows only one at a time.
 */
@SuppressLint("MissingPermission")
public class WujieGattConnection {
    /** Callbacks to {@link DisplayService}. */
    public interface Listener {
        void onWujieConnected(
                WujieGattConnection source, BrailleDisplayProperties properties);
        void onWujieInputEvent(
                WujieGattConnection source, BrailleInputEvent event);
        void onWujieDisconnected(WujieGattConnection source);
        void onWujieConnectFailed(WujieGattConnection source, int error);
    }

    public static final UUID SERVICE_UUID = uuid("12345678-1234-5678-1234-56789abcdef0");
    public static final UUID NOTIFY_UUID = uuid("12345678-1234-5678-1234-56789abcdef1");
    public static final UUID WRITE_A_UUID = uuid("12345678-1234-5678-1234-56789abcdef2");
    public static final UUID WRITE_B_UUID = uuid("12345678-1234-5678-1234-56789abcdef3");
    public static final UUID BATTERY_UUID = uuid("12345678-1234-5678-1234-56789abcdef4");
    public static final UUID SPEC_UUID = uuid("12345678-1234-5678-1234-56789abcdef5");
    public static final UUID AUX_UUID = uuid("12345678-1234-5678-1234-56789abcdef6");
    public static final UUID VERSION_UUID = uuid("12345678-1234-5678-1234-56789abcdef7");
    public static final UUID EXTRA_UUID = uuid("12345678-1234-5678-1234-56789abcdefa");

    /** Client Characteristic Configuration descriptor (0x2902). */
    private static final UUID CCCD_UUID =
            uuid("00002902-0000-1000-8000-00805f9b34fb");

    /** A 30-cell write needs 30 value bytes plus the 3-byte ATT header. */
    private static final int MIN_MTU = WujieBrailleFrameCodec.CELL_COUNT + 3;
    private static final int REQUESTED_MTU = 500;
    private static final long CONNECT_TIMEOUT_MS = 15000;
    private static final long SETUP_TIMEOUT_MS = 4000;

    private static final String KEY_NAME_NEXT = "next";
    private static final String KEY_NAME_PREVIOUS = "prev";

    public static final int ERROR_CONNECT = -1;
    public static final int ERROR_PERMISSION = -2;
    public static final int ERROR_SERVICE = -3;
    public static final int ERROR_MTU = -4;
    public static final int ERROR_NO_WRITE = -5;

    private final Context mContext;
    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private final Object mLock = new Object();
    private final ArrayDeque<Runnable> mOps = new ArrayDeque<>();
    private final ArrayDeque<byte[]> mFrames = new ArrayDeque<>(1);
    private final List<BluetoothGattCharacteristic> mWriteCandidates =
            new ArrayList<>();
    private final List<BluetoothGattCharacteristic> mNotifyChars =
            new ArrayList<>();

    private volatile BluetoothGatt mGatt;
    private volatile boolean mClosed;
    private boolean mOpInFlight;
    private boolean mFrameOpQueued;
    private int mCandidateIndex;
    private byte[] mInFlightFrame;
    private int mMtu = 23;
    private boolean mServicesDiscovered;
    private boolean mConnectedNotified;
    private boolean mFailureNotified;
    private boolean mSetupComplete;

    private final Runnable mConnectTimeout = new Runnable() {
        @Override
        public void run() {
            Timber.w("Wujie GATT connect timed out");
            fail(ERROR_CONNECT);
        }
    };

    private final Runnable mSetupTimeout = new Runnable() {
        @Override
        public void run() {
            if (!mSetupComplete) {
                Timber.w("Wujie GATT setup incomplete after %d ms; "
                        + "continuing with the display", SETUP_TIMEOUT_MS);
                finishSetup();
            }
        }
    };

    private final BluetoothGattCallback mCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status,
                int newState) {
            if (mClosed || gatt != mGatt) {
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED
                    && status == BluetoothGatt.GATT_SUCCESS) {
                Timber.i("Wujie GATT connected; requesting MTU %d",
                        REQUESTED_MTU);
                mHandler.removeCallbacks(mConnectTimeout);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    gatt.requestMtu(REQUESTED_MTU);
                }
                if (!gatt.discoverServices()) {
                    Timber.e("Wujie GATT discoverServices() failed to start");
                    fail(ERROR_SERVICE);
                }
                mHandler.postDelayed(mSetupTimeout, SETUP_TIMEOUT_MS);
                return;
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Timber.i("Wujie GATT disconnected (status=%d)", status);
                if (!mConnectedNotified) {
                    fail(status == BluetoothGatt.GATT_SUCCESS
                            ? ERROR_CONNECT : ERROR_CONNECT);
                } else {
                    notifyDisconnected();
                }
                closeGatt();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (mClosed || gatt != mGatt) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.e("Wujie GATT service discovery failed (status=%d)",
                        status);
                fail(ERROR_SERVICE);
                return;
            }
            mServicesDiscovered = true;
            inspectServices(gatt);
            if (mClosed || mFailureNotified) {
                return;
            }
            startSetup(gatt);
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (mClosed || gatt != mGatt) {
                return;
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mMtu = mtu;
                Timber.i("Wujie GATT MTU is %d", mtu);
            } else {
                Timber.w("Wujie GATT MTU request failed (status=%d), MTU=%d",
                        status, mtu);
            }
            maybeEstablishDisplay();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                BluetoothGattDescriptor descriptor, int status) {
            if (mClosed || gatt != mGatt) {
                return;
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.w("Wujie GATT descriptor write failed for %s "
                        + "(status=%d)", descriptor.getUuid(), status);
            }
            finishOp();
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic, int status) {
            if (mClosed || gatt != mGatt) {
                return;
            }
            byte[] value = characteristic.getValue();
            if (status == BluetoothGatt.GATT_SUCCESS && value != null) {
                Timber.i("Wujie GATT read %s = %s",
                        shortUuid(characteristic.getUuid()), toHex(value));
                if (SPEC_UUID.equals(characteristic.getUuid())
                        && value.length >= 2) {
                    int rows = value[0] & 0xFF;
                    int cells = value[1] & 0xFF;
                    Timber.i("Wujie display reports %d row(s) x %d cell(s)",
                            rows, cells);
                }
            } else {
                Timber.w("Wujie GATT read %s failed (status=%d)",
                        shortUuid(characteristic.getUuid()), status);
            }
            finishOp();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic, int status) {
            if (mClosed || gatt != mGatt) {
                return;
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                finishOp();
                return;
            }
            Timber.w("Wujie GATT write to %s failed (status=%d)",
                    shortUuid(characteristic.getUuid()), status);
            retryWithNextCandidate();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic) {
            if (mClosed || gatt != mGatt) {
                return;
            }
            byte[] value = characteristic.getValue();
            Timber.i("Wujie GATT notify %s = %s",
                    shortUuid(characteristic.getUuid()), toHex(value));
            if (!NOTIFY_UUID.equals(characteristic.getUuid())) {
                return;
            }
            int key = WujieKeyEventCodec.decode(value);
            if (key == WujieKeyEventCodec.COMMAND_NONE) {
                Timber.w("Ignoring unknown Wujie key payload on def1");
                return;
            }
            int command = key == WujieKeyEventCodec.COMMAND_NEXT
                    ? BrailleInputEvent.CMD_NAV_PAN_RIGHT
                    : BrailleInputEvent.CMD_NAV_PAN_LEFT;
            BrailleInputEvent event = new BrailleInputEvent(
                    command, 0, SystemClock.uptimeMillis());
            Timber.i("Wujie key %s -> %s",
                    key == WujieKeyEventCodec.COMMAND_NEXT
                            ? KEY_NAME_NEXT : KEY_NAME_PREVIOUS,
                    BrailleInputEvent.commandToString(command));
            mListener.onWujieInputEvent(WujieGattConnection.this, event);
        }
    };

    public WujieGattConnection(Context context, Listener listener) {
        mContext = context.getApplicationContext();
        mListener = listener;
    }

    /** Connects as a BLE GATT client. Must not be called twice. */
    public void connect(String deviceAddress) {
        if (deviceAddress == null) {
            fail(ERROR_CONNECT);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && mContext.checkSelfPermission(
                        Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            Timber.e("BLUETOOTH_CONNECT permission not granted");
            fail(ERROR_PERMISSION);
            return;
        }
        BluetoothDevice device;
        try {
            android.bluetooth.BluetoothAdapter adapter =
                    android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                fail(ERROR_CONNECT);
                return;
            }
            device = adapter.getRemoteDevice(deviceAddress);
        } catch (IllegalArgumentException | SecurityException e) {
            Timber.e(e, "Invalid Wujie device address %s", deviceAddress);
            fail(ERROR_CONNECT);
            return;
        }
        Timber.i("Connecting to Wujie display %s over LE GATT", deviceAddress);
        mGatt = device.connectGatt(mContext, false, mCallback,
                BluetoothDevice.TRANSPORT_LE);
        if (mGatt == null) {
            Timber.e("connectGatt returned null for %s", deviceAddress);
            fail(ERROR_CONNECT);
            return;
        }
        mHandler.postDelayed(mConnectTimeout, CONNECT_TIMEOUT_MS);
    }

    /** Queues one braille window; only the newest pending frame is kept. */
    public void writeWindow(byte[] patterns) {
        if (mClosed) {
            return;
        }
        byte[] frame = WujieBrailleFrameCodec.encode(
                patterns, WujieBrailleFrameCodec.CELL_COUNT, false);
        synchronized (mLock) {
            mFrames.clear();
            mFrames.add(frame);
            queueFrameOpLocked();
        }
    }

    /** Tears the connection down immediately. */
    public void disconnect() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        mHandler.removeCallbacks(mConnectTimeout);
        mHandler.removeCallbacks(mSetupTimeout);
        BluetoothGatt gatt = mGatt;
        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (SecurityException e) {
                Timber.w(e, "disconnect() denied");
            }
            closeGatt();
        }
        synchronized (mLock) {
            mOps.clear();
            mFrames.clear();
            mOpInFlight = false;
            mFrameOpQueued = false;
        }
        notifyDisconnected();
    }

    private void inspectServices(BluetoothGatt gatt) {
        List<BluetoothGattCharacteristic> all =
                new ArrayList<BluetoothGattCharacteristic>();
        BluetoothGattService target = gatt.getService(SERVICE_UUID);
        if (target == null) {
            Timber.e("Wujie service %s not found", SERVICE_UUID);
            fail(ERROR_SERVICE);
            return;
        }
        all.addAll(target.getCharacteristics());
        Timber.i("Wujie GATT characteristics (%d):", all.size());
        for (BluetoothGattCharacteristic characteristic : all) {
            Timber.i("  %s props=0x%02x permissions=0x%02x",
                    shortUuid(characteristic.getUuid()),
                    characteristic.getProperties(),
                    characteristic.getPermissions());
        }

        mWriteCandidates.clear();
        mNotifyChars.clear();
        BluetoothGattCharacteristic writeA = null;
        BluetoothGattCharacteristic writeB = null;
        BluetoothGattCharacteristic aux = null;
        BluetoothGattCharacteristic extra = null;
        BluetoothGattCharacteristic notify = null;
        BluetoothGattCharacteristic version = null;
        BluetoothGattCharacteristic battery = null;
        BluetoothGattCharacteristic spec = null;
        for (BluetoothGattCharacteristic characteristic : all) {
            UUID uuid = characteristic.getUuid();
            if (NOTIFY_UUID.equals(uuid)) {
                notify = characteristic;
            } else if (WRITE_A_UUID.equals(uuid)) {
                writeA = characteristic;
            } else if (WRITE_B_UUID.equals(uuid)) {
                writeB = characteristic;
            } else if (AUX_UUID.equals(uuid)) {
                aux = characteristic;
            } else if (EXTRA_UUID.equals(uuid)) {
                extra = characteristic;
            } else if (VERSION_UUID.equals(uuid)) {
                version = characteristic;
            } else if (BATTERY_UUID.equals(uuid)) {
                battery = characteristic;
            } else if (SPEC_UUID.equals(uuid)) {
                spec = characteristic;
            }
            if (isWritable(characteristic)) {
                // Add the known braille channels first, then any unknown
                // writable characteristic as a last-resort fallback.
                if (!BATTERY_UUID.equals(uuid) && !SPEC_UUID.equals(uuid)
                        && !VERSION_UUID.equals(uuid)) {
                    mWriteCandidates.add(characteristic);
                }
            }
        }
        Collections.sort(mWriteCandidates,
                new Comparator<BluetoothGattCharacteristic>() {
                    @Override
                    public int compare(BluetoothGattCharacteristic a,
                            BluetoothGattCharacteristic b) {
                        return Integer.compare(rankForWrite(a.getUuid()),
                                rankForWrite(b.getUuid()));
                    }
                });
        if (notify != null) {
            mNotifyChars.add(notify);
        }
        if (version != null && supportsNotify(version)) {
            mNotifyChars.add(version);
        }
        if (battery != null && supportsNotify(battery)) {
            mNotifyChars.add(battery);
        }
        if (aux != null && supportsNotify(aux)) {
            mNotifyChars.add(aux);
        }
        Timber.i("Wujie write candidates: %s", describe(mWriteCandidates));
        Timber.i("Wujie notify characteristics: %s", describe(mNotifyChars));

        if (mWriteCandidates.isEmpty()) {
            Timber.e("Wujie display exposes no writable characteristic");
            fail(ERROR_NO_WRITE);
            return;
        }
        mCandidateIndex = 0;
        // Reference the known handles so the compiler does not warn about
        // unused locals if the layout changes.
        if (writeA == null) {
            Timber.w("Wujie write channel def2 not present");
        }
        if (writeB == null) {
            Timber.w("Wujie write channel def3 not present");
        }
        if (extra != null) {
            Timber.i("Wujie extra characteristic defa present");
        }
        if (spec != null) {
            Timber.i("Wujie spec characteristic found");
        }
    }

    private void startSetup(BluetoothGatt gatt) {
        // Subscribe to the device-to-phone channel first, then read the
        // identity characteristics. Each operation is serialised by mOps.
        for (final BluetoothGattCharacteristic characteristic : mNotifyChars) {
            enqueue(new Runnable() {
                @Override
                public void run() {
                    enableNotifications(mGatt, characteristic);
                }
            });
        }
        enqueueRead(SPEC_UUID);
        enqueueRead(VERSION_UUID);
        enqueueRead(BATTERY_UUID);
        maybeEstablishDisplay();
    }

    private void enqueueRead(final UUID uuid) {
        enqueue(new Runnable() {
            @Override
            public void run() {
                BluetoothGatt gatt = mGatt;
                BluetoothGattCharacteristic characteristic = gatt == null
                        ? null : findCharacteristic(gatt, uuid);
                if (characteristic == null
                        || !gatt.readCharacteristic(characteristic)) {
                    finishOp();
                }
            }
        });
    }

    private void enableNotifications(BluetoothGatt gatt,
            BluetoothGattCharacteristic characteristic) {
        if (gatt == null || characteristic == null) {
            finishOp();
            return;
        }
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Timber.w("setCharacteristicNotification failed for %s",
                    shortUuid(characteristic.getUuid()));
            finishOp();
            return;
        }
        BluetoothGattDescriptor descriptor =
                characteristic.getDescriptor(CCCD_UUID);
        if (descriptor == null) {
            finishOp();
            return;
        }
        byte[] cccdValue =
                (characteristic.getProperties()
                        & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (gatt.writeDescriptor(descriptor, cccdValue)
                    != BluetoothGatt.GATT_SUCCESS) {
                finishOp();
            }
        } else {
            descriptor.setValue(cccdValue);
            if (!gatt.writeDescriptor(descriptor)) {
                finishOp();
            }
        }
    }

    /** Called once the GATT tree is usable; reports the display upstream. */
    private void maybeEstablishDisplay() {
        if (mClosed || mConnectedNotified || !mServicesDiscovered) {
            return;
        }
        // Wait for a usable MTU. If the stack keeps the default 23, the write
        // will fail and the setup timeout reports the reason instead of
        // hanging forever.
        if (mMtu < MIN_MTU) {
            return;
        }
        mConnectedNotified = true;
        mHandler.removeCallbacks(mConnectTimeout);
        BrailleDisplayProperties properties = createDisplayProperties();
        Timber.i("Wujie display ready (MTU=%d)", mMtu);
        // The firmware blanks the display when no frame arrives within five
        // seconds, so send a blank frame immediately.
        ensureInitialFrame();
        mListener.onWujieConnected(this, properties);
    }

    private BrailleDisplayProperties createDisplayProperties() {
        BrailleKeyBinding[] keyBindings = new BrailleKeyBinding[] {
                new BrailleKeyBinding(BrailleInputEvent.CMD_NAV_PAN_RIGHT,
                        new String[] { KEY_NAME_NEXT }, false),
                new BrailleKeyBinding(BrailleInputEvent.CMD_NAV_PAN_LEFT,
                        new String[] { KEY_NAME_PREVIOUS }, false)
        };
        Map<String, String> friendlyKeyNames = new HashMap<>();
        friendlyKeyNames.put(KEY_NAME_NEXT,
                mContext.getString(R.string.key_wujie_page_next));
        friendlyKeyNames.put(KEY_NAME_PREVIOUS,
                mContext.getString(R.string.key_wujie_page_previous));
        return new BrailleDisplayProperties(
                WujieBrailleFrameCodec.CELL_COUNT, 0,
                keyBindings, friendlyKeyNames);
    }

    /** Sends one blank frame unless the upper layer already queued output. */
    private void ensureInitialFrame() {
        synchronized (mLock) {
            if (mFrames.isEmpty()) {
                mFrames.add(WujieBrailleFrameCodec.encode(
                        null, WujieBrailleFrameCodec.CELL_COUNT, false));
            }
            queueFrameOpLocked();
        }
    }

    /** Queues the single pending frame operation. Caller holds mLock. */
    private void queueFrameOpLocked() {
        if (mFrameOpQueued || !mServicesDiscovered) {
            return;
        }
        mFrameOpQueued = true;
        mOps.add(new Runnable() {
            @Override
            public void run() {
                writeNextFrame();
            }
        });
        runNextOpLocked();
    }

    private void finishSetup() {
        if (mSetupComplete) {
            return;
        }
        mSetupComplete = true;
        if (!mConnectedNotified && !mClosed) {
            if (mMtu < MIN_MTU) {
                Timber.e("Wujie display needs MTU >= %d but negotiated %d",
                        MIN_MTU, mMtu);
                fail(ERROR_MTU);
            } else {
                maybeEstablishDisplay();
            }
        }
    }

    private void writeNextFrame() {
        byte[] frame;
        synchronized (mLock) {
            frame = mFrames.poll();
            if (frame == null) {
                mFrameOpQueued = false;
                finishOpLocked();
                return;
            }
            mFrameOpQueued = false;
        }
        mInFlightFrame = frame;
        if (!writeWithCurrentCandidate(frame)) {
            retryWithNextCandidate();
        }
    }

    private boolean writeWithCurrentCandidate(byte[] frame) {
        BluetoothGatt gatt = mGatt;
        if (gatt == null || mWriteCandidates.isEmpty()) {
            return false;
        }
        if (mCandidateIndex >= mWriteCandidates.size()) {
            return false;
        }
        BluetoothGattCharacteristic characteristic =
                mWriteCandidates.get(mCandidateIndex);
        int writeType = isWritableWithoutResponse(characteristic)
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            int result = gatt.writeCharacteristic(
                    characteristic, frame, writeType);
            return result == BluetoothGatt.GATT_SUCCESS;
        }
        characteristic.setWriteType(writeType);
        characteristic.setValue(frame);
        return gatt.writeCharacteristic(characteristic);
    }

    private void retryWithNextCandidate() {
        if (mCandidateIndex + 1 < mWriteCandidates.size()) {
            mCandidateIndex++;
            byte[] frame = mInFlightFrame;
            Timber.i("Wujie retrying frame on %s",
                    shortUuid(mWriteCandidates.get(mCandidateIndex).getUuid()));
            if (frame != null && writeWithCurrentCandidate(frame)) {
                return;
            }
        }
        Timber.w("Wujie dropped a frame after exhausting write channels");
        mInFlightFrame = null;
        synchronized (mLock) {
            mFrameOpQueued = false;
            queueFrameOpLocked();
        }
        finishOp();
    }

    private void enqueue(Runnable op) {
        synchronized (mLock) {
            mOps.add(op);
            runNextOpLocked();
        }
    }

    private void runNextOpLocked() {
        if (mOpInFlight || mClosed) {
            return;
        }
        Runnable next = mOps.poll();
        if (next == null) {
            return;
        }
        mOpInFlight = true;
        next.run();
    }

    private void finishOp() {
        synchronized (mLock) {
            finishOpLocked();
        }
    }

    private void finishOpLocked() {
        mOpInFlight = false;
        runNextOpLocked();
    }

    private void fail(int error) {
        if (mFailureNotified || mClosed) {
            return;
        }
        mFailureNotified = true;
        mHandler.removeCallbacks(mConnectTimeout);
        mHandler.removeCallbacks(mSetupTimeout);
        closeGatt();
        mListener.onWujieConnectFailed(this, error);
    }

    private void notifyDisconnected() {
        if (mFailureNotified) {
            return;
        }
        mFailureNotified = true;
        mListener.onWujieDisconnected(this);
    }

    private void closeGatt() {
        BluetoothGatt gatt = mGatt;
        mGatt = null;
        if (gatt != null) {
            try {
                gatt.close();
            } catch (SecurityException e) {
                Timber.w(e, "close() denied");
            }
        }
    }

    private static BluetoothGattCharacteristic findCharacteristic(
            BluetoothGatt gatt, UUID uuid) {
        BluetoothGattService service = gatt.getService(SERVICE_UUID);
        if (service != null) {
            BluetoothGattCharacteristic characteristic =
                    service.getCharacteristic(uuid);
            if (characteristic != null) {
                return characteristic;
            }
        }
        for (BluetoothGattService other : gatt.getServices()) {
            BluetoothGattCharacteristic characteristic =
                    other.getCharacteristic(uuid);
            if (characteristic != null) {
                return characteristic;
            }
        }
        return null;
    }

    private static boolean supportsNotify(
            BluetoothGattCharacteristic characteristic) {
        int properties = characteristic.getProperties();
        return (properties & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                || (properties
                        & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;
    }

    private static boolean isWritable(
            BluetoothGattCharacteristic characteristic) {
        int properties = characteristic.getProperties();
        return (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                || (properties
                        & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)
                        != 0;
    }

    private static boolean isWritableWithoutResponse(
            BluetoothGattCharacteristic characteristic) {
        return (characteristic.getProperties()
                & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                && (characteristic.getProperties()
                        & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0;
    }

    private static int rankForWrite(UUID uuid) {
        if (WRITE_A_UUID.equals(uuid)) {
            return 0;
        }
        if (WRITE_B_UUID.equals(uuid)) {
            return 1;
        }
        if (AUX_UUID.equals(uuid)) {
            return 2;
        }
        if (EXTRA_UUID.equals(uuid)) {
            return 3;
        }
        return 4;
    }

    private static String describe(
            List<BluetoothGattCharacteristic> characteristics) {
        if (characteristics.isEmpty()) {
            return "(none)";
        }
        StringBuilder builder = new StringBuilder();
        for (BluetoothGattCharacteristic characteristic : characteristics) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(shortUuid(characteristic.getUuid()));
        }
        return builder.toString();
    }

    private static String shortUuid(UUID uuid) {
        String value = uuid.toString().toLowerCase(Locale.US);
        return value.startsWith("12345678-1234-5678-1234-56789abcdef")
                ? "def" + value.substring(value.length() - 1)
                : value;
    }

    private static String toHex(byte[] value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder(value.length * 3);
        for (byte b : value) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(String.format(Locale.US, "%02X", b & 0xFF));
        }
        return builder.toString();
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
