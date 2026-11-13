/*
 * Copyright (C) 2026 BrailleBack contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.googlecode.eyesfree.braille.service.display;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties;
import com.googlecode.eyesfree.braille.display.UsbDeviceEntry;
import com.googlecode.eyesfree.utils.LogUtils;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

/**
 * Wired USB connection path (P1).
 *
 * <p>Unlike the RFCOMM path, brltty's Android USB backend in
 * {@code usb_android.c} owns the whole I/O: it enumerates devices through
 * {@link UsbHelper} and performs control/bulk transfers over an
 * {@code UsbDeviceConnection}. The Java layer therefore only has to
 * <ol>
 *   <li>find a plugged device whose VID/PID is in the native USB driver table,
 *   <li>make sure {@link UsbManager} permission has been granted,
 *   <li>start {@link DriverThread} with the {@code usb:...} parameter, and
 *   <li>keep waking the driver so key presses are polled (there is no Java-side
 *       input callback on this path, unlike HID / BLE).
 * </ol>
 *
 * <p>Mirrors the existing {@link HidDisplayConnection}: a {@link NoOpOutputStream}
 * is passed to {@link DriverThread} because the native backend writes directly.
 */
public class UsbDisplayConnection implements DriverThread.OnInitListener {

    private static final String LOG_TAG = "UsbDisplayConnection";
    private static final String ACTION_USB_PERMISSION =
            "com.googlecode.eyesfree.brailleback.USB_PERMISSION";

    /** brltty has no Java-side input callback on USB, so poll the driver. */
    private static final long INPUT_POLL_MILLIS = 50L;
    /** Fallback in case the permission broadcast is not delivered. */
    private static final long PERMISSION_POLL_MILLIS = 500L;
    private static final int PERMISSION_POLL_MAX = 60;
    /** Let the failed driver finish its native teardown before retrying. */
    private static final long DRIVER_FALLBACK_DELAY_MILLIS = 300L;

    /** Callbacks are always delivered on the main thread. */
    public interface Listener {
        void onUsbConnected(UsbDisplayConnection source,
                BrailleDisplayProperties properties);

        void onUsbDisconnected(UsbDisplayConnection source);

        void onUsbConnectFailed(UsbDisplayConnection source, String reason);
    }

    private final Context mContext;
    private final File mTablesDir;
    private final Listener mListener;
    private final DriverThread.OnInputEventListener mInputEventListener;
    private final UsbManager mUsbManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private volatile DriverThread mDriverThread;
    private volatile boolean mDisconnecting;
    private volatile boolean mPermissionSettled;
    private volatile boolean mFailed;
    private UsbDevice mDevice;
    private String mDriverCode;
    private List<String> mDriverCandidates;
    private int mDriverIndex;
    private int mPermissionPollCount;
    private BroadcastReceiver mPermissionReceiver;

