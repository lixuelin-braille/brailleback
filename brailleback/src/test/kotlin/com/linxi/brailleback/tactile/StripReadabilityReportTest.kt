/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 为什么需要它：
 * 纸面 BMP 链路回答「图能不能刻印出来」，点显链路还要回答另一个问题——一张典型图形
 * 送到 20/30/40 方、1~4 行、6/8 点的点显屏上，到底摸不摸得出来。本用例在纯 JVM 侧
 * 程序化构造圆、方、箭头、心形、房屋轮廓、粗体字母、简单平面图七类样本，跑真实编码器
 * （[ImageStripEncoder]），输出：
 * - 每种「规格 × 点数」组合的页数统计；
 * - 首页点阵 ASCII 预览（# = 凸点，· = 空点）；
 * - 首页凸起方数 / 凸起点数等客观指标，供可读性分级对照。
 *
 * 分级结论（可辨 / 勉强可辨 / 不可辨）写在 docs 报告里，依据是本用例跑出的真实预览与
 * 指标；本类不引 Android、不连设备，可离线跑。
 */
class StripReadabilityReportTest {

    private companion object {
        /** 样本画布边长（正方形）。 */
        const val CANVAS = 100
    }

    private val outDir: File =
        File(System.getProperty("java.io.tmpdir"), "strip-readability").apply { mkdirs() }

    // ------------------------------------------------------------- 样本图形

    private fun canvas(ink: Set<Pair<Int, Int>>): GrayImage =
        GrayImage(CANVAS, CANVAS, IntArray(CANVAS * CANVAS) { i ->
            val x = i % CANVAS
            val y = i / CANVAS
            if (ink.contains(x to y)) 0 else 255
        })

    /** 描边圆：圆心 (50,50)，半径 34，线宽约 2 点。 */
    private fun ring(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        for (y in 0 until CANVAS) for (x in 0 until CANVAS) {
            val d = hypot((x - 50).toDouble(), (y - 50).toDouble())
            if (abs(d - 34.0) <= 2.0) pts.add(x to y)
        }
        return pts
    }

    /** 描边方框：18..82，边框厚 3。 */
    private fun square(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        for (y in 0 until CANVAS) for (x in 0 until CANVAS) {
            val nearTop = y in 18..20
            val nearBottom = y in 80..82
            val nearLeft = x in 18..20
            val nearRight = x in 80..82
            if (nearTop || nearBottom || nearLeft || nearRight) pts.add(x to y)
        }
        return pts
    }

    /** 向右箭头：箭杆 + 三角箭头轮廓。 */
    private fun arrow(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        // 箭杆 x 20..62，y 45..55。
        for (y in 45..55) for (x in 20..62) pts.add(x to y)
        // 箭头三角：(62,30) (82,50) (62,70) 两条斜边。
        strokeLine(pts, 62, 30, 82, 50)
        strokeLine(pts, 82, 50, 62, 70)
        return pts
    }

    /** 心形：参数方程描边，缩放到 100 画布。 */
    private fun heart(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        val steps = 400
        for (i in 0..steps) {
            val t = Math.PI * 2 * i / steps
            val hx = 16.0 * Math.pow(Math.sin(t), 3.0)
            val hy = 13.0 * Math.cos(t) - 5.0 * Math.cos(2 * t) -
                2.0 * Math.cos(3 * t) - Math.cos(4 * t)
            val x = (50 + hx * 2.4).toInt().coerceIn(0, CANVAS - 1)
            val y = (50 - hy * 2.4).toInt().coerceIn(0, CANVAS - 1)
            pts.add(x to y)
            // 描边加粗：邻域三点。
            for (dx in -1..1) for (dy in -1..1) {
                val nx = (x + dx).coerceIn(0, CANVAS - 1)
                val ny = (y + dy).coerceIn(0, CANVAS - 1)
                pts.add(nx to ny)
            }
        }
        return pts
    }

    /** 房屋轮廓：矩形墙身 + 三角屋顶。 */
    private fun house(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        // 墙身矩形 25..75，y 45..85。
        strokeLine(pts, 25, 45, 25, 85)
        strokeLine(pts, 75, 45, 75, 85)
        strokeLine(pts, 25, 85, 75, 85)
        // 屋顶三角 (25,45) (50,20) (75,45)。
        strokeLine(pts, 25, 45, 50, 20)
        strokeLine(pts, 50, 20, 75, 45)
        return pts
    }

    /** 粗体大写字母 A：两条斜腿 + 横杠，笔画加粗。 */
    private fun letterA(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        thickLine(pts, 28, 85, 50, 22)
        thickLine(pts, 50, 22, 72, 85)
        // 横杠 y 56..60，x 38..62。
        for (y in 56..60) for (x in 38..62) pts.add(x to y)
        return pts
    }

