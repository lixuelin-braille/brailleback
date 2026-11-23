/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 叠加层点阵 Compose Canvas：几何与点位数学复用 [BrailleCellGeometry]，
 * 本层只负责把状态快照画到 Canvas 上。
 *
 * 时序边界（与 C2 契约一致）：
 * - 600/800ms 闪烁、pulseTimer、BlinkPolicy 仍全部留在 DisplayManager，本层一行不碰；
 * - 本层唯一允许的定时是高亮格 300ms 自动清除（常数 [HIGHLIGHT_TIME_MS] 保留），
 *   用 LaunchedEffect 完成，不出现 postDelayed / object : Runnable；
 * - 闪烁节拍不下沉到画布，画布只接收点位快照 + 高亮格下标。
 */

package com.linxi.brailleback.compose

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

/** 高亮格自动清除时长，与原 BrailleView.HIGHLIGHT_TIME_MS 一致。 */
internal const val HIGHLIGHT_TIME_MS = 300L

/**
 * 叠加层点阵渲染画布。
 *
 * @param highlightedCell 外部要求高亮的格下标；>=0 时本画布 300ms 后自动清空。
 * @param onHighlightCleared 高亮自动清除回调（把上浮状态复位）。
 * @param onCellClick 点击格下标回调（CMD_ROUTE 链路不变，由宿主翻译成输入事件）。
 * @param onResize 宽度可容纳格数变化回调。
 */
@Composable
internal fun BrailleOverlayCanvas(
    numCells: Int,
    braille: ByteArray,
    text: CharSequence,
    brailleToTextPositions: IntArray,
    highlightedCell: Int,
    modifier: Modifier = Modifier,
    dotRadius: Dp = 2.dp,
    cellWidth: Dp = 5.dp,
    cellHeight: Dp = 15.dp,
    cellPadding: Dp = 6.5.dp,
    textSize: TextUnit = 10.sp,
    textColor: Color = Color.White,
    highlightColor: Color = Color(0xFF33B5E5),
    onHighlightCleared: () -> Unit = {},
    onCellClick: (Int) -> Unit = {},
    onResize: (Int) -> Unit = {},
) {
    val density = LocalDensity.current
    var pressedCell by remember { mutableStateOf(-1) }

    // 高亮 300ms 自动清除：不使用 Handler/Runnable。
    LaunchedEffect(highlightedCell) {
        if (highlightedCell >= 0) {
            kotlinx.coroutines.delay(HIGHLIGHT_TIME_MS)
            onHighlightCleared()
        }
    }

    val primaryPaint: Paint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
        }
    }
    LaunchedEffect(textColor, textSize) {
        primaryPaint.color = textColor.toArgb()
        primaryPaint.textSize = with(density) { textSize.toPx() }
    }
    val outerWidthDp = cellWidth + cellPadding * 2
    val outerHeightDp = cellHeight + cellPadding * 2

    BoxWithConstraints(modifier = modifier.height(outerHeightDp + cellPadding)) {
        val outerWidthPx = with(density) { outerWidthDp.toPx() }
        val maxCells = floor(constraints.maxWidth / outerWidthPx).toInt()
        LaunchedEffect(maxCells) { onResize(maxCells) }

        Canvas(
            modifier = Modifier.pointerInput(numCells, outerWidthDp) {
                detectTapGestures(
                    onPress = { offset ->
                        val idx = floor(offset.x / outerWidthPx).toInt()
                        pressedCell = if (idx in 0 until numCells) idx else -1
                        tryAwaitRelease()
                        pressedCell = -1
                    },
                    onTap = { offset ->
                        val idx = floor(offset.x / outerWidthPx).toInt()
                        if (idx in 0 until numCells) onCellClick(idx)
                    },
                )
            },
        ) {
        val outerHeightPx = outerHeightDp.toPx()
        val cellWidthPx = cellWidth.toPx()
        val cellHeightPx = cellHeight.toPx()
        val cellPaddingPx = cellPadding.toPx()
        val inner = BrailleCellGeometry.innerRect(cellWidthPx, cellHeightPx, cellPaddingPx)

        // 高亮格 / 按压格底衬。
        fun cellBackground(cellIndex: Int, alpha: Float) {
            if (cellIndex in 0 until numCells) {
                drawRect(
                    highlightColor.copy(alpha = alpha),
                    topLeft = Offset(cellIndex * outerWidthPx, 0f),
                    size = Size(outerWidthPx, outerHeightPx),
                )
            }
        }
        cellBackground(highlightedCell, 0.3f)
        if (pressedCell != highlightedCell) {
            cellBackground(pressedCell, 0.3f)
        }

        // 8 点位。
        for (i in 0 until numCells) {
            val pattern = if (i < braille.size) braille[i].toInt() and 0xFF else 0x00
            var bits = pattern
            var dot = 0
            while (dot < BrailleCellGeometry.DOT_POSITIONS.size / 2) {
                val (x, y) = BrailleCellGeometry.dotCenter(inner, dot)
                val raised = (bits and 1) != 0
                drawCircle(
                    color = if (raised) textColor else textColor.copy(alpha = BrailleCellGeometry.DIMMED_ALPHA / 255f),
                    radius = dotRadius.toPx(),
                    center = Offset(i * outerWidthPx + x, y),
                )
                bits = bits shr 1
                dot++
            }
        }

        // 对应文字：按分片裁切，溢出把 textScaleX 压到格宽后复位 1.0。
        val metrics = primaryPaint.fontMetrics
        val textStartY = outerHeightPx - metrics.ascent
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            val segments = BrailleCellGeometry.textSegments(
                positions = brailleToTextPositions,
                numCells = numCells,
                textLength = text.length,
            )
            for (seg in segments) {
                val clipLeft = outerWidthPx * seg.brailleStart
                val clipRight = outerWidthPx * seg.brailleEnd
                val clipTop = outerHeightPx
                val clipBottom = clipTop + cellPaddingPx - metrics.ascent + metrics.descent + cellPaddingPx
                val centerX = (clipLeft + clipRight) / 2
                val measured = primaryPaint.measureText(text, seg.textStart, seg.textEnd)
                if (measured > clipRight - clipLeft) {
                    primaryPaint.textScaleX = (clipRight - clipLeft) / measured
                }
                native.save()
                native.clipRect(clipLeft, clipTop, clipRight, clipBottom)
                native.drawText(text, seg.textStart, seg.textEnd, centerX, textStartY, primaryPaint)
                native.restore()
                primaryPaint.textScaleX = 1.0f
            }
        }
        }
    }
}
