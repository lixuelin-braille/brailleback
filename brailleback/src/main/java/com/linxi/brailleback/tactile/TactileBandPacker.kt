package com.linxi.brailleback.tactile

/**
 * **已归档，不再投入**（P2 结论，见 docs 73）。
 *
 * 实测结论：在 7 类样本 × 3 规格上，40×1 切片带已填满 `bandRows`，不满足「合并后总高
 * ≤ 屏高」的条件，带数零下降，无压缩空间；单行设备一页的物理上限由 `bandRows` 决定，
 * 不存在靠合并薄带减少翻页次数的空间。类与既有测试保留不删，避免破坏既有引用；后续
 * 不再为本类加功能或调参。
 *
 * 与视口的关系：P0 单行视口（[TactileViewport]）是在全局点阵上做**二维平移浏览**，
 * 按窗格而非薄带组织内容，不需要把相邻带合并；本类面向的「切片后纵向薄带合并」场景
 * 在视口模型下不成立。多行设备仍走三段式固定 Pager，也不经本类。
 *
 * 历史背景（保留备查）：单行点显设备（`linesPerScreen = 1`）上，[TactileStripSlicer]
 * 按固定带高切缝，遇到空白行分段时会把一张图切成很多薄带，翻页次数偏多（上阶段验收：
 * 40×1 八点含墨带 10–39、六点 16–78）。本类原拟在切片结果**之后**做一次保守合并：
 * 把同一横向段、中间只隔空白行的相邻薄带并成一带，不跨含墨行、不丢图。
 *
 * 硬性保证（历史实现，归档后不再维护）：
 * - 合并前后被覆盖的含墨行并集不变（只允许跨过整行无墨的空白行）；
 * - 合并带字节由全局点阵在合并行区间内重新打包，不沿用旧带字节拼接；
 * - 合并后总高不超过 [Config.maxBandRows]（默认等于切片带高 [SliceResult.bandRows]）；
 * - [Config.enabled] 默认 false：默认成书路径不经过本类，既有切片 / 成书测试不变。
 */
@Deprecated(
    message = "已归档：实测 40×1 带已填满 bandRows，无压缩空间；单行页数物理上限由 bandRows 决定。" +
        "保留类与测试避免破坏引用，不再投入。视口为二维平移，不需要合并带。",
    level = DeprecationLevel.WARNING,
)
object TactileBandPacker {

    /**
     * @param enabled 总开关。false 时 [pack] 原样返回切片结果。
     * @param maxBandRows 合并后一带允许的最大点行数；null 取切片结果的 [SliceResult.bandRows]。
     */
    data class Config(
        val enabled: Boolean = false,
        val maxBandRows: Int? = null,
    ) {
        init {
            require(maxBandRows == null || maxBandRows > 0) {
                "maxBandRows 必须为正数或 null，实际为 $maxBandRows"
            }
        }

        companion object {
            val OFF = Config()
        }
    }

    /**
     * 合并可合并的相邻薄带。
     *
     * @deprecated 已归档，无实测收益；保留方法签名避免破坏既有测试与引用，不再投入。
     *
     * @param ink 全局二值点阵，行优先，长度 cols × rows。
     * @param cols 全局点列数。
     * @param rows 全局点行数。
     * @param slice 切片结果（通常先经 `TactileStripBookBuilder.stripEmpty` 剔空带）。
     * @param spec 点显版面，用于按合并区间重新打包字节。
     * @param config 开关与合并上限。
     */
    fun pack(
        ink: BooleanArray,
        cols: Int,
        rows: Int,
        slice: SliceResult,
        spec: ImageStripEncoder.Spec,
        config: Config = Config.OFF,
    ): SliceResult {
        require(cols > 0 && rows > 0) { "点阵宽高必须为正数，实际为 ${cols}×$rows" }
        require(ink.size == cols * rows) {
            "点阵长度 ${ink.size} 不等于 ${cols}×$rows"
        }
        if (!config.enabled || slice.bands.isEmpty()) return slice

        val rowInk = BooleanArray(rows) { r ->
            var any = false
            for (c in 0 until cols) {
                if (ink[r * cols + c]) { any = true; break }
            }
            any
        }
        val maxRows = config.maxBandRows ?: slice.bandRows

        // 按横向段起点分组：只有同一段（x0 相同）的上下薄带才可能合并。
        val bySegment = slice.bands.groupBy { it.x0 }
        val merged = ArrayList<Band>()
        bySegment.forEach { (_, segmentBands) ->
            val ordered = segmentBands.sortedBy { it.y0 }
            var acc: Band? = null
            for (band in ordered) {
                val head = acc
                if (head == null) {
                    acc = band
                    continue
                }
                val gapStart = head.y0 + head.height
                val gapEnd = band.y0 // 不含 band.y0
                val gapBlank = (gapStart until gapEnd).all { r -> !rowInk[r] }
                val mergedHeight = band.y0 + band.height - head.y0
                val eligible = gapBlank && mergedHeight <= maxRows
                acc = if (eligible) {
                    val bytes = TactileStripSlicer.toBytes(
                        ink, cols, head.x0, head.y0, mergedHeight, spec
                    )
                    Band(
                        bytes = bytes,
                        x0 = head.x0,
                        y0 = head.y0,
                        height = mergedHeight,
                        overlapTop = head.overlapTop,
                        overlapBottom = band.overlapBottom,
                        bandIndex = 0,
                        total = 0,
                    )
                } else {
                    merged.add(head)
                    band
                }
            }
            acc?.let { merged.add(it) }
        }

        // 恢复行-major 出页顺序：先按行起点，再按列起点。
        merged.sortWith(compareBy({ it.y0 }, { it.x0 }))
        val total = merged.size
        val reindexed = merged.mapIndexed { i, band ->
            band.copy(bandIndex = i + 1, total = total)
        }
        return slice.copy(bands = reindexed, totalBands = total)
    }
}
