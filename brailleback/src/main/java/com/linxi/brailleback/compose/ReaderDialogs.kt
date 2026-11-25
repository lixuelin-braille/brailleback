/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 阅读器弹窗：目录 / 书签 / 删书签 / 跳页 / 跳到本页行 / 导出格式与版面 / 字号。
 * 纯展示层：标题、条目、回调全部由 BrailleReaderScreen 与 Activity 喂进来。
 */

package com.linxi.brailleback.compose

import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun ListDialog(
    title: String,
    labels: List<String>,
    cancelLabel: String,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    descriptions: List<String> = emptyList(),
    hint: String = "",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // 顶部建议：说清“想刻印或上点显器该选哪个”，不让人盲猜。
                if (hint.isNotEmpty()) {
                    Text(
                        text = hint,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                    )
                }
                labels.forEachIndexed { index, label ->
                    val description = descriptions.getOrNull(index).orEmpty()
                    // 整行合并成一个焦点：TalkBack 一次读出“格式名 + 用途”，
                    // 不拆成两个焦点，避免用户要滑两次才知道这条是干什么的。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics(mergeDescendants = true) {}
                            .combinedClickable(onClick = { onPick(index) })
                            .padding(vertical = 12.dp),
                    ) {
                        Text(text = label, style = MaterialTheme.typography.bodyLarge)
                        if (description.isNotEmpty()) {
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(cancelLabel) }
        },
    )
}

@Composable
internal fun BookmarksDialog(
    dialog: ReaderDialog.Bookmarks,
    labels: ReaderLabels,
    onPick: (Int) -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialog.title) },
        text = {
            Column {
                dialog.labels.forEachIndexed { index, label ->
                    Text(
                        text = label,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(onClick = { onPick(index) })
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDelete) { Text(labels.delete) }
                OutlinedButton(onClick = onClear) { Text(labels.clearAll) }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(labels.cancel) }
        },
    )
}

@Composable
internal fun CustomGeometryDialog(
    dialog: ReaderDialog.ExportCustomGeometry,
    labels: ReaderLabels,
    onConfirm: (columns: Int, lines: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var columns by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialog.title) },
        text = {
            Column {
                TextField(
                    value = columns,
                    onValueChange = { columns = it },
                    placeholder = { Text("20–40") },
                    singleLine = true,
                )
                TextField(
                    value = lines,
                    onValueChange = { lines = it },
                    placeholder = { Text("20–28") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val c = columns.toIntOrNull()
                val l = lines.toIntOrNull()
                if (c != null && l != null) onConfirm(c, l)
            }) { Text(labels.ok) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(labels.cancel) }
        },
    )
}

@Composable
internal fun GotoDialog(
    dialog: ReaderDialog.Goto,
    labels: ReaderLabels,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dialog.title) },
        text = {
            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(dialog.hint) },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { input.toIntOrNull()?.let(onConfirm) }) { Text(labels.ok) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(labels.cancel) }
        },
    )
}

/**
 * 纯数字输入弹窗（跳到本页第 N 行）。
 *
 * 与 [GotoDialog] 分开：那边文案与「总页数」绑在一起，这里要的是本页行号范围。
 */
@Composable
internal fun NumberInputDialog(
    title: String,
    hint: String,
    okLabel: String,
    cancelLabel: String,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            TextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(hint) },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { input.toIntOrNull()?.let(onConfirm) }) { Text(okLabel) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(cancelLabel) }
        },
    )
}

/**
 * 单一点击入口的按钮：点击前进/后退，长按逐行。
 *
 * 不用 OutlinedButton 再套 combinedClickable——那样一次点按会从两条路径各走一次，
 * 翻页变成一次跳两页（真机上就像「翻页不对劲」）。这里 enabled 也交给
 * combinedClickable，禁用时 TalkBack 与长按一起失效，语义与视觉一致。
 */
@Composable
private fun ReaderButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (enabled) MaterialTheme.colorScheme.outline
    else MaterialTheme.colorScheme.outlineVariant
    val textColor = if (enabled) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .combinedClickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = textColor, style = MaterialTheme.typography.labelLarge)
    }
}
