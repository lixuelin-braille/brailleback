/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * N3 打印预检（125 §3 / 65 §4.1 N3）：送打印前纯计算判「可打 / 需重排」。
 *
 * 零 Android 依赖：入参只用 [PageGeometry] 与 [PreflightParams]，输入为已经排好的
 * 盲文行列表；不读设备、不碰打印机、不发起 IO。后续由导出链路或打印界面调用，
 * 本类不反向修改排版结果。
 *
 * 四项检查（对应 127 看板条 K-13）：
 *  1. 行宽越界：单行方数超过 [PageGeometry.columns]；
 *  2. 页数预估：行数 ÷ 每页行数向上取整，超出给定页数上限即报；
 *  3. 装订边：可打印宽度扣掉装订边后，是否还容得下当前行宽；
 *  4. 点密度上限：单行 / 单页凸点数是否超过可压印上限。
 */
package com.linxi.brailleback

import java.util.Locale

/** 预检结论。 */
enum class PreflightVerdict(val label: String) {
    /** 四项检查全部通过，可以送打印。 */
    PRINTABLE("可打"),

    /** 至少一项不通过，需要重新排版后再打。 */
    REARRANGE("需重排"),
}

/** 问题类别，四类与 125 §3 N3 一一对应。 */
enum class PreflightIssueCategory {
    /** 行宽越界。 */
    LINE_WIDTH,

    /** 页数超出上限。 */
    PAGE_COUNT,

    /** 装订边后可打印宽度不足。 */
    BINDING_MARGIN,

    /** 单行 / 单页凸点密度超限。 */
    DOT_DENSITY,
}

/** 一条可朗读的问题说明；[message] 直接面向盲用户，不用工程缩写。 */
data class PreflightIssue(
    val category: PreflightIssueCategory,
    val message: String,
)

/**
 * 预检参数：物理量与阈值全部参数化。
 *
 * 默认值取自 65 号文档 §3.3 的 A4 口径：
 * - A4 宽 210 mm，左右各留 15 mm 边距 → 可打印宽 180 mm；
 * - 方间距约 6 mm，行距约 10 mm，点距约 2.5 mm；
 * - 装订边（订口）默认沿用同一档 15 mm：散页单面刻印可传 0.0 覆盖。
 *
 * 点密度阈值未给定时取物理上限（每格 6 点）；刻印机实测密度上限出来后，
 * 应由调用方按实测值收紧，这里不替硬件拍板。
 */
data class PreflightParams(
    /** A4 纸宽 mm（65 §3.3：A4 宽 210 mm）。 */
    val paperWidthMm: Double = 210.0,

    /** 单侧边距 mm（65 §3.3：左右各留 15 mm 边距）。 */
    val sideMarginMm: Double = 15.0,

    /** 装订边（订口）预留 mm：沿用 65 §3.3 单侧边距同档 15 mm；装订方式不同时由调用方覆盖。 */
    val bindingMarginMm: Double = 15.0,

    /** 方间距 mm（65 §3.3：方间距约 6 mm）。 */
    val cellPitchMm: Double = 6.0,

    /** 允许的最大页数；null 表示不设页数上限，只报预估页数。 */
    val maxPages: Int? = null,

    /** 单行凸点上限；null 取物理上限：每行方数 × 每格点数。 */
    val maxDotsPerLine: Int? = null,

    /** 单页凸点上限；null 取物理上限：每页行数 × 每行方数 × 每格点数。 */
    val maxDotsPerPage: Int? = null,

    /** 每格点数（六点盲文为 6；八点表可传 8）。 */
    val dotsPerCell: Int = 6,
) {
    init {
        require(paperWidthMm > 0.0) { "paperWidthMm must be > 0" }
        require(sideMarginMm >= 0.0) { "sideMarginMm must be >= 0" }
        require(bindingMarginMm >= 0.0) { "bindingMarginMm must be >= 0" }
        require(cellPitchMm > 0.0) { "cellPitchMm must be > 0" }
        require(dotsPerCell > 0) { "dotsPerCell must be > 0" }
        maxPages?.let { require(it > 0) { "maxPages must be > 0" } }
        maxDotsPerLine?.let { require(it >= 0) { "maxDotsPerLine must be >= 0" } }
        maxDotsPerPage?.let { require(it >= 0) { "maxDotsPerPage must be >= 0" } }
    }

    /** 扣掉左右边距后的可打印宽度 mm。 */
    val printableWidthMm: Double
        get() = paperWidthMm - 2.0 * sideMarginMm
}

/** 预检结果。 */
data class PreflightReport(
    val verdict: PreflightVerdict,
    val issues: List<PreflightIssue>,
    /** 总行数。 */
    val lineCount: Int,
    /** 预估页数（行数 ÷ 每页行数，向上取整）。 */
    val estimatedPages: Int,
    /** 最宽一行的方数。 */
    val widestLineCells: Int,
    /** 全书凸点总数。 */
    val totalDots: Int,
) {
    /** 四项检查是否全部通过。 */
    val isPrintable: Boolean
        get() = verdict == PreflightVerdict.PRINTABLE

    /** 可朗读的整份结论：先报判定，再逐条念问题。 */
    fun readAloud(): String {
        if (issues.isEmpty()) return verdict.label
        return buildString {
            append(verdict.label)
            issues.forEach { append("；").append(it.message) }
        }
    }
}

