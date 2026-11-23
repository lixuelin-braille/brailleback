/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.braille.service.display

import android.content.Context
import com.googlecode.eyesfree.utils.SettingsStore

/**
 * 「连接方式」：用户点名要走哪一条链路。
 *
 * <p>标准 Braille HID 不是一种链路，而是一套 report descriptor 被三条物理
 * 承载分别运送，因此这里把它们拆成三个可分别点名的取值：
 *
 * - [HID_BLE]：蓝牙低功耗 HID over GATT（HOGP，GATT 服务 0x1812）。链路、报告
 *   收发都由系统 [android.accessibilityservice.BrailleDisplayController] 负责。
 * - [HID_USB]：USB 有线 HID（USB-IF HID 1.11 设备类）。同一条 HID 驱动，只是
 *   底层换成了有线端点，并多一步系统 USB 授权。
 * - [HID_CLASSIC]：蓝牙经典 HID（HIDP 1.1，BR/EDR，SDP 记录 0x1124）。
 *   这条在 Android 上第三方应用拿不到：`HID_HOST` 是 @SystemApi，需要
 *   BLUETOOTH_PRIVILEGED，App 既不能注册 HID 主机，也不能发 output report。
 *   选中它时本 App 不发起连接，由系统输入系统接管（只能收到按键，无法写点显器）。
 * - [USB_SERIAL]：USB 线 + brltty 的串口驱动（有线老设备最常见的一条）。
 * - [SERIAL]：蓝牙经典串口 SPP（RFCOMM，0x1101），交给 brltty 驱动解析按键。
 * - [AUTO]：保持既有判断（对 HID 不推按键的厂商固件跳过 HID，其余 HID 优先）。
 *
 * <p>这是「蓝牙 / USB / 自动」协议偏好内部的细分，两层结构，因此单独一个键，
 * 不塞进 `connection_protocol`。注意：六条取值已经覆盖了协议维度的全部组合，
 * 所以设置页只保留这一行，协议偏好改成由它推导后回写（见
 * [com.linxi.brailleback.compose.DeviceConnectionLogic.protocolForTransport]），
 * 避免同一件事在两行设置里各存一份、改一处另一处不变。
 */
object ConnectionTransportPolicy {
    const val AUTO = "auto"
    const val HID_BLE = "hid_ble"
    const val HID_USB = "hid_usb"
    const val HID_CLASSIC = "hid_classic"
    const val USB_SERIAL = "usb_serial"
    const val SERIAL = "serial"

    /** 偏好键。只被 Compose 设备页读写，旧 XML 设置页不显示这一项。 */
    const val PREFERENCE_KEY = "connection_transport"

    /**
     * 兼容老值：升级前存的是 `hid` / `ble` / `blehid`，语义就是 HOGP，
     * 一律折成 [HID_BLE]；老的 `classic` 指的是经典串口，不能折成 [HID_CLASSIC]。
     */
    @JvmStatic
    fun normalize(value: String?): String {
        return when (value?.trim()?.lowercase()) {
            HID_BLE, "hid", "ble", "blehid", "hidfirst", "hogp", "gatt" -> HID_BLE
            HID_USB, "usb", "usbhid" -> HID_USB
            USB_SERIAL, "usbserial", "usb_serial", "usb-serial" -> USB_SERIAL
            HID_CLASSIC, "hidp", "btclassic" -> HID_CLASSIC
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
     * 本次连接要不要跳过标准 HID。
     *
     * [legacyDecision] 就是 [AUTO] 下既有的厂商判断，由调用方传入，避免把
     * 「哪些名字算 Wujie」这类规则复制第二份。
     *
     * 只有 [HID_BLE] 明确不跳过；[HID_USB] / [HID_CLASSIC] / [SERIAL] 都不走
     * 蓝牙 HID（前两者的链路不是 GATT，后者根本不用 HID）。
     */
    @JvmStatic
    fun shouldSkipHid(transport: String, legacyDecision: () -> Boolean): Boolean {
        return when (normalize(transport)) {
            HID_BLE -> false
            AUTO -> legacyDecision()
            else -> true
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
