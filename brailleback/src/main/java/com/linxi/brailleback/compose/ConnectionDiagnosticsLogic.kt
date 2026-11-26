/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.linxi.brailleback.compose

/** 单台 USB 设备在诊断页上的真实取值，全部来自 UsbManager 实时枚举。 */
data class UsbDeviceDiagnostic(
    val vid: Int,
    val pid: Int,
    val hasPermission: Boolean,
    val active: Boolean,
)

/**
 * 诊断页纯函数口径。不碰 Android API，方便单测锁定路由归一与取值类别。
 */
object ConnectionDiagnosticsLogic {

    const val ROUTE_USB = "usb"
    const val ROUTE_BLUETOOTH = "bluetooth"
    const val ROUTE_NONE = "none"

    /** 服务端路由码归一到诊断页三类取值；未知值一律按未连接处理。 */
    fun normalizeRoute(route: String?): String = when (route) {
        ROUTE_USB -> ROUTE_USB
        ROUTE_BLUETOOTH -> ROUTE_BLUETOOTH
        else -> ROUTE_NONE
    }

    /** 把 VID/PID 拼成读屏可朗读的十六进制短句，例如 0452 / 0100。 */
    fun formatHex(value: Int): String = String.format("%04X", value)
}