/**
 * 打印预检入口。纯函数：同样的输入永远得到同样的结果，不触碰 Android 框架。
 */
object PrintPreflight {

    /**
     * @param lines 已经排好的盲文行；每行长度即该行方数，盲文字符取 U+2800..U+28FF
     * @param geometry 版面几何（每行方数、每页行数）
     * @param params 物理量与阈值参数
     */
    fun precheck(
        lines: List<String>,
        geometry: PageGeometry,
        params: PreflightParams = PreflightParams(),
    ): PreflightReport {
        val issues = ArrayList<PreflightIssue>()

        // 1. 行宽越界
        var widest = 0
        lines.forEachIndexed { index, line ->
            val cells = line.length
            if (cells > widest) widest = cells
            if (cells > geometry.columns) {
                issues.add(
                    PreflightIssue(
                        PreflightIssueCategory.LINE_WIDTH,
                        "第 ${index + 1} 行 $cells 方，超出 ${cells - geometry.columns} 方",
                    ),
                )
            }
        }

        // 2. 页数预估
        val lineCount = lines.size
        val estimatedPages = if (lineCount == 0) {
            0
        } else {
            (lineCount + geometry.linesPerPage - 1) / geometry.linesPerPage
        }
        params.maxPages?.let { maxPages ->
            if (estimatedPages > maxPages) {
                issues.add(
                    PreflightIssue(
                        PreflightIssueCategory.PAGE_COUNT,
                        "预估 $estimatedPages 页，超上限 ${estimatedPages - maxPages} 页",
                    ),
                )
            }
        }

        // 3. 装订边：可打印宽度扣掉装订边后，是否容得下当前行宽
        val requiredWidthMm = geometry.columns * params.cellPitchMm
        val availableMm = params.printableWidthMm - params.bindingMarginMm
        if (requiredWidthMm > availableMm) {
            issues.add(
                PreflightIssue(
                    PreflightIssueCategory.BINDING_MARGIN,
                    "装订边后可打印宽 ${fmtMm(availableMm)} mm，容不下 ${geometry.columns} 方" +
                        "（需 ${fmtMm(requiredWidthMm)} mm，差 ${fmtMm(requiredWidthMm - availableMm)} mm）",
                ),
            )
        }

        // 4. 点密度上限：逐行、逐页统计凸点数
        val dotsPerLine = lines.map { countDots(it) }
        val lineCap = params.maxDotsPerLine ?: geometry.columns * params.dotsPerCell
        dotsPerLine.forEachIndexed { index, dots ->
            if (dots > lineCap) {
                issues.add(
                    PreflightIssue(
                        PreflightIssueCategory.DOT_DENSITY,
                        "第 ${index + 1} 行凸点 $dots 个，超单行上限 $lineCap 个",
                    ),
                )
            }
        }

        val pageCap = params.maxDotsPerPage ?: geometry.linesPerPage * geometry.columns * params.dotsPerCell
        for (page in 0 until estimatedPages) {
            val start = page * geometry.linesPerPage
            val end = minOf(start + geometry.linesPerPage, dotsPerLine.size)
            val pageDots = dotsPerLine.subList(start, end).sum()
            if (pageDots > pageCap) {
                issues.add(
                    PreflightIssue(
                        PreflightIssueCategory.DOT_DENSITY,
                        "第 ${page + 1} 页凸点 $pageDots 个，超单页上限 $pageCap 个",
                    ),
                )
            }
        }

        val totalDots = dotsPerLine.sum()
        val verdict = if (issues.isEmpty()) PreflightVerdict.PRINTABLE else PreflightVerdict.REARRANGE
        return PreflightReport(
            verdict = verdict,
            issues = issues,
            lineCount = lineCount,
            estimatedPages = estimatedPages,
            widestLineCells = widest,
            totalDots = totalDots,
        )
    }

    /** 一行内的凸点数：只统计 U+2800..U+28FF 盲文格，明眼字符不计凸点。 */
    private fun countDots(line: String): Int =
        line.sumOf { c ->
            if (c.code in 0x2800..0x28FF) popcount(c.code - 0x2800) else 0
        }

    /** 8 位掩码的置位数（不依赖 stdlib 的 countOneBits，兼容旧 Kotlin 版本）。 */
    private fun popcount(mask: Int): Int {
        var m = mask and 0xFF
        var n = 0
        while (m != 0) {
            n += m and 1
            m = m shr 1
        }
        return n
    }

    private fun fmtMm(value: Double): String = String.format(Locale.US, "%.1f", value)
}
