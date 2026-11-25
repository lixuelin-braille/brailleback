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

import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

/** 阅读器界面静态文案（取自资源，Screen 不碰 Context）。 */
internal data class ReaderLabels(
    val prev: String,
    val next: String,
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
    /** 有没有可读内容。没有时不摆一排停用按钮，只留提示与字号。 */
    val hasContent: Boolean = false,
    val prevEnabled: Boolean = false,
    val nextEnabled: Boolean = false,
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
    /** 导出格式：标题、每种格式一句用途说明、以及弹窗顶部的一句选择建议。 */
    data class ExportFormat(
        val title: String,
        val labels: List<String>,
        val descriptions: List<String>,
        val hint: String,
    ) : ReaderDialog
    /** 导出排版：标题、每档说明、以及弹窗顶部一句「有没有读到点显器方数」。 */
    data class ExportGeometry(
        val title: String,
        val labels: List<String>,
        val hint: String = "",
    ) : ReaderDialog
    data class ExportCustomGeometry(val title: String) : ReaderDialog
    data class FontSize(val title: String, val labels: List<String>) : ReaderDialog
}

@Composable
internal fun BrailleReaderScreen(
    uiState: ReaderUiState,
    labels: ReaderLabels,
    dialog: ReaderDialog?,
    onPrev: () -> Unit,
    onPrevLine: () -> Unit,
    onNext: () -> Unit,
    onNextLine: () -> Unit,
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
    /** 实测正文区一行能排几个等宽字符、能排几行（换字号/横竖屏都要重报）。 */
    onTextMetrics: (Int, Int) -> Unit = { _, _ -> },
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 文本区：按行/段拆成多个可聚焦节点，不整页合并为一个焦点。
        var textArea by remember { mutableStateOf(IntSize.Zero) }
        val measurer = rememberTextMeasurer()
        val probeStyle = MaterialTheme.typography.bodyLarge.copy(
            fontSize = uiState.fontSizeSp.sp)
        // 「一页 = 屏幕上真正能排下的方数 × 行数」：字号、屏幕宽度、横竖屏都会变，
        // 所以每次排版稳定后重测一次，交给 Activity 重建页表（保留阅读位置）。
        LaunchedEffect(textArea, uiState.fontSizeSp) {
            if (textArea.width <= 0 || textArea.height <= 0) return@LaunchedEffect
            val charW = measurer.measure("汉", probeStyle).size.width
            val lineH = measurer.measure("汉", probeStyle).size.height
            if (charW <= 0 || lineH <= 0) return@LaunchedEffect
            onTextMetrics(
                (textArea.width / charW).coerceIn(8, 400),
                (textArea.height / lineH).coerceIn(2, 80),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { textArea = it },
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

        // 第一行：上一页 / 下一页（「发送点显」已按用户要求去掉：
        // 连上点显器后翻页与进页会自动推屏，不需要一个常年停用的按钮）
        if (uiState.hasContent) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 一个按键只挂一处点击：早先是 OutlinedButton(onClick) 外面又套
                // combinedClickable，双击 TalkBack 时两条路径都触发，一次点按走两页；
                // 这里直接用一个 combinedClickable 的盒子承担点击与长按。
                ReaderButton(
                    label = labels.prev,
                    enabled = uiState.prevEnabled,
                    onClick = onPrev,
                    onLongClick = onPrevLine,
                    modifier = Modifier.weight(1f),
                )
                ReaderButton(
                    label = labels.next,
                    enabled = uiState.nextEnabled,
                    onClick = onNext,
                    onLongClick = onNextLine,
                    modifier = Modifier.weight(1f),
                )
            }

            // 第二行：目录 / 书签 / 跳页
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
            }

            // 第三行：字号 / 导出为盲文
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                onPick = onExportPick, onDismiss = onDismissDialog,
                descriptions = current.descriptions, hint = current.hint)
            is ReaderDialog.ExportGeometry -> ListDialog(
                current.title, current.labels, labels.cancel,
                onPick = onGeometryPick, onDismiss = onDismissDialog,
                hint = current.hint)
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
