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
import android.accessibilityservice.BrailleDisplayController;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Executor;
import timber.log.Timber;

/**
 * Drives a Braille HID standard (usage page 0x41) display through the system
 * {@code BrailleDisplayController} API.
 *
 * <p>This is a BrailleBack port of Google TalkBack's HidConnector +
 * BrlttyHidNativeHelper approach: the framework provides the raw HID transport
 * (descriptor + input reports + write), and the native BRLTTY "hid" driver
 * (Drivers/Braille/HID + hid_android.c, from Google's TalkBack brltty 6.7 port)
 * decodes the reports into braille cells and key events.
 */
@SuppressLint("NewApi") // Only created by DisplayService on API 35+.
public class HidDisplayConnection implements DriverThread.OnInitListener {

  /** Callbacks to the DisplayService. */
  public interface Listener {
    void onHidConnected(
        HidDisplayConnection source, BrailleDisplayProperties properties);
    void onHidDisconnected(HidDisplayConnection source);
    void onHidConnectFailed(HidDisplayConnection source, int error);
  }

  private static final String LOG_TAG = HidDisplayConnection.class.getSimpleName();

  private static final long DISCONNECT_TIMEOUT_MS = 2000;
  private static final int FAILURE_NONE = Integer.MIN_VALUE;

  private final Context mContext;
  private final Resources mResources;
  private final File mTablesDir;
  private final Listener mListener;
  private final DriverThread.OnInputEventListener mInputEventListener;
  private final Executor mExecutor;
  private final Handler mMainHandler = new Handler(Looper.getMainLooper());
  private final HidConnectionLifecycle mLifecycle = new HidConnectionLifecycle();

  private volatile BrailleDisplayController mController;
  private volatile BluetoothDevice mDevice;
  private volatile DriverThread mDriverThread;
  private volatile int mPendingFailure = FAILURE_NONE;

  private final Runnable mDisconnectTimeout = new Runnable() {
    @Override
    public void run() {
      if (!mLifecycle.finishDisconnectTimeout()) {
        return;
      }
      Timber.w("HID disconnect timed out after %d ms; completing teardown",
          DISCONNECT_TIMEOUT_MS);
      // Best effort: there is no callback to wait for any more, but asking the
      // controller to release again may help the next attempt.
      requestControllerDisconnect();
      completeDisconnected();
    }
  };

  public HidDisplayConnection(Context context, Resources resources, File tablesDir,
      Listener listener, DriverThread.OnInputEventListener inputEventListener) {
    mContext = context;
    mResources = resources;
    mTablesDir = tablesDir;
    mListener = listener;
    mInputEventListener = inputEventListener;
    mExecutor = new HandlerExecutor(new Handler(Looper.getMainLooper()));
  }

  /** Connects to the HID braille display with the given bluetooth address. */
  public void connect(BrailleDisplayController controller, String deviceAddress) {
    if (controller == null || deviceAddress == null) {
      Log.e(LOG_TAG, "Cannot connect: missing controller or device address");
      notifyConnectFailed(-1);
      return;
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        && mContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
      Log.e(LOG_TAG, "BLUETOOTH_CONNECT permission not granted");
      notifyConnectFailed(-6);
      return;
    }
    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    if (adapter == null) {
      notifyConnectFailed(-1);
      return;
    }
    BluetoothDevice device;
    try {
      device = adapter.getRemoteDevice(deviceAddress);
    } catch (IllegalArgumentException | SecurityException e) {
      Log.e(LOG_TAG, "Invalid HID braille display address "
          + deviceAddress + ": " + e);
      notifyConnectFailed(-5);
      return;
    }
    if (!mLifecycle.beginConnect()) {
      Timber.w("Ignoring HID connect request in state %s", mLifecycle.getState());
      return;
    }
    mController = controller;
    mDevice = device;
    Log.i(LOG_TAG, "Connecting to HID braille display " + deviceAddress);
    Timber.i("Starting HID connection to %s", deviceAddress);
    try {
      controller.connect(device, mExecutor, mCallback);
    } catch (IllegalStateException e) {
      // The system already holds a Braille display connection for this
      // accessibility service (e.g. a previous connection that has not been
      // fully torn down yet, or the display is held by another service).
      // Release it and wait for the controller to settle before reporting a
      // clean failure, so the service does not race the system teardown.
      Log.e(LOG_TAG, "BrailleDisplayController already connected: " + e);
      startTeardown(-3);
    } catch (RuntimeException e) {
      Log.e(LOG_TAG, "BrailleDisplayController.connect() failed: " + e);
      startTeardown(-4);
    }
  }

  /**
   * Writes dot patterns to the HID display driver. Forwarded to the native
   * BRLTTY "hid" driver which prepares the output report (report ID + cells)
   * and sends it through the {@code BrailleDisplayController}.
   */
  public void writeWindow(byte[] patterns) {
    DriverThread dt = mDriverThread;
    if (dt != null) {
      dt.writeWindow(patterns);
    } else {
      Log.w(LOG_TAG, "writeWindow before driver is ready; dropping "
          + patterns.length + " cells");
    }
  }

  /** Tears down the HID connection after the system releases the controller. */
  public void disconnect() {
    startTeardown(FAILURE_NONE);
  }

  /** True while the system controller is being released asynchronously. */
  public boolean isDisconnecting() {
    return mLifecycle.isDisconnecting();
  }

