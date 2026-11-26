/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.linxi.brailleback.compose

import androidx.compose.runtime.Composable

/** 一次性收集好的中文文案，让 Screen 保持无 Context 依赖。 */
internal data class DiagnosticsLabels(
    val title: String,
    val back: String,
    val section: String,
    val stateTitle: String,
    val routeTitle: String,
    val routeUsb: String,
    val routeBluetooth: String,
    val routeNone: String,
    val usbCountTitle: String,
    val usbNoneHint: String,
    val deviceTitle: String,
    val permissionTitle: String,
    val permissionGranted: String,
    val permissionDenied: String,
    val openTitle: String,
    val openActive: String,
    val openInactive: String,
    val failureTitle: String,
    val failureNone: String,
    val a11yTitle: String,
    val a11yEnabled: String,
    val a11yDisabled: String,
    val freezeHintTitle: String,
    val freezeHint: String,
    val refreshTitle: String,
    val refreshHint: String,
    val retryTitle: String,
    val retryHint: String,
)

/**
 * 「连接诊断」子屏。
 *
 * <p>每个字段都是独立可聚焦的文本节点（[SettingsInfoRow] / [SettingsActionRow]），
 * TalkBack 逐行朗读；取值全部来自真实 API，不写死占位文本。进入页面与回到前台
 * 时由 Activity 重新采集，页面上也提供「刷新」动作。
 */
@Composable
internal fun ConnectionDiagnosticsScreen(
    labels: DiagnosticsLabels,
    stateText: String,
    routeLabel: String,
    devices: List<UsbDeviceDiagnostic>,
    failureReason: String?,
    a11yEnabled: Boolean,
    onRefresh: () -> Unit,
    onRetryConnect: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsScreenScaffold(
        title = labels.title,
        backLabel = labels.back,
        onBack = onBack,
    ) {
        settingsSectionHeader(labels.section)

        item {
            SettingsInfoRow(labels.stateTitle, stateText)
        }
        item {
            SettingsInfoRow(labels.routeTitle, routeLabel)
        }
        item {
            SettingsInfoRow(
                labels.usbCountTitle,
                labels.usbCountSummary(devices.size),
            )
        }
        if (devices.isEmpty()) {
            item {
                SettingsInfoRow(labels.usbCountTitle, labels.usbNoneHint)
            }
        }
        devices.forEachIndexed { index, device ->
            item(key = "usb-${index}-device") {
                SettingsInfoRow(
                    labels.deviceTitle,
                    "${ConnectionDiagnosticsLogic.formatHex(device.vid)}：" +
                        ConnectionDiagnosticsLogic.formatHex(device.pid),
                )
            }
            item(key = "usb-${index}-permission") {
                SettingsInfoRow(
                    labels.permissionTitle,
                    if (device.hasPermission) labels.permissionGranted
                    else labels.permissionDenied,
                )
            }
            item(key = "usb-${index}-open") {
                SettingsInfoRow(
                    labels.openTitle,
                    if (device.active) labels.openActive
                    else labels.openInactive,
                )
            }
        }
        item {
            SettingsInfoRow(
                labels.failureTitle,
                failureReason ?: labels.failureNone,
            )
        }
        item {
            SettingsInfoRow(
                labels.a11yTitle,
                if (a11yEnabled) labels.a11yEnabled else labels.a11yDisabled,
            )
        }
        item {
            SettingsInfoRow(labels.freezeHintTitle, labels.freezeHint)
        }
        item {
            SettingsActionRow(
                title = labels.refreshTitle,
                summary = labels.refreshHint,
                clickLabel = labels.refreshTitle,
                onClick = onRefresh,
            )
        }
        item {
            SettingsActionRow(
                title = labels.retryTitle,
                summary = labels.retryHint,
                clickLabel = labels.retryTitle,
                onClick = onRetryConnect,
            )
        }
    }
}

/** USB 设备数摘要：0 台与多台都给可读短句。 */
private fun DiagnosticsLabels.usbCountSummary(count: Int): String = "$count 台"
