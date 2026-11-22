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
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.googlecode.eyesfree.utils.SettingsStore
import android.util.Log
import com.googlecode.eyesfree.braille.service.R
import timber.log.Timber

/**
 * 用户主动发现窗口时长（毫秒）。比自动重连窗口短：这是用户盯着列表等一轮
 * 扫描的一次性窗口，到期即收，不长期占着电台。
 */
internal const val USER_DISCOVERY_WINDOW_DURATION = 12000L

/**
 * DisplayService 的连接编排：协议判定、连接入口、自动重连与发现窗口。
 *
 * 这些函数从 DisplayService.kt 原样搬出，仍以 DisplayService 作接收者，因此
 * 方法体一字未改，名字也没变；变动的只有声明前缀（类成员 → 同包扩展函数）。
 *
 * 搬出的理由是可读性：DisplayService.kt 原来的 1763 行里，一半是「读偏好、
 * 判断协议、算重连延迟、开关蓝牙发现窗口」这类可以单独读、单独测的策略，
 * 与「生命周期 + IPC 回调」混在一起。拆开后主类只留后者。
 *
 * 之所以用扩展函数而不是新建一个持有 service 引用的类：后者要把每个
 * mXxx 字段改成 service.mXxx，方法体被大面积改写，风险与收益不成比例。
 */

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
    internal fun DisplayService.getHidFailureMessage(): String {
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
    internal fun DisplayService.getUsbHidFailureMessage(error: Int): String {
        if (error == -7) {
            return getString(R.string.connprog_usb_hid_not_found)
        }
        if (error == -8) {
            return getString(R.string.connprog_usb_hid_permission_denied)
        }
        return getString(R.string.connprog_usb_hid_failed, error)
    }

    /** Returns the user-selected connection protocol. */
    internal fun DisplayService.getConnectionProtocol(): String {
        val value = SettingsStore.get(this)
                .getString(ConnectionProtocolPolicy.PREFERENCE_KEY,
                        ConnectionProtocolPolicy.BLUETOOTH)
        return ConnectionProtocolPolicy.normalize(value)
    }

    internal fun DisplayService.isBluetoothProtocol(): Boolean {
        // Auto may still end up on Bluetooth (when nothing is plugged in), so
        // every "can we use the wireless transports" gate must answer true for
        // it; only USB is excluded.
        return !isUsbProtocol()
    }

    internal fun DisplayService.isUsbProtocol(): Boolean {
        return ConnectionProtocolPolicy.USB.equals(getConnectionProtocol())
    }

    internal fun DisplayService.isAutoProtocol(): Boolean {
        return ConnectionProtocolPolicy.AUTO.equals(getConnectionProtocol())
    }

    /** Wired USB must keep retrying even when Bluetooth is off. */
    internal fun DisplayService.isWiredProtocol(): Boolean {
        return isUsbProtocol()
    }

    /** USB attach/detach handling also applies to auto, which may pick USB. */
    internal fun DisplayService.isWiredOrAutoProtocol(): Boolean {
        return isUsbProtocol() || isAutoProtocol()
    }

    /**
     * True when a plugged USB device matches the brltty VID/PID table.
     *
     * Auto uses this to choose between wired and wireless before dialling. It
     * reads the same table the wired path uses, so a "true" here means
     * connectUsb() will find a driver instead of failing on "no match".
     * Any JNI/link failure degrades to "nothing attached" (Bluetooth path).
     */
    internal fun DisplayService.isUsbBrailleDeviceAttached(): Boolean {
        val usb = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val table = BrlttyWrapper.getUsbDriverTable()
        if (table.isEmpty()) {
            return false
        }
        return usb.deviceList.values.any { device ->
            UsbBrailleDevice.candidateDrivers(
                    table, device.vendorId, device.productId).isNotEmpty()
        }
    }

    internal fun DisplayService.isHidRequested(): Boolean {
        return isBluetoothProtocol()
    }

    /** Resets the per-cycle transport fallback state. */
    internal fun DisplayService.resetConnectionAttemptFlags() {
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
    internal fun DisplayService.autoConnectBraille() {
        mAutoReconnectSuppressed = false
        mAutoReconnectAttempt = 0
        mHandler.cancelAutoReconnect()
        if (SettingsStore.get(this)
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
    internal fun DisplayService.startExplicitConnect() {
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
    internal fun DisplayService.retryWhenBluetoothDeviceAppears(intent: Intent?) {
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
    internal fun DisplayService.retryHidWhenInputDeviceAppears() {
        // Wired USB HID devices also arrive as InputDevice additions, even
        // though they are not part of the Bluetooth HID protocol set.
        val wiredUsbHid = isUsbProtocol()
        if ((!isHidRequested() && !wiredUsbHid) || isConnectionActive()) {
            return
        }
        prepareAutomaticRetry("HID input device appeared")
    }

    internal fun DisplayService.prepareAutomaticRetry(reason: String?) {
        if (isConnectionActive()) {
            return
        }
        mHandler.cancelHidControllerTimeout()
        mWaitingForHidController = false
        resetConnectionAttemptFlags()
        Timber.i("%s; retrying display connection", reason)
        autoConnectBraille()
    }

    internal fun DisplayService.isConnectionActive(): Boolean {
        return mConnectionState == DisplayService.STATE_CONNECTED
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
    internal fun DisplayService.scheduleAutoReconnectIfNeeded() {
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
                    DisplayService.DISCOVERY_SESSION_DURATION
                } else {
                    -1L
                })
    }

    internal fun DisplayService.shouldAutoReconnect(): Boolean {
        if (!SettingsStore.get(this)
                .getBoolean("auto_connect", true)) {
            return false
        }
        // 九个 return 会被 detekt ReturnCount 拦住，也不能直接并成一个
        // 七条件 if（ComplexCondition 上限 5）。先算成两个局部布尔。
        val blocked = mAutoReconnectSuppressed || !haveClients()
                || mDataFileState != DisplayService.FILES_EXTRACTED
        val busy = isConnectionActive() || mConnectPending
                || mWaitingForHidController || !isScreenInteractive()
        if (blocked || busy) {
            return false
        }
        // Wired retries even with Bluetooth off; auto with a display plugged
        // in must do the same, otherwise unplug/replug would never reconnect.
        if (isWiredProtocol()
                || (isAutoProtocol() && isUsbBrailleDeviceAttached())) {
            return true
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        return try {
            adapter != null && adapter.isEnabled()
        } catch (e: SecurityException) {
            Timber.w(e,
                    "BLUETOOTH_CONNECT permission denied checking adapter")
            false
        }
    }

    internal fun DisplayService.isScreenInteractive(): Boolean {
        val powerManager =
                getSystemService(Context.POWER_SERVICE) as? PowerManager
        return powerManager == null || powerManager.isInteractive()
    }

    internal fun DisplayService.hasBluetoothScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
    }

    internal fun DisplayService.shouldStartDiscovery(): Boolean {
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
                SettingsStore.get(this)
                        .getBoolean("auto_connect", true),
                mAutoReconnectSuppressed,
                haveClients(),
                mDataFileState == DisplayService.FILES_EXTRACTED,
                isConnectionActive(),
                mConnectPending,
                mWaitingForHidController,
                isScreenInteractive(),
                adapterEnabled,
                hasBluetoothScanPermission())
    }

    /** Opens (or extends) the short discovery window used after a failed connect. */
    internal fun DisplayService.startDiscoveryWindow(reason: String): Boolean {
        if (!shouldStartDiscovery()) {
            Timber.v("Not starting discovery (%s): not eligible", reason)
            return false
        }
        val now = SystemClock.uptimeMillis()
        // 用户主动窗口在用时，自动重连不抢电台、不延长用户窗口。
        if (discoveryWindow.isUserActive && now < discoveryWindow.deadlineMillis) {
            Timber.v("Auto reconnect leaves the user discovery window alone (%s)", reason)
            return false
        }
        if (discoveryWindow.isAutoActive && now < discoveryWindow.deadlineMillis) {
            discoveryWindow.extendAuto(now, DisplayService.DISCOVERY_SESSION_DURATION)
            mDiscoverySessionDeadline = discoveryWindow.deadlineMillis
            mHandler.scheduleDiscoveryTimeout(DisplayService.DISCOVERY_SESSION_DURATION)
            Timber.d("Extending Bluetooth discovery window (%s)", reason)
            startDiscoveryInternal(reason)
            return true
        }
        if (!discoveryWindow.startAuto(now, DisplayService.DISCOVERY_SESSION_DURATION)) {
            return false
        }
        mDiscoverySessionActive = true
        mDiscoveryStartedByUs = false
        mDiscoverySessionDeadline = discoveryWindow.deadlineMillis
        mHandler.scheduleDiscoveryTimeout(DisplayService.DISCOVERY_SESSION_DURATION)
        Timber.i("Starting %d ms Bluetooth discovery window (%s)",
                DisplayService.DISCOVERY_SESSION_DURATION / 1000, reason)
        startDiscoveryInternal(reason)
        return mDiscoverySessionActive
    }

    /**
     * Eligibility for the user-driven discovery window. Unlike the auto-reconnect
     * policy this does not require auto-connect to be enabled or not suppressed:
     * an explicit tap on "find display" must be honored even when the user has
     * turned automatic connection off. Connection busy, no client, data files
     * not ready, screen off, Bluetooth off or missing scan permission still
     * block it.
     */
    internal fun DisplayService.shouldStartUserDiscovery(): Boolean {
        if (isConnectionActive()) {
            return false
        }
        if (!haveClients()) {
            return false
        }
        if (mDataFileState != DisplayService.FILES_EXTRACTED) {
            return false
        }
        if (!isScreenInteractive()) {
            return false
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return false
        val adapterEnabled = try {
            adapter.isEnabled()
        } catch (e: SecurityException) {
            Timber.w(e, "BLUETOOTH_CONNECT denied checking adapter")
            false
        }
        return adapterEnabled && hasBluetoothScanPermission()
    }

    /**
     * Opens the one-shot user discovery window (12 seconds). The user tap takes
     * the radio over any existing auto-reconnect window; when the window ends,
     * is cancelled, Bluetooth goes off or the scan permission is revoked,
     * [finishUserDiscovery] returns everything to the idle rest state without
     * touching the auto-reconnect policy.
     *
     * MIUI / 国产 ROM 后台限制：息屏或后台时 `startDiscovery()` 可能被系统直接
     * 拦下或立刻回传 `ACTION_DISCOVERY_FINISHED`。这种情况下本窗口同样会在
     * 截止时间前收尾，界面上表现为「本轮没有发现设备」，降级路径是让用户到
     * 系统蓝牙设置里完成配对——不要在后台反复重开扫描。
     */
    internal fun DisplayService.startUserDiscoveryWindow(): Boolean {
        if (!shouldStartUserDiscovery()) {
            Timber.v("Not starting user discovery: not eligible")
            return false
        }
        // 让位：先收掉既有自动窗口，避免两个窗口共用一个电台互相打断。
        stopDiscoverySession("user discovery takes over")
        val now = SystemClock.uptimeMillis()
        discoveryWindow.startUser(now, USER_DISCOVERY_WINDOW_DURATION)
        mDiscoverySessionActive = true
        mDiscoveryStartedByUs = false
        mDiscoverySessionDeadline = discoveryWindow.deadlineMillis
        mHandler.scheduleUserDiscoveryTimeout(USER_DISCOVERY_WINDOW_DURATION)
        // 新一轮查找清掉上一轮残留的未配对候选，再把（清空后的）列表推给 UI。
        synchronized(mUnpairedCandidates) {
            mUnpairedCandidates.clear()
        }
        broadcastDeviceList()
        Timber.i("Starting %d ms user discovery window",
                USER_DISCOVERY_WINDOW_DURATION / 1000)
        startDiscoveryInternal("user discovery")
        return mDiscoverySessionActive
    }

    /**
     * Closes the user discovery window and returns to idle. The collected
     * candidates are kept for the current settings page; a new discovery clears
     * them. Pushing the list once more lets the UI freeze the final state
     * (e.g. "search finished, 1 device found").
     */
    internal fun DisplayService.finishUserDiscovery(reason: String) {
        if (!discoveryWindow.isUserActive && !mDiscoverySessionActive) {
            return
        }
        Timber.i("Finishing user discovery window (%s)", reason)
        stopDiscoverySession(reason)
        broadcastDeviceList()
    }

    internal fun DisplayService.startDiscoveryInternal(reason: String) {
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

    internal fun DisplayService.handleDiscoveryFinished() {
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
        mHandler.scheduleDiscoveryRestart(DisplayService.DISCOVERY_RESTART_DELAY)
    }

    internal fun DisplayService.handleDiscoveredDevice(intent: Intent?) {
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
        var bondState = BluetoothDevice.BOND_NONE
        try {
            address = device.getAddress()
            name = device.getName()
            bondState = device.bondState
        } catch (e: SecurityException) {
            Timber.w(e, "BLUETOOTH_CONNECT permission denied reading discovery result")
        }
        if (address == null) {
            return
        }

        // 用户主动发现窗口：只把名称命中点显器名称表的未配对设备收进候选，
        // 不自动连接——配对动作由用户在列表里触发。
        if (discoveryWindow.isUserActive) {
            if (bondState != BluetoothDevice.BOND_BONDED
                    && DeviceFinder.matchesKnownName(name)) {
                addUnpairedCandidate(address, name)
            }
            return
        }

        // 既有自动重连路径：只认已配对的已知设备，语义保持不变。
        if (!DeviceFinder(this).isKnownDevice(address)) {
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

    /**
     * Adds a freshly discovered unpaired braille display to the candidate list,
     * deduplicated by address. Re-discovering the same address refreshes the
     * name (the remote name may arrive after the first inquiry result).
     */
    internal fun DisplayService.addUnpairedCandidate(address: String, name: String?) {
        val normalized = address.uppercase(java.util.Locale.US)
        synchronized(mUnpairedCandidates) {
            val existing = mUnpairedCandidates.firstOrNull {
                it.address.equals(normalized, ignoreCase = true)
            }
            if (existing != null) {
                if (existing.name != name) {
                    mUnpairedCandidates.remove(existing)
                    mUnpairedCandidates.add(DiscoveredDevice(normalized, name))
                }
            } else {
                mUnpairedCandidates.add(DiscoveredDevice(normalized, name))
            }
        }
        Timber.i("Added unpaired candidate %s (%s)", name, normalized)
        broadcastDeviceList()
    }

    internal fun DisplayService.stopDiscoverySession(reason: String) {
        mDiscoverySessionActive = false
        mDiscoverySessionDeadline = 0
        discoveryWindow.finish()
        mHandler.cancelDiscoveryTimeout()
        mHandler.cancelDiscoveryRestart()
        mHandler.cancelUserDiscoveryTimeout()
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
