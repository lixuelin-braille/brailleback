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

import android.accessibilityservice.BrailleDisplayController;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Executor;

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
public class HidDisplayConnection implements DriverThread.OnInitListener {

  /** Callbacks to the DisplayService. */
  public interface Listener {
    void onHidConnected(BrailleDisplayProperties properties);
    void onHidDisconnected();
    void onHidConnectFailed(int error);
  }

  private static final String LOG_TAG = HidDisplayConnection.class.getSimpleName();

  private static final int STATE_IDLE = 0;
  private static final int STATE_CONNECTING = 1;
  private static final int STATE_CONNECTED = 2;

  private final Context mContext;
  private final Resources mResources;
  private final File mTablesDir;
  private final Listener mListener;
  private final DriverThread.OnInputEventListener mInputEventListener;
  private final Executor mExecutor;

  private BrailleDisplayController mController;
  private BluetoothDevice mDevice;
  private DriverThread mDriverThread;
  private int mState = STATE_IDLE;

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
    if (mState != STATE_IDLE) {
      return;
    }
    mController = controller;
    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    if (adapter == null) {
      mListener.onHidConnectFailed(-1);
      return;
    }
    mDevice = adapter.getRemoteDevice(deviceAddress);
    mState = STATE_CONNECTING;
    Log.i(LOG_TAG, "Connecting to HID braille display " + deviceAddress);
    try {
      mController.connect(mDevice, mExecutor, mCallback);
    } catch (IllegalStateException e) {
      // The system already holds a Braille display connection for this
      // accessibility service (e.g. a previous connection that has not been
      // fully torn down yet, or the display is held by another service).
      // Release it and report a clean failure so the service can retry/fall
      // back instead of crashing.
      Log.e(LOG_TAG, "BrailleDisplayController already connected: " + e);
      mState = STATE_IDLE;
      try {
        mController.disconnect();
      } catch (RuntimeException ignored) {
      }
      stopDriver();
      BrlttyHidNativeHelper.reset();
      mListener.onHidConnectFailed(-3);
    } catch (RuntimeException e) {
      Log.e(LOG_TAG, "BrailleDisplayController.connect() failed: " + e);
      mState = STATE_IDLE;
      stopDriver();
      BrlttyHidNativeHelper.reset();
      mListener.onHidConnectFailed(-4);
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
    }
  }

  /**
   * Tears down the HID connection and the native driver. Always notifies the
   * listener synchronously so a pending protocol-switch reconnect is not left
   * hanging waiting for an asynchronous callback that may never arrive.
   */
  public void disconnect() {
    if (mState == STATE_IDLE) {
      return;
    }
    mState = STATE_IDLE;
    if (mController != null && mController.isConnected()) {
      // Asynchronous; the late onDisconnected callback is ignored because
      // mState is already STATE_IDLE (no double notification).
      mController.disconnect();
    }
    stopDriver();
    BrlttyHidNativeHelper.reset();
    mListener.onHidDisconnected();
  }

  private final BrailleDisplayController.BrailleDisplayCallback mCallback =
      new BrailleDisplayController.BrailleDisplayCallback() {
        @Override
        public void onConnected(byte[] descriptor) {
          if (mState != STATE_CONNECTING) {
            return;
          }
          mState = STATE_CONNECTED;
          Log.i(LOG_TAG, "HID braille display connected, descriptor("
              + descriptor.length + "): " + toHex(descriptor));
          BrlttyHidNativeHelper.setup(mController, descriptor);
          startDriver();
        }

        @Override
        public void onConnectionFailed(int error) {
          if (mState != STATE_CONNECTING) {
            return;
          }
          Log.e(LOG_TAG, "HID connection failed: " + error);
          mState = STATE_IDLE;
          stopDriver();
          BrlttyHidNativeHelper.reset();
          mListener.onHidConnectFailed(error);
        }

        @Override
        public void onDisconnected() {
          if (mState == STATE_IDLE) {
            return;
          }
          Log.i(LOG_TAG, "HID braille display disconnected");
          mState = STATE_IDLE;
          stopDriver();
          BrlttyHidNativeHelper.reset();
          mListener.onHidDisconnected();
        }

        @Override
        public void onInput(byte[] input) {
          if (mState != STATE_CONNECTED) {
            return;
          }
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
      mState = STATE_IDLE;
      if (mController != null && mController.isConnected()) {
        // Release the system connection; the late onDisconnected callback
        // is ignored because mState is already STATE_IDLE.
        mController.disconnect();
      }
      stopDriver();
      BrlttyHidNativeHelper.reset();
      mListener.onHidConnectFailed(-2);
      return;
    }
    mListener.onHidConnected(displayProperties);
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
