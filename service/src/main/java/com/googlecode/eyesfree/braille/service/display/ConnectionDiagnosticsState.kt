/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.googlecode.eyesfree.braille.service.display

/**
 * 主进程内的连接运行时快照，供设置侧「连接诊断」页直接读取。
 *
 * <p>DisplayService 与 brailleback 设置 Activity 同处主进程（TranslatorService 才是
 * `:service` 进程），因此这里用一个普通内存对象桥接，不扩 AIDL。字段全部由服务端
 * 连接回调写入，诊断页只读，不允许页面反向改值。
 */
object ConnectionDiagnosticsState {

    /** 最近一次出点阵的链路；未连接 / 无任何出点阵时为 [ROUTE_NONE]。 */
    @Volatile
    var route: String = ROUTE_NONE

    /** 最近一次 USB 连接失败原因（原因链末环）；没有失败记录时为 null。 */
    @Volatile
    var lastFailureReason: String? = null

    /** USB 串口驱动是否正在运行。 */
    @Volatile
    var usbActive: Boolean = false

    /** 最近一次枚举轮询窗口采到的逐秒样本，供快照与诊断页读取。 */
    @Volatile
    var lastPollSamples: List<String> = emptyList()

    const val ROUTE_NONE = "none"
    const val ROUTE_BLUETOOTH = "bluetooth"
    const val ROUTE_USB = "usb"

    /** 本轮连接尝试按顺序累积的失败原因（串口 → HID → …），后环不许覆盖前环。 */
    private val reasonChain = mutableListOf<String>()

    /** 追加一环失败原因；[lastFailureReason] 始终取链末，链本身保留完整顺序。 */
    @Synchronized
    fun appendFailureReason(stage: String, reason: String?) {
        if (reason.isNullOrEmpty()) return
        reasonChain.add("$stage: $reason")
        lastFailureReason = reasonChain.last()
    }

    /** 只读快照：按连接尝试顺序排列的失败原因。 */
    @Synchronized
    fun failureChain(): List<String> = reasonChain.toList()

    /** 新一轮连接尝试开始时清空上一轮的原因链。 */
    @Synchronized
    fun resetFailureChain() {
        reasonChain.clear()
    }

    /** 服务端断开时复位，避免诊断页读到上一轮会话的陈旧路由。 */
    fun resetOnDisconnect() {
        route = ROUTE_NONE
        usbActive = false
    }
}
