/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 「按键与手势」子屏：六个设置项 + 两个分组标题，
 * 与 res/xml/settings.xml 中同名子屏一一对应。
 *
 * 全部复用 SettingsComponents 的组件底座，不在这里另写 Row，
 * 保证 heading / Role.Switch / stateDescription / 整行合并朗读不漂移。
 * 纯展示 + 显式回调，状态由 Activity 提供。
 */
@Composable
internal fun KeysGesturesScreen(
    labels: KeysGesturesLabels,
    helpAvailable: Boolean,
    gesturesEnabled: Boolean,
    volumeValues: Map<String, String>,
    onOpenKeyBindings: () -> Unit,
    onOpenCustomKeyMap: () -> Unit,
    onGesturesEnabledChange: (Boolean) -> Unit,
    onVolumeActionSelected: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    // 三个动作槽位共用一个对话框，记住当前编辑的是哪一项。
    var dialog by remember { mutableStateOf<ChoiceDialogState?>(null) }

    SettingsScreenScaffold(
        title = labels.title,
        backLabel = labels.back,
        onBack = onBack,
    ) {
        settingsSectionHeader(labels.sectionDisplayKeys)

        item(key = KeysGesturesLogic.KEY_KEY_BINDINGS) {
            SettingsActionRow(
                title = labels.keyBindingsTitle,
                summary = labels.keyBindingsSummary,
                clickLabel = labels.openHint,
                onClick = onOpenKeyBindings,
                // 与旧 PreferenceFragment.onConnectionStatusChanged 一致：
                // 未连接时该页没有设备属性可读，禁止进入。
                enabled = helpAvailable,
            )
        }

        item(key = KeysGesturesLogic.KEY_CUSTOM_KEY_MAP) {
            SettingsActionRow(
                title = labels.customKeyMapTitle,
                summary = labels.customKeyMapSummary,
                clickLabel = labels.openHint,
                onClick = onOpenCustomKeyMap,
            )
        }

        settingsSectionHeader(labels.sectionVolume)

        item(key = KeysGesturesLogic.KEY_VOLUME_GESTURES_ENABLED) {
            SettingsSwitchRow(
                title = labels.gesturesTitle,
                // 状态由 stateDescription 播报，摘要刻意去掉「已开启/已关闭」前缀，
                // 避免读屏把同一个状态念两遍。
                summary = if (gesturesEnabled) {
                    labels.gesturesSummaryOn
                } else {
                    labels.gesturesSummaryOff
                },
                checked = gesturesEnabled,
                onLabel = labels.on,
                offLabel = labels.off,
                onCheckedChange = onGesturesEnabledChange,
            )
        }

        // 三个动作槽位刻意逐个写出（不折叠成循环），让迁移契约能数清
        // 「六个设置项一个不少」，也方便以后逐项调整读屏文案。
        val up = labels.volumeSlots[0]
        item(key = up.key) {
            SettingsListRow(
                title = up.title,
                currentLabel = KeysGesturesLogic.labelForValue(
                    labels.actionValues, labels.actionEntries, volumeValues[up.key]
                ),
                chooseHint = labels.chooseHint,
                onClick = { dialog = ChoiceDialogState(up.key, up.title) },
            )
        }

        val down = labels.volumeSlots[1]
        item(key = down.key) {
            SettingsListRow(
                title = down.title,
                currentLabel = KeysGesturesLogic.labelForValue(
                    labels.actionValues, labels.actionEntries, volumeValues[down.key]
                ),
                chooseHint = labels.chooseHint,
                onClick = { dialog = ChoiceDialogState(down.key, down.title) },
            )
        }

        val doublePress = labels.volumeSlots[2]
        item(key = doublePress.key) {
            SettingsListRow(
                title = doublePress.title,
                currentLabel = KeysGesturesLogic.labelForValue(
                    labels.actionValues, labels.actionEntries, volumeValues[doublePress.key]
                ),
                chooseHint = labels.chooseHint,
                onClick = { dialog = ChoiceDialogState(doublePress.key, doublePress.title) },
            )
        }
    }

    dialog?.let { state ->
        SettingsChoiceDialog(
            title = state.title,
            entries = labels.actionEntries,
            values = labels.actionValues,
            currentValue = volumeValues[state.key].orEmpty(),
            cancelLabel = labels.cancel,
            selectedLabel = labels.selected,
            unselectedLabel = labels.unselected,
            onSelect = { value ->
                dialog = null
                onVolumeActionSelected(state.key, value)
            },
            onDismiss = { dialog = null },
        )
    }
}

/** 当前正在编辑的动作槽位：key 用于回写偏好，title 用作对话框标题。 */
private data class ChoiceDialogState(val key: String, val title: String)
