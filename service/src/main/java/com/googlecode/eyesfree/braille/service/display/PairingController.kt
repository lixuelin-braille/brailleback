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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import timber.log.Timber
import java.util.Locale

/**
 * 配对兜底时长（毫秒）。系统配对确认框由 system uid 弹出，本 App 关不掉也
 * 不反射自动确认；60 秒内没等到 BOND_BONDED 就按超时落回可重试态，避免卡在
 * 「配对中」。
 */
internal const val PAIRING_TIMEOUT_MILLIS = 60_000L

/**
 * 未配对候选的配对编排：调 [BluetoothDevice.createBond]、喂状态机、超时兜底。
 * 配对成功后不再另造连接路径，沿用既有的 [connectFromBroadcastIntent] →
 * `autoConnectBraille()` 链路。
 */

/**
 * 用户在列表里对一个未配对候选点「配对」。
 *
 * 覆盖路径：
 * - 已配对：直接交回既有连接路径；
 * - 同一地址正在配对：忽略重复点击；
 * - `createBond()` 返回 false 或抛 SecurityException：落可重试失败态；
 * - 成功发起：进入 BONDING，挂 60 秒超时兜底。
 */
internal fun DisplayService.handlePairDevice(address: String?) {
    if (address.isNullOrBlank()) {
        return
    }
    val normalized = address.uppercase(Locale.US)
    val adapter = BluetoothAdapter.getDefaultAdapter()
    val device: BluetoothDevice? = try {
        adapter?.getRemoteDevice(normalized)
    } catch (e: IllegalArgumentException) {
        Timber.w("Invalid Bluetooth address %s", normalized)
        null
    } catch (e: SecurityException) {
        Timber.w(e, "BLUETOOTH_CONNECT denied resolving device")
        null
    }

    val bondState: Int? = try {
        device?.bondState
    } catch (e: SecurityException) {
        Timber.w(e, "BLUETOOTH_CONNECT denied reading bond state")
        null
    }

    // 已配对：不走配对流程，直接交回既有连接路径。
    if (bondState == BluetoothDevice.BOND_BONDED) {
        Timber.i("Device %s already bonded; connecting via existing path", normalized)
        mPendingBluetoothAddress = normalized
        autoConnectBraille()
        return
    }

    // 同一地址已在配对中：忽略重复点击，不重复发起 createBond。
    if (pairingMachine.state == PairingState.BONDING &&
        pairingMachine.address?.equals(normalized, ignoreCase = true) == true
    ) {
        Timber.v("Pairing already in progress for %s; ignoring tap", normalized)
        return
    }

    if (device == null) {
        pairingMachine.startBond(normalized, PAIRING_TIMEOUT_MILLIS)
        pairingMachine.onCreateBondResult(false, PairingFailureReason.COULD_NOT_START)
        broadcastDeviceList()
        return
    }

    val started: Boolean = try {
        device.createBond()
    } catch (e: SecurityException) {
        Timber.w(e, "BLUETOOTH_CONNECT denied calling createBond")
        pairingMachine.startBond(normalized, PAIRING_TIMEOUT_MILLIS)
        pairingMachine.onCreateBondResult(false, PairingFailureReason.PERMISSION)
        broadcastDeviceList()
        return
    }

    if (!pairingMachine.startBond(normalized, PAIRING_TIMEOUT_MILLIS)) {
        return
    }
    if (started) {
        mHandler.schedulePairingTimeout(PAIRING_TIMEOUT_MILLIS)
    } else {
        Timber.w("createBond() returned false for %s", normalized)
        pairingMachine.onCreateBondResult(false, PairingFailureReason.COULD_NOT_START)
    }
    broadcastDeviceList()
}

/** 配对超时兜底：仍在 BONDING 就落回可重试超时态，并刷新列表。 */
internal fun DisplayService.handlePairingTimeout() {
    pairingMachine.onTimeout(System.currentTimeMillis())
    broadcastDeviceList()
}

/**
 * 按配对状态机给某个候选地址计算列表状态码。地址不属于当前配对流程时，
 * 未配对候选一律返回 [CandidateState.UNPAIRED]。
 */
internal fun DisplayService.candidateStateFor(address: String): Int {
    if (pairingMachine.address?.equals(address, ignoreCase = true) != true) {
        return CandidateState.UNPAIRED
    }
    return when (pairingMachine.state) {
        PairingState.BONDING -> CandidateState.BONDING
        PairingState.BONDED -> CandidateState.BONDED
        PairingState.FAILED -> CandidateState.FAILED
        PairingState.TIMEOUT -> CandidateState.TIMEOUT
        PairingState.IDLE -> CandidateState.UNPAIRED
    }
}
