package com.linxi.brailleback.tactile

/**
 * 线宽归一三态开关。
 *
 * - [OFF]：不做任何改动，原样返回输入点阵，用于对比与回归。
 * - [AUTO]：生产默认。同时满足两道门控才处理——① [TactileStrokeNormalizer.isPureLineArt]
 *   判定为纯线条图；② [TactileStrokeNormalizer.hasFragmentationSign] 检出断裂迹象。无断裂
 *   迹象的完整轮廓（圆 / 方框 / 闭合字形）原样返回，逐字节不变。
 * - [FORCE]：绕过两道判定直接处理，仅用于调试 / 度量对照，生产入口不使用。
 */
enum class StockNormalizerMode { OFF, AUTO, FORCE }

/**
 * 为什么需要它：
 * [ImageStripEncoder.dotGrid] 把灰度块均值二值化后，1 点宽的细线在降采样 / 斜线处容易
 * 断成孤立点，摸起来是一串散点而不是一条线。本类在二值点阵上做**受限的细线加固**：
 * 先把断成两格间隔的孤立点接回线段，再只对「细笔画连通域」沿其法向单侧补点；粗块
 * （含 2×2 实心块的连通域）与高墨密度区域整体不参与膨胀，避免把密集细墙互相填满。
 *
 * 定位：纯 Kotlin 点阵预处理，不碰 Android、不碰灰度图解码。生产流水在
 * `TactileStripSession.build` 读图二值化之后、切片之前调用；默认 [StockNormalizerMode.AUTO]，
 * 门控不命中时原样返回输入数组，既有切片 / 成书路径零回归。
 *
 * 参考：Brailix 的线宽归一发生在矢量描边时刻——中心线沿线盖圆盘，半径由
 * `min_line_width_mm` 换算。我们手里只有二值点阵、没有矢量中心线，所以这里用
 * 「断裂门控 + 细连通域判定 + 方向感知法向补点 + 实心块 / 密度兜底」近似同一目标。
 */
object TactileStrokeNormalizer {

    /**
     * 纯线条图判定阈值（[isPureLineArt] 使用，写成常量便于单测与调参）：
     *
     * - [MAX_INK_RATIO]：整图墨点占比上限。超过即判定为非线条图（照片 / 大面积填充），
     *   不启用归一。线条轮廓图墨占比通常远低于此；实心填充图会明显抬高占比。
     * - [MAX_SOLID_BLOCK_RATIO]：2×2 实心块数量占全部 2×2 窗口的比例上限。线条图只在
     *   笔画交叉处偶尔出现 2×2；大面积实心块 / 粗墙会显著抬高该比例。
     */
    const val MAX_INK_RATIO: Double = 0.35
    const val MAX_SOLID_BLOCK_RATIO: Double = 0.08

    /**
     * 断裂迹象门控阈值（[hasFragmentationSign] 使用，写成常量便于单测与调参）：
     *
     * - [MIN_ISOLATED_DOTS]：8 邻域墨点 ≤1 的孤立点达到该数量，判为有断裂迹象。完整闭合
     *   轮廓的孤立点通常为 0；降采样断线图会散出一串孤立点。
     * - [MIN_SINGLETON_COMPONENTS]：4 邻域大小为 1 的孤立连通域达到该数量，判为有断裂
     *   迹象。与孤立点计数互为补充：个别邻点粘连的断线仍可能留下单格碎域。
     */
    const val MIN_ISOLATED_DOTS: Int = 8
    const val MIN_SINGLETON_COMPONENTS: Int = 5

    /** 断线搭桥允许跨越的最大格距；超过则视为两段无关墨迹，不连接。 */
    const val MAX_BRIDGE_GAP: Int = 4

    /**
     * 归一化配置。
     *
     * @param mode 三态开关，默认 [StockNormalizerMode.AUTO]。
     * @param maxPasses 补点迭代轮数。1 轮足以把 1 点宽线补到 2 点宽；多轮用于更细的
     *   退化样本，轮数越高粗块被波及的风险越大。
     * @param maxGrowthRatio 新增墨点占原墨点数量的比例硬上限。一轮补点预计新增超过该
     *   比例时放弃本轮、返回未膨胀点阵。粗块不过度膨胀主要靠「粗连通域不参与 + 不产生
     *   新实心块」保证，本比例只是总量兜底。
     */
    data class Config(
        val mode: StockNormalizerMode = StockNormalizerMode.AUTO,
        val maxPasses: Int = 1,
        val maxGrowthRatio: Double = 2.0,
    ) {
        init {
            require(maxPasses in 0..3) { "maxPasses 必须在 0..3，实际为 $maxPasses" }
            require(maxGrowthRatio in 0.0..2.0) {
                "maxGrowthRatio 必须在 0..2，实际为 $maxGrowthRatio"
            }
        }

        companion object {
            /** 对比 / 回归用：完全关闭。 */
            val OFF = Config(StockNormalizerMode.OFF)

            /** 生产默认：纯线条图且有断裂迹象才处理。 */
            val AUTO = Config()

            /** 调试 / 度量对照用：绕过判定直接处理。 */
            val FORCE = Config(StockNormalizerMode.FORCE)
        }
    }

