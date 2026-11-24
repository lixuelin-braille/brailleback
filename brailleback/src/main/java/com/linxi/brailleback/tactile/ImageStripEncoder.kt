package com.linxi.brailleback.tactile

/**
 * 为什么需要它：
 * 纸面触觉图走 [TactilePipeline] → BMP，点显器却要的是「每方一个字节」的实时点位。
 * 两者的版面语言不同：纸面按毫米排点，点显按方/行分屏，一张图必须切成若干页才能翻。
 * 这里只做纯逻辑（灰度图 → 点格 → 分页 → 点显字节），不碰 Android，也不碰 UI，
 * 以便在 JVM 单测里逐位断言；入口层拿到 List<ByteArray> 后直接交给 displayDots。
 *
 * 字节位序与项目其余部分一致：dot1=bit0 … dot8=bit7；6 点只用低 6 位。
 */
object ImageStripEncoder {

    /**
     * 点显版面。
     *
     * 点显版面的横向点列必须等于 [cellsPerLine]×2（每方左右两列点）：默认值即按此取值，
     * 一张图在一行内铺满整屏；[dotColumns] 保留为显式覆盖项，仅供调试 / 原型验证使用，
     * 真实设备请走 [forDisplay]，不要手工喂一个小于 cellsPerLine×2 的宽度，否则右侧若干方
     * 会永远读不到像素。
     *
     * @param cellsPerLine 一行多少方（20/30/40…）。
     * @param linesPerScreen 点显屏有几行文字；单行设备传 1。
     * @param dotsPerCell 6 或 8。
     * @param dotColumns 整张图横向占多少点；默认 cellsPerLine×2。与每页横向宽度不整除时，
     *   最后一段横向不满的页只填已有点位，不崩、不越界、不重复填充。
     * @param threshold 二值阈值；-1 表示用 Otsu 自动取。
     */
    data class Spec(
        val cellsPerLine: Int,
        val linesPerScreen: Int = 1,
        val dotsPerCell: Int = 8,
        val dotColumns: Int = cellsPerLine * 2,
        val threshold: Int = -1,
    ) {
        init {
            require(cellsPerLine > 0) { "cellsPerLine 必须为正数，实际为 $cellsPerLine" }
            require(linesPerScreen > 0) { "linesPerScreen 必须为正数，实际为 $linesPerScreen" }
            require(dotsPerCell == 6 || dotsPerCell == 8) { "dotsPerCell 只能是 6 或 8" }
            require(dotColumns > 0) { "dotColumns 必须为正数，实际为 $dotColumns" }
        }

        companion object {
            /**
             * 供 App 侧按真实点显容量建版面：横向点列固定为 cellsPerLine×2（铺满一行），
             * 阈值走 Otsu 自动二值化。调试 / 原型才需要直接调构造函数并覆盖 [dotColumns]。
             */
            fun forDisplay(
                cellsPerLine: Int,
                linesPerScreen: Int = 1,
                dotsPerCell: Int = 8,
            ): Spec = Spec(
                cellsPerLine = cellsPerLine,
                linesPerScreen = linesPerScreen,
                dotsPerCell = dotsPerCell,
                dotColumns = cellsPerLine * 2,
                threshold = -1,
            )
        }
    }

    /** 每方占用几个点行：8 点 4 行，6 点 3 行。 */
    private fun dotRows(spec: Spec): Int = if (spec.dotsPerCell == 8) 4 else 3

    /**
     * 灰度图 → 按页排好的点显字节。
     *
     * 页序与阅读一致：从左到右、从上到下。每页长度 = linesPerScreen × cellsPerLine。
     * 图比一页窄时右侧补空方，比一页宽时横向切段，不缩放、不裁掉内容。
     */
    fun encode(image: GrayImage, spec: Spec): List<ByteArray> {
        val cols = spec.dotColumns
        val rows = maxOf(1, Math.round(cols.toFloat() * image.height / image.width))
        val ink = dotGrid(image, cols, rows, spec.threshold)
        val rowsPerCell = dotRows(spec)
        val pageCols = spec.cellsPerLine * 2
        val pageRows = spec.linesPerScreen * rowsPerCell
        val pages = ArrayList<ByteArray>()
        var top = 0
        while (top < rows) {
            var left = 0
            while (left < cols) {
                pages.add(page(ink, cols, rows, left, top, spec, rowsPerCell))
                left += pageCols
            }
            top += pageRows
        }
        return pages
    }

    /** 全图二值化后的点格：true = 有凸点。 */
    fun dotGrid(image: GrayImage, cols: Int, rows: Int, threshold: Int = -1): BooleanArray {
        val means = DoubleArray(cols * rows)
        for (y in 0 until rows) {
            val sy0 = y * image.height / rows
            val sy1 = maxOf(sy0 + 1, (y + 1) * image.height / rows)
            for (x in 0 until cols) {
                val sx0 = x * image.width / cols
                val sx1 = maxOf(sx0 + 1, (x + 1) * image.width / cols)
                var sum = 0L
                var count = 0
                for (sy in sy0 until minOf(sy1, image.height)) {
                    for (sx in sx0 until minOf(sx1, image.width)) {
                        sum += image.luma[sy * image.width + sx]
                        count++
                    }
                }
                means[y * cols + x] = if (count == 0) 255.0 else sum.toDouble() / count
            }
        }
        val cut = if (threshold >= 0) threshold else otsu(means)
        return BooleanArray(cols * rows) { means[it] <= cut }
    }

    /** Otsu：让前景/背景类间方差最大的 0..255 阈值；点格均值取整后统计。 */
    private fun otsu(values: DoubleArray): Int {
        val hist = IntArray(256)
        for (v in values) hist[v.toInt().coerceIn(0, 255)]++
        val total = values.size.coerceAtLeast(1)
        var sumAll = 0.0
        for (i in 0..255) sumAll += i.toDouble() * hist[i]
        var sumBack = 0.0
        var weightBack = 0
        var best = 0
        var bestVar = -1.0
        for (t in 0..255) {
            weightBack += hist[t]
            if (weightBack == 0) continue
            val weightFore = total - weightBack
            if (weightFore == 0) break
            sumBack += t.toDouble() * hist[t]
            val meanBack = sumBack / weightBack
            val meanFore = (sumAll - sumBack) / weightFore
            val between = weightBack.toDouble() * weightFore * (meanBack - meanFore) *
                (meanBack - meanFore)
            if (between > bestVar) {
                bestVar = between
                best = t
            }
        }
        return best
    }

    /** 切一页，把点格打包成每方一个字节。 */
    private fun page(
        ink: BooleanArray,
        cols: Int,
        rows: Int,
        left: Int,
        top: Int,
        spec: Spec,
        rowsPerCell: Int,
    ): ByteArray {
        val out = ByteArray(spec.linesPerScreen * spec.cellsPerLine)
        var index = 0
        for (line in 0 until spec.linesPerScreen) {
            for (cell in 0 until spec.cellsPerLine) {
                var bits = 0
                for (dr in 0 until rowsPerCell) {
                    val gy = top + line * rowsPerCell + dr
                    if (gy >= rows) break
                    for (dc in 0..1) {
                        val gx = left + cell * 2 + dc
                        if (gx >= cols) continue
                        if (ink[gy * cols + gx]) {
                            bits = bits or (1 shl dotIndex(dr, dc, spec.dotsPerCell))
                        }
                    }
                }
                out[index++] = bits.toByte()
            }
        }
        return out
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
