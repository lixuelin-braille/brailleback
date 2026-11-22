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

/**
 * 蓝牙发现窗口的来源。用户主动查找与连接失败后的自动重连共用同一个电台
 * （经典蓝牙同一时刻只能跑一次 inquiry），必须能区分是谁开的窗，避免自动
 * 重连策略把用户窗口误判成自己的重试窗口。
 */
enum class DiscoverySource {
    /** 没有发现窗口在跑。 */
    NONE,

    /** 连接失败 / 客户端注册后打开的自动重连窗口。 */
    AUTO_RECONNECT,

    /** 用户在设备列表页主动点「查找点显器」打开的一次性窗口。 */
    USER_DISCOVERY
}

/**
 * 发现窗口的纯状态机：记录当前窗口来源与截止时间，不碰 Android 框架类，
 * 因此来源让位、到期、收窗这些规则可以直接在 JVM 单测里验证。
 *
 * 服务里既有的 `mDiscoverySessionActive` / `mDiscoverySessionDeadline` 仍负责
 * 电台归属与 Handler 超时调度；本类只回答「窗口是谁的、是否还在有效期内」。
 */
class DiscoveryWindowTracker {

    /** 当前窗口来源；[DiscoverySource.NONE] 表示窗口已收。 */
    var source: DiscoverySource = DiscoverySource.NONE
        private set

    /** 当前窗口的截止时间（`SystemClock.uptimeMillis()` 基线）。 */
    var deadlineMillis: Long = 0L
        private set

    val isActive: Boolean
        get() = source != DiscoverySource.NONE

    val isUserActive: Boolean
        get() = source == DiscoverySource.USER_DISCOVERY

    val isAutoActive: Boolean
        get() = source == DiscoverySource.AUTO_RECONNECT

    /**
     * 打开（或刷新）用户主动窗口。用户点一次查找就抢占电台：若之前是自动
     * 重连窗口，先让它结束，由本窗口接管。
     */
    fun startUser(now: Long, durationMillis: Long) {
        source = DiscoverySource.USER_DISCOVERY
        deadlineMillis = now + durationMillis
    }

    /**
     * 打开自动重连窗口。若用户窗口仍在有效期内，自动重试不得延长或接管
     * 用户窗口——返回 false 表示电台仍归用户窗口所有。
     *
     * @return true 表示调用结束后自动窗口拥有电台。
     */
    fun startAuto(now: Long, durationMillis: Long): Boolean {
        if (source == DiscoverySource.USER_DISCOVERY && now < deadlineMillis) {
            return false
        }
        source = DiscoverySource.AUTO_RECONNECT
        deadlineMillis = now + durationMillis
        return true
    }

    /** 延长既有自动窗口；非自动窗口（用户窗口 / 已收窗）不动，返回 false。 */
    fun extendAuto(now: Long, durationMillis: Long): Boolean {
        if (source != DiscoverySource.AUTO_RECONNECT) {
            return false
        }
        deadlineMillis = now + durationMillis
        return true
    }

    /** 当前窗口是否已超过截止时间。无窗口时返回 false。 */
    fun expired(now: Long): Boolean = isActive && now >= deadlineMillis

    /** 收窗：来源归位、截止时间清零。 */
    fun finish() {
        source = DiscoverySource.NONE
        deadlineMillis = 0L
    }
}

/**
 * 一次发现到的候选设备。`name` 可能在扫描结果里暂时取不到（权限或远端
 * 尚未回传），保留 null 由 UI 用地址兜底朗读。
 */
data class DiscoveredDevice(
    val address: String,
    val name: String?
)

/**
 * 候选设备在列表里的状态码，经 IPC 传给客户端。
 *
 * [BONDED]  已配对，走既有连接路径；
 * [UNPAIRED] 发现到但未配对，需要用户触发配对（配对中的后续状态在配对切片加入）。
 */
object CandidateState {
    const val BONDED = 0
    const val UNPAIRED = 1

    /** 正在配对中（已调 createBond，等待系统配对结果）。 */
    const val BONDING = 2

    /** 配对失败（被拒绝 / 启动失败 / 权限不足），可重试。 */
    const val FAILED = 3

    /** 配对超时，可重试。 */
    const val TIMEOUT = 4
}