    /**
     * 判断一张二值点阵是否为「纯线条图」（轮廓 / 线稿），供 AUTO 门控①决定是否启用归一。
     * 纯函数，不修改入参，可在 JVM 单测里直接断言。
     *
     * 判据（两条任一命中即判定为非线条图）：
     * 1. 墨点占比 > [MAX_INK_RATIO]；
     * 2. 2×2 实心块占比 > [MAX_SOLID_BLOCK_RATIO]。
     * 全白图视为纯线条图（归一阶段本就空图不变）。
     *
     * @param ink 二值点阵，行优先，true = 有凸点，长度 cols × rows。
     * @param cols 点列数。
     * @param rows 点行数。
     */
    fun isPureLineArt(ink: BooleanArray, cols: Int, rows: Int): Boolean {
        require(cols > 0 && rows > 0) { "点阵宽高必须为正数，实际为 ${cols}×$rows" }
        require(ink.size == cols * rows) {
            "点阵长度 ${ink.size} 不等于 ${cols}×$rows"
        }
        val total = cols * rows
        val inkCount = ink.count { it }
        if (inkCount == 0) return true
        if (inkCount.toDouble() / total > MAX_INK_RATIO) return false

        val blockCols = (cols - 1).coerceAtLeast(1)
        val blockRows = (rows - 1).coerceAtLeast(1)
        var solidBlocks = 0
        for (y in 0 until rows - 1) {
            for (x in 0 until cols - 1) {
                val i00 = y * cols + x
                if (ink[i00] && ink[i00 + 1] && ink[i00 + cols] && ink[i00 + cols + 1]) {
                    solidBlocks++
                }
            }
        }
        return solidBlocks.toDouble() / (blockCols * blockRows) <= MAX_SOLID_BLOCK_RATIO
    }

    /**
     * 判断一张二值点阵是否有「断裂迹象」，供 AUTO 门控②决定是否启用归一。纯函数。
     *
     * 两条判据任一命中即视为有断裂：
     * 1. 8 邻域墨点 ≤1 的孤立点 ≥ [MIN_ISOLATED_DOTS]；
     * 2. 4 邻域单格连通域（大小为 1）≥ [MIN_SINGLETON_COMPONENTS]。
     * 无断裂迹象的完整轮廓直接返回 false，归一阶段原样返回，逐字节不变。
     */
    fun hasFragmentationSign(ink: BooleanArray, cols: Int, rows: Int): Boolean {
        require(cols > 0 && rows > 0) { "点阵宽高必须为正数，实际为 ${cols}×$rows" }
        require(ink.size == cols * rows) {
            "点阵长度 ${ink.size} 不等于 ${cols}×$rows"
        }
        val isolated = countIsolatedDots(ink, cols, rows)
        if (isolated >= MIN_ISOLATED_DOTS) return true
        return countSingletonComponents(ink, cols, rows) >= MIN_SINGLETON_COMPONENTS
    }

    /**
     * 对点阵做线宽归一。
     *
     * @param ink 二值点阵，行优先，true = 有凸点，长度 cols × rows。
     * @param cols 点列数。
     * @param rows 点行数。
     * @param config 三态开关与参数；[StockNormalizerMode.OFF] 或 AUTO 门控不命中时返回
     *   原数组本身（不复制、不改写）。
     * @return 归一后的新点阵。
     */
    fun normalize(ink: BooleanArray, cols: Int, rows: Int, config: Config = Config.OFF): BooleanArray {
        require(cols > 0 && rows > 0) { "点阵宽高必须为正数，实际为 ${cols}×$rows" }
        require(ink.size == cols * rows) {
            "点阵长度 ${ink.size} 不等于 ${cols}×$rows"
        }
        if (config.mode == StockNormalizerMode.OFF) return ink
        val originalInk = ink.count { it }
        if (originalInk == 0) return ink

        if (config.mode == StockNormalizerMode.AUTO) {
            if (!isPureLineArt(ink, cols, rows)) return ink
            if (!hasFragmentationSign(ink, cols, rows)) return ink
        }

        var current = ink.copyOf()
        // 第一步：把两格间隔的孤立点接回线段（断线修复），再做线宽加固。
        val bridged = bridgeGaps(current, cols, rows, originalInk, config.maxGrowthRatio)
        if (bridged != null) current = bridged

        repeat(config.maxPasses) {
            val next = dilateThinStrokes(current, cols, rows) ?: return current
            val added = next.count { it } - current.count { it }
            if (added > originalInk * config.maxGrowthRatio) return current
            current = next
        }
        return current
    }

