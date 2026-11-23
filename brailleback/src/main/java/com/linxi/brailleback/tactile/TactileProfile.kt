package com.linxi.brailleback.tactile

import kotlin.math.roundToInt

/**
 * 为什么需要它：
 * 触觉图（可触摸的凸起图）的质量几乎全由几个物理量决定——打印机的分辨率、页面有多大、
 * 两条凸线之间至少留多少毫米才摸得出来。此前这些数字散落在导出排版里，且只有「方 × 行」，
 * 没有毫米。这里把「物理世界」集中成一处配置：页面毫米数是唯一尺寸真源，像素数一律由 dpi 推导，
 * 所以同一份设置换一台不同 dpi 的打印机，出来的图物理尺寸不变。
 */
data class TactileProfile(
    /** 打印机 / 设备分辨率。像素数由 dpi 与毫米换算得出，不单独存。 */
    val dpi: Int = 300,
    /** 页面宽（毫米）。A4 = 210。 */
    val pageWidthMm: Double = 210.0,
    /** 页面高（毫米）。A4 = 297。 */
    val pageHeightMm: Double = 297.0,
    /** 两条凸起之间至少的间距（毫米）。小于它的两条线在指尖上会并成一条。 */
    val minFeatureSpacingMm: Double = 0.9,
    /** 凸线的最小线宽（毫米）。太细的线压不出来。 */
    val minLineWidthMm: Double = 0.5,
    /** 贴在触觉图上的盲文标签的度量（点、方、行）。 */
    val labelMetrics: BrailleTagMetrics = BrailleTagMetrics(),
) {

    init {
        // 非正数只会让后续所有换算变成 0 或负数，早点抛比输出一张空白纸好。
        require(dpi > 0) { "dpi 必须为正数，实际为 $dpi" }
        require(pageWidthMm > 0.0) { "页面宽必须为正数，实际为 $pageWidthMm mm" }
        require(pageHeightMm > 0.0) { "页面高必须为正数，实际为 $pageHeightMm mm" }
        require(minFeatureSpacingMm > 0.0) { "最小特征间距必须为正数，实际为 $minFeatureSpacingMm mm" }
        require(minLineWidthMm > 0.0) { "最小线宽必须为正数，实际为 $minLineWidthMm mm" }
    }

    /** 每毫米多少像素。所有 mm→px 换算都从这里出发，不另算一份。 */
    val pxPerMm: Double get() = dpi / MM_PER_INCH

    /** 页面宽对应的像素数。 */
    val pageWidthPx: Int get() = mmToPx(pageWidthMm)

    /** 页面高对应的像素数。 */
    val pageHeightPx: Int get() = mmToPx(pageHeightMm)

    /** BMP 头里要写的「每米像素数」（像素/米，BMP 规范用 ppm，不是 dpi）。 */
    val pixelsPerMeter: Int get() = (dpi / (MM_PER_INCH / 1000.0)).roundToInt()

    /** 毫米 → 像素。四舍五入，避免整体尺寸被系统性缩半像素。 */
    fun mmToPx(mm: Double): Int = (mm * pxPerMm).roundToInt()

    /** 像素 → 毫米，用于把已算好的栅格反推回物理尺寸。 */
    fun pxToMm(px: Int): Double = px / pxPerMm

    companion object {
        const val MM_PER_INCH: Double = 25.4

        /** A4 竖版：210 × 297 mm，对应导出排版里的「30 方 × 25 行」默认页。 */
        val A4: TactileProfile = TactileProfile()

        /** 与外部参考软件默认一致的「无页面参数时」长边长度。 */
        const val DEFAULT_LONG_EDGE_MM: Double = 160.0
    }
}

/**
 * 为什么需要它：
 * 触觉图上除了图形，还要有盲文标签说明「这是什么」。盲文的可读性由点距、方距、行距决定，
 * 换打印机这些毫米值也不该跟着变，所以单独成一组常量，而不是散在排版代码里。
 */
data class BrailleTagMetrics(
    /** 点半径（毫米）。太大相邻点会粘连。 */
    val dotRadiusMm: Double = 0.75,
    /** 同一方内相邻两点的中心距（毫米）。 */
    val dotPitchMm: Double = 2.5,
    /** 相邻两方的中心距（方距，毫米）。 */
    val cellPitchMm: Double = 6.0,
    /** 相邻两行的中心距（行距，毫米）。与现有导出排版的行距一致。 */
    val linePitchMm: Double = 10.0,
) {

    init {
        require(dotRadiusMm > 0.0) { "点半径必须为正数，实际为 $dotRadiusMm mm" }
        require(dotPitchMm > 0.0) { "点距必须为正数，实际为 $dotPitchMm mm" }
        require(cellPitchMm > dotPitchMm) { "方距必须大于点距，实际方距 $cellPitchMm mm、点距 $dotPitchMm mm" }
        require(linePitchMm > 0.0) { "行距必须为正数，实际为 $linePitchMm mm" }
    }
}
