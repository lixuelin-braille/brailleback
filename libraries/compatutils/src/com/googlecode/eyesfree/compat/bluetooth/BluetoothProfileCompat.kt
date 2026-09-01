/*
 * Copyright (C) 2011 Google Inc.
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

import android.os.Build
import com.googlecode.eyesfree.compat.CompatUtils
import com.googlecode.eyesfree.compat.bluetooth.BluetoothProfileCompatHoneycomb.ServiceListenerBridge
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.Collections

object BluetoothProfileCompat {
    private interface BluetoothProfileVersionImpl {
        fun newServiceListener(listener: ServiceListenerCompat): Any?
    }

    private open class BluetoothProfileStubImpl : BluetoothProfileVersionImpl {
        override fun newServiceListener(listener: ServiceListenerCompat): Any? = null
    }

    private class BluetoothProfileHoneycombImpl : BluetoothProfileStubImpl() {
        override fun newServiceListener(listener: ServiceListenerCompat): Any? =
            BluetoothProfileCompatHoneycomb.newServiceListener(object : ServiceListenerBridge {
                override fun onServiceConnected(profile: Int, proxy: Any) {
                    listener.onServiceConnected(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) {
                    listener.onServiceDisconnected(profile)
                }
            })
    }

    private val IMPL: BluetoothProfileVersionImpl

    @JvmField
    val CLASS_BluetoothProfile: Class<*>? =
        CompatUtils.getClass("android.bluetooth.BluetoothProfile")

    private val METHOD_getConnectedDevices: Method? =
        CompatUtils.getMethod(CLASS_BluetoothProfile, "getConnectedDevices")

    const val EXTRA_STATE: String = "android.bluetooth.profile.extra.STATE"
    const val EXTRA_PREVIOUS_STATE: String = "android.bluetooth.profile.extra.PREVIOUS_STATE"
    const val STATE_DISCONNECTED: Int = 0
    const val STATE_CONNECTING: Int = 1
    const val STATE_CONNECTED: Int = 2
    const val STATE_DISCONNECTING: Int = 3
    const val HEADSET: Int = 1
    const val A2DP: Int = 2
    const val HEALTH: Int = 3

    init {
        IMPL = if (Build.VERSION.SDK_INT >= 11) {
            BluetoothProfileHoneycombImpl()
        } else {
            BluetoothProfileStubImpl()
        }
    }

    @JvmStatic
    fun getConnectedDevices(receiver: Any?): List<BluetoothDeviceCompat> {
        val results = CompatUtils.invoke(receiver, null, METHOD_getConnectedDevices) as List<*>?
        if (results == null) {
            return Collections.emptyList()
        }
        val output = ArrayList<BluetoothDeviceCompat>(results.size)
        for (result in results) {
            output.add(BluetoothDeviceCompat(result!!))
        }
        return output
    }

    abstract class ServiceListenerCompat {
        @Suppress("unused")
        private val mListener: Any? = IMPL.newServiceListener(this)

        abstract fun onServiceConnected(profile: Int, proxy: Any?)

        abstract fun onServiceDisconnected(profile: Int)

        companion object {
            @JvmField
            val CLASS_ServiceListenerCompat: Class<*>? =
                CompatUtils.getClass("android.bluetooth.BluetoothProfile.ServiceListener")
        }
    }
}