  private synchronized void startTeardown(int failure) {
    boolean started = mLifecycle.beginDisconnect();
    if (!started && !mLifecycle.isDisconnecting()) {
      return;
    }
    if (!started) {
      Timber.i("HID teardown already in progress");
      return;
    }
    if (failure != FAILURE_NONE) {
      mPendingFailure = failure;
    }
    Timber.i("Requesting HID teardown (failure=%d, controllerConnected=%s)",
        mPendingFailure, isControllerConnected());
    mMainHandler.removeCallbacks(mDisconnectTimeout);
    stopDriver();
    BrlttyHidNativeHelper.reset();
    requestControllerDisconnect();
    mMainHandler.postDelayed(mDisconnectTimeout, DISCONNECT_TIMEOUT_MS);
  }

  private void requestControllerDisconnect() {
    BrailleDisplayController controller = mController;
    if (controller == null) {
      return;
    }
    try {
      // Do not gate this on isConnected(): a connect request may still be in
      // flight, and it must be cancelled before the next protocol starts.
      controller.disconnect();
    } catch (RuntimeException e) {
      Log.w(LOG_TAG, "Releasing BrailleDisplayController failed: " + e);
      Timber.w(e, "Releasing BrailleDisplayController failed");
    }
  }

  private boolean isControllerConnected() {
    BrailleDisplayController controller = mController;
    try {
      return controller != null && controller.isConnected();
    } catch (RuntimeException e) {
      return false;
    }
  }

  private synchronized void completeDisconnected() {
    mMainHandler.removeCallbacks(mDisconnectTimeout);
    stopDriver();
    BrlttyHidNativeHelper.reset();
    int failure = mPendingFailure;
    mPendingFailure = FAILURE_NONE;
    if (failure != FAILURE_NONE) {
      Timber.e("HID teardown finished with failure %d", failure);
      mListener.onHidConnectFailed(this, failure);
    } else {
      Timber.i("HID teardown complete");
      mListener.onHidDisconnected(this);
    }
  }

  private void notifyConnectFailed(int error) {
    Timber.e("HID connection could not start (%d)", error);
    mListener.onHidConnectFailed(this, error);
  }

  private final BrailleDisplayController.BrailleDisplayCallback mCallback =
      new BrailleDisplayController.BrailleDisplayCallback() {
        @Override
        public void onConnected(byte[] descriptor) {
          if (mLifecycle.isDisconnecting()) {
            // The connection completed while a local teardown was already in
            // progress. Ask the controller to release again and wait for the
            // real callback (or the watchdog).
            Timber.w("HID connected during local teardown; releasing again");
            requestControllerDisconnect();
            return;
          }
          if (!mLifecycle.markConnected()) {
            Timber.w("Ignoring stale HID connected callback in state %s",
                mLifecycle.getState());
            return;
          }
          if (descriptor == null) {
            Log.e(LOG_TAG, "HID connection returned no report descriptor");
            startTeardown(-5);
            return;
          }
          Log.i(LOG_TAG, "HID braille display connected, descriptor("
              + descriptor.length + "): " + toHex(descriptor));
          Timber.i("HID controller connected, descriptor length %d",
              descriptor.length);
          BrlttyHidNativeHelper.setup(mController, descriptor);
          startDriver();
        }

        @Override
        public void onConnectionFailed(int error) {
          if (mLifecycle.isDisconnecting()) {
            if (mLifecycle.markDisconnected()) {
              Timber.i("HID teardown completed by connection failure %d", error);
              completeDisconnected();
            }
            return;
          }
          if (!mLifecycle.markConnectFailed()) {
            Timber.w("Ignoring stale HID failure callback %d in state %s",
                error, mLifecycle.getState());
            return;
          }
          Log.e(LOG_TAG, "HID connection failed: " + error);
          stopDriver();
          BrlttyHidNativeHelper.reset();
          mListener.onHidConnectFailed(HidDisplayConnection.this, error);
        }

        @Override
        public void onDisconnected() {
          if (!mLifecycle.markDisconnected()) {
            Timber.w("Ignoring duplicate/late HID disconnected callback in state %s",
                mLifecycle.getState());
            return;
          }
          Log.i(LOG_TAG, "HID braille display disconnected");
          completeDisconnected();
        }

        @Override
        public void onInput(byte[] input) {
          if (!mLifecycle.isConnected()) {
            return;
          }
          Timber.tag("HidInput").v("callback %dB: %s", input == null ? 0 : input.length,
              HidInputReportNormalizer.toHex(input));
          BrlttyHidNativeHelper.onInput(input);
          DriverThread dt = mDriverThread;
          if (dt != null) {
            dt.wakeReadOperation();
          }
        }
      };

  private void startDriver() {
    if (mDriverThread != null) {
      return;
    }
    mDriverThread = new DriverThread(new NoOpOutputStream(),
        DeviceFinder.DeviceInfo.forHid(mDevice), mResources, mTablesDir, mContext,
        this /*initListener*/, mInputEventListener);
  }

  @Override
  public void onInit(BrailleDisplayProperties displayProperties) {
    // We're in the driver thread.
    if (displayProperties == null) {
      Log.e(LOG_TAG, "HID driver failed to initialize");
      startTeardown(-2);
      return;
    }
    if (!mLifecycle.isConnected()) {
      Timber.w("Ignoring stale HID driver init in state %s",
          mLifecycle.getState());
      return;
    }
    mListener.onHidConnected(this, displayProperties);
  }
  private void stopDriver() {
    if (mDriverThread != null) {
      mDriverThread.stop();
      mDriverThread = null;
    }
  }

  private static class NoOpOutputStream extends OutputStream {
    @Override
    public void write(int b) throws IOException {
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
    }
  }

  private static class HandlerExecutor implements Executor {
    private final Handler mHandler;

    HandlerExecutor(Handler handler) {
      mHandler = handler;
    }

    @Override
    public void execute(Runnable runnable) {
      mHandler.post(runnable);
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02X", b));
    }
    return sb.toString();
  }

}
