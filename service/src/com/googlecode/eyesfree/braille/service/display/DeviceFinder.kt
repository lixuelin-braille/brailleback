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
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.preference.PreferenceManager
import android.util.Log
import com.googlecode.eyesfree.braille.service.R
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
import java.util.UUID
import java.util.regex.Pattern

/**
 * Finds supported devices among bonded devices.
 */
class DeviceFinder(context: Context) {

    private val mContext: Context = context.applicationContext
    private val mSharedPreferences: SharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(mContext)

    /**
     * Information about a supported bonded bluetooth device.
     */
    class DeviceInfo(
            private val mBluetoothDevice: BluetoothDevice?,
            private val mDriverCode: String?,
            private val mSdpUuid: UUID?,
            private val mConnectSecurely: Boolean,
            private val mFriendlyKeyNames: Map<String, Int>,
            private val mBrailleDevice: String?) {

        constructor(
                bluetoothDevice: BluetoothDevice?,
                driverCode: String?,
                sdpUuid: UUID?,
                connectSecurely: Boolean,
                friendlyKeyNames: Map<String, Int>
        ) : this(bluetoothDevice, driverCode, sdpUuid, connectSecurely,
                friendlyKeyNames, null)

        /**
         * The braille device string passed to the native BRLTTY driver, e.g.
         * {@code "bluetooth:XX:XX:..."} for classic RFCOMM or
         * {@code "hid:name=unused"} for the Braille HID standard driver.
         * {@code null} means the caller should derive it from the bluetooth
         * address (the RFCOMM case).
         */
        fun getBrailleDevice(): String? = mBrailleDevice

        /**
         * Returns the bluetooth device from the system.
         */
        fun getBluetoothDevice(): BluetoothDevice? = mBluetoothDevice

        /**
         * Returns the brltty driver code to use for this device.
         */
        fun getDriverCode(): String? = mDriverCode

        /**
         * Returns the service record uuid to use when connecting to
         * this device.
         */
        fun getSdpUuid(): UUID? = mSdpUuid

        /**
         * Returns whether to connect securely (preferred)
         * or not.
         * @see BluetoothDevice#createInsecureRfcommSocketToServiceRecord
         * @see BluetoothDevice#createRfcommSocketToServiceRecord
         */
        fun getConnectSecurely(): Boolean = mConnectSecurely

        /**
         */
        fun getFriendlyKeyNames(): Map<String, Int> = mFriendlyKeyNames

        companion object {
            /**
             * Builds a {@link DeviceInfo} for the Braille HID standard (usage page
             * 0x41) driver ("hid" driver code in BRLTTY).  The native driver does
             * all of the HID transport through {@code BrailleDisplayController},
             * so the SDP UUID is irrelevant here.
             */
            @JvmStatic
            fun forHid(bluetoothDevice: BluetoothDevice?): DeviceInfo {
                return DeviceInfo(bluetoothDevice, "hid", null, true,
                        KeyNameMapBuilder()
                                .dots8()
                                .add("Space", R.string.key_Space)
                                .add("PanLeft", R.string.key_pan_left)
                                .add("PanRight", R.string.key_pan_right)
                                .add("DPadUp", R.string.key_dpad_up)
                                .add("DPadDown", R.string.key_dpad_down)
                                .add("DPadLeft", R.string.key_dpad_left)
                                .add("DPadRight", R.string.key_dpad_right)
                                .add("DPadCenter", R.string.key_dpad_center)
                                .add("RockerUp", R.string.key_rocker_up)
                                .add("RockerDown", R.string.key_rocker_down)
                                .build(),
                        "hid:name=unused")
            }

            /**
             * Builds a {@link DeviceInfo} for a wired USB display driven by brltty.
             *
             * <p>There is no {@link BluetoothDevice} on this path, and the native
             * USB backend performs its own I/O, so the only fields that matter are
             * the brltty driver code and the {@code usb:...} device parameter.
             * {@link #getBrailleDevice()} is non-null, which makes
             * {@code BrlttyWrapper.start()} use it directly instead of deriving a
             * {@code bluetooth:XX:XX:...} address.
             *
             * <p>The friendly key names are the generic HID set: a USB display may
             * expose additional keys, and those bindings are filtered out by
             * {@code BrlttyWrapper.getKeyMap()} rather than shown with raw names.
             * Per-model maps can be added once real devices have been tested.
             */
            @JvmStatic
            fun forUsb(driverCode: String?, brailleDevice: String?): DeviceInfo {
                return DeviceInfo(null, driverCode, null, true,
                        KeyNameMapBuilder()
                                .dots8()
                                .routing()
                                .add("Space", R.string.key_Space)
                                .add("Backspace", R.string.key_Backspace)
                                .add("PanLeft", R.string.key_pan_left)
                                .add("PanRight", R.string.key_pan_right)
                                .add("DPadUp", R.string.key_dpad_up)
                                .add("DPadDown", R.string.key_dpad_down)
                                .add("DPadLeft", R.string.key_dpad_left)
                                .add("DPadRight", R.string.key_dpad_right)
                                .add("DPadCenter", R.string.key_dpad_center)
                                .add("RockerUp", R.string.key_rocker_up)
                                .add("RockerDown", R.string.key_rocker_down)
                                .build(),
                        brailleDevice)
            }
        }
    }

