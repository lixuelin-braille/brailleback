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

import com.linxi.brailleback.ReaderSegment
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.semantics.invisibleToUser
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
import kotlinx.coroutines.flow.distinctUntilChanged

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
    /** 行进度文案（当前行, 总行数）：滑块改成页码后，只留给「跳到本页第 N 行」这类入口用。 */
    val pageLine: (Int, Int) -> String,
    /** 页码滑块文案（当前页, 总页数），照系统桌面翻页提示：「第 N 页 共 M 页」。 */
    val pageScrubber: (Int, Int) -> String,
    /** 页码滑块的双击动作文案（也是 TalkBack 的动作名）。 */
    val pageScrubberAction: String,
    /** 行进度条的双击动作文案（也是 TalkBack 的动作名）。 */
    val pageLineAction: String,
    /** 正文列表末尾那一行的翻页文案（「下一页」）。 */
    val nextPage: String = "下一页",
)

/** 阅读器界面状态：使能位与文本都由 Activity 的 updateUiState 汇总后喂进来。 */
internal data class ReaderUiState(
    val readerText: String = "",
    val focusSegments: List<ReaderSegment> = emptyList(),
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
    /** 当前第几页（0 基）与全书共几页：滑块按「第 N 页 共 M 页」朗读，照系统桌面翻页提示的样子。 */
    val pageIndex: Int = 0,
    val pageCount: Int = 1,
    /**
     * 显式换页/跳行的计数器：一变就让正文列表回到顶部。
     *
     * 滚屏时页号是跟着滚动走的，列表位置不能被动；只有用户自己按滑块/跳页/换字号
     * 明确换了位置，才需要回顶。
     */
    val scrollToken: Int = 0,
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
    /** 拖动页码滑块：按页号（0 基）定位。 */
    onPageSeek: (Int) -> Unit = {},
    /** 点页码文字：弹数字输入「跳到第几页」。 */
    onPageEdit: () -> Unit = {},
    /** 拖动行滑块：按本页行号（0 基）定位。 */
    onLineSeek: (Int) -> Unit = {},
    /** 点行文字：弹数字输入「跳到本页第 N 行」。 */
    onLineEdit: () -> Unit = {},
    /** 行滑块到头还继续往前：+1 下一页、-1 上一页（滚屏不在页末卡住）。 */
    onLineOverflow: (Int) -> Unit = {},
    /** 正文列表滚到某一项成为「首可见项」：Activity 据此更新页号（滚屏时的分页）。 */
    onTopSegment: (Int) -> Unit = {},
    /** 正文列表快滚到已加载的末尾了：请求再补一页的段落，焦点才不会断。 */
    onNeedMoreSegments: () -> Unit = {},
    /** 数字输入确认（行号从 1 开始）。 */
    onLineConfirm: (Int) -> Unit = {},
    /** 实测正文区一行能排几个等宽字符、能排几行（换字号/横竖屏都要重报）。 */
    onTextMetrics: (Int, Int) -> Unit = { _, _ -> },
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            // 底部提示行会被系统导航栏压住，按导航栏高度让开。
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
        // 正文改成一条可滚动的焦点流：一段一项、一项一个焦点，列表一直往后续下去。
        //
        // 为什么用 LazyColumn（Compose 里的 RecyclerView）而不是 Column：段数一多只有可见项
        // 参与组合与测量，翻看长书不会一次铺出上百个节点。
        //
        // 为什么要把后面几页的段也放进来：读屏焦点滑到页末最后一段时，必须有「下一个节点」
        // 才肯继续动。窗口里接着后面页的段，焦点就能一路滑下去，滚过一页页码自己往前走，
        // 不需要额外的「下一页」节点，也不需要双击。
        val listState = rememberLazyListState()
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { textArea = it },
        ) {
            val segments = uiState.focusSegments
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (segments.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = uiState.readerText,
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = uiState.fontSizeSp.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    // key 用下标：窗口是「往后续」的，已有项的下标不会变，
                    // 续装或整窗重铺时列表都能把滚动位置按项对齐。
                    itemsIndexed(segments, key = { index, _ -> index }) { _, segment ->
                        Text(
                            text = segment.text,
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = uiState.fontSizeSp.sp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // 滚屏时的分页：首可见项落在哪一页，页号就跟着走。
        // 读屏自己滚列表（TalkBack 把焦点推到屏外节点时会带容器一起滚），
        // 这里只看滚动结果，不主动改列表位置，免得跟手指抢。
        val segmentCount = uiState.focusSegments.size
        LaunchedEffect(segmentCount) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { index ->
                    if (uiState.focusSegments.isEmpty()) return@collect
                    onTopSegment(index)
                    if (index >= (listState.layoutInfo.totalItemsCount - 4)) {
                        onNeedMoreSegments()
                    }
                }
        }
        // 显式换页/跳行/换字号：列表回到顶部（滚屏自己引起的页号变化不带这个 token）。
        LaunchedEffect(uiState.scrollToken) {
            listState.scrollToItem(0)
        }

        // 两个滑块：上面管页（整本书跨页），下面管本页的行（页内逐行切换）。
        //
        // 每一块都是自绘的部件、各占一个无障碍节点。为什么不用 M3 Slider：
        // 它自带 RangeInfo 语义，与外面声明的描述会各自变成一个节点，读屏念两遍。
        // 所以自己画滑轨，语义各声明一次，子节点为零。
        // 段号小字只给眼睛看，不进朗读内容。
        if (uiState.hasContent) {
            // 段号小字：只给眼睛看，不进朗读（invisibleToUser 让它不产生可聚焦节点）。
            if (uiState.pageInfo.isNotEmpty()) {
                Text(
                    text = uiState.pageInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { invisibleToUser() },
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScrubberBar(
                    description = labels.pageScrubber(uiState.pageIndex + 1, uiState.pageCount),
                    index = uiState.pageIndex,
                    count = uiState.pageCount,
                    actionLabel = labels.pageScrubberAction,
                    fontSizeSp = uiState.fontSizeSp,
                    onSeek = onPageSeek,
                    onEdit = onPageEdit,
                )
                ScrubberBar(
                    description = labels.pageLine(uiState.lineIndex + 1, uiState.lineCount),
                    index = uiState.lineIndex,
                    count = uiState.lineCount,
                    actionLabel = labels.pageLineAction,
                    fontSizeSp = uiState.fontSizeSp,
                    onSeek = onLineSeek,
                    onEdit = onLineEdit,
                    overflowEnabled = true,
                    onOverflow = onLineOverflow,
                )
            }
        } else if (uiState.pageInfo.isNotEmpty()) {
            Text(
                text = uiState.pageInfo,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // 第一行：目录 / 书签。
        // 翻页与逐行都改成滑块了，「上一页 / 下一页」按钮不再需要；
        // 「跳页」按钮也去掉了：页码滑块双击就是「跳到第几页」。
        if (uiState.hasContent) {
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

        // 底部提示行（最后焦点）。加了第二条滑块之后纵向吃紧，这里用最小字号，
        // 免得被系统导航栏压住。
        Text(
            text = labels.navHint,
            style = MaterialTheme.typography.bodySmall,
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

