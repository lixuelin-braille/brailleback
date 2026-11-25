/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 自绘双滑块：一个部件、一个无障碍节点。页面上摆两条——上面管整本书的页，
 * 下面管本页的行。手势判定与整块语义在这里，不随界面装配改动。
 */

package com.linxi.brailleback.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** 滑块手势判定结果：落在某格（[index] ≥ 0、[overflow] = 0），或越过端点要求翻页（[index] = -1、[overflow] = ±1）。 */
internal data class ScrubberHit(val index: Int, val overflow: Int)

/** 按下/拖动到横坐标 [x] 时该给哪一格；越过滑轨两端 [overflowSlack] 以上的部分算「继续往前推」。 */
internal fun scrubberHitAt(
    x: Float,
    trackStartPx: Float,
    trackEndPx: Float,
    maxIndex: Int,
    overflowEnabled: Boolean,
    overflowSlack: Float = 0.04f,
): ScrubberHit {
    val span = (trackEndPx - trackStartPx).coerceAtLeast(1f)
    val raw = (x - trackStartPx) / span
    if (overflowEnabled && raw > 1f + overflowSlack) return ScrubberHit(-1, 1)
    if (overflowEnabled && raw < -overflowSlack) return ScrubberHit(-1, -1)
    val fraction = raw.coerceIn(0f, 1f)
    return ScrubberHit((fraction * maxIndex).roundToInt().coerceIn(0, maxIndex), 0)
}

/**
 * 读屏上下滑（`setProgress`）时该给哪一格。
 *
 * 已经停在本页最后一行、读屏还在往上调（目标被夹回同一格）——这就是「到头了还想往前」，
 * 于是要求翻到下一页；第一行再往下调则翻上一页。这样盲文用户靠上下滑就能一路滚下去，
 * 不会在页末停住。
 */
internal fun scrubberHitForAdjust(
    index: Int,
    target: Int,
    maxIndex: Int,
    overflowEnabled: Boolean,
): ScrubberHit {
    val t = target.coerceIn(0, maxIndex)
    if (t != index) return ScrubberHit(t, 0)
    if (!overflowEnabled) return ScrubberHit(-1, 0)
    return when {
        index >= maxIndex -> ScrubberHit(-1, 1)
        index <= 0 -> ScrubberHit(-1, -1)
        else -> ScrubberHit(-1, 0)
    }
}

/**
 * 一条自绘滑块：一个部件、一个无障碍节点。页面上摆两条——上面管整本书的页，
 * 下面管本页的行。
 *
 * 为什么不用 M3 `Slider`：它自带 `progressBarRangeInfo` 语义，与我们在外层声明的
 * 描述会各自变成一个 AccessibilityNode，读屏就停在「滑块」和「文字」两处。
 * 这里自己画：Canvas 只负责像素，语义整块声明在同一个 Box 上，子节点为零。
 *
 * - 读屏：念 [description] + 「滑块」，上下滑一格一格走（RangeInfo + setProgress）；
 *   双击走 onClick，弹数字输入。
 * - 版式：文字在左，**滑轨接在文字后面**（右侧），像普通滑块那样摆着。
 * - 触摸：按在滑轨上就跳到对应格，横向拖动则一格一格地跟手。
 * - [overflowEnabled]：到头再往前推时交给 [onOverflow]（+1 下一页 / -1 上一页），
 *   这样行滑块拖到底、或读屏调到底还继续调，都会顺势滚进下一页。
 */
