/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.compose

import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.googlecode.eyesfree.braille.display.DeviceListEntry

/**
 * 「设备与连接」子屏：三个设置项，与 res/xml/settings.xml 中同名子屏一一对应。
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
    onRefresh: () -> Unit,
    onProtocolSelected: (String) -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
    onStartDiscovery: () -> Unit,
    onCandidateClicked: (DeviceListEntry) -> Unit,
    onBack: () -> Unit,
) {
    var showProtocolDialog by remember { mutableStateOf(false) }
    val protocolLabel = DeviceConnectionLogic.labelForValue(
        labels.protocolValues, labels.protocolEntries, protocolValue
    )

    SettingsScreenScaffold(
        title = labels.title,
        backLabel = labels.back,
        onBack = onBack,
    ) {
        settingsSectionHeader(labels.section)

        // 用户主动查找入口：单独一行动作。新行不挂 key，保持既有三个设置项的
        // 结构不变。
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

        item(key = DeviceConnectionLogic.KEY_CONNECTION_PROTOCOL) {
            SettingsListRow(
                title = labels.protocolTitle,
                currentLabel = protocolLabel,
                chooseHint = labels.chooseHint,
                onClick = { showProtocolDialog = true },
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

    if (showProtocolDialog) {
        SettingsChoiceDialog(
            title = labels.dialogTitle,
            entries = labels.protocolEntries,
            values = labels.protocolValues,
            currentValue = protocolValue,
            cancelLabel = labels.cancel,
            selectedLabel = labels.selected,
            unselectedLabel = labels.unselected,
            onSelect = { value ->
                showProtocolDialog = false
                onProtocolSelected(value)
            },
            onDismiss = { showProtocolDialog = false },
        )
    }
}
