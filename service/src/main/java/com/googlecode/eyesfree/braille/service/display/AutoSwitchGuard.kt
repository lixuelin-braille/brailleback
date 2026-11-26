/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.braille.service.display

/**
 * AUTO 档切换的幂等判据：纯函数，不碰 Android 类型，JVM 可测。
 *
 * 背景：USB 插拔广播会重复到达（系统多次 ATTACHED、枚举刷新、蓝牙与 USB 同时
 * 在线），如果每来一条广播就重算并切一次链路，点显器会在两条链路之间来回跳。
 * 稳定判据：
 * - USB 优先由 [AutoConnectionRouter] 保证；这里只挡「重复切换」；
 * - 同一设备 60 秒内不重复切；
 * - 路由与设备都没变时，重复广播直接幂等忽略。
 */
object AutoSwitchGuard {

    /** 同一设备的切换冷却窗口：60 秒。 */
    const val SWITCH_COOLDOWN_MS = 60_000L

    /** 上一次落定的切换状态；[deviceKey] 为 VID:PID 或蓝牙地址。 */
    data class State(
        val deviceKey: String?,
        val route: LinkRoute?,
        val lastSwitchTimeMs: Long,
    ) {
        companion object {
            val IDLE = State(null, null, 0L)
        }
    }

    /** 一次幂等判定的结论：要不要切 + 落定后的新状态 + 忽略原因。 */
    data class Decision(
        val shouldSwitch: Boolean,
        val nextState: State,
        val reason: String?,
    )

    @JvmStatic
    fun decide(
        state: State,
        deviceKey: String?,
        route: LinkRoute,
        nowMs: Long,
    ): Decision {
        // 路由与设备都没变：重复 attach 广播幂等，不再切一次。
        if (route == state.route && deviceKey == state.deviceKey) {
            return Decision(false, state, "same route and device; ignoring repeated broadcast")
        }
        // 同一设备在冷却窗口内：插拔抖动 / 重复广播不来回切。
        if (deviceKey != null && deviceKey == state.deviceKey &&
            nowMs - state.lastSwitchTimeMs < SWITCH_COOLDOWN_MS
        ) {
            return Decision(false, state, "same device within cooldown; holding")
        }
        val next = State(deviceKey, route, nowMs)
        return Decision(true, next, null)
    }
}
