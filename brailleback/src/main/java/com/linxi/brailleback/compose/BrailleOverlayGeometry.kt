/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 叠加层点阵的纯 Kotlin 几何数学：内框分支、点位中心、文字分片。
 * 不依赖 Android Canvas / Paint，JVM 单测可直接打，也用于新旧布局耗时对比。
 *
 * 几何口径与原 BrailleView.onDraw 逐项对齐：
 * - outerWidth = cellWidth + 2·cellPadding，outerHeight = cellHeight + 2·cellPadding；
 * - 内框 3:1 两个分支：3·cellWidth ≥ cellHeight 横向补 padding，否则纵向补；
 * - 8 点位行列比 0 / 0.33 / 0.67（点位 7、8 行比 1.00）；
 * - 未点亮点 DIMMED_ALPHA = 0x40。
 */

package com.linxi.brailleback.compose

/** 单个格子内框与原点偏移（单位与外部传入的 cell 尺寸一致，px 或 dp 由调用方定）。 */
internal data class InnerRect(
    val innerWidth: Float,
    val innerHeight: Float,
    val offsetX: Float,
    val offsetY: Float,
)

/** 一段文字分片：覆盖 brailleStart..brailleEnd 这些格，对应 text[textStart..textEnd)。 */
internal data class TextSegment(
    val brailleStart: Int,
    val brailleEnd: Int,
    val textStart: Int,
    val textEnd: Int,
)

internal object BrailleCellGeometry {

    // 代码内 px 兜底默认值（原 View 构造参数缺省值，保留为后备）。
    const val DOT_RADIUS_PX_FALLBACK = 4.0f
    const val CELL_WIDTH_PX_FALLBACK = 10.0f
    const val CELL_HEIGHT_PX_FALLBACK = 30.0f
    const val CELL_PADDING_PX_FALLBACK = 13.0f

    const val DIMMED_ALPHA = 0x40

    /** 8 点位：每两个数一组 (列比, 行比)，点位 1..8。 */
    val DOT_POSITIONS = floatArrayOf(
        0.0f, 0.00f, // dot 1
        0.0f, 0.33f, // dot 2
        0.0f, 0.67f, // dot 3
        1.0f, 0.00f, // dot 4
        1.0f, 0.33f, // dot 5
        1.0f, 0.67f, // dot 6
        0.0f, 1.00f, // dot 7
        1.0f, 1.00f, // dot 8
    )

    fun outerWidth(cellWidth: Float, cellPadding: Float) = cellWidth + 2 * cellPadding

    fun outerHeight(cellHeight: Float, cellPadding: Float) = cellHeight + 2 * cellPadding

    /** 复刻 onDraw 的 3:1 内框分支。 */
    fun innerRect(cellWidth: Float, cellHeight: Float, cellPadding: Float): InnerRect {
        return if (3 * cellWidth >= cellHeight) {
            // 横向补 padding。
            val innerWidth = cellHeight / 3
            InnerRect(
                innerWidth = innerWidth,
                innerHeight = cellHeight,
                offsetX = cellPadding + (cellWidth - innerWidth) / 2,
                offsetY = cellPadding,
            )
        } else {
            // 纵向补 padding。
            InnerRect(
                innerWidth = cellWidth,
                innerHeight = cellWidth * 3,
                offsetX = cellPadding,
                offsetY = cellPadding + (cellHeight - cellWidth * 3) / 2,
            )
        }
    }

    /** 第 dotIndex 个点位（0..7）在内框中的中心坐标。 */
    fun dotCenter(rect: InnerRect, dotIndex: Int): Pair<Float, Float> {
        val x = rect.offsetX + DOT_POSITIONS[dotIndex * 2] * rect.innerWidth
        val y = rect.offsetY + DOT_POSITIONS[dotIndex * 2 + 1] * rect.innerHeight
        return x to y
    }

    /** 复刻 onDraw 的文字分片裁切：按 brailleToTextPositions 把文字切到对应格子区间。 */
    fun textSegments(
        positions: IntArray,
        numCells: Int,
        textLength: Int,
    ): List<TextSegment> {
        val segments = mutableListOf<TextSegment>()
        var brailleIndex = 0
        while (brailleIndex < numCells && brailleIndex < positions.size) {
            val brailleStart = brailleIndex
            val textStart = positions[brailleStart]
            do {
                brailleIndex++
            } while (brailleIndex < positions.size && positions[brailleIndex] <= textStart)
            val brailleEnd = brailleIndex
            val textEnd = if (brailleEnd < positions.size) {
                positions[brailleEnd]
            } else {
                textLength
            }
            segments.add(TextSegment(brailleStart, brailleEnd, textStart, textEnd))
        }
        return segments
    }
}
