/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.audit

/**
 * 转译体检单入口。五类检查全部为纯函数：相同输入必得相同清单，
 * 不读文件、不打日志、不碰 UI 与 Android 组件。
 *
 * 五类：未覆盖字符、超宽行、避头尾点违例、缩写密度、末行孤字。
 */
object BrailleAudit {

    fun audit(input: AuditInput): AuditReport {
        val issues = ArrayList<AuditIssue>()
        issues += checkUnmappedCharacters(input)
        issues += checkOverwideLines(input)
        issues += checkPunctuationAvoidance(input)
        issues += checkContractionDensity(input)
        issues += checkOrphanLines(input)
        return AuditReport(issues)
    }

    // ── 1. 未覆盖字符 ──────────────────────────────────────────────────────

    private fun checkUnmappedCharacters(input: AuditInput): List<AuditIssue> {
        // 调用方没给可覆盖字符集时，本类检查无依据，跳过而不是误报。
        if (input.coveredChars.isEmpty()) return emptyList()

        // 按码点统计：生僻字/扩展 B 区字是 astral 码点，按 Char 遍历会拆成代理对。
        val coveredCodes = input.coveredChars.map { it.code }.toSet()
        val counts = HashMap<Int, Int>()
        for (paragraph in input.sourceParagraphs) {
            paragraph.codePoints().forEach { code ->
                if (code < 32 || Character.isWhitespace(code)) return@forEach
                if (code !in coveredCodes) {
                    counts[code] = (counts[code] ?: 0) + 1
                }
            }
        }
        return counts.entries
            .sortedByDescending { it.value }
            .map { (code, count) ->
                val label = String(Character.toChars(code))
                AuditIssue(
                    kind = AuditIssueKind.UNMAPPED_CHARACTER,
                    message = "未覆盖字符「$label」出现 $count 次",
                )
            }
    }

    // ── 2. 超宽行 ──────────────────────────────────────────────────────────

    private fun checkOverwideLines(input: AuditInput): List<AuditIssue> {
        val columns = input.geometry.columns
        val out = ArrayList<AuditIssue>()
        for (line in input.paragraphs.flatten()) {
            val cells = line.text.count { it != '\r' && it != '\n' }
            if (cells > columns) {
                out += AuditIssue(
                    kind = AuditIssueKind.OVERWIDE_LINE,
                    message = "第 ${line.page} 页第 ${line.lineInPage} 行超宽，" +
                        "$cells 方，超出 ${cells - columns} 方",
                    page = line.page,
                    line = line.lineInPage,
                )
            }
        }
        return out
    }

    // ── 3. 避头尾点违例 ────────────────────────────────────────────────────

    /**
     * 闭标点（避头点：行首不得出现）。口径对齐
     * `wrapping.WrapStrategy.isClosingPunctuation`，两份集合保持一致；
     * 体检单自留副本，避免反向依赖折行策略内部私有方法。
     */
    private val closingPunctuation = setOf(
        '，', '。', '！', '？', '；', '：',
        '、', '）', '】', '》', '〉', '”',
        '’', '…', '·',
        ',', '.', '!', '?', ';', ':',
        ')', ']', '}', '>', '"', '\'',
    )

    /** 开标点（避尾点：行尾不得出现）。口径对齐 WrapStrategy.isOpeningPunctuation。 */
    private val openingPunctuation = setOf(
        '（', '【', '［', '《', '〈', '〔',
        '{', '[', '(', '<',
        '“', '‘', '「', '『',
    )

    private fun checkPunctuationAvoidance(input: AuditInput): List<AuditIssue> {
        val out = ArrayList<AuditIssue>()
        for (line in input.paragraphs.flatten()) {
            val head = endpointChar(line, fromHead = true)
            if (head != null && head in closingPunctuation) {
                out += AuditIssue(
                    kind = AuditIssueKind.PUNCTUATION_AVOIDANCE,
                    message = "第 ${line.page} 页第 ${line.lineInPage} 行行首为闭标点" +
                        "「$head」，违反避头点规则",
                    page = line.page,
                    line = line.lineInPage,
                )
            }
            val tail = endpointChar(line, fromHead = false)
            if (tail != null && tail in openingPunctuation) {
                out += AuditIssue(
                    kind = AuditIssueKind.PUNCTUATION_AVOIDANCE,
                    message = "第 ${line.page} 页第 ${line.lineInPage} 行行尾为开标点" +
                        "「$tail」，违反避尾点规则",
                    page = line.page,
                    line = line.lineInPage,
                )
            }
        }
        return out
    }

    /** 取行首/行尾第一个非空字符；优先用原文，缺失时退回中间态文本。 */
    private fun endpointChar(line: AuditLine, fromHead: Boolean): Char? {
        val source = line.sourceText
        val sourceChar = if (source != null) {
            if (fromHead) source.firstNonBlank() else source.lastNonBlank()
        } else {
            null
        }
        return sourceChar ?: if (fromHead) line.text.firstNonBlank() else line.text.lastNonBlank()
    }

    // ── 4. 缩写密度 ────────────────────────────────────────────────────────

    private fun checkContractionDensity(input: AuditInput): List<AuditIssue> {
        val total = input.segments.size
        if (total == 0) return emptyList()
        val contracted = input.segments.count { it.isContraction }
        val ratio = contracted.toDouble() / total
        if (ratio <= input.abbreviationThreshold) return emptyList()

        val percent = Math.round(ratio * 100).toInt()
        val thresholdPercent = Math.round(input.abbreviationThreshold * 100).toInt()
        return listOf(
            AuditIssue(
                kind = AuditIssueKind.CONTRACTION_DENSITY,
                message = "缩写占比 $percent%，高于阈值 $thresholdPercent%" +
                    "（$contracted 个缩写语节 / $total 个语节）",
            ),
        )
    }

    // ── 5. 末行孤字 ────────────────────────────────────────────────────────

    private fun checkOrphanLines(input: AuditInput): List<AuditIssue> {
        val out = ArrayList<AuditIssue>()
        input.paragraphs.forEachIndexed { index, lines ->
            val last = lines.lastOrNull { line -> line.text.any { !isBlankCell(it) } }
                ?: return@forEachIndexed
            val realChars = last.text.filter { !isBlankCell(it) }
            if (realChars.length == 1) {
                val ch = last.sourceText?.lastNonBlank() ?: realChars[0]
                out += AuditIssue(
                    kind = AuditIssueKind.ORPHAN_LINE,
                    message = "第 ${index + 1} 段末行只剩 1 个字「$ch」，属孤字",
                    page = last.page,
                    line = last.lineInPage,
                )
            }
        }
        return out
    }

    // ── 工具 ───────────────────────────────────────────────────────────────

    private fun isBlankCell(ch: Char): Boolean =
        ch == ' ' || ch == '\u2800' || ch == '\t'

    private fun String.firstNonBlank(): Char? = firstOrNull { !isBlankCell(it) }

    private fun String.lastNonBlank(): Char? = lastOrNull { !isBlankCell(it) }
}
