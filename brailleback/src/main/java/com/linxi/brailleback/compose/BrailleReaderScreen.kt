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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 阅读器界面静态文案（取自资源，Screen 不碰 Context）。 */
internal data class ReaderLabels(
    val prev: String,
    val next: String,
    val toc: String,
    val bookmark: String,
    val goto: String,
    val export: String,
    /** 「翻译」菜单入口：重新翻译 / 停止翻译 / 清除缓存。 */
    val translate: String,
    /** 「更多」菜单入口：翻译、字号、导出都收在里面。 */
    val more: String,
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

/** 阅读器界面状态：正文与按钮使能位由 Activity 的 updateUiState 汇总后喂进来。 */
internal data class ReaderUiState(
    val readerText: String = "",
    val focusSegments: List<ReaderSegment> = emptyList(),
    val fontSizeSp: Int = 18,
    /** 有没有可读内容。没有时不摆一排停用按钮，只留提示与字号。 */
    val hasContent: Boolean = false,
    val prevEnabled: Boolean = false,
    val nextEnabled: Boolean = false,
    val tocEnabled: Boolean = false,
    val bookmarkEnabled: Boolean = false,
    val gotoEnabled: Boolean = false,
    val exportEnabled: Boolean = false,
    /** 「翻译」按钮能不能点：只有明眼文字（需要翻译的那种）才有意义。 */
    val translateEnabled: Boolean = false,
    /** 「更多」按钮：有正文才摆出来。 */
    val moreEnabled: Boolean = false,
)

/**
 * 滚动相关状态：页号/行号/计数/回顶令牌。
 *
 * 与正文、按钮状态分开持有：滚屏时只推这一份，正文列表与按钮区不跟着整份重组。
 */
internal data class ReaderScrollState(
    val pageInfo: String = "",
    /** 本页行进度：当前第几行（0 基）、本页共几行，供进度条与「跳到第 N 行」使用。 */
    val lineIndex: Int = 0,
    val lineCount: Int = 1,
    /** 当前第几页（0 基）与全书共几页：滑块按「第 N 页 共 M 页」朗读。 */
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
    /** 翻译菜单：标题、条目、每条的说明、以及每条对应的动作。 */
    data class Translate(
        val title: String,
        val labels: List<String>,
        val descriptions: List<String>,
        val actions: List<TranslateAction>,
    ) : ReaderDialog
    /** 「更多」菜单：标题、条目、说明与对应动作。 */
    data class More(
        val title: String,
        val labels: List<String>,
        val descriptions: List<String>,
        val actions: List<MoreAction>,
    ) : ReaderDialog
}

/** 翻译菜单里的动作。条目按当前状态动态生成，序号与这里一一对应。 */
internal enum class TranslateAction { RETRY, STOP, CLEAR_CACHE }

/** 「更多」菜单里的动作：翻译、字号、导出。 */
internal enum class MoreAction { TRANSLATE, FONT, EXPORT }

@Composable
internal fun BrailleReaderScreen(
    uiState: ReaderUiState,
    scrollState: ReaderScrollState,
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
    onTranslate: () -> Unit = {},
    onTranslatePick: (Int) -> Unit = {},
    onMore: () -> Unit = {},
    onMorePick: (Int) -> Unit = {},
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
        ReaderBodyList(
            modifier = Modifier.weight(1f),
            uiState = uiState,
            scrollState = scrollState,
            onTextMetrics = onTextMetrics,
            onTopSegment = onTopSegment,
            onNeedMoreSegments = onNeedMoreSegments,
        )

        // 两个滑块：上面管页（整本书跨页），下面管本页的行（页内逐行切换）。
        //
        // 每一块都是自绘的部件、各占一个无障碍节点。为什么不用 M3 Slider：
        // 它自带 RangeInfo 语义，与外面声明的描述会各自变成一个节点，读屏念两遍。
        // 所以自己画滑轨，语义各声明一次，子节点为零。
        // 段号小字只给眼睛看，不进朗读内容。
        if (uiState.hasContent) {
            // 段号小字：只给眼睛看，不进朗读（invisibleToUser 让它不产生可聚焦节点）。
            if (scrollState.pageInfo.isNotEmpty()) {
                Text(
                    text = scrollState.pageInfo,
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
                    description = labels.pageScrubber(scrollState.pageIndex + 1, scrollState.pageCount),
                    index = scrollState.pageIndex,
                    count = scrollState.pageCount,
                    actionLabel = labels.pageScrubberAction,
                    fontSizeSp = uiState.fontSizeSp,
                    onSeek = onPageSeek,
                    onEdit = onPageEdit,
                )
                ScrubberBar(
                    description = labels.pageLine(scrollState.lineIndex + 1, scrollState.lineCount),
                    index = scrollState.lineIndex,
                    count = scrollState.lineCount,
                    actionLabel = labels.pageLineAction,
                    fontSizeSp = uiState.fontSizeSp,
                    onSeek = onLineSeek,
                    onEdit = onLineEdit,
                    overflowEnabled = true,
                    onOverflow = onLineOverflow,
                )
            }
        } else if (scrollState.pageInfo.isNotEmpty()) {
            Text(
                text = scrollState.pageInfo,
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
                // 翻译、字号、导出都收进「更多」：正文上方只留三个入口，
                // 免得一排按钮把正文挤没，读屏滑过按钮区也要多走好几步。
                OutlinedButton(
                    onClick = onMore,
                    enabled = uiState.moreEnabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(labels.more)
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
            is ReaderDialog.Translate -> ListDialog(
                current.title, current.labels, labels.cancel,
                onPick = onTranslatePick, onDismiss = onDismissDialog,
                descriptions = current.descriptions)
            is ReaderDialog.More -> ListDialog(
                current.title, current.labels, labels.cancel,
                onPick = onMorePick, onDismiss = onDismissDialog,
                descriptions = current.descriptions)
        }
    }
}
