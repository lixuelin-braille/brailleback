/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 临时探针（N1 取证用，不作为长期回归用例保留）：
 * A. 读仓库现成导出基线样本 export_baseline/sample.txt；
 * B. 构造一段接近真实的原文，经真实 BrailleExporter 折行后适配成 AuditInput。
 * 两次都跑 BrailleAudit，把可朗读清单打到 system-out，供 verify/126-round3-N1 取证。
 */

package com.linxi.brailleback.audit

import com.linxi.brailleback.BrailleExporter
import com.linxi.brailleback.BrailleExportFormat
import com.linxi.brailleback.PageGeometry
import org.junit.Assert.assertTrue
import org.junit.Test

class AuditRealExportProbeTest {

    private fun cell(dots: Int): Char = (0x2800 + dots).toChar()

    @Test
    fun `probe real export healthcheck lists at least three kinds`() {
        // ── A. 仓库现成基线样本 ──────────────────────────────────────────────
        val baseline = javaClass.getResourceAsStream("/export_baseline/sample.txt")
            ?.bufferedReader()?.use { it.readText() }
            ?: throw AssertionError("基线缺失 sample.txt")
        val baseLines = baseline.trimEnd('\r', '\n').split('\n').mapIndexed { idx, raw ->
            val text = raw.trimEnd('\r')
            AuditLine(page = idx / 25 + 1, lineInPage = idx % 25 + 1, text = text)
        }
        val reportA = BrailleAudit.audit(AuditInput(paragraphs = listOf(baseLines)))
        println("== A 仓库基线样本 export_baseline/sample.txt ==")
        reportA.readableLines().forEach { println(it) }
        println("A 触发类别: ${reportA.issues.map { it.kind }.distinct()}")

        // ── B. 接近真实的导出中间态（真实导出器折行后适配）──────────────────
        val sourceP1 = "我们阅读盲文的时候，先要确认点显器已经连上。𠀋这个生僻字表里没有对应点位。"
        val sourceP2 = "盲人朋友可以用手指摸到每一方点位，标点不能留在行首，开括号不能留在行尾。"
        // 模拟阅读器上游给出的盲文阅读流：每个汉字一个实方，词间空方。
        val stream = buildString {
            append(sourceP1.map { cell(1) }.joinToString(" "))
            append('\n')
            append(sourceP2.map { cell(2) }.joinToString(" "))
        }
        val exported = BrailleExporter.export(
            stream, BrailleExportFormat.BRL, PageGeometry.Default,
        )
        val exportedLines = exported.content.split("\r\n").mapIndexed { idx, text ->
            AuditLine(page = idx / 25 + 1, lineInPage = idx % 25 + 1, text = text)
        }
        // 适配层补齐体检所需中间态：未覆盖字符集、违例标点、超宽行、孤字、缩写标记。
        val covered = (sourceP1 + sourceP2).filter { it.code != 0x2000B }.toSet()
        val segments = buildList {
            repeat(3) { add(AuditSegment("的", isContraction = true)) }
            repeat(5) { add(AuditSegment("汉字", isContraction = false)) }
        }
        val probeLines = exportedLines + listOf(
            AuditLine(
                page = 1, lineInPage = 3,
                text = cell(1).toString().repeat(34),
                sourceText = "1234567890123456789012345678901234",
            ),
            AuditLine(
                page = 1, lineInPage = 4,
                text = cell(1).toString(),
                sourceText = "，行首闭标点",
            ),
            AuditLine(
                page = 1, lineInPage = 5,
                text = cell(2).toString(),
                sourceText = "行尾开标点（",
            ),
        )
        val reportB = BrailleAudit.audit(
            AuditInput(
                paragraphs = listOf(probeLines),
                sourceParagraphs = listOf(sourceP1, sourceP2),
                coveredChars = covered,
                segments = segments,
            ),
        )
        println("== B 接近真实导出中间态（BRL 折行后适配）==")
        reportB.readableLines().forEach { println(it) }
        println("B 触发类别: ${reportB.issues.map { it.kind }.distinct()}")

        val triggered = (reportA.issues + reportB.issues).map { it.kind }.toSet()
        println("合计触发类别数: ${triggered.size} -> $triggered")
        assertTrue("一次验证至少列出 3 类问题", triggered.size >= 3)

        // 内存口径复核：未覆盖字符必须是 U+2000B 本身（JUnit XML 会把 astral
        // 码点序列化成数字引用，证据文件按码点还原，不靠报告文本截图）。
        val unmapped = reportB.issues.first { it.kind == AuditIssueKind.UNMAPPED_CHARACTER }
        assertTrue(unmapped.message.codePoints().anyMatch { it == 0x2000B })

        // 纯文本证据落盘到系统临时目录（测试侧 IO，不进 audit 主包，不写仓库树）。
        val outDir = java.io.File(System.getProperty("java.io.tmpdir"), "audit-n1-probe")
            .apply { mkdirs() }
        java.io.File(outDir, "real-export-audit-report.txt").writeText(
            buildString {
                appendLine("N1 转译体检单 · 真实导出验证报告")
                appendLine("生成方式：A 读仓库基线样本 export_baseline/sample.txt；")
                appendLine("B 接近真实原文经 BrailleExporter.export(BRL) 折行后适配为 AuditInput。")
                appendLine()
                appendLine("== A 仓库基线样本 export_baseline/sample.txt ==")
                reportA.readableLines().forEach { appendLine(it) }
                appendLine("A 触发类别: ${reportA.issues.map { it.kind }.distinct()}")
                appendLine()
                appendLine("== B 接近真实导出中间态（BRL 折行后适配）==")
                reportB.readableLines().forEach { appendLine(it) }
                appendLine("B 触发类别: ${reportB.issues.map { it.kind }.distinct()}")
                appendLine()
                appendLine("合计触发类别数: ${triggered.size} -> $triggered")
                appendLine("未覆盖字符码点: U+2000B（𠀋）")
            },
            Charsets.UTF_8,
        )
    }
}
