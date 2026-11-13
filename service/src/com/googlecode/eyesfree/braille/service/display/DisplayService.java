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

package com.googlecode.eyesfree.braille.service.display;
import android.Manifest;
import android.accessibilityservice.BrailleDisplayController;
import android.annotation.SuppressLint;
import android.os.Build;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbManager;
import android.preference.PreferenceManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties;
import com.googlecode.eyesfree.braille.display.BrailleInputEvent;
import com.googlecode.eyesfree.braille.display.IBrailleService;
import com.googlecode.eyesfree.braille.display.IBrailleServiceCallback;
import com.googlecode.eyesfree.braille.service.BuildConfig;
import com.googlecode.eyesfree.braille.service.R;
import com.googlecode.eyesfree.braille.utils.ZipResourceExtractor;
import com.googlecode.eyesfree.utils.LogUtils;
import com.googlecode.eyesfree.utils.LogFileWriter;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import timber.log.Timber;

/**
 * An Andorid service that connects to braille displays and exposes a unified
 * interface to other Apps.
 *
 * DisplayService contains ReadThread.
 * ReadThread contains DriverThread and DeviceFinder.
 * DriverThread contains BrlttyWrapper.
 */
public class DisplayService extends Service
        implements DriverThread.OnInputEventListener {
    private static final String LOG_TAG = DisplayService.class.getSimpleName();


    /**
     * Listener thread which reads from the braille device and forwards
     * data to the driver.
     */
    /** Written in main thread, read in IPC threads. */
    private volatile ReadThread mReadThread;
    /** The list of registered clients. */
    private final RemoteCallbackList<IBrailleServiceCallback> mClients =
            new RemoteCallbackList<IBrailleServiceCallback>();
    private final ServiceImpl mServiceImpl = new ServiceImpl();
    private final BroadcastReceiver mBroadcastReceiver =
        new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    // Try reconnecting to Braille device on screen on action.
                    // This is done so that if a display was turned on
                    // after the service is started, it will be
                    // connected the next time the user unlocks the device.
                    mHandler.unscheduleDisconnect();
                    autoConnectBraille();
                } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    // Disconnect the display after the screen goes off, but
                    // wait a bit since it takes some time to reconnect, which
                    // is going to be frustrating if the screen went off
                    // accidentally.
                    stopDiscoverySession("screen off");
                    mHandler.scheduleDisconnect(SCREEN_OFF_DISCONNECT_DELAY);
                } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(
                                action)) {
                    connectFromBroadcastIntent(intent);
                } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(
                                action)) {
                    // The display may come back after being switched on or
                    // after the Bluetooth link was re-established. Retry
                    // without waiting for the next scheduled attempt.
                    retryWhenBluetoothDeviceAppears(intent);
                } else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    handleDiscoveredDevice(intent);
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(
                                action)) {
                    handleDiscoveryFinished();
                } else if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(
                                action)) {
                    Timber.d("Bluetooth discovery started");
                } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(
                                action)) {
                    int state = intent.getIntExtra(
                            BluetoothAdapter.EXTRA_STATE,
                            BluetoothAdapter.ERROR);
                    if (state == BluetoothAdapter.STATE_ON) {
                        Timber.i("Bluetooth enabled; connecting braille display");
                        autoConnectBraille();
                    } else if (state == BluetoothAdapter.STATE_OFF
                            || state == BluetoothAdapter.STATE_TURNING_OFF) {
                        stopDiscoverySession("Bluetooth disabled");
                        cancelAutoReconnect();
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    // Wired displays only: a USB stick or keyboard must not
                    // disturb a working Bluetooth connection.
                    if (isWiredProtocol() && !isConnectionActive()) {
                        Timber.i("USB device attached; trying wired connection");
                        autoConnectBraille();
                    }
                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    if (mUsbConnection != null
                            || (isUsbProtocol()
                                && mHidConnection != null)) {
                        Timber.i("USB device detached; disconnecting display");
                        disconnectBraille();
                    }
                } else {
                    Log.w(LOG_TAG, "Unexpected broadcast " + action);
            }
        }
    };

    /**
     * Hidden input-stack broadcast. Kept in a separate non-exported receiver
     * because Bluetooth broadcasts are sent by the Bluetooth process rather
     * than the system UID; those must be registered as exported, while this
     * one does not need to accept broadcasts from other apps.
     */
    private final BroadcastReceiver mInputDeviceReceiver =
            new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_INPUT_DEVICE_ADDED.equals(intent.getAction())) {
                retryHidWhenInputDeviceAppears();
            }
        }
    };

    private final MainHandler mHandler = new MainHandler(this);

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTED = 1;
    private int mConnectionState = STATE_DISCONNECTED;
    private String mConnectionProgress = null;
    /**
     * Delay between the screen goes off and the display gets automatically
     * disconnected.
     */
    private static final long SCREEN_OFF_DISCONNECT_DELAY = 7000;
    private static final long HID_RETRY_DELAY = 300;
    /**
     * Delays for background reconnects after the display drops or a
     * connection attempt fails. The delay doubles on each consecutive
     * failure, up to the maximum, so a sleeping or out-of-range display is
     * not polled aggressively.
     */
    private static final long AUTO_RECONNECT_INITIAL_DELAY = 3000;
    private static final long AUTO_RECONNECT_MAX_DELAY = 30000;
    /**
     * TalkBack-like discovery window. A bonded display is normally connected
     * directly, but while it is switched off that attempt fails. Keeping a
     * short scan open afterwards lets a display that has just been powered on
     * be found immediately, without leaving Bluetooth inquiry running all day.
     */
    private static final long DISCOVERY_SESSION_DURATION = 30000;
    private static final long DISCOVERY_RESTART_DELAY = 300;
    /**
     * Hidden but stable system broadcast sent when the input stack registers a
     * new device. A Braille HID display shows up here after the Bluetooth HID
     * profile connects, which is usually later than the user's protocol choice.
     */
    private static final String ACTION_INPUT_DEVICE_ADDED =
            "android.hardware.input.action.INPUT_DEVICE_ADDED";
    /**
     * How long to wait for the accessibility service to publish a
     * BrailleDisplayController before reporting it as unavailable. The
     * controller is briefly absent whenever the accessibility service is
     * rebound, so an immediate error is usually misleading.
     */
    private static final long HID_CONTROLLER_WAIT_TIMEOUT = 2500;
    private BrailleDisplayProperties mDisplayProperties;
    private File mTablesDir;
    private static final int FILES_ERROR = -1;
    private static final int FILES_NOT_EXTRACTED = 0;
    private static final int FILES_EXTRACTED = 1;
    private int mDataFileState = FILES_NOT_EXTRACTED;
    /** Set to {@code true} if a connect request comes in while
     * we are still disconnecting.
     */
    private boolean mConnectPending;
    private String mPendingBluetoothAddress;

    /**
     * HID connection (Braille HID standard via BrailleDisplayController).
     */
    private volatile HidDisplayConnection mHidConnection;
    /**
     * Custom BLE GATT connection used by the Wujie / Orbit 30-cell display.
     */
    private volatile WujieGattConnection mWujieConnection;
    /** Wired USB connection (brltty USB backend), null unless selected. */
    private volatile UsbDisplayConnection mUsbConnection;
    /**
     * 刷新链路心跳。冻结问题时只看三件事：上层有没有继续送点阵、
     * 服务有没有收到、最终走了哪条连接路径。每 5 秒一行，量很小。
     */
    private static final long DOTS_TELEMETRY_INTERVAL = 5000;
    private static final int ROUTE_NONE = 0;
    private static final int ROUTE_USB = 1;
    private static final int ROUTE_HID = 2;
    private static final int ROUTE_WUJIE = 3;
    private static final int ROUTE_READ_THREAD = 4;
    private final AtomicLong mDotsCalls = new AtomicLong();
    private volatile int mDotsLastDigest;
    private volatile int mDotsLastCells;
    private volatile int mDotsLastRoute = ROUTE_NONE;
    private long mDotsReportedCalls;
    private final Runnable mDotsTelemetry = new Runnable() {
        @Override
        public void run() {
            long calls = mDotsCalls.get();
            boolean hasConnection = mUsbConnection != null
                    || mHidConnection != null
                    || mWujieConnection != null
                    || mReadThread != null;
            if (hasConnection || calls > mDotsReportedCalls) {
                LogUtils.i(DisplayService.this,
                        "dots stats: calls=%d (+%d) route=%s digest=%08X "
                        + "cells=%d usb=%s usbActive=%s hid=%s wujie=%s read=%s",
                        calls, calls - mDotsReportedCalls,
                        routeName(mDotsLastRoute), mDotsLastDigest,
                        mDotsLastCells,
                        mUsbConnection != null, isUsbActive(),
                        mHidConnection != null, mWujieConnection != null,
                        mReadThread != null);
            }
            mDotsReportedCalls = calls;
            mHandler.postDelayed(this, DOTS_TELEMETRY_INTERVAL);
        }
    };
    /** True after the vendor BLE path was attempted in this cycle. */
    private boolean mWujieTried;
    /** True after the brltty serial USB path was attempted in this cycle. */
    private boolean mUsbSerialTried;
    /** True after the system USB HID path was attempted in this cycle. */
    private boolean mUsbHidTried;
    /** Whether mHidConnection is the wired USB HID path rather than Bluetooth. */
    private boolean mHidConnectionIsUsb;
    /**
     * True after an HID connection attempt, to avoid retrying HID in a loop
     * when the connected display only supports classic RFCOMM.
     */
    private volatile boolean mHidTried;
    /** Number of HID retries already used by the current connection cycle. */
    private int mHidRetryCount;
    /**
     * True while waiting for the accessibility service to publish the HID
     * controller. {@link HidBridge} notifies us as soon as it appears.
     */
    private boolean mWaitingForHidController;
    /**
     * Error returned by the last HID attempt, or {@code 0} if no attempt has
     * failed yet. Used to replace the generic failure message with an
     * actionable one (for example, "display is not in Braille HID mode").
     */
    private int mLastHidError;
    /** Number of consecutive automatic reconnect attempts. */
    private int mAutoReconnectAttempt;
    /**
     * True after the user explicitly disconnected the display. Automatic
     * retries stay off until the next explicit connect trigger (opening the
     * app, requesting a connect, or turning the screen on).
     */
    private boolean mAutoReconnectSuppressed;
    /**
     * True while the current explicit automatic-connect trigger is still
     * allowed to open its one discovery window. It is cleared as soon as that
     * window starts, so periodic safety retries do not turn into continuous
     * scanning.
     */
    private boolean mDiscoveryAllowedForCycle;
    /** True while this service owns the current Bluetooth discovery session. */
    private boolean mDiscoverySessionActive;
    /** True when this service, rather than another app, started discovery. */
    private boolean mDiscoveryStartedByUs;
    private long mDiscoverySessionDeadline;

    /** Reconnects with a different protocol when the setting changes. */
    private final SharedPreferences.OnSharedPreferenceChangeListener
            mProtocolPrefListener =
        new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(
                    SharedPreferences prefs, String key) {
                if ("connection_protocol".equals(key)) {
                    String protocol = ConnectionProtocolPolicy.normalize(
                            prefs.getString(key, null));
                    mHandler.cancelHidRetry();
                    mHidRetryCount = 0;
                    resetConnectionAttemptFlags();
                    Timber.i("Connection protocol changed to %s", protocol);
                    if (mConnectionState == STATE_CONNECTED
                            || mReadThread != null || mHidConnection != null
                            || mWujieConnection != null
                            || mUsbConnection != null) {
                        // Reconnect with the new protocol only after the current
                        // connection has been fully torn down. HID disconnect is
                        // asynchronous (BrailleDisplayController.disconnect()), so
                        // the reconnect is triggered by the teardown callback
                        // (handleOnDisplayDisconnected) via mConnectPending.
                        mConnectPending = true;
                        disconnectBraille();
                    } else {
                        mHidTried = false;
                        connectBraille();
                    }
                }
            }
        };
    /**
     * Reacts to the auto-connect preference. When auto-connect is turned on,
     * an immediate connect attempt is made (subject to the usual connection
     * guards); turning it off does not tear down an existing connection, it
     * only suppresses future implicit connects.
     */
    private final SharedPreferences.OnSharedPreferenceChangeListener
            mAutoConnectPrefListener =
        new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(
                    SharedPreferences prefs, String key) {
                if ("auto_connect".equals(key) && prefs.getBoolean(key, true)) {
                    autoConnectBraille();
                } else if ("auto_connect".equals(key)) {
                    stopDiscoverySession("auto-connect disabled");
                    cancelAutoReconnect();
                }
            }
        };
    /**
     * Invoked by {@link HidBridge} when the accessibility service obtains a
     * (new) BrailleDisplayController, e.g. after the HID link dropped and was
     * re-established. Reconnects the HID display with the fresh controller.
     */
    private final Runnable mHidControllerChangeListener = new Runnable() {
        @Override
        public void run() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    boolean hidRequested = isBluetoothProtocol();
                    if (hidRequested && mWaitingForHidController) {
                        mWaitingForHidController = false;
                        mHandler.cancelHidControllerTimeout();
                        mHidTried = false;
                        Timber.i("HID controller published; resuming pending connection");
                        connectBraille();
                        return;
                    }
                    if (hidRequested && mConnectionState == STATE_DISCONNECTED
                            && mReadThread == null && mHidConnection == null
                            && mWujieConnection == null
                            && mUsbConnection == null) {
                        mHidTried = false;
                        autoConnectBraille();
                    }
                }
            });
        }
    };


    /**
     * Mirrors the accessibility service's log level and installs the shared
     * file sink. Both services run in the same process, so init is idempotent:
     * whoever starts first owns the single RollingFileLog.
     */
    private void configureProcessLogging() {
        LogUtils.setLogLevel(computeLogLevel());
        LogFileWriter.init(this);
    }

    /**
     * Mirrors PreferenceUtils.getLogLevel for early display-service starts.
     * A level chosen on the settings screen wins; otherwise both build types
     * start at INFO, matching R.string.pref_log_level_default. Debug builds
     * never run quieter than INFO so a tester report keeps the connection
     * lifecycle lines, but may still opt into VERBOSE.
     */
    private int computeLogLevel() {
        String raw = PreferenceManager.getDefaultSharedPreferences(this)
                .getString("log_level", null);
        if (raw != null) {
            try {
                int stored = Integer.parseInt(raw);
                return BuildConfig.DEBUG ? Math.min(stored, Log.INFO) : stored;
            } catch (NumberFormatException e) {
                // Fall through to the build default.
            }
        }
        return Log.INFO;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Route Timber into LogUtils so connection failures (including USB)
        // reach the in-app file log that users can share or copy.
        if (Timber.treeCount() == 0) {
            Timber.plant(new LogUtilsTimberTree());
        }
        // The display service can be the first component to start (for example
        // when a client binds it before the accessibility service). Install the
        // shared file sink here so USB diagnostics never depend on start order.
        configureProcessLogging();
        mTablesDir = getDir("keytables", Context.MODE_PRIVATE);
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(
                        mProtocolPrefListener);
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(
                        mAutoConnectPrefListener);
        registerBroadcastReceiver();
        HidBridge.setControllerChangeListener(
                this, mHidControllerChangeListener);
        ensureDataFiles();
        // P0 probe (debug builds only): confirm the prebuilt .so still exports
        // the USB driver table JNI entry point. Does not touch connection logic.
        if (BuildConfig.DEBUG) {
            BrlttyWrapper.logUsbDriverTableProbe();
        }
        mHandler.postDelayed(mDotsTelemetry, DOTS_TELEMETRY_INTERVAL);
        LogUtils.i(this, "Service started.");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(LOG_TAG, "Destroying service.");
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(
                        mProtocolPrefListener);
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(
                        mAutoConnectPrefListener);
        HidBridge.setControllerChangeListener(this, null);
        mHandler.unscheduleDisconnect();
        cancelPendingReconnect();
        mAutoReconnectSuppressed = true;
        stopDiscoverySession("service destroyed");
        cancelAutoReconnect();
        disconnectBraille();
        // Drop callbacks queued by a display that may already be torn down.
        mHandler.removeCallbacksAndMessages(null);
        if (mBroadcastReceiver != null) {
            unregisterReceiver(mBroadcastReceiver);
        }
        if (mInputDeviceReceiver != null) {
            unregisterReceiver(mInputDeviceReceiver);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.v(LOG_TAG, "onBind");
        autoConnectBraille();
        return mServiceImpl;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // 多客户端场景：仅当最后一个客户端解绑时才断开点显器。
        // 否则（设置页/阅读器/键盘帮助等临时客户端切走时）点显器会被
        // 断开再重连，每次切换界面都触发 BrailleBackService 的
        // "断开 + 连接" 提示音（DisplayService 仍被主客户端绑定着）。
        int clientCount = mClients.beginBroadcast();
        mClients.finishBroadcast();
        if (clientCount <= 0) {
            cancelPendingReconnect();
            disconnectBraille();
        }
        return true;
    }

    @Override
    public void onRebind(Intent intent) {
        autoConnectBraille();
    }

    // NOTE: The methods in this class are invoked on threads created by the
    // IPC system and not the main application thread.
    private class ServiceImpl extends IBrailleService.Stub {
        @Override
        public boolean registerCallback(
            final IBrailleServiceCallback callback) {
            if (callback == null) {
                Log.e(LOG_TAG, "Registering null callback");
                return false;
            }
            mHandler.registerCallback(callback);
            return true;
        }

        @Override
        public void unregisterCallback(IBrailleServiceCallback callback) {
            if (callback == null) {
                Log.e(LOG_TAG, "Unregistering null callback");
                return;
            }
            if (!mClients.unregister(callback)) {
                Log.w(LOG_TAG, "Failed to unregister callback" + callback);
            }
        }

        @Override
        public void displayDots(final byte[] patterns) {
            if (patterns == null) {
                Log.e(LOG_TAG, "null dot patterns");
                return;
            }
            // Route output to the HID connection when it is the active path
            // (HID uses HidDisplayConnection instead of ReadThread).
            UsbDisplayConnection usbConnection = mUsbConnection;
            if (usbConnection != null) {
                recordDots(patterns, ROUTE_USB);
                usbConnection.writeWindow(patterns);
                return;
            }
            HidDisplayConnection hidConnection = mHidConnection;
            if (hidConnection != null) {
                recordDots(patterns, ROUTE_HID);
                hidConnection.writeWindow(patterns);
                return;
            }
        WujieGattConnection wujieConnection = mWujieConnection;
        if (wujieConnection != null) {
            recordDots(patterns, ROUTE_WUJIE);
            wujieConnection.writeWindow(patterns);
            return;
        }
            ReadThread localReadThread = mReadThread;
            if (localReadThread == null) {
                recordDots(patterns, ROUTE_NONE);
                return;
            }
            DriverThread localDriverThread = localReadThread.getDriverThread();
            if (localDriverThread == null) {
                recordDots(patterns, ROUTE_NONE);
                return;
            }
            recordDots(patterns, ROUTE_READ_THREAD);
            localDriverThread.writeWindow(patterns);
        }

        @Override
        public void poll() {
            mHandler.connectBrailleExplicit();
        }

        @Override
        public void disconnect() {
            mHandler.disconnectBraille();
        }
    }

    /*package*/ void onDisplayConnected(
        final BrailleDisplayProperties properties) {
        mHandler.onDisplayConnected(properties);
    }

    /** Records one displayDots delivery for the refresh telemetry. */
    private void recordDots(byte[] patterns, int route) {
        mDotsCalls.incrementAndGet();
        mDotsLastCells = patterns.length;
        mDotsLastDigest = Arrays.hashCode(patterns);
        mDotsLastRoute = route;
    }

    /** True when the wired USB path still owns a live driver thread. */
    private boolean isUsbActive() {
        UsbDisplayConnection connection = mUsbConnection;
        return connection != null && connection.isDriverActive();
    }

    private static String routeName(int route) {
        switch (route) {
            case ROUTE_USB:
                return "usb";
            case ROUTE_HID:
                return "hid";
            case ROUTE_WUJIE:
                return "wujie";
            case ROUTE_READ_THREAD:
                return "readthread";
            default:
                return "none";
        }
    }

    /*package*/ void onDisplayDisconnected() {
        mHandler.onDisplayDisconnected();
    }

    /*package*/ void setConnectionProgress(String description) {
        mHandler.setConnectionProgress(description);
    }

    /**
     * Forwards input events from the driver thread to be broadcast
     * from the main service thread.
     */
    @Override
    public void onInputEvent(final BrailleInputEvent event) {
        mHandler.onInputEvent(event);
    }

    /**
     * Disconnects the service from the currently connected braille device,
     * sends notification to the clients about the state change.
     */
    private void disconnectBraille() {
        disconnectBraille(false);
    }

    /**
     * Disconnects the display. A user-initiated disconnect suppresses the
     * background reconnect loop until the next explicit connect trigger.
     */
    private void disconnectBraille(boolean userInitiated) {
        if (userInitiated) {
            mAutoReconnectSuppressed = true;
            mAutoReconnectAttempt = 0;
            cancelAutoReconnect();
        }
        // Unexpected drops may be the display being switched off and back on;
        // allow one discovery window when the teardown completes. A user
        // initiated disconnect stays suppressed.
        mDiscoveryAllowedForCycle = !userInitiated;
        stopDiscoverySession("disconnecting display");
        Log.i(LOG_TAG, "Disconnecting braille display");
        Timber.i("Disconnecting braille display (pending=%s)", mConnectPending);
        clearHidControllerWait();
        clearConnectionProgress();
        mConnectionState = STATE_DISCONNECTED;
        mDisplayProperties = null;
        broadcastConnectionState();
        if (mReadThread != null) {
            mReadThread.disconnect();
        }
        if (mHidConnection != null) {
            // Keep the reference until HidDisplayConnection reports that the
            // system controller has really been released. Clearing it here
            // lets a new connection race the asynchronous teardown.
            mHidConnection.disconnect();
        }
        if (mWujieConnection != null) {
            mWujieConnection.disconnect();
        }
        if (mUsbConnection != null) {
            // Like HID, this reports back asynchronously once the native
            // driver has actually stopped.
            mUsbConnection.disconnect();
        }
        mHidTried = false;
        // An explicit disconnect starts a new connection cycle, so a
        // controller release conflict may be retried once in that cycle.
        mHidRetryCount = 0;
    }

    /** Cancels a reconnect requested by an earlier transition. */
    private void cancelPendingReconnect() {
        mConnectPending = false;
        mHidRetryCount = 0;
        mHandler.cancelHidRetry();
    }

    /**
     * Clears any in-flight connection progress and notifies clients, so the
     * settings UI is not left showing a stale "connecting..." message and
     * re-renders the real connection state.
     */
    private void clearConnectionProgress() {
        if (mConnectionProgress != null) {
            mConnectionProgress = null;
            broadcastConnectionProgress();
        }
    }

    /**
     * Waits for the accessibility service to publish a
     * BrailleDisplayController. The controller is transiently absent whenever
     * the service is rebound, so the UI shows a neutral waiting message and
     * {@link HidBridge} resumes the connection as soon as it appears. If the
     * controller never arrives, the bounded timeout reports the real error.
     */
    private void waitForHidController() {
        if (mWaitingForHidController) {
            return;
        }
        mWaitingForHidController = true;
        Timber.i("Waiting up to %d ms for the HID controller",
                HID_CONTROLLER_WAIT_TIMEOUT);
        mHandler.setConnectionProgress(
                getString(R.string.connprog_hid_waiting_controller));
        mHandler.scheduleHidControllerTimeout(HID_CONTROLLER_WAIT_TIMEOUT);
    }

    /** Cancels a pending wait for the HID controller. */
    private void clearHidControllerWait() {
        if (mWaitingForHidController) {
            mWaitingForHidController = false;
            mHandler.cancelHidControllerTimeout();
        }
    }

    /**
     * Tries to connect to the braille device, on success sends notification to
     * registered clients.
     */
    private void connectBraille() {
        if (mConnectionState == STATE_CONNECTED) {
            return;
        }

        if (mDataFileState != FILES_EXTRACTED) {
            return;
        }

        if (mReadThread != null || mHidConnection != null
                || mWujieConnection != null || mUsbConnection != null) {
            // We must wait until the previous attempt is done before
            // retrying a connect, so just set the flag here.
            mConnectPending = true;
            return;
        }

        // RFCOMM/HID connections are faster and more reliable when Bluetooth
        // inquiry is not competing with them.
        stopDiscoverySession("connecting display");
        String protocol = getConnectionProtocol();
        Timber.i("Starting connection with protocol %s", protocol);
        if (isUsbProtocol()) {
            connectUsbUnified();
        } else {
            connectBluetooth();
        }
    }

    /** Tries the wireless transports in order: Wujie BLE, HID, then RFCOMM. */
    private void connectBluetooth() {
        if (!mWujieTried && mPendingBluetoothAddress == null) {
            String wujieAddress = findNamedWujieAddress();
            if (wujieAddress != null) {
                mWujieTried = true;
                connectWujie(wujieAddress);
                return;
            }
        }
        if (!mHidTried) {
            if (tryConnectHid()) {
                // A Braille HID connection attempt was started (async).
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
                    && !HidBridge.isAvailable()) {
                // The controller is briefly null while the accessibility service
                // rebinds. Wait instead of falling back to RFCOMM prematurely.
                waitForHidController();
                return;
            }
        }
        // HID failed / is unavailable: classic Bluetooth serial (RFCOMM).
        mReadThread = new ReadThread(this, mTablesDir,
                mPendingBluetoothAddress);
        mPendingBluetoothAddress = null;
        mReadThread.start();
    }

    /**
     * Tries to start a Braille HID standard (usage page 0x41) connection.
     * Returns true if an HID attempt was started (asynchronously), false to
     * fall back to classic RFCOMM.
     */
    // BrailleDisplayController and HidBridge require API 35; callers reach
    // this method only after HidBridge.isAvailable() has checked the SDK level.
    @SuppressLint("NewApi")
    private boolean tryConnectHid() {
        if (mHidTried) {
            return false;
        }
        if (!HidBridge.isAvailable()) {
            Timber.w("HID requested but BrailleDisplayController is unavailable (sdk=%d)",
                    Build.VERSION.SDK_INT);
            return false;
        }
        return startHidAttempt();
    }

    /** Retries an HID attempt after the controller release conflict. */
    private boolean retryHidConnect() {
        if (mHidTried || !HidBridge.isAvailable()) {
            return false;
        }
        return startHidAttempt();
    }

    @SuppressLint("NewApi")
    private boolean startHidAttempt() {
        BrailleDisplayController controller = HidBridge.getController();
        if (controller == null) {
            Timber.w("Aborting HID attempt: controller became null");
            return false;
        }
        String address = mPendingBluetoothAddress;
        if (address == null) {
            List<DeviceFinder.DeviceInfo> devices =
                    new DeviceFinder(this).findDevices();
            if (devices.isEmpty()) {
                return false;
            }
            try {
                address = devices.get(0).getBluetoothDevice().getAddress();
            } catch (SecurityException e) {
                Log.w(LOG_TAG,
                        "BLUETOOTH_CONNECT permission denied reading address",
                        e);
                return false;
            }
            if (address == null) {
                return false;
            }
        }
        mHidTried = true;
        mHidConnectionIsUsb = false;
        mLastHidError = 0;
        mPendingBluetoothAddress = null;
        mHandler.setConnectionProgress(getString(R.string.connprog_trying_hid));
        HidDisplayConnection connection = new HidDisplayConnection(this,
                getResources(), mTablesDir, mHidListener, this);
        mHidConnection = connection;
        Timber.i("Creating HID connection attempt for %s", address);
        connection.connect(controller, address);
        return true;
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
    private String getHidFailureMessage() {
        if ((mLastHidError & 2) != 0) {
            return getString(R.string.connprog_hid_not_found);
        }
        if (mLastHidError != 0) {
            return getString(R.string.connprog_hid_failed_code,
                    mLastHidError);
        }
        return getString(R.string.connprog_hid_failed);
    }

    /**
     * Failure copy for the wired USB HID path. Error -7 means no attached USB
     * device exposes a HID interface; -8 means the USB permission was denied.
     * Both are expected states when nothing is plugged in, so the wording must
     * tell the user what to do instead of just showing an error code.
     */
    private String getUsbHidFailureMessage(int error) {
        if (error == -7) {
            return getString(R.string.connprog_usb_hid_not_found);
        }
        if (error == -8) {
            return getString(R.string.connprog_usb_hid_permission_denied);
        }
        return getString(R.string.connprog_usb_hid_failed, error);
    }

    /** Returns the user-selected connection protocol. */
    private String getConnectionProtocol() {
        String value = PreferenceManager.getDefaultSharedPreferences(this)
                .getString(ConnectionProtocolPolicy.PREFERENCE_KEY,
                        ConnectionProtocolPolicy.BLUETOOTH);
        return ConnectionProtocolPolicy.normalize(value);
    }

    private boolean isBluetoothProtocol() {
        return ConnectionProtocolPolicy.BLUETOOTH.equals(
                getConnectionProtocol());
    }

    private boolean isUsbProtocol() {
        return ConnectionProtocolPolicy.USB.equals(getConnectionProtocol());
    }

    /** Wired USB must keep retrying even when Bluetooth is off. */
    private boolean isWiredProtocol() {
        return isUsbProtocol();
    }

    private boolean isHidRequested() {
        return isBluetoothProtocol();
    }

    /** Resets the per-cycle transport fallback state. */
    private void resetConnectionAttemptFlags() {
        mHidTried = false;
        mHidRetryCount = 0;
        mLastHidError = 0;
        mWujieTried = false;
        mUsbSerialTried = false;
        mUsbHidTried = false;
        mHidConnectionIsUsb = false;
    }

    /**
     * Tries to connect to the braille display, but only if automatic
     * connection is enabled in the preferences. Used for implicit
     * (non-user-initiated) connect triggers such as screen-on, (re)bind,
     * device pairing and HID controller changes. Explicit connects via
     * {@code poll()} / {@code disconnect()} always bypass this check so that
     * the volume-key toggle keeps working regardless of the setting.
     */
    private void autoConnectBraille() {
        mAutoReconnectSuppressed = false;
        mAutoReconnectAttempt = 0;
        mHandler.cancelAutoReconnect();
        if (PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("auto_connect", true)) {
            if (!isConnectionActive()) {
                resetConnectionAttemptFlags();
            }
            mDiscoveryAllowedForCycle = true;
            connectBraille();
        } else {
            mDiscoveryAllowedForCycle = false;
            stopDiscoverySession("auto-connect disabled");
        }
    }

    /** Starts a connection explicitly requested by a client. */
    private void startExplicitConnect() {
        mAutoReconnectSuppressed = false;
        mAutoReconnectAttempt = 0;
        mHandler.cancelAutoReconnect();
        if (!isConnectionActive()) {
            resetConnectionAttemptFlags();
        }
        mDiscoveryAllowedForCycle = true;
        connectBraille();
    }

    /**
     * Retries when a known braille display establishes its Bluetooth link.
     * Only acts while nothing is connected or connecting, so unrelated
     * devices (headsets, mice, ...) cannot disturb an established display
     * connection.
     */
    private void retryWhenBluetoothDeviceAppears(Intent intent) {
        if (isConnectionActive()) {
            return;
        }
        BluetoothDevice device = intent.getParcelableExtra(
                BluetoothDevice.EXTRA_DEVICE);
        if (device != null) {
            String address = null;
            try {
                address = device.getAddress();
            } catch (SecurityException e) {
                Timber.w(e,
                        "BLUETOOTH_CONNECT permission denied reading address");
            }
            if (address != null
                    && !new DeviceFinder(this).isKnownDevice(address)) {
                Timber.i("Ignoring Bluetooth link for unrelated device %s",
                        address);
                return;
            }
        }
        prepareAutomaticRetry("Bluetooth link appeared");
    }

    /** Retries an HID attempt when a new input device is registered. */
    private void retryHidWhenInputDeviceAppears() {
        // Wired USB HID devices also arrive as InputDevice additions, even
        // though they are not part of the Bluetooth HID protocol set.
        boolean wiredUsbHid = isUsbProtocol();
        if ((!isHidRequested() && !wiredUsbHid) || isConnectionActive()) {
            return;
        }
        prepareAutomaticRetry("HID input device appeared");
    }

    private void prepareAutomaticRetry(String reason) {
        if (isConnectionActive()) {
            return;
        }
        mHandler.cancelHidControllerTimeout();
        mWaitingForHidController = false;
        resetConnectionAttemptFlags();
        Timber.i("%s; retrying display connection", reason);
        autoConnectBraille();
    }

    private boolean isConnectionActive() {
        return mConnectionState == STATE_CONNECTED
                || mReadThread != null
                || mHidConnection != null
                || mWujieConnection != null
                || mUsbConnection != null;
    }

    /**
     * Schedules the next background reconnect attempt after a failed or
     * dropped connection. Retrying is intentionally tied to the lifecycle of
     * the service: it only runs while a client is bound, the screen is
     * interactive, auto-connect is enabled and the user has not explicitly
     * disconnected the display.
     */
    private void scheduleAutoReconnectIfNeeded() {
        if (!shouldAutoReconnect()) {
            Timber.v("Not scheduling auto reconnect (not eligible)");
            return;
        }
        if (mHandler.hasAutoReconnectScheduled()) {
            return;
        }
        boolean discoveryStarted = false;
        if (mDiscoveryAllowedForCycle) {
            mDiscoveryAllowedForCycle = false;
            discoveryStarted = startDiscoveryWindow(
                    "connection attempt failed");
        }
        int attempt = mAutoReconnectAttempt++;
        // While the discovery window is open, wait for a device to appear
        // instead of cancelling the scan with an RFCOMM page three seconds
        // later. The ordinary backoff resumes once the window closes.
        mHandler.scheduleAutoReconnect(attempt,
                discoveryStarted ? DISCOVERY_SESSION_DURATION : -1);
    }

    private boolean shouldAutoReconnect() {
        if (!PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("auto_connect", true)) {
            return false;
        }
        if (mAutoReconnectSuppressed || !haveClients()) {
            return false;
        }
        if (mDataFileState != FILES_EXTRACTED) {
            return false;
        }
        if (isConnectionActive() || mConnectPending
                || mWaitingForHidController) {
            return false;
        }
        if (!isScreenInteractive()) {
            return false;
        }
        if (isWiredProtocol()) {
            return true;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        try {
            return adapter != null && adapter.isEnabled();
        } catch (SecurityException e) {
            Timber.w(e,
                    "BLUETOOTH_CONNECT permission denied checking adapter");
            return false;
        }
    }

    private boolean isScreenInteractive() {
        PowerManager powerManager =
                (PowerManager) getSystemService(Context.POWER_SERVICE);
        return powerManager == null || powerManager.isInteractive();
    }

    private boolean hasBluetoothScanPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean shouldStartDiscovery() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        boolean adapterEnabled = false;
        if (adapter != null) {
            try {
                adapterEnabled = adapter.isEnabled();
            } catch (SecurityException e) {
                Timber.w(e, "BLUETOOTH_CONNECT permission denied checking adapter");
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
                hasBluetoothScanPermission());
    }

    /** Opens (or extends) the short discovery window used after a failed connect. */
    private boolean startDiscoveryWindow(String reason) {
        if (!shouldStartDiscovery()) {
            Timber.v("Not starting discovery (%s): not eligible", reason);
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (mDiscoverySessionActive && now < mDiscoverySessionDeadline) {
            mDiscoverySessionDeadline = now + DISCOVERY_SESSION_DURATION;
            mHandler.scheduleDiscoveryTimeout(DISCOVERY_SESSION_DURATION);
            Timber.d("Extending Bluetooth discovery window (%s)", reason);
            startDiscoveryInternal(reason);
            return true;
        }
        mDiscoverySessionActive = true;
        mDiscoveryStartedByUs = false;
        mDiscoverySessionDeadline = now + DISCOVERY_SESSION_DURATION;
        mHandler.scheduleDiscoveryTimeout(DISCOVERY_SESSION_DURATION);
        Timber.i("Starting %d ms Bluetooth discovery window (%s)",
                DISCOVERY_SESSION_DURATION / 1000, reason);
        startDiscoveryInternal(reason);
        return mDiscoverySessionActive;
    }

    private void startDiscoveryInternal(String reason) {
        if (!mDiscoverySessionActive) {
            return;
        }
        if (!shouldStartDiscovery()) {
            stopDiscoverySession("discovery no longer eligible");
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            stopDiscoverySession("no Bluetooth adapter");
            return;
        }
        try {
            if (adapter.isDiscovering()) {
                Timber.d("Bluetooth discovery already active (%s)", reason);
                return;
            }
            if (adapter.startDiscovery()) {
                mDiscoveryStartedByUs = true;
                Timber.i("Bluetooth discovery started (%s)", reason);
            } else {
                mDiscoveryStartedByUs = false;
                Timber.w("Bluetooth discovery was not started (%s)", reason);
            }
        } catch (SecurityException e) {
            Timber.w(e, "BLUETOOTH_SCAN permission denied; stopping discovery");
            stopDiscoverySession("scan permission denied");
        }
    }

    private void handleDiscoveryFinished() {
        if (!mDiscoverySessionActive) {
            return;
        }
        mDiscoveryStartedByUs = false;
        long now = SystemClock.uptimeMillis();
        if (now >= mDiscoverySessionDeadline) {
            stopDiscoverySession("discovery window expired");
            return;
        }
        if (!shouldStartDiscovery()) {
            stopDiscoverySession("discovery no longer eligible");
            return;
        }
        Timber.d("Discovery cycle finished; restarting within window");
        mHandler.scheduleDiscoveryRestart(DISCOVERY_RESTART_DELAY);
    }

    private void handleDiscoveredDevice(Intent intent) {
        if (!mDiscoverySessionActive || isConnectionActive()) {
            return;
        }
        BluetoothDevice device = intent.getParcelableExtra(
                BluetoothDevice.EXTRA_DEVICE);
        if (device == null) {
            return;
        }
        String address = null;
        String name = null;
        try {
            address = device.getAddress();
            name = device.getName();
        } catch (SecurityException e) {
            Timber.w(e, "BLUETOOTH_CONNECT permission denied reading discovery result");
        }
        if (address == null || !new DeviceFinder(this).isKnownDevice(address)) {
            Timber.v("Discovery found unrelated device %s (%s)", name, address);
            return;
        }
        Timber.i("Discovered known braille display %s (%s); connecting",
                name, address);
        stopDiscoverySession("known display discovered");
        mPendingBluetoothAddress = address;
        mHidTried = false;
        mWujieTried = false;
        mHidRetryCount = 0;
        mLastHidError = 0;
        autoConnectBraille();
        // If this attempt fails, wait for the next lifecycle event or the
        // periodic safety retry instead of immediately scanning again.
        mDiscoveryAllowedForCycle = false;
    }

    private void stopDiscoverySession(String reason) {
        mDiscoverySessionActive = false;
        mDiscoverySessionDeadline = 0;
        mHandler.cancelDiscoveryTimeout();
        mHandler.cancelDiscoveryRestart();
        boolean owned = mDiscoveryStartedByUs;
        mDiscoveryStartedByUs = false;
        if (!owned) {
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !hasBluetoothScanPermission()) {
            return;
        }
        try {
            if (adapter.isDiscovering()) {
                adapter.cancelDiscovery();
                Timber.i("Bluetooth discovery stopped (%s)", reason);
            }
        } catch (SecurityException e) {
            Timber.w(e, "BLUETOOTH_SCAN permission denied cancelling discovery");
        }
    }

    private void cancelAutoReconnect() {
        mHandler.cancelAutoReconnect();
    }

    /**
     * Callbacks from the {@link HidDisplayConnection}.
     */
    private final HidDisplayConnection.Listener mHidListener =
            new HidDisplayConnection.Listener() {
        @Override
        public void onHidConnected(HidDisplayConnection source,
                BrailleDisplayProperties properties) {
            // Do NOT clear mConnectionProgress here: it must be broadcast to
            // clients so the settings UI re-renders the connected state.
            // handleOnDisplayConnected -> clearConnectionProgress() does that.
            mHandler.onHidConnected(source, properties);
        }

        @Override
        public void onHidDisconnected(HidDisplayConnection source) {
            mHandler.onHidDisconnected(source);
        }

        @Override
        public void onHidConnectFailed(HidDisplayConnection source, int error) {
            mHandler.onHidConnectFailed(source, error);
        }
    };

    private final WujieGattConnection.Listener mWujieListener =
            new WujieGattConnection.Listener() {
        @Override
        public void onWujieConnected(WujieGattConnection source,
                BrailleDisplayProperties properties) {
            mHandler.onWujieConnected(source, properties);
        }

        @Override
        public void onWujieInputEvent(WujieGattConnection source,
                BrailleInputEvent event) {
            mHandler.onWujieInputEvent(source, event);
        }

        @Override
        public void onWujieDisconnected(WujieGattConnection source) {
            mHandler.onWujieDisconnected(source);
        }

        @Override
        public void onWujieConnectFailed(WujieGattConnection source,
                int error) {
            mHandler.onWujieConnectFailed(source, error);
        }
    };

    /** Callbacks from the wired USB connection. */
    private final UsbDisplayConnection.Listener mUsbListener =
            new UsbDisplayConnection.Listener() {
        @Override
        public void onUsbConnected(UsbDisplayConnection source,
                BrailleDisplayProperties properties) {
            if (source != mUsbConnection) {
                Timber.w("Ignoring stale USB connected callback");
                return;
            }
            mHandler.onDisplayConnected(properties);
        }

        @Override
        public void onUsbDisconnected(UsbDisplayConnection source) {
            if (source != mUsbConnection) {
                Timber.w("Ignoring stale USB disconnected callback");
                return;
            }
            mHandler.onDisplayDisconnected();
        }

        @Override
        public void onUsbConnectFailed(UsbDisplayConnection source,
                String reason) {
            if (source != mUsbConnection) {
                Timber.w("Ignoring stale USB failure callback");
                return;
            }
            mUsbConnection = null;
            mConnectPending = false;
            Timber.w("USB connection failed: %s", reason);
            if (isUsbProtocol() && !mUsbHidTried && canUseUsbHid()) {
                mUsbHidTried = true;
                Timber.i("Serial USB path unavailable; trying USB HID");
                connectUsbHid();
                return;
            }
            mHandler.setConnectionProgress(
                    getString(R.string.connprog_usb_failed, reason));
            scheduleAutoReconnectIfNeeded();
        }
    };

    /**
     * Starts the wired USB connection path (brltty's Android USB backend).
     * Device enumeration, permission and the actual transfers are handled by
     * {@link UsbDisplayConnection}; this method only wires it into the service
     * state machine.
     */
    private void connectUsb() {
        mHandler.setConnectionProgress(
                getString(R.string.connprog_trying_usb));
        UsbDisplayConnection connection = new UsbDisplayConnection(
                this, mTablesDir, mUsbListener, this);
        mUsbConnection = connection;
        Timber.i("Starting wired USB connection");
        connection.connect();
    }

    /**
     * Unified USB entry point: the precise brltty VID/PID table is tried
     * first; if no serial device matches, the failure callback falls back to
     * the system Braille HID path on Android 15+.
     */
    private void connectUsbUnified() {
        mUsbSerialTried = true;
        connectUsb();
    }

    private boolean canUseUsbHid() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
                && HidBridge.isAvailable();
    }

    /**
     * Starts the wired USB HID path (Android 15+ / Braille HID standard).
     * Ported from Google TalkBack's UsbHidConnector: the system
     * {@code BrailleDisplayController.connect(UsbDevice, ...)} overload owns
     * the descriptor and transport, so the Bluetooth HID callbacks, the native
     * "hid" driver and BrlttyHidNativeHelper are reused unchanged.
     */
    @SuppressLint("NewApi")
    private void connectUsbHid() {
        if (Build.VERSION.SDK_INT
                < Build.VERSION_CODES.VANILLA_ICE_CREAM
                || !HidBridge.isAvailable()) {
            mHandler.setConnectionProgress(
                    getString(R.string.connprog_usb_hid_unavailable));
            scheduleAutoReconnectIfNeeded();
            return;
        }
        mHidTried = true;
        mHidConnectionIsUsb = true;
        mLastHidError = 0;
        mPendingBluetoothAddress = null;
        mHandler.setConnectionProgress(
                getString(R.string.connprog_trying_usb_hid));
        HidDisplayConnection connection = new HidDisplayConnection(this,
                getResources(), mTablesDir, mHidListener, this);
        mHidConnection = connection;
        Timber.i("Starting wired USB HID connection");
        connection.connectUsb(HidBridge.getController(),
                (UsbManager) getSystemService(Context.USB_SERVICE));
    }

    /**
     * Starts the private BLE GATT connection used by the Wujie display.
     * Unlike the HID path this does not depend on the system braille HID
     * service, so the display can stay in its vendor BLE mode.
     */
    private void connectWujie() {
        String address = mPendingBluetoothAddress;
        if (address == null) {
            address = findWujieAddress();
        }
        connectWujie(address);
    }

    private void connectWujie(String address) {
        if (address == null) {
            Timber.w("No bonded Wujie display found");
            mHandler.setConnectionProgress(
                    getString(R.string.connprog_no_devices));
            scheduleAutoReconnectIfNeeded();
            return;
        }
        mPendingBluetoothAddress = null;
        mHandler.setConnectionProgress(
                getString(R.string.connprog_trying_wujie));
        WujieGattConnection connection =
                new WujieGattConnection(this, mWujieListener);
        mWujieConnection = connection;
        Timber.i("Creating custom GATT connection for %s", address);
        connection.connect(address);
    }

    /**
     * Returns the address of a bonded Wujie / Orbit display. Name matching is
     * deliberately broad because the display advertises as "Orbit wujie 30"
     * and the user may have renamed it.
     */
    private String findWujieAddress() {
        String named = findNamedWujieAddress();
        if (named != null) {
            return named;
        }
        return PreferenceManager
                .getDefaultSharedPreferences(this)
                .getString("lastBluetoothDevice", null);
    }

    /** Strict discovery for automatic Bluetooth fallback. */
    private String findNamedWujieAddress() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            return null;
        }
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) {
                return null;
            }
            for (BluetoothDevice device : bonded) {
                if (device == null) {
                    continue;
                }
                String name = device.getName();
                if (name == null) {
                    continue;
                }
                String lower = name.toLowerCase(java.util.Locale.US);
                if (lower.contains("orbit") || lower.contains("wujie")
                        || name.contains("无界")) {
                    return device.getAddress();
                }
            }
        } catch (SecurityException e) {
            Log.w(LOG_TAG,
                    "BLUETOOTH_CONNECT permission denied finding Wujie device",
                    e);
            return null;
        }
        return null;
    }


    /**
     * If {@code intent} indicates that a new device has been
     * paired, try to connect to it.
     */
    private void connectFromBroadcastIntent(Intent intent) {
        BluetoothDevice bthDev = intent.getParcelableExtra(
                BluetoothDevice.EXTRA_DEVICE);
        if (bthDev == null) {
            return;
        }
        String address;
        try {
            address = bthDev.getAddress();
        } catch (SecurityException e) {
            Log.w(LOG_TAG,
                    "BLUETOOTH_CONNECT permission denied; ignoring bonded device",
                    e);
            return;
        }
        if (address == null) {
            return;
        }
        int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                BluetoothDevice.BOND_NONE);
        if (bondState == BluetoothDevice.BOND_NONE) {
            if (address.equals(mPendingBluetoothAddress)) {
                mPendingBluetoothAddress = null;
                Timber.i("Cleared pending Bluetooth device after unpair");
            }
            return;
        }
        if (bondState != BluetoothDevice.BOND_BONDED) {
            return;
        }
        if (mConnectionState == STATE_CONNECTED) {
            return;
        }
        if (!new DeviceFinder(this).isKnownDevice(address)) {
            Timber.i("Ignoring unrelated bonded Bluetooth device %s", address);
            return;
        }
        mPendingBluetoothAddress = address;
        // A fresh bond starts a new connection attempt. Without this reset a
        // failed HID attempt from before the device was unpaired would make
        // tryConnectHid() return false forever and replay the stale error.
        mHidTried = false;
        mHidRetryCount = 0;
        mLastHidError = 0;
        autoConnectBraille();
    }

    private void registerBroadcastReceiver() {
        String[] actions = new String[] {
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
        };
        IntentFilter filter = new IntentFilter();
        for (String action : actions) {
            filter.addAction(action);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            // Bluetooth broadcasts come from the Bluetooth process, not the
            // system UID, so a NOT_EXPORTED receiver does not reliably see
            // ACTION_FOUND/ACTION_ACL_CONNECTED on Android 13+.
            registerReceiver(mBroadcastReceiver, filter, null, null,
                    Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(mBroadcastReceiver, filter, null, null);
        }

        IntentFilter inputFilter = new IntentFilter(ACTION_INPUT_DEVICE_ADDED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(mInputDeviceReceiver, inputFilter, null, null,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mInputDeviceReceiver, inputFilter, null, null);
        }
    }

    private void sendConnectionState(IBrailleServiceCallback callback) {
        try {
            switch (mConnectionState) {
                case STATE_CONNECTED:
                    callback.onDisplayConnected(mDisplayProperties);
                    break;
                case STATE_DISCONNECTED:
                    callback.onDisplayDisconnected();
                    break;
                default:
                    Log.e(LOG_TAG,
                            "Unknown connection state: " + mConnectionState);
                    break;
            }
        } catch (RemoteException ex) {
            // Nothing to do, the callback list will remove the callback
            // later.
        }
    }

    private void sendConnectionProgress(IBrailleServiceCallback callback) {
        try {
            callback.onConnectionChangeProgress(mConnectionProgress);
        } catch (RemoteException ex) {
            // Nothing to do, the callback list will remove the callback
            // later.
        }
    }

    private void broadcastConnectionState() {
        int i = mClients.beginBroadcast();
        try {
            while (i-- > 0) {
                sendConnectionState(mClients.getBroadcastItem(i));
            }
        } finally {
            mClients.finishBroadcast();
        }
    }

    private void broadcastConnectionProgress() {
        int i = mClients.beginBroadcast();
        try {
            while (i-- > 0) {
                sendConnectionProgress(mClients.getBroadcastItem(i));
            }
        } finally {
            mClients.finishBroadcast();
        }
    }

    private void broadcastInputEvent(BrailleInputEvent event) {
        int i = mClients.beginBroadcast();
        try {
            while (i-- > 0) {
                try {
                    mClients.getBroadcastItem(i).onInput(event);
                } catch (RemoteException ex) {
                    // Nothing to do, the callback list will remove the
                    // callback later.
                }
            }
        } finally {
            mClients.finishBroadcast();
        }
    }

    /**
     * Returns {@code true} if there are any registered clients that
     * haven't died or unregistered themselves.
     */
    private boolean haveClients() {
        // Unfortunately, the callback list doesn't provide this
        // information without doing this.
        int numClients = mClients.beginBroadcast();
        mClients.finishBroadcast();
        return numClients > 0;
    }

    private void ensureDataFiles() {
        if (mDataFileState != FILES_NOT_EXTRACTED) {
            return;
        }
        // TODO: When the zip file is larger than a few kilobytes, detect if
        // the data was already extracted and don't do this every time the
        // service starts.
        new DataFileExtractor(this, R.raw.keytables, mTablesDir).execute();
    }

    private static class DataFileExtractor extends ZipResourceExtractor {
        private final WeakReference<DisplayService> mService;

        DataFileExtractor(DisplayService service, int rawResId, File outputDir) {
            super(service.getApplicationContext(), rawResId, outputDir);
            mService = new WeakReference<>(service);
        }

        @Override
        protected void onPostExecute(Integer result) {
            DisplayService service = mService.get();
            if (service == null) {
                return;
            }
            if (result == RESULT_OK) {
                service.mDataFileState = FILES_EXTRACTED;
                if (service.haveClients()) {
                    service.connectBraille();
                }
                // The initial bind can arrive before data extraction
                // finishes, and the client callback may not be registered
                // yet. Check again shortly so startup never leaves the
                // display disconnected until the user taps "Status".
                service.mHandler.scheduleAutoReconnect(0);
            } else {
                Log.e(LOG_TAG, "Couldn't extract data files");
                // TODO: figure out a way to deal with this so a user
                // doesn't get stuck in this state.
                service.mDataFileState = FILES_ERROR;
                service.broadcastConnectionState();
            }
        }
    }

    private static final class HidEvent {
        final HidDisplayConnection connection;
        final BrailleDisplayProperties properties;
        final int error;

        HidEvent(HidDisplayConnection connection,
                BrailleDisplayProperties properties, int error) {
            this.connection = connection;
            this.properties = properties;
            this.error = error;
        }
    }

    private static final class WujieEvent {
        final WujieGattConnection connection;
        final BrailleDisplayProperties properties;
        final int error;

        WujieEvent(WujieGattConnection connection,
                BrailleDisplayProperties properties, int error) {
            this.connection = connection;
            this.properties = properties;
            this.error = error;
        }
    }

    private static final class WujieInputEvent {
        final WujieGattConnection connection;
        final BrailleInputEvent event;

        WujieInputEvent(WujieGattConnection connection,
                BrailleInputEvent event) {
            this.connection = connection;
            this.event = event;
        }
    }

    private static class MainHandler extends Handler {
        private final WeakReference<DisplayService> mService;

        private static final int MSG_REGISTER_CALLBACK = 1;
        private static final int MSG_ON_DISPLAY_CONNECTED = 2;
        private static final int MSG_ON_DISPLAY_DISCONNECTED = 3;
        private static final int MSG_SET_CONNECTION_PROGRESS = 4;
        private static final int MSG_ON_INPUT_EVENT = 5;
        private static final int MSG_CONNECT_BRAILLE = 6;
        private static final int MSG_DISCONNECT_BRAILLE = 7;
        private static final int MSG_ON_HID_CONNECTED = 8;
        private static final int MSG_ON_HID_DISCONNECTED = 9;
        private static final int MSG_ON_HID_CONNECT_FAILED = 10;
        private static final int MSG_RETRY_HID = 11;
        private static final int MSG_ON_WUJIE_CONNECTED = 12;
        private static final int MSG_ON_WUJIE_DISCONNECTED = 13;
        private static final int MSG_ON_WUJIE_CONNECT_FAILED = 14;
        private static final int MSG_ON_WUJIE_INPUT_EVENT = 15;
        private static final int MSG_HID_CONTROLLER_TIMEOUT = 16;
        private static final int MSG_AUTO_RECONNECT = 17;
        private static final int MSG_DISCOVERY_TIMEOUT = 18;
        private static final int MSG_DISCOVERY_RESTART = 19;

        MainHandler(DisplayService service) {
            super(Looper.getMainLooper());
            mService = new WeakReference<>(service);
        }

        public void registerCallback(IBrailleServiceCallback callback) {
            obtainMessage(MSG_REGISTER_CALLBACK, callback).sendToTarget();
        }

        public void onDisplayConnected(BrailleDisplayProperties properties) {
            obtainMessage(MSG_ON_DISPLAY_CONNECTED, properties).sendToTarget();
        }

        public void onDisplayDisconnected() {
            sendEmptyMessage(MSG_ON_DISPLAY_DISCONNECTED);
        }

        public void onHidConnected(HidDisplayConnection source,
                BrailleDisplayProperties properties) {
            obtainMessage(MSG_ON_HID_CONNECTED,
                    new HidEvent(source, properties, 0)).sendToTarget();
        }

        public void onHidDisconnected(HidDisplayConnection source) {
            obtainMessage(MSG_ON_HID_DISCONNECTED,
                    new HidEvent(source, null, 0)).sendToTarget();
        }

        public void onHidConnectFailed(HidDisplayConnection source, int error) {
            obtainMessage(MSG_ON_HID_CONNECT_FAILED,
                    new HidEvent(source, null, error)).sendToTarget();
        }

        public void onWujieConnected(WujieGattConnection source,
                BrailleDisplayProperties properties) {
            obtainMessage(MSG_ON_WUJIE_CONNECTED,
                    new WujieEvent(source, properties, 0)).sendToTarget();
        }

        public void onWujieDisconnected(WujieGattConnection source) {
            obtainMessage(MSG_ON_WUJIE_DISCONNECTED,
                    new WujieEvent(source, null, 0)).sendToTarget();
        }

        public void onWujieInputEvent(WujieGattConnection source,
                BrailleInputEvent event) {
            obtainMessage(MSG_ON_WUJIE_INPUT_EVENT,
                    new WujieInputEvent(source, event)).sendToTarget();
        }

        public void onWujieConnectFailed(WujieGattConnection source,
                int error) {
            obtainMessage(MSG_ON_WUJIE_CONNECT_FAILED,
                    new WujieEvent(source, null, error)).sendToTarget();
        }

        public void setConnectionProgress(String description) {
            obtainMessage(MSG_SET_CONNECTION_PROGRESS, description)
                    .sendToTarget();
        }

        public void onInputEvent(BrailleInputEvent event) {
            obtainMessage(MSG_ON_INPUT_EVENT, event).sendToTarget();
        }

        public void unscheduleDisconnect() {
            removeMessages(MSG_DISCONNECT_BRAILLE);
        }

        public void scheduleDisconnect(long delayMillis) {
            sendMessageDelayed(obtainMessage(MSG_DISCONNECT_BRAILLE, 0, 0),
                    delayMillis);
        }

        public void connectBraille() {
            obtainMessage(MSG_CONNECT_BRAILLE, 0, 0).sendToTarget();
        }

        public void connectBrailleExplicit() {
            obtainMessage(MSG_CONNECT_BRAILLE, 1, 0).sendToTarget();
        }

        public void disconnectBraille() {
            obtainMessage(MSG_DISCONNECT_BRAILLE, 1, 0).sendToTarget();
        }

        public void scheduleAutoReconnect(int attempt) {
            scheduleAutoReconnect(attempt, -1);
        }

        public void scheduleAutoReconnect(int attempt, long overrideDelay) {
            long delay = AUTO_RECONNECT_INITIAL_DELAY
                    << Math.min(attempt, 4);
            delay = Math.min(delay, AUTO_RECONNECT_MAX_DELAY);
            if (overrideDelay >= 0) {
                delay = overrideDelay;
            }
            removeMessages(MSG_AUTO_RECONNECT);
            sendEmptyMessageDelayed(MSG_AUTO_RECONNECT, delay);
            Timber.i("Scheduling automatic reconnect attempt %d in %d ms",
                    attempt + 1, delay);
        }

        public void cancelAutoReconnect() {
            removeMessages(MSG_AUTO_RECONNECT);
        }

        public boolean hasAutoReconnectScheduled() {
            return hasMessages(MSG_AUTO_RECONNECT);
        }

        public void scheduleDiscoveryTimeout(long delayMillis) {
            removeMessages(MSG_DISCOVERY_TIMEOUT);
            sendEmptyMessageDelayed(MSG_DISCOVERY_TIMEOUT, delayMillis);
        }

        public void cancelDiscoveryTimeout() {
            removeMessages(MSG_DISCOVERY_TIMEOUT);
        }

        public void scheduleDiscoveryRestart(long delayMillis) {
            removeMessages(MSG_DISCOVERY_RESTART);
            sendEmptyMessageDelayed(MSG_DISCOVERY_RESTART, delayMillis);
        }

        public void cancelDiscoveryRestart() {
            removeMessages(MSG_DISCOVERY_RESTART);
        }

        public void scheduleHidRetry() {
            sendEmptyMessageDelayed(MSG_RETRY_HID, HID_RETRY_DELAY);
        }

        public void cancelHidRetry() {
            removeMessages(MSG_RETRY_HID);
        }

        public void scheduleHidControllerTimeout(long delayMillis) {
            removeMessages(MSG_HID_CONTROLLER_TIMEOUT);
            sendEmptyMessageDelayed(MSG_HID_CONTROLLER_TIMEOUT, delayMillis);
        }

        public void cancelHidControllerTimeout() {
            removeMessages(MSG_HID_CONTROLLER_TIMEOUT);
        }

        @Override
        public void handleMessage(Message msg) {
            DisplayService service = mService.get();
            if (service == null) {
                return;
            }
            switch (msg.what) {
                case MSG_REGISTER_CALLBACK:
                    handleRegisterCallback(service,
                            (IBrailleServiceCallback) msg.obj);
                    break;
                case MSG_ON_DISPLAY_CONNECTED:
                    handleOnDisplayConnected(service,
                        (BrailleDisplayProperties) msg.obj);
                    break;
                case MSG_ON_DISPLAY_DISCONNECTED:
                    handleOnDisplayDisconnected(service);
                    break;
                case MSG_SET_CONNECTION_PROGRESS:
                    handleSetConnectionProgress(service, (String) msg.obj);
                    break;
                case MSG_ON_INPUT_EVENT:
                    handleOnInputEvent(service, (BrailleInputEvent) msg.obj);
                    break;
                case MSG_CONNECT_BRAILLE:
                    if (msg.arg1 != 0) {
                        service.startExplicitConnect();
                    } else {
                        service.connectBraille();
                    }
                    break;
                case MSG_DISCONNECT_BRAILLE:
                    service.cancelPendingReconnect();
                    service.disconnectBraille(msg.arg1 != 0);
                    break;
                case MSG_ON_HID_CONNECTED:
                    handleOnHidConnected(service, (HidEvent) msg.obj);
                    break;
                case MSG_ON_HID_DISCONNECTED:
                    handleOnHidDisconnected(service, (HidEvent) msg.obj);
                    break;
                case MSG_ON_HID_CONNECT_FAILED:
                    handleOnHidConnectFailed(service, (HidEvent) msg.obj);
                    break;
                case MSG_RETRY_HID:
                    handleRetryHid(service);
                    break;
                case MSG_ON_WUJIE_CONNECTED:
                    handleOnWujieConnected(service, (WujieEvent) msg.obj);
                    break;
                case MSG_ON_WUJIE_DISCONNECTED:
                    handleOnWujieDisconnected(service, (WujieEvent) msg.obj);
                    break;
                case MSG_ON_WUJIE_CONNECT_FAILED:
                    handleOnWujieConnectFailed(service, (WujieEvent) msg.obj);
                    break;
                case MSG_ON_WUJIE_INPUT_EVENT:
                    handleOnWujieInputEvent(service,
                            (WujieInputEvent) msg.obj);
                    break;
                case MSG_HID_CONTROLLER_TIMEOUT:
                    handleHidControllerTimeout(service);
                    break;
                case MSG_AUTO_RECONNECT:
                    handleAutoReconnect(service);
                    break;
                case MSG_DISCOVERY_TIMEOUT:
                    service.stopDiscoverySession("discovery window expired");
                    break;
                case MSG_DISCOVERY_RESTART:
                    service.startDiscoveryInternal("discovery cycle restart");
                    break;
        default: // fall out
      }
        }

        private void handleRegisterCallback(
                DisplayService service, IBrailleServiceCallback callback) {
            service.mClients.register(callback);
            if (service.mDataFileState == FILES_EXTRACTED
                    && service.mConnectionState == STATE_DISCONNECTED
                    && !service.isConnectionActive()
                    && !service.mConnectPending
                    && !service.mWaitingForHidController) {
                // onBind() runs before the client has registered, so its
                // failed attempt cannot open a discovery window yet. Fill that
                // gap now that haveClients() is true.
                service.mDiscoveryAllowedForCycle = true;
                service.startDiscoveryWindow("client registered");
            }
            if (service.mConnectionProgress != null) {
                service.sendConnectionProgress(callback);
            }
            if (service.mDataFileState == FILES_NOT_EXTRACTED
                    || (service.mConnectionState == STATE_DISCONNECTED
                            && (service.mReadThread != null
                                    || (service.mHidConnection != null
                                            && !service.mHidConnection
                                                    .isDisconnecting())
                                    || service.mWujieConnection != null
                                    || service.mUsbConnection != null))) {
                // Extraction or connection in progress, there will be a
                // broadcast of the state when it either succeeds or fails.
                return;
            }
            service.sendConnectionState(callback);
        }

        private void handleOnDisplayConnected(
                DisplayService service, BrailleDisplayProperties properties) {
            handleConnectionEstablished(service, properties);
        }

        private void handleOnHidConnected(
                DisplayService service, HidEvent event) {
            if (event.connection != service.mHidConnection) {
                Timber.w("Ignoring stale HID connected callback");
                return;
            }
            handleConnectionEstablished(service, event.properties);
        }

        private void handleConnectionEstablished(
                DisplayService service, BrailleDisplayProperties properties) {
            service.clearHidControllerWait();
            service.mConnectionState = STATE_CONNECTED;
            // Also broadcast the progress clear, so clients do not keep
            // showing a stale "connecting..." message and suppress the
            // connected state.
            service.clearConnectionProgress();
            service.mDisplayProperties = properties;
            service.mConnectPending = false;
            service.mHidTried = false;
            service.mWujieTried = false;
            service.mUsbSerialTried = false;
            service.mUsbHidTried = false;
            service.mHidConnectionIsUsb = false;
            service.mHidRetryCount = 0;
            service.mAutoReconnectAttempt = 0;
            service.mAutoReconnectSuppressed = false;
            service.mDiscoveryAllowedForCycle = false;
            service.stopDiscoverySession("display connected");
            service.cancelAutoReconnect();
            cancelHidRetry();
            service.broadcastConnectionState();
        }

        private void handleOnDisplayDisconnected(DisplayService service) {
            service.mReadThread = null;
            // Also released by the wired path (which has no ReadThread).
            service.mUsbConnection = null;
            handleConnectionReleased(service);
        }

        private void handleOnHidDisconnected(
                DisplayService service, HidEvent event) {
            if (event.connection != service.mHidConnection) {
                Timber.w("Ignoring stale HID disconnected callback");
                return;
            }
            service.mHidConnection = null;
            handleConnectionReleased(service);
        }

        private void handleConnectionReleased(DisplayService service) {
            service.resetConnectionAttemptFlags();
            cancelHidRetry();
            service.clearConnectionProgress();
            if (service.mConnectionState != STATE_DISCONNECTED) {
                service.mConnectionState = STATE_DISCONNECTED;
                service.broadcastConnectionState();
            }
            if (service.mConnectPending) {
                // Don't get stuck retrying if connecting fails.
                service.mConnectPending = false;
                Timber.i("Previous connection released; reconnecting once");
                service.connectBraille();
                return;
            }
            service.scheduleAutoReconnectIfNeeded();
        }

        private void handleOnHidConnectFailed(
                DisplayService service, HidEvent event) {
            if (event.connection != service.mHidConnection) {
                Timber.w("Ignoring stale HID failure callback %d", event.error);
                return;
            }
            service.mHidConnection = null;
            service.mConnectPending = false;
            service.mLastHidError = event.error;
            if (service.mHidConnectionIsUsb && service.isUsbProtocol()) {
                service.mHidConnectionIsUsb = false;
                // A wired USB HID attempt with no device attached fails
                // synchronously inside connectUsb(). Calling connectBraille()
                // here would repost the same failure on the main looper, which
                // spins at thousands of iterations per second (observed
                // 47k retries in 17 s, ~106% CPU). Report the failure once and
                // let the USB attach broadcast or the backoff schedule a retry.
                Timber.w("Wired USB HID connect failed (%d); waiting for a "
                        + "device or the next scheduled retry", event.error);
                service.mHandler.setConnectionProgress(
                        service.getUsbHidFailureMessage(event.error));
                service.scheduleAutoReconnectIfNeeded();
                return;
            }
            String protocol = service.getConnectionProtocol();
            boolean hidRequested = service.isBluetoothProtocol();
            if (event.error == -3 && service.mHidRetryCount == 0
                    && hidRequested && service.mReadThread == null) {
                service.mHidRetryCount = 1;
                service.mHidTried = false;
                Timber.i("Retrying HID once after controller release conflict");
                scheduleHidRetry();
                return;
            }
            Timber.e("HID connect failed (%d), protocol=%s", event.error,
                    protocol);
            // mHidTried remains true so auto mode falls through to RFCOMM and
            // forced HID reports the existing failure message.
            service.connectBraille();
            service.scheduleAutoReconnectIfNeeded();
        }

        private void handleOnWujieConnected(
                DisplayService service, WujieEvent event) {
            if (event.connection != service.mWujieConnection) {
                Timber.w("Ignoring stale Wujie connected callback");
                return;
            }
            handleConnectionEstablished(service, event.properties);
        }

        private void handleOnWujieDisconnected(
                DisplayService service, WujieEvent event) {
            if (event.connection != service.mWujieConnection) {
                Timber.w("Ignoring stale Wujie disconnected callback");
                return;
            }
            service.mWujieConnection = null;
            handleConnectionReleased(service);
        }

        private void handleOnWujieConnectFailed(
                DisplayService service, WujieEvent event) {
            if (event.connection != service.mWujieConnection) {
                Timber.w("Ignoring stale Wujie failure callback %d",
                        event.error);
                return;
            }
            service.mWujieConnection = null;
            service.mConnectPending = false;
            Timber.e("Wujie GATT connect failed (%d)", event.error);
            int message;
            switch (event.error) {
                case WujieGattConnection.ERROR_PERMISSION:
                    message = R.string.connprog_wujie_permission;
                    break;
                case WujieGattConnection.ERROR_SERVICE:
                    message = R.string.connprog_wujie_service_missing;
                    break;
                case WujieGattConnection.ERROR_MTU:
                    message = R.string.connprog_wujie_mtu;
                    break;
                case WujieGattConnection.ERROR_NO_WRITE:
                    message = R.string.connprog_wujie_no_write;
                    break;
                default:
                    service.mHandler.setConnectionProgress(
                            service.getString(
                                    R.string.connprog_wujie_failed_code,
                                    event.error));
                    service.scheduleAutoReconnectIfNeeded();
                    return;
            }
            service.mHandler.setConnectionProgress(
                    service.getString(message));
            service.scheduleAutoReconnectIfNeeded();
        }

        private void handleOnWujieInputEvent(
                DisplayService service, WujieInputEvent input) {
            if (input.connection != service.mWujieConnection
                    || input.event == null) {
                Timber.w("Ignoring stale Wujie input event");
                return;
            }
            service.broadcastInputEvent(input.event);
        }

        private void handleRetryHid(DisplayService service) {
            if (service.mHidConnection != null || service.mReadThread != null
                    || service.mUsbConnection != null) {
                return;
            }
            if (!service.isHidRequested()) {
                return;
            }
            service.mHidTried = false;
            if (!service.retryHidConnect()) {
                service.mHidTried = true;
                service.connectBraille();
            }
        }

        private void handleHidControllerTimeout(DisplayService service) {
            if (!service.mWaitingForHidController) {
                return;
            }
            service.mWaitingForHidController = false;
            if (!service.isBluetoothProtocol()) {
                return;
            }
            if (HidBridge.isAvailable()) {
                // The controller arrived just before the timeout fired.
                service.mHidTried = false;
                service.connectBraille();
                return;
            }
            // HID is not available in this cycle, so use classic RFCOMM instead
            // of waiting forever.
            service.mHidTried = true;
            service.connectBraille();
            service.scheduleAutoReconnectIfNeeded();
        }

        private void handleAutoReconnect(DisplayService service) {
            if (!service.shouldAutoReconnect()) {
                Timber.i("Skipping automatic reconnect (no longer eligible)");
                return;
            }
            Timber.i("Automatic reconnect attempt %d",
                    service.mAutoReconnectAttempt);
            service.connectBraille();
        }

        private void handleSetConnectionProgress(
                DisplayService service, String description) {
            if ((description == null && service.mConnectionProgress == null)
                    || (description != null && description.equals(
                                    service.mConnectionProgress))) {
                return;
            }
            service.mConnectionProgress = description;
            service.broadcastConnectionProgress();
        }

        private void handleOnInputEvent(
                DisplayService service, BrailleInputEvent event) {
            service.broadcastInputEvent(event);
        }
    }
}
