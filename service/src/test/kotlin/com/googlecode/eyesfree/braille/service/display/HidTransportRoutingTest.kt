/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.braille.service.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「协议偏好 × 连接方式偏好 × 是否插线」折成的起手路由。
 *
 * <p>这几条断言钉的都是「用户点名了哪条链路就必须从哪条起手」，而不是
 * 「有没有连上」——连不上是设备问题，起手错是代码问题，两者在日志里必须
 * 能区分开。
 */
class HidTransportRoutingTest {

    private fun resolve(protocol: String, transport: String, attached: Boolean = false) =
        HidTransportRouting.resolve(protocol, transport, attached)

    @Test
    fun transportPinsTheRouteRegardlessOfProtocol() {
        assertEquals(
            LinkRoute.BLUETOOTH,
            resolve(ConnectionProtocolPolicy.BLUETOOTH, ConnectionTransportPolicy.HID_BLE)
        )
        // 点名蓝牙 HID 时就算当前协议是 auto、还插着线，也不许被拽去 USB。
        assertEquals(
            LinkRoute.BLUETOOTH,
            resolve(ConnectionProtocolPolicy.AUTO, ConnectionTransportPolicy.HID_BLE, attached = true)
        )
        // 点名 USB HID 时不试串口，直接走系统 HID 控制器。
        assertEquals(
            LinkRoute.USB_HID,
            resolve(ConnectionProtocolPolicy.BLUETOOTH, ConnectionTransportPolicy.HID_USB)
        )
        assertEquals(
            LinkRoute.USB_HID,
            resolve(ConnectionProtocolPolicy.USB, ConnectionTransportPolicy.HID_USB, attached = true)
        )
        // 经典蓝牙 HID 不归本 App 管。
        assertEquals(
            LinkRoute.NONE,
            resolve(ConnectionProtocolPolicy.AUTO, ConnectionTransportPolicy.HID_CLASSIC)
        )
        // USB 有线串口：协议是蓝牙也必须走线，不能被协议拽回蓝牙。
        assertEquals(
            LinkRoute.USB_SERIAL,
            resolve(ConnectionProtocolPolicy.BLUETOOTH, ConnectionTransportPolicy.USB_SERIAL)
        )
        assertEquals(
            LinkRoute.USB_SERIAL,
            resolve(ConnectionProtocolPolicy.AUTO, ConnectionTransportPolicy.USB_SERIAL)
        )
    }

    @Test
    fun serialKeepsProtocolPreference() {
        assertEquals(
            LinkRoute.BLUETOOTH,
            resolve(ConnectionProtocolPolicy.BLUETOOTH, ConnectionTransportPolicy.SERIAL)
        )
        assertEquals(
            LinkRoute.USB_SERIAL,
            resolve(ConnectionProtocolPolicy.USB, ConnectionTransportPolicy.SERIAL)
        )
    }

    @Test
    fun autoKeepsLegacyBehaviour() {
        assertEquals(
            LinkRoute.USB_SERIAL,
            resolve(ConnectionProtocolPolicy.USB, ConnectionTransportPolicy.AUTO)
        )
        // 自动 + 插着线：线的优先级高于蓝牙，与改造前一致。
        assertEquals(
            LinkRoute.USB_SERIAL,
            resolve(ConnectionProtocolPolicy.AUTO, ConnectionTransportPolicy.AUTO, attached = true)
        )
        assertEquals(
            LinkRoute.BLUETOOTH,
            resolve(ConnectionProtocolPolicy.AUTO, ConnectionTransportPolicy.AUTO, attached = false)
        )
        assertEquals(
            LinkRoute.BLUETOOTH,
            resolve(ConnectionProtocolPolicy.BLUETOOTH, ConnectionTransportPolicy.AUTO, attached = false)
        )
        // 存了坏值：按自动走，不能凭空变成别的链路。
        assertEquals(LinkRoute.BLUETOOTH, resolve("garbage", "garbage", attached = false))
    }

    @Test
    fun usbAttachDoesNotHijackPinnedBluetoothHid() {
        assertFalse(
            HidTransportRouting.shouldReactToUsbAttach(
                ConnectionProtocolPolicy.AUTO, ConnectionTransportPolicy.HID_BLE
            )
        )
        assertFalse(
            HidTransportRouting.shouldReactToUsbAttach(
                ConnectionProtocolPolicy.AUTO, ConnectionTransportPolicy.HID_CLASSIC
            )
        )
        assertTrue(
            HidTransportRouting.shouldReactToUsbAttach(
                ConnectionProtocolPolicy.AUTO, ConnectionTransportPolicy.AUTO
            )
        )
        assertTrue(
            HidTransportRouting.shouldReactToUsbAttach(
                ConnectionProtocolPolicy.BLUETOOTH, ConnectionTransportPolicy.HID_USB
            )
        )
        // 点名 USB 串口时要响应插线（这条链路本来就是线）。
        assertTrue(
            HidTransportRouting.shouldReactToUsbAttach(
                ConnectionProtocolPolicy.BLUETOOTH, ConnectionTransportPolicy.USB_SERIAL
            )
        )
        // 串口档：只有协议本来就指 USB 时才响应插线。
        assertTrue(
            HidTransportRouting.shouldReactToUsbAttach(
                ConnectionProtocolPolicy.USB, ConnectionTransportPolicy.SERIAL
            )
        )
        assertFalse(
            HidTransportRouting.shouldReactToUsbAttach(
                ConnectionProtocolPolicy.BLUETOOTH, ConnectionTransportPolicy.SERIAL
            )
        )
    }

    @Test
    fun onlyBluetoothHidForbidsSerialFallback() {
        assertTrue(
            HidTransportRouting.forbidsSerialFallback(ConnectionTransportPolicy.HID_BLE)
        )
        assertFalse(
            HidTransportRouting.forbidsSerialFallback(ConnectionTransportPolicy.AUTO)
        )
        assertFalse(
            HidTransportRouting.forbidsSerialFallback(ConnectionTransportPolicy.SERIAL)
        )
        assertFalse(
            HidTransportRouting.forbidsSerialFallback(ConnectionTransportPolicy.HID_CLASSIC)
        )
    }
}
