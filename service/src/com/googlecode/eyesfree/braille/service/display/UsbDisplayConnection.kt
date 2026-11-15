/*
 * Copyright (C) 2026 BrailleBack contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.googlecode.eyesfree.braille.service.display

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.googlecode.eyesfree.braille.display.UsbDeviceEntry
import com.googlecode.eyesfree.utils.LogUtils
import java.io.File
import java.io.IOException
import java.io.OutputStream

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
class UsbDisplayConnection : DriverThread.OnInitListener {

    companion object {
        private const val LOG_TAG = "UsbDisplayConnection"
        private const val ACTION_USB_PERMISSION =
            "com.googlecode.eyesfree.brailleback.USB_PERMISSION"

        /** brltty has no Java-side input callback on USB, so poll the driver. */
        private const val INPUT_POLL_MILLIS = 50L
        /** Fallback in case the permission broadcast is not delivered. */
        private const val PERMISSION_POLL_MILLIS = 500L
        private const val PERMISSION_POLL_MAX = 60
        /**
         * Poll interval used once the system prompt has already been shown
         * for a device. Asking again from a background service cannot show
         * a dialog, it only makes the reconnect loop re-show it forever.
         */
        private const val SILENT_PERMISSION_POLL_MILLIS = 2000L
        /** Let the failed driver finish its native teardown before retrying. */
        private const val DRIVER_FALLBACK_DELAY_MILLIS = 300L

        /**
         * Device name of the last device the system prompt was shown for.
         * The prompt is only shown again after the device is re-attached or
         * the user opens the app, which proves somebody can answer it.
         */
        @Volatile
        private var sPromptedDeviceName: String? = null

        /** Called when the user returns to the app or plugs the device in. */
        @JvmStatic
        fun allowPermissionPromptAgain() {
            sPromptedDeviceName = null
        }
    }

    /** Callbacks are always delivered on the main thread. */
    interface Listener {
        fun onUsbConnected(source: UsbDisplayConnection?,
                properties: BrailleDisplayProperties?)

        fun onUsbDisconnected(source: UsbDisplayConnection?)

        fun onUsbConnectFailed(source: UsbDisplayConnection?, reason: String?)
    }

    private val mContext: Context
    private val mTablesDir: File
    private val mListener: Listener
    private val mInputEventListener: DriverThread.OnInputEventListener
    private val mUsbManager: UsbManager?
    private val mHandler = Handler(Looper.getMainLooper())

    @Volatile private var mDriverThread: DriverThread? = null
    @Volatile private var mDisconnecting = false
    @Volatile private var mPermissionSettled = false
    @Volatile private var mFailed = false
    @Volatile private var mSilentPermissionWait = false
    private var mDevice: UsbDevice? = null
    private var mDriverCode: String? = null
    private var mDriverCandidates: List<String>? = null
    private var mDriverIndex = 0
    private var mPermissionPollCount = 0
    private var mPermissionReceiver: BroadcastReceiver? = null

    private val mInputPollRunnable = object : Runnable {
        override fun run() {
            if (mDisconnecting) {
                return
            }
            val dt = mDriverThread
            if (dt != null) {
                dt.wakeReadOperation()
                mHandler.postDelayed(this, INPUT_POLL_MILLIS)
            }
        }
    }

    private val mPermissionPollRunnable = object : Runnable {
        override fun run() {
            // 设备与管理器各取一次局部：属性中途可能被清空，非空断言会直接崩。
            val device = mDevice
            if (mDisconnecting || mPermissionSettled || mFailed || device == null) {
                return
            }
            val usbManager = mUsbManager ?: return
            if (usbManager.hasPermission(device)) {
                LogUtils.i(this, "USB permission detected by fallback poll")
                onPermissionGranted()
                return
            }
            if (mSilentPermissionWait) {
                // The prompt was already shown for this device. Ask again
                // only once the user has signalled that they are present.
                if (sPromptedDeviceName != device.getDeviceName()) {
                    LogUtils.i(this,
                            "App is in the foreground; asking for USB permission again")
                    mSilentPermissionWait = false
                    mPermissionPollCount = 0
                    requestPermission()
                } else {
                    mHandler.postDelayed(this, SILENT_PERMISSION_POLL_MILLIS)
                }
                return
            }
            if (++mPermissionPollCount >= PERMISSION_POLL_MAX) {
                fail("USB permission was not granted (timed out)")
                return
            }
            mHandler.postDelayed(this, PERMISSION_POLL_MILLIS)
        }
    }

    private val mPermissionBroadcastReceiver =
            object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!ACTION_USB_PERMISSION.equals(intent?.action)) {
                return
            }
            val granted = intent?.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED, false) ?: false
            val device = intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            LogUtils.i(this, "USB permission broadcast: granted=" + granted)
            // hasPermission() is authoritative: some OEMs deliver the
            // broadcast without the system's extras.
            val localDevice = mDevice
            val usbManager = mUsbManager
            if (!granted && localDevice != null && usbManager != null
                    && !usbManager.hasPermission(localDevice)) {
                fail("USB permission denied by the user")
                return
            }
            if (device != null && localDevice != null
                    && device.getDeviceId() != localDevice.getDeviceId()) {
                LogUtils.w(this, "Ignoring permission for a different USB device")
                return
            }
            onPermissionGranted()
        }
    }

    constructor(context: Context, tablesDir: File,
            listener: Listener,
            inputEventListener: DriverThread.OnInputEventListener) {
        mContext = context.getApplicationContext()
        mTablesDir = tablesDir
        mListener = listener
        mInputEventListener = inputEventListener
        mUsbManager = mContext.getSystemService(Context.USB_SERVICE) as UsbManager?
    }

    /** Starts the wired connection attempt. Safe to call once per instance. */
    fun connect() {
        if (mDisconnecting) {
            return
        }
        if (mUsbManager == null) {
            fail("USB host is not available on this device")
            return
        }

        val plugged = mUsbManager?.getDeviceList() ?: return
        LogUtils.i(this, "USB devices currently attached: " + plugged.size)
        for (device in plugged.values) {
            LogUtils.i(this, "  device id=" + device.getDeviceId()
                    + " vid=" + device.getVendorId()
                    + " pid=" + device.getProductId()
                    + " name=" + device.getDeviceName())
        }

        val table = BrlttyWrapper.getUsbDriverTable()
        LogUtils.i(this, "native USB driver table entries: " + table.size)
        if (table.isEmpty()) {
            fail("native USB driver table is empty (stale libbrlttywrap.so?)")
            return
        }

        var selected: UsbDevice? = null
        var candidates: List<String>? = null
        for (device in plugged.values) {
            val drivers = UsbBrailleDevice.candidateDrivers(
                    table, device.getVendorId(), device.getProductId())
            LogUtils.i(this, "  driver candidates for vid="
                    + device.getVendorId() + " pid=" + device.getProductId()
                    + ": " + drivers)
            if (!drivers.isEmpty()) {
                selected = device
                candidates = drivers
                break
            }
        }
        if (selected == null || candidates == null) {
            fail("No attached USB device matches the brltty driver table")
            return
        }

        // 选中项落局部，后面不再反复读属性（属性随时可能被 disconnect 清空）。
        val chosenDevice = selected
        val chosenCandidates = candidates
        mDevice = chosenDevice
        mDriverCandidates = chosenCandidates
        mDriverIndex = 0
        mDriverCode = chosenCandidates[0]
        if (chosenCandidates.size > 1) {
            LogUtils.w(this, "Multiple drivers match; trying " + mDriverCode
                    + " first. Fallback order: " + chosenCandidates)
        }

        val usbManager = mUsbManager
        val deviceName = chosenDevice.getDeviceName()
        if (usbManager == null || !usbManager.hasPermission(chosenDevice)) {
            if (sPromptedDeviceName == deviceName) {
                startSilentPermissionWait()
            } else {
                sPromptedDeviceName = deviceName
                requestPermission()
            }
            return
        }
        startDriver()
    }

    // 33+ 已带 RECEIVER_NOT_EXPORTED，旧重载分支只在 SDK < 33 生效，
    // 那里没有「必须指定 flag」的要求，属 lint 误报。
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun requestPermission() {
        val device = mDevice ?: return
        LogUtils.i(this, "Requesting USB permission for device id="
                + device.getDeviceId())
        // A receiver from an earlier request may still be registered when
        // the prompt is asked again; keep exactly one at a time.
        unregisterPermissionReceiver()
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        mPermissionReceiver = mPermissionBroadcastReceiver
        val receiver = mPermissionReceiver ?: return
        if (Build.VERSION.SDK_INT >= 33) {
            mContext.registerReceiver(receiver, filter,
                    Context.RECEIVER_NOT_EXPORTED)
        } else {
            mContext.registerReceiver(receiver, filter)
        }

        val intent = Intent(ACTION_USB_PERMISSION)
                .setPackage(mContext.getPackageName())
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The system fills EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED into
            // this PendingIntent when the dialog is answered. An immutable
            // PendingIntent silently drops that fill-in, so the receiver sees
            // granted=false even after the user allowed the device.
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        val permissionIntent = PendingIntent.getBroadcast(
                mContext, 0, intent, flags)
        val usbManager = mUsbManager
        if (usbManager == null) {
            fail("USB host is not available on this device")
            return
        }
        usbManager.requestPermission(device, permissionIntent)
        // The broadcast is the primary signal; the poll protects against
        // OEMs that swallow it (and against a screen reader missing the dialog).
        mHandler.postDelayed(mPermissionPollRunnable, PERMISSION_POLL_MILLIS)
    }

    /**
     * Waits for the permission state without calling requestPermission().
     * Used when the prompt has already been shown for this device, so a
     * background reconnect cannot keep re-showing the system dialog.
     */
    private fun startSilentPermissionWait() {
        if (mDisconnecting || mFailed || mPermissionSettled) {
            return
        }
        if (!mSilentPermissionWait) {
            LogUtils.i(this, "USB permission still pending; waiting without prompting")
            mSilentPermissionWait = true
        }
        mPermissionPollCount = 0
        mHandler.removeCallbacks(mPermissionPollRunnable)
        mHandler.postDelayed(mPermissionPollRunnable, SILENT_PERMISSION_POLL_MILLIS)
    }

    private fun onPermissionGranted() {
        if (mPermissionSettled || mDisconnecting || mFailed) {
            return
        }
        mPermissionSettled = true
        mSilentPermissionWait = false
        sPromptedDeviceName = null
        mHandler.removeCallbacks(mPermissionPollRunnable)
        unregisterPermissionReceiver()
        startDriver()
    }

    private fun startDriver() {
        val device = mDevice ?: return
        if (mDisconnecting || mFailed) {
            return
        }
        val serial = safeSerialNumber(device)
        val parameter = UsbBrailleDevice.buildParameter(
                serial, device.getVendorId(), device.getProductId())
        LogUtils.i(this, "Starting brltty USB driver=" + mDriverCode
                + " parameter=" + parameter)
        try {
            mDriverThread = DriverThread(NoOpOutputStream(),
                    DeviceFinder.DeviceInfo.forUsb(mDriverCode, parameter),
                    mContext.getResources(), mTablesDir, mContext,
                    this, mInputEventListener)
        } catch (t: Throwable) {
            LogUtils.e(this, t, "Could not create the USB driver thread")
            fail("Could not start the brltty USB driver: " + t)
        }
    }

    private fun safeSerialNumber(device: UsbDevice): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return device.getSerialNumber()
            } catch (t: Throwable) {
                LogUtils.w(this, t, "USB serial number unavailable; matching by VID/PID")
            }
        }
        // Empty is safe: usbVerifyString() skips verification for an empty value.
        return ""
    }

    override fun onInit(properties: BrailleDisplayProperties?) {
        // Called on the DriverThread's handler thread.
        if (mDisconnecting || mFailed) {
            return
        }
        if (properties == null) {
            LogUtils.w(this, "brltty USB driver failed to initialize")
            val failedDriver = mDriverCode
            mHandler.postDelayed(object : Runnable {
                override fun run() {
                    tryNextDriver(failedDriver)
                }
            }, DRIVER_FALLBACK_DELAY_MILLIS)
            return
        }
        LogUtils.i(this, "USB braille display ready, text cells="
                + properties.numTextCells)
        mHandler.post(object : Runnable {
            override fun run() {
                if (mDisconnecting || mFailed) {
                    return
                }
                mListener.onUsbConnected(this@UsbDisplayConnection, properties)
                mHandler.post(mInputPollRunnable)
            }
        })
    }

    override fun onDriverFailed() {
        // Called on the driver thread; fail() hands the notification back to
        // this connection's handler.
        fail("brltty USB driver stopped unexpectedly")
    }

    /** Sends a braille window to the display. */
    fun writeWindow(patterns: ByteArray) {
        val dt = mDriverThread
        if (dt != null) {
            dt.writeWindow(patterns)
        }
    }

    /** True while this connection is live and still owns a driver thread. */
    fun isDriverActive(): Boolean {
        return mDriverThread != null && !mDisconnecting && !mFailed
    }

    /** Tears the connection down. The listener is notified after native stop. */
    fun disconnect() {
        if (mDisconnecting) {
            return
        }
        mDisconnecting = true
        mSilentPermissionWait = false
        LogUtils.i(this, "Disconnecting USB braille display")
        mHandler.removeCallbacks(mInputPollRunnable)
        mHandler.removeCallbacks(mPermissionPollRunnable)
        unregisterPermissionReceiver()

        val dt = mDriverThread
        mDriverThread = null
        if (dt == null) {
            notifyDisconnected()
            return
        }
        // DriverThread.stop() joins up to 1s; never block the main thread.
        Thread(object : Runnable {
            override fun run() {
                dt.stop()
                notifyDisconnected()
            }
        }, "UsbDriverStop").start()
    }

    private fun notifyDisconnected() {
        mHandler.post(object : Runnable {
            override fun run() {
                mListener.onUsbDisconnected(this@UsbDisplayConnection)
            }
        })
    }

    private fun fail(reason: String?) {
        if (mFailed || mDisconnecting) {
            return
        }
        mFailed = true
        mSilentPermissionWait = false
        LogUtils.w(this, "USB connection failed: " + reason)
        mHandler.removeCallbacks(mInputPollRunnable)
        mHandler.removeCallbacks(mPermissionPollRunnable)
        unregisterPermissionReceiver()
        mHandler.post(object : Runnable {
            override fun run() {
                mListener.onUsbConnectFailed(this@UsbDisplayConnection, reason)
            }
        })
    }

    /**
     * Some USB chips are shared by several drivers (CP2102 is used by both
     * BrailleMemo and Seika). Try the next candidate when the current driver
     * cannot initialize this particular device.
     */
    private fun tryNextDriver(failedDriver: String?) {
        if (mDisconnecting || mFailed) {
            return
        }
        val candidates = mDriverCandidates
        if (candidates == null || mDriverIndex + 1 >= candidates.size) {
            fail("brltty USB driver failed to initialize (" + failedDriver + ")")
            return
        }
        mDriverThread = null
        mDriverIndex += 1
        mDriverCode = candidates.get(mDriverIndex)
        LogUtils.i(this, "Trying fallback driver " + mDriverCode
                + " after " + failedDriver + " failed")
        startDriver()
    }

    private fun unregisterPermissionReceiver() {
        val receiver = mPermissionReceiver
        mPermissionReceiver = null
        if (receiver != null) {
            try {
                mContext.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                LogUtils.d(this, "Permission receiver was already unregistered")
            }
        }
    }

    /** The native USB backend does its own writes; this stream is never used. */
    private class NoOpOutputStream : OutputStream() {
        override fun write(b: Int) {
        }

        override fun write(b: ByteArray?, off: Int, len: Int) {
        }
    }
}
