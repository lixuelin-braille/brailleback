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
 * 「声音与行为」子屏：六个设置项 + 两个分组标题，
 * 与 res/xml/settings.xml 中同名子屏一一对应。
 *
 * 全部复用 SettingsComponents 的组件底座，不在这里另写 Row，
 * 保证 heading / Role.Switch / stateDescription / 整行合并朗读不漂移。
 * 纯展示 + 显式回调，状态由 Activity 提供。
 */
@Composable
internal fun SoundBehaviorScreen(
    labels: SoundBehaviorLabels,
    soundEnabled: Boolean,
    volume: Int,
    pauseValue: String,
    idleValue: String,
    motionMode: Boolean,
    quietNavigation: Boolean,
    onSoundEnabledChange: (Boolean) -> Unit,
    onVolumeConfirmed: (Int) -> Unit,
    onPauseSelected: (String) -> Unit,
    onIdleSelected: (String) -> Unit,
    onMotionModeChange: (Boolean) -> Unit,
    onQuietNavigationChange: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    // 两个 ListPreference 共用一个单选对话框，记住当前编辑的是哪一项。
    var choiceDialog by remember { mutableStateOf<SoundChoiceState?>(null) }
    var volumeDialogOpen by remember { mutableStateOf(false) }

    SettingsScreenScaffold(
        title = labels.title,
        backLabel = labels.back,
        onBack = onBack,
    ) {
        settingsSectionHeader(labels.sectionSound)

        item(key = SoundBehaviorLogic.KEY_SOUND_ENABLED) {
            SettingsSwitchRow(
                title = labels.soundTitle,
                // 状态由 stateDescription 播报，摘要刻意不带「已开启/已关闭」前缀，
                // 避免读屏把同一个状态念两遍。
                summary = if (soundEnabled) {
                    labels.soundSummaryOn
                } else {
                    labels.soundSummaryOff
                },
                checked = soundEnabled,
                onLabel = labels.on,
                offLabel = labels.off,
                onCheckedChange = onSoundEnabledChange,
            )
        }

        item(key = SoundBehaviorLogic.KEY_SOUND_VOLUME) {
            SettingsActionRow(
                title = labels.volumeTitle,
                summary = SoundBehaviorLogic.formatVolume(labels.volumeTemplate, volume),
                clickLabel = labels.adjustHint,
                onClick = { volumeDialogOpen = true },
            )
        }

        settingsSectionHeader(labels.sectionBehavior)

        item(key = SoundBehaviorLogic.KEY_PAUSE_BEHAVIOR) {
            SettingsListRow(
                title = labels.pauseTitle,
                currentLabel = SoundBehaviorLogic.labelForValue(
                    labels.pauseValues, labels.pauseEntries, pauseValue
                ),
                chooseHint = labels.chooseHint,
                onClick = {
                    choiceDialog = SoundChoiceState(
                        SoundBehaviorLogic.KEY_PAUSE_BEHAVIOR, labels.pauseTitle
                    )
                },
            )
        }

        item(key = SoundBehaviorLogic.KEY_IDLE_CLEAR) {
            SettingsListRow(
                title = labels.idleTitle,
                currentLabel = SoundBehaviorLogic.labelForValue(
                    labels.idleValues, labels.idleEntries, idleValue
                ),
                chooseHint = labels.chooseHint,
                onClick = {
                    choiceDialog = SoundChoiceState(
                        SoundBehaviorLogic.KEY_IDLE_CLEAR, labels.idleTitle
                    )
                },
            )
        }

        item(key = SoundBehaviorLogic.KEY_MOTION_MODE) {
            SettingsSwitchRow(
                title = labels.motionTitle,
                summary = if (motionMode) {
                    labels.motionSummaryOn
                } else {
                    labels.motionSummaryOff
                },
                checked = motionMode,
                onLabel = labels.on,
                offLabel = labels.off,
                onCheckedChange = onMotionModeChange,
            )
        }

        item(key = SoundBehaviorLogic.KEY_QUIET_NAVIGATION) {
            SettingsSwitchRow(
                title = labels.quietTitle,
                summary = if (quietNavigation) {
                    labels.quietSummaryOn
                } else {
                    labels.quietSummaryOff
                },
                checked = quietNavigation,
                onLabel = labels.on,
                offLabel = labels.off,
                onCheckedChange = onQuietNavigationChange,
            )
        }
    }

    choiceDialog?.let { state ->
        val isPause = state.key == SoundBehaviorLogic.KEY_PAUSE_BEHAVIOR
        SettingsChoiceDialog(
            title = state.title,
            entries = if (isPause) labels.pauseEntries else labels.idleEntries,
            values = if (isPause) labels.pauseValues else labels.idleValues,
            currentValue = if (isPause) pauseValue else idleValue,
            cancelLabel = labels.cancel,
            selectedLabel = labels.selected,
            unselectedLabel = labels.unselected,
            onSelect = { value ->
                choiceDialog = null
                if (isPause) onPauseSelected(value) else onIdleSelected(value)
            },
            onDismiss = { choiceDialog = null },
        )
    }

    if (volumeDialogOpen) {
        SettingsVolumeDialog(
            title = labels.volumeTitle,
            currentValue = volume,
            min = SoundBehaviorLogic.VOLUME_MIN,
            max = SoundBehaviorLogic.VOLUME_MAX,
            valueText = { SoundBehaviorLogic.formatVolume(labels.volumeTemplate, it) },
            decreaseLabel = labels.decrease,
            increaseLabel = labels.increase,
            confirmLabel = labels.confirm,
            cancelLabel = labels.cancel,
            onConfirm = { value ->
                volumeDialogOpen = false
                onVolumeConfirmed(value)
            },
            onDismiss = { volumeDialogOpen = false },
        )
    }
}

/** 当前正在编辑的单选项：key 用于区分暂停行为 / 闲置清除。 */
private data class SoundChoiceState(val key: String, val title: String)
