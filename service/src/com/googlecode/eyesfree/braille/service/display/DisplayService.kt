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
import android.Manifest
import android.accessibilityservice.BrailleDisplayController
import android.annotation.SuppressLint
import android.os.Build

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.preference.PreferenceManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.PowerManager
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.IBrailleService
import com.googlecode.eyesfree.braille.display.IBrailleServiceCallback
import com.googlecode.eyesfree.braille.service.BuildConfig
import com.googlecode.eyesfree.braille.service.R
import com.googlecode.eyesfree.braille.utils.ZipResourceExtractor
import com.googlecode.eyesfree.utils.LogUtils
import com.googlecode.eyesfree.utils.LogFileWriter
import java.io.File
import java.lang.ref.WeakReference
import java.util.Arrays
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/**
 * An Andorid service that connects to braille displays and exposes a unified
 * interface to other Apps.
 *
 * DisplayService contains ReadThread.
 * ReadThread contains DriverThread and DeviceFinder.
 * DriverThread contains BrlttyWrapper.
 */
class DisplayService : Service(), DriverThread.OnInputEventListener {

    companion object {
        @JvmField
        val LOG_TAG: String = DisplayService::class.java.simpleName

        const val STATE_DISCONNECTED = 0
        const val STATE_CONNECTED = 1
        /**
         * Delay between the screen goes off and the display gets automatically
         * disconnected.
         */
        private const val SCREEN_OFF_DISCONNECT_DELAY = 7000L
        const val HID_RETRY_DELAY = 300L
        /**
         * Delays for background reconnects after the display drops or a
         * connection attempt fails. The delay doubles on each consecutive
         * failure, up to the maximum, so a sleeping or out-of-range display is
         * not polled aggressively.
         */
        const val AUTO_RECONNECT_INITIAL_DELAY = 3000L
        const val AUTO_RECONNECT_MAX_DELAY = 30000L
        /**
         * TalkBack-like discovery window. A bonded display is normally connected
         * directly, but while it is switched off that attempt fails. Keeping a
         * short scan open afterwards lets a display that has just been powered on
         * be found immediately, without leaving Bluetooth inquiry running all day.
         */
        private const val DISCOVERY_SESSION_DURATION = 30000L
        private const val DISCOVERY_RESTART_DELAY = 300L
        /**
         * Hidden but stable system broadcast sent when the input stack registers a
         * new device. A Braille HID display shows up here after the Bluetooth HID
         * profile connects, which is usually later than the user's protocol choice.
         */
        private const val ACTION_INPUT_DEVICE_ADDED =
                "android.hardware.input.action.INPUT_DEVICE_ADDED"
        /**
         * How long to wait for the accessibility service to publish a
         * BrailleDisplayController before reporting it as unavailable. The
         * controller is briefly absent whenever the accessibility service is
         * rebound, so an immediate error is usually misleading.
         */
        private const val HID_CONTROLLER_WAIT_TIMEOUT = 2500L
        const val FILES_ERROR = -1
        const val FILES_NOT_EXTRACTED = 0
        const val FILES_EXTRACTED = 1
        /**
         * 刷新链路心跳。冻结问题时只看三件事：上层有没有继续送点阵、
         * 服务有没有收到、最终走了哪条连接路径。每 5 秒一行，量很小。
         */
        private const val DOTS_TELEMETRY_INTERVAL = 5000L
        private const val ROUTE_NONE = 0
        private const val ROUTE_USB = 1
        private const val ROUTE_HID = 2
        private const val ROUTE_WUJIE = 3
        private const val ROUTE_READ_THREAD = 4

        @JvmStatic
        private fun routeName(route: Int): String {
            return when (route) {
                ROUTE_USB -> "usb"
                ROUTE_HID -> "hid"
                ROUTE_WUJIE -> "wujie"
                ROUTE_READ_THREAD -> "readthread"
                else -> "none"
            }
        }
    }