    /** 简单平面图：外房间 + 隔墙（带门洞）+ 门 + 一条通行动线。 */
    private fun plan(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        // 外墙 15..85，厚 2。
        strokeLine(pts, 15, 15, 85, 15)
        strokeLine(pts, 15, 85, 85, 85)
        strokeLine(pts, 15, 15, 15, 85)
        strokeLine(pts, 85, 15, 85, 85)
        // 隔墙 x=55，y 22..78，门洞 45..55。
        strokeLine(pts, 55, 22, 55, 45)
        strokeLine(pts, 55, 55, 55, 78)
        // 南墙门缺口 38..48（外墙在该处断开，用一段动线表示入口）。
        strokeLine(pts, 38, 85, 48, 85)
        // 动线：南门 → 隔墙门洞。
        strokeLine(pts, 43, 85, 43, 60)
        strokeLine(pts, 43, 60, 55, 50)
        return pts
    }

    private fun strokeLine(
        pts: MutableSet<Pair<Int, Int>>, x0: Int, y0: Int, x1: Int, y1: Int,
    ) {
        val steps = maxOf(abs(x1 - x0), abs(y1 - y0), 1)
        for (i in 0..steps) {
            val x = Math.round(x0 + (x1 - x0) * i.toDouble() / steps).toInt()
            val y = Math.round(y0 + (y1 - y0) * i.toDouble() / steps).toInt()
            pts.add(x to y)
        }
    }

    /** 加粗笔画：中心线 + 3×3 邻域。 */
    private fun thickLine(
        pts: MutableSet<Pair<Int, Int>>, x0: Int, y0: Int, x1: Int, y1: Int,
    ) {
        val steps = maxOf(abs(x1 - x0), abs(y1 - y0), 1)
        for (i in 0..steps) {
            val x = Math.round(x0 + (x1 - x0) * i.toDouble() / steps).toInt()
            val y = Math.round(y0 + (y1 - y0) * i.toDouble() / steps).toInt()
            for (dx in -1..1) for (dy in -1..1) {
                pts.add((x + dx).coerceIn(0, CANVAS - 1) to
                    (y + dy).coerceIn(0, CANVAS - 1))
            }
        }
    }

    // ------------------------------------------------------------- 指标与预览

    private data class ShapeResult(
        val name: String,
        val spec: ImageStripEncoder.Spec,
        val pages: Int,
        val nonEmptyPages: Int,
        val raisedCellsBook: Int,
        val raisedDotsBook: Int,
        val cellsPage1: Int,
        val raisedCellsPage1: Int,
        val raisedDotsPage1: Int,
        val preview: String,
    )

    private fun evaluate(
        name: String, img: GrayImage, spec: ImageStripEncoder.Spec,
    ): ShapeResult {
        val pages = ImageStripEncoder.encode(img, spec)
        val cols = spec.dotColumns
        val rows = maxOf(1, Math.round(cols.toFloat() * img.height / img.width))
        val ink = ImageStripEncoder.dotGrid(img, cols, rows, spec.threshold)
        val rowsPerCell = if (spec.dotsPerCell == 8) 4 else 3
        val pageRows = spec.linesPerScreen * rowsPerCell

        var raisedCellsBook = 0
        var raisedDotsBook = 0
        var nonEmpty = 0
        for (page in pages) {
            var pageEmpty = true
            for (b in page) {
                val v = b.toInt() and 0xFF
                if (v != 0) {
                    pageEmpty = false
                    raisedCellsBook++
                }
                raisedDotsBook += Integer.bitCount(v)
            }
            if (!pageEmpty) nonEmpty++
        }
        val first = pages.first()
        var raisedCellsPage1 = 0
        var raisedDotsPage1 = 0
        for (b in first) {
            val v = b.toInt() and 0xFF
            if (v != 0) raisedCellsPage1++
            raisedDotsPage1 += Integer.bitCount(v)
        }
        val preview = renderPreview(ink, cols, rows, spec, rowsPerCell, pageRows)
        return ShapeResult(
            name = name,
            spec = spec,
            pages = pages.size,
            nonEmptyPages = nonEmpty,
            raisedCellsBook = raisedCellsBook,
            raisedDotsBook = raisedDotsBook,
            cellsPage1 = first.size,
            raisedCellsPage1 = raisedCellsPage1,
            raisedDotsPage1 = raisedDotsPage1,
            preview = preview,
        )
    }

