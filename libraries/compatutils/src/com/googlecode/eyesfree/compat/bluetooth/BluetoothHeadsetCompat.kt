/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.googlecode.eyesfree.compat.bluetooth

import com.googlecode.eyesfree.compat.CompatUtils
import java.lang.reflect.Method

class BluetoothHeadsetCompat(private val receiver: Any) {
    fun isAudioConnected(device: BluetoothDeviceCompat): Boolean {
        return CompatUtils.invoke(receiver, false, METHOD_isAudioConnected, device.getObject()) as Boolean
    }

    fun startScoUsingVirtualVoiceCall(device: BluetoothDeviceCompat): Boolean {
        return CompatUtils.invoke(
            receiver, false, METHOD_startScoUsingVirtualVoiceCall, device.getObject()
        ) as Boolean
    }

    fun stopScoUsingVirtualVoiceCall(device: BluetoothDeviceCompat): Boolean {
        return CompatUtils.invoke(
            receiver, false, METHOD_stopScoUsingVirtualVoiceCall, device.getObject()
        ) as Boolean
    }

    companion object {
        private val CLASS_BluetoothDevice: Class<*> =
            CompatUtils.getClass("android.bluetooth.BluetoothDevice")!!
        private val CLASS_BluetoothHeadset: Class<*> =
            CompatUtils.getClass("android.bluetooth.BluetoothHeadset")!!
        private val METHOD_startScoUsingVirtualVoiceCall: Method = CompatUtils.getMethod(
            CLASS_BluetoothHeadset, "startScoUsingVirtualVoiceCall", CLASS_BluetoothDevice
        )!!
        private val METHOD_stopScoUsingVirtualVoiceCall: Method = CompatUtils.getMethod(
            CLASS_BluetoothHeadset, "stopScoUsingVirtualVoiceCall", CLASS_BluetoothDevice
        )!!
        private val METHOD_isAudioConnected: Method = CompatUtils.getMethod(
            CLASS_BluetoothHeadset, "isAudioConnected", CLASS_BluetoothDevice
        )!!
    }
}
