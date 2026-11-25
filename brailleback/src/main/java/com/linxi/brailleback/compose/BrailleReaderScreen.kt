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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.roundToInt

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
    /** 本页行进度文案（当前行, 总行数）。 */
    val pageLine: (Int, Int) -> String,
    /** 行进度条的双击动作文案（也是 TalkBack 的动作名）。 */
    val pageLineAction: String,
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
    /** 本页行进度：当前第几行（0 基）、本页共几行，供进度条与「跳到第 N 行」使用。 */
    val lineIndex: Int = 0,
    val lineCount: Int = 1,
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
    /** 跳到本页第 N 行：标题 + 可输入范围提示。 */
    data class PageLine(val title: String, val hint: String) : ReaderDialog
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
    /** 拖动行进度条：按行号（0 基）定位。 */
    onLineSeek: (Int) -> Unit = {},
    /** 点行号文字：弹数字输入「跳到本页第 N 行」。 */
    onLineEdit: () -> Unit = {},
    /** 数字输入确认（行号从 1 开始）。 */
    onLineConfirm: (Int) -> Unit = {},
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
        //
        // 行高必须用**多行文本的平均行高**，不能用单字的 height：Text 的实际行距
        // 由 style 的 lineHeight 决定，比单字高出一截。按单字算出来的行数会偏多，
        // 结果页面尾部几行被容器裁掉——滚屏时最明显（段边界那几行看不全）。
        LaunchedEffect(textArea, uiState.fontSizeSp) {
            if (textArea.width <= 0 || textArea.height <= 0) return@LaunchedEffect
            val charW = measurer.measure("汉", probeStyle).size.width
            val lineH = measurer.measure("汉\n汉\n汉", probeStyle).size.height / 3
            if (charW <= 0 || lineH <= 0) return@LaunchedEffect
            // 再留 2 行的余量：多段 Text 每段都有首末行 font padding，
            // 段数一多就攒出小半行，铺满时最后一行会被容器切掉半截。
            val usable = textArea.height - lineH * 2
            onTextMetrics(
                (textArea.width / charW).coerceIn(8, 400),
                (usable / lineH).coerceIn(2, 80),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { textArea = it },
            // 段与段之间不再额外留白：屏幕正文严格按「行高 × 行数」铺，
            // 行数与页表的 mLinesPerPage 口径一致，滚屏到段边界时最后一行不会被裁掉。
            // 段落区分靠盲文自身的段首缩进与段间换行。
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

        // 本页行进度条：自绘的一个部件，**只占一个无障碍节点**。
        //
        // 这一段前后踩过两个坑：把行信息文字与 Slider 并排，是三个焦点（页码、行号、滑块）；
        // 用 mergeDescendants 把整块并起来，TalkBack 仍然读到「滑块」两次——
        // 因为 M3 Slider 自带 RangeInfo 语义，它和外面声明的描术会各自成节点。
        // 所以这里不用 Slider：自己画一条滑轨，语义只声明一次
        // （contentDescription = 第几行 / 共几行，progressBarRangeInfo/setProgress = 上下滑一格一行，
        // onClick = 双击弹数字输入），绘制走 Canvas，不产生任何子节点。
        // 页码只画在屏上给眼睛看，不进朗读内容（用户要求滑块里不要页码）。
        if (uiState.hasContent) {
            val lineText = labels.pageLine(uiState.lineIndex + 1, uiState.lineCount)
            LineScrubber(
                description = lineText,
                pageInfo = uiState.pageInfo,
                lineIndex = uiState.lineIndex,
                lineCount = uiState.lineCount,
                actionLabel = labels.pageLineAction,
                fontSizeSp = uiState.fontSizeSp,
                onSeek = onLineSeek,
                onEdit = onLineEdit,
            )
        } else if (uiState.pageInfo.isNotEmpty()) {
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
            is ReaderDialog.PageLine -> NumberInputDialog(
                title = current.title,
                hint = current.hint,
                okLabel = labels.ok,
                cancelLabel = labels.cancel,
                onConfirm = onLineConfirm,
                onDismiss = onDismissDialog)
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
 * 本页行进度条：一个部件、一个无障碍节点。
 *
 * 为什么不用 M3 `Slider`：它自带 `progressBarRangeInfo` 语义，与我们在外层声明的
 * 描述会各自变成一个 AccessibilityNode，读屏就停在「滑块」和「第几行」两处。
 * 这里自己画：Canvas 只负责像素，语义整块声明在同一个 Box 上，子节点为零。
 *
 * - 读屏：只念「第几行 / 共几行」；上下滑一格走一行（RangeInfo + setProgress）；
 * - 读屏：这一块是「滑块」语义，调它时会先念百分比、再念「第几行 / 共几行」；
 *   双击走 onClick，弹「跳到本页第 N 行」的数字输入。
 * - 版式按用户要求：行数在左，**滑块在行数后面**（右侧），像普通滑块那样摆着。
 * - 页码只画在屏上（顶部小字），**不进朗读内容**：用户要求滑块里不要页码。
 * - 触摸：按在滑轨上就跳到对应行，横向拖动则一行一行地跟手。
 */
@Composable
private fun LineScrubber(
    description: String,
    pageInfo: String,
    lineIndex: Int,
    lineCount: Int,
    actionLabel: String,
    fontSizeSp: Int,
    onSeek: (Int) -> Unit,
    onEdit: () -> Unit,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val lineStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = fontSizeSp.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val text = measurer.measure(AnnotatedString(description), lineStyle)
    // 页码：只供眼睛看的小字，比正文小一号、暗一档。
    val pageText = if (pageInfo.isEmpty()) null else measurer.measure(
        AnnotatedString(pageInfo),
        lineStyle.copy(
            fontSize = (fontSizeSp - 4).sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
    val pageHeightDp = pageText?.let { with(density) { it.size.height.toDp() } } ?: 0.dp
    // 行数与滑轨同一行、滑轨在行数右侧；这一行的高度取「文字高度」与「滑轨高度」的较大者。
    val textHeightDp = with(density) { text.size.height.toDp() }
    val rowHeight = maxOf(textHeightDp, 26.dp)
    val barHeight = pageHeightDp + rowHeight + 4.dp
    val maxIndex = (lineCount - 1).coerceAtLeast(1)
    val thumbColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    // drawText 不会沿用 layout 里 style 的颜色，必须显式传，否则默认画成黑色
    // （在深色背景上就等于看不见）。
    val lineColor = MaterialTheme.colorScheme.onSurface
    val pageColor = MaterialTheme.colorScheme.onSurfaceVariant
    // 滑轨几何先算成像素：Canvas 与 pointerInput 用同一套，免得触摸和画的位置对不上。
    val radiusPx = with(density) { 6.dp.toPx() }
    val gapPx = with(density) { 14.dp.toPx() }
    val textWidthPx = text.size.width.toFloat()
    val trackStartPx = textWidthPx + gapPx + radiusPx

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight)
            // 触摸：按下即跳到该行，拖动时一行一行跟手。
            // 只有一行时不必响应（拖不出任何东西）。
            .pointerInput(lineCount, maxIndex, trackStartPx) {
                if (lineCount < 2) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val span = (size.width - trackStartPx).coerceAtLeast(1f)
                    fun seekAt(x: Float) {
                        val fraction = ((x - trackStartPx) / span).coerceIn(0f, 1f)
                        onSeek((fraction * maxIndex).roundToInt())
                    }
                    seekAt(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        seekAt(change.position.x)
                        change.consume()
                    }
                }
            }
            .semantics {
                contentDescription = description
                // 恢复「滑块」语义（用户要求）：读屏调它时会先念百分比、再念
                // 「第 N 行 / 共 M 行」。stateDescription 保证行号那句用的是我们的文案，
                // 不会被换成别的说法。
                stateDescription = description
                if (lineCount >= 2) {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        lineIndex.toFloat(),
                        0f..maxIndex.toFloat(),
                        (lineCount - 2).coerceAtLeast(0),
                    )
                    setProgress { target ->
                        onSeek(target.roundToInt().coerceIn(0, maxIndex))
                        true
                    }
                }
                onClick(label = actionLabel) {
                    onEdit()
                    true
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pageHeightPx = with(density) { pageHeightDp.toPx() }
            pageText?.let { drawText(it, color = pageColor, topLeft = Offset(0f, 0f)) }
            val rowTop = pageHeightPx
            val rowHeightPx = rowHeight.toPx()
            // 行数在左、垂直居中；滑轨接在它后面（右侧），同一行里摆开。
            val textTop = rowTop + (rowHeightPx - text.size.height) / 2
            drawText(text, color = lineColor, topLeft = Offset(0f, textTop))
            val cy = rowTop + rowHeightPx / 2
            if (lineCount >= 2 && trackStartPx + radiusPx < size.width) {
                drawLine(
                    color = trackColor,
                    start = Offset(trackStartPx, cy),
                    end = Offset(size.width - radiusPx, cy),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                val span = (size.width - trackStartPx - radiusPx).coerceAtLeast(1f)
                val fraction = lineIndex.toFloat() / maxIndex
                drawCircle(
                    color = thumbColor,
                    radius = radiusPx,
                    center = Offset(trackStartPx + span * fraction, cy),
                )
            }
        }
    }
}

/**
 * 纯数字输入弹窗（跳到本页第 N 行）。
 *
 * 与 [GotoDialog] 分开：那边文案与「总页数」绑在一起，这里要的是本页行号范围。
 */
@Composable
private fun NumberInputDialog(
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
