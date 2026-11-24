package com.linxi.brailleback.tactile

/**
 * 一个图形带：全局点格子集打包成的「一方一字节」，附带原点格坐标与上下重叠量，
 * 足够重建这一带在整图中的位置。
 *
 * @param bytes 带内点位，长度恒为 linesPerScreen × cellsPerLine；不足一屏的行补空方。
 * @param x0 带在全局点阵中的起始点列。
 * @param y0 带在全局点阵中的起始点行。
 * @param height 带实际占用的点行数（可能小于 bandRows，末带允许不足一屏）。
 * @param overlapTop 与上一带纵向重叠的行数（上一带尾部原样复制进本带顶部）。
 * @param overlapBottom 与下一带纵向重叠的行数（本带尾部原样复制进下一带顶部）。
 * @param bandIndex 带序号，1 基。
 * @param total 全局带总数。
 */
data class Band(
    val bytes: ByteArray,
    val x0: Int,
    val y0: Int,
    val height: Int,
    val overlapTop: Int,
    val overlapBottom: Int,
    val bandIndex: Int,
    val total: Int,
)

/** 一次切片的完整结果：全局点阵尺寸、版面参数与全部图形带。 */
data class SliceResult(
    val bands: List<Band>,
    val totalBands: Int,
    val cols: Int,
    val rows: Int,
    val pageCols: Int,
    val bandRows: Int,
)

/**
 * 为什么需要它：
 * 旧编码器按固定网格硬切块，笔画正好落在切缝上会被劈断。切片器只在已经二值化好的
 * 全局点阵上裁，不缩放：纵向优先切在整行无墨的空白行；找不到空白行就在理想边界切，
 * 并按线宽重叠 1～2 行，重叠行在前后两带原样复制，保证墨迹不被切缝切断。横向同理。
 *
 * 硬性保证：
 * - 每一行墨迹至少出现在一个带里，不丢图；
 * - 最后一带可以不足 bandRows，但输出字节长度仍恒为 linesPerScreen × cellsPerLine，缺行补空；
 * - 推进量恒为正，不死循环、不越界。
 *
 * 本类纯 Kotlin，不依赖 android.*，可在 JVM 单测里逐带断言。
 */
object TactileStripSlicer {

    /**
     * 把全局点阵切成图形带。
     *
     * @param ink 二值点阵，行优先，true = 有凸点，长度 cols × rows。
     * @param cols 全局点列数。
     * @param rows 全局点行数。
     * @param spec 点显版面。
     */
    fun slice(ink: BooleanArray, cols: Int, rows: Int, spec: ImageStripEncoder.Spec): SliceResult {
        require(cols > 0 && rows > 0) { "点阵宽高必须为正数，实际为 ${cols}×$rows" }
        require(ink.size == cols * rows) {
            "点阵长度 ${ink.size} 不等于 ${cols}×${rows}"
        }
        val pageCols = spec.cellsPerLine * 2
        val rowsPerCell = if (spec.dotsPerCell == 8) 4 else 3
        val bandRows = spec.linesPerScreen * rowsPerCell

        val rowInk = BooleanArray(rows) { r ->
            var any = false
            for (c in 0 until cols) {
                if (ink[r * cols + c]) { any = true; break }
            }
            any
        }
        val colInk = BooleanArray(cols) { c ->
            var any = false
            for (r in 0 until rows) {
                if (ink[r * cols + c]) { any = true; break }
            }
            any
        }

        val bands = ArrayList<Band>()
        var y = 0
        var prevY1 = -1
        while (y < rows) {
            val idealY = minOf(y + bandRows, rows)
            val blankRow = findBlankLine(rowInk, y, idealY, bandRows, rows)
            val y1: Int
            val overlapBottom: Int
            if (blankRow >= 0) {
                y1 = blankRow
                overlapBottom = 0
            } else {
                y1 = idealY
                overlapBottom = rowOverlap(ink, cols, rowInk, idealY, rows)
            }
            val overlapTop = if (prevY1 < 0) 0 else (prevY1 - y).coerceAtLeast(0)

            var x = 0
            while (x < cols) {
                val idealX = minOf(x + pageCols, cols)
                val blankCol = findBlankLine(colInk, x, idealX, pageCols, cols)
                val x1: Int
                val overlapRight: Int
                if (blankCol >= 0) {
                    x1 = blankCol
                    overlapRight = 0
                } else {
                    x1 = idealX
                    overlapRight = if (idealX >= cols) 0 else 1
                }
                val bytes = toBytes(ink, cols, x, y, y1 - y, spec)
                bands.add(
                    Band(
                        bytes = bytes,
                        x0 = x,
                        y0 = y,
                        height = y1 - y,
                        overlapTop = overlapTop,
                        overlapBottom = overlapBottom,
                        bandIndex = bands.size + 1,
                        total = 0,
                    )
                )
                val nextX = if (blankCol >= 0) x1 else x1 - overlapRight
                if (nextX <= x) break // 防死循环：推进量必须为正
                x = nextX
            }

            prevY1 = y1
            val nextY = if (blankRow >= 0) y1 else y1 - overlapBottom
            if (nextY <= y) break // 防死循环：推进量必须为正
            y = nextY
        }

        val total = bands.size
        val finalized = bands.map { it.copy(total = total) }
        return SliceResult(finalized, total, cols, rows, pageCols, bandRows)
    }

