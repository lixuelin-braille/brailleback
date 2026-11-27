/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * N3 打印预检纯 JVM 契约：四项检查各至少一正一反，另加边界值用例
 * （等于上限判可打，超 1 方判需重排）。
 */
package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrintPreflightTest {

    /** 满格六点盲文格（点位 1-6 全凸）。 */
    private fun fullCell(): Char = (0x2800 + 0x3F).toChar()

    /** 构造 [cells] 方的一行，默认满格；[mask] 可换成指定点位。 */
    private fun line(cells: Int, mask: Int = 0x3F): String =
        buildString { repeat(cells) { append((0x2800 + mask).toChar()) } }

    /** 隔离其余三项检查的默认参数：装订边传 0，不设页数与密度上限。 */
    private fun params(
        bindingMm: Double = 0.0,
        maxPages: Int? = null,
        maxDotsPerLine: Int? = null,
        maxDotsPerPage: Int? = null,
    ) = PreflightParams(
        bindingMarginMm = bindingMm,
        maxPages = maxPages,
        maxDotsPerLine = maxDotsPerLine,
        maxDotsPerPage = maxDotsPerPage,
    )

    private fun issuesOf(report: PreflightReport, category: PreflightIssueCategory) =
        report.issues.filter { it.category == category }

    // ------------------------------------------------ 1. 行宽越界

    @Test
    fun lineWidthWithinLimit_isPrintable() {
        val report = PrintPreflight.precheck(
            lines = listOf(line(30)),
            geometry = PageGeometry.Default,
            params = params(),
        )
        assertTrue(report.isPrintable)
        assertTrue(issuesOf(report, PreflightIssueCategory.LINE_WIDTH).isEmpty())
    }

    @Test
    fun lineWidthOverLimit_rearrangesWithMessage() {
        val report = PrintPreflight.precheck(
            lines = listOf(line(31)),
            geometry = PageGeometry.Default,
            params = params(),
        )
        assertFalse(report.isPrintable)
        val issues = issuesOf(report, PreflightIssueCategory.LINE_WIDTH)
        assertEquals(1, issues.size)
        assertEquals("第 1 行 31 方，超出 1 方", issues[0].message)
    }

    // ------------------------------------------------ 2. 页数预估

    @Test
    fun pageCountWithinLimit_isPrintable() {
        val report = PrintPreflight.precheck(
            lines = List(25) { line(30, mask = 0) },
            geometry = PageGeometry.Default,
            params = params(maxPages = 1),
        )
        assertTrue(report.isPrintable)
        assertEquals(1, report.estimatedPages)
        assertTrue(issuesOf(report, PreflightIssueCategory.PAGE_COUNT).isEmpty())
    }

    @Test
    fun pageCountOverLimit_rearrangesWithMessage() {
        val report = PrintPreflight.precheck(
            lines = List(26) { line(30, mask = 0) },
            geometry = PageGeometry.Default,
            params = params(maxPages = 1),
        )
        assertFalse(report.isPrintable)
        assertEquals(2, report.estimatedPages)
        val issues = issuesOf(report, PreflightIssueCategory.PAGE_COUNT)
        assertEquals(1, issues.size)
        assertEquals("预估 2 页，超上限 1 页", issues[0].message)
    }

    // ------------------------------------------------ 3. 装订边

    @Test
    fun bindingMarginEnough_isPrintable() {
        // 装订边 0：可打印宽 180 mm，正好容下 30 方 × 6 mm。
        val report = PrintPreflight.precheck(
            lines = listOf(line(30, mask = 0)),
            geometry = PageGeometry.Default,
            params = params(bindingMm = 0.0),
        )
        assertTrue(report.isPrintable)
        assertTrue(issuesOf(report, PreflightIssueCategory.BINDING_MARGIN).isEmpty())
    }

    @Test
    fun bindingMarginTooNarrow_rearrangesWithMessage() {
        // 默认装订边 15 mm：剩余 165 mm，容不下 30 方（需 180 mm）。
        val report = PrintPreflight.precheck(
            lines = listOf(line(30, mask = 0)),
            geometry = PageGeometry.Default,
            params = params(bindingMm = 15.0),
        )
        assertFalse(report.isPrintable)
        val issues = issuesOf(report, PreflightIssueCategory.BINDING_MARGIN)
        assertEquals(1, issues.size)
        assertEquals(
            "装订边后可打印宽 165.0 mm，容不下 30 方（需 180.0 mm，差 15.0 mm）",
            issues[0].message,
        )
    }

    // ------------------------------------------------ 4. 点密度上限

    @Test
    fun dotDensityWithinLineLimit_isPrintable() {
        // 单行上限 60 点：10 个满格 = 60 点，正好卡住。
        val report = PrintPreflight.precheck(
            lines = listOf(line(10)),
            geometry = PageGeometry.Default,
            params = params(maxDotsPerLine = 60),
        )
        assertTrue(report.isPrintable)
        assertTrue(issuesOf(report, PreflightIssueCategory.DOT_DENSITY).isEmpty())
    }

    @Test
    fun dotDensityOverLineLimit_rearrangesWithMessage() {
        // 单行上限 60 点：11 个满格 = 66 点，超 6 点。
        val report = PrintPreflight.precheck(
            lines = listOf(line(11)),
            geometry = PageGeometry.Default,
            params = params(maxDotsPerLine = 60),
        )
        assertFalse(report.isPrintable)
        val issues = issuesOf(report, PreflightIssueCategory.DOT_DENSITY)
        assertEquals(1, issues.size)
        assertEquals("第 1 行凸点 66 个，超单行上限 60 个", issues[0].message)
    }

    @Test
    fun dotDensityOverPageLimit_rearrangesWithMessage() {
        // 单页上限 100 点：两行各 10 个满格，共 120 点。
        val report = PrintPreflight.precheck(
            lines = listOf(line(10), line(10)),
            geometry = PageGeometry.Default,
            params = params(maxDotsPerPage = 100),
        )
        assertFalse(report.isPrintable)
        val issues = issuesOf(report, PreflightIssueCategory.DOT_DENSITY)
        assertTrue(issues.any { it.message == "第 1 页凸点 120 个，超单页上限 100 个" })
    }

    // ------------------------------------------------ 边界值

    @Test
    fun boundary_lineExactlyAtColumns_isPrintable() {
        val report = PrintPreflight.precheck(
            lines = listOf(line(30)),
            geometry = PageGeometry.Default,
            params = params(),
        )
        assertTrue("等于行宽上限应判可打", report.isPrintable)
    }

    @Test
    fun boundary_lineOneCellOverColumns_rearranges() {
        val report = PrintPreflight.precheck(
            lines = listOf(line(31)),
            geometry = PageGeometry.Default,
            params = params(),
        )
        assertFalse("超 1 方应判需重排", report.isPrintable)
    }

    @Test
    fun boundary_pageExactlyAtLimit_isPrintable() {
        val report = PrintPreflight.precheck(
            lines = List(25) { line(30, mask = 0) },
            geometry = PageGeometry.Default,
            params = params(maxPages = 1),
        )
        assertTrue("25 行正好 1 页，应判可打", report.isPrintable)
    }

    @Test
    fun boundary_densityExactlyAtLimit_isPrintable() {
        val report = PrintPreflight.precheck(
            lines = listOf(line(10)),
            geometry = PageGeometry.Default,
            params = params(maxDotsPerLine = 60),
        )
        assertTrue("凸点正好等于上限应判可打", report.isPrintable)
    }

    // ------------------------------------------------ 汇总口径

    @Test
    fun readAloud_startsWithVerdictLabel() {
        val report = PrintPreflight.precheck(
            lines = listOf(line(31)),
            geometry = PageGeometry.Default,
            params = params(),
        )
        assertEquals("需重排", report.verdict.label)
        assertTrue(report.readAloud().startsWith("需重排；"))
    }

    // ------------------------------------------------ 实证：含多处越界的 30×25 版面

    /**
     * 实证样本：以默认版面 30 方 × 25 行为底，注入四类越界——
     * 第 7 行 34 方（行宽超 4）、共 30 行（预估 2 页，超 1 页上限）、
     * 第 12 行 12 个满格（72 点，超单行 60 点上限）、装订边按默认 15 mm 预留。
     * 验收判据（125 §3 N3）：预检必须判出一次「需重排」。
     */
    @Test
    fun empirical_sampleWithMultipleViolations_judgesRearrange() {
        val sample = ArrayList<String>()
        repeat(30) { index ->
            when (index) {
                6 -> sample.add(line(34)) // 第 7 行：34 方
                11 -> sample.add(line(12)) // 第 12 行：12 个满格 = 72 点
                else -> sample.add(line(30, mask = 2)) // 其余：30 方，单点
            }
        }
        val report = PrintPreflight.precheck(
            lines = sample,
            geometry = PageGeometry.Default,
            params = params(bindingMm = 15.0, maxPages = 1, maxDotsPerLine = 60),
        )

        assertFalse(report.isPrintable)
        assertEquals(PreflightVerdict.REARRANGE, report.verdict)
        val categories = report.issues.map { it.category }.toSet()
        assertTrue(categories.contains(PreflightIssueCategory.LINE_WIDTH))
        assertTrue(categories.contains(PreflightIssueCategory.PAGE_COUNT))
        assertTrue(categories.contains(PreflightIssueCategory.BINDING_MARGIN))
        assertTrue(categories.contains(PreflightIssueCategory.DOT_DENSITY))

        // 结构化输出，落 verify/126-round3-N3/preflight-report.txt 取证。
        println("=== N3 实证预检报告 ===")
        println("判定：${report.verdict.label}")
        println("总行数：${report.lineCount}；预估页数：${report.estimatedPages}；最宽行：${report.widestLineCells} 方；凸点总数：${report.totalDots}")
        report.issues.forEachIndexed { i, issue ->
            println("问题 ${i + 1} [${issue.category}]：${issue.message}")
        }
        println("朗读稿：${report.readAloud()}")
    }

    @Test
    fun emptyInput_isPrintableWithZeroPages() {
        val report = PrintPreflight.precheck(
            lines = emptyList(),
            geometry = PageGeometry.Default,
            params = params(),
        )
        assertTrue(report.isPrintable)
        assertEquals(0, report.estimatedPages)
        assertEquals(0, report.totalDots)
    }
}
