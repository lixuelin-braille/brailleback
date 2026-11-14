/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

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
    onRefresh: () -> Unit,
    onProtocolSelected: (String) -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
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
