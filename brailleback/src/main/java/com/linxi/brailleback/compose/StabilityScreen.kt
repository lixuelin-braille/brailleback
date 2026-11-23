/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linxi.brailleback.OemVendor
import com.linxi.brailleback.StabilitySnapshot

/** 一次性收好的文案，Screen 不碰 Context。 */
internal data class StabilityLabels(
    val title: String,
    val back: String,
    val sectionStatus: String,
    val sectionOem: String,
    val off: String,
    val on: String,
    val serviceTitle: String,
    val serviceRunning: String,
    val serviceNotEnabled: String,
    val serviceNotRunning: String,
    val batteryTitle: String,
    val batteryUnrestricted: String,
    val batteryOptimized: String,
    val openBattery: String,
    val backgroundTitle: String,
    val backgroundRestricted: String,
    val backgroundOk: String,
    val openAutostart: String,
    val notificationTitle: String,
    val granted: String,
    val denied: String,
    val requestPermission: String,
    val bluetoothTitle: String,
    val summaryAllOk: String,
    val summaryTitle: String,
    val vendorName: String,
    val vendorSteps: List<String>,
    val android17Note: String,
)

/**
 * 「稳定性与省电适配」子屏。
 *
 * 纯展示：所有系统查询都在 Activity 里做，这里只收 [snapshot] 与回调。
 */
@Composable
internal fun StabilityScreen(
    labels: StabilityLabels,
    snapshot: StabilitySnapshot,
    vendor: OemVendor,
    issueSummary: String,
    onOpenBatterySettings: () -> Unit,
    onOpenAutostart: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestBluetooth: () -> Unit,
    onBack: () -> Unit,
) {
    SettingsScreenScaffold(
        title = labels.title,
        backLabel = labels.back,
        onBack = onBack,
    ) {
        settingsSectionHeader(labels.sectionStatus)

        item(key = "stability:summary") {
            StabilityTextRow(title = labels.summaryTitle, body = issueSummary)
        }

        item(key = "stability:service") {
            StabilityTextRow(
                title = labels.serviceTitle,
                body = when {
                    snapshot.serviceRunning -> labels.serviceRunning
                    snapshot.serviceEnabled -> labels.serviceNotRunning
                    else -> labels.serviceNotEnabled
                },
            )
        }

        item(key = "stability:battery") {
            SettingsActionRow(
                title = labels.batteryTitle,
                summary = if (snapshot.batteryUnrestricted) {
                    labels.batteryUnrestricted
                } else {
                    labels.batteryOptimized
                },
                clickLabel = labels.openBattery,
                onClick = onOpenBatterySettings,
                enabled = !snapshot.batteryUnrestricted,
            )
        }

        item(key = "stability:background") {
            SettingsActionRow(
                title = labels.backgroundTitle,
                summary = if (snapshot.backgroundRestricted) {
                    labels.backgroundRestricted
                } else {
                    labels.backgroundOk
                },
                clickLabel = labels.openAutostart,
                onClick = onOpenAutostart,
            )
        }

        item(key = "stability:notification") {
            SettingsActionRow(
                title = labels.notificationTitle,
                summary = if (snapshot.notificationGranted) labels.granted else labels.denied,
                clickLabel = labels.requestPermission,
                onClick = onRequestNotification,
                enabled = !snapshot.notificationGranted,
            )
        }

        item(key = "stability:bluetooth") {
            SettingsActionRow(
                title = labels.bluetoothTitle,
                summary = if (snapshot.bluetoothGranted) labels.granted else labels.denied,
                clickLabel = labels.requestPermission,
                onClick = onRequestBluetooth,
                enabled = !snapshot.bluetoothGranted,
            )
        }

        settingsSectionHeader(labels.sectionOem)

        item(key = "stability:vendor-title") {
            StabilityTextRow(title = labels.vendorName, body = labels.android17Note)
        }

        labels.vendorSteps.forEachIndexed { index, step ->
            item(key = "stability:step:$index") {
                StabilityTextRow(title = "${index + 1}.", body = step)
            }
        }
    }
}

/** 只读信息行：没有点击动作，所以不给 Role.Button，避免读屏报「可点击」却点不动。 */
@Composable
private fun StabilityTextRow(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
