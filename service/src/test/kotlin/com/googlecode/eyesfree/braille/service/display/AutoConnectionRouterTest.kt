/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.braille.service.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUTO 纯路由 + 切换幂等判据。
 *
 * 六条断言钉的是「这次该从哪条链路起手、要不要播报、算不算重复切换」，
 * 连不上是设备问题，起手错是代码问题，两者在日志里必须能区分开。
 */
class AutoConnectionRouterTest {

    /** metec 40 方（42 格）USB，VID:PID 0452:0100，驱动码 mt，口径沿用 usb_devices 表。 */
    private val metecUsb = UsbProbe(0x0452, 0x0100, listOf("mt"))

    private fun route(
        protocol: String = ConnectionProtocolPolicy.AUTO,
        transport: String = ConnectionTransportPolicy.AUTO,
        usb: List<UsbProbe> = emptyList(),
        btOn: Boolean = false,
        btBonded: Boolean = false,
        hidAvailable: Boolean = true,
    ): AutoRouteResult = AutoConnectionRouter.route(
        AutoRouteInput(protocol, transport, usb, btOn, btBonded, hidAvailable)
    )

    // 情形一：有已知盲文 USB 设备时，USB 优先于同时在线的蓝牙。
    @Test
    fun usbDisplayWinsOverBluetooth() {
        val result = route(usb = listOf(metecUsb), btOn = true, btBonded = true)
        assertEquals(LinkRoute.USB_SERIAL, result.route)
        assertEquals(AutoAnnounce.USB_DISPLAY_FOUND, result.announce)
        assertFalse(result.keepCurrentAndRescan)
        assertNull(result.degradedReason)
    }

    // 情形二：没有 USB，但蓝牙开启且有已配对已知点显 → 蓝牙。
    @Test
    fun bondedBluetoothUsedWhenNoUsb() {
        val result = route(btOn = true, btBonded = true)
        assertEquals(LinkRoute.BLUETOOTH, result.route)
        assertEquals(AutoAnnounce.NONE, result.announce)
        assertFalse(result.keepCurrentAndRescan)
        assertNull(result.degradedReason)
    }

    // 情形三：两者皆无 → 保持当前档，进入周期性重扫，不静默失败。
    @Test
    fun neitherKeepsCurrentAndRescans() {
        val result = route(btOn = false, btBonded = false)
        assertEquals(LinkRoute.BLUETOOTH, result.route)
        assertEquals(AutoAnnounce.WAITING_FOR_DISPLAY, result.announce)
        assertTrue(result.keepCurrentAndRescan)
        assertNull(result.degradedReason)
    }

    // 情形四：手动档覆盖 AUTO——选了蓝牙 / USB / 链路点名 HID，自动判定不许介入。
    @Test
    fun manualProtocolOverridesAuto() {
        // 点名蓝牙：插着线也不许拽去 USB。
        val bluetooth = route(
            protocol = ConnectionProtocolPolicy.BLUETOOTH,
            usb = listOf(metecUsb),
        )
        assertEquals(LinkRoute.BLUETOOTH, bluetooth.route)
        assertEquals(AutoAnnounce.NONE, bluetooth.announce)
        assertFalse(bluetooth.keepCurrentAndRescan)

        // 点名 USB：没有线也按 USB 起手。
        val usb = route(protocol = ConnectionProtocolPolicy.USB)
        assertEquals(LinkRoute.USB_SERIAL, usb.route)

        // 链路点名蓝牙 HID：协议是 AUTO 也被固定到蓝牙。
        val hidBle = route(
            transport = ConnectionTransportPolicy.HID_BLE,
            usb = listOf(metecUsb),
        )
        assertEquals(LinkRoute.BLUETOOTH, hidBle.route)
    }

    // 情形五：HID 控制器不可用时，AUTO 显式降级到 usb_serial，而不是直接判失败。
    @Test
    fun hidUnavailableDegradesToUsbSerial() {
        val result = route(usb = listOf(metecUsb), hidAvailable = false)
        assertEquals(LinkRoute.USB_SERIAL, result.route)
        assertEquals(AutoAnnounce.HID_DEGRADED_TO_SERIAL, result.announce)
        assertEquals(
            AutoConnectionRouter.DEGRADED_REASON_HID_UNAVAILABLE,
            result.degradedReason,
        )
        assertFalse(result.keepCurrentAndRescan)

        // 没有 USB 时 HID 不可用仍落蓝牙串口（RFCOMM），不卡在 HID 等待上。
        val bluetoothFallback = route(btOn = true, btBonded = true, hidAvailable = false)
        assertEquals(LinkRoute.BLUETOOTH, bluetoothFallback.route)
        assertNull(bluetoothFallback.degradedReason)
    }

    // 情形六：重复 attach 同一台设备不来回切；同设备换路由受 60 秒冷却窗口约束。
    @Test
    fun repeatedAttachDoesNotSwitchTwice() {
        val deviceKey = "0452:0100"

        val first = AutoSwitchGuard.decide(
            AutoSwitchGuard.State.IDLE, deviceKey, LinkRoute.USB_SERIAL, nowMs = 0L,
        )
        assertTrue("首次落定应当切换", first.shouldSwitch)
        assertEquals(deviceKey, first.nextState.deviceKey)
        assertEquals(LinkRoute.USB_SERIAL, first.nextState.route)

        // 重复 attach 同一台、链路也没变：幂等忽略，状态原样保留。
        val secondAttach = AutoSwitchGuard.decide(
            first.nextState, deviceKey, LinkRoute.USB_SERIAL, nowMs = 30_000L,
        )
        assertFalse("重复 attach 同一路由不应再切", secondAttach.shouldSwitch)
        assertEquals(first.nextState, secondAttach.nextState)

        // 30 秒内同一台设备要换链路（USB / 蓝牙同时在线抖动）：冷却窗口内按住不切。
        val flap = AutoSwitchGuard.decide(
            first.nextState, deviceKey, LinkRoute.BLUETOOTH, nowMs = 30_000L,
        )
        assertFalse("冷却窗口内同设备换路由应当按住", flap.shouldSwitch)
        assertEquals(first.nextState, flap.nextState)

        // 冷却窗口外再判同设备换链路：允许重新落定。
        val afterCooldown = AutoSwitchGuard.decide(
            first.nextState, deviceKey, LinkRoute.BLUETOOTH, nowMs = 61_000L,
        )
        assertTrue("冷却结束后应当允许切换", afterCooldown.shouldSwitch)
    }
}
