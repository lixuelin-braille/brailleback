/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N1 转译体检单五类检查的单测：每类 1 正 1 反，另加一份全干净输入的空清单用例。
 */
class AuditHealthcheckTest {

    private fun line(page: Int, lineInPage: Int, text: String, sourceText: String? = null) =
        AuditLine(page = page, lineInPage = lineInPage, text = text, sourceText = sourceText)

    // ── 1. 未覆盖字符 ──────────────────────────────────────────────────────

    @Test
    fun `unmapped character is reported with occurrence count`() {
        val covered = setOf('甲', '乙', '丙', '，', '。')
        val input = AuditInput(
            sourceParagraphs = listOf("甲乙丙𠀋乙𠀋"),
            coveredChars = covered,
        )
        val report = BrailleAudit.audit(input)
        assertEquals(1, report.countByKind(AuditIssueKind.UNMAPPED_CHARACTER))
        val issue = report.issues.first()
        assertTrue(issue.message.contains("𠀋"))
        assertTrue(issue.message.contains("2 次"))
    }

    @Test
    fun `unmapped character check is clean when every char is covered`() {
        val covered = setOf('甲', '乙', '丙', '，', '。')
        val input = AuditInput(
            sourceParagraphs = listOf("甲乙丙，乙。"),
            coveredChars = covered,
        )
        val report = BrailleAudit.audit(input)
        assertEquals(0, report.countByKind(AuditIssueKind.UNMAPPED_CHARACTER))
    }

    // ── 2. 超宽行 ──────────────────────────────────────────────────────────

    @Test
    fun `overwide line reports cell count and overflow`() {
        val input = AuditInput(
            paragraphs = listOf(listOf(line(1, 1, "A".repeat(34)))),
        )
        val report = BrailleAudit.audit(input)
        assertEquals(1, report.countByKind(AuditIssueKind.OVERWIDE_LINE))
        val message = report.issues.first().message
        assertTrue(message.contains("34 方"))
        assertTrue(message.contains("超出 4 方"))
        assertTrue(message.contains("第 1 页第 1 行"))
    }

    @Test
    fun `line within width is not overwide`() {
        val input = AuditInput(
            paragraphs = listOf(listOf(line(1, 1, "A".repeat(30)))),
        )
        val report = BrailleAudit.audit(input)
        assertEquals(0, report.countByKind(AuditIssueKind.OVERWIDE_LINE))
    }

    // ── 3. 避头尾点违例 ────────────────────────────────────────────────────

    @Test
    fun `head closing punctuation and tail opening punctuation are flagged`() {
        val input = AuditInput(
            paragraphs = listOf(
                listOf(
                    line(1, 2, "⠁⠁", sourceText = "，你好"),
                    line(1, 3, "⠁⠁", sourceText = "你好（"),
                ),
            ),
        )
        val report = BrailleAudit.audit(input)
        assertEquals(2, report.countByKind(AuditIssueKind.PUNCTUATION_AVOIDANCE))
        val messages = report.readableLines()
        assertTrue(messages.any { it.contains("行首为闭标点「，」") })
        assertTrue(messages.any { it.contains("行尾为开标点「（」") })
    }

    @Test
    fun `ordinary head and tail punctuation placement is clean`() {
        val input = AuditInput(
            paragraphs = listOf(
                listOf(line(1, 1, "⠁⠁", sourceText = "你好，世界")),
            ),
        )
        val report = BrailleAudit.audit(input)
        assertEquals(0, report.countByKind(AuditIssueKind.PUNCTUATION_AVOIDANCE))
    }

    // ── 4. 缩写密度 ───────────────────────────────────────────────────────

    @Test
    fun `high contraction density is flagged against threshold`() {
        val segments = buildList {
            repeat(3) { add(AuditSegment("的", isContraction = true)) }
            repeat(5) { add(AuditSegment("汉字", isContraction = false)) }
        }
        val input = AuditInput(segments = segments)
        val report = BrailleAudit.audit(input)
        assertEquals(1, report.countByKind(AuditIssueKind.CONTRACTION_DENSITY))
        val message = report.issues.first().message
        assertTrue(message.contains("高于阈值 25%"))
        assertTrue(message.contains("3 个缩写语节 / 8 个语节"))
    }

    @Test
    fun `low contraction density is clean`() {
        val segments = buildList {
            add(AuditSegment("的", isContraction = true))
            repeat(7) { add(AuditSegment("汉字", isContraction = false)) }
        }
        val input = AuditInput(segments = segments)
        val report = BrailleAudit.audit(input)
        assertEquals(0, report.countByKind(AuditIssueKind.CONTRACTION_DENSITY))
    }

    // ── 5. 末行孤字 ────────────────────────────────────────────────────────

    @Test
    fun `orphan single-character last line is flagged`() {
        val input = AuditInput(
            paragraphs = listOf(
                listOf(
                    line(1, 1, "⠁⠁⠁", sourceText = "第一段正文"),
                    line(1, 2, "⠁", sourceText = "甲"),
                ),
            ),
        )
        val report = BrailleAudit.audit(input)
        assertEquals(1, report.countByKind(AuditIssueKind.ORPHAN_LINE))
        assertTrue(report.issues.first().message.contains("第 1 段末行只剩 1 个字「甲」"))
    }

    @Test
    fun `multi-character last line is not orphan`() {
        val input = AuditInput(
            paragraphs = listOf(
                listOf(
                    line(1, 1, "⠁⠁⠁", sourceText = "第一段正文"),
                    line(1, 2, "⠁⠁", sourceText = "甲乙"),
                ),
            ),
        )
        val report = BrailleAudit.audit(input)
        assertEquals(0, report.countByKind(AuditIssueKind.ORPHAN_LINE))
    }

    // ── 全干净输入 ─────────────────────────────────────────────────────────

    @Test
    fun `clean input produces empty readable report`() {
        val covered = setOf('你', '好', '世', '界', '，', '。')
        val segments = List(6) { AuditSegment("汉字", isContraction = false) }
        val input = AuditInput(
            paragraphs = listOf(
                listOf(
                    line(1, 1, "⠁⠁⠁⠁", sourceText = "你好，"),
                    line(1, 2, "⠁⠁", sourceText = "世界。"),
                ),
            ),
            sourceParagraphs = listOf("你好，世界。"),
            coveredChars = covered,
            segments = segments,
        )
        val report = BrailleAudit.audit(input)
        assertTrue(report.isClean)
        assertTrue(report.readableLines().isEmpty())
        AuditIssueKind.entries.forEach {
            assertEquals(0, report.countByKind(it))
        }
    }
}
