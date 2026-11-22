/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */
package com.googlecode.eyesfree.brailleback.compose

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
        assertEquals("hid", DeviceConnectionLogic.normalizeTransport("BLE"))
        assertEquals("serial", DeviceConnectionLogic.normalizeTransport("SPP"))
    }

    @Test
    fun transportSummaryFollowsSelection() {
        val auto = "自动：优先 HID，无界系列送去串口"
        val hid = "只用标准 HID"
        val serial = "只用经典串口"
        assertEquals(auto, DeviceConnectionLogic.transportSummary("auto", auto, hid, serial))
        assertEquals(hid, DeviceConnectionLogic.transportSummary("hid", auto, hid, serial))
        assertEquals(serial, DeviceConnectionLogic.transportSummary("serial", auto, hid, serial))
    }

    /**
     * 「标准 HID」遇到「自动本来会送去串口」的设备时要提示只能显示、不能输入。
     * 只在 HID 选项下提示，其它选项下不该出现这条警告。
     */
    @Test
    fun hidKeyWarningOnlyForHidOnSerialOnlyDevice() {
        assertEquals(true, DeviceConnectionLogic.transportHidNeedsKeyWarning("hid", true))
        assertEquals(false, DeviceConnectionLogic.transportHidNeedsKeyWarning("hid", false))
        assertEquals(false, DeviceConnectionLogic.transportHidNeedsKeyWarning("serial", true))
        assertEquals(false, DeviceConnectionLogic.transportHidNeedsKeyWarning("auto", true))
    }
}