    /**
     * 把点格子集打包成一方一字节。越界行列按空方处理；height 不足一屏时尾部补空。
     */
    fun toBytes(
        ink: BooleanArray,
        cols: Int,
        x0: Int,
        y0: Int,
        height: Int,
        spec: ImageStripEncoder.Spec,
    ): ByteArray {
        val out = ByteArray(spec.linesPerScreen * spec.cellsPerLine)
        val rowsPerCell = if (spec.dotsPerCell == 8) 4 else 3
        var index = 0
        for (line in 0 until spec.linesPerScreen) {
            for (cell in 0 until spec.cellsPerLine) {
                var bits = 0
                for (dr in 0 until rowsPerCell) {
                    val gy = y0 + line * rowsPerCell + dr
                    if (gy >= y0 + height) break
                    for (dc in 0..1) {
                        val gx = x0 + cell * 2 + dc
                        if (gx >= cols) continue
                        val gi = gy * cols + gx
                        if (gi >= ink.size) continue
                        if (ink[gi]) {
                            bits = bits or (1 shl dotIndex(dr, dc, spec.dotsPerCell))
                        }
                    }
                }
                out[index++] = bits.toByte()
            }
        }
        return out
    }

    /**
     * 供视口等复用的空白切缝查找：规则与 [findBlankLine] 完全一致，避免另写一套
     * 不一致的切缝逻辑。返回切缝位置（不含该行/列本身），找不到返回 -1。
     */
    fun blankSeam(
        flags: BooleanArray,
        start: Int,
        idealEnd: Int,
        bandSize: Int,
        limit: Int,
    ): Int = findBlankLine(flags, start, idealEnd, bandSize, limit)

    /**
     * 在理想边界上下各 [bandSize]/2 行（列）窗口内找最近的整行（列）无墨位置；
     * 找不到返回 -1。返回值是切缝位置（不含该行/列本身，它归入下一带）。
     */
    private fun findBlankLine(
        flags: BooleanArray,
        start: Int,
        idealEnd: Int,
        bandSize: Int,
        limit: Int,
    ): Int {
        if (idealEnd >= limit) return -1
        val half = bandSize / 2
        for (d in 0..half) {
            val up = idealEnd - d
            if (up > start && up < limit && !flags[up]) return up
            val down = idealEnd + d
            if (down > start && down < limit && down != up && !flags[down]) return down
        }
        return -1
    }

    /**
     * 边界相邻两行在同一列都有墨（竖向连续 2 点线宽）→ 重叠 2 行；
     * 两行虽各自有墨但列不相交，按细线条处理重叠 1 行；末带不重叠。
     */
    private fun rowOverlap(
        ink: BooleanArray,
        cols: Int,
        rowInk: BooleanArray,
        idealEnd: Int,
        rows: Int,
    ): Int {
        if (idealEnd >= rows) return 0
        if (idealEnd - 1 < 0 || !rowInk[idealEnd - 1] || !rowInk[idealEnd]) return 1
        for (c in 0 until cols) {
            if (ink[(idealEnd - 1) * cols + c] && ink[idealEnd * cols + c]) return 2
        }
        return 1
    }

    /** 点号（0 基）：左列自上而下 1,2,3,7；右列 4,5,6,8。6 点无 7/8。 */
    private fun dotIndex(row: Int, col: Int, dotsPerCell: Int): Int {
        if (dotsPerCell == 6) {
            return if (col == 0) row else row + 3
        }
        return if (col == 0) {
            when (row) {
                0 -> 0
                1 -> 1
                2 -> 2
                else -> 6
            }
        } else {
            when (row) {
                0 -> 3
                1 -> 4
                2 -> 5
                else -> 7
            }
        }
    }
}
