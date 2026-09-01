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
import android.os.Build;

import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties;
import com.googlecode.eyesfree.braille.display.BrailleInputEvent;
import com.googlecode.eyesfree.braille.display.IBrailleService;
import com.googlecode.eyesfree.braille.display.IBrailleServiceCallback;
import com.googlecode.eyesfree.braille.service.R;
import com.googlecode.eyesfree.braille.utils.ZipResourceExtractor;
import java.io.File;
import java.util.List;

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
                    mHandler.scheduleDisconnect(SCREEN_OFF_DISCONNECT_DELAY);
                } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(
                                action)) {
                    connectFromBroadcastIntent(intent);
                } else {
                    Log.w(LOG_TAG, "Unexpected broadcast " + action);
            }
        }
    };

    private MainHandler mHandler = new MainHandler();

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTED = 1;
    private int mConnectionState = STATE_DISCONNECTED;
    private String mConnectionProgress = null;
    /**
     * Delay between the screen goes off and the display gets automatically
     * disconnected.
     */
    private static final long SCREEN_OFF_DISCONNECT_DELAY = 7000;
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
    private HidDisplayConnection mHidConnection;
    /**
     * True after an HID connection attempt, to avoid retrying HID in a loop
     * when the connected display only supports classic RFCOMM.
     */
    private boolean mHidTried;

    /** Reconnects with a different protocol when the setting changes. */
    private final SharedPreferences.OnSharedPreferenceChangeListener
            mProtocolPrefListener =
        new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(
                    SharedPreferences prefs, String key) {
                if ("connection_protocol".equals(key)) {
                    if (mConnectionState == STATE_CONNECTED
                            || mReadThread != null || mHidConnection != null) {
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
                    String protocol = getConnectionProtocol();
                    boolean hidRequested = "hid".equals(protocol)
                            || "auto".equals(protocol);
                    if (hidRequested && mConnectionState == STATE_DISCONNECTED
                            && mReadThread == null && mHidConnection == null) {
                        mHidTried = false;
                        autoConnectBraille();
                    }
                }
            });
        }
    };


    @Override
    public void onCreate() {
        super.onCreate();
        mTablesDir = getDir("keytables", Context.MODE_PRIVATE);
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(
                        mProtocolPrefListener);
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(
                        mAutoConnectPrefListener);
        registerBroadcastReceiver();
        HidBridge.setControllerChangeListener(
                mHidControllerChangeListener);
        ensureDataFiles();
        Log.i(LOG_TAG, "Service started.");
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
        HidBridge.setControllerChangeListener(null);
        disconnectBraille();
        if (mBroadcastReceiver != null) {
            unregisterReceiver(mBroadcastReceiver);
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
            HidDisplayConnection hidConnection = mHidConnection;
            if (hidConnection != null) {
                hidConnection.writeWindow(patterns);
                return;
            }
            ReadThread localReadThread = mReadThread;
            if (localReadThread == null) {
                return;
            }
            DriverThread localDriverThread = localReadThread.getDriverThread();
            if (localDriverThread == null) {
                return;
            }
            localDriverThread.writeWindow(patterns);
        }

        @Override
        public void poll() {
            mHandler.connectBraille();
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
        Log.i(LOG_TAG, "Disconnecting braille display");
        clearConnectionProgress();
        mConnectionState = STATE_DISCONNECTED;
        mDisplayProperties = null;
        broadcastConnectionState();
        if (mReadThread != null) {
            mReadThread.disconnect();
        }
        if (mHidConnection != null) {
            mHidConnection.disconnect();
            mHidConnection = null;
        }
        mHidTried = false;
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

        if (mReadThread != null || mHidConnection != null) {
            // We must wait until the previous attempt is done before
            // retrying a connect, so just set the flag here.
            mConnectPending = true;
            return;
        }

        String protocol = getConnectionProtocol();
        if ("auto".equals(protocol) || "hid".equals(protocol)) {
            if (tryConnectHid()) {
                // A Braille HID connection attempt was started (async).
                return;
            }
            if ("hid".equals(protocol)) {
                // The user forced HID but it could not be used; do not fall
                // back to classic RFCOMM silently.
                if (mHidTried) {
                    mHandler.setConnectionProgress(
                            getString(R.string.connprog_hid_failed));
                } else {
                    mHandler.setConnectionProgress(
                            getString(R.string.connprog_hid_unavailable));
                }
                return;
            }
        }
        // "auto" (HID failed / not available) or "rfcomm": classic RFCOMM.
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
    private boolean tryConnectHid() {
        if (mHidTried || !HidBridge.isAvailable()) {
            return false;
        }
        String address = mPendingBluetoothAddress;
        if (address == null) {
            List<DeviceFinder.DeviceInfo> devices =
                    new DeviceFinder(this).findDevices();
            if (devices.isEmpty()) {
                return false;
            }
            address = devices.get(0).getBluetoothDevice().getAddress();
        }
        mHidTried = true;
        mPendingBluetoothAddress = null;
        mHandler.setConnectionProgress(getString(R.string.connprog_trying_hid));
        mHidConnection = new HidDisplayConnection(this, getResources(),
                mTablesDir, mHidListener, this);
        mHidConnection.connect(HidBridge.getController(), address);
        return true;
    }

    /** Returns the user-selected connection protocol: auto, hid or rfcomm. */
    private String getConnectionProtocol() {
        return PreferenceManager.getDefaultSharedPreferences(this)
                .getString("connection_protocol", "auto");
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
        if (PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean("auto_connect", true)) {
            connectBraille();
        }
    }

    /**
     * Callbacks from the {@link HidDisplayConnection}.
     */
    private final HidDisplayConnection.Listener mHidListener =
            new HidDisplayConnection.Listener() {
        @Override
        public void onHidConnected(BrailleDisplayProperties properties) {
            // Do NOT clear mConnectionProgress here: it must be broadcast to
            // clients so the settings UI re-renders the connected state.
            // handleOnDisplayConnected -> clearConnectionProgress() does that.
            mHandler.onDisplayConnected(properties);
        }

        @Override
        public void onHidDisconnected() {
            mHidConnection = null;
            mHidTried = false;
            mHandler.onDisplayDisconnected();
        }

        @Override
        public void onHidConnectFailed(int error) {
            Log.e(LOG_TAG, "HID connect failed (" + error + "), falling back to RFCOMM");
            mHidConnection = null;
            connectBraille();
        }
    };


    /**
     * If {@code intent} indicates that a new device has been
     * paired, try to connect to it.
     */
    private void connectFromBroadcastIntent(Intent intent) {
        if (mConnectionState == STATE_CONNECTED) {
            return;
        }
        int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                BluetoothDevice.BOND_NONE);
        if (bondState != BluetoothDevice.BOND_BONDED) {
            return;
        }
        BluetoothDevice bthDev = intent.getParcelableExtra(
                BluetoothDevice.EXTRA_DEVICE);
        if (bthDev == null) {
            return;
        }
        mPendingBluetoothAddress = bthDev.getAddress();
        autoConnectBraille();
    }

    private void registerBroadcastReceiver() {
        String[] actions = new String[] {
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            BluetoothDevice.ACTION_BOND_STATE_CHANGED,
        };
        IntentFilter filter = new IntentFilter();
        for (String action : actions) {
            filter.addAction(action);
        }
        if (Build.VERSION.SDK_INT >= 33) {
    registerReceiver(mBroadcastReceiver, filter, null, null,
            Context.RECEIVER_NOT_EXPORTED);
} else {
    registerReceiver(mBroadcastReceiver, filter, null, null);
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
        ZipResourceExtractor extractor = new ZipResourceExtractor(
            this, R.raw.keytables, mTablesDir) {
            @Override
            protected void onPostExecute(Integer result) {
                if (result == RESULT_OK) {
                    mDataFileState = FILES_EXTRACTED;
                    if (haveClients()) {
                        connectBraille();
                    }
                } else {
                    Log.e(LOG_TAG, "Couldn't extract data files");
                    // TODO: figure out a way to deal with this so a user
                    // doesn't get stuck in this state.
                    mDataFileState = FILES_ERROR;
                    broadcastConnectionState();
                }
            }
        };
        extractor.execute();
    }

    private class MainHandler extends Handler {
        private static final int MSG_REGISTER_CALLBACK = 1;
        private static final int MSG_ON_DISPLAY_CONNECTED = 2;
        private static final int MSG_ON_DISPLAY_DISCONNECTED = 3;
        private static final int MSG_SET_CONNECTION_PROGRESS = 4;
        private static final int MSG_ON_INPUT_EVENT = 5;
        private static final int MSG_CONNECT_BRAILLE = 6;
        private static final int MSG_DISCONNECT_BRAILLE = 7;

        public void registerCallback(IBrailleServiceCallback callback) {
            obtainMessage(MSG_REGISTER_CALLBACK, callback).sendToTarget();
        }

        public void onDisplayConnected(BrailleDisplayProperties properties) {
            obtainMessage(MSG_ON_DISPLAY_CONNECTED, properties).sendToTarget();
        }

        public void onDisplayDisconnected() {
            sendEmptyMessage(MSG_ON_DISPLAY_DISCONNECTED);
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
            sendEmptyMessageDelayed(MSG_DISCONNECT_BRAILLE, delayMillis);
        }

        public void connectBraille() {
            sendEmptyMessage(MSG_CONNECT_BRAILLE);
        }

        public void disconnectBraille() {
            sendEmptyMessage(MSG_DISCONNECT_BRAILLE);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_REGISTER_CALLBACK:
                    handleRegisterCallback((IBrailleServiceCallback) msg.obj);
                    break;
                case MSG_ON_DISPLAY_CONNECTED:
                    handleOnDisplayConnected(
                        (BrailleDisplayProperties) msg.obj);
                    break;
                case MSG_ON_DISPLAY_DISCONNECTED:
                    handleOnDisplayDisconnected();
                    break;
                case MSG_SET_CONNECTION_PROGRESS:
                    handleSetConnectionProgress((String) msg.obj);
                    break;
                case MSG_ON_INPUT_EVENT:
                    handleOnInputEvent((BrailleInputEvent) msg.obj);
                    break;
                case MSG_CONNECT_BRAILLE:
                    DisplayService.this.connectBraille();
                    break;
                case MSG_DISCONNECT_BRAILLE:
                    disconnectBraille();
                    break;
        default: // fall out
      }
        }

        private void handleRegisterCallback(IBrailleServiceCallback callback) {
            mClients.register(callback);
            if (mConnectionProgress != null) {
                sendConnectionProgress(callback);
            }
            if (mDataFileState == FILES_NOT_EXTRACTED ||
                    (mConnectionState == STATE_DISCONNECTED
                            && (mReadThread != null || mHidConnection != null))) {
                // Extraction or connection in progress, there will be a
                // broadcast of the state when it either succeeds or fails.
                return;
            }
            sendConnectionState(callback);
        }

        private void handleOnDisplayConnected(
        BrailleDisplayProperties properties) {
            mConnectionState = STATE_CONNECTED;
            // Also broadcast the progress clear, so clients do not keep
            // showing a stale "connecting..." message and suppress the
            // connected state.
            clearConnectionProgress();
            mDisplayProperties = properties;
            mConnectPending = false;
            broadcastConnectionState();
        }

        private void handleOnDisplayDisconnected() {
            mReadThread = null;
            mHidConnection = null;
            mHidTried = false;
            clearConnectionProgress();
            if (mConnectionState != STATE_DISCONNECTED) {
                mConnectionState = STATE_DISCONNECTED;
                broadcastConnectionState();
            }
            if (mConnectPending) {
                // Don't get stuck retrying if connecting fails.
                mConnectPending = false;
                connectBraille();
            }
        }

        private void handleSetConnectionProgress(String description) {
            if ((description == null && mConnectionProgress == null)
                    || (description != null && description.equals(
                                    mConnectionProgress))) {
                return;
            }
            mConnectionProgress = description;
            broadcastConnectionProgress();
        }

        private void handleOnInputEvent(BrailleInputEvent event) {
            broadcastInputEvent(event);
        }
    }
}
