/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 阅读器 Compose 界面。状态与动作全部由 BrailleReaderActivity 持有，本文件只渲染：
 * 文本区 → 页码行 → 第一行四键（打开/上一页/下一页/发送点显）→
 * 第二行三键 + 导出（目录/书签/跳页/导出为盲文）→ 底部提示行。
 *
 * 焦点顺序即布局顺序；整行合并语义，不拆成多个可聚焦节点。
 */

package com.linxi.brailleback.compose

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 阅读器界面静态文案（取自资源，Screen 不碰 Context）。 */
internal data class ReaderLabels(
    val open: String,
    val prev: String,
    val next: String,
    val send: String,
    val toc: String,
    val bookmark: String,
    val goto: String,
    val export: String,
    val fontSize: String,
    val navHint: String,
    val ok: String,
    val cancel: String,
    val delete: String,
    val clearAll: String,
)

/** 阅读器界面状态：使能位与文本都由 Activity 的 updateUiState 汇总后喂进来。 */
internal data class ReaderUiState(
    val readerText: String = "",
    val focusSegments: List<String> = emptyList(),
    val fontSizeSp: Int = 18,
    val pageInfo: String = "",
    val prevEnabled: Boolean = false,
    val nextEnabled: Boolean = false,
    val sendEnabled: Boolean = false,
    val tocEnabled: Boolean = false,
    val bookmarkEnabled: Boolean = false,
    val gotoEnabled: Boolean = false,
    val exportEnabled: Boolean = false,
)

/** 阅读器弹窗：目录 / 书签 / 删书签 / 跳页 / 导出格式。 */
internal sealed interface ReaderDialog {
    data class Toc(val title: String, val labels: List<String>, val targets: List<Int>) : ReaderDialog
    data class Bookmarks(
        val title: String,
        val labels: List<String>,
        val offsets: List<Int>,
    ) : ReaderDialog
    data class DeleteBookmark(
        val title: String,
        val labels: List<String>,
        val offsets: List<Int>,
    ) : ReaderDialog
    data class Goto(val title: String, val hint: String) : ReaderDialog
    data class ExportFormat(val title: String, val labels: List<String>) : ReaderDialog
    data class ExportGeometry(val title: String, val labels: List<String>) : ReaderDialog
    data class ExportCustomGeometry(val title: String) : ReaderDialog
    data class FontSize(val title: String, val labels: List<String>) : ReaderDialog
}

@Composable
internal fun BrailleReaderScreen(
    uiState: ReaderUiState,
    labels: ReaderLabels,
    dialog: ReaderDialog?,
    onOpen: () -> Unit,
    onPrev: () -> Unit,
    onPrevLine: () -> Unit,
    onNext: () -> Unit,
    onNextLine: () -> Unit,
    onSend: () -> Unit,
    onToc: () -> Unit,
    onBookmark: () -> Unit,
    onGoto: () -> Unit,
    onExport: () -> Unit,
    onFont: () -> Unit,
    onFontSizePick: (Int) -> Unit,
    onTocPick: (Int) -> Unit,
    onBookmarkPick: (Int) -> Unit,
    onBookmarkDelete: () -> Unit,
    onBookmarkClear: () -> Unit,
    onDeletePick: (Int) -> Unit,
    onGotoConfirm: (Int) -> Unit,
    onExportPick: (Int) -> Unit,
    onGeometryPick: (Int) -> Unit,
    onCustomGeometryConfirm: (Int, Int) -> Unit,
    onDismissDialog: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 文本区：按行/段拆成多个可聚焦节点，不整页合并为一个焦点。
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val segments = uiState.focusSegments.ifEmpty { listOf(uiState.readerText) }
            segments.forEach { segment ->
                Text(
                    text = segment,
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = uiState.fontSizeSp.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 页码行
        if (uiState.pageInfo.isNotEmpty()) {
            Text(
                text = uiState.pageInfo,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // 第一行：打开 / 上一页 / 下一页 / 发送点显
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                Text(labels.open)
            }
            OutlinedButton(
                onClick = onPrev,
                enabled = uiState.prevEnabled,
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(onClick = onPrev, onLongClick = onPrevLine),
            ) {
                Text(labels.prev)
            }
            OutlinedButton(
                onClick = onNext,
                enabled = uiState.nextEnabled,
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(onClick = onNext, onLongClick = onNextLine),
            ) {
                Text(labels.next)
            }
            OutlinedButton(
                onClick = onSend,
                enabled = uiState.sendEnabled,
                modifier = Modifier.weight(1f),
            ) {
                Text(labels.send)
            }
        }

        // 第二行：目录 / 书签 / 跳页 / 导出为盲文
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onToc,
                enabled = uiState.tocEnabled,
                modifier = Modifier.weight(1f),
            ) {
                Text(labels.toc)
            }
            OutlinedButton(
                onClick = onBookmark,
                enabled = uiState.bookmarkEnabled,
                modifier = Modifier.weight(1f),
            ) {
                Text(labels.bookmark)
            }
            OutlinedButton(
                onClick = onGoto,
                enabled = uiState.gotoEnabled,
                modifier = Modifier.weight(1f),
            ) {
                Text(labels.goto)
            }
            OutlinedButton(
                onClick = onFont,
                modifier = Modifier.weight(1f),
            ) {
                Text(labels.fontSize)
            }
            Button(
                onClick = onExport,
                enabled = uiState.exportEnabled,
                modifier = Modifier.weight(1f),
            ) {
                Text(labels.export)
            }
        }

        // 底部提示行（最后焦点）
        Text(
            text = labels.navHint,
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    dialog?.let { current ->
        when (current) {
            is ReaderDialog.Toc -> ListDialog(
                current.title, current.labels, labels.cancel,
                onPick = onTocPick, onDismiss = onDismissDialog)
            is ReaderDialog.Bookmarks -> BookmarksDialog(
                current, labels, onBookmarkPick, onBookmarkDelete, onBookmarkClear, onDismissDialog)
            is ReaderDialog.DeleteBookmark -> ListDialog(
                current.title, current.labels, labels.cancel,
                onPick = onDeletePick, onDismiss = onDismissDialog)
            is ReaderDialog.Goto -> GotoDialog(
                current, labels, onGotoConfirm, onDismissDialog)
            is ReaderDialog.ExportFormat -> ListDialog(
                current.title, current.labels, labels.cancel,
                onPick = onExportPick, onDismiss = onDismissDialog)
            is ReaderDialog.ExportGeometry -> ListDialog(
                current.title, current.labels, labels.cancel,
                onPick = onGeometryPick, onDismiss = onDismissDialog)
            is ReaderDialog.ExportCustomGeometry -> CustomGeometryDialog(
                current, labels, onCustomGeometryConfirm, onDismissDialog)
            is ReaderDialog.FontSize -> ListDialog(
                current.title, current.labels, labels.cancel,
                onPick = onFontSizePick, onDismiss = onDismissDialog)
        }
    }
}

@Composable
private fun ListDialog(
    title: String,
    labels: List<String>,
    cancelLabel: String,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                labels.forEachIndexed { index, label ->
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
        confirmButton = {},
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(cancelLabel) }
        },
    )
}

@Composable
private fun BookmarksDialog(
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
private fun CustomGeometryDialog(
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
private fun GotoDialog(
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
