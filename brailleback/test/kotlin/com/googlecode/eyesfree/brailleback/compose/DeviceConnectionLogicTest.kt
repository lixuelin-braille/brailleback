/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.compose

import com.googlecode.eyesfree.braille.service.display.ConnectionProtocolPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「设备与连接」Compose 子屏的纯逻辑单测（零 Android 依赖）。 */
class DeviceConnectionLogicTest {

    private val connected = "已连接"
    private val notConnected = "未连接"
    private val unavailable = "不可用"

    private fun text(state: Int, progress: String? = null) =
        DeviceConnectionLogic.resolveStateText(
            state, progress, connected, notConnected, unavailable
        )

    @Test
    fun progressDescriptionTakesPriorityOverState() {
        assertEquals("正在连接蓝牙点显器", text(1, "正在连接蓝牙点显器"))
        assertEquals("正在连接蓝牙点显器", text(-1, "正在连接蓝牙点显器"))
        assertEquals("", text(1, ""))
    }

    @Test
    fun stateIsMappedToLocalizedText() {
        assertEquals(connected, text(DeviceConnectionLogic.STATE_CONNECTED))
        assertEquals(notConnected, text(DeviceConnectionLogic.STATE_NOT_CONNECTED))
        assertEquals(unavailable, text(-1))
        assertEquals(unavailable, text(-2))
        assertEquals(unavailable, text(Int.MAX_VALUE))
    }

    @Test
    fun protocolNormalizationMatchesServicePolicy() {
        listOf(null, "", "usb", "usbhid", "bluetooth", "hid", "rfcomm", "auto", "junk")
            .forEach { value ->
                assertEquals(
                    "归一化必须与 ConnectionProtocolPolicy 完全一致：$value",
                    ConnectionProtocolPolicy.normalize(value),
                    DeviceConnectionLogic.normalizeProtocol(value)
                )
            }
    }

    @Test
    fun isUsbOnlyForUsbFamily() {
        assertTrue(DeviceConnectionLogic.isUsb("usb"))
        assertTrue(DeviceConnectionLogic.isUsb("usbhid"))
        assertFalse(DeviceConnectionLogic.isUsb("bluetooth"))
        assertFalse(DeviceConnectionLogic.isUsb(null))
    }

    @Test
    fun labelForValueHandlesKnownUnknownAndEmptyLists() {
        val values = listOf("bluetooth", "usb")
        val entries = listOf("蓝牙连接", "USB 连接")
        assertEquals("蓝牙连接", DeviceConnectionLogic.labelForValue(values, entries, "bluetooth"))
        assertEquals("USB 连接", DeviceConnectionLogic.labelForValue(values, entries, "usb"))
        // 旧版本值仍要能显示成 USB，不能朗读空白
        assertEquals("USB 连接", DeviceConnectionLogic.labelForValue(values, entries, "usbhid"))
        assertEquals("蓝牙连接", DeviceConnectionLogic.labelForValue(values, entries, null))
        assertEquals("蓝牙连接", DeviceConnectionLogic.labelForValue(values, entries, "junk"))
        assertEquals("", DeviceConnectionLogic.labelForValue(emptyList(), emptyList(), "usb"))
    }

    @Test
    fun labelForAutoReadsTheThirdEntry() {
        val values = listOf("bluetooth", "usb", "auto")
        val entries = listOf("蓝牙连接", "USB 连接", "自动")
        // 自动必须是第三项、且真的能读到：读屏只会顺序念这个列表，
        // 少一项就等于这一项不存在。
        assertEquals("自动", DeviceConnectionLogic.labelForValue(values, entries, "auto"))
        assertFalse(DeviceConnectionLogic.isUsb("auto"))
    }
}
