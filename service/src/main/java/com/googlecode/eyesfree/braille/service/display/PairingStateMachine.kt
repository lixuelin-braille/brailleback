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

/** 配对阶段（纯状态机，不碰 Android 框架类，便于 JVM 单测）。 */
enum class PairingState {
    /** 空闲，未在配对。 */
    IDLE,

    /** 已调 createBond，等待系统配对广播。 */
    BONDING,

    /** 配对成功，交回既有连接路径。 */
    BONDED,

    /** 配对失败（被拒绝 / 权限或启动失败等），可重试。 */
    FAILED,

    /** 超过自设截止时间仍未完成，可重试。 */
    TIMEOUT,
}

/** 配对失败原因码；可读文案由客户端按状态 + 原因本地化。 */
enum class PairingFailureReason {
    /** 无失败。 */
    NONE,

    /** 用户在系统配对框里拒绝了配对。 */
    REJECTED,

    /** 等待配对结果超时。 */
    TIMEOUT,

    /** createBond() 直接返回 false，配对没能启动。 */
    COULD_NOT_START,

    /** 缺少 BLUETOOTH_CONNECT 权限，没法发起配对。 */
    PERMISSION,
}

/**
 * 经典蓝牙配对的纯状态机：`IDLE → BONDING → BONDED / FAILED / TIMEOUT`，
 * 失败与超时后回到可重试态，用户再次点配对即可从 `FAILED/TIMEOUT` 重回
 * `BONDING`。
 *
 * 状态迁移全部集中在本类，服务编排只负责把广播结果和系统回调喂进来；
 * 这样「进行中不许重复点」「失败可重试」「超时兜底」这些规则可以直接在
 * JVM 单测里验证，不用起真机或模拟器。
 */
class PairingStateMachine(
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    /** 当前配对状态。 */
    var state: PairingState = PairingState.IDLE
        private set

    /** 最近一次失败原因；[PairingState.FAILED] / [PairingState.TIMEOUT] 时有意义。 */
    var reason: PairingFailureReason = PairingFailureReason.NONE
        private set

    /** 正在 / 最近配对的设备地址（大写归一）。 */
    var address: String? = null
        private set

    private var deadlineMillis: Long = 0L

    /** 是否允许重新发起配对：失败、超时可重试；进行中、已成功、空闲不可。 */
    val retryable: Boolean
        get() = state == PairingState.FAILED || state == PairingState.TIMEOUT

    /**
     * 发起一次配对。
     *
     * @return true 表示状态已进入（或保持）配对流程；false 表示同一地址已在
     * 配对中，本次调用应被忽略（界面上表现为禁用重复点击）。
     */
    fun startBond(targetAddress: String, timeoutMillis: Long): Boolean {
        when (state) {
            PairingState.BONDING -> {
                return address?.equals(targetAddress, ignoreCase = true) == true
            }
            PairingState.BONDED -> return true
            else -> {
                // IDLE / FAILED / TIMEOUT：都允许重新进入 BONDING。
            }
        }
        address = targetAddress.uppercase(java.util.Locale.US)
        reason = PairingFailureReason.NONE
        state = PairingState.BONDING
        deadlineMillis = clock() + timeoutMillis
        return true
    }

    /**
     * 记录 `createBond()` 的直接返回值。返回 false 说明配对压根没启动，
     * 直接落到可重试失败态。
     */
    fun onCreateBondResult(success: Boolean, failureReason: PairingFailureReason) {
        if (state != PairingState.BONDING) return
        if (success) return
        reason = failureReason
        state = PairingState.FAILED
    }

    /**
     * 喂入系统 `ACTION_BOND_STATE_CHANGED` 的新配对状态。
     *
     * @param bondState 见 [BondState]：BONDED / BONDING / NONE。
     */
    fun onBondState(bondState: Int) {
        when (bondState) {
            BondState.BONDED -> {
                state = PairingState.BONDED
                reason = PairingFailureReason.NONE
            }
            BondState.NONE -> {
                // 只有处于配对中时，BOND_NONE 才算用户拒绝；空闲态的 NONE 不动。
                if (state == PairingState.BONDING) {
                    state = PairingState.FAILED
                    reason = PairingFailureReason.REJECTED
                }
            }
            BondState.BONDING -> {
                if (state != PairingState.BONDED) {
                    state = PairingState.BONDING
                }
            }
        }
    }

    /** 超时兜底：仍在配对中且已过截止时间，落到可重试超时态。 */
    fun onTimeout(now: Long) {
        if (state == PairingState.BONDING && now >= deadlineMillis) {
            state = PairingState.TIMEOUT
            reason = PairingFailureReason.TIMEOUT
        }
    }

    /** 回到空闲态（例如服务销毁、候选被清掉时）。 */
    fun reset() {
        state = PairingState.IDLE
        reason = PairingFailureReason.NONE
        address = null
        deadlineMillis = 0L
    }
}

/**
 * 系统蓝牙配对状态码镜像（`BluetoothDevice.BOND_*` 的 int 值），放在这里
 * 是为了状态机不直接依赖 android.bluetooth 包，JVM 单测可直接跑。
 */
object BondState {
    const val NONE = 10
    const val BONDING = 11
    const val BONDED = 12
}