    /**
     * Returns a list of bonded and supported devices in the order they
     * should be tried.
     *
     * <p>On Android 12+ (API 31) reading the bonded device list requires
     * {@code BLUETOOTH_CONNECT}; if the permission is missing this returns an
     * empty list instead of crashing with a {@link SecurityException}.
     */
    fun findDevices(): List<DeviceInfo> {
        if (!hasBluetoothConnectPermission()) {
            return Collections.emptyList()
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            return Collections.emptyList()
        }

        val ret: MutableList<DeviceInfo> = ArrayList()
        try {
            // getBondedDevices() may contain null.
            val bondedDevices = adapter.bondedDevices
            if (bondedDevices == null) {
                return ret
            }
            for (dev in bondedDevices) {
                if (dev == null) {
                    continue
                }
                for (matcher in SUPPORTED_DEVICES) {
                    val matched = matcher.match(dev)
                    if (matched != null) {
                        ret.add(matched)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "BLUETOOTH_CONNECT permission denied; no devices", e)
            return Collections.emptyList()
        }
        val lastAddress = mSharedPreferences.getString(
                LAST_CONNECTED_DEVICE_KEY, null)
        if (lastAddress != null) {
            // If the last device that was successfully connected is
            // not already first in the list, put it there.
            // (Hence, the 1 below is intentional).
            for (i in 1 until ret.size) {
                var address: String? = null
                try {
                    address = ret.get(i).getBluetoothDevice()?.address
                } catch (e: SecurityException) {
                    Log.w(LOG_TAG,
                            "BLUETOOTH_CONNECT permission denied reading device address",
                            e)
                    break
                }
                if (lastAddress == address) {
                    java.util.Collections.swap(ret, 0, i)
                }
            }
        }
        return ret
    }

    /** Returns true if the app can read the bonded Bluetooth devices. */
    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // Pre-Android 12: BLUETOOTH is a normal permission granted at install.
            return true
        }
        return mContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
    }

    fun rememberSuccessfulConnection(info: DeviceInfo) {
        val device = info.getBluetoothDevice()
        var address: String? = null
        try {
            address = device?.address
        } catch (e: SecurityException) {
            Log.w(LOG_TAG,
                    "BLUETOOTH_CONNECT permission denied; not remembering device",
                    e)
            return
        }
        if (address == null) {
            return
        }
        val editor = mSharedPreferences.edit()
        editor.putString(LAST_CONNECTED_DEVICE_KEY, address)
        editor.apply()
    }

    /**
     * Returns true when a just-bonded device is a braille display this app
     * knows how to use. A newly paired device is not necessarily a braille
     * display (headphones and Bluetooth serial adapters also generate bond
     * broadcasts), so the bond broadcast must not bypass this filter.
     */
    fun isKnownDevice(address: String?): Boolean {
        if (address == null) {
            return false
        }
        val supportedAddresses: MutableList<String?> = ArrayList()
        for (info in findDevices()) {
            try {
                val supportedAddress = info.getBluetoothDevice()?.address
                if (supportedAddress != null) {
                    supportedAddresses.add(supportedAddress)
                }
            } catch (e: SecurityException) {
                Log.w(LOG_TAG,
                        "BLUETOOTH_CONNECT permission denied reading address",
                        e)
                break
            }
        }
        val lastAddress = mSharedPreferences.getString(
                LAST_CONNECTED_DEVICE_KEY, null)
        return shouldAutoConnectBondedDevice(
                address, lastAddress, supportedAddresses)
    }

