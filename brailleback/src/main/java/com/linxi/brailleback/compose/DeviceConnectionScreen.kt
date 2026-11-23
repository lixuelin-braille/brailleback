/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.compose

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.googlecode.eyesfree.braille.display.DeviceListEntry

/**
 * 「设备与连接」子屏。
 *
 * <p>设置项只保留一行链路选择（[DeviceConnectionLogic.KEY_TRANSPORT]）。早先还有
 * 一行「协议」（蓝牙 / USB / 自动），与链路选择信息重复：两行都叫「连接方式」、
 * 都带一个「自动」，改一处另一处不变，读屏用户听到两行几乎相同的标题根本分不清
 * 该改哪一行。现在由六条链路取值覆盖全部组合，协议偏好改由链路推导后回写。
 *
 * 纯展示 + 显式回调，不持有 Context、不直接读写 SharedPreferences；
 * 状态由 Activity 提供，符合项目的 state holder + 显式参数约定。
 */
@Composable
internal fun DeviceConnectionScreen(
    labels: DeviceConnectionLabels,
    stateText: String,
    protocolValue: String,
    autoConnect: Boolean,
    candidates: List<DeviceListEntry>,
    searching: Boolean,
    discoveryEnabled: Boolean,
    canDisconnect: Boolean,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onTransportSelected: (String) -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
    onStartDiscovery: () -> Unit,
    onCandidateClicked: (DeviceListEntry) -> Unit,
    onBack: () -> Unit,
) {
    var showTransportDialog by remember { mutableStateOf(false) }
    val protocolLabel = DeviceConnectionLogic.protocolLabelForValue(
        labels.protocolValues, labels.protocolEntries, protocolValue
    )

    SettingsScreenScaffold(
        title = labels.title,
        backLabel = labels.back,
        onBack = onBack,
    ) {
        settingsSectionHeader(labels.section)

        // 用户主动查找入口：单独一行动作。新行不挂 key，保持既有设置项的结构不变。
        item {
            val discoverSummary = when {
                !discoveryEnabled -> labels.discoverDenied
                searching -> labels.discoverSearching
                else -> labels.discoverHint
            }
            SettingsActionRow(
                title = labels.discoverTitle,
                summary = discoverSummary,
                clickLabel = if (discoveryEnabled) labels.discoverTitle else "",
                onClick = onStartDiscovery,
                enabled = discoveryEnabled,
            )
        }

        item(key = DeviceConnectionLogic.KEY_CONNECTION_STATUS) {
            SettingsActionRow(
                title = labels.statusTitle,
                summary = stateText,
                clickLabel = labels.refreshHint,
                onClick = onRefresh,
            )
        }

        // 断开当前点显器：只有真连上才可点。断开后服务端会暂停自动重连，
        // 所以这一行必须存在且能随时按到，否则用户只能去系统蓝牙设置里断。
        item(key = DeviceConnectionLogic.KEY_DISCONNECT) {
            SettingsActionRow(
                title = labels.disconnectTitle,
                summary = if (canDisconnect) labels.disconnectHint else labels.disconnectIdle,
                clickLabel = if (canDisconnect) labels.disconnectTitle else "",
                onClick = onDisconnect,
                enabled = canDisconnect,
            )
        }

        // 连接方式只有三项：自动 / 蓝牙 / 有线 USB。摘要里必须带上「这样选意味着什么」，
        // 只说一个词会让读屏用户分不清「自动」与「蓝牙」的差别。
        item(key = DeviceConnectionLogic.KEY_TRANSPORT) {
            SettingsListRow(
                title = labels.transportTitle,
                currentLabel = protocolLabel + " · " + DeviceConnectionLogic.protocolSummary(
                    protocolValue,
                    labels.protocolAutoSummary,
                    labels.protocolBluetoothSummary,
                    labels.protocolUsbSummary
                ),
                chooseHint = labels.chooseHint,
                onClick = { showTransportDialog = true },
            )
        }

        item(key = DeviceConnectionLogic.KEY_AUTO_CONNECT) {
            SettingsSwitchRow(
                title = labels.autoConnectTitle,
                summary = labels.autoConnectSummary,
                checked = autoConnect,
                onLabel = labels.on,
                offLabel = labels.off,
                onCheckedChange = onAutoConnectChange,
            )
        }

        // 候选设备列表：已配对设备顺序来自服务端既有逻辑，未配对项追加在后。
        // 状态（已配对 / 未配对）写进 summary，TalkBack 朗读整行时能听到，不只靠图标。
        // 地址作 key 必须唯一：服务端已按地址去重，这里再兜一层，避免任何
        // 重复项把整页崩掉（Compose 对重复 key 直接抛异常）。
        items(candidates.distinctBy { it.address }, key = { it.address }) { entry ->
            val stateLabel = DeviceConnectionLogic.candidateStateLabel(
                entry.state,
                labels.bondedLabel,
                labels.unpairedLabel,
                labels.bondingLabel,
                labels.failedLabel,
                labels.timeoutLabel
            )
            val actionLabel = DeviceConnectionLogic.candidateActionLabel(
                entry.state,
                labels.connectHint,
                labels.pairLabel,
                labels.retryLabel
            )
            SettingsActionRow(
                title = DeviceConnectionLogic.candidateDisplayName(
                    entry.name, labels.unknownName
                ),
                summary = stateLabel,
                clickLabel = actionLabel,
                onClick = { onCandidateClicked(entry) },
            )
        }
    }

    if (showTransportDialog) {
        SettingsChoiceDialog(
            title = labels.transportTitle,
            entries = labels.protocolEntries,
            values = labels.protocolValues,
            currentValue = protocolValue,
            cancelLabel = labels.cancel,
            selectedLabel = labels.selected,
            unselectedLabel = labels.unselected,
            onSelect = { value ->
                showTransportDialog = false
                onTransportSelected(value)
            },
            onDismiss = { showTransportDialog = false },
        )
    }
}
