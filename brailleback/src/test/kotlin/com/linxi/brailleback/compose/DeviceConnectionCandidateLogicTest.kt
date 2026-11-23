/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */
package com.linxi.brailleback.compose

import com.googlecode.eyesfree.braille.display.DeviceListEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** 候选设备状态 / 动作标签的纯逻辑单测（零 Android 依赖）。 */
class DeviceConnectionCandidateLogicTest {

    private val bonded = "已配对，点显器"
    private val unpaired = "未配对，点显器"
    private val bonding = "正在配对，点显器"
    private val failed = "配对失败，可重试"
    private val timeout = "配对超时，可重试"
    private val connect = "双击连接"
    private val pair = "双击配对"
    private val retry = "双击重试配对"
    private val unknown = "未命名设备"

    private fun stateLabel(state: Int) = DeviceConnectionLogic.candidateStateLabel(
        state, bonded, unpaired, bonding, failed, timeout
    )

    private fun actionLabel(state: Int) = DeviceConnectionLogic.candidateActionLabel(
        state, connect, pair, retry
    )

    @Test
    fun stateLabelMapsEveryState() {
        assertEquals(bonded, stateLabel(DeviceListEntry.STATE_BONDED))
        assertEquals(unpaired, stateLabel(DeviceListEntry.STATE_UNPAIRED))
        assertEquals(bonding, stateLabel(DeviceListEntry.STATE_BONDING))
        assertEquals(failed, stateLabel(DeviceListEntry.STATE_FAILED))
        assertEquals(timeout, stateLabel(DeviceListEntry.STATE_TIMEOUT))
    }

    @Test
    fun actionLabelMapsEveryState() {
        assertEquals(connect, actionLabel(DeviceListEntry.STATE_BONDED))
        assertEquals("", actionLabel(DeviceListEntry.STATE_BONDING))
        assertEquals(pair, actionLabel(DeviceListEntry.STATE_UNPAIRED))
        assertEquals(retry, actionLabel(DeviceListEntry.STATE_FAILED))
        assertEquals(retry, actionLabel(DeviceListEntry.STATE_TIMEOUT))
    }

    @Test
    fun displayNameFallsBackToUnknownWhenNameMissing() {
        assertEquals("TSM 3647", DeviceConnectionLogic.candidateDisplayName("TSM 3647", unknown))
        assertEquals(unknown, DeviceConnectionLogic.candidateDisplayName(null, unknown))
        assertEquals(unknown, DeviceConnectionLogic.candidateDisplayName("   ", unknown))
    }

    @Test
    fun discoveryGateFollowsScanPermission() {
        assertEquals(true, DeviceConnectionLogic.canStartDiscovery(scanPermissionGranted = true))
        assertEquals(false, DeviceConnectionLogic.canStartDiscovery(scanPermissionGranted = false))
    }

    /**
     * 已配对条目也要下发请求：服务端对已配对地址的处理就是「连这一台」。
     * 早先客户端对已配对条目只调 poll()（通用重连），有两台已配对点显器时
     * 点第二台其实连的还是上次那台，读屏用户无从察觉。
     */
    @Test
    fun bondedCandidateClickAlsoGoesToService() {
        assertEquals(true, DeviceConnectionLogic.shouldSendCandidateClick(
            DeviceListEntry.STATE_BONDED))
        assertEquals(true, DeviceConnectionLogic.shouldSendCandidateClick(
            DeviceListEntry.STATE_UNPAIRED))
        assertEquals(true, DeviceConnectionLogic.shouldSendCandidateClick(
            DeviceListEntry.STATE_FAILED))
        assertEquals(true, DeviceConnectionLogic.shouldSendCandidateClick(
            DeviceListEntry.STATE_TIMEOUT))
    }

    @Test
    fun bondingCandidateClickIsIgnored() {
        assertEquals(false, DeviceConnectionLogic.shouldSendCandidateClick(
            DeviceListEntry.STATE_BONDING))
    }

    /**
     * 「断开连接」只在真连上时可用：未连接时术语上没什么可断的，
     * 允许点会让连点两下把刚连上的设备断掉。
     */
    @Test
    fun disconnectOnlyOfferedWhileConnected() {
        assertEquals(true, DeviceConnectionLogic.shouldOfferDisconnect(
            DeviceConnectionLogic.STATE_CONNECTED))
        assertEquals(false, DeviceConnectionLogic.shouldOfferDisconnect(
            DeviceConnectionLogic.STATE_NOT_CONNECTED))
        assertEquals(false, DeviceConnectionLogic.shouldOfferDisconnect(
            DeviceConnectionLogic.STATE_UNKNOWN))
    }