    /**
     * Listener thread which reads from the braille device and forwards
     * data to the driver.
     */
    /** Written in main thread, read in IPC threads. */
    @Volatile
    var mReadThread: ReadThread? = null
    /** The list of registered clients. */
    val mClients =
            RemoteCallbackList<IBrailleServiceCallback>()
    private val mServiceImpl = ServiceImpl()
    private val mBroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action: String? = intent?.getAction()
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    // Try reconnecting to Braille device on screen on action.
                    // This is done so that if a display was turned on
                    // after the service is started, it will be
                    // connected the next time the user unlocks the device.
                    mHandler.unscheduleDisconnect()
                    autoConnectBraille()
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    // Disconnect the display after the screen goes off, but
                    // wait a bit since it takes some time to reconnect, which
                    // is going to be frustrating if the screen went off
                    // accidentally.
                    stopDiscoverySession("screen off")
                    mHandler.scheduleDisconnect(SCREEN_OFF_DISCONNECT_DELAY)
                } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(
                                action)) {
                    connectFromBroadcastIntent(intent)
                } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(
                                action)) {
                    // The display may come back after being switched on or
                    // after the Bluetooth link was re-established. Retry
                    // without waiting for the next scheduled attempt.
                    retryWhenBluetoothDeviceAppears(intent)
                } else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    handleDiscoveredDevice(intent)
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(
                                action)) {
                    handleDiscoveryFinished()
                } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(
                                action)) {
                    Timber.d("Bluetooth discovery started")
                } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(
                                action)) {
                    val state = intent?.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR) ?: BluetoothAdapter.ERROR
                    if (state == BluetoothAdapter.STATE_ON) {
                        Timber.i("Bluetooth enabled; connecting braille display")
                        autoConnectBraille()
                    } else if (state == BluetoothAdapter.STATE_OFF
                            || state == BluetoothAdapter.STATE_TURNING_OFF) {
                        stopDiscoverySession("Bluetooth disabled")
                        cancelAutoReconnect()
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    // Wired displays only: a USB stick or keyboard must not
                    // disturb a working Bluetooth connection.
                    if (isWiredProtocol() && !isConnectionActive()) {
                        Timber.i("USB device attached; trying wired connection");
                        autoConnectBraille()
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    if (mUsbConnection != null
                            || (isUsbProtocol()
                                && mHidConnection != null)) {
                        Timber.i("USB device detached; disconnecting display")
                        disconnectBraille()
                    }
                } else {
                    Log.w(LOG_TAG, "Unexpected broadcast " + action)
                }
            }
        }

    /**
     * Hidden input-stack broadcast. Kept in a separate non-exported receiver
     * because Bluetooth broadcasts are sent by the Bluetooth process rather
     * than the system UID; those must be registered as exported, while this
     * one does not need to accept broadcasts from other apps.
     */
    private val mInputDeviceReceiver =
            object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (ACTION_INPUT_DEVICE_ADDED.equals(intent?.getAction())) {
                retryHidWhenInputDeviceAppears()
            }
        }
    };

    val mHandler = MainHandler(this)

    var mConnectionState = STATE_DISCONNECTED
    var mConnectionProgress: String? = null
    var mConnectPending = false

    var mDisplayProperties: BrailleDisplayProperties? = null
    private lateinit var mTablesDir: File
    var mDataFileState = FILES_NOT_EXTRACTED
    private var mPendingBluetoothAddress: String? = null

    /**
     * HID connection (Braille HID standard via BrailleDisplayController).
     */
    @Volatile
    var mHidConnection: HidDisplayConnection? = null
    /**
     * Custom BLE GATT connection used by the Wujie / Orbit 30-cell display.
     */
    @Volatile
    var mWujieConnection: WujieGattConnection? = null
    /** Wired USB connection (brltty USB backend), null unless selected. */
    @Volatile
    var mUsbConnection: UsbDisplayConnection? = null

    private val mDotsCalls = AtomicLong()
    @Volatile
    private var mDotsLastDigest = 0
    @Volatile
    private var mDotsLastCells = 0
    @Volatile
    private var mDotsLastRoute = ROUTE_NONE
    private var mDotsReportedCalls: Long = 0
    private val mDotsTelemetry = object : Runnable {
        override fun run() {
            val calls = mDotsCalls.get()
            val hasConnection = mUsbConnection != null
                    || mHidConnection != null
                    || mWujieConnection != null
                    || mReadThread != null
            if (hasConnection || calls > mDotsReportedCalls) {
                LogUtils.i(this@DisplayService,
                        "dots stats: calls=%d (+%d) route=%s digest=%08X "
                        + "cells=%d usb=%s usbActive=%s hid=%s wujie=%s read=%s",
                        calls, calls - mDotsReportedCalls,
                        routeName(mDotsLastRoute), mDotsLastDigest,
                        mDotsLastCells,
                        mUsbConnection != null, isUsbActive(),
                        mHidConnection != null, mWujieConnection != null,
                        mReadThread != null)
            }
            mDotsReportedCalls = calls
            mHandler.postDelayed(this, DOTS_TELEMETRY_INTERVAL)
        }
    }
    /** True after the vendor BLE path was attempted in this cycle. */
    var mWujieTried = false
    /** True after the brltty serial USB path was attempted in this cycle. */
    var mUsbSerialTried = false
    /** True after the system USB HID path was attempted in this cycle. */
    var mUsbHidTried = false
    /** Whether mHidConnection is the wired USB HID path rather than Bluetooth. */
    var mHidConnectionIsUsb = false
    /**
     * True after an HID connection attempt, to avoid retrying HID in a loop
     * when the connected display only supports classic RFCOMM.
     */
    @Volatile
    var mHidTried = false
    /** Number of HID retries already used by the current connection cycle. */
    var mHidRetryCount = 0
    /**
     * True while waiting for the accessibility service to publish the HID
     * controller. {@link HidBridge} notifies us as soon as it appears.
     */
    var mWaitingForHidController = false
    /**
     * Error returned by the last HID attempt, or {@code 0} if no attempt has
     * failed yet. Used to replace the generic failure message with an
     * actionable one (for example, "display is not in Braille HID mode").
     */
    var mLastHidError = 0
    /** Number of consecutive automatic reconnect attempts. */
    var mAutoReconnectAttempt = 0
    /**
     * True after the user explicitly disconnected the display. Automatic
     * retries stay off until the next explicit connect trigger (opening the
     * app, requesting a connect, or turning the screen on).
     */
    var mAutoReconnectSuppressed = false
    /**
     * True while the current explicit automatic-connect trigger is still
     * allowed to open its one discovery window. It is cleared as soon as that
     * window starts, so periodic safety retries do not turn into continuous
     * scanning.
     */
    var mDiscoveryAllowedForCycle = false
    /** True while this service owns the current Bluetooth discovery session. */
    private var mDiscoverySessionActive = false
    /** True when this service, rather than another app, started discovery. */
    private var mDiscoveryStartedByUs = false
    private var mDiscoverySessionDeadline: Long = 0

    /** Reconnects with a different protocol when the setting changes. */
    private val mProtocolPrefListener =
        object : SharedPreferences.OnSharedPreferenceChangeListener {
            override fun onSharedPreferenceChanged(
                    prefs: SharedPreferences?, key: String?) {
                if ("connection_protocol".equals(key)) {
                    val protocol = ConnectionProtocolPolicy.normalize(
                            prefs?.getString(key, null))
                    mHandler.cancelHidRetry()
                    mHidRetryCount = 0
                    resetConnectionAttemptFlags()
                    Timber.i("Connection protocol changed to %s", protocol)
                    if (mConnectionState == STATE_CONNECTED
                            || mReadThread != null || mHidConnection != null
                            || mWujieConnection != null
                            || mUsbConnection != null) {
                        // Reconnect with the new protocol only after the current
                        // connection has been fully torn down. HID disconnect is
                        // asynchronous (BrailleDisplayController.disconnect()), so
                        // the reconnect is triggered by the teardown callback
                        // (handleOnDisplayDisconnected) via mConnectPending.
                        mConnectPending = true
                        disconnectBraille()
                    } else {
                        mHidTried = false
                        connectBraille()
                    }
                }
            }
        }
    /**
     * Reacts to the auto-connect preference. When auto-connect is turned on,
     * an immediate connect attempt is made (subject to the usual connection
     * guards); turning it off does not tear down an existing connection, it
     * only suppresses future implicit connects.
     */
    private val mAutoConnectPrefListener =
        object : SharedPreferences.OnSharedPreferenceChangeListener {
            override fun onSharedPreferenceChanged(
                    prefs: SharedPreferences?, key: String?) {
                if ("auto_connect".equals(key) && prefs?.getBoolean(key, true) == true) {
                    autoConnectBraille()
                } else if ("auto_connect".equals(key)) {
                    stopDiscoverySession("auto-connect disabled")
                    cancelAutoReconnect()
                }
            }
        }
    /**
     * Invoked by {@link HidBridge} when the accessibility service obtains a
     * (new) BrailleDisplayController, e.g. after the HID link dropped and was
     * re-established. Reconnects the HID display with the fresh controller.
     */
    private val mHidControllerChangeListener = object : Runnable {
        override fun run() {
            mHandler.post(object : Runnable {
                override fun run() {
                    val hidRequested = isBluetoothProtocol()
                    if (hidRequested && mWaitingForHidController) {
                        mWaitingForHidController = false
                        mHandler.cancelHidControllerTimeout()
                        mHidTried = false
                        Timber.i("HID controller published; resuming pending connection")
                        connectBraille()
                        return
                    }
                    if (hidRequested && mConnectionState == STATE_DISCONNECTED
                            && mReadThread == null && mHidConnection == null
                            && mWujieConnection == null
                            && mUsbConnection == null) {
                        mHidTried = false
                        autoConnectBraille()
                    }
                }
            })
        }
    }


    /**
     * Mirrors the accessibility service's log level and installs the shared
     * file sink. Both services run in the same process, so init is idempotent:
     * whoever starts first owns the single RollingFileLog.
     */
    private fun configureProcessLogging() {
        LogUtils.setLogLevel(computeLogLevel())
        LogFileWriter.init(this)
    }

    /**
     * Mirrors PreferenceUtils.getLogLevel for early display-service starts.
     * A level chosen on the settings screen wins; otherwise both build types
     * start at INFO, matching R.string.pref_log_level_default. Debug builds
     * never run quieter than INFO so a tester report keeps the connection
     * lifecycle lines, but may still opt into VERBOSE.
     */
    private fun computeLogLevel(): Int {
        val raw: String? = PreferenceManager.getDefaultSharedPreferences(this)
                .getString("log_level", null)
        if (raw != null) {
            try {
                val stored = Integer.parseInt(raw)
                return if (BuildConfig.DEBUG) {
                    Math.min(stored, Log.INFO)
                } else {
                    stored
                }
            } catch (e: NumberFormatException) {
                // Fall through to the build default.
            }
        }
        return Log.INFO
    }

    override fun onCreate() {
        super.onCreate()
        // Route Timber into LogUtils so connection failures (including USB)
        // reach the in-app file log that users can share or copy.
        if (Timber.treeCount == 0) {
            Timber.plant(LogUtilsTimberTree())
        }
        // The display service can be the first component to start (for example
        // when a client binds it before the accessibility service). Install the
        // shared file sink here so USB diagnostics never depend on start order.
        configureProcessLogging()
        mTablesDir = getDir("keytables", Context.MODE_PRIVATE)
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(
                        mProtocolPrefListener)
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(
                        mAutoConnectPrefListener)
        registerBroadcastReceiver()
        HidBridge.setControllerChangeListener(
                this, mHidControllerChangeListener)
        ensureDataFiles()
        // P0 probe (debug builds only): confirm the prebuilt .so still exports
        // the USB driver table JNI entry point. Does not touch connection logic.
        if (BuildConfig.DEBUG) {
            BrlttyWrapper.logUsbDriverTableProbe()
        }
        mHandler.postDelayed(mDotsTelemetry, DOTS_TELEMETRY_INTERVAL)
        LogUtils.i(this, "Service started.")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(LOG_TAG, "Destroying service.")
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(
                        mProtocolPrefListener)
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(
                        mAutoConnectPrefListener)
        HidBridge.setControllerChangeListener(this, null)
        mHandler.unscheduleDisconnect()
        cancelPendingReconnect()
        mAutoReconnectSuppressed = true
        stopDiscoverySession("service destroyed")
        cancelAutoReconnect()
        disconnectBraille()
        // Drop callbacks queued by a display that may already be torn down.
        mHandler.removeCallbacksAndMessages(null)
        if (mBroadcastReceiver != null) {
            unregisterReceiver(mBroadcastReceiver)
        }
        if (mInputDeviceReceiver != null) {
            unregisterReceiver(mInputDeviceReceiver)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.v(LOG_TAG, "onBind")
        autoConnectBraille()
        return mServiceImpl
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // 多客户端场景：仅当最后一个客户端解绑时才断开点显器。
        // 否则（设置页/阅读器/键盘帮助等临时客户端切走时）点显器会被
        // 断开再重连，每次切换界面都触发 BrailleBackService 的
        // "断开 + 连接" 提示音（DisplayService 仍被主客户端绑定着）。
        val clientCount = mClients.beginBroadcast()
        mClients.finishBroadcast()
        if (clientCount <= 0) {
            cancelPendingReconnect()
            disconnectBraille()
        }
        return true
    }

    override fun onRebind(intent: Intent?) {
        autoConnectBraille()
    }

    // NOTE: The methods in this class are invoked on threads created by the
    // IPC system and not the main application thread.
    private inner class ServiceImpl : IBrailleService.Stub() {
        override fun registerCallback(
            callback: IBrailleServiceCallback?): Boolean {
            if (callback == null) {
                Log.e(LOG_TAG, "Registering null callback")
                return false
            }
            mHandler.registerCallback(callback)
            return true
        }

        override fun unregisterCallback(callback: IBrailleServiceCallback?) {
            if (callback == null) {
                Log.e(LOG_TAG, "Unregistering null callback")
                return
            }
            if (!mClients.unregister(callback)) {
                Log.w(LOG_TAG, "Failed to unregister callback" + callback)
            }
        }

        override fun displayDots(patterns: ByteArray?) {
            if (patterns == null) {
                Log.e(LOG_TAG, "null dot patterns")
                return
            }
            // Route output to the HID connection when it is the active path
            // (HID uses HidDisplayConnection instead of ReadThread).
            val usbConnection = mUsbConnection
            if (usbConnection != null) {
                recordDots(patterns, ROUTE_USB)
                usbConnection.writeWindow(patterns)
                return
            }
            val hidConnection = mHidConnection
            if (hidConnection != null) {
                recordDots(patterns, ROUTE_HID)
                hidConnection.writeWindow(patterns)
                return
            }
            val wujieConnection = mWujieConnection
            if (wujieConnection != null) {
                recordDots(patterns, ROUTE_WUJIE)
                wujieConnection.writeWindow(patterns)
                return
            }
            val localReadThread = mReadThread
            if (localReadThread == null) {
                recordDots(patterns, ROUTE_NONE)
                return
            }
            val localDriverThread = localReadThread.getDriverThread()
            if (localDriverThread == null) {
                recordDots(patterns, ROUTE_NONE)
                return
            }
            recordDots(patterns, ROUTE_READ_THREAD)
            localDriverThread.writeWindow(patterns)
        }

        override fun poll() {
            mHandler.connectBrailleExplicit()
        }

        override fun disconnect() {
            mHandler.disconnectBraille()
        }
    }

    /*package*/ fun onDisplayConnected(
        properties: BrailleDisplayProperties?) {
        mHandler.onDisplayConnected(properties)
    }

    /** Records one displayDots delivery for the refresh telemetry. */
    private fun recordDots(patterns: ByteArray, route: Int) {
        mDotsCalls.incrementAndGet()
        mDotsLastCells = patterns.size
        mDotsLastDigest = Arrays.hashCode(patterns)
        mDotsLastRoute = route
    }

    /** True when the wired USB path still owns a live driver thread. */
    private fun isUsbActive(): Boolean {
        val connection = mUsbConnection
        return connection != null && connection.isDriverActive()
    }

    /*package*/ fun onDisplayDisconnected() {
        mHandler.onDisplayDisconnected()
    }

    /*package*/ fun setConnectionProgress(description: String?) {
        mHandler.setConnectionProgress(description)
    }

    /**
     * Forwards input events from the driver thread to be broadcast
     * from the main service thread.
     */
    override fun onInputEvent(event: BrailleInputEvent) {
        mHandler.onInputEvent(event)
    }

    /**
     * Disconnects the service from the currently connected braille device,
     * sends notification to the clients about the state change.
     */
    fun disconnectBraille() {
        disconnectBraille(false)
    }

    /**
     * Disconnects the display. A user-initiated disconnect suppresses the
     * background reconnect loop until the next explicit connect trigger.
     */
    fun disconnectBraille(userInitiated: Boolean) {
        if (userInitiated) {
            mAutoReconnectSuppressed = true
            mAutoReconnectAttempt = 0
            cancelAutoReconnect()
        }
        // Unexpected drops may be the display being switched off and back on;
        // allow one discovery window when the teardown completes. A user
        // initiated disconnect stays suppressed.
        mDiscoveryAllowedForCycle = !userInitiated
        stopDiscoverySession("disconnecting display")
        Log.i(LOG_TAG, "Disconnecting braille display")
        Timber.i("Disconnecting braille display (pending=%s)", mConnectPending)
        clearHidControllerWait()
        clearConnectionProgress()
        mConnectionState = STATE_DISCONNECTED
        mDisplayProperties = null
        broadcastConnectionState()
        if (mReadThread != null) {
            mReadThread!!.disconnect()
        }
        if (mHidConnection != null) {
            // Keep the reference until HidDisplayConnection reports that the
            // system controller has really been released. Clearing it here
            // lets a new connection race the asynchronous teardown.
            mHidConnection!!.disconnect()
        }
        if (mWujieConnection != null) {
            mWujieConnection!!.disconnect()
        }
        if (mUsbConnection != null) {
            // Like HID, this reports back asynchronously once the native
            // driver has actually stopped.
            mUsbConnection!!.disconnect()
        }
        mHidTried = false
        // An explicit disconnect starts a new connection cycle, so a
        // controller release conflict may be retried once in that cycle.
        mHidRetryCount = 0
    }

    /** Cancels a reconnect requested by an earlier transition. */
    fun cancelPendingReconnect() {
        mConnectPending = false
        mHidRetryCount = 0
        mHandler.cancelHidRetry()
    }

    /**
     * Clears any in-flight connection progress and notifies clients, so the
     * settings UI is not left showing a stale "connecting..." message and
     * re-renders the real connection state.
     */
    fun clearConnectionProgress() {
        if (mConnectionProgress != null) {
            mConnectionProgress = null
            broadcastConnectionProgress()
        }
    }

    /**
     * Waits for the accessibility service to publish a
     * BrailleDisplayController. The controller is transiently absent whenever
     * the service is rebound, so the UI shows a neutral waiting message and
     * {@link HidBridge} resumes the connection as soon as it appears. If the
     * controller never arrives, the bounded timeout reports the real error.
     */
    private fun waitForHidController() {
        if (mWaitingForHidController) {
            return
        }
        mWaitingForHidController = true
        Timber.i("Waiting up to %d ms for the HID controller",
                HID_CONTROLLER_WAIT_TIMEOUT)
        mHandler.setConnectionProgress(
                getString(R.string.connprog_hid_waiting_controller))
        mHandler.scheduleHidControllerTimeout(HID_CONTROLLER_WAIT_TIMEOUT)
    }

    /** Cancels a pending wait for the HID controller. */
    fun clearHidControllerWait() {
        if (mWaitingForHidController) {
            mWaitingForHidController = false
            mHandler.cancelHidControllerTimeout()
        }
    }

    /**
     * Tries to connect to the braille device, on success sends notification to
     * registered clients.
     */
    fun connectBraille() {
        if (mConnectionState == STATE_CONNECTED) {
            return
        }

        if (mDataFileState != FILES_EXTRACTED) {
            return
        }

        if (mReadThread != null || mHidConnection != null
                || mWujieConnection != null || mUsbConnection != null) {
            // We must wait until the previous attempt is done before
            // retrying a connect, so just set the flag here.
            mConnectPending = true
            return
        }

        // RFCOMM/HID connections are faster and more reliable when Bluetooth
        // inquiry is not competing with them.
        stopDiscoverySession("connecting display")
        val protocol = getConnectionProtocol()
        Timber.i("Starting connection with protocol %s", protocol)
        if (isUsbProtocol()) {
            connectUsbUnified()
        } else {
            connectBluetooth()
        }
    }

    /** Tries the wireless transports in order: Wujie BLE, HID, then RFCOMM. */
    private fun connectBluetooth() {
        if (!mWujieTried && isWujieTarget()) {
            mWujieTried = true
            // connectWujie() prefers mPendingBluetoothAddress, so a display
            // found by discovery is dialled directly instead of being
            // re-scanned for.
            connectWujie()
            return
        }
        if (!mHidTried) {
            if (tryConnectHid()) {
                // A Braille HID connection attempt was started (async).
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
                    && !HidBridge.isAvailable()) {
                // The controller is briefly null while the accessibility service
                // rebinds. Wait instead of falling back to RFCOMM prematurely.
                waitForHidController()
                return
            }
        }
        // HID failed / is unavailable: classic Bluetooth serial (RFCOMM).
        mReadThread = ReadThread(this, mTablesDir,
                mPendingBluetoothAddress)
        mPendingBluetoothAddress = null
        mReadThread!!.start()
    }

    /**
     * Tries to start a Braille HID standard (usage page 0x41) connection.
     * Returns true if an HID attempt was started (asynchronously), false to
     * fall back to classic RFCOMM.
     */
    // BrailleDisplayController and HidBridge require API 35; callers reach
    // this method only after HidBridge.isAvailable() has checked the SDK level.
    @SuppressLint("NewApi")
    private fun tryConnectHid(): Boolean {
        if (mHidTried) {
            return false
        }
        if (!HidBridge.isAvailable()) {
            Timber.w("HID requested but BrailleDisplayController is unavailable (sdk=%d)",
                    Build.VERSION.SDK_INT)
            return false
        }
        return startHidAttempt()
    }

    /** Retries an HID attempt after the controller release conflict. */
    fun retryHidConnect(): Boolean {
        if (mHidTried || !HidBridge.isAvailable()) {
            return false
        }
        return startHidAttempt()
    }

    @SuppressLint("NewApi")
    private fun startHidAttempt(): Boolean {
        val controller = HidBridge.getController()
        if (controller == null) {
            Timber.w("Aborting HID attempt: controller became null")
            return false
        }
        var address: String? = mPendingBluetoothAddress
        if (address == null) {
            val devices = DeviceFinder(this).findDevices()
            if (devices.isEmpty()) {
                return false
            }
            try {
                address = devices[0].getBluetoothDevice()?.address
            } catch (e: SecurityException) {
                Log.w(LOG_TAG,
                        "BLUETOOTH_CONNECT permission denied reading address",
                        e)
                return false
            }
            if (address == null) {
                return false
            }
        }
        mHidTried = true
        mHidConnectionIsUsb = false
        mLastHidError = 0
        mPendingBluetoothAddress = null
        mHandler.setConnectionProgress(getString(R.string.connprog_trying_hid))
        val connection = HidDisplayConnection(this,
                getResources(), mTablesDir, mHidListener, this)
        mHidConnection = connection
        Timber.i("Creating HID connection attempt for %s", address)
        connection.connect(controller, address)
        return true
    }

    /**
     * Returns a message that explains why the last HID attempt failed.
     *
     * <p>The platform reports a bit mask from
     * {@code BrailleDisplayController}: bit 1 means that the HIDRAW nodes could
     * not be inspected, and bit 2 means that no uniquely matching Braille HID
     * display was found. The latter is by far the most common case and means
     * the display is still in Bluetooth serial (SPP/RFCOMM) mode, or was
     * paired while in that mode, so the system has no Braille HID node for it.
     */
    private fun getHidFailureMessage(): String {
        if ((mLastHidError and 2) != 0) {
            return getString(R.string.connprog_hid_not_found)
        }
        if (mLastHidError != 0) {
            return getString(R.string.connprog_hid_failed_code,
                    mLastHidError)
        }
        return getString(R.string.connprog_hid_failed)
    }

    /**
     * Failure copy for the wired USB HID path. Error -7 means no attached USB
     * device exposes a HID interface; -8 means the USB permission was denied.
     * Both are expected states when nothing is plugged in, so the wording must
     * tell the user what to do instead of just showing an error code.
     */
    fun getUsbHidFailureMessage(error: Int): String {
        if (error == -7) {
            return getString(R.string.connprog_usb_hid_not_found)
        }
        if (error == -8) {
            return getString(R.string.connprog_usb_hid_permission_denied)
        }
        return getString(R.string.connprog_usb_hid_failed, error)
    }

    /** Returns the user-selected connection protocol. */
    fun getConnectionProtocol(): String {
        val value = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(ConnectionProtocolPolicy.PREFERENCE_KEY,
                        ConnectionProtocolPolicy.BLUETOOTH)
        return ConnectionProtocolPolicy.normalize(value)
    }

    fun isBluetoothProtocol(): Boolean {
        return ConnectionProtocolPolicy.BLUETOOTH.equals(
                getConnectionProtocol())
    }

    fun isUsbProtocol(): Boolean {
        return ConnectionProtocolPolicy.USB.equals(getConnectionProtocol())
    }

    /** Wired USB must keep retrying even when Bluetooth is off. */
    private fun isWiredProtocol(): Boolean {
        return isUsbProtocol()
    }

    fun isHidRequested(): Boolean {
        return isBluetoothProtocol()
    }

    /** Resets the per-cycle transport fallback state. */
    fun resetConnectionAttemptFlags() {
        mHidTried = false
        mHidRetryCount = 0
        mLastHidError = 0
        mWujieTried = false
        mUsbSerialTried = false
        mUsbHidTried = false
        mHidConnectionIsUsb = false
    }

    /**
     * Tries to connect to the braille display, but only if automatic
     * connection is enabled in the preferences. Used for implicit
     * (non-user-initiated) connect triggers such as screen-on, (re)bind,
     * device pairing and HID controller changes. Explicit connects via
     * {@code poll()} / {@code disconnect()} always bypass this check so that
     * the volume-key toggle keeps working regardless of the setting.
     */
    private fun autoConnectBraille() {
        mAutoReconnectSuppressed = false
        mAutoReconnectAttempt = 0
        mHandler.cancelAutoReconnect()
        if (PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("auto_connect", true)) {
            if (!isConnectionActive()) {
                resetConnectionAttemptFlags()
            }
            mDiscoveryAllowedForCycle = true
            connectBraille()
        } else {
            mDiscoveryAllowedForCycle = false
            stopDiscoverySession("auto-connect disabled")
        }
    }

    /** Starts a connection explicitly requested by a client. */
    fun startExplicitConnect() {
        mAutoReconnectSuppressed = false
        mAutoReconnectAttempt = 0
        mHandler.cancelAutoReconnect()
        if (!isConnectionActive()) {
            resetConnectionAttemptFlags()
        }
        mDiscoveryAllowedForCycle = true
        connectBraille()
    }

    /**
     * Retries when a known braille display establishes its Bluetooth link.
     * Only acts while nothing is connected or connecting, so unrelated
     * devices (headsets, mice, ...) cannot disturb an established display
     * connection.
     */
    private fun retryWhenBluetoothDeviceAppears(intent: Intent?) {
        if (isConnectionActive()) {
            return
        }
        val device: BluetoothDevice? =
                intent?.getParcelableExtra<BluetoothDevice>(
                        BluetoothDevice.EXTRA_DEVICE)
        if (device != null) {
            var address: String? = null
            try {
                address = device.getAddress()
            } catch (e: SecurityException) {
                Timber.w(e,
                        "BLUETOOTH_CONNECT permission denied reading address")
            }
            if (address != null
                    && !DeviceFinder(this).isKnownDevice(address)) {
                Timber.i("Ignoring Bluetooth link for unrelated device %s",
                        address)
                return
            }
        }
        prepareAutomaticRetry("Bluetooth link appeared")
    }

    /** Retries an HID attempt when a new input device is registered. */
    private fun retryHidWhenInputDeviceAppears() {
        // Wired USB HID devices also arrive as InputDevice additions, even
        // though they are not part of the Bluetooth HID protocol set.
        val wiredUsbHid = isUsbProtocol()
        if ((!isHidRequested() && !wiredUsbHid) || isConnectionActive()) {
            return
        }
        prepareAutomaticRetry("HID input device appeared")
    }

    private fun prepareAutomaticRetry(reason: String?) {
        if (isConnectionActive()) {
            return
        }
        mHandler.cancelHidControllerTimeout()
        mWaitingForHidController = false
        resetConnectionAttemptFlags()
        Timber.i("%s; retrying display connection", reason)
        autoConnectBraille()
    }

    fun isConnectionActive(): Boolean {
        return mConnectionState == STATE_CONNECTED
                || mReadThread != null
                || mHidConnection != null
                || mWujieConnection != null
                || mUsbConnection != null
    }

    /**
     * Schedules the next background reconnect attempt after a failed or
     * dropped connection. Retrying is intentionally tied to the lifecycle of
     * the service: it only runs while a client is bound, the screen is
     * interactive, auto-connect is enabled and the user has not explicitly
     * disconnected the display.
     */
    fun scheduleAutoReconnectIfNeeded() {
        if (!shouldAutoReconnect()) {
            Timber.v("Not scheduling auto reconnect (not eligible)")
            return
        }
        if (mHandler.hasAutoReconnectScheduled()) {
            return
        }
        var discoveryStarted = false
        if (mDiscoveryAllowedForCycle) {
            mDiscoveryAllowedForCycle = false
            discoveryStarted = startDiscoveryWindow(
                    "connection attempt failed")
        }
        val attempt = mAutoReconnectAttempt++
        // While the discovery window is open, wait for a device to appear
        // instead of cancelling the scan with an RFCOMM page three seconds
        // later. The ordinary backoff resumes once the window closes.
        mHandler.scheduleAutoReconnect(attempt,
                if (discoveryStarted) {
                    DISCOVERY_SESSION_DURATION
                } else {
                    -1L
                })
    }

    fun shouldAutoReconnect(): Boolean {
        if (!PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("auto_connect", true)) {
            return false
        }
        if (mAutoReconnectSuppressed || !haveClients()) {
            return false
        }
        if (mDataFileState != FILES_EXTRACTED) {
            return false
        }
        if (isConnectionActive() || mConnectPending
                || mWaitingForHidController) {
            return false
        }
        if (!isScreenInteractive()) {
            return false
        }
        if (isWiredProtocol()) {
            return true
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        try {
            return adapter != null && adapter.isEnabled()
        } catch (e: SecurityException) {
            Timber.w(e,
                    "BLUETOOTH_CONNECT permission denied checking adapter")
            return false
        }
    }

    private fun isScreenInteractive(): Boolean {
        val powerManager =
                getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager == null || powerManager.isInteractive()
    }

    private fun hasBluetoothScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun shouldStartDiscovery(): Boolean {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        var adapterEnabled = false
        if (adapter != null) {
            try {
                adapterEnabled = adapter.isEnabled()
            } catch (e: SecurityException) {
                Timber.w(e, "BLUETOOTH_CONNECT permission denied checking adapter")
            }
        }
        return BluetoothDiscoveryPolicy.shouldStartDiscovery(
                PreferenceManager.getDefaultSharedPreferences(this)
                        .getBoolean("auto_connect", true),
                mAutoReconnectSuppressed,
                haveClients(),
                mDataFileState == FILES_EXTRACTED,
                isConnectionActive(),
                mConnectPending,
                mWaitingForHidController,
                isScreenInteractive(),
                adapterEnabled,
                hasBluetoothScanPermission())
    }

    /** Opens (or extends) the short discovery window used after a failed connect. */
    fun startDiscoveryWindow(reason: String): Boolean {
        if (!shouldStartDiscovery()) {
            Timber.v("Not starting discovery (%s): not eligible", reason)
            return false
        }
        val now = SystemClock.uptimeMillis()
        if (mDiscoverySessionActive && now < mDiscoverySessionDeadline) {
            mDiscoverySessionDeadline = now + DISCOVERY_SESSION_DURATION
            mHandler.scheduleDiscoveryTimeout(DISCOVERY_SESSION_DURATION)
            Timber.d("Extending Bluetooth discovery window (%s)", reason)
            startDiscoveryInternal(reason)
            return true
        }
        mDiscoverySessionActive = true
        mDiscoveryStartedByUs = false
        mDiscoverySessionDeadline = now + DISCOVERY_SESSION_DURATION
        mHandler.scheduleDiscoveryTimeout(DISCOVERY_SESSION_DURATION)
        Timber.i("Starting %d ms Bluetooth discovery window (%s)",
                DISCOVERY_SESSION_DURATION / 1000, reason)
        startDiscoveryInternal(reason)
        return mDiscoverySessionActive
    }

    fun startDiscoveryInternal(reason: String) {
        if (!mDiscoverySessionActive) {
            return
        }
        if (!shouldStartDiscovery()) {
            stopDiscoverySession("discovery no longer eligible")
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            stopDiscoverySession("no Bluetooth adapter")
            return
        }
        try {
            if (adapter.isDiscovering()) {
                Timber.d("Bluetooth discovery already active (%s)", reason)
                return
            }
            if (adapter.startDiscovery()) {
                mDiscoveryStartedByUs = true
                Timber.i("Bluetooth discovery started (%s)", reason)
            } else {
                mDiscoveryStartedByUs = false
                Timber.w("Bluetooth discovery was not started (%s)", reason)
            }
        } catch (e: SecurityException) {
            Timber.w(e, "BLUETOOTH_SCAN permission denied; stopping discovery")
            stopDiscoverySession("scan permission denied")
        }
    }

    private fun handleDiscoveryFinished() {
        if (!mDiscoverySessionActive) {
            return
        }
        mDiscoveryStartedByUs = false
        val now = SystemClock.uptimeMillis()
        if (now >= mDiscoverySessionDeadline) {
            stopDiscoverySession("discovery window expired")
            return
        }
        if (!shouldStartDiscovery()) {
            stopDiscoverySession("discovery no longer eligible")
            return
        }
        Timber.d("Discovery cycle finished; restarting within window")
        mHandler.scheduleDiscoveryRestart(DISCOVERY_RESTART_DELAY)
    }

    private fun handleDiscoveredDevice(intent: Intent?) {
        if (!mDiscoverySessionActive || isConnectionActive()) {
            return
        }
        val device: BluetoothDevice? =
                intent?.getParcelableExtra<BluetoothDevice>(
                        BluetoothDevice.EXTRA_DEVICE)
        if (device == null) {
            return
        }
        var address: String? = null
        var name: String? = null
        try {
            address = device.getAddress()
            name = device.getName()
        } catch (e: SecurityException) {
            Timber.w(e, "BLUETOOTH_CONNECT permission denied reading discovery result")
        }
        if (address == null || !DeviceFinder(this).isKnownDevice(address)) {
            Timber.v("Discovery found unrelated device %s (%s)", name, address)
            return
        }
        Timber.i("Discovered known braille display %s (%s); connecting",
                name, address)
        stopDiscoverySession("known display discovered")
        mPendingBluetoothAddress = address
        mHidTried = false
        mWujieTried = false
        mHidRetryCount = 0
        mLastHidError = 0
        autoConnectBraille()
        // If this attempt fails, wait for the next lifecycle event or the
        // periodic safety retry instead of immediately scanning again.
        mDiscoveryAllowedForCycle = false
    }

    fun stopDiscoverySession(reason: String) {
        mDiscoverySessionActive = false
        mDiscoverySessionDeadline = 0
        mHandler.cancelDiscoveryTimeout()
        mHandler.cancelDiscoveryRestart()
        val owned = mDiscoveryStartedByUs
        mDiscoveryStartedByUs = false
        if (!owned) {
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !hasBluetoothScanPermission()) {
            return
        }
        try {
            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery()
                Timber.i("Bluetooth discovery stopped (%s)", reason)
            }
        } catch (e: SecurityException) {
            Timber.w(e, "BLUETOOTH_SCAN permission denied cancelling discovery")
        }
    }

    fun cancelAutoReconnect() {
        mHandler.cancelAutoReconnect()
    }

    /**
     * Connection callbacks come from the extension factories in
     * DisplayServiceListeners.kt. Extensions keep the listener bodies
     * reading service state directly instead of through a wrapper.
     */
    private val mHidListener = createHidListener()
    private val mWujieListener = createWujieListener()
    private val mUsbListener = createUsbListener()

    /**
     * Starts the wired USB connection path (brltty's Android USB backend).
     * Device enumeration, permission and the actual transfers are handled by
     * {@link UsbDisplayConnection}; this method only wires it into the service
     * state machine.
     */
    fun connectUsb() {
        mHandler.setConnectionProgress(
                getString(R.string.connprog_trying_usb))
        val connection = UsbDisplayConnection(
                this, mTablesDir, mUsbListener, this)
        mUsbConnection = connection
        Timber.i("Starting wired USB connection")
        connection.connect()
    }

    /**
     * Unified USB entry point: the precise brltty VID/PID table is tried
     * first; if no serial device matches, the failure callback falls back to
     * the system Braille HID path on Android 15+.
     */
    private fun connectUsbUnified() {
        mUsbSerialTried = true
        connectUsb()
    }

    fun canUseUsbHid(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
                && HidBridge.isAvailable()
    }

    /**
     * Starts the wired USB HID path (Android 15+ / Braille HID standard).
     * Ported from Google TalkBack's UsbHidConnector: the system
     * {@code BrailleDisplayController.connect(UsbDevice, ...)} overload owns
     * the descriptor and transport, so the Bluetooth HID callbacks, the native
     * "hid" driver and BrlttyHidNativeHelper are reused unchanged.
     */
    @SuppressLint("NewApi")
    fun connectUsbHid() {
        if (Build.VERSION.SDK_INT
                < Build.VERSION_CODES.VANILLA_ICE_CREAM
                || !HidBridge.isAvailable()) {
            mHandler.setConnectionProgress(
                    getString(R.string.connprog_usb_hid_unavailable))
            scheduleAutoReconnectIfNeeded()
            return
        }
        mHidTried = true
        mHidConnectionIsUsb = true
        mLastHidError = 0
        mPendingBluetoothAddress = null
        mHandler.setConnectionProgress(
                getString(R.string.connprog_trying_usb_hid))
        val connection = HidDisplayConnection(this,
                getResources(), mTablesDir, mHidListener, this)
        mHidConnection = connection
        Timber.i("Starting wired USB HID connection")
        connection.connectUsb(HidBridge.getController(),
                getSystemService(Context.USB_SERVICE) as? UsbManager)
    }

    /**
     * Starts the private BLE GATT connection used by the Wujie display.
     * Unlike the HID path this does not depend on the system braille HID
     * service, so the display can stay in its vendor BLE mode.
     */
    private fun connectWujie() {
        var address: String? = mPendingBluetoothAddress
        if (address == null) {
            address = findWujieAddress()
        }
        connectWujie(address)
    }

    private fun connectWujie(address: String?) {
        if (address == null) {
            Timber.w("No bonded Wujie display found")
            mHandler.setConnectionProgress(
                    getString(R.string.connprog_no_devices))
            scheduleAutoReconnectIfNeeded()
            return
        }
        mPendingBluetoothAddress = null
        mHandler.setConnectionProgress(
                getString(R.string.connprog_trying_wujie))
        val connection =
                WujieGattConnection(this, mWujieListener)
        mWujieConnection = connection
        Timber.i("Creating custom GATT connection for %s", address)
        connection.connect(address)
    }

    /**
     * True when the display this cycle is about to reach is a Wujie / Orbit
     * device, which only answers the vendor BLE GATT protocol.
     *
     * <p>The pending address must be inspected, not just its absence: a
     * discovery result or a fresh bond sets {@link #mPendingBluetoothAddress},
     * and the old "pending is null" precondition made a rediscovered display
     * skip the Wujie path entirely and fall through to the standard Braille
     * HID path, which the vendor BLE mode never answers.
     */
    private fun isWujieTarget(): Boolean {
        val pending = mPendingBluetoothAddress
        if (pending == null) {
            return findNamedWujieAddress() != null
        }
        if (isWujieName(readRemoteDeviceName(pending))) {
            return true
        }
        // Fallback through the bonded scan: the remote name can be
        // unavailable without BLUETOOTH_CONNECT, and a user-renamed display
        // still matches by address.
        val bonded = findNamedWujieAddress()
        if (bonded != null && bonded.equals(pending, ignoreCase = true)) {
            return true
        }
        // Deliberately not falling back to the lastConnected preference: it
        // records the last display of ANY protocol, so treating it as Wujie
        // evidence would send a plain RFCOMM display through a doomed BLE
        // attempt on every reconnect.
        return false
    }

    /** Name match shared by the bonded scan and the pending-address check. */
    private fun isWujieName(name: String?): Boolean {
        if (name == null) {
            return false
        }
        val lower = name.lowercase(java.util.Locale.US)
        return lower.contains("orbit") || lower.contains("wujie")
                || name.contains("无界")
    }

    /** Cached remote name of a bonded device, or null when unreadable. */
    private fun readRemoteDeviceName(address: String): String? {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            return null
        }
        return try {
            adapter.getRemoteDevice(address).getName()
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "BLUETOOTH_CONNECT permission denied reading name", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(LOG_TAG, "Invalid Bluetooth address $address")
            null
        }
    }

    /**
     * Returns the address of a bonded Wujie / Orbit display. Name matching is
     * deliberately broad because the display advertises as "Orbit wujie 30"
     * and the user may have renamed it.
     */
    private fun findWujieAddress(): String? {
        val named = findNamedWujieAddress()
        if (named != null) {
            return named
        }
        return PreferenceManager
                .getDefaultSharedPreferences(this)
                .getString("lastBluetoothDevice", null)
    }

    /** Strict discovery for automatic Bluetooth fallback. */
    private fun findNamedWujieAddress(): String? {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            return null
        }
        try {
            val bonded = adapter.getBondedDevices()
            if (bonded == null) {
                return null
            }
            for (device in bonded) {
                if (device == null) {
                    continue
                }
                val name: String? = device.getName()
                if (name == null) {
                    continue
                }
                if (isWujieName(name)) {
                    return device.getAddress()
                }
            }
        } catch (e: SecurityException) {
            Log.w(LOG_TAG,
                    "BLUETOOTH_CONNECT permission denied finding Wujie device",
                    e)
            return null
        }
        return null
    }


    /**
     * If {@code intent} indicates that a new device has been
     * paired, try to connect to it.
     */
    private fun connectFromBroadcastIntent(intent: Intent?) {
        val bthDev: BluetoothDevice? =
                intent?.getParcelableExtra<BluetoothDevice>(
                        BluetoothDevice.EXTRA_DEVICE)
        if (bthDev == null) {
            return
        }
        val address: String?
        try {
            address = bthDev.getAddress()
        } catch (e: SecurityException) {
            Log.w(LOG_TAG,
                    "BLUETOOTH_CONNECT permission denied; ignoring bonded device",
                    e)
            return
        }
        if (address == null) {
            return
        }
        val bondState = intent?.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                BluetoothDevice.BOND_NONE) ?: BluetoothDevice.BOND_NONE
        if (bondState == BluetoothDevice.BOND_NONE) {
            if (address.equals(mPendingBluetoothAddress)) {
                mPendingBluetoothAddress = null
                Timber.i("Cleared pending Bluetooth device after unpair")
            }
            return
        }
        if (bondState != BluetoothDevice.BOND_BONDED) {
            return
        }
        if (mConnectionState == STATE_CONNECTED) {
            return
        }
        if (!DeviceFinder(this).isKnownDevice(address)) {
            Timber.i("Ignoring unrelated bonded Bluetooth device %s", address)
            return
        }
        mPendingBluetoothAddress = address
        // A fresh bond starts a new connection attempt. Without this reset a
        // failed HID attempt from before the device was unpaired would make
        // tryConnectHid() return false forever and replay the stale error.
        mHidTried = false
        mHidRetryCount = 0
        mLastHidError = 0
        autoConnectBraille()
    }

    private fun registerBroadcastReceiver() {
        val actions = arrayOf(
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            BluetoothDevice.ACTION_BOND_STATE_CHANGED,
            BluetoothDevice.ACTION_ACL_CONNECTED,
            BluetoothDevice.ACTION_FOUND,
            BluetoothAdapter.ACTION_DISCOVERY_STARTED,
            BluetoothAdapter.ACTION_DISCOVERY_FINISHED,
            BluetoothAdapter.ACTION_STATE_CHANGED,
            UsbManager.ACTION_USB_DEVICE_ATTACHED,
            UsbManager.ACTION_USB_DEVICE_DETACHED,
        )
        val filter = IntentFilter()
        for (action in actions) {
            filter.addAction(action)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // Bluetooth broadcasts come from the Bluetooth process, not the
            // system UID, so a NOT_EXPORTED receiver does not reliably see
            // ACTION_FOUND/ACTION_ACL_CONNECTED on Android 13+.
            registerReceiver(mBroadcastReceiver, filter, null, null,
                    Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(mBroadcastReceiver, filter, null, null)
        }

        val inputFilter = IntentFilter(ACTION_INPUT_DEVICE_ADDED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mInputDeviceReceiver, inputFilter, null, null,
                    Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mInputDeviceReceiver, inputFilter, null, null)
        }
    }

    fun sendConnectionState(callback: IBrailleServiceCallback?) {
        callback ?: return
        try {
            when (mConnectionState) {
                STATE_CONNECTED -> {
                    callback.onDisplayConnected(mDisplayProperties)
                }
                STATE_DISCONNECTED -> {
                    callback.onDisplayDisconnected()
                }
                else -> {
                    Log.e(LOG_TAG,
                            "Unknown connection state: " + mConnectionState)
                }
            }
        } catch (ex: RemoteException) {
            // Nothing to do, the callback list will remove the callback
            // later.
        }
    }

    fun sendConnectionProgress(callback: IBrailleServiceCallback?) {
        callback ?: return
        try {
            callback.onConnectionChangeProgress(mConnectionProgress)
        } catch (ex: RemoteException) {
            // Nothing to do, the callback list will remove the callback
            // later.
        }
    }

    fun broadcastConnectionState() {
        var i = mClients.beginBroadcast()
        try {
            while (i-- > 0) {
                sendConnectionState(mClients.getBroadcastItem(i))
            }
        } finally {
            mClients.finishBroadcast()
        }
    }

    fun broadcastConnectionProgress() {
        var i = mClients.beginBroadcast()
        try {
            while (i-- > 0) {
                sendConnectionProgress(mClients.getBroadcastItem(i))
            }
        } finally {
            mClients.finishBroadcast()
        }
    }

    fun broadcastInputEvent(event: BrailleInputEvent?) {
        var i = mClients.beginBroadcast()
        try {
            while (i-- > 0) {
                try {
                    mClients.getBroadcastItem(i).onInput(event)
                } catch (ex: RemoteException) {
                    // Nothing to do, the callback list will remove the
                    // callback later.
                }
            }
        } finally {
            mClients.finishBroadcast()
        }
    }

    /**
     * Returns {@code true} if there are any registered clients that
     * haven't died or unregistered themselves.
     */
    fun haveClients(): Boolean {
        // Unfortunately, the callback list doesn't provide this
        // information without doing this.
        val numClients = mClients.beginBroadcast()
        mClients.finishBroadcast()
        return numClients > 0
    }

    private fun ensureDataFiles() {
        if (mDataFileState != FILES_NOT_EXTRACTED) {
            return
        }
        // TODO: When the zip file is larger than a few kilobytes, detect if
        // the data was already extracted and don't do this every time the
        // service starts.
        DataFileExtractor(this, R.raw.keytables, mTablesDir).execute()
    }

}
