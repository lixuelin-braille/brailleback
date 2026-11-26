/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 阅读器正文焦点流：段落窗口 LazyColumn、排版实测回传、滚屏分页与续装请求。
 * 列表项 key 用下标，窗口往后续装时已有项下标不变。
 */

package com.linxi.brailleback.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun ReaderBodyList(
    modifier: Modifier,
    uiState: ReaderUiState,
    scrollState: ReaderScrollState,
    onTextMetrics: (Int, Int) -> Unit,
    onTopSegment: (Int) -> Unit,
    onNeedMoreSegments: () -> Unit,
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
            modifier = modifier
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
        LaunchedEffect(scrollState.scrollToken) {
            listState.scrollToItem(0)
        }
}
