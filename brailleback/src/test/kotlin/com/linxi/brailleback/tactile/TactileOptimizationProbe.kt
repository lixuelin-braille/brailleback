/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 优化度量探针（纯 JVM，不引 Android / OCR / Python）：
 * 7 类几何形状 × 代表规格，分别在「关 / 自动 / 强制 / 薄带合并」四档下统计
 * 带数、凸点数、孤立点（8 邻域墨点 ≤1）数与墨点总量，输出到 stdout 供文档记录。
 * 形状构造见 [TactileShapeFixtures]。
 */
package com.linxi.brailleback.tactile

import org.junit.Test

class TactileOptimizationProbe {

    private data class Metrics(
        val bands: Int,
        val dots: Int,
        val isolated: Int,
        val ink: Int,
    )

    private fun neighbors(ink: BooleanArray, cols: Int, rows: Int, x: Int, y: Int): Int {
        var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx
            val ny = y + dy
            if (nx < 0 || nx >= cols || ny < 0 || ny >= rows) continue
            if (ink[ny * cols + nx]) n++
        }
        return n
    }

    private fun evaluate(
        img: GrayImage,
        spec: ImageStripEncoder.Spec,
        mode: StockNormalizerMode,
        packOn: Boolean,
    ): Metrics {
        val cols = spec.dotColumns
        val rows = maxOf(1, Math.round(cols.toFloat() * img.height.toFloat() / img.width))
        val rawInk = ImageStripEncoder.dotGrid(img, cols, rows, spec.threshold)
        val ink = TactileStrokeNormalizer.normalize(
            rawInk, cols, rows, TactileStrokeNormalizer.Config(mode = mode)
        )

        val rawSlice = TactileStripSlicer.slice(ink, cols, rows, spec)
        val sliced = if (packOn) {
            TactileBandPacker.pack(
                ink, cols, rows, rawSlice, spec,
                TactileBandPacker.Config(enabled = true),
            )
        } else rawSlice
        val slice = TactileStripBookBuilder.stripEmpty(sliced)

        var dots = 0
        slice.bands.forEach { band ->
            band.bytes.forEach { b -> dots += Integer.bitCount(b.toInt() and 0xFF) }
        }
        var isolated = 0
        for (y in 0 until rows) for (x in 0 until cols) {
            if (ink[y * cols + x] && neighbors(ink, cols, rows, x, y) <= 1) isolated++
        }
        return Metrics(slice.totalBands, dots, isolated, ink.count { it })
    }

    @Test
    fun optimizationProbe() {
        val shapes = TactileShapeFixtures.all()
        val specs = listOf(
            ImageStripEncoder.Spec.forDisplay(40, 1, 8),
            ImageStripEncoder.Spec.forDisplay(40, 1, 6),
            ImageStripEncoder.Spec.forDisplay(40, 4, 8),
        )
        for ((name, img) in shapes) {
            for (spec in specs) {
                val off = evaluate(img, spec, StockNormalizerMode.OFF, packOn = false)
                val auto = evaluate(img, spec, StockNormalizerMode.AUTO, packOn = false)
                val force = evaluate(img, spec, StockNormalizerMode.FORCE, packOn = false)
                val pack = evaluate(img, spec, StockNormalizerMode.OFF, packOn = true)
                println(
                    "PROBE | $name | ${spec.cellsPerLine}x${spec.linesPerScreen}x${spec.dotsPerCell} | " +
                        "off bands=${off.bands} dots=${off.dots} isolated=${off.isolated} ink=${off.ink} | " +
                        "auto bands=${auto.bands} dots=${auto.dots} isolated=${auto.isolated} ink=${auto.ink} added=${auto.ink - off.ink} | " +
                        "force bands=${force.bands} dots=${force.dots} isolated=${force.isolated} ink=${force.ink} added=${force.ink - off.ink} | " +
                        "pack bands=${pack.bands} dots=${pack.dots}"
                )
            }
        }
    }
}
