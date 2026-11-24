/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 验收方独立可读性探针（纯 JVM，不引 Android / OCR / LaTeX / Python）：
 * 7 类几何形状 + 6 类数学图形，全部手绘像素成 100×100 灰度画布，走真实
 * ImageStripEncoder.dotGrid 二值化 + TactileStripSlicer 切片，输出每配置的
 * 带数、凸起方/点统计与按带拆分的 ASCII 点阵预览，供验收分级对照。
 * 报告落 %TEMP%/strip-acceptance/report.md。
 */
package com.linxi.brailleback.tactile

import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class AcceptanceReadabilityProbe {

    private companion object {
        const val CANVAS = 100
    }

    private val outDir = File(System.getProperty("java.io.tmpdir"), "strip-acceptance").apply { mkdirs() }

    // ---------------------------------------------------------- 手绘像素工具

    private fun canvas(ink: Set<Pair<Int, Int>>): GrayImage =
        GrayImage(CANVAS, CANVAS, IntArray(CANVAS * CANVAS) { i ->
            val x = i % CANVAS
            val y = i / CANVAS
            if (ink.contains(x to y)) 0 else 255
        })

    private fun line(pts: MutableSet<Pair<Int, Int>>, x0: Int, y0: Int, x1: Int, y1: Int) {
        val steps = maxOf(abs(x1 - x0), abs(y1 - y0), 1)
        for (i in 0..steps) {
            val x = Math.round(x0 + (x1 - x0) * i.toDouble() / steps).toInt()
            val y = Math.round(y0 + (y1 - y0) * i.toDouble() / steps).toInt()
            pts.add(x.coerceIn(0, CANVAS - 1) to y.coerceIn(0, CANVAS - 1))
        }
    }

    private fun thick(pts: MutableSet<Pair<Int, Int>>, x0: Int, y0: Int, x1: Int, y1: Int) {
        val steps = maxOf(abs(x1 - x0), abs(y1 - y0), 1)
        for (i in 0..steps) {
            val x = Math.round(x0 + (x1 - x0) * i.toDouble() / steps).toInt()
            val y = Math.round(y0 + (y1 - y0) * i.toDouble() / steps).toInt()
            for (dx in -1..1) for (dy in -1..1) {
                pts.add((x + dx).coerceIn(0, CANVAS - 1) to (y + dy).coerceIn(0, CANVAS - 1))
            }
        }
    }

    private val glyph = mapOf(
        '1' to listOf(".#.", "##.", ".#.", ".#.", "###"),
        '2' to listOf("###", "..#", "###", "#..", "###"),
        'a' to listOf("...", "###", ".#.", "###", ".##"),
        'b' to listOf("#..", "##.", "#.#", "##.", "#.#"),
        'c' to listOf("...", "###", "#..", "#..", "###"),
        'A' to listOf(".#.", "#.#", "###", "#.#", "#.#"),
        'B' to listOf("##.", "#.#", "##.", "#.#", "##."),
        'C' to listOf(".##", "#..", "#..", "#..", ".##"),
    )

    private fun drawGlyph(pts: MutableSet<Pair<Int, Int>>, ch: Char, ox: Int, oy: Int) {
        val rows = glyph[ch] ?: return
        rows.forEachIndexed { ry, row ->
            row.forEachIndexed { rx, c ->
                if (c == '#') pts.add((ox + rx) to (oy + ry))
            }
        }
    }

    // ---------------------------------------------------------- 7 类几何形状

    private fun ring(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        for (y in 0 until CANVAS) for (x in 0 until CANVAS) {
            val d = hypot((x - 50).toDouble(), (y - 50).toDouble())
            if (abs(d - 34.0) <= 2.0) pts.add(x to y)
        }
        return pts
    }

    private fun square(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        for (y in 0 until CANVAS) for (x in 0 until CANVAS) {
            if (y in 18..20 || y in 80..82 || x in 18..20 || x in 80..82) pts.add(x to y)
        }
        return pts
    }

    private fun arrow(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        for (y in 45..55) for (x in 20..62) pts.add(x to y)
        line(pts, 62, 30, 82, 50)
        line(pts, 82, 50, 62, 70)
        return pts
    }

    private fun heart(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        val steps = 400
        for (i in 0..steps) {
            val t = Math.PI * 2 * i / steps
            val hx = 16.0 * Math.pow(sin(t), 3.0)
            val hy = 13.0 * cos(t) - 5.0 * cos(2 * t) - 2.0 * cos(3 * t) - cos(4 * t)
            val x = (50 + hx * 2.4).toInt().coerceIn(0, CANVAS - 1)
            val y = (50 - hy * 2.4).toInt().coerceIn(0, CANVAS - 1)
            pts.add(x to y)
            for (dx in -1..1) for (dy in -1..1) {
                pts.add((x + dx).coerceIn(0, CANVAS - 1) to (y + dy).coerceIn(0, CANVAS - 1))
            }
        }
        return pts
    }

    private fun house(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        line(pts, 25, 45, 25, 85); line(pts, 75, 45, 75, 85); line(pts, 25, 85, 75, 85)
        line(pts, 25, 45, 50, 20); line(pts, 50, 20, 75, 45)
        return pts
    }

    private fun letterA(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        thick(pts, 28, 85, 50, 22); thick(pts, 50, 22, 72, 85)
        for (y in 56..60) for (x in 38..62) pts.add(x to y)
        return pts
    }

    private fun plan(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        line(pts, 15, 15, 85, 15); line(pts, 15, 85, 85, 85)
        line(pts, 15, 15, 15, 85); line(pts, 85, 15, 85, 85)
        line(pts, 55, 22, 55, 45); line(pts, 55, 55, 55, 78)
        line(pts, 38, 85, 48, 85)
        line(pts, 43, 85, 43, 60); line(pts, 43, 60, 55, 50)
        return pts
    }

    // ---------------------------------------------------------- 6 类数学图形

    /** 1 三角形 + 顶点字母 + 直角符号。 */
    private fun mathTriangle(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        line(pts, 25, 75, 75, 75)   // 底边
        line(pts, 25, 75, 25, 30)   // 左边（直角边）
        line(pts, 25, 30, 75, 75)   // 斜边
        // 直角符号：左下角小方角。
        line(pts, 35, 75, 35, 65); line(pts, 25, 65, 35, 65)
        // 顶点字母标注。
        drawGlyph(pts, 'A', 16, 20)
        drawGlyph(pts, 'B', 14, 80)
        drawGlyph(pts, 'C', 76, 80)
        return pts
    }

    /** 2 坐标系（双轴箭头）+ 正弦曲线 + 刻度。 */
    private fun mathCoordinate(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        // x 轴 y=70，y 轴 x=25。
        line(pts, 15, 70, 85, 70)
        line(pts, 25, 80, 25, 18)
        // 箭头。
        line(pts, 85, 70, 78, 66); line(pts, 85, 70, 78, 74)
        line(pts, 25, 18, 21, 25); line(pts, 25, 18, 29, 25)
        // 刻度。
        for (x in 35..80 step 10) line(pts, x, 70, x, 74)
        for (y in 30..65 step 10) line(pts, 25, y, 21, y)
        // 正弦曲线，振幅 18，中线 y=70。
        for (x in 30..80) {
            val y = (70 - 18 * sin((x - 30) / 50.0 * Math.PI * 2)).toInt()
            pts.add(x to y)
        }
        return pts
    }

    /** 3 分数 1/2 + 根号（含被开方横线）。 */
    private fun mathFractionRadical(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        // 分数：横线 y=50，x 30..55；上 1 下 2。
        line(pts, 30, 50, 55, 50)
        drawGlyph(pts, '1', 39, 30)
        drawGlyph(pts, '2', 39, 58)
        // 根号：钩 + 上升斜边 + 顶部横线。
        line(pts, 66, 58, 70, 66)   // 钩
        line(pts, 70, 66, 76, 34)   // 斜边上升
        line(pts, 76, 34, 92, 34)   // 被开方横线
        drawGlyph(pts, 'c', 80, 42) // 被开方内容
        return pts
    }

    /** 4 上下标公式 a²+b²=c²（含上标 2）。 */
    private fun mathSuperscript(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        val baseY = 52
        drawGlyph(pts, 'a', 18, baseY)
        drawGlyph(pts, '2', 27, baseY - 12) // 上标
        drawGlyph(pts, 'b', 42, baseY)
        drawGlyph(pts, '2', 51, baseY - 12)
        drawGlyph(pts, 'c', 66, baseY)
        drawGlyph(pts, '2', 75, baseY - 12)
        // 运算符 + + =。
        line(pts, 34, baseY + 2, 38, baseY + 2); line(pts, 36, baseY, 36, baseY + 4)
        line(pts, 58, baseY + 2, 62, baseY + 2); line(pts, 60, baseY, 60, baseY + 4)
        line(pts, 86, baseY + 1, 90, baseY + 1); line(pts, 86, baseY + 4, 90, baseY + 4)
        return pts
    }

    /** 5 柱状图：轴线 + 三根柱。 */
    private fun mathBarChart(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        line(pts, 20, 20, 20, 82)   // y 轴
        line(pts, 20, 82, 86, 82)  // x 轴
        val bars = listOf(30 to 30, 48 to 46, 66 to 20)
        bars.forEach { (x, h) ->
            for (y in 82 - h until 82) for (bx in x..x + 10) pts.add(bx to y)
        }
        return pts
    }

    /** 6 2×2 小表格：外框 + 分格线 + 格内短标记。 */
    private fun mathTable(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        line(pts, 15, 20, 85, 20); line(pts, 15, 80, 85, 80)
        line(pts, 15, 20, 15, 80); line(pts, 85, 20, 85, 80)
        line(pts, 50, 20, 50, 80)  // 竖分格
        line(pts, 15, 50, 85, 50)  // 横分格
        // 格内短标记。
        line(pts, 25, 32, 38, 32)
        line(pts, 62, 32, 75, 32)
        line(pts, 25, 62, 38, 62)
        line(pts, 62, 62, 75, 62)
        return pts
    }

    // ---------------------------------------------------------- 评估与预览

    private data class Row(
        val shape: String, val spec: ImageStripEncoder.Spec,
        val bands: Int, val nonEmpty: Int, val raisedCells: Int, val raisedDots: Int,
        val preview: String,
    )

    private fun evaluate(name: String, img: GrayImage, spec: ImageStripEncoder.Spec): Row {
        val cols = spec.dotColumns
        val rows = maxOf(1, Math.round(cols.toFloat() * img.height.toFloat() / img.width))
        val ink = ImageStripEncoder.dotGrid(img, cols, rows, spec.threshold)
        val rawSlice = TactileStripSlicer.slice(ink, cols, rows, spec)
        // 与生产成书路径一致：组书前剔字节全 0 的空带（全白图至少留 1 带）。
        val slice = TactileStripBookBuilder.stripEmpty(rawSlice)

        var cells = 0
        var dots = 0
        var nonEmpty = 0
        slice.bands.forEach { b ->
            var pageEmpty = true
            b.bytes.forEach { v ->
                val u = v.toInt() and 0xFF
                if (u != 0) { pageEmpty = false; cells++ }
                dots += Integer.bitCount(u)
            }
            if (!pageEmpty) nonEmpty++
        }
        return Row(name, spec, slice.bands.size, nonEmpty, cells, dots, renderBands(slice, spec))
    }

    /** 从带内真实字节解出 ASCII：每方 2 列 × rowsPerCell 行，# 凸点 · 空点。 */
    private fun renderBands(slice: SliceResult, spec: ImageStripEncoder.Spec): String {
        val rowsPerCell = if (spec.dotsPerCell == 8) 4 else 3
        val out = StringBuilder()
        slice.bands.forEach { band ->
            out.appendLine("--- 带 ${band.bandIndex}/${slice.totalBands} ---")
            for (lineIdx in 0 until spec.linesPerScreen) {
                for (dr in 0 until rowsPerCell) {
                    val row = StringBuilder()
                    for (cell in 0 until spec.cellsPerLine) {
                        val b = band.bytes[lineIdx * spec.cellsPerLine + cell].toInt() and 0xFF
                        for (dc in 0..1) {
                            val bit = dotBit(dr, dc, spec.dotsPerCell)
                            row.append(if (b and (1 shl bit) != 0) "#" else "·")
                        }
                        row.append(' ')
                    }
                    out.appendLine(row.toString().trimEnd())
                }
            }
        }
        return out.toString().trimEnd()
    }

    private fun dotBit(row: Int, col: Int, dots: Int): Int = when (dots) {
        6 -> if (col == 0) row else row + 3
        else -> if (col == 0) {
            when (row) { 0 -> 0; 1 -> 1; 2 -> 2; else -> 6 }
        } else {
            when (row) { 0 -> 3; 1 -> 4; 2 -> 5; else -> 7 }
        }
    }

    @Test
    fun acceptanceReadabilityProbe() {
        val shapes = linkedMapOf(
            "圆（描边环）" to canvas(ring()),
            "方框（描边）" to canvas(square()),
            "向右箭头" to canvas(arrow()),
            "心形" to canvas(heart()),
            "房屋轮廓" to canvas(house()),
            "粗体字母 A" to canvas(letterA()),
            "简单平面图" to canvas(plan()),
        )
        val math = linkedMapOf(
            "数学·三角形+角标+直角符号" to canvas(mathTriangle()),
            "数学·坐标系+正弦+刻度" to canvas(mathCoordinate()),
            "数学·分数+根号" to canvas(mathFractionRadical()),
            "数学·上下标公式" to canvas(mathSuperscript()),
            "数学·柱状图" to canvas(mathBarChart()),
            "数学·2×2 表格" to canvas(mathTable()),
        )
        val shapeSpecs = listOf(
            ImageStripEncoder.Spec.forDisplay(40, 4, 8),
            ImageStripEncoder.Spec.forDisplay(40, 4, 6),
            ImageStripEncoder.Spec.forDisplay(20, 4, 8),
            ImageStripEncoder.Spec.forDisplay(20, 4, 6),
            ImageStripEncoder.Spec.forDisplay(40, 1, 8),
            ImageStripEncoder.Spec.forDisplay(40, 1, 6),
            ImageStripEncoder.Spec.forDisplay(4, 3, 8),
            ImageStripEncoder.Spec.forDisplay(4, 3, 6),
        )
        val mathSpecs = listOf(
            ImageStripEncoder.Spec.forDisplay(40, 4, 8),
            ImageStripEncoder.Spec.forDisplay(40, 4, 6),
            ImageStripEncoder.Spec.forDisplay(20, 4, 8),
            ImageStripEncoder.Spec.forDisplay(40, 1, 8),
            ImageStripEncoder.Spec.forDisplay(40, 1, 6),
        )

        val report = StringBuilder()
        report.appendLine("# 验收方独立可读性探针（真实编码器输出）")
        report.appendLine()
        report.appendLine("画布 $CANVAS×$CANVAS；dotGrid 阈值走 Otsu；切片走 TactileStripSlicer，组书前经 stripEmpty 剔除全空带。")
        report.appendLine()

        fun dump(title: String, map: Map<String, GrayImage>, specs: List<ImageStripEncoder.Spec>) {
            report.appendLine("## $title")
            report.appendLine()
            for ((name, img) in map) {
                report.appendLine("### $name")
                report.appendLine()
                for (spec in specs) {
                    val r = evaluate(name, img, spec)
                    report.appendLine(
                        "#### ${spec.cellsPerLine}×${spec.linesPerScreen} · ${spec.dotsPerCell} 点"
                    )
                    report.appendLine(
                        "带数 ${r.bands}；非空带 ${r.nonEmpty}/${r.bands}；" +
                            "凸起方 ${r.raisedCells}；凸点 ${r.raisedDots}"
                    )
                    report.appendLine("```")
                    report.appendLine(r.preview)
                    report.appendLine("```")
                    report.appendLine()
                }
            }
        }

        dump("几何形状（7 类）", shapes, shapeSpecs)
        dump("数学图形（6 类）", math, mathSpecs)

        val file = File(outDir, "report.md")
        file.writeText(report.toString(), Charsets.UTF_8)
        println("PROBE_FILE=${file.absolutePath}")
    }
}
