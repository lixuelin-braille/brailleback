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
import com.googlecode.eyesfree.utils.SettingsStore
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

        /**
         * 【临时诊断开关 2026-11-13】为验证 brltty 标准驱动能否自己认这台点显器，
         * 置 true 时完全跳过厂商私有 BLE（Wujie GATT），强制回落到标准 Braille
         * HID → RFCOMM。诊断结束改回 false 即恢复，不需要动别的地方。
         *
         * 注意：这个开关只影响「要不要走厂商私有协议」，不改任何驱动实现。
         * 打开后日志里不会再出现 "Creating custom GATT connection"。
         */
        const val DISABLE_VENDOR_BLE_TEMP = true

        /**
         * 置 true 时，识别为 Wujie/Orbit 的点显器跳过标准 Braille HID，
         * 直接走经典蓝牙 SPP（RFCOMM）+ brltty 的 bm（Baum）驱动。
         *
         * 原因：该固件的 HOGP 通道只声明输入报告、实际从不推送按键
         * （实机验证：按遍所有键，onInput 回调 0 次），而串口通道是
         * 完整双向的。沉淀链路为「显示 + 按键都走 SPP」，按键由 bm
         * 驱动解析后经 readCommand 回传，不再依赖 HID 输入报告。
         *
         * 置 false 即恢复「HID 优先、RFCOMM 兜底」的旧顺序。
         */
        const val PREFER_RFCOMM_FOR_WUJIE = true

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
        internal const val DISCOVERY_SESSION_DURATION = 30000L
        internal const val DISCOVERY_RESTART_DELAY = 300L
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
                    // disturb a working Bluetooth connection. Auto counts here
                    // because plugging the display in should switch it over.
                    if (isWiredOrAutoProtocol() && !isConnectionActive()) {
                        // 重新插入属于新的枚举，可以再问一次 USB 授权。
                        UsbDisplayConnection.allowPermissionPromptAgain()
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

    /**
     * 前台保活是否生效。无障碍服务未开启时，连接成功后由服务自身提升为
     * connectedDevice 前台服务；生效期间客户端数归零不主动断开——界面退出
     * 只是用户离开设置页/阅读器，不代表要停止点显器。
     */
    @Volatile
    internal var mDisplayKeepAliveActive = false

    var mDisplayProperties: BrailleDisplayProperties? = null
    private lateinit var mTablesDir: File
    var mDataFileState = FILES_NOT_EXTRACTED
    internal var mPendingBluetoothAddress: String? = null

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
    internal var mDiscoverySessionActive = false
    /** True when this service, rather than another app, started discovery. */
    internal var mDiscoveryStartedByUs = false
    internal var mDiscoverySessionDeadline: Long = 0

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
        // 诊断包：级别硬编码进 APK，先于偏好项生效。
        if (BuildConfig.DIAG_LOG_LEVEL > 0) {
            return BuildConfig.DIAG_LOG_LEVEL
        }
        val raw: String? = SettingsStore.get(this)
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
        SettingsStore.get(this)
                .registerOnSharedPreferenceChangeListener(
                        mProtocolPrefListener)
        SettingsStore.get(this)
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
        SettingsStore.get(this)
                .unregisterOnSharedPreferenceChangeListener(
                        mProtocolPrefListener)
        SettingsStore.get(this)
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
        unregisterReceiver(mBroadcastReceiver)
        unregisterReceiver(mInputDeviceReceiver)
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
            // 前台保活期间不解绑即断开：服务已是 started，客户端暂时归零
            // 只说明界面退出，连接应当保留到显式断开或点显器掉线为止。
            if (!mDisplayKeepAliveActive) {
                cancelPendingReconnect()
                disconnectBraille()
            }
        }
        return true
    }

    override fun onRebind(intent: Intent?) {
        autoConnectBraille()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 连接建立时通过 startForegroundService 拉起本服务，必须在 5 秒内
        // 补上 startForeground；其它启动来源不需要前台状态。
        if (intent?.action == ACTION_DISPLAY_KEEP_ALIVE) {
            postDisplayKeepAliveNotification()
        }
        return START_NOT_STICKY
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
        promoteDisplayForeground()
    }

    /** Records one displayDots delivery for the refresh telemetry. */
    private fun recordDots(patterns: ByteArray, route: Int) {
        mDotsCalls.incrementAndGet()
        mDotsLastCells = patterns.size
        mDotsLastDigest = patterns.contentHashCode()
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
        mReadThread?.disconnect()
        // Keep the reference until HidDisplayConnection reports that the
        // system controller has really been released. Clearing it here
        // lets a new connection race the asynchronous teardown.
        mHidConnection?.disconnect()
        mWujieConnection?.disconnect()
        // Like HID, this reports back asynchronously once the native
        // driver has actually stopped.
        mUsbConnection?.disconnect()
        mHidTried = false
        // An explicit disconnect starts a new connection cycle, so a
        // controller release conflict may be retried once in that cycle.
        mHidRetryCount = 0
        dropDisplayForeground()
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
        } else if (isAutoProtocol() && isUsbBrailleDeviceAttached()) {
            // Auto: a plugged display wins. The probe below only reads the USB
            // device list against the same brltty VID/PID table the wired path
            // uses, so it can not report a match that connectUsb() would reject.
            Timber.i("Auto protocol: attached braille display found, using USB")
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
        if (!mHidTried && !shouldSkipHidForWujie()) {
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
        // 先落局部再赋值：属性不参与智能转换，也避免两次读之间被别的线程改掉。
        val readThread = ReadThread(this, mTablesDir,
                mPendingBluetoothAddress)
        mReadThread = readThread
        mPendingBluetoothAddress = null
        readThread.start()
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
        // 临时诊断：厂商私有 BLE 已被 DISABLE_VENDOR_BLE_TEMP 关掉，
        // 一律按「不是 Wujie」处理，让连接落到标准 Braille HID / RFCOMM。
        if (DISABLE_VENDOR_BLE_TEMP) {
            Timber.i("Vendor BLE disabled by DISABLE_VENDOR_BLE_TEMP; "
                    + "falling back to standard HID/RFCOMM")
            return false
        }
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

    /**
     * True when this connection cycle targets a Wujie/Orbit display and the
     * serial SPP path must be used instead of the standard Braille HID path.
     *
     * <p>Mirrors [isWujieTarget] but deliberately ignores
     * [DISABLE_VENDOR_BLE_TEMP]: that switch only gates the vendor GATT
     * protocol, while this decision is about HID versus RFCOMM. The firmware
     * declares an HID input report but never sends it, so HID would give a
     * display-only link with dead keys; SPP carries both directions.
     */
    private fun shouldSkipHidForWujie(): Boolean {
        if (!PREFER_RFCOMM_FOR_WUJIE) {
            return false
        }
        val pending = mPendingBluetoothAddress
        if (pending != null) {
            if (isWujieName(readRemoteDeviceName(pending))) {
                return true
            }
            val bonded = findNamedWujieAddress()
            return bonded != null && bonded.equals(pending, ignoreCase = true)
        }
        return findNamedWujieAddress() != null
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
        return SettingsStore.get(this)
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

    // 注册本身已按 API 33 分版本：33+ 用带 flag 的重载，低版本走旧重载。旧重载那条分支
    // 只在 SDK < 33 的设备上执行，而「必须指定 RECEIVER_EXPORTED/NOT_EXPORTED」是
    // targetSdk 34+ 才引入的约束，低版本根本不存在，因此这是 lint 的误报，不是漏配。
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
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
