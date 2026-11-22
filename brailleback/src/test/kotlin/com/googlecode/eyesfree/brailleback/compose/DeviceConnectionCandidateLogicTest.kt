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
}
