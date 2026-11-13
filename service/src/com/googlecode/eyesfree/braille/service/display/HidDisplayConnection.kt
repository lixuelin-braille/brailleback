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
import android.accessibilityservice.BrailleDisplayController
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Resources
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.googlecode.eyesfree.utils.LogUtils
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.ArrayList
import java.util.concurrent.Executor
import timber.log.Timber

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
class HidDisplayConnection : DriverThread.OnInitListener {

  /** Callbacks to the DisplayService. */
  interface Listener {
    fun onHidConnected(
        source: HidDisplayConnection?, properties: BrailleDisplayProperties?)
    fun onHidDisconnected(source: HidDisplayConnection?)
    fun onHidConnectFailed(source: HidDisplayConnection?, error: Int)
  }

  companion object {
    private val LOG_TAG: String = HidDisplayConnection::class.java.simpleName

    private const val DISCONNECT_TIMEOUT_MS = 2000L
    private const val USB_PERMISSION_TIMEOUT_MS = 15000L
    private const val FAILURE_NONE = Int.MIN_VALUE
    private const val ACTION_USB_HID_PERMISSION =
        "com.googlecode.eyesfree.brailleback.action.USB_HID_PERMISSION"

    private fun toHex(bytes: ByteArray): String {
      val sb = StringBuilder(bytes.size * 2)
      for (b in bytes) {
        sb.append(String.format("%02X", b))
      }
      return sb.toString()
    }
  }

  private val mContext: Context
  private val mResources: Resources
  private val mTablesDir: File
  private val mListener: Listener
  private val mInputEventListener: DriverThread.OnInputEventListener
  private val mExecutor: Executor
  private val mMainHandler = Handler(Looper.getMainLooper())
  private val mLifecycle = HidConnectionLifecycle()

  @Volatile private var mController: BrailleDisplayController? = null
  @Volatile private var mDevice: BluetoothDevice? = null
  @Volatile private var mUsbDevice: UsbDevice? = null
  @Volatile private var mPendingUsbController: BrailleDisplayController? = null
  private var mUsbManager: UsbManager? = null
  private var mUsbPermissionReceiver: BroadcastReceiver? = null
  private var mUsbPermissionSettled = false
  @Volatile private var mDriverThread: DriverThread? = null
  @Volatile private var mPendingFailure = FAILURE_NONE

  private val mUsbPermissionTimeout = object : Runnable {
    override fun run() {
      val granted = mUsbDevice != null && mUsbManager != null &&
          mUsbManager!!.hasPermission(mUsbDevice!!)
      onUsbPermissionResult(granted)
    }
  }

  private val mDisconnectTimeout = object : Runnable {
    override fun run() {
      if (!mLifecycle.finishDisconnectTimeout()) {
        return
      }
      Timber.w("HID disconnect timed out after %d ms; completing teardown",
          DISCONNECT_TIMEOUT_MS)
      // Best effort: there is no callback to wait for any more, but asking the
      // controller to release again may help the next attempt.
      requestControllerDisconnect()
      completeDisconnected()
    }
  }

  constructor(context: Context, resources: Resources, tablesDir: File,
      listener: Listener, inputEventListener: DriverThread.OnInputEventListener) {
    mContext = context
    mResources = resources
    mTablesDir = tablesDir
    mListener = listener
    mInputEventListener = inputEventListener
    mExecutor = HandlerExecutor(Handler(Looper.getMainLooper()))
  }

  /** Connects to the HID braille display with the given bluetooth address. */
  fun connect(controller: BrailleDisplayController?, deviceAddress: String?) {
    if (controller == null || deviceAddress == null) {
      LogUtils.e(this, "Cannot connect: missing controller or device address")
      notifyConnectFailed(-1)
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        && mContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
      LogUtils.e(this, "BLUETOOTH_CONNECT permission not granted")
      notifyConnectFailed(-6)
      return
    }
    val adapter = BluetoothAdapter.getDefaultAdapter()
    if (adapter == null) {
      notifyConnectFailed(-1)
      return
    }
    val device: BluetoothDevice = try {
      adapter.getRemoteDevice(deviceAddress)
    } catch (e: IllegalArgumentException) {
      LogUtils.e(this, "Invalid HID braille display address "
          + deviceAddress + ": " + e)
      notifyConnectFailed(-5)
      return
    } catch (e: SecurityException) {
      LogUtils.e(this, "Invalid HID braille display address "
          + deviceAddress + ": " + e)
      notifyConnectFailed(-5)
      return
    }
    if (!mLifecycle.beginConnect()) {
      Timber.w("Ignoring HID connect request in state %s", mLifecycle.getState())
      return
    }
    mController = controller
    mDevice = device
    mUsbDevice = null
    LogUtils.i(this, "Connecting to HID braille display " + deviceAddress)
    Timber.i("Starting HID connection to %s", deviceAddress)
    try {
      controller.connect(device, mExecutor, mCallback)
    } catch (e: IllegalStateException) {
      // The system already holds a Braille display connection for this
      // accessibility service (e.g. a previous connection that has not been
      // fully torn down yet, or the display is held by another service).
      // Release it and wait for the controller to settle before reporting a
      // clean failure, so the service does not race the system teardown.
      LogUtils.e(this, "BrailleDisplayController already connected: " + e)
      startTeardown(-3)
    } catch (e: RuntimeException) {
      LogUtils.e(this, "BrailleDisplayController.connect() failed: " + e)
      startTeardown(-4)
    }
  }

