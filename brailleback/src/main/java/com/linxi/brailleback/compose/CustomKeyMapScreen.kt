/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 自定义键位页 Compose 界面。状态与落盘全部由 CustomKeyMapActivity 持有，
 * 本文件只渲染：说明段 → 分组标题 → 24 行动态按键 → 冲突提示 → 全部复位，
 * 以及单条改动作的单选对话框与复位确认框。
 *
 * 无障碍：每行整行可点 + mergeDescendants；单选项 Role.RadioButton +
 * stateDescription；冲突提示是普通文本节点，TalkBack 可直接读到，不只靠置灰。
 */

package com.linxi.brailleback.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 一行可自定义按键：标题/当前摘要/设备物理键名由 Activity 拼好传入。 */
internal data class KeyMapRow(
    val source: String,
    val title: String,
    val summary: String,
    val deviceKeyLine: String,
    val dialogTitle: String,
)

/** 静态文案（取自资源，Screen 不碰 Context）。 */
internal data class CustomKeyMapLabels(
    val title: String,
    val instructionsTitle: String,
    val instructions: String,
    val sectionTitle: String,
    val chooseHint: String,
    val back: String,
    val resetTitle: String,
    val resetSummary: String,
    val resetConfirmTitle: String,
    val resetConfirmMessage: String,
    val selected: String,
    val unselected: String,
    val ok: String,
    val cancel: String,
)

@Composable
internal fun CustomKeyMapScreen(
    labels: CustomKeyMapLabels,
    rows: List<KeyMapRow>,
    optionLabels: List<String>,
    optionValues: List<String>,
    currentValueOf: (String) -> String,
    conflictText: String?,
    onPickAction: (String, String) -> Unit,
    onResetAll: () -> Unit,
    onBack: () -> Unit,
) {
    var editingRow by remember { mutableStateOf<KeyMapRow?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    SettingsScreenScaffold(
        title = labels.title,
        backLabel = labels.back,
        onBack = onBack,
    ) {
        item(key = "instructions") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = labels.instructionsTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = labels.instructions,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        settingsSectionHeader(labels.sectionTitle)

        items(rows, key = { it.source }) { row ->
            KeyMapRow(
                row = row,
                chooseHint = labels.chooseHint,
                onClick = { editingRow = row },
            )
        }

        if (!conflictText.isNullOrEmpty()) {
            item(key = "conflict") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .semantics(mergeDescendants = true) {},
                ) {
                    Text(
                        text = conflictText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item(key = "reset") {
            SettingsActionRow(
                title = labels.resetTitle,
                summary = labels.resetSummary,
                clickLabel = labels.chooseHint,
                onClick = { confirmReset = true },
            )
        }
    }

    editingRow?.let { row ->
        SettingsChoiceDialog(
            title = row.dialogTitle,
            entries = optionLabels,
            values = optionValues,
            currentValue = currentValueOf(row.source),
            cancelLabel = labels.cancel,
            selectedLabel = labels.selected,
            unselectedLabel = labels.unselected,
            onSelect = { value ->
                editingRow = null
                onPickAction(row.source, value)
            },
            onDismiss = { editingRow = null },
        )
    }

    if (confirmReset) {
        SettingsConfirmDialog(
            title = labels.resetConfirmTitle,
            message = labels.resetConfirmMessage,
            confirmLabel = labels.ok,
            cancelLabel = labels.cancel,
            onConfirm = {
                confirmReset = false
                onResetAll()
            },
            onDismiss = { confirmReset = false },
        )
    }
}

@Composable
private fun KeyMapRow(
    row: KeyMapRow,
    chooseHint: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = chooseHint,
                onClick = onClick,
            )
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = row.title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = row.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = row.deviceKeyLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
