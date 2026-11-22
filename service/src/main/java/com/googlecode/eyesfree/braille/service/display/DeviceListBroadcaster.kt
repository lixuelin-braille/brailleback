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

import android.os.RemoteException
import com.googlecode.eyesfree.braille.display.IBrailleServiceCallback
import timber.log.Timber

/**
 * 候选设备列表的组装与下发。独立成文件是为了把 [DisplayService.kt] 控制在
 * 拆分行数预算内；已配对设备始终来自 [DeviceFinder.findDevices()] 的原始顺序，
 * 本轮发现的未配对设备只追加在后，不改既有元素的顺序与语义。
 */

/**
 * Sends the current candidate device list to one client. Bonded devices
 * come from [DeviceFinder.findDevices()] in its original order; discovered
 * unpaired devices are appended after them.
 */
internal fun DisplayService.sendDeviceList(callback: IBrailleServiceCallback?) {
    callback ?: return
    val addresses = ArrayList<String>()
    val names = ArrayList<String?>()
    val states = ArrayList<Int>()
    val bonded = try {
        DeviceFinder(this).findDevices()
    } catch (e: SecurityException) {
        Timber.w(e, "BLUETOOTH_CONNECT denied; empty bonded list")
        emptyList()
    }
    for (info in bonded) {
        val dev = info.getBluetoothDevice() ?: continue
        val addr: String? = try {
            dev.address
        } catch (e: SecurityException) {
            continue
        }
        val name: String? = try {
            dev.name
        } catch (e: SecurityException) {
            null
        }
        if (addr == null) continue
        addresses.add(addr)
        names.add(name)
        states.add(CandidateState.BONDED)
    }
    synchronized(mUnpairedCandidates) {
        for (candidate in mUnpairedCandidates) {
            if (addresses.any { it.equals(candidate.address, ignoreCase = true) }) {
                continue
            }
            addresses.add(candidate.address)
            names.add(candidate.name)
            states.add(CandidateState.UNPAIRED)
        }
    }
    try {
        callback.onDeviceListChanged(
                addresses.toTypedArray(),
                names.toTypedArray(),
                states.toIntArray())
    } catch (ex: RemoteException) {
        // Nothing to do, the callback list will remove the callback later.
    }
}

/** Broadcasts the candidate list to every registered client. */
internal fun DisplayService.broadcastDeviceList() {
    var i = mClients.beginBroadcast()
    try {
        while (i-- > 0) {
            sendDeviceList(mClients.getBroadcastItem(i))
        }
    } finally {
        mClients.finishBroadcast()
    }
}
