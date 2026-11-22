/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.braille.service.display

import android.content.Context
import com.googlecode.eyesfree.utils.SettingsStore

/**
 * 蓝牙内部走哪条链路：标准 Braille HID（系统 BrailleDisplayController），
 * 还是经典串口 SPP（交给 brltty 驱动）。
 *
 * <p>这是「蓝牙」协议内部的细分，和协议偏好（蓝牙 / USB / 自动）是两层，
 * 因此单独一个键，不塞进 `connection_protocol`。
 *
 * - [AUTO]：保持既有判断。对 Wujie / Orbit 系列跳过 HID 走 SPP（该固件的
 *   HID 只声明输入报告、从不推按键，实测按遍所有键 onInput 回调 0 次），
 *   其它设备 HID 优先、SPP 兜底。
 * - [HID]：只用标准 Braille HID，不回落串口。这条就是「独立连接」：连接、
 *   收发都由系统和本 App 的 HID 通道负责，不经过 brltty。
 * - [SERIAL]：只用经典串口，跳过 HID（按键由 brltty 的驱动解析后回传）。
 */
object ConnectionTransportPolicy {
    const val AUTO = "auto"
    const val HID = "hid"
    const val SERIAL = "serial"

    /** 偏好键。只被 Compose 设备页读写，旧 XML 设置页不显示这一项。 */
    const val PREFERENCE_KEY = "connection_transport"

    @JvmStatic
    fun normalize(value: String?): String {
        return when (value?.trim()?.lowercase()) {
            HID, "ble", "blehid", "hidfirst" -> HID
            SERIAL, "spp", "rfcomm", "classic" -> SERIAL
            else -> AUTO
        }
    }

    /** 读当前偏好。每次连接判定读一次，不缓存，避免设置页改了不生效。 */
    @JvmStatic
    fun current(context: Context): String {
        return normalize(SettingsStore.get(context).getString(PREFERENCE_KEY, null))
    }

    /**
     * 本次连接要不要跳过 HID。
     *
     * [legacyDecision] 就是 [AUTO] 下既有的厂商判断，由调用方传入，避免把
     * 「哪些名字算 Wujie」这类规则复制第二份。
     */
    @JvmStatic
    fun shouldSkipHid(transport: String, legacyDecision: () -> Boolean): Boolean {
        return when (normalize(transport)) {
            HID -> false
            SERIAL -> true
            else -> legacyDecision()
        }
    }
}

/**
 * 连接周期要不要跳过标准 Braille HID。原实现是 [DisplayService] 里的私有
 * 厂商判断（[DisplayService.legacyShouldSkipHidByVendor]），这里只加一层
 * 「用户选择的链路优先」。
 */
internal fun DisplayService.shouldSkipHidForWujie(): Boolean =
    ConnectionTransportPolicy.shouldSkipHid(
        transport = ConnectionTransportPolicy.current(this),
        legacyDecision = { legacyShouldSkipHidByVendor() },
    )
