/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.compose

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 「开发者选项」子屏：七个界面项 + 两个分组标题，
 * 与 res/xml/settings.xml 中同名子屏一一对应。
 *
 * 全部复用 SettingsComponents 的组件底座，不在这里另写 Row，
 * 保证 heading / Role.Switch / stateDescription / 整行合并朗读不漂移。
 * 对话框开闭状态留在 Screen 内，业务数据与动作由 Activity 的回调提供。
 */
@Composable
internal fun DeveloperOptionsScreen(
    labels: DeveloperOptionsLabels,
    logLevelValue: String,
    overlayEnabled: Boolean,
    onLogLevelSelected: (String) -> Unit,
    onViewLog: () -> String,
    onShareLog: () -> Unit,
    onCopyLog: () -> Unit,
    onClearLog: () -> Unit,
    onOverlayChange: (Boolean) -> Unit,
    onOpenTutorial: () -> Unit,
    onBack: () -> Unit,
) {
    var levelDialogOpen by remember { mutableStateOf(false) }
    var logViewBody by remember { mutableStateOf<String?>(null) }
    var clearConfirmOpen by remember { mutableStateOf(false) }
    val anyDialogOpen = levelDialogOpen || logViewBody != null || clearConfirmOpen

    // 系统返回键先关掉最上层对话框，再退出整个子屏。
    BackHandler(enabled = anyDialogOpen) {
        levelDialogOpen = false
        logViewBody = null
        clearConfirmOpen = false
    }

    SettingsScreenScaffold(
        title = labels.title,
        backLabel = labels.back,
        onBack = onBack,
    ) {
        settingsSectionHeader(labels.sectionLog)

        item(key = DeveloperOptionsLogic.KEY_LOG_LEVEL) {
            SettingsListRow(
                title = labels.logLevelTitle,
                currentLabel = DeveloperOptionsLogic.labelForValue(
                    labels.logLevelValues, labels.logLevelEntries, logLevelValue
                ),
                chooseHint = labels.chooseHint,
                onClick = { levelDialogOpen = true },
            )
        }

        item(key = DeveloperOptionsLogic.KEY_VIEW_LOG) {
            SettingsActionRow(
                title = labels.viewLogTitle,
                summary = labels.viewLogSummary,
                clickLabel = labels.actionHint,
                onClick = { logViewBody = onViewLog() },
            )
        }

        item(key = DeveloperOptionsLogic.KEY_SHARE_LOG) {
            SettingsActionRow(
                title = labels.shareLogTitle,
                summary = labels.shareLogSummary,
                clickLabel = labels.actionHint,
                onClick = onShareLog,
            )
        }

        item(key = DeveloperOptionsLogic.KEY_COPY_LOG) {
            SettingsActionRow(
                title = labels.copyLogTitle,
                summary = labels.copyLogSummary,
                clickLabel = labels.actionHint,
                onClick = onCopyLog,
            )
        }

        item(key = DeveloperOptionsLogic.KEY_CLEAR_LOG) {
            SettingsActionRow(
                title = labels.clearLogTitle,
                summary = labels.clearLogSummary,
                clickLabel = labels.actionHint,
                onClick = { clearConfirmOpen = true },
            )
        }

        settingsSectionHeader(labels.sectionOverlay)

        item(key = DeveloperOptionsLogic.KEY_OVERLAY) {
            SettingsSwitchRow(
                title = labels.overlayTitle,
                summary = labels.overlaySummary,
                checked = overlayEnabled,
                onLabel = labels.on,
                offLabel = labels.off,
                onCheckedChange = onOverlayChange,
            )
        }

        // 旧 XML 里这一行有 app:dependency="@string/pref_braille_overlay_key"：
        // 叠加层没开时教程不可用。禁用时还要把原因写成文字，
        // 不能只靠置灰让读屏用户猜。
        item(key = DeveloperOptionsLogic.KEY_OVERLAY_TUTORIAL) {
            SettingsActionRow(
                title = labels.tutorialTitle,
                summary = if (DeveloperOptionsLogic.tutorialEnabled(overlayEnabled)) {
                    labels.tutorialSummary
                } else {
                    labels.tutorialDisabled
                },
                clickLabel = labels.actionHint,
                enabled = DeveloperOptionsLogic.tutorialEnabled(overlayEnabled),
                onClick = onOpenTutorial,
            )
        }
    }

    if (levelDialogOpen) {
        SettingsChoiceDialog(
            title = labels.logLevelTitle,
            entries = labels.logLevelEntries,
            values = labels.logLevelValues,
            currentValue = logLevelValue,
            cancelLabel = labels.cancel,
            selectedLabel = labels.selected,
            unselectedLabel = labels.unselected,
            onSelect = { value ->
                levelDialogOpen = false
                onLogLevelSelected(value)
            },
            onDismiss = { levelDialogOpen = false },
        )
    }

    logViewBody?.let { body ->
        SettingsLogViewDialog(
            title = labels.logViewTitle,
            body = body,
            copyLabel = labels.logCopyLabel,
            shareLabel = labels.logShareLabel,
            closeLabel = labels.cancel,
            onCopy = {
                logViewBody = null
                onCopyLog()
            },
            onShare = {
                logViewBody = null
                onShareLog()
            },
            onDismiss = { logViewBody = null },
        )
    }

    if (clearConfirmOpen) {
        SettingsConfirmDialog(
            title = labels.clearLogTitle,
            message = labels.clearConfirmMessage,
            confirmLabel = labels.clearConfirmLabel,
            cancelLabel = labels.cancel,
            onConfirm = {
                clearConfirmOpen = false
                onClearLog()
            },
            onDismiss = { clearConfirmOpen = false },
        )
    }
}