    companion object {
        @JvmStatic
        fun shouldAutoConnectBondedDevice(
                address: String?,
                lastAddress: String?,
                supportedAddresses: List<String?>?
        ): Boolean {
            if (address == null || address.isEmpty()) {
                return false
            }
            if (lastAddress != null &&
                    address.equals(lastAddress, ignoreCase = true)) {
                return true
            }
            if (supportedAddresses == null) {
                return false
            }
            for (supportedAddress in supportedAddresses) {
                if (supportedAddress != null &&
                        address.equals(supportedAddress, ignoreCase = true)) {
                    return true
                }
            }
            return false
        }
    }
}

private const val LOG_TAG = "DeviceFinder"

private val SERIAL_BOARD_UUID: UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

private const val LAST_CONNECTED_DEVICE_KEY = "lastBluetoothDevice"

private interface SupportedDevice {
    fun match(bluetoothDevice: BluetoothDevice): DeviceFinder.DeviceInfo?
}

private class NameRegexSupportedDevice : SupportedDevice {
    private val mDriverCode: String
    private val mConnectSecurely: Boolean
    private val mFriendlyKeyNames: Map<String, Int>
    private val mNameRegexes: Array<out Pattern>

    constructor(
            driverCode: String,
            connectSecurely: Boolean,
            friendlyKeyNames: Map<String, Int>,
            vararg nameRegexes: Pattern
    ) {
        mDriverCode = driverCode
        mConnectSecurely = connectSecurely
        mFriendlyKeyNames = friendlyKeyNames
        mNameRegexes = nameRegexes
    }

    override fun match(bluetoothDevice: BluetoothDevice): DeviceFinder.DeviceInfo? {
        var name: String? = null
        try {
            name = bluetoothDevice.name
        } catch (e: SecurityException) {
            Log.w(LOG_TAG,
                    "BLUETOOTH_CONNECT permission denied reading device name",
                    e)
            return null
        }
        if (name == null) {
            return null
        }
        for (nameRegex in mNameRegexes) {
            if (nameRegex.matcher(name).lookingAt()) {
                return DeviceFinder.DeviceInfo(bluetoothDevice, mDriverCode,
                        SERIAL_BOARD_UUID, mConnectSecurely,
                        mFriendlyKeyNames)
            }
        }
        return null
    }

    override fun toString(): String {
        val s = StringBuilder()
        s.append(mDriverCode)
        for (p in mNameRegexes) {
            s.append(" " + p)
        }
        return s.toString()
    }
}

private class KeyNameMapBuilder {
    private val mNameMap: MutableMap<String, Int> = HashMap()

    /**
     * Adds a mapping from the internal {@code name} to a friendly name
     * with resource id {@code resId}.
     */
    fun add(name: String, resId: Int): KeyNameMapBuilder {
        mNameMap.put(name, resId)
        return this
    }

    fun dots6(): KeyNameMapBuilder {
        add("Dot1", R.string.key_Dot1)
        add("Dot2", R.string.key_Dot2)
        add("Dot3", R.string.key_Dot3)
        add("Dot4", R.string.key_Dot4)
        add("Dot5", R.string.key_Dot5)
        add("Dot6", R.string.key_Dot6)
        return this
    }

    fun dots8(): KeyNameMapBuilder {
        dots6()
        add("Dot7", R.string.key_Dot7)
        add("Dot8", R.string.key_Dot8)
        return this
    }

    fun routing(): KeyNameMapBuilder {
        return add("RoutingKey", R.string.key_Routing)
    }

