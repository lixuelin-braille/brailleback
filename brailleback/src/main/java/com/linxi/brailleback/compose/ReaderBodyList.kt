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

/** 续装判定的快照三元组：首可见下标、末可见下标、列表总项数。 */
internal data class AppendScrollState(
    val firstIndex: Int,
    val lastIndex: Int,
    val total: Int,
)

/**
 * 末可见项距列表末尾 ≤ [trailingSlots] 项时，应续装下一页段落。
 *
 * 判据看**末可见项**而不是首可见项：首可见下标上限 = total − 可见项数，可见项多于预留槽位时
 * 旧判据（首项 ≥ total − 4）物理上不可达；末可见项距末尾的距离与视口能摆几项无关。
 */
internal fun shouldAppendMore(
    lastVisibleIndex: Int,
    totalItemsCount: Int,
    trailingSlots: Int = 2,
): Boolean {
    if (totalItemsCount <= 0 || lastVisibleIndex < 0) return false
    return lastVisibleIndex >= totalItemsCount - 1 - trailingSlots
}

/**
 * 续装判据 + 去重守卫。
 *
 * - 同一 [AppendScrollState.total] 只放行一次真正追加；追加后挂起待命，
 *   等观察到 total 真正变大（段落已落进 state）才允许下一次追加判定；
 * - 待命落定后仍贴在末尾，才再向 Activity 发一次「文末探测」，走溢出/到头播报分支，
 *   不重复追加。
 */
internal class AppendGate(
    private var lastAppendedTotal: Int = -1,
    private var pending: Boolean = false,
) {
    /** 贴到末尾预留槽即处于需要向 Activity 要更多的窗口。 */
    fun nearEnd(lastVisibleIndex: Int, totalItemsCount: Int): Boolean =
        shouldAppendMore(lastVisibleIndex, totalItemsCount)

    /** 本 total 是否允许真正追加：同一 total 只追加一次。 */
    fun canAppend(lastVisibleIndex: Int, totalItemsCount: Int): Boolean =
        nearEnd(lastVisibleIndex, totalItemsCount) &&
            (!pending || totalItemsCount != lastAppendedTotal)

    /** 记下本次追加前的 total，进入待命。 */
    fun noteAppended(totalItemsCount: Int) {
        lastAppendedTotal = totalItemsCount
        pending = true
    }

    /** 观察列表总项数：total 变大说明追加已落进 state，清掉待命标记。 */
    fun observeTotal(totalItemsCount: Int) {
        if (totalItemsCount != lastAppendedTotal) pending = false
    }

    /** 待命已落定（追加已变长，或本就没追加过），可做文末探测。 */
    fun isSettled(): Boolean = !pending

    /** 待命期间、仍停在已续过装的同一 total：允许再发文末探测（不重复追加）。 */
    fun canProbeEnd(totalItemsCount: Int): Boolean =
        pending && totalItemsCount == lastAppendedTotal
}

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
        val appendGate = remember { AppendGate() }
        LaunchedEffect(segmentCount) {
            snapshotFlow {
                val info = listState.layoutInfo
                val visible = info.visibleItemsInfo
                AppendScrollState(
                    firstIndex = visible.firstOrNull()?.index ?: -1,
                    lastIndex = visible.lastOrNull()?.index ?: -1,
                    total = info.totalItemsCount,
                )
            }.distinctUntilChanged()
                .collect { state ->
                    if (uiState.focusSegments.isEmpty()) return@collect
                    appendGate.observeTotal(state.total)
                    onTopSegment(state.firstIndex)
                    if (appendGate.nearEnd(state.lastIndex, state.total)) {
                        when {
                            appendGate.canAppend(state.lastIndex, state.total) -> {
                                appendGate.noteAppended(state.total)
                                onNeedMoreSegments()
                            }
                            appendGate.isSettled() || appendGate.canProbeEnd(state.total) -> {
                                // 本 total 已续过装：仍贴在末尾即文末探测，走溢出/播报分支，不重复追加。
                                onNeedMoreSegments()
                            }
                        }
                    }
                }
        }
        // 显式换页/跳行/换字号：列表回到顶部（滚屏自己引起的页号变化不带这个 token）。
        LaunchedEffect(scrollState.scrollToken) {
            listState.scrollToItem(0)
        }
}