@Composable
internal fun ScrubberBar(
    description: String,
    index: Int,
    count: Int,
    actionLabel: String,
    fontSizeSp: Int,
    onSeek: (Int) -> Unit,
    onEdit: () -> Unit,
    overflowEnabled: Boolean = false,
    onOverflow: (Int) -> Unit = {},
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val lineStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = fontSizeSp.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val text = measurer.measure(AnnotatedString(description), lineStyle)
    // 文字与滑轨同一行、滑轨在文字右侧；这一行的高度取「文字高度」与「滑轨高度」的较大者。
    val textHeightDp = with(density) { text.size.height.toDp() }
    // 行高要比文字本身再高一点：正好等于文字高度时上下会被 Canvas 切掉半截笔画。
    val rowHeight = maxOf(textHeightDp + 6.dp, 26.dp)
    val barHeight = rowHeight
    val maxIndex = (count - 1).coerceAtLeast(1)
    val thumbColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    // drawText 不会沿用 layout 里 style 的颜色，必须显式传，否则默认画成黑色
    // （在深色背景上就等于看不见）。
    
    // 正文那些 Text 用的是 LocalContentColor；Canvas 自绘必须取同一个色，
    // 用 onSurface 在深色主题里会画得明显偏暗（读屏不受影响，但屏上几乎看不见）。
    val lineColor = LocalContentColor.current
    val pageColor = MaterialTheme.colorScheme.onSurfaceVariant
    // 滑轨几何先算成像素：Canvas 与 pointerInput 用同一套，免得触摸和画的位置对不上。
    val radiusPx = with(density) { 6.dp.toPx() }
    val gapPx = with(density) { 14.dp.toPx() }
    val textWidthPx = text.size.width.toFloat()
    val trackStartPx = textWidthPx + gapPx + radiusPx

    // 手势里要读的值都用 rememberUpdatedState 拿「最新的那一份」：
    // 翻译还在跑时 totalUnits/行数会跳变，一旦把这些当 pointerInput 的 key，
    // 手势会被中途重建，拖动只认按下那一刻的坐标（表现为「拖不到末尾」）。
    val currentMaxIndex by rememberUpdatedState(maxIndex)
    val currentCount by rememberUpdatedState(count)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnOverflow by rememberUpdatedState(onOverflow)
    val currentOverflowEnabled by rememberUpdatedState(overflowEnabled)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight)
            // 触摸：按下即跳到该格，拖动时一格一格跟手。
            // 拖出滑轨两端时（行滑块）继续往前推进到上/下一页，滚动才不会在页末卡住。
            // 只有一格时不必响应（拖不出任何东西）。
            .pointerInput(trackStartPx) {
                if (currentCount < 2) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // 与 Canvas 画出来的那段轨对齐：轨终点在 size.width - radiusPx。
                    val trackEnd = size.width - radiusPx
                    var over = 0
                    // 顺手把 ACTION_UP 也算进去：快速拖动时中间事件会被合并/丢失，
                    // 抬起那一刻的位置才是用户真正推到的位置。
                    fun seekAt(x: Float) {
                        val hit = scrubberHitAt(
                            x = x,
                            trackStartPx = trackStartPx,
                            trackEndPx = trackEnd,
                            maxIndex = currentMaxIndex,
                            overflowEnabled = currentOverflowEnabled,
                        )
                        if (hit.overflow != 0) {
                            if (over != hit.overflow) {
                                over = hit.overflow
                                currentOnOverflow(hit.overflow)
                            }
                            return
                        }
                        over = 0
                        if (hit.index >= 0) currentOnSeek(hit.index)
                    }
                    seekAt(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) {
                            seekAt(change.position.x)
                            break
                        }
                        seekAt(change.position.x)
                        change.consume()
                    }
                }
            }
            .semantics {
                contentDescription = description
                // 滑块语义（用户要求保留「滑块」这个词，像桌面那排翻页提示）：
                // 读屏念 [description] + 「滑块」，一格一格走。
                stateDescription = description
                if (count >= 2) {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        index.toFloat(),
                        0f..maxIndex.toFloat(),
                        (count - 2).coerceAtLeast(0),
                    )
                    setProgress { target ->
                        val hit = scrubberHitForAdjust(
                            index = index,
                            target = target.roundToInt(),
                            maxIndex = maxIndex,
                            overflowEnabled = overflowEnabled,
                        )
                        when {
                            hit.overflow != 0 -> onOverflow(hit.overflow)
                            hit.index >= 0 -> onSeek(hit.index)
                        }
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
            val rowHeightPx = rowHeight.toPx()
            // 文字在左、垂直居中；滑轨接在它后面（右侧），同一行里摆开。
            val textTop = (rowHeightPx - text.size.height) / 2
            drawText(text, color = lineColor, topLeft = Offset(0f, textTop))
            val cy = rowHeightPx / 2
            if (count >= 2 && trackStartPx + radiusPx < size.width) {
                drawLine(
                    color = trackColor,
                    start = Offset(trackStartPx, cy),
                    end = Offset(size.width - radiusPx, cy),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                val span = (size.width - trackStartPx - radiusPx).coerceAtLeast(1f)
                val fraction = index.toFloat() / maxIndex
                drawCircle(
                    color = thumbColor,
                    radius = radiusPx,
                    center = Offset(trackStartPx + span * fraction, cy),
                )
            }
        }
    }
}