    /** 统计 8 邻域墨点 ≤1 的孤立点数量。 */
    private fun countIsolatedDots(ink: BooleanArray, cols: Int, rows: Int): Int {
        var isolated = 0
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val idx = y * cols + x
                if (!ink[idx]) continue
                if (neighborCount8(ink, cols, rows, x, y) <= 1) isolated++
            }
        }
        return isolated
    }

    /** 统计 4 邻域大小为 1 的孤立连通域数量。 */
    private fun countSingletonComponents(ink: BooleanArray, cols: Int, rows: Int): Int {
        val label = IntArray(cols * rows) { -1 }
        var singletons = 0
        for (sy in 0 until rows) {
            for (sx in 0 until cols) {
                val start = sy * cols + sx
                if (!ink[start] || label[start] >= 0) continue
                val stack = ArrayDeque<Int>()
                stack.addLast(start)
                label[start] = 0
                var size = 0
                while (stack.isNotEmpty()) {
                    val idx = stack.removeLast()
                    size++
                    val px = idx % cols
                    val py = idx / cols
                    val neighbors = intArrayOf(
                        if (px > 0) idx - 1 else -1,
                        if (px < cols - 1) idx + 1 else -1,
                        if (py > 0) idx - cols else -1,
                        if (py < rows - 1) idx + cols else -1,
                    )
                    neighbors.forEach { n ->
                        if (n >= 0 && ink[n] && label[n] < 0) {
                            label[n] = 0
                            stack.addLast(n)
                        }
                    }
                }
                if (size == 1) singletons++
            }
        }
        return singletons
    }

    /** 8 邻域墨点计数。 */
    private fun neighborCount8(ink: BooleanArray, cols: Int, rows: Int, x: Int, y: Int): Int {
        var n = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) continue
                if (ink[ny * cols + nx]) n++
            }
        }
        return n
    }

    /** 8 个罗盘方向的单位位移。 */
    private val bridgeDirs = arrayOf(
        1 to 0, -1 to 0, 0 to 1, 0 to -1,
        1 to 1, 1 to -1, -1 to 1, -1 to -1,
    )

    /** 断线搭桥：孤立（或近孤立）墨点沿 8 个方向在 [MAX_BRIDGE_GAP] 格内寻找另一头墨点，
     *  把方向线段上的空格补齐，接成连续线段。只补断线空隙，不延长线端；新增点不得构成
     *  新的 2×2 实心块；没有可补点时返回 null。 */
    private fun bridgeGaps(
        ink: BooleanArray,
        cols: Int,
        rows: Int,
        originalInk: Int,
        maxGrowthRatio: Double,
    ): BooleanArray? {
        val additions = LinkedHashSet<Int>()
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val idx = y * cols + x
                if (!ink[idx] || neighborCount8(ink, cols, rows, x, y) > 1) continue
                for ((dx, dy) in bridgeDirs) {
                    var farX = x
                    var farY = y
                    var found = false
                    for (step in 1..MAX_BRIDGE_GAP) {
                        farX += dx
                        farY += dy
                        if (farX < 0 || farX >= cols || farY < 0 || farY >= rows) break
                        if (ink[farY * cols + farX]) { found = true; break }
                    }
                    if (!found) continue
                    // 沿方向线段补齐中间空格。
                    for (step in 1 until maxOf(kotlin.math.abs(farX - x), kotlin.math.abs(farY - y))) {
                        val mx = x + dx * step
                        val my = y + dy * step
                        val mid = my * cols + mx
                        if (!ink[mid] && !createsSolidBlock(ink, cols, rows, mx, my)) {
                            additions.add(mid)
                        }
                    }
                }
            }
        }
        if (additions.isEmpty()) return null
        if (additions.size > originalInk * maxGrowthRatio) return null

        val next = ink.copyOf()
        additions.forEach { next[it] = true }
        return next
    }

    /**
     * 一轮方向感知的受限膨胀：只给细笔画连通域的墨点沿法向补点。
     * - 含 2×2 实心块的连通域判为粗块，整域不参与；
     * - 有横向邻域 → 按横笔画处理，只补下方；有纵向邻域 → 只补右方；孤立点不补；
     * - 新增点不得与现有墨点构成新的 2×2 实心块，避免把缝填成粗块。
     *
     * @return 新点阵；若没有任何可补点则返回 null（调用方据此停止迭代）。
     */
    private fun dilateThinStrokes(
        ink: BooleanArray,
        cols: Int,
        rows: Int,
    ): BooleanArray? {
        val component = labelComponents(ink, cols, rows)
        val thick = BooleanArray(component.size)
        // 含 2×2 实心块的连通域判为粗块。
        for (y in 0 until rows - 1) {
            for (x in 0 until cols - 1) {
                val i00 = y * cols + x
                if (ink[i00] && ink[i00 + 1] && ink[i00 + cols] && ink[i00 + cols + 1]) {
                    thick[component[i00]] = true
                }
            }
        }

        val additions = ArrayList<Int>()
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val idx = y * cols + x
                if (!ink[idx] || thick[component[idx]]) continue

                val left = x > 0 && ink[idx - 1]
                val right = x < cols - 1 && ink[idx + 1]
                val up = y > 0 && ink[idx - cols]
                val down = y < rows - 1 && ink[idx + cols]

                val horizontal = left || right
                // 单侧补点：1 点宽线补到 2 点宽即可，不双侧外扩成 3 点宽，避免密集墙互相填满。
                val candidates: List<Pair<Int, Int>> = when {
                    horizontal -> listOf(x to y + 1)
                    up || down -> listOf(x + 1 to y)
                    else -> emptyList()
                }

                for ((nx, ny) in candidates) {
                    if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) continue
                    val nIdx = ny * cols + nx
                    if (ink[nIdx]) continue
                    // 不填补两条平行笔画之间的空隙：候选点法向两侧都已有墨时不落点，
                    // 避免密集细墙被互相填满成块。
                    val sandwiched = if (horizontal) {
                        ny > 0 && ink[(ny - 1) * cols + nx] &&
                            ny < rows - 1 && ink[(ny + 1) * cols + nx]
                    } else {
                        nx > 0 && ink[ny * cols + nx - 1] &&
                            nx < cols - 1 && ink[ny * cols + nx + 1]
                    }
                    if (sandwiched) continue
                    if (createsSolidBlock(ink, cols, rows, nx, ny)) continue
                    additions.add(nIdx)
                }
            }
        }
        if (additions.isEmpty()) return null

        val next = ink.copyOf()
        additions.forEach { next[it] = true }
        return next
    }

    /** 4 邻域连通域标号；返回与点阵等长的域编号数组。 */
    private fun labelComponents(ink: BooleanArray, cols: Int, rows: Int): IntArray {
        val label = IntArray(cols * rows) { -1 }
        var nextLabel = 0
        for (sy in 0 until rows) {
            for (sx in 0 until cols) {
                val start = sy * cols + sx
                if (!ink[start] || label[start] >= 0) continue
                val stack = ArrayDeque<Int>()
                stack.addLast(start)
                label[start] = nextLabel
                while (stack.isNotEmpty()) {
                    val idx = stack.removeLast()
                    val px = idx % cols
                    val py = idx / cols
                    val neighbors = intArrayOf(
                        if (px > 0) idx - 1 else -1,
                        if (px < cols - 1) idx + 1 else -1,
                        if (py > 0) idx - cols else -1,
                        if (py < rows - 1) idx + cols else -1,
                    )
                    neighbors.forEach { n ->
                        if (n >= 0 && ink[n] && label[n] < 0) {
                            label[n] = nextLabel
                            stack.addLast(n)
                        }
                    }
                }
                nextLabel++
            }
        }
        return label
    }

    /** 假设在 (x, y) 落墨，是否会与现有墨点构成某个 2×2 实心块。 */
    private fun createsSolidBlock(
        ink: BooleanArray,
        cols: Int,
        rows: Int,
        x: Int,
        y: Int,
    ): Boolean {
        // 以 (x,y) 为右下、右上、左下、左上四种角色检查 2×2。
        val corners = listOf(
            x - 1 to y - 1, x to y - 1,
            x - 1 to y, x to y,
        )
        for ((bx, by) in corners) {
            if (bx < 0 || by < 0 || bx >= cols - 1 || by >= rows - 1) continue
            val i00 = by * cols + bx
            val a = ink[i00] || (bx == x && by == y)
            val b = ink[i00 + 1] || (bx + 1 == x && by == y)
            val c = ink[i00 + cols] || (bx == x && by + 1 == y)
            val d = ink[i00 + cols + 1] || (bx + 1 == x && by + 1 == y)
            if (a && b && c && d) return true
        }
        return false
    }
}