  /**
   * Connects to a wired USB HID braille display (Android 15+ / Braille HID
   * standard). Ported from Google TalkBack 16.2's {@code UsbHidConnector}: the
   * framework's {@code BrailleDisplayController.connect(UsbDevice, ...)}
   * overload supplies the report descriptor and the transport, so the same
   * callbacks, BRLTTY "hid" driver and BrlttyHidNativeHelper used by the
   * Bluetooth HID path are reused verbatim. No native change is required.
   *
   * <p>Unlike Bluetooth, USB needs an explicit permission grant before the
   * framework lets an accessibility service open the device.
   */
  fun connectUsb(controller: BrailleDisplayController?, usbManager: UsbManager?) {
    if (controller == null || usbManager == null) {
      LogUtils.e(this, "Cannot connect USB HID: controller or UsbManager missing")
      notifyConnectFailed(-7)
      return
    }
    mUsbManager = usbManager
    val plugged = usbManager.getDeviceList()
    val candidates = ArrayList<UsbHidCandidate>()
    for (device in plugged.values) {
      val classes = IntArray(device.getInterfaceCount())
      val subclasses = IntArray(classes.size)
      val protocols = IntArray(classes.size)
      for (i in classes.indices) {
        classes[i] = device.getInterface(i).getInterfaceClass()
        subclasses[i] = device.getInterface(i).getInterfaceSubclass()
        protocols[i] = device.getInterface(i).getInterfaceProtocol()
      }
      candidates.add(UsbHidCandidate(device.getDeviceId(),
          device.getVendorId(), device.getProductId(), classes, subclasses,
          protocols))
    }
    LogUtils.i(this, "USB HID: attached devices=" + plugged.size)
    val match = UsbHidDevicePolicy.firstCompatible(candidates)
    if (match == null) {
      LogUtils.w(this, "No attached USB device exposes a HID interface")
      notifyConnectFailed(-7)
      return
    }
    var selected: UsbDevice? = null
    for (device in plugged.values) {
      if (device.getDeviceId() == match.deviceId) {
        selected = device
        break
      }
    }
    if (selected == null) {
      notifyConnectFailed(-7)
      return
    }
    mUsbDevice = selected
    mPendingUsbController = controller
    LogUtils.i(this, "USB HID display id=" + selected.getDeviceId()
        + " vid=" + selected.getVendorId()
        + " pid=" + selected.getProductId())
    if (!usbManager.hasPermission(selected)) {
      requestUsbPermission(selected)
      return
    }
    finishUsbConnect(controller, selected)
  }

