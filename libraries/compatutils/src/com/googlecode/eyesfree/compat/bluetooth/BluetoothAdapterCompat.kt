/*
 * Copyright (C) 2012 Google Inc.
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

package com.googlecode.eyesfree.compat.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Context
import com.googlecode.eyesfree.compat.CompatUtils
import java.lang.reflect.Method
import java.util.Collections
import java.util.HashSet

class BluetoothAdapterCompat private constructor(private val mReceiver: Any?) {
    companion object {
        private val CLASS_BluetoothAdapter: Class<*>? =
            CompatUtils.getClass("android.bluetooth.BluetoothAdapter")
        private val METHOD_getProfileProxy: Method? = CompatUtils.getMethod(
            CLASS_BluetoothAdapter, "getProfileProxy", Context::class.java,
            BluetoothProfileCompat.ServiceListenerCompat.CLASS_ServiceListenerCompat,
            Int::class.javaPrimitiveType
        )
        private val METHOD_closeProfileProxy: Method? = CompatUtils.getMethod(
            CLASS_BluetoothAdapter, "closeProfileProxy", Int::class.javaPrimitiveType,
            BluetoothProfileCompat.CLASS_BluetoothProfile
        )
        private val METHOD_getDefaultAdapter: Method? =
            CompatUtils.getMethod(CLASS_BluetoothAdapter, "getDefaultAdapter")
        private val METHOD_getBondedDevices: Method? =
            CompatUtils.getMethod(CLASS_BluetoothAdapter, "getBondedDevices")
        private val METHOD_isEnabled: Method? = CompatUtils.getMethod(CLASS_BluetoothAdapter, "isEnabled")

        @JvmStatic
        fun getDefaultAdapter(): BluetoothAdapterCompat? {
            val result = CompatUtils.invoke(null, null, METHOD_getDefaultAdapter) ?: return null
            return BluetoothAdapterCompat(result)
        }
    }

    val `object`: Any?
        get() = mReceiver

    fun getBondedDevices(): Set<BluetoothDeviceCompat> {
        val results = CompatUtils.invoke(mReceiver, null, METHOD_getBondedDevices) as Set<*>?
        if (results == null) {
            return Collections.emptySet()
        }
        val output = HashSet<BluetoothDeviceCompat>()
        for (result in results) {
            output.add(BluetoothDeviceCompat(result!!))
        }
        return Collections.unmodifiableSet(output)
    }

    fun getProfileProxy(
        context: Context, listener: BluetoothProfileCompat.ServiceListenerCompat, profile: Int
    ): Boolean = (CompatUtils.invoke(
        mReceiver, false, METHOD_getProfileProxy, context, listener
    ) as Boolean)

    fun closeProfileProxy(profile: Int, proxy: Any?) {
        CompatUtils.invoke(mReceiver, null, METHOD_closeProfileProxy, profile, proxy)
    }

    fun isEnabled(): Boolean = (CompatUtils.invoke(mReceiver, false, METHOD_isEnabled) as Boolean)
}