    fun dualJoysticks(): KeyNameMapBuilder {
        add("LeftJoystickLeft", R.string.key_LeftJoystickLeft)
        add("LeftJoystickRight", R.string.key_LeftJoystickRight)
        add("LeftJoystickUp", R.string.key_LeftJoystickUp)
        add("LeftJoystickDown", R.string.key_LeftJoystickDown)
        add("LeftJoystickPress", R.string.key_LeftJoystickCenter)
        add("RightJoystickLeft", R.string.key_RightJoystickLeft)
        add("RightJoystickRight", R.string.key_RightJoystickRight)
        add("RightJoystickUp", R.string.key_RightJoystickUp)
        add("RightJoystickDown", R.string.key_RightJoystickDown)
        add("RightJoystickPress", R.string.key_RightJoystickCenter)
        return this
    }

    fun build(): Map<String, Int> {
        return Collections.unmodifiableMap(mNameMap)
    }
}

// ADD_DEVICE_SUPPORT
private val SUPPORTED_DEVICES: List<SupportedDevice> =
        mutableListOf<SupportedDevice>().apply {
            // TODO: Follow up on why secure connections can't be established
            // with some devices.

            // BraillePen
            add(NameRegexSupportedDevice("vo", true,
                    KeyNameMapBuilder()
                            .dots6()
                            .add("Shift", R.string.key_BP_Shift)
                            .add("Space", R.string.key_Space)
                            .add("Control", R.string.key_BP_Control)
                            .add("JoystickLeft", R.string.key_JoystickLeft)
                            .add("JoystickRight", R.string.key_JoystickRight)
                            .add("JoystickUp", R.string.key_JoystickUp)
                            .add("JoystickDown", R.string.key_JoystickDown)
                            .add("JoystickEnter", R.string.key_JoystickCenter)
                            .add("ScrollLeft", R.string.key_BP_ScrollLeft)
                            .add("ScrollRight", R.string.key_BP_ScrollRight)
                            .build(),
                    Pattern.compile("EL12-")))

            // Esys
            add(NameRegexSupportedDevice("eu", true,
                    KeyNameMapBuilder()
                            .dots8()
                            .add("Switch1Left", R.string.key_esys_SwitchLeft)
                            .add("Switch1Right", R.string.key_esys_SwitchRight)
                            .dualJoysticks()
                            .add("Backspace", R.string.key_Backspace)
                            .add("Space", R.string.key_Space)
                            .add("RoutingKey1", R.string.key_Routing)
                            .build(),
                    Pattern.compile("Esys-")))

            // Freedom Scientific Focus blue displays.
            add(NameRegexSupportedDevice("fs", true,
                    KeyNameMapBuilder()
                            .dots8()
                            .add("Space", R.string.key_Space)
                            .add("LeftAdvance", R.string.key_focus_LeftAdvance)
                            .add("RightAdvance", R.string.key_focus_RightAdvance)
                            .add("LeftWheelPress",
                                    R.string.key_focus_LeftWheelPress)
                            .add("LeftWheelDown",
                                    R.string.key_focus_LeftWheelDown)
                            .add("LeftWheelUp",
                                    R.string.key_focus_LeftWheelUp)
                            .add("RightWheelPress",
                                    R.string.key_focus_RightWheelPress)
                            .add("RightWheelDown",
                                    R.string.key_focus_RightWheelDown)
                            .add("RightWheelUp",
                                    R.string.key_focus_RightWheelUp)
                            .routing()
                            .add("LeftShift", R.string.key_focus_LeftShift)
                            .add("RightShift", R.string.key_focus_RightShift)
                            .add("LeftGdf", R.string.key_focus_LeftGdf)
                            .add("RightGdf", R.string.key_focus_RightGdf)
                            .add("LeftRockerUp", R.string.key_focus_LeftRockerUp)
                            .add("LeftRockerDown",
                                    R.string.key_focus_LeftRockerDown)
                            .add("RightRockerUp", R.string.key_focus_RightRockerUp)
                            .add("RightRockerDown",
                                    R.string.key_focus_RightRockerDown)
                            .build(),
                    Pattern.compile("Focus (40|14) BT")))

            // Brailliant
            // Secure connections currently fail on Android devices for the
            // Brailliant.
            add(NameRegexSupportedDevice("hw", false,
                    KeyNameMapBuilder()
                            .dots8()
                            .add("Left", R.string.key_JoystickLeft)
                            .add("Right", R.string.key_JoystickRight)
                            .add("Up", R.string.key_JoystickUp)
                            .add("Down", R.string.key_JoystickDown)
                            .add("Press", R.string.key_JoystickCenter)
                            .routing()
                            .add("Space", R.string.key_Space)
                            .add("Power", R.string.key_brailliant_Power)
                            .add("Display1", R.string.key_brailliant_Display1)
                            .add("Display2", R.string.key_brailliant_Display2)
                            .add("Display3", R.string.key_brailliant_Display3)
                            .add("Display4", R.string.key_brailliant_Display4)
                            .add("Display5", R.string.key_brailliant_Display5)
                            .add("Display6", R.string.key_brailliant_Display6)
                            .add("Thumb1", R.string.key_brailliant_Thumb1)
                            .add("Thumb2", R.string.key_brailliant_Thumb2)
                            .add("Thumb3", R.string.key_brailliant_Thumb3)
                            .add("Thumb4", R.string.key_brailliant_Thumb4)
                            .build(),
                    Pattern.compile("Brailliant BI")))

            // HIMS
            add(NameRegexSupportedDevice("hm", false,
                    KeyNameMapBuilder()
                            .dots8()
                            .routing()
                            .add("Space", R.string.key_Space)
                            .add("F1", R.string.key_F1)
                            .add("F2", R.string.key_F2)
                            .add("F3", R.string.key_F3)
                            .add("F4", R.string.key_F4)
                            .add("Backward", R.string.key_Backward)
                            .add("Forward", R.string.key_Forward)
                            .build(),
                    Pattern.compile("Hansone|HansoneXL|BrailleSense|BrailleEDGE|SmartBeetle")))

            // APH Refreshabraille.
            // Secure connections get prematurely closed 50% of the time
            // by the Refreshabraille.
            add(NameRegexSupportedDevice("bm", false,
                    KeyNameMapBuilder()
                            .dots8()
                            .add("Left", R.string.key_JoystickLeft)
                            .add("Right", R.string.key_JoystickRight)
                            .add("Up", R.string.key_JoystickUp)
                            .add("Down", R.string.key_JoystickDown)
                            .add("Press", R.string.key_JoystickCenter)
                            .routing()
                            .add("Display2", R.string.key_APH_AdvanceLeft)
                            .add("Display5", R.string.key_APH_AdvanceRight)
                            .add("B9", R.string.key_Space)
                            .add("B10", R.string.key_Space)
                            .build(),
                    Pattern.compile("Refreshabraille")))

            // APH Orbit Reader.
            // Secure connections get prematurely closed 50% of the time
            // by the Orbit Reader.
            add(NameRegexSupportedDevice("bm", false,
                    KeyNameMapBuilder()
                            .dots8()
                            .add("Left", R.string.key_JoystickLeft)
                            .add("Right", R.string.key_JoystickRight)
                            .add("Up", R.string.key_JoystickUp)
                            .add("Down", R.string.key_JoystickDown)
                            .add("Press", R.string.key_JoystickCenter)
                            .add("Display2", R.string.key_APH_AdvanceLeft)
                            .add("Display5", R.string.key_APH_AdvanceRight)
                            .add("Space", R.string.key_Space)
                            .build(),
                    Pattern.compile("Orbit")))

            // Baum VarioConnect
            add(NameRegexSupportedDevice("bm", false,
                    KeyNameMapBuilder()
                            .dots8()
                            .add("Left", R.string.key_JoystickLeft)
                            .add("Right", R.string.key_JoystickRight)
                            .add("Up", R.string.key_JoystickUp)
                            .add("Down", R.string.key_JoystickDown)
                            .add("Press", R.string.key_JoystickCenter)
                            .routing()
                            .add("Display2", R.string.key_APH_AdvanceLeft)
                            .add("Display5", R.string.key_APH_AdvanceRight)
                            .add("B9", R.string.key_Space)
                            .add("B10", R.string.key_Space)
                            .build(),
                    Pattern.compile("VarioConnect")))
            // Baum VarioUltra
            add(NameRegexSupportedDevice("bm", false,
                    KeyNameMapBuilder()
                            .dots8()
                            .add("Left", R.string.key_JoystickLeft)
                            .add("Right", R.string.key_JoystickRight)
                            .add("Up", R.string.key_JoystickUp)
                            .add("Down", R.string.key_JoystickDown)
                            .add("Press", R.string.key_JoystickCenter)
                            .routing()
                            .add("Display2", R.string.key_APH_AdvanceLeft)
                            .add("Display5", R.string.key_APH_AdvanceRight)
                            .add("B9", R.string.key_Space)
                            .add("B10", R.string.key_Space)
                            .build(),
                    Pattern.compile("VarioUltra")))

            // Older Brailliant, from Humanware group. Uses Baum
            // protocol. No Braille keyboard on this one. Secure
            // connections currently fail on Android devices with this
            // display.
            add(NameRegexSupportedDevice("bm", false,
                    KeyNameMapBuilder()
                            .add("Display1", R.string.key_hwg_brailliant_Display1)
                            .add("Display2", R.string.key_hwg_brailliant_Display2)
                            .add("Display3", R.string.key_hwg_brailliant_Display3)
                            .add("Display4", R.string.key_hwg_brailliant_Display4)
                            .add("Display5", R.string.key_hwg_brailliant_Display5)
                            .add("Display6", R.string.key_hwg_brailliant_Display6)
                            .routing()
                            .build(),
                    Pattern.compile("HWG Brailliant")))

            // Braillex Trio
            add(NameRegexSupportedDevice("pm", true,
                    KeyNameMapBuilder()
                            .dots8()
                            .add("LeftSpace", R.string.key_Space)
                            .add("RightSpace", R.string.key_Space)
                            .add("Space", R.string.key_Space)
                            .add("LeftThumb", R.string.key_braillex_LeftThumb)
                            .add("RightThumb", R.string.key_braillex_RightThumb)
                            .add("RoutingKey1", R.string.key_Routing)
                            .add("BarLeft1", R.string.key_braillex_BarLeft1)
                            .add("BarLeft2", R.string.key_braillex_BarLeft2)
                            .add("BarRight1", R.string.key_braillex_BarRight1)
                            .add("BarRight2", R.string.key_braillex_BarRight2)
                            .add("BarUp1", R.string.key_braillex_BarUp1)
                            .add("BarUp2", R.string.key_braillex_BarUp2)
                            .add("BarDown1", R.string.key_braillex_BarDown1)
                            .add("BarDown2", R.string.key_braillex_BarDown2)
                            .add("LeftKeyRear", R.string.key_braillex_LeftKeyRear)
                            .add("LeftKeyFront", R.string.key_braillex_LeftKeyFront)
                            .add("RightKeyRear", R.string.key_braillex_RightKeyRear)
                            .add("RightKeyFront",
                                    R.string.key_braillex_RightKeyFront)
                            .build(),
                    Pattern.compile("braillex trio")))

            // Alva BC640/BC680
            add(NameRegexSupportedDevice("al", false,
                    KeyNameMapBuilder()
                    // No braille dot keys.
                    .add("ETouchLeftRear", R.string.key_albc_ETouchLeftRear)
                    .add("ETouchRightRear", R.string.key_albc_ETouchRightRear)
                    .add("ETouchLeftFront", R.string.key_albc_ETouchLeftFront)
                    .add("ETouchRightFront", R.string.key_albc_ETouchRightFront)
                    .add("SmartpadF1", R.string.key_albc_SmartpadF1)
                    .add("SmartpadF2", R.string.key_albc_SmartpadF2)
                    .add("SmartpadF3", R.string.key_albc_SmartpadF3)
                    .add("SmartpadF4", R.string.key_albc_SmartpadF4)
                    .add("SmartpadUp", R.string.key_albc_SmartpadUp)
                    .add("SmartpadDown", R.string.key_albc_SmartpadDown)
                    .add("SmartpadLeft", R.string.key_albc_SmartpadLeft)
                    .add("SmartpadRight", R.string.key_albc_SmartpadRight)
                    .add("SmartpadEnter", R.string.key_albc_SmartpadEnter)
                    .add("ThumbLeft", R.string.key_albc_ThumbLeft)
                    .add("ThumbRight", R.string.key_albc_ThumbRight)
                    .add("ThumbUp", R.string.key_albc_ThumbUp)
                    .add("ThumbDown", R.string.key_albc_ThumbDown)
                    .add("ThumbHome", R.string.key_albc_ThumbHome)
                    .add("RoutingKey1", R.string.key_Routing)
                    .build(),
                    Pattern.compile("Alva BC", Pattern.CASE_INSENSITIVE)))

            // HandyTech displays
            add(NameRegexSupportedDevice("ht", true,
                    KeyNameMapBuilder()
                        .add("B4", R.string.key_Dot1)
                        .add("B3", R.string.key_Dot2)
                        .add("B2", R.string.key_Dot3)
                        .add("B1", R.string.key_Dot7)
                        .add("B5", R.string.key_Dot4)
                        .add("B6", R.string.key_Dot5)
                        .add("B7", R.string.key_Dot6)
                        .add("B8", R.string.key_Dot8)
                        .routing()
                        .add("LeftRockerTop",
                            R.string.key_handytech_LeftTrippleActionTop)
                        .add("LeftRockerBottom",
                            R.string.key_handytech_LeftTrippleActionBottom)
                        .add("LeftRockerTop+LeftRockerBottom",
                            R.string.key_handytech_LeftTrippleActionMiddle)
                        .add("RightRockerTop",
                            R.string.key_handytech_RightTrippleActionTop)
                        .add("RightRockerBottom",
                            R.string.key_handytech_RightTrippleActionBottom)
                        .add("RightRockerTop+RightRockerBottom",
                            R.string.key_handytech_RightTrippleActionMiddle)
                        .add("SpaceLeft", R.string.key_handytech_LeftSpace)
                        .add("SpaceRight", R.string.key_handytech_RightSpace)
                        .add("Display1", R.string.key_hwg_brailliant_Display1)
                        .add("Display2", R.string.key_hwg_brailliant_Display2)
                        .add("Display3", R.string.key_hwg_brailliant_Display3)
                        .add("Display4", R.string.key_hwg_brailliant_Display4)
                        .add("Display5", R.string.key_hwg_brailliant_Display5)
                        .add("Display6", R.string.key_hwg_brailliant_Display6)
                        .build(),
                        Pattern.compile("(Braille Wave( BRW)?|Braillino( BL2)?|Braille Star 40( BS4)?|Easy Braille( EBR)?|Active Braille( AB4)?|Basic Braille BB[3,4,6]?)\\/[a-zA-Z][0-9]-[0-9]{5}"),
                        Pattern.compile("(BRW|BL2|BS4|EBR|AB4|BB(3|4|6)?)\\/[a-zA-Z][0-9]-[0-9]{5}")))

            // Seika Mini Note Taker. Secure connections fail to connect reliably.
            add(NameRegexSupportedDevice("sk", false,
                    KeyNameMapBuilder()
                    .dots8()
                    .routing()
                    .dualJoysticks()
                    .add("Backspace", R.string.key_Backspace)
                    .add("Space", R.string.key_Space)
                    .add("LeftButton", R.string.key_skntk_PanLeft)
                    .add("RightButton", R.string.key_skntk_PanRight)
                    .build(),
                    Pattern.compile("TSM")))

            // Seika Braille Display. No Braille keys on this display.
            add(NameRegexSupportedDevice("sk", true,
                    KeyNameMapBuilder()
                    .add("K1", R.string.key_skbdp_PanLeft)
                    .add("K8", R.string.key_skbdp_PanRight)
                    .add("K2", R.string.key_skbdp_LeftRockerLeft)
                    .add("K3", R.string.key_skbdp_LeftRockerRight)
                    .add("K4", R.string.key_skbdp_LeftLongKey)
                    .add("K5", R.string.key_skbdp_RightLongKey)
                    .add("K6", R.string.key_skbdp_RightRockerLeft)
                    .add("K7", R.string.key_skbdp_RightRockerRight)
                    .add("RoutingKey2", R.string.key_Routing)
                    .routing()
                    .build(),
                    Pattern.compile("TS5")))
        }.toList()
