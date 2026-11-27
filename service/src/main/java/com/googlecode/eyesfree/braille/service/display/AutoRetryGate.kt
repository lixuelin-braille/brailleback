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

import android.os.SystemClock

/**
 * 自动重连触发的幂等 / 冷却判据（纯逻辑，不碰 Android 框架类，JVM 可测）。
 *
 * 背景：启动期输入设备反复注册、系统广播重复到达，旧 `prepareAutomaticRetry()`
 * 只挡「已连上 / 在途」，没有最小间隔、没有去重，每条广播都立刻重拨一次；再叠
 * `HID_RETRY_DELAY = 300L`，节奏就是几百毫秒一轮的自激循环（前科：17 秒 47k 次
 * 重试、约 106% CPU，见 `DisplayServiceHandlerActions.kt` 的注释）。
 *
 * 判据按顺序：
 * 1. 已连接或在途 → 直接短路返回；同时把这次「活跃」观察记下来作为 settle 起点。
 *    本守卫不取消、不重建任何进行中的连接尝试；
 * 2. settle 稳定期：距上一次观察到「活跃」不足 [settleMillis]，不把迟到的重复广播
 *    判成失败、不重试。这是判据核心：区分「刚连上 / 正在落定」与「空闲无设备」，
 *    而不是靠把 backoff 间隔调大来压节奏；
 * 3. 来源去重与最小间隔，按来源键分别记账，不同来源互不干扰：
 *    - 取不到稳定键（null）：退回全局最小间隔 [minIntervalMillis]，防止无键触发成串重拨；
 *    - 同一设备（deviceId / 地址归一后的稳定键）：在独立去重窗口
 *      [dedupeWindowMillis] 内重复出现直接忽略，即使已过最小间隔；
 *    - 不同来源：互不干扰，一条新设备插上不被别的设备的冷却 / 去重挡住。
 *
 * 注意：这里不改 `HID_RETRY_DELAY` / `DISCOVERY_RESTART_DELAY` /
 * `AUTO_RECONNECT_INITIAL_DELAY` 等既有 backoff 常量，也不取消已排定的 HID 控制器
 * 超时；只挡住启动期同来源的重复触发。
 */
internal class AutoRetryGate(
    private val minIntervalMillis: Long = AUTO_RETRY_MIN_INTERVAL_MS,
    private val dedupeWindowMillis: Long = AUTO_RETRY_DEDUPE_WINDOW_MS,
    private val settleMillis: Long = CONNECTION_SETTLE_MS,
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
) {
    private var lastReleasedAtMs: Long = 0L
    private var lastReleasedKey: String? = null
    private var hasReleased: Boolean = false
    private var lastActiveAtMs: Long = 0L
    private var hasActiveHistory: Boolean = false

    /**
     * 记录一次「活跃」观察。观察到活跃时记下 settle 起点；之后状态回落到空闲，
     * 这段时间内的迟到广播仍按「尚未落定」处理，不判失败。
     */
    private fun markActive(now: Long, active: Boolean) {
        if (active) {
            lastActiveAtMs = now
            hasActiveHistory = true
        }
    }

    /**
     * 判定本次自动重连触发是否放行。
     *
     * @param active 当前是否已连接 / 在途（对应 `isConnectionActive()`）。生产接线必须
     *   在调用守卫之前先取到这个值并传进来；否则 settle 分支收不到活跃记录，会退化成死代码。
     * @param inFlight 是否有尚未结束的连接尝试（`mConnectPending` /
     *   `mWaitingForHidController`）；在途时不得被新触发打断或重建。
     * @param sourceKey 触发来源的稳定键：HID 输入设备用 deviceId，蓝牙用地址；取不到为 null。
     * @return true 放行本次重连；false 被活跃短路 / settle / 冷却 / 去重挡下。
     */
    fun shouldAllow(active: Boolean, inFlight: Boolean, sourceKey: String?): Boolean {
        val now = clock()
        markActive(now, active)
        // 1) 活跃或在途：保留既有短路，不打断进行中的尝试；活跃态在此被记录。
        if (active || inFlight) {
            return false
        }
        // 2) settle 稳定期：刚从活跃态回落，迟到广播不判失败、不重试。
        if (hasActiveHistory && now - lastActiveAtMs < settleMillis) {
            return false
        }
        when {
            // 取不到稳定键：退回全局最小间隔，防止无键触发成串重拨。
            sourceKey == null -> {
                if (hasReleased && now - lastReleasedAtMs < minIntervalMillis) {
                    return false
                }
            }
            // 同一设备在独立去重窗口内重复出现：直接忽略。
            sourceKey == lastReleasedKey -> {
                if (now - lastReleasedAtMs < dedupeWindowMillis) {
                    return false
                }
            }
            // 不同来源：互不干扰，不被别的设备的冷却 / 去重挡住。
            else -> Unit
        }
        return true
    }

    /** 放行后记录本次来源与时刻；被挡下的调用不更新状态，避免冷却被刷新。 */
    fun recordReleased(sourceKey: String?) {
        lastReleasedAtMs = clock()
        lastReleasedKey = sourceKey
        hasReleased = true
    }

    companion object {
        /**
         * 无稳定键触发的全局最小间隔：800ms。
         *
         * 为什么是 800：启动期输入设备注册 / 广播在数百毫秒内成串到达，300ms 的
         * `HID_RETRY_DELAY` 不足以把它们隔开；800ms 足够吞掉同一轮枚举的重复广播，
         * 又不会拖慢一次真实的新设备出现后的首次重连（首次触发不受本间隔约束）。
         * 带稳定键的同设备去重由 [AUTO_RETRY_DEDUPE_WINDOW_MS] 独立记账。
         */
        const val AUTO_RETRY_MIN_INTERVAL_MS = 800L

        /**
         * 同一设备重复出现的独立去重窗口：2000ms，大于 800ms 最小间隔。
         *
         * 为什么是 2000：同一台点显器在插拔 / 枚举过程中会在 1～2 秒内补发多条
         *「设备出现」广播；800ms 之外、2000ms 之内的同设备重复仍应吞掉，避免一次
         * 插拔产生一串重连。窗口按 sourceKey 记账，不同设备互不影响。
         */
        const val AUTO_RETRY_DEDUPE_WINDOW_MS = 2000L

        /**
         * 连接成功（或在途）后的稳定期：1000ms。这段时间内不把迟到广播判成掉线失败。
         *
         * 用意：链路刚建立时系统仍在补发历史设备广播，状态可能短暂回落空闲；若立刻
         * 重连，会与正在收尾的旧链路打架。settle 与去重窗口是两件事——settle 看「最近
         * 是否活跃过」，去重看「上次放行的是不是同一设备、距今多久」。
         */
        const val CONNECTION_SETTLE_MS = 1000L
    }
}
