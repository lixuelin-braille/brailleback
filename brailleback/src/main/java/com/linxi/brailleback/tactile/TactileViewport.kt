package com.linxi.brailleback.tactile

/**
 * 视口模式：[OVERVIEW] 整图压成一屏总览（唯一允许降采样的一页）；[DETAIL] 在全局点阵上
 * 按「块」浏览，只裁不缩放。一块 = 固定 4 屏，块内翻页，块间再换块。
 */
enum class ViewportMode { OVERVIEW, DETAIL }

/**
 * 平移 / 翻页受阻边界。
 * - [LEFT]/[RIGHT]/[TOP]/[BOTTOM]：单屏横向 / 纵向到边；
 * - [BLOCK_START]/[BLOCK_END]：已在当前块的首屏 / 末屏；
 * - [FIRST_BLOCK]/[LAST_BLOCK]：已在第一块 / 最后一块。
 */
enum class Boundary { LEFT, RIGHT, TOP, BOTTOM, BLOCK_START, BLOCK_END, FIRST_BLOCK, LAST_BLOCK }

/**
 * 一次平移 / 翻页的结果：[moved] 为 false 时状态不变，[boundary] 给出受阻信号（未受阻为 null）。
 */
data class MoveResult(val moved: Boolean, val boundary: Boundary?)

/**
 * 一个已排好的细节窗（全局点阵坐标）。相邻屏纵向重叠 1 点行、相邻横向窗重叠 1 点列，
 * 重叠内容来自同一布尔源，原样复制。
 */
data class DetailWindow(
    val col: Int,
    val row: Int,
    val x0: Int,
    val y0: Int,
    val width: Int,
    val height: Int,
)

/**
 * 为什么需要它：
 * 既有三段式按固定网格把图切成 10～78 条单行带，单行设备翻带成本高、横向割裂。视口在
 * 已经二值化好的全局点阵上先看一屏总览；选择框选中一个**块**进入原分辨率细节：一块固定
 * 4 屏（8 点 16 点行 / 6 点 12 点行），块内翻页，到块末再换相邻块，可一键返回总览。
 *
 * 硬性保证（纯 Kotlin，不依赖 android.*，可在 JVM 单测里逐块逐屏断言）：
 * - 细节页只从全局点阵裁剪，不缩放；总览是唯一降采样的一页，并以 [isOverview] 标记；
 * - 块内屏数 ≤ 4；末块不足一块时允许 1–3 屏，不跨出 4 屏；
 * - 到边界翻页返回 false，origin 不变，同时给出 [Boundary] 信号；
 * - 相邻屏重叠 1 点，重叠行 / 列与全局点阵逐位一致；
 * - 所有细节窗覆盖的墨行并集 == 全局点阵墨行，不丢图；
 * - 标签不画进点阵：图例由 [TactileLegendPage] 出独立屏，本类不合成任何文字点位。
 *
 * @param ink 二值点阵，行优先，true = 有凸点，长度 cols × rows。
 * @param cols 全局点列数。
 * @param rows 全局点行数。
 * @param spec 点显版面。
 */