  private fun requestUsbPermission(device: UsbDevice) {
    LogUtils.i(this, "Requesting USB permission for HID device id="
        + device.getDeviceId())
    mUsbPermissionSettled = false
    val filter = IntentFilter(ACTION_USB_HID_PERMISSION)
    mUsbPermissionReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        val granted = intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        if (granted == null || mUsbDevice == null
            || granted.getDeviceId() != mUsbDevice!!.getDeviceId()) {
          LogUtils.w(this, "Ignoring USB permission for a different device")
          return
        }
        onUsbPermissionResult(intent?.getBooleanExtra(
            UsbManager.EXTRA_PERMISSION_GRANTED, false) ?: false)
      }
    }
    if (Build.VERSION.SDK_INT >= 33) {
      mContext.registerReceiver(mUsbPermissionReceiver!!, filter,
          Context.RECEIVER_NOT_EXPORTED)
    } else {
      mContext.registerReceiver(mUsbPermissionReceiver!!, filter)
    }
    val intent = Intent(ACTION_USB_HID_PERMISSION)
        .setPackage(mContext.getPackageName())
    var flags = PendingIntent.FLAG_UPDATE_CURRENT
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      // The system fills the permission result into this PendingIntent; an
      // immutable one drops those extras and always reports "denied".
      flags = flags or PendingIntent.FLAG_MUTABLE
    }
    mUsbManager!!.requestPermission(device,
        PendingIntent.getBroadcast(mContext, 0, intent, flags))
    // Some OEMs swallow the broadcast; the poll is the fallback.
    mMainHandler.postDelayed(mUsbPermissionTimeout, USB_PERMISSION_TIMEOUT_MS)
  }

  @Synchronized
  private fun onUsbPermissionResult(granted: Boolean) {
    if (mUsbPermissionSettled || mLifecycle.isDisconnecting()) {
      return
    }
    mUsbPermissionSettled = true
    mMainHandler.removeCallbacks(mUsbPermissionTimeout)
    unregisterUsbReceiver()
    // hasPermission() is authoritative; "granted" may be false merely because
    // an OEM dropped the system's PendingIntent extras.
    if (mUsbDevice == null || mUsbManager == null
        || !mUsbManager!!.hasPermission(mUsbDevice!!)) {
      LogUtils.w(this, "USB permission for HID display was not granted")
      notifyConnectFailed(-8)
      return
    }
    finishUsbConnect(mPendingUsbController, mUsbDevice!!)
  }

  private fun finishUsbConnect(controller: BrailleDisplayController?,
      device: UsbDevice) {
    if (controller == null || !mLifecycle.beginConnect()) {
      Timber.w("Ignoring USB HID connect request in state %s",
          mLifecycle.getState())
      return
    }
    mController = controller
    mDevice = null
    Timber.i("Connecting to wired USB HID braille display")
    try {
      controller.connect(device, mExecutor, mCallback)
    } catch (e: IllegalStateException) {
      LogUtils.e(this, "BrailleDisplayController already connected: " + e)
      startTeardown(-3)
    } catch (e: RuntimeException) {
      LogUtils.e(this,
          "BrailleDisplayController.connect(UsbDevice) failed: " + e)
      startTeardown(-4)
    }
  }

  private fun cleanupUsbPermission() {
    mMainHandler.removeCallbacks(mUsbPermissionTimeout)
    unregisterUsbReceiver()
  }

  private fun unregisterUsbReceiver() {
    if (mUsbPermissionReceiver == null) {
      return
    }
    try {
      mContext.unregisterReceiver(mUsbPermissionReceiver!!)
    } catch (e: IllegalArgumentException) {
      LogUtils.d(this, "USB permission receiver was already unregistered")
    }
    mUsbPermissionReceiver = null
  }

  /**
   * Writes dot patterns to the HID display driver. Forwarded to the native
   * BRLTTY "hid" driver which prepares the output report (report ID + cells)
   * and sends it through the {@code BrailleDisplayController}.
   */
  fun writeWindow(patterns: ByteArray) {
    val dt = mDriverThread
    if (dt != null) {
      dt.writeWindow(patterns)
    } else {
      LogUtils.w(this, "writeWindow before driver is ready; dropping "
          + patterns.size + " cells")
    }
  }

  /** Tears down the HID connection after the system releases the controller. */
  fun disconnect() {
    startTeardown(FAILURE_NONE)
  }

  /** True while the system controller is being released asynchronously. */
  fun isDisconnecting(): Boolean {
    return mLifecycle.isDisconnecting()
  }

  @Synchronized
  private fun startTeardown(failure: Int) {
    cleanupUsbPermission()
    val started = mLifecycle.beginDisconnect()
    if (!started && !mLifecycle.isDisconnecting()) {
      return
    }
    if (!started) {
      Timber.i("HID teardown already in progress")
      return
    }
    if (failure != FAILURE_NONE) {
      mPendingFailure = failure
    }
    Timber.i("Requesting HID teardown (failure=%d, controllerConnected=%s)",
        mPendingFailure, isControllerConnected())
    mMainHandler.removeCallbacks(mDisconnectTimeout)
    stopDriver()
    BrlttyHidNativeHelper.reset()
    requestControllerDisconnect()
    mMainHandler.postDelayed(mDisconnectTimeout, DISCONNECT_TIMEOUT_MS)
  }

  private fun requestControllerDisconnect() {
    val controller = mController
    if (controller == null) {
      return
    }
    try {
      // Do not gate this on isConnected(): a connect request may still be in
      // flight, and it must be cancelled before the next protocol starts.
      controller.disconnect()
    } catch (e: RuntimeException) {
      LogUtils.w(this, "Releasing BrailleDisplayController failed: " + e)
      Timber.w(e, "Releasing BrailleDisplayController failed")
    }
  }

  private fun isControllerConnected(): Boolean {
    val controller = mController
    try {
      return controller != null && controller.isConnected()
    } catch (e: RuntimeException) {
      return false
    }
  }

  @Synchronized
  private fun completeDisconnected() {
    cleanupUsbPermission()
    mMainHandler.removeCallbacks(mDisconnectTimeout)
    stopDriver()
    BrlttyHidNativeHelper.reset()
    val failure = mPendingFailure
    mPendingFailure = FAILURE_NONE
    if (failure != FAILURE_NONE) {
      Timber.e("HID teardown finished with failure %d", failure)
      mListener.onHidConnectFailed(this, failure)
    } else {
      Timber.i("HID teardown complete")
      mListener.onHidDisconnected(this)
    }
  }

  private fun notifyConnectFailed(error: Int) {
    cleanupUsbPermission()
    // 没插点显器时这条每次重连都会出现，属正常回退；用 WARNING 避免把
    // 用户日志刷成一堆“E”，真正需要关注的错误反而被淹没。
    Timber.w("HID connection could not start (%d)", error)
    mListener.onHidConnectFailed(this, error)
  }

  private val mCallback: BrailleDisplayController.BrailleDisplayCallback =
      object : BrailleDisplayController.BrailleDisplayCallback {
        override fun onConnected(descriptor: ByteArray) {
          if (mLifecycle.isDisconnecting()) {
            // The connection completed while a local teardown was already in
            // progress. Ask the controller to release again and wait for the
            // real callback (or the watchdog).
            Timber.w("HID connected during local teardown; releasing again")
            requestControllerDisconnect()
            return
          }
          if (!mLifecycle.markConnected()) {
            Timber.w("Ignoring stale HID connected callback in state %s",
                mLifecycle.getState())
            return
          }
          if (descriptor == null) {
            LogUtils.e(this, "HID connection returned no report descriptor")
            startTeardown(-5)
            return
          }
          LogUtils.i(this, "HID braille display connected, descriptor("
              + descriptor.size + "): " + toHex(descriptor))
          Timber.i("HID controller connected, descriptor length %d",
              descriptor.size)
          BrlttyHidNativeHelper.setup(mController, descriptor)
          startDriver()
        }

        override fun onConnectionFailed(error: Int) {
          if (mLifecycle.isDisconnecting()) {
            if (mLifecycle.markDisconnected()) {
              Timber.i("HID teardown completed by connection failure %d", error)
              completeDisconnected()
            }
            return
          }
          if (!mLifecycle.markConnectFailed()) {
            Timber.w("Ignoring stale HID failure callback %d in state %s",
                error, mLifecycle.getState())
            return
          }
          LogUtils.e(this, "HID connection failed: " + error)
          stopDriver()
          BrlttyHidNativeHelper.reset()
          mListener.onHidConnectFailed(this@HidDisplayConnection, error)
        }

        override fun onDisconnected() {
          if (!mLifecycle.markDisconnected()) {
            Timber.w("Ignoring duplicate/late HID disconnected callback in state %s",
                mLifecycle.getState())
            return
          }
          LogUtils.i(this, "HID braille display disconnected")
          completeDisconnected()
        }

        override fun onInput(input: ByteArray) {
          if (!mLifecycle.isConnected()) {
            return
          }
          Timber.tag("HidInput").v("callback %dB: %s",
              if (input == null) 0 else input.size,
              HidInputReportNormalizer.toHex(input))
          BrlttyHidNativeHelper.onInput(input)
          val dt = mDriverThread
          if (dt != null) {
            dt.wakeReadOperation()
          }
        }
      }

  private fun startDriver() {
    if (mDriverThread != null) {
      return
    }
    // Wired USB HID and Bluetooth HID share the same native "hid" driver.
    // The only difference is how DeviceInfo describes the (non-)Bluetooth
    // device; the transport itself is always BrailleDisplayController.
    val info = if (mUsbDevice != null) {
      DeviceFinder.DeviceInfo.forUsb("hid", "hid:name=unused")
    } else {
      DeviceFinder.DeviceInfo.forHid(mDevice)
    }
    mDriverThread = DriverThread(NoOpOutputStream(),
        info, mResources, mTablesDir, mContext,
        this /*initListener*/, mInputEventListener)
  }

  override fun onInit(displayProperties: BrailleDisplayProperties?) {
    // We're in the driver thread.
    if (displayProperties == null) {
      LogUtils.e(this, "HID driver failed to initialize")
      startTeardown(-2)
      return
    }
    if (!mLifecycle.isConnected()) {
      Timber.w("Ignoring stale HID driver init in state %s",
          mLifecycle.getState())
      return
    }
    mListener.onHidConnected(this, displayProperties)
  }

  private fun stopDriver() {
    if (mDriverThread != null) {
      mDriverThread!!.stop()
      mDriverThread = null
    }
  }

  private class NoOpOutputStream : OutputStream() {
    override fun write(b: Int) {
    }

    override fun write(b: ByteArray?, off: Int, len: Int) {
    }
  }

  private class HandlerExecutor(handler: Handler) : Executor {
    private val mHandler: Handler = handler

    override fun execute(runnable: Runnable) {
      mHandler.post(runnable)
    }
  }
}
