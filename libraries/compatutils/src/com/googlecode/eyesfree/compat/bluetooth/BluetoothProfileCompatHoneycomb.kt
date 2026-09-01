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

import android.bluetooth.BluetoothProfile

class BluetoothProfileCompatHoneycomb private constructor() {

    interface ServiceListenerBridge {
        fun onServiceConnected(profile: Int, proxy: Any)

        fun onServiceDisconnected(profile: Int)
    }

    companion object {
        @JvmStatic
        fun newServiceListener(bridge: ServiceListenerBridge): Any {
            return object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    bridge.onServiceConnected(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) {
                    bridge.onServiceDisconnected(profile)
                }
            }
        }
    }
}
