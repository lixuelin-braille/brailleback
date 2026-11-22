/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.braille.service.display

import com.googlecode.eyesfree.braille.service.R
import timber.log.Timber

/**
 * 一次连接周期该从哪条链路起手。
 *
 * - [USB_SERIAL]：USB 线 + brltty 的串口驱动（失败后再回落到 USB HID）。
 * - [USB_HID]：USB 线 + 系统盲文 HID 控制器，跳过串口尝试。
 * - [BLUETOOTH]：蓝牙分支（内部再按 HID / 厂商 BLE / SPP 顺序走）。
 * - [NONE]：用户选了一条本 App 无法驱动的链路（经典蓝牙 HID），不发起连接。
 */
enum class LinkRoute {
    USB_SERIAL,
    USB_HID,
    BLUETOOTH,
    NONE,
}

/**
 * 把「协议偏好 × 连接方式偏好 × 当前是否插着线」折成一条起手路由。
 *
 * <p>纯 JVM，不碰 Android：路由错一处，用户看到的现象就是「选了 USB HID
 * 却还在试串口」「选了经典 HID 却连上了蓝牙串口」这类说不清的错误，
 * 而读屏用户无法从界面判断到底是设置没生效还是设备不支持。
 */
object HidTransportRouting {

    /** 自动档下「插着的线优先」，与改造前的 connectBraille 行为一致。 */
    @JvmStatic
    fun resolve(protocol: String?, transport: String?, usbAttached: Boolean): LinkRoute {
        val protocolValue = ConnectionProtocolPolicy.normalize(protocol)
        val transportValue = ConnectionTransportPolicy.normalize(transport)
        return when (transportValue) {
            ConnectionTransportPolicy.HID_USB -> LinkRoute.USB_HID
            ConnectionTransportPolicy.USB_SERIAL -> LinkRoute.USB_SERIAL
            ConnectionTransportPolicy.HID_CLASSIC -> LinkRoute.NONE
            ConnectionTransportPolicy.HID_BLE -> LinkRoute.BLUETOOTH
            // 旧配置可能只把协议改成 usb、连接方式还停在串口；别把它拽回蓝牙。
            ConnectionTransportPolicy.SERIAL ->
                if (protocolValue == ConnectionProtocolPolicy.USB) {
                    LinkRoute.USB_SERIAL
                } else {
                    LinkRoute.BLUETOOTH
                }
            else -> when {
                protocolValue == ConnectionProtocolPolicy.USB -> LinkRoute.USB_SERIAL
                usbAttached -> LinkRoute.USB_SERIAL
                else -> LinkRoute.BLUETOOTH
            }
        }
    }

    /**
     * 插上 USB 设备时该不该切过去。
     *
     * 点名蓝牙 HID（低功耗或经典）时不切：用户刚刚手动固定了链路，插一根
     * 数据线不该把他拽走。点名串口时仍按协议偏好决定，因为「有线串口」本身
     * 就是一条 USB 链路。
     */
    @JvmStatic
    fun shouldReactToUsbAttach(protocol: String?, transport: String?): Boolean {
        val protocolValue = ConnectionProtocolPolicy.normalize(protocol)
        return when (ConnectionTransportPolicy.normalize(transport)) {
            ConnectionTransportPolicy.HID_USB,
            ConnectionTransportPolicy.USB_SERIAL -> true
            ConnectionTransportPolicy.HID_BLE,
            ConnectionTransportPolicy.HID_CLASSIC -> false
            ConnectionTransportPolicy.SERIAL -> protocolValue == ConnectionProtocolPolicy.USB
            else -> true
        }
    }

    /**
     * 用户点名只走蓝牙 HID（低功耗）时，HID 失败不许悄悄回落到串口。
     *
     * 改造前「标准 HID」这一档的注释写着「不回落串口」，代码却仍然会去建
     * RFCOMM 线程；用户选了 HID 却在串口上连上了，等于设置被吞掉。
     */
    @JvmStatic
    fun forbidsSerialFallback(transport: String?): Boolean =
        ConnectionTransportPolicy.normalize(transport) == ConnectionTransportPolicy.HID_BLE
}

/**
 * 点名了「只走 HID」但这条链路起不来：报一次进度就停下，不再建串口线程。
 * 返回 true 表示调用方应当直接结束本次连接。
 */
internal fun DisplayService.stopBeforeSerialFallback(): Boolean {
    if (!HidTransportRouting.forbidsSerialFallback(
            ConnectionTransportPolicy.current(this))) {
        return false
    }
    Timber.i("Transport pinned to Bluetooth HID; refusing serial fallback")
    mHandler.setConnectionProgress(getString(R.string.connprog_hid_unavailable))
    scheduleAutoReconnectIfNeeded()
    return true
}

/**
 * 用户选了经典蓝牙 HID：这条链路在 Android 上不归第三方应用管，本 App 什么
 * 都不能做，只能如实说明，而不是假装连了一下。
 */
internal fun DisplayService.reportSystemOnlyTransport() {
    Timber.i("Transport hid_classic selected; the system HID host owns this link")
    mHandler.setConnectionProgress(
        getString(R.string.connprog_hid_classic_system_only))
    scheduleAutoReconnectIfNeeded()
}