    /** 渲染全书 ASCII：逐条带铺开，每方 2 列、rowsPerCell 行；# 凸点，· 空点。 */
    private fun renderPreview(
        ink: BooleanArray, cols: Int, rows: Int,
        spec: ImageStripEncoder.Spec, rowsPerCell: Int, pageRows: Int,
    ): String {
        val bands = (rows + pageRows - 1) / pageRows
        val out = StringBuilder()
        for (band in 0 until bands) {
            for (line in 0 until spec.linesPerScreen) {
                for (dr in 0 until rowsPerCell) {
                    val gy = band * pageRows + line * rowsPerCell + dr
                    if (gy >= rows) break
                    val row = StringBuilder()
                    for (cell in 0 until spec.cellsPerLine) {
                        for (dc in 0..1) {
                            val gx = cell * 2 + dc
                            row.append(if (gx < cols && ink[gy * cols + gx]) "#" else "·")
                        }
                        row.append(' ')
                    }
                    out.appendLine(row.toString().trimEnd())
                }
            }
            if (band < bands - 1) out.appendLine("--- 带 ${"%d".format(band + 1)} / $bands ---")
        }
        return out.toString().trimEnd()
    }

    // ------------------------------------------------------------- 跑矩阵

    @Test
    fun readabilityMatrix() {
        val shapes = linkedMapOf(
            "圆（描边环）" to canvas(ring()),
            "方框（描边）" to canvas(square()),
            "向右箭头" to canvas(arrow()),
            "心形" to canvas(heart()),
            "房屋轮廓" to canvas(house()),
            "粗体字母 A" to canvas(letterA()),
            "简单平面图" to canvas(plan()),
        )
        val specs = listOf(
            ImageStripEncoder.Spec.forDisplay(40, 4, 8),
            ImageStripEncoder.Spec.forDisplay(40, 4, 6),
            ImageStripEncoder.Spec.forDisplay(20, 4, 8),
            ImageStripEncoder.Spec.forDisplay(20, 4, 6),
            ImageStripEncoder.Spec.forDisplay(40, 1, 8),
            ImageStripEncoder.Spec.forDisplay(40, 1, 6),
            ImageStripEncoder.Spec.forDisplay(4, 3, 8),
            ImageStripEncoder.Spec.forDisplay(4, 3, 6),
        )

        val report = StringBuilder()
        report.appendLine("# 点显图形可读性矩阵（编码器真实输出）")
        report.appendLine()
        report.appendLine("样本画布 $CANVAS×$CANVAS；规格 = 方数×行数，点数 6/8；" +
            "dotColumns = 方数×2（forDisplay 铺满一行）。")
        report.appendLine()

        val results = ArrayList<ShapeResult>()
        for ((shapeName, img) in shapes) {
            report.appendLine("## $shapeName")
            report.appendLine()
            for (spec in specs) {
                val r = evaluate(shapeName, img, spec)
                results.add(r)
                report.appendLine(
                    "### ${spec.cellsPerLine} 方 × ${spec.linesPerScreen} 行 · " +
                        "${spec.dotsPerCell} 点"
                )
                report.appendLine(
                    "页数 ${r.pages}；非空页 ${r.nonEmptyPages}/${r.pages}；" +
                        "全书凸起方 ${r.raisedCellsBook}；全书凸点 ${r.raisedDotsBook}；" +
                        "首页凸起方 ${r.raisedCellsPage1}/${r.cellsPage1}；" +
                        "首页凸点 ${r.raisedDotsPage1}"
                )
                report.appendLine("```")
                report.appendLine(r.preview)
                report.appendLine("```")
                report.appendLine()
            }
        }

        // dotColumns 核对：forDisplay 下横向点列必须等于 方数×2，40 方铺满 80 点。
        report.appendLine("## dotColumns 核对")
        for (cells in listOf(20, 30, 40)) {
            val s = ImageStripEncoder.Spec.forDisplay(cells, 1, 8)
            report.appendLine(
                "- ${cells} 方：dotColumns=${s.dotColumns}，" +
                    "每行方数上限=${s.dotColumns / 2}"
            )
        }
        report.appendLine()
        report.appendLine("旧默认 dotColumns=64 时：40 方一行只有 64 点列 → 填满 " +
            "${64 / 2} 方，右侧 ${40 - 64 / 2} 方永远读不到像素；现已改为 cellsPerLine×2。")

        val file = File(outDir, "strip-readability.md")
        file.writeText(report.toString(), Charsets.UTF_8)
        println("REPORT_DIR=${outDir.absolutePath}")
        println("REPORT_FILE=${file.absolutePath}")
        println("RESULT_COUNT=${results.size}")
    }
}