class TactileViewport(
    val ink: BooleanArray,
    val cols: Int,
    val rows: Int,
    val spec: ImageStripEncoder.Spec,
) {
    init {
        require(cols > 0 && rows > 0) { "点阵宽高必须为正数，实际为 ${cols}×$rows" }
        require(ink.size == cols * rows) {
            "点阵长度 ${ink.size} 不等于 ${cols}×${rows}"
        }
    }

    /** 每块固定屏数。 */
    val blockScreens: Int = BLOCK_SCREENS

    /** 每方点列恒为 2；窗口横向点列 = 一行方数 × 2。 */
    val windowCols: Int = spec.cellsPerLine * 2

    /** 单屏纵向点行：8 点一行 4 行，6 点一行 3 行；多行设备按 linesPerScreen 折算。 */
    val bandRows: Int = spec.linesPerScreen * rowsPerCell()

    /** 一块纵向点行 = 屏数 × 单屏点行（8 点 16、6 点 12）。 */
    val blockRows: Int = BLOCK_SCREENS * bandRows

    /** 当前视口模式，默认从总览进入。 */
    var mode: ViewportMode = ViewportMode.OVERVIEW
        private set

    /** 当前细节窗在全局点阵中的起点列（点列）。 */
    var originCol: Int = 0
        private set

    /** 当前细节窗在全局点阵中的起点行（点行）。 */
    var originRow: Int = 0
        private set

    /** 总览选择框（块）起点列（点列，按方对齐）。 */
    var boxCol: Int = 0
        private set

    /** 总览选择框（块）起点行（点行）。 */
    var boxRow: Int = 0
        private set

    /** 选择框宽度（点列），与细节窗同宽。 */
    val boxCols: Int = windowCols

    /** 选择框高度（点行），与一块同高。 */
    val boxRows: Int = blockRows

    /** 当前块序号（0 基）。 */
    var blockIndex: Int = 0
        private set

    /** 当前块内屏序号（0 基）。 */
    var blockPageIndex: Int = 0
        private set

    private var colIndex: Int = 0

    private val colInk: BooleanArray = BooleanArray(cols) { c ->
        var any = false
        for (r in 0 until rows) if (ink[r * cols + c]) { any = true; break }
        any
    }

    /** 横向细节窗起点列表（含空白缝对齐与 1 列重叠规则）。 */
    private val colStarts: List<Int> = computeStarts(cols, windowCols, colInk)

    /** 全部块：按 blockRows 纵向切分，块内排屏。 */
    private val blocks: List<Block> = buildBlocks()

    /** 块总数。 */
    val blockCount: Int get() = blocks.size

    /** 当前块屏数（末块可能 1–3）。 */
    fun blockPageCount(): Int = blocks[blockIndex].rowStarts.size

    /** 当前是否总览页。 */
    val isOverview: Boolean get() = mode == ViewportMode.OVERVIEW

    /** 总览页的固定标记，走 TTS / 文本屏，不写进点位字节。 */
    val overviewLabel: String get() = OVERVIEW_LABEL

    private fun rowsPerCell(): Int = if (spec.dotsPerCell == 8) 4 else 3

    // ------------------------------------------------------------------ 块 / 屏排布

    private data class Block(val y0: Int, val y1: Int, val rowStarts: List<Int>)

    private fun buildBlocks(): List<Block> {
        val result = ArrayList<Block>()
        var y = 0
        while (y < rows) {
            val y1 = minOf(y + blockRows, rows)
            result.add(Block(y, y1, rowPageStarts(y, y1)))
            y = y1
        }
        return result
    }

    /** 块内屏起点：固定 4 屏，相邻屏重叠 1 点行（推进 bandRows-1）；末屏吃到块尾。 */
    private fun rowPageStarts(y0: Int, y1: Int): List<Int> {
        val starts = ArrayList<Int>()
        var p = y0
        while (p < y1 && starts.size < BLOCK_SCREENS) {
            starts.add(p)
            p += bandRows - 1
        }
        return starts
    }

    /** 沿横向排细节窗起点：理想边界附近优先找整列空白缝；找不到退到理想边界前 1 点。 */
    private fun computeStarts(size: Int, window: Int, flags: BooleanArray): List<Int> {
        val starts = ArrayList<Int>()
        var p = 0
        while (p < size) {
            starts.add(p)
            val ideal = minOf(p + window, size)
            if (ideal >= size) break
            val seam = TactileStripSlicer.blankSeam(flags, p, ideal, window, size)
            val next = if (seam >= 0) seam else ideal - 1
            if (next <= p) break
            p = next
        }
        return starts
    }

    /** 当前块内当前屏对应的细节窗。 */
    private fun currentWindow(): DetailWindow {
        val block = blocks[blockIndex]
        val ry = block.rowStarts[blockPageIndex]
        val x = colStarts[colIndex]
        val h = minOf(bandRows, block.y1 - ry)
        val w = minOf(windowCols, cols - x)
        return DetailWindow(colIndex, blockPageIndex, x, ry, w, h)
    }

    private fun syncOrigin() {
        val window = currentWindow()
        originCol = window.x0
        originRow = window.y0
    }

    /** 全部细节窗，块内屏行优先摊平；供覆盖 / 重叠断言。 */
    fun detailPages(): List<DetailWindow> {
        val pages = ArrayList<DetailWindow>()
        blocks.forEach { block ->
            block.rowStarts.forEachIndexed { ri, y ->
                val h = minOf(bandRows, block.y1 - y)
                colStarts.forEachIndexed { ci, x ->
                    val w = minOf(windowCols, cols - x)
                    pages.add(DetailWindow(ci, ri, x, y, w, h))
                }
            }
        }
        return pages
    }

    /** 全部屏数（跨块摊平），供旧口径兼容播报。 */
    val totalBands: Int get() = blocks.sumOf { it.rowStarts.size } * colStarts.size

    // ------------------------------------------------------------------ 块内翻页

    /** 翻到块内下一屏；已在块末返回 false，信号 [Boundary.BLOCK_END]。 */
    fun nextPage(): MoveResult {
        if (mode != ViewportMode.DETAIL) return MoveResult(false, null)
        val block = blocks[blockIndex]
        if (blockPageIndex >= block.rowStarts.size - 1) {
            return MoveResult(false, Boundary.BLOCK_END)
        }
        blockPageIndex++
        syncOrigin()
        return MoveResult(true, null)
    }

    /** 回到块内上一屏；已在块首返回 false，信号 [Boundary.BLOCK_START]。 */
    fun prevPage(): MoveResult {
        if (mode != ViewportMode.DETAIL) return MoveResult(false, null)
        if (blockPageIndex == 0) return MoveResult(false, Boundary.BLOCK_START)
        blockPageIndex--
        syncOrigin()
        return MoveResult(true, null)
    }

    /** 换到下一块并回到该块首屏；已在最后一块返回 false，信号 [Boundary.LAST_BLOCK]。 */
    fun nextBlock(): MoveResult {
        if (mode != ViewportMode.DETAIL) return MoveResult(false, null)
        if (blockIndex >= blocks.size - 1) return MoveResult(false, Boundary.LAST_BLOCK)
        blockIndex++
        blockPageIndex = 0
        colIndex = 0
        syncOrigin()
        return MoveResult(true, null)
    }

    /** 回到上一块并回到该块首屏；已在第一块返回 false，信号 [Boundary.FIRST_BLOCK]。 */
    fun prevBlock(): MoveResult {
        if (mode != ViewportMode.DETAIL) return MoveResult(false, null)
        if (blockIndex == 0) return MoveResult(false, Boundary.FIRST_BLOCK)
        blockIndex--
        blockPageIndex = 0
        colIndex = 0
        syncOrigin()
        return MoveResult(true, null)
    }

    // ------------------------------------------------------------------ 横向平移

    /** 左移一个横向窗；已到左边界返回 false 且状态不变。 */
    fun panLeft(): MoveResult {
        if (mode != ViewportMode.DETAIL) return MoveResult(false, null)
        if (colIndex == 0) return MoveResult(false, Boundary.LEFT)
        colIndex--
        syncOrigin()
        return MoveResult(true, null)
    }

    /** 右移一个横向窗；已到右边界返回 false 且状态不变。 */
    fun panRight(): MoveResult {
        if (mode != ViewportMode.DETAIL) return MoveResult(false, null)
        if (colIndex == colStarts.size - 1) return MoveResult(false, Boundary.RIGHT)
        colIndex++
        syncOrigin()
        return MoveResult(true, null)
    }

    // ------------------------------------------------------------------ 总览与选择框

    /**
     * 移动总览选择框：[dxCells] 按方（1 方 = 2 点列），[dyBands] 按块（1 块 = blockRows 点行）。
     * 出界方向钳到边缘并返回对应边界信号；未受阻返回 null。
     */
    fun moveBox(dxCells: Int, dyBands: Int): MoveResult {
        val nextCol = (boxCol + dxCells * 2).coerceIn(0, maxCol())
        val nextRow = (boxRow + dyBands * blockRows).coerceIn(0, maxRow())
        var boundary: Boundary? = null
        if (dxCells != 0 && nextCol == boxCol) {
            boundary = if (dxCells < 0) Boundary.LEFT else Boundary.RIGHT
        }
        if (dyBands != 0 && nextRow == boxRow) {
            boundary = if (dyBands < 0) Boundary.TOP else Boundary.BOTTOM
        }
        val moved = nextCol != boxCol || nextRow != boxRow
        boxCol = nextCol
        boxRow = nextRow
        return MoveResult(moved, boundary)
    }

    private fun maxCol(): Int = maxOf(0, cols - boxCols)
    private fun maxRow(): Int = maxOf(0, rows - boxRows)

    /** 从总览进入选择框所在块的第 1 屏原分辨率细节视图。 */
    fun enterDetail() {
        blockIndex = blocks.indexOfLast { it.y0 <= boxRow }.coerceAtLeast(0)
        blockPageIndex = 0
        colIndex = 0
        syncOrigin()
        mode = ViewportMode.DETAIL
    }

    /** 从任意细节位置一键返回总览；选择框回到当前块位置。 */
    fun backToOverview() {
        boxCol = originCol.coerceIn(0, maxCol())
        boxRow = blocks[blockIndex].y0.coerceIn(0, maxRow())
        mode = ViewportMode.OVERVIEW
    }

    // ------------------------------------------------------------------ 点位打包

    /**
     * 总览字节：整图多数表决压成一屏（[windowCols] × bandRows）。这是全流程唯一允许降采样
     * 的一页；点位里不含「总览」字样，标记走 [overviewLabel]。
     */
    fun overviewBytes(): ByteArray {
        val overview = downsample()
        return TactileStripSlicer.toBytes(overview, windowCols, 0, 0, bandRows, spec)
    }

    /** 细节窗字节：从全局点阵只裁不缩放，长度恒为 linesPerScreen × cellsPerLine。 */
    fun windowBytes(): ByteArray {
        val window = currentWindow()
        return TactileStripSlicer.toBytes(ink, cols, window.x0, window.y0, window.height, spec)
    }

    /**
     * 细节窗裁剪出的全局点阵副本（行优先，宽 [windowCols]、高为实际占用行）。
     * 与字节页同源同序，便于单测在点级断言重叠 / 不丢墨；UI 不直接消费本方法。
     */
    fun windowInk(): BooleanArray {
        val window = currentWindow()
        val out = BooleanArray(windowCols * window.height)
        for (y in 0 until window.height) {
            val gy = window.y0 + y
            for (x in 0 until windowCols) {
                val gx = window.x0 + x
                out[y * windowCols + x] = gx < cols && gy < rows && ink[gy * cols + gx]
            }
        }
        return out
    }

    /** 当前屏在全图摊平后的序号（1 基）；总览页为 0。 */
    fun currentBandIndex(): Int {
        if (mode != ViewportMode.DETAIL) return 0
        var before = 0
        for (i in 0 until blockIndex) before += blocks[i].rowStarts.size
        return (before + blockPageIndex + 1) * colStarts.size
    }

    /** 把全局点阵按块多数表决压成总览尺寸的二值点阵。 */
    private fun downsample(): BooleanArray {
        val out = BooleanArray(windowCols * bandRows)
        for (oy in 0 until bandRows) {
            val sy0 = oy * rows / bandRows
            val sy1 = maxOf(sy0 + 1, (oy + 1) * rows / bandRows)
            for (ox in 0 until windowCols) {
                val sx0 = ox * cols / windowCols
                val sx1 = maxOf(sx0 + 1, (ox + 1) * cols / windowCols)
                var inkCount = 0
                var total = 0
                for (sy in sy0 until minOf(sy1, rows)) {
                    for (sx in sx0 until minOf(sx1, cols)) {
                        total++
                        if (ink[sy * cols + sx]) inkCount++
                    }
                }
                out[oy * windowCols + ox] = total > 0 && inkCount * 2 >= total
            }
        }
        return out
    }

    companion object {
        /** 每块固定屏数（验收口径：块内 2–4 屏，取上限 4）。 */
        const val BLOCK_SCREENS: Int = 4

        /** 总览页的 TTS / 文本标记，不进点位字节。 */
        const val OVERVIEW_LABEL: String = "总览"
    }
}
