/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.braille.service.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「连接方式」取值的纯逻辑测试。
 *
 * <p>五条取值的语义必须稳定，否则用户在设备页选「标准 HID（蓝牙）」却还是被
 * 送去串口，或者选「经典串口」却先试了 HID，都表现为「选了没用」，而读屏用户
 * 没有任何办法从现象上判断是设置没生效还是设备不支持。
 */
class ConnectionTransportPolicyTest {

    @Test
    fun normalizeAcceptsLegacyAndKnownValues() {
        assertEquals(ConnectionTransportPolicy.AUTO, ConnectionTransportPolicy.normalize(null))
        assertEquals(ConnectionTransportPolicy.AUTO, ConnectionTransportPolicy.normalize(""))
        assertEquals(ConnectionTransportPolicy.AUTO, ConnectionTransportPolicy.normalize("  "))
        assertEquals(ConnectionTransportPolicy.AUTO, ConnectionTransportPolicy.normalize("whatever"))
        assertEquals(ConnectionTransportPolicy.AUTO, ConnectionTransportPolicy.normalize("AUTO"))
        // 升级前的老值 hid / ble / blehid 语义就是 HOGP。
        assertEquals(ConnectionTransportPolicy.HID_BLE, ConnectionTransportPolicy.normalize("hid"))
        assertEquals(ConnectionTransportPolicy.HID_BLE, ConnectionTransportPolicy.normalize("BLE"))
        assertEquals(ConnectionTransportPolicy.HID_BLE, ConnectionTransportPolicy.normalize("blehid"))
        assertEquals(ConnectionTransportPolicy.HID_BLE, ConnectionTransportPolicy.normalize("hid_ble"))
        assertEquals(ConnectionTransportPolicy.HID_USB, ConnectionTransportPolicy.normalize("usbhid"))
        assertEquals(ConnectionTransportPolicy.HID_USB, ConnectionTransportPolicy.normalize("hid_usb"))
        assertEquals(
            ConnectionTransportPolicy.USB_SERIAL,
            ConnectionTransportPolicy.normalize("usb_serial")
        )
        assertEquals(ConnectionTransportPolicy.HID_CLASSIC, ConnectionTransportPolicy.normalize("hidp"))
        assertEquals(
            ConnectionTransportPolicy.HID_CLASSIC,
            ConnectionTransportPolicy.normalize("HID_CLASSIC")
        )
        assertEquals(ConnectionTransportPolicy.SERIAL, ConnectionTransportPolicy.normalize("serial"))
        assertEquals(ConnectionTransportPolicy.SERIAL, ConnectionTransportPolicy.normalize("SPP"))
        assertEquals(ConnectionTransportPolicy.SERIAL, ConnectionTransportPolicy.normalize("rfcomm"))
        // 老的 classic 指经典串口，不能被折成经典 HID。
        assertEquals(ConnectionTransportPolicy.SERIAL, ConnectionTransportPolicy.normalize("classic"))
    }

    @Test
    fun hidNeverSkipsHid() {
        assertFalse(
            ConnectionTransportPolicy.shouldSkipHid(ConnectionTransportPolicy.HID_BLE) { true }
        )
        assertFalse(
            ConnectionTransportPolicy.shouldSkipHid(ConnectionTransportPolicy.HID_BLE) { false }
        )
    }

    @Test
    fun serialAlwaysSkipsHid() {
        assertTrue(
            ConnectionTransportPolicy.shouldSkipHid(ConnectionTransportPolicy.SERIAL) { true }
        )
        assertTrue(
            ConnectionTransportPolicy.shouldSkipHid(ConnectionTransportPolicy.SERIAL) { false }
        )
    }

    /** USB HID 与经典 HID 都不是蓝牙 GATT 链路，必须跳过蓝牙 HID 尝试。 */
    @Test
    fun nonGattTransportsSkipBluetoothHid() {
        assertTrue(
            ConnectionTransportPolicy.shouldSkipHid(ConnectionTransportPolicy.HID_USB) { false }
        )
        assertTrue(
            ConnectionTransportPolicy.shouldSkipHid(ConnectionTransportPolicy.HID_CLASSIC) { false }
        )
    }

    @Test
    fun autoDefersToVendorDecision() {
        assertTrue(ConnectionTransportPolicy.shouldSkipHid(ConnectionTransportPolicy.AUTO) { true })
        assertFalse(
            ConnectionTransportPolicy.shouldSkipHid(ConnectionTransportPolicy.AUTO) { false }
        )
        // 未知值走自动，不能因为存了个坏值就 stuck 在串口。
        assertTrue(ConnectionTransportPolicy.shouldSkipHid("garbage") { true })
    }
}