    /** 连接方式：坏值不许让读屏停在空白项上，一律回落「自动」。 */
    @Test
    fun transportNormalizesUnknownToAuto() {
        assertEquals("auto", DeviceConnectionLogic.normalizeTransport(null))
        assertEquals("auto", DeviceConnectionLogic.normalizeTransport("nonsense"))
        assertEquals("hid_ble", DeviceConnectionLogic.normalizeTransport("BLE"))
        // 升级前存的 "hid" 语义是蓝牙低功耗 HID，不能变成别的档。
        assertEquals("hid_ble", DeviceConnectionLogic.normalizeTransport("hid"))
        assertEquals("hid_usb", DeviceConnectionLogic.normalizeTransport("usbhid"))
        assertEquals("hid_classic", DeviceConnectionLogic.normalizeTransport("hidp"))
        assertEquals("serial", DeviceConnectionLogic.normalizeTransport("SPP"))
    }

    @Test
    fun transportSummaryFollowsSelection() {
        val auto = "自动：插着线走 USB，没插走蓝牙"
        val ble = "只用标准 HID（蓝牙）"
        val usb = "只走 USB 有线 HID"
        val usbSerial = "只走 USB 有线串口"
        val serial = "只用蓝牙经典串口"
        val classic = "经典蓝牙 HID 由系统接管"
        val all = listOf(ble, usb, usbSerial, serial, classic)
        assertEquals(
            auto,
            DeviceConnectionLogic.transportSummary("auto", auto, ble, usb, usbSerial, serial, classic)
        )
        for ((index, value) in listOf("hid_ble", "hid_usb", "usb_serial", "serial", "hid_classic")
            .withIndex()) {
            assertEquals(
                all[index],
                DeviceConnectionLogic.transportSummary(
                    value, auto, ble, usb, usbSerial, serial, classic
                )
            )
        }
        // 老值 "hid" 必须落到蓝牙低功耗那一句，不能掉到自动。
        assertEquals(
            ble,
            DeviceConnectionLogic.transportSummary("hid", auto, ble, usb, usbSerial, serial, classic)
        )
    }

    /**
     * 回归：连接方式行的标签一度借用了协议归一化，`hid_usb` 匹配不到协议表，
     * 索引落成 -1 回落到第一项，界面出现「自动（推荐）· 只走 USB 有线 HID」。
     * 现在每个取值都必须念出自己那一项。
     */
    @Test
    fun transportLabelNeverFallsBackToAutoForKnownValues() {
        val values = listOf("auto", "hid_ble", "hid_usb", "usb_serial", "serial", "hid_classic")
        val entries = listOf("自动", "蓝牙 HID", "USB HID", "USB 串口", "蓝牙串口", "经典蓝牙 HID")
        for ((index, value) in values.withIndex()) {
            assertEquals(
                entries[index],
                DeviceConnectionLogic.transportLabelForValue(values, entries, value)
            )
        }
        // 老值与坏值：老值要有正确标签，坏值才允许回落第一项。
        assertEquals(
            "蓝牙 HID",
            DeviceConnectionLogic.transportLabelForValue(values, entries, "hid")
        )
        assertEquals(
            "自动",
            DeviceConnectionLogic.transportLabelForValue(values, entries, "nonsense")
        )
        assertEquals("", DeviceConnectionLogic.transportLabelForValue(emptyList(), emptyList(), "auto"))
    }

    /** 链路改变必须回写等价协议值，否则服务端 USB 失败分支会按蓝牙处理。 */
    @Test
    fun protocolForTransportKeepsLegacyKeyConsistent() {
        assertEquals("usb", DeviceConnectionLogic.protocolForTransport("hid_usb"))
        assertEquals("usb", DeviceConnectionLogic.protocolForTransport("usb_serial"))
        assertEquals("auto", DeviceConnectionLogic.protocolForTransport("auto"))
        assertEquals("bluetooth", DeviceConnectionLogic.protocolForTransport("hid_ble"))
        assertEquals("bluetooth", DeviceConnectionLogic.protocolForTransport("serial"))
        assertEquals("bluetooth", DeviceConnectionLogic.protocolForTransport("hid_classic"))
        // 老值也要折对。
        assertEquals("usb", DeviceConnectionLogic.protocolForTransport("usbhid"))
    }

    /**
     * 「标准 HID（蓝牙）」遇到「自动本来会送去串口」的设备时要提示只能显示、不能输入。
     * 只在蓝牙 HID 选项下提示，其它选项下不该出现这条警告。
     */
    @Test
    fun hidKeyWarningOnlyForHidOnSerialOnlyDevice() {
        assertEquals(true, DeviceConnectionLogic.transportHidNeedsKeyWarning("hid_ble", true))
        assertEquals(false, DeviceConnectionLogic.transportHidNeedsKeyWarning("hid_ble", false))
        assertEquals(false, DeviceConnectionLogic.transportHidNeedsKeyWarning("serial", true))
        assertEquals(false, DeviceConnectionLogic.transportHidNeedsKeyWarning("auto", true))
        // USB 有线那条不是 GATT 链路，套用「固件不推按键」的警告会误导。
        assertEquals(false, DeviceConnectionLogic.transportHidNeedsKeyWarning("hid_usb", true))
        assertEquals(false, DeviceConnectionLogic.transportHidNeedsKeyWarning("hid_classic", true))
    }
}