    private final Runnable mInputPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (mDisconnecting) {
                return;
            }
            DriverThread dt = mDriverThread;
            if (dt != null) {
                dt.wakeReadOperation();
                mHandler.postDelayed(this, INPUT_POLL_MILLIS);
            }
        }
    };

    private final Runnable mPermissionPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (mDisconnecting || mPermissionSettled || mFailed || mDevice == null) {
                return;
            }
            if (mUsbManager.hasPermission(mDevice)) {
                LogUtils.i(this, "USB permission detected by fallback poll");
                onPermissionGranted();
                return;
            }
            if (++mPermissionPollCount >= PERMISSION_POLL_MAX) {
                fail("USB permission was not granted (timed out)");
                return;
            }
            mHandler.postDelayed(this, PERMISSION_POLL_MILLIS);
        }
    };

    private final BroadcastReceiver mPermissionBroadcastReceiver =
            new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) {
                return;
            }
            boolean granted = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED, false);
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            LogUtils.i(this, "USB permission broadcast: granted=" + granted);
            // hasPermission() is authoritative: some OEMs deliver the
            // broadcast without the system's extras.
            if (!granted && mDevice != null
                    && !mUsbManager.hasPermission(mDevice)) {
                fail("USB permission denied by the user");
                return;
            }
            if (device != null && mDevice != null
                    && device.getDeviceId() != mDevice.getDeviceId()) {
                LogUtils.w(this, "Ignoring permission for a different USB device");
                return;
            }
            onPermissionGranted();
        }
    };

    public UsbDisplayConnection(Context context, File tablesDir,
            Listener listener,
            DriverThread.OnInputEventListener inputEventListener) {
        mContext = context.getApplicationContext();
        mTablesDir = tablesDir;
        mListener = listener;
        mInputEventListener = inputEventListener;
        mUsbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
    }

    /** Starts the wired connection attempt. Safe to call once per instance. */
    public void connect() {
        if (mDisconnecting) {
            return;
        }
        if (mUsbManager == null) {
            fail("USB host is not available on this device");
            return;
        }

        Map<String, UsbDevice> plugged = mUsbManager.getDeviceList();
        LogUtils.i(this, "USB devices currently attached: " + plugged.size());
        for (UsbDevice device : plugged.values()) {
            LogUtils.i(this, "  device id=" + device.getDeviceId()
                    + " vid=" + device.getVendorId()
                    + " pid=" + device.getProductId()
                    + " name=" + device.getDeviceName());
        }

        List<UsbDeviceEntry> table = BrlttyWrapper.getUsbDriverTable();
        LogUtils.i(this, "native USB driver table entries: " + table.size());
        if (table.isEmpty()) {
            fail("native USB driver table is empty (stale libbrlttywrap.so?)");
            return;
        }

        UsbDevice selected = null;
        List<String> candidates = null;
        for (UsbDevice device : plugged.values()) {
            List<String> drivers = UsbBrailleDevice.candidateDrivers(
                    table, device.getVendorId(), device.getProductId());
            LogUtils.i(this, "  driver candidates for vid="
                    + device.getVendorId() + " pid=" + device.getProductId()
                    + ": " + drivers);
            if (!drivers.isEmpty()) {
                selected = device;
                candidates = drivers;
                break;
            }
        }
        if (selected == null || candidates == null) {
            fail("No attached USB device matches the brltty driver table");
            return;
        }

        mDevice = selected;
        mDriverCandidates = candidates;
        mDriverIndex = 0;
        mDriverCode = candidates.get(0);
        if (candidates.size() > 1) {
            LogUtils.w(this, "Multiple drivers match; trying " + mDriverCode
                    + " first. Fallback order: " + candidates);
        }

        if (!mUsbManager.hasPermission(mDevice)) {
            requestPermission();
            return;
        }
        startDriver();
    }

    private void requestPermission() {
        LogUtils.i(this, "Requesting USB permission for device id="
                + mDevice.getDeviceId());
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        mPermissionReceiver = mPermissionBroadcastReceiver;
        if (Build.VERSION.SDK_INT >= 33) {
            mContext.registerReceiver(mPermissionReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            mContext.registerReceiver(mPermissionReceiver, filter);
        }

        Intent intent = new Intent(ACTION_USB_PERMISSION)
                .setPackage(mContext.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The system fills EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED into
            // this PendingIntent when the dialog is answered. An immutable
            // PendingIntent silently drops that fill-in, so the receiver sees
            // granted=false even after the user allowed the device.
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        PendingIntent permissionIntent = PendingIntent.getBroadcast(
                mContext, 0, intent, flags);
        mUsbManager.requestPermission(mDevice, permissionIntent);
        // The broadcast is the primary signal; the poll protects against
        // OEMs that swallow it (and against a screen reader missing the dialog).
        mHandler.postDelayed(mPermissionPollRunnable, PERMISSION_POLL_MILLIS);
    }

    private void onPermissionGranted() {
        if (mPermissionSettled || mDisconnecting || mFailed) {
            return;
        }
        mPermissionSettled = true;
        mHandler.removeCallbacks(mPermissionPollRunnable);
        unregisterPermissionReceiver();
        startDriver();
    }

    private void startDriver() {
        if (mDisconnecting || mFailed || mDevice == null) {
            return;
        }
        String serial = safeSerialNumber(mDevice);
        String parameter = UsbBrailleDevice.buildParameter(
                serial, mDevice.getVendorId(), mDevice.getProductId());
        LogUtils.i(this, "Starting brltty USB driver=" + mDriverCode
                + " parameter=" + parameter);
        try {
            mDriverThread = new DriverThread(new NoOpOutputStream(),
                    DeviceFinder.DeviceInfo.forUsb(mDriverCode, parameter),
                    mContext.getResources(), mTablesDir, mContext,
                    this, mInputEventListener);
        } catch (Throwable t) {
            LogUtils.e(this, t, "Could not create the USB driver thread");
            fail("Could not start the brltty USB driver: " + t);
        }
    }

    private String safeSerialNumber(UsbDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return device.getSerialNumber();
            } catch (Throwable t) {
                LogUtils.w(this, t, "USB serial number unavailable; matching by VID/PID");
            }
        }
        // Empty is safe: usbVerifyString() skips verification for an empty value.
        return "";
    }

    @Override
    public void onInit(final BrailleDisplayProperties properties) {
        // Called on the DriverThread's handler thread.
        if (mDisconnecting || mFailed) {
            return;
        }
        if (properties == null) {
            LogUtils.w(this, "brltty USB driver failed to initialize");
            final String failedDriver = mDriverCode;
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    tryNextDriver(failedDriver);
                }
            }, DRIVER_FALLBACK_DELAY_MILLIS);
            return;
        }
        LogUtils.i(this, "USB braille display ready, text cells="
                + properties.getNumTextCells());
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mDisconnecting || mFailed) {
                    return;
                }
                mListener.onUsbConnected(UsbDisplayConnection.this, properties);
                mHandler.post(mInputPollRunnable);
            }
        });
    }

    /** Sends a braille window to the display. */
    public void writeWindow(byte[] patterns) {
        DriverThread dt = mDriverThread;
        if (dt != null) {
            dt.writeWindow(patterns);
        }
    }

    /** True while this connection is live and still owns a driver thread. */
    public boolean isDriverActive() {
        return mDriverThread != null && !mDisconnecting && !mFailed;
    }

    /** Tears the connection down. The listener is notified after native stop. */
    public void disconnect() {
        if (mDisconnecting) {
            return;
        }
        mDisconnecting = true;
        LogUtils.i(this, "Disconnecting USB braille display");
        mHandler.removeCallbacks(mInputPollRunnable);
        mHandler.removeCallbacks(mPermissionPollRunnable);
        unregisterPermissionReceiver();

        final DriverThread dt = mDriverThread;
        mDriverThread = null;
        if (dt == null) {
            notifyDisconnected();
            return;
        }
        // DriverThread.stop() joins up to 1s; never block the main thread.
        new Thread(new Runnable() {
            @Override
            public void run() {
                dt.stop();
                notifyDisconnected();
            }
        }, "UsbDriverStop").start();
    }

    private void notifyDisconnected() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onUsbDisconnected(UsbDisplayConnection.this);
            }
        });
    }

    private void fail(final String reason) {
        if (mFailed || mDisconnecting) {
            return;
        }
        mFailed = true;
        LogUtils.w(this, "USB connection failed: " + reason);
        mHandler.removeCallbacks(mInputPollRunnable);
        mHandler.removeCallbacks(mPermissionPollRunnable);
        unregisterPermissionReceiver();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mListener.onUsbConnectFailed(UsbDisplayConnection.this, reason);
            }
        });
    }

    /**
     * Some USB chips are shared by several drivers (CP2102 is used by both
     * BrailleMemo and Seika). Try the next candidate when the current driver
     * cannot initialize this particular device.
     */
    private void tryNextDriver(String failedDriver) {
        if (mDisconnecting || mFailed) {
            return;
        }
        List<String> candidates = mDriverCandidates;
        if (candidates == null || mDriverIndex + 1 >= candidates.size()) {
            fail("brltty USB driver failed to initialize (" + failedDriver + ")");
            return;
        }
        mDriverThread = null;
        mDriverIndex += 1;
        mDriverCode = candidates.get(mDriverIndex);
        LogUtils.i(this, "Trying fallback driver " + mDriverCode
                + " after " + failedDriver + " failed");
        startDriver();
    }

    private void unregisterPermissionReceiver() {
        BroadcastReceiver receiver = mPermissionReceiver;
        mPermissionReceiver = null;
        if (receiver != null) {
            try {
                mContext.unregisterReceiver(receiver);
            } catch (IllegalArgumentException e) {
                LogUtils.d(this, "Permission receiver was already unregistered");
            }
        }
    }

    /** The native USB backend does its own writes; this stream is never used. */
    private static class NoOpOutputStream extends OutputStream {
        @Override
        public void write(int b) throws IOException {
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
        }
    }
}
